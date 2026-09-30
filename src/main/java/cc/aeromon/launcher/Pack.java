package cc.aeromon.launcher;

import com.google.gson.*;
import java.nio.file.*;
import java.nio.channels.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.function.Consumer;
import java.io.*;

final class Pack {
    static final String FEED = "https://panel.aeromon.cc/feed";
    // Pinned from the deployed signing key. Never trust a key downloaded alongside the release.
    static final String KEY = "1015bf1ab708746279cdf9b5c6d0ad700365db1c8ff8da62d70276c7656bea4d";
    final Path home, instance, state;
    record Release(JsonObject manifest, byte[] raw, String signature) { String version(){return manifest.get("version").getAsString();} }
    Pack(Path home) throws IOException { this.home=home.toAbsolutePath().normalize(); instance=this.home.resolve("instance"); state=this.home.resolve("launcher"); Files.createDirectories(instance); Files.createDirectories(state); }
    static String hash(Path file,String algorithm) throws Exception {
        var digest=MessageDigest.getInstance(algorithm);
        try(var in=Files.newInputStream(file)){byte[] buffer=new byte[65536]; for(int n;(n=in.read(buffer))!=-1;) digest.update(buffer,0,n);}
        return HexFormat.of().formatHex(digest.digest());
    }
    static Release verify(byte[] raw,JsonObject pointer,String key) throws Exception {
        if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)).equals(pointer.get("sha256").getAsString())) throw new SecurityException("Manifest checksum mismatch");
        byte[] prefix=HexFormat.of().parseHex("302a300506032b6570032100"); byte[] publicKey=HexFormat.of().parseHex(key);
        byte[] encoded=new byte[prefix.length+publicKey.length];System.arraycopy(prefix,0,encoded,0,prefix.length);System.arraycopy(publicKey,0,encoded,prefix.length,publicKey.length);
        var verifier=Signature.getInstance("Ed25519");verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded)));verifier.update(raw);
        if(!verifier.verify(HexFormat.of().parseHex(pointer.get("signature").getAsString()))) throw new SecurityException("Invalid release signature");
        var manifest=JsonParser.parseString(new String(raw,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        if(manifest.get("schema").getAsInt()!=1 || !manifest.get("version").equals(pointer.get("version"))) throw new SecurityException("Unsupported or mismatched release");
        return new Release(manifest,raw,pointer.get("signature").getAsString());
    }
    Release latest(String channel) throws Exception {
        if(!Set.of("stable","beta").contains(channel)) throw new IllegalArgumentException("Invalid channel");
        var pointer=Net.json(FEED+"/channels/"+channel+".json"); String version=pointer.get("version").getAsString();
        if(!version.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) throw new SecurityException("Invalid version");
        return verify(Net.bytes(FEED+"/releases/"+version+"/manifest.json"),pointer,KEY);
    }
    Path safe(Path root,String name) throws IOException {
        if(name.isBlank() || name.contains("\\") || name.contains(":") || name.startsWith("/") || Arrays.asList(name.split("/")).contains("..")) throw new IOException("Unsafe pack path: "+name);
        if(Set.of("saves","screenshots","logs","journeymap","launcher").contains(name.split("/")[0].toLowerCase(Locale.ROOT))) throw new IOException("Player data cannot be managed: "+name);
        Path path=root.resolve(name).normalize(); if(!path.startsWith(root) || path.equals(root)) throw new IOException("Unsafe pack path");
        for(Path current=path;current!=null && current.startsWith(root);current=current.getParent()) if(Files.isSymbolicLink(current)) throw new IOException("Symlink in pack path: "+name);
        return path;
    }
    static String expected(JsonObject file,String algo) {
        if(algo.equals("SHA-256") && file.has("sha256")) return file.get("sha256").getAsString();
        if(!file.has("hashes"))return null;
        var hashes=file.get("hashes");
        if(hashes.isJsonObject()) {String key=algo.equals("SHA-1")?"sha1":"sha256";return hashes.getAsJsonObject().has(key)?hashes.getAsJsonObject().get(key).getAsString():null;}
        for(var item:hashes.getAsJsonArray()) if(item.getAsJsonObject().get("algo").getAsInt()==1 && algo.equals("SHA-1"))return item.getAsJsonObject().get("value").getAsString();
        return null;
    }
    static boolean matches(Path path,JsonObject file)throws Exception {
        if(!Files.isRegularFile(path)||Files.size(path)!=file.get("size").getAsLong())return false;
        for(String algorithm:List.of("SHA-256","SHA-1")){String expected=expected(file,algorithm);if(expected!=null)return hash(path,algorithm).equalsIgnoreCase(expected);}
        throw new SecurityException("No checksum for "+file.get("path"));
    }
    String installed()throws IOException {Path p=state.resolve("installed.json");return Files.exists(p)?JsonParser.parseString(Files.readString(p)).getAsJsonObject().get("version").getAsString():"Not installed";}
    void install(Release release,Consumer<String> progress)throws Exception {
        try(var channel=FileChannel.open(state.resolve("install.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.tryLock()) {
            if(lock==null)throw new IOException("An installation is already running");
            Path gamePid=state.resolve("game.pid");
            if(Files.exists(gamePid)){long pid=Long.parseLong(Files.readString(gamePid).trim());if(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))throw new IOException("Close Minecraft before updating the pack");Files.delete(gamePid);}
            recover();
            Path staging=state.resolve("staging");Files.createDirectories(staging);
            Map<String,JsonObject> desired=new LinkedHashMap<>();
            for(var element:release.manifest.getAsJsonArray("files")){var file=element.getAsJsonObject();String path=file.get("path").getAsString();safe(instance,path);if(desired.putIfAbsent(path.toLowerCase(Locale.ROOT),file)!=null)throw new IOException("Duplicate pack path");}
            List<JsonObject> changed=new ArrayList<>();int done=0;
            for(var file:desired.values()){
                String name=file.get("path").getAsString();progress.accept("Checking "+(++done)+" / "+desired.size()+" · "+name);
                Path target=safe(instance,name); if(matches(target,file))continue;
                // Seed player preferences only on the first install.
                if(Set.of("options.txt","servers.dat").contains(name) && Files.exists(target))continue;
                Path staged=safe(staging,name);
                if(!matches(staged,file)){
                    String url=file.get("source").getAsString().equals("aeromon")?FEED+"/artifacts/"+file.get("sha256").getAsString():file.get("url").getAsString();
                    progress.accept("Downloading "+done+" / "+desired.size()+" · "+name);Net.download(url,staged);
                    if(!matches(staged,file)){Files.deleteIfExists(staged);throw new SecurityException("Checksum mismatch: "+name);}
                }
                changed.add(file);
            }
            List<String> paths=new ArrayList<>();for(var f:changed)paths.add(f.get("path").getAsString());
            Path installed=state.resolve("installed.json");
            if(Files.exists(installed))for(var f:JsonParser.parseString(Files.readString(installed)).getAsJsonObject().getAsJsonArray("files")){String name=f.getAsJsonObject().get("path").getAsString();if(!desired.containsKey(name.toLowerCase(Locale.ROOT))&&!Set.of("options.txt","servers.dat").contains(name))paths.add(name);}
            Path rollback=state.resolve("rollback-"+UUID.randomUUID());Files.createDirectories(rollback);
            JsonObject journal=new JsonObject();journal.addProperty("rollback",rollback.getFileName().toString());JsonArray entries=new JsonArray();
            for(String name:paths){Path target=safe(instance,name);JsonObject entry=new JsonObject();entry.addProperty("path",name);entry.addProperty("existed",Files.exists(target));entries.add(entry);if(Files.exists(target)){Path backup=safe(rollback,name);Files.createDirectories(backup.getParent());Files.copy(target,backup,StandardCopyOption.REPLACE_EXISTING);}}
            journal.addProperty("installedExisted",Files.exists(installed));if(Files.exists(installed))Files.copy(installed,rollback.resolve("installed.previous"));
            journal.add("entries",entries);atomic(state.resolve("transaction.json"),journal.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            try {
                for(String name:paths)Files.deleteIfExists(safe(instance,name));
                for(var file:changed){String name=file.get("path").getAsString();Path target=safe(instance,name);Files.createDirectories(target.getParent());Files.copy(safe(staging,name),target,StandardCopyOption.REPLACE_EXISTING);}
                atomic(installed,release.raw); Files.delete(state.resolve("transaction.json"));progress.accept("Aeromon "+release.version()+" installed");
            } catch(Exception e){recover();throw e;}
        }
    }
    void recover()throws Exception {
        Path journal=state.resolve("transaction.json");if(!Files.exists(journal))return;
        var data=JsonParser.parseString(Files.readString(journal)).getAsJsonObject();String folder=data.get("rollback").getAsString();if(!folder.matches("rollback-[a-f0-9-]+"))throw new IOException("Invalid recovery journal");
        for(var e:data.getAsJsonArray("entries")){var entry=e.getAsJsonObject();String name=entry.get("path").getAsString();Path target=safe(instance,name);if(entry.get("existed").getAsBoolean()){Files.createDirectories(target.getParent());Files.copy(safe(state.resolve(folder),name),target,StandardCopyOption.REPLACE_EXISTING);}else Files.deleteIfExists(target);}
        if(data.has("installedExisted")){if(data.get("installedExisted").getAsBoolean())atomic(state.resolve("installed.json"),Files.readAllBytes(state.resolve(folder).resolve("installed.previous")));else Files.deleteIfExists(state.resolve("installed.json"));}
        Files.delete(journal);
    }
    static void atomic(Path path,byte[] bytes)throws IOException {Path temp=path.resolveSibling(path.getFileName()+".tmp");Files.write(temp,bytes);Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
}
