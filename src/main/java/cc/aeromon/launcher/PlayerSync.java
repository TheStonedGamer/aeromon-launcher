package cc.aeromon.launcher;

import com.google.gson.*;
import java.io.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Flow;
import java.util.function.LongConsumer;
import java.util.zip.*;
import java.nio.charset.StandardCharsets;

/** Explicit, account-scoped cloud snapshots. Credentials never enter archives. */
final class PlayerSync {
    static final String ENDPOINT="https://panel.aeromon.cc/player-sync/v1/";
    static final long MAX_BYTES=1024L*1024*1024,MAX_EXPANDED=2L*1024*1024*1024;
    static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NEVER).build();
    final Pack pack; final Minecraft.Session session; final String branch; final Path state; final java.util.function.BiConsumer<Long,Long> progress;
    PlayerSync(Pack pack,Minecraft.Session session,String branch,java.util.function.BiConsumer<Long,Long> progress)throws IOException {
        if(session==null)throw new IOException("Sign in with Microsoft before using cloud storage");
        if(!Set.of("stable","test").contains(branch))throw new IOException("Cloud storage is available for Stable and Test");
        if(!session.uuid().matches("[a-fA-F0-9]{32}"))throw new IOException("Invalid Minecraft account");
        this.pack=pack;this.session=session;this.branch=branch;this.progress=progress;
        state=pack.state.resolve("player-sync").resolve(session.uuid().toLowerCase(Locale.ROOT));Files.createDirectories(state);
    }
    static boolean allowed(String name){
        if(name.isEmpty()||name.contains("\\")||name.contains(":")||name.startsWith("/")||Arrays.stream(name.split("/",-1)).anyMatch(x->x.isEmpty()||x.equals(".")||x.equals("..")))return false;
        return Set.of("options.txt","optionsof.txt","optionsshaders.txt").contains(name)||name.startsWith("journeymap/")||name.startsWith("config/journeymap/");
    }
    static boolean sharedTile(String name){return name.regionMatches(true,0,"journeymap/data/mp/Aeromon/",0,"journeymap/data/mp/Aeromon/".length())&&name.toLowerCase(Locale.ROOT).endsWith(".png");}
    void stopped()throws Exception {
        new CustomMods(pack).stopped();
        try(var processes=ProcessHandle.allProcesses()){
            if(processes.anyMatch(p->p.isAlive()&&p.info().arguments().map(args->Arrays.stream(args).anyMatch(a->a.equalsIgnoreCase(pack.instance.toString()))).orElse(false)))throw new IOException("Close Minecraft before transferring player data");
        }
        if(Files.isSymbolicLink(pack.instance))throw new IOException("Linked instance directories cannot be synced");
    }
    HttpRequest.Builder request(String suffix){return Net.request(ENDPOINT+branch+suffix).header("Authorization","Bearer "+session.token());}
    static void accepted(int code)throws IOException {
        if(code==200)return;
        throw new IOException(switch(code){case 401->"Minecraft sign-in expired; sign in again";case 409->"Cloud data changed on another computer. Restore its snapshot before uploading; your local files are unchanged.";case 413->"Snapshot exceeds the cloud storage size limit";case 404->"Cloud snapshot is unavailable";default->"Player cloud storage unavailable (HTTP "+code+")";});
    }
    JsonObject status()throws Exception {
        var response=HTTP.send(request("").GET().build(),HttpResponse.BodyHandlers.ofString());accepted(response.statusCode());return JsonParser.parseString(response.body()).getAsJsonObject();
    }
    JsonObject tilesRequest(String route,JsonObject body)throws Exception {
        var response=HTTP.send(request(route).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),HttpResponse.BodyHandlers.ofString());accepted(response.statusCode());return JsonParser.parseString(response.body()).getAsJsonObject();
    }
    static String hash(Path path)throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");try(var input=Files.newInputStream(path)){byte[] buffer=new byte[65536];int n;while((n=input.read(buffer))!=-1)digest.update(buffer,0,n);}return HexFormat.of().formatHex(digest.digest());
    }
    static String hashWithManifest(Path path,byte[] manifest)throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");try(var input=Files.newInputStream(path)){byte[] buffer=new byte[65536];int n;while((n=input.read(buffer))!=-1)digest.update(buffer,0,n);}digest.update((byte)0);digest.update(manifest);return HexFormat.of().formatHex(digest.digest());
    }
    static HttpRequest.BodyPublisher tracking(HttpRequest.BodyPublisher source,LongConsumer onProgress){
        return new HttpRequest.BodyPublisher(){
            public long contentLength(){return source.contentLength();}
            public void subscribe(Flow.Subscriber<? super java.nio.ByteBuffer> subscriber){
                source.subscribe(new Flow.Subscriber<>(){
                    public void onSubscribe(Flow.Subscription subscription){subscriber.onSubscribe(subscription);}
                    public void onNext(java.nio.ByteBuffer buffer){int bytes=buffer.remaining();onProgress.accept(bytes);subscriber.onNext(buffer);}
                    public void onError(Throwable error){subscriber.onError(error);}
                    public void onComplete(){subscriber.onComplete();}
                });
            }
        };
    }
    static Map<String,String> tileHashes(Path instance)throws Exception {
        Map<String,String> hashes=new TreeMap<>();long total=0;int count=0;
        try(var paths=Files.walk(instance)){
            for(Path file:paths.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).toList()){
                String name=instance.relativize(file).toString().replace('\\','/');if(sharedTile(name)){if(Files.isSymbolicLink(file))throw new IOException("Linked player data cannot be synced: "+name);if(++count>100000)throw new IOException("Player data exceeds the snapshot file limit");total+=Files.size(file);if(total>MAX_EXPANDED)throw new IOException("Player data exceeds the snapshot size limit");hashes.put(name,hash(file));}
            }
        }
        return hashes;
    }
    static void snapshot(Path instance,Path archive,Map<String,String> tiles,Set<String> includeTiles)throws Exception {
        long expanded=0;int count=0;
        try(var output=new ZipOutputStream(Files.newOutputStream(archive));var paths=Files.walk(instance)){
            for(Path file:paths.sorted().toList()){
                String name=instance.relativize(file).toString().replace('\\','/');
                if(!allowed(name))continue;
                if(sharedTile(name)&&tiles!=null&&!includeTiles.contains(tiles.get(name)))continue;
                if(Files.isSymbolicLink(file))throw new IOException("Linked player data cannot be synced: "+name);
                if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))continue;
                expanded+=Files.size(file);if(expanded>MAX_EXPANDED||++count>100000)throw new IOException("Player data exceeds the snapshot size limit");
                // Stable timestamps let identical snapshots keep identical revisions.
                var entry=new ZipEntry(name);entry.setTime(0);output.putNextEntry(entry);Files.copy(file,output);output.closeEntry();
            }
            if(tiles!=null){var entry=new ZipEntry("META-INF/aeromon-tiles.json");entry.setTime(0);output.putNextEntry(entry);output.write(tileManifest(tiles).toString().getBytes(StandardCharsets.UTF_8));output.closeEntry();}
        }
        if(Files.size(archive)>MAX_BYTES)throw new IOException("Compressed snapshot exceeds 1 GiB");
    }
    static JsonObject tileManifest(Map<String,String> tiles){JsonObject result=new JsonObject();for(var e:tiles.entrySet())result.addProperty(e.getKey(),e.getValue());return result;}
    void upload()throws Exception {
        stopped();
        if(!Files.isDirectory(pack.instance))throw new IOException("Install this branch before uploading player data");
        Path revisionFile=state.resolve("revision.txt");String revision=Files.exists(revisionFile)?Files.readString(revisionFile).trim():"";
        Path archive=Files.createTempFile(state,"upload-",".zip");
        try{
            Map<String,String> tiles=tileHashes(pack.instance);JsonArray requested=new JsonArray();new TreeSet<>(tiles.values()).forEach(requested::add);JsonObject check=new JsonObject();check.add("hashes",requested);
            JsonArray missingResponse=tilesRequest("/tiles/missing",check).getAsJsonArray("missing");Set<String> missing=new HashSet<>();missingResponse.forEach(v->missing.add(v.getAsString()));
            snapshot(pack.instance,archive,tiles,missing);stopped();
            long total=Files.size(archive);progress.accept(0L,total);
            var body=tracking(HttpRequest.BodyPublishers.ofFile(archive),sent->progress.accept(sent,total));
            var response=HTTP.send(request("/deduplicated").header("If-Match",revision).header("Content-Type","application/zip").PUT(body).build(),HttpResponse.BodyHandlers.ofString());
            accepted(response.statusCode());var result=JsonParser.parseString(response.body()).getAsJsonObject();Files.writeString(revisionFile,result.get("revision").getAsString());
        }finally{Files.deleteIfExists(archive);}
    }
    void restore()throws Exception {
        stopped();
        if(!Files.isDirectory(pack.instance))throw new IOException("Install this branch before restoring player data");
        String revision=status().get("revision").getAsString();if(revision.isEmpty())throw new IOException("No cloud snapshot exists for this branch yet");
        if(!revision.matches("[0-9a-f]{64}"))throw new IOException("Invalid cloud revision");
        Path archive=Files.createTempFile(state,"download-",".zip"),staging=Files.createTempDirectory(state,"restore-");
        try{
            var response=HTTP.send(request("/snapshot/"+revision).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
            long total=response.headers().firstValueAsLong("Content-Length").orElse(MAX_BYTES);progress.accept(0L,total);
            long archiveSize=0;
            try(var input=response.body();var output=Files.newOutputStream(archive)){
                accepted(response.statusCode());byte[] buffer=new byte[65536];int n;while((n=input.read(buffer))!=-1){if((archiveSize+=n)>MAX_BYTES)throw new IOException("Cloud snapshot exceeds the size limit");output.write(buffer,0,n);progress.accept(archiveSize,total);}
            }
            boolean deduplicated="2".equals(response.headers().firstValue("X-Aeromon-Snapshot-Format").orElse("1"));
            JsonObject manifest=new JsonObject();
            if(deduplicated){var manifestResponse=HTTP.send(request("/snapshot/"+revision+"/manifest").GET().build(),HttpResponse.BodyHandlers.ofString());accepted(manifestResponse.statusCode());manifest=JsonParser.parseString(manifestResponse.body()).getAsJsonObject();}
            if(deduplicated?!hashWithManifest(archive,manifest.toString().getBytes(StandardCharsets.UTF_8)).equals(revision):!hash(archive).equals(revision))throw new IOException("Cloud snapshot checksum mismatch");
            long expanded=0;Set<String> seen=new HashSet<>();
            try(var input=new ZipInputStream(Files.newInputStream(archive))){ZipEntry entry;while((entry=input.getNextEntry())!=null){
                String name=entry.getName();if(entry.isDirectory()||!allowed(name)||!seen.add(name.toLowerCase(Locale.ROOT))||seen.size()>100000)throw new IOException("Unsafe cloud snapshot path");
                Path target=staging.resolve(name).normalize();if(!target.startsWith(staging))throw new IOException("Unsafe cloud snapshot path");Files.createDirectories(target.getParent());
                try(var output=Files.newOutputStream(target)){byte[] buffer=new byte[65536];int n;while((n=input.read(buffer))!=-1){if((expanded+=n)>MAX_EXPANDED)throw new IOException("Expanded snapshot exceeds the size limit");output.write(buffer,0,n);}}
            }}
            if(deduplicated&&!manifest.isEmpty()){
                var tilesResponse=HTTP.send(request("/snapshot/"+revision+"/tiles").GET().build(),HttpResponse.BodyHandlers.ofInputStream());accepted(tilesResponse.statusCode());long tileTotal=tilesResponse.headers().firstValueAsLong("Content-Length").orElse(MAX_BYTES);Path tileArchive=Files.createTempFile(state,"tiles-",".zip");long tileBytes=0;
                try{
                try(var input=tilesResponse.body();var output=Files.newOutputStream(tileArchive)){byte[] buffer=new byte[65536];int n;while((n=input.read(buffer))!=-1){if((tileBytes+=n)>MAX_BYTES)throw new IOException("Shared map tiles exceed the cloud storage size limit");output.write(buffer,0,n);progress.accept(archiveSize+tileBytes,total+tileTotal);}}
                Map<String,List<String>> pathsByHash=new HashMap<>();for(var item:manifest.entrySet()){if(!sharedTile(item.getKey())||!item.getValue().getAsString().matches("[0-9a-f]{64}"))throw new IOException("Invalid shared map tile manifest");pathsByHash.computeIfAbsent(item.getValue().getAsString(),k->new ArrayList<>()).add(item.getKey());}
                Set<String> received=new HashSet<>();try(var input=new ZipInputStream(Files.newInputStream(tileArchive))){ZipEntry entry;while((entry=input.getNextEntry())!=null){String digest=entry.getName();if(entry.isDirectory()||!pathsByHash.containsKey(digest)||!received.add(digest))throw new IOException("Unsafe shared map tile archive");Path tile=Files.createTempFile(state,"tile-",".part");try{try(var output=Files.newOutputStream(tile)){byte[] buffer=new byte[65536];int n;while((n=input.read(buffer))!=-1)output.write(buffer,0,n);}if(!hash(tile).equals(digest))throw new IOException("Shared map tile checksum mismatch");long tileSize=Files.size(tile);for(String name:pathsByHash.get(digest)){if(!seen.add(name.toLowerCase(Locale.ROOT))||(expanded+=tileSize)>MAX_EXPANDED)throw new IOException("Duplicate or oversized shared map tile path");Path target=staging.resolve(name).normalize();if(!target.startsWith(staging))throw new IOException("Unsafe shared map tile path");Files.createDirectories(target.getParent());Files.copy(tile,target,StandardCopyOption.REPLACE_EXISTING);}}finally{Files.deleteIfExists(tile);}}
                }
                if(!received.equals(pathsByHash.keySet()))throw new IOException("Cloud snapshot is missing shared map tiles");
                }finally{Files.deleteIfExists(tileArchive);}
            }
            stopped();
            // Keep the complete previous collection; overlay cloud files transactionally.
            Path backup=state.resolve("before-restore-"+System.currentTimeMillis()+".zip");snapshot(pack.instance,backup,null,Set.of());
            List<Path> copied=new ArrayList<>(),previous=new ArrayList<>();Path rollback=Files.createTempDirectory(state,"rollback-");
            try(var paths=Files.walk(staging)){
                for(Path source:paths.filter(Files::isRegularFile).toList()){
                    Path relative=staging.relativize(source),target=pack.instance.resolve(relative);
                    for(Path parent=target;parent!=null&&parent.startsWith(pack.instance);parent=parent.getParent())if(Files.isSymbolicLink(parent))throw new IOException("Linked player data cannot be replaced");
                    if(Files.exists(target)){Path old=rollback.resolve(relative);Files.createDirectories(old.getParent());Files.copy(target,old);previous.add(relative);}
                    copied.add(relative);Files.createDirectories(target.getParent());Path pending=Files.createTempFile(target.getParent(),".aeromon-sync-",".part");
                    try{Files.copy(source,pending,StandardCopyOption.REPLACE_EXISTING);Files.move(pending,target,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(pending);}
                }
                Files.writeString(state.resolve("revision.txt"),revision);
            }catch(Exception failure){
                for(Path relative:copied){Path target=pack.instance.resolve(relative);if(previous.contains(relative))Files.copy(rollback.resolve(relative),target,StandardCopyOption.REPLACE_EXISTING);else Files.deleteIfExists(target);}throw failure;
            }finally{BranchReset.deleteTree(rollback);}
        }finally{Files.deleteIfExists(archive);BranchReset.deleteTree(staging);}
    }
}
