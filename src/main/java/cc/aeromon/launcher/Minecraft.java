package cc.aeromon.launcher;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.awt.Desktop;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.io.*;
import java.util.zip.*;

final class Minecraft {
    private record Nbt(int type,Object value) {}
    // Session tokens remain in memory and are never written to pack files or logs.
    record Session(String name,String uuid,String token,String refreshToken){}
    final Path home, root; final Consumer<String> progress;
    Minecraft(Path home,Consumer<String> progress){this.home=home;this.root=home.resolve("minecraft");this.progress=progress;}
    static String encode(String s){return URLEncoder.encode(s,StandardCharsets.UTF_8);}
    static JsonObject post(String url,String type,String body)throws Exception {
        java.net.http.HttpResponse<String> response;
        try {
            response=Net.HTTP.send(Net.request(url).header("Content-Type",type).POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        } catch(java.io.IOException failure) {
            String host=URI.create(url).getHost();
            boolean dns=false;
            for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof java.nio.channels.UnresolvedAddressException || cause instanceof java.net.UnknownHostException)dns=true;
            throw new IOException(dns?"Sign-in could not resolve "+host+". Check your DNS connection, then retry sign-in.":"Sign-in could not connect to "+host+". Check your connection, then retry.",failure);
        }
        if(response.statusCode()!=200)throw new IOException("Sign-in service rejected the request (HTTP "+response.statusCode()+", "+URI.create(url).getHost()+"): "+rejectionDetail(response.body()));
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }
    private static String rejectionDetail(String body) {
        String detail="No provider error message was returned.";
        try {
            JsonObject response=JsonParser.parseString(body).getAsJsonObject();
            for(String key:new String[]{"errorMessage","message"})if(response.has(key)&&response.get(key).isJsonPrimitive()){
                detail=response.get(key).getAsString();break;
            }
        } catch(Exception ignored) { /* Do not print raw provider responses. */ }
        detail=detail.replaceAll("(?i)eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+","[redacted token]")
            .replaceAll("(?i)XBL3\\.0\\s+[^\\s\\\"}]+","XBL3.0 [redacted]")
            .replaceAll("[\\r\\n\\t]+"," ").trim();
        return detail.length()>240?detail.substring(0,240):detail;
    }
    static Session login(String clientId,Consumer<String> progress)throws Exception {
        String verifier=Base64.getUrlEncoder().withoutPadding().encodeToString(random(48));String challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));String state=HexFormat.of().formatHex(random(24));
        var callback=new CompletableFuture<String>();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);String redirect="http://127.0.0.1:"+server.getAddress().getPort();
        server.createContext("/",exchange->{
            try{
                Map<String,String> query=new HashMap<>();String raw=exchange.getRequestURI().getRawQuery();if(raw!=null)for(String pair:raw.split("&")){String[] parts=pair.split("=",2);query.put(URLDecoder.decode(parts[0],StandardCharsets.UTF_8),parts.length>1?URLDecoder.decode(parts[1],StandardCharsets.UTF_8):"");}
                if(!state.equals(query.get("state"))){exchange.sendResponseHeaders(400,-1);return;}
                if(query.containsKey("code"))callback.complete(query.get("code"));else callback.completeExceptionally(new IOException("Microsoft sign-in was cancelled or denied"));
                byte[] message="Aeromon sign-in received. You can return to the launcher.".getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","text/plain; charset=utf-8");exchange.sendResponseHeaders(200,message.length);exchange.getResponseBody().write(message);
            }finally{exchange.close();}
        });server.start();
        try {
            String scope="XboxLive.signin offline_access";
            String url="https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize?client_id="+encode(clientId)+"&response_type=code&redirect_uri="+encode(redirect)+"&scope="+encode(scope)+"&state="+state+"&code_challenge="+challenge+"&code_challenge_method=S256&prompt=select_account";
            Desktop.getDesktop().browse(URI.create(url));progress.accept("Complete Microsoft sign-in in your browser, then return here.");
            String code=callback.get(10,TimeUnit.MINUTES);
            var ms=post("https://login.microsoftonline.com/consumers/oauth2/v2.0/token","application/x-www-form-urlencoded","client_id="+encode(clientId)+"&grant_type=authorization_code&code="+encode(code)+"&redirect_uri="+encode(redirect)+"&code_verifier="+encode(verifier));
            var gson=new Gson();var props=new JsonObject();props.addProperty("AuthMethod","RPS");props.addProperty("SiteName","user.auth.xboxlive.com");props.addProperty("RpsTicket","d="+ms.get("access_token").getAsString());var body=new JsonObject();body.add("Properties",props);body.addProperty("RelyingParty","http://auth.xboxlive.com");body.addProperty("TokenType","JWT");
            var xbox=post("https://user.auth.xboxlive.com/user/authenticate","application/json",gson.toJson(body));
            props=new JsonObject();props.addProperty("SandboxId","RETAIL");var tokens=new JsonArray();tokens.add(xbox.get("Token"));props.add("UserTokens",tokens);body=new JsonObject();body.add("Properties",props);body.addProperty("RelyingParty","rp://api.minecraftservices.com/");body.addProperty("TokenType","JWT");
            var xsts=post("https://xsts.auth.xboxlive.com/xsts/authorize","application/json",gson.toJson(body));String uhs=xsts.getAsJsonObject("DisplayClaims").getAsJsonArray("xui").get(0).getAsJsonObject().get("uhs").getAsString();
            body=new JsonObject();body.addProperty("identityToken","XBL3.0 x="+uhs+";"+xsts.get("Token").getAsString());var mc=post("https://api.minecraftservices.com/authentication/login_with_xbox","application/json",gson.toJson(body));String token=mc.get("access_token").getAsString();
            var profileResponse=Net.HTTP.send(Net.request("https://api.minecraftservices.com/minecraft/profile").header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofString());
            if(profileResponse.statusCode()!=200)throw new IOException("This Microsoft account has no accessible Minecraft Java profile");
            var profile=JsonParser.parseString(profileResponse.body()).getAsJsonObject();return new Session(profile.get("name").getAsString(),profile.get("id").getAsString(),token,ms.has("refresh_token")?ms.get("refresh_token").getAsString():null);
        }finally{server.stop(0);}
    }
    static Session refresh(Path home,String clientId,String refreshToken,Consumer<String> progress)throws Exception {
        var ms=post("https://login.microsoftonline.com/consumers/oauth2/v2.0/token","application/x-www-form-urlencoded","client_id="+encode(clientId)+"&grant_type=refresh_token&refresh_token="+encode(refreshToken)+"&scope="+encode("XboxLive.signin offline_access"));
        // Preserve the rotated Microsoft token even if a later Xbox/Minecraft request fails.
        if(ms.has("refresh_token"))CredentialStore.save(home,ms.get("refresh_token").getAsString());
        var gson=new Gson();var props=new JsonObject();props.addProperty("AuthMethod","RPS");props.addProperty("SiteName","user.auth.xboxlive.com");props.addProperty("RpsTicket","d="+ms.get("access_token").getAsString());var body=new JsonObject();body.add("Properties",props);body.addProperty("RelyingParty","http://auth.xboxlive.com");body.addProperty("TokenType","JWT");
        var xbox=post("https://user.auth.xboxlive.com/user/authenticate","application/json",gson.toJson(body));
        props=new JsonObject();props.addProperty("SandboxId","RETAIL");var tokens=new JsonArray();tokens.add(xbox.get("Token"));props.add("UserTokens",tokens);body=new JsonObject();body.add("Properties",props);body.addProperty("RelyingParty","rp://api.minecraftservices.com/");body.addProperty("TokenType","JWT");
        var xsts=post("https://xsts.auth.xboxlive.com/xsts/authorize","application/json",gson.toJson(body));String uhs=xsts.getAsJsonObject("DisplayClaims").getAsJsonArray("xui").get(0).getAsJsonObject().get("uhs").getAsString();
        body=new JsonObject();body.addProperty("identityToken","XBL3.0 x="+uhs+";"+xsts.get("Token").getAsString());var mc=post("https://api.minecraftservices.com/authentication/login_with_xbox","application/json",gson.toJson(body));String token=mc.get("access_token").getAsString();
        var profileResponse=Net.HTTP.send(Net.request("https://api.minecraftservices.com/minecraft/profile").header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofString());
        if(profileResponse.statusCode()!=200)throw new IOException("This Microsoft account has no accessible Minecraft Java profile");
        var profile=JsonParser.parseString(profileResponse.body()).getAsJsonObject();return new Session(profile.get("name").getAsString(),profile.get("id").getAsString(),token,ms.has("refresh_token")?ms.get("refresh_token").getAsString():refreshToken);
    }
    static Session restore(Path home,String clientId,Consumer<String> progress)throws Exception {
        String token=CredentialStore.load(home);return token==null?null:refresh(home,clientId,token,progress);
    }
    static byte[] random(int count){byte[] bytes=new byte[count];new SecureRandom().nextBytes(bytes);return bytes;}
    Path java()throws Exception{return JavaRuntime.ensure(home,progress);}
    Path gameJava()throws Exception{
        Path executable=java();
        if(os().equals("windows")){
            Path windowed=executable.resolveSibling("javaw.exe");
            if(!Files.isRegularFile(windowed))throw new IOException("Windowed Java runtime is missing. Repair the Java runtime and retry.");
            return windowed;
        }
        return executable;
    }
    static String os(){String value=System.getProperty("os.name").toLowerCase();return value.contains("win")?"windows":value.contains("mac")?"osx":"linux";}
    void artifact(JsonObject download,Path target)throws Exception {
        String hash=download.has("sha1")?download.get("sha1").getAsString():null;
        if(Files.exists(target)&&hash!=null&&Pack.hash(target,"SHA-1").equals(hash))return;
        progress.accept("Installing runtime · "+target.getFileName());Path temp=target.resolveSibling(target.getFileName()+".part");Net.download(download.get("url").getAsString(),temp);
        if(hash!=null&&!Pack.hash(temp,"SHA-1").equals(hash))throw new SecurityException("Runtime checksum mismatch: "+target.getFileName());
        Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);
    }
    JsonObject vanilla(String version)throws Exception { return vanilla(version,root); }
    JsonObject vanilla(String version,Path runtimeRoot)throws Exception {
        var list=Net.json("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
        for(var value:list.getAsJsonArray("versions")){var entry=value.getAsJsonObject();if(entry.get("id").getAsString().equals(version)){
            Path file=runtimeRoot.resolve("versions/"+version+"/"+version+".json");artifact(entry,file);return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        }}throw new IOException("Minecraft version unavailable");
    }
    void prepare(JsonObject pack)throws Exception { prepare(pack,root); }
    static boolean neoForgeInstalled(Path metadataFile,Path readyMarker,String version) {
        if(!Files.isRegularFile(metadataFile)||!Files.isRegularFile(readyMarker))return false;
        try {
            JsonObject metadata=JsonParser.parseString(Files.readString(metadataFile)).getAsJsonObject();
            return metadata.has("id")&&metadata.get("id").getAsString().equals("neoforge-"+version)
                &&metadata.has("mainClass")&&!metadata.get("mainClass").getAsString().isBlank()
                &&metadata.has("libraries")&&!metadata.getAsJsonArray("libraries").isEmpty()
                &&Files.readString(readyMarker).trim().equals(version);
        } catch(Exception ignored) { return false; }
    }
    void prepare(JsonObject pack,Path runtimeRoot)throws Exception {
        var sharedPacks=new ResourcePacks(ResourcePacks.baseFor(home));sharedPacks.ensureDefaults(pack);sharedPacks.sync(home.resolve("instance"));
        String minecraft=pack.get("minecraft").getAsString(),neo=pack.get("neoforge").getAsString();if(!minecraft.matches("[0-9.]+")||!neo.matches("[0-9.]+"))throw new IOException("Unsupported runtime version");
        Files.createDirectories(runtimeRoot);var base=vanilla(minecraft,runtimeRoot);artifact(base.getAsJsonObject("downloads").getAsJsonObject("client"),runtimeRoot.resolve("versions/"+minecraft+"/"+minecraft+".jar"));
        libraries(base,runtimeRoot);
        var index=base.getAsJsonObject("assetIndex");Path indexPath=runtimeRoot.resolve("assets/indexes/"+index.get("id").getAsString()+".json");artifact(index,indexPath);var objects=JsonParser.parseString(Files.readString(indexPath)).getAsJsonObject().getAsJsonObject("objects");int count=0;
        for(var entry:objects.entrySet()){var object=entry.getValue().getAsJsonObject();String hash=object.get("hash").getAsString();var download=new JsonObject();download.addProperty("url","https://resources.download.minecraft.net/"+hash.substring(0,2)+"/"+hash);download.addProperty("sha1",hash);artifact(download,runtimeRoot.resolve("assets/objects/"+hash.substring(0,2)+"/"+hash));if(++count%100==0)progress.accept("Minecraft assets · "+count+" / "+objects.size());}
        Path neoJson=runtimeRoot.resolve("versions/neoforge-"+neo+"/neoforge-"+neo+".json");
        Path ready=home.resolve("launcher/neoforge-"+neo+"-"+Integer.toHexString(runtimeRoot.toString().hashCode())+".ready");
        boolean neoInstalled=neoForgeInstalled(neoJson,ready,neo);
        if(!neoInstalled){
            String url="https://maven.neoforged.net/releases/net/neoforged/neoforge/"+neo+"/neoforge-"+neo+"-installer.jar";Path installer=home.resolve("launcher/neoforge-installer.jar");var download=new JsonObject();download.addProperty("url",url);download.addProperty("sha1",new String(Net.bytes(url+".sha1"),StandardCharsets.US_ASCII).trim());artifact(download,installer);
            // NeoForge's client installer requires a launcher profile marker in the isolated runtime root.
            Path profiles=runtimeRoot.resolve("launcher_profiles.json");if(!Files.exists(profiles))Files.writeString(profiles,"{\"profiles\":{},\"selectedProfile\":null}");
            progress.accept("Installing NeoForge "+neo);Path log=home.resolve("launcher/neoforge-install.log");var process=new ProcessBuilder(java().toString(),"-jar",installer.toString(),"--installClient",runtimeRoot.toString()).directory(runtimeRoot.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if(!process.waitFor(15,TimeUnit.MINUTES)){process.destroy();throw new IOException("NeoForge installer timed out");}if(process.exitValue()!=0)throw new IOException("NeoForge installation failed. See "+log);
            Files.writeString(ready,neo);
        } else if(!Files.isRegularFile(ready))Files.writeString(ready,neo);
        JsonObject neoVersion=JsonParser.parseString(Files.readString(neoJson)).getAsJsonObject();if(!neoVersion.has("mainClass")||!neoVersion.has("libraries"))throw new IOException("NeoForge version metadata is incomplete");libraries(neoVersion,runtimeRoot);
    }
    /** Copy only manifest-owned client files into the official launcher's game directory. */
    void syncOfficialInstance(Pack.Release release, Path gameDir) throws Exception {
        new ResourcePacks(ResourcePacks.baseFor(home)).sync(gameDir);
        Path source=home.resolve("instance").toAbsolutePath().normalize();
        Path target=gameDir.toAbsolutePath().normalize();
        if(source.equals(target))return;
        Files.createDirectories(target);
        Pack pack=new Pack(home);
        Set<String> desired=new HashSet<>();
        for(var element:release.manifest().getAsJsonArray("files")) {
            JsonObject file=element.getAsJsonObject();String name=file.get("path").getAsString();
            Path from=pack.resolveSafe(source,name),to=pack.resolveSafe(target,name);
            if(Pack.isPlayerDataPath(name))continue;
            desired.add(name.toLowerCase(Locale.ROOT));
            if(!Pack.matches(from,file))throw new IOException("Pack file is missing or changed: "+name+". Reinstall Aeromon and retry.");
            if(Pack.matches(to,file))continue;
            Files.createDirectories(to.getParent());Path temp=to.resolveSibling(to.getFileName()+".aeromon-part");Files.copy(from,temp,StandardCopyOption.REPLACE_EXISTING);Files.move(temp,to,StandardCopyOption.REPLACE_EXISTING);
        }
        Path optionalState=home.resolve("launcher/official-optional-files.json");Set<String> optionalNames=new HashSet<>();Path optionalMods=home.resolve("launcher/optional-mods");if(Files.isDirectory(optionalMods))try(var files=Files.list(optionalMods)){for(Path record:files.filter(Files::isRegularFile).toList()){String name=record.getFileName().toString();Path enabled=source.resolve("mods").resolve(name);if(!Files.isRegularFile(enabled)||Files.mismatch(record,enabled)!=-1)continue;optionalNames.add(name.toLowerCase(Locale.ROOT));Path official=target.resolve("mods").resolve(name);Files.createDirectories(official.getParent());Files.copy(enabled,official,StandardCopyOption.REPLACE_EXISTING);}}if(Files.exists(optionalState))for(JsonElement old:JsonParser.parseString(Files.readString(optionalState)).getAsJsonArray()){String name=old.getAsString();if(!optionalNames.contains(name.toLowerCase(Locale.ROOT)))Files.deleteIfExists(target.resolve("mods").resolve(name));}JsonArray optionalOwned=new JsonArray();optionalNames.forEach(optionalOwned::add);Pack.atomic(optionalState,optionalOwned.toString().getBytes(StandardCharsets.UTF_8));
        Path custom=home.resolve("launcher/custom-mods"),customState=home.resolve("launcher/official-custom-files.json");Set<String> customNames=new HashSet<>();
        if(Files.isDirectory(custom))try(var files=Files.list(custom)){for(Path original:files.filter(Files::isRegularFile).toList()){
            String name=original.getFileName().toString();Path enabled=source.resolve("mods").resolve(name);if(!Files.exists(enabled))continue;customNames.add(name.toLowerCase(Locale.ROOT));
            Path official=target.resolve("mods").resolve(name);Files.createDirectories(official.getParent());Files.copy(enabled,official,StandardCopyOption.REPLACE_EXISTING);
        }}
        if(Files.exists(customState))for(JsonElement old:JsonParser.parseString(Files.readString(customState)).getAsJsonArray()){
            String name=old.getAsString();if(!customNames.contains(name.toLowerCase(Locale.ROOT)))Files.deleteIfExists(target.resolve("mods").resolve(name));
        }
        JsonArray customs=new JsonArray();customNames.forEach(customs::add);Pack.atomic(customState,customs.toString().getBytes(StandardCharsets.UTF_8));
        // Remove only files recorded as Aeromon-owned by the previous official sync.
        Path state=home.resolve("launcher/official-files.json");
        if(Files.exists(state))for(JsonElement old:JsonParser.parseString(Files.readString(state)).getAsJsonArray()) {
            String name=old.getAsString();if(desired.contains(name.toLowerCase(Locale.ROOT))||Pack.isPlayerDataPath(name))continue;
            Path file=pack.safe(target,name);Files.deleteIfExists(file);
        }
        JsonArray owned=new JsonArray();desired.forEach(owned::add);Pack.atomic(state,owned.toString().getBytes(StandardCharsets.UTF_8));
        for(String preference:List.of("servers.dat","options.txt")){
            Path player=source.resolve(preference),official=target.resolve(preference);
            if(!Files.exists(official)&&Files.isRegularFile(player)){Files.createDirectories(official.getParent());Files.copy(player,official);}
        }
        mergeServerList(target.resolve("servers.dat"));
    }
    private static Nbt readTag(DataInputStream in,int type)throws IOException {
        return switch(type){
            case 1 -> new Nbt(type,in.readByte()); case 2 -> new Nbt(type,in.readShort()); case 3 -> new Nbt(type,in.readInt()); case 4 -> new Nbt(type,in.readLong());
            case 5 -> new Nbt(type,in.readFloat()); case 6 -> new Nbt(type,in.readDouble()); case 7 -> {int n=in.readInt();if(n<0||n>64*1024*1024)throw new IOException("Invalid NBT byte array");byte[] b=new byte[n];in.readFully(b);yield new Nbt(type,b);}
            case 8 -> new Nbt(type,in.readUTF()); case 9 -> {int t=in.readUnsignedByte(),n=in.readInt();if(t>12||n<0||n>1_000_000)throw new IOException("Invalid NBT list");List<Nbt> a=new ArrayList<>(n);for(int i=0;i<n;i++)a.add(readTag(in,t));yield new Nbt(type,new Object[]{t,a});}
            case 10 -> {Map<String,Nbt> m=new LinkedHashMap<>();for(int t;(t=in.readUnsignedByte())!=0;){if(t>12)throw new IOException("Invalid NBT tag");m.put(in.readUTF(),readTag(in,t));}yield new Nbt(type,m);}
            case 11 -> {int n=in.readInt();if(n<0||n>1_000_000)throw new IOException("Invalid NBT int array");int[] a=new int[n];for(int i=0;i<n;i++)a[i]=in.readInt();yield new Nbt(type,a);}
            case 12 -> {int n=in.readInt();if(n<0||n>1_000_000)throw new IOException("Invalid NBT long array");long[] a=new long[n];for(int i=0;i<n;i++)a[i]=in.readLong();yield new Nbt(type,a);}
            default -> throw new IOException("Unsupported NBT tag "+type);
        };
    }
    private static void writePayload(DataOutputStream out,Nbt tag)throws IOException {
        switch(tag.type()){
            case 1 -> out.writeByte((Byte)tag.value());case 2 -> out.writeShort((Short)tag.value());case 3 -> out.writeInt((Integer)tag.value());case 4 -> out.writeLong((Long)tag.value());case 5 -> out.writeFloat((Float)tag.value());case 6 -> out.writeDouble((Double)tag.value());
            case 7 -> {byte[] b=(byte[])tag.value();out.writeInt(b.length);out.write(b);}case 8 -> out.writeUTF((String)tag.value());
            case 9 -> {Object[] l=(Object[])tag.value();out.writeByte((Integer)l[0]);@SuppressWarnings("unchecked") List<Nbt> values=(List<Nbt>)l[1];out.writeInt(values.size());for(Nbt n:values)writePayload(out,n);}
            case 10 -> {for(var e:((Map<String,Nbt>)tag.value()).entrySet()){out.writeByte(e.getValue().type());out.writeUTF(e.getKey());writePayload(out,e.getValue());}out.writeByte(0);}
            case 11 -> {int[] a=(int[])tag.value();out.writeInt(a.length);for(int x:a)out.writeInt(x);}case 12 -> {long[] a=(long[])tag.value();out.writeInt(a.length);for(long x:a)out.writeLong(x);}default -> throw new IOException("Unsupported NBT tag");
        }
    }
    private static void mergeServerList(Path file)throws Exception {
        Map<String,Nbt> root=new LinkedHashMap<>();List<Nbt> servers=new ArrayList<>();
        boolean modified=false,compressed=false;
        if(Files.exists(file))try{
            byte[] header=new byte[2];try(InputStream raw=Files.newInputStream(file)){int n=raw.read(header);compressed=n==2&&(header[0]&255)==0x1f&&(header[1]&255)==0x8b;}
            InputStream input=Files.newInputStream(file);if(compressed)input=new GZIPInputStream(input);
            try(var in=new DataInputStream(input)){
            int type=in.readUnsignedByte();if(type!=10)throw new IOException("Invalid Minecraft server list");in.readUTF();Nbt parsed=readTag(in,type);root.putAll((Map<String,Nbt>)parsed.value());
            Nbt existing=root.get("servers");if(existing!=null&&existing.type()==9){Object[] list=(Object[])existing.value();@SuppressWarnings("unchecked")List<Nbt> values=(List<Nbt>)list[1];servers.addAll(values);}
            }
        } catch(IOException invalid){Path backup=file.resolveSibling("servers.dat.aeromon-backup");if(!Files.exists(backup))Files.copy(file,backup);root.clear();servers.clear();compressed=false;System.err.println("Existing Minecraft server list is malformed; preserved backup at "+backup+" and will create the Aeromon entry in the separate game directory.");}
        for(String[] entry:new String[][]{{"Aeromon","mc.aeromon.cc"},{"Aeromon Test","test.aeromon.cc"}}){boolean found=false;for(Nbt server:servers){Map<String,Nbt> values=(Map<String,Nbt>)server.value();if(values.get("ip")!=null&&entry[1].equals(values.get("ip").value())){found=true;if(!entry[0].equals(values.get("name").value())){values.put("name",new Nbt(8,entry[0]));modified=true;}if(values.get("acceptTextures")==null||values.get("acceptTextures").type()!=1||!Byte.valueOf((byte)1).equals(values.get("acceptTextures").value())){values.put("acceptTextures",new Nbt(1,(byte)1));modified=true;}break;}}if(!found){Map<String,Nbt> added=new LinkedHashMap<>();added.put("name",new Nbt(8,entry[0]));added.put("ip",new Nbt(8,entry[1]));added.put("acceptTextures",new Nbt(1,(byte)1));servers.add(new Nbt(10,added));modified=true;}}
        if(modified){root.put("servers",new Nbt(9,new Object[]{10,servers}));writeNbt(file,root,servers,compressed);}
    }
    private static void writeNbt(Path file,Map<String,Nbt> root,List<Nbt> servers,boolean compressed)throws Exception {
        root.put("servers",new Nbt(9,new Object[]{10,servers}));
        Path temp=file.resolveSibling("servers.dat.aeromon-part");OutputStream output=Files.newOutputStream(temp);if(compressed)output=new GZIPOutputStream(output);try(var out=new DataOutputStream(output)){out.writeByte(10);out.writeUTF("");writePayload(out,new Nbt(10,root));}Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);
    }
    static void verifyOfficialProfile(Path root,Path gameDir,JsonObject pack)throws Exception {
        JsonObject doc=JsonParser.parseString(Files.readString(root.resolve("launcher_profiles.json"))).getAsJsonObject();
        if(!"aeromon".equals(doc.get("selectedProfile").getAsString()))throw new IOException("Select the Aeromon profile in Minecraft Launcher.");
        JsonObject profile=doc.getAsJsonObject("profiles").getAsJsonObject("aeromon");String neo="neoforge-"+pack.get("neoforge").getAsString();
        if(profile==null||!neo.equals(profile.get("lastVersionId").getAsString())||!gameDir.toString().equals(Path.of(profile.get("gameDir").getAsString()).toAbsolutePath().normalize().toString()))throw new IOException("The Aeromon profile must use NeoForge and the Aeromon game directory.");
        Path metadata=root.resolve("versions").resolve(neo).resolve(neo+".json");if(!Files.isRegularFile(metadata))throw new IOException("NeoForge metadata is missing from the official launcher root.");
        JsonObject version=JsonParser.parseString(Files.readString(metadata)).getAsJsonObject();if(!version.has("mainClass")||!version.has("libraries"))throw new IOException("NeoForge profile metadata is incomplete.");
        Path mods=gameDir.resolve("mods");long expected=pack.getAsJsonArray("files").asList().stream().map(JsonElement::getAsJsonObject).filter(f->f.get("path").getAsString().startsWith("mods/")&&f.get("path").getAsString().endsWith(".jar")).count();
        long present=Files.isDirectory(mods)?Files.list(mods).filter(p->p.toString().endsWith(".jar")).count():0;if(present<expected)throw new IOException("The official Aeromon instance is missing mod files; reinstall the pack before Play.");
    }
    static boolean allowed(JsonObject item){
        if(!item.has("rules"))return true;boolean allowed=false;
        for(var element:item.getAsJsonArray("rules")){var rule=element.getAsJsonObject();boolean matches=true;
            if(rule.has("os")){var system=rule.getAsJsonObject("os");if(system.has("name"))matches &= system.get("name").getAsString().equals(os());if(system.has("arch"))matches &= System.getProperty("os.arch").matches(system.get("arch").getAsString());if(system.has("version"))matches &= System.getProperty("os.version").matches(system.get("version").getAsString());}
            if(rule.has("features"))for(var feature:rule.getAsJsonObject("features").entrySet())matches &= !feature.getValue().getAsBoolean();
            if(matches)allowed=rule.get("action").getAsString().equals("allow");
        }return allowed;
    }
    Path libPath(String name){String[] parts=name.split(":");return root.resolve("libraries/"+parts[0].replace('.','/')+"/"+parts[1]+"/"+parts[2]+"/"+parts[1]+"-"+parts[2]+(parts.length>3?"-"+parts[3]:"")+".jar");}
    void libraries(JsonObject version)throws Exception {
        libraries(version,root);
    }
    void libraries(JsonObject version,Path runtimeRoot)throws Exception {
        if(!version.has("libraries"))return;
        for(var element:version.getAsJsonArray("libraries")){var lib=element.getAsJsonObject();if(!allowed(lib))continue;
            if(lib.has("downloads")){var downloads=lib.getAsJsonObject("downloads");if(downloads.has("artifact")){var a=downloads.getAsJsonObject("artifact");artifact(a,runtimeRoot.resolve("libraries/"+a.get("path").getAsString()));}
                if(lib.has("natives")&&lib.getAsJsonObject("natives").has(os())){String classifier=lib.getAsJsonObject("natives").getAsString().replace("${arch}",System.getProperty("os.arch").contains("64")?"64":"32");var a=downloads.getAsJsonObject("classifiers").getAsJsonObject(classifier);Path jar=runtimeRoot.resolve("libraries/"+a.get("path").getAsString());artifact(a,jar);extract(jar,runtimeRoot.resolve("natives"));}
            } else {String[] parts=lib.get("name").getAsString().split(":");Path path=runtimeRoot.resolve("libraries/"+parts[0].replace('.','/')+"/"+parts[1]+"/"+parts[2]+"/"+parts[1]+"-"+parts[2]+(parts.length>3?"-"+parts[3]:"")+".jar");if(!Files.exists(path))throw new IOException("NeoForge installer did not produce "+path.getFileName());}
        }
    }
    static void extract(Path zip,Path root)throws Exception {Files.createDirectories(root);try(var in=new ZipInputStream(Files.newInputStream(zip))){for(ZipEntry entry;(entry=in.getNextEntry())!=null;){if(entry.isDirectory()||entry.getName().startsWith("META-INF/"))continue;Path path=root.resolve(entry.getName()).normalize();if(!path.startsWith(root))throw new IOException("Unsafe native archive");Files.createDirectories(path.getParent());Files.copy(in,path,StandardCopyOption.REPLACE_EXISTING);}}}
    List<String> arguments(JsonObject version,String kind,Map<String,String> variables){var args=new ArrayList<String>();if(!version.has("arguments"))return args;var values=version.getAsJsonObject("arguments").getAsJsonArray(kind);if(values==null)return args;
        for(var element:values){if(element.isJsonPrimitive())args.add(element.getAsString());else{var object=element.getAsJsonObject();if(allowed(object)){var value=object.get("value");if(value.isJsonArray())value.getAsJsonArray().forEach(x->args.add(x.getAsString()));else args.add(value.getAsString());}}}
        args.replaceAll(s->{for(var e:variables.entrySet())s=s.replace("${"+e.getKey()+"}",e.getValue());if(s.contains("${"))throw new IllegalArgumentException("Unknown Minecraft argument: "+s);return s;});return args;
    }
    Process launch(JsonObject pack,Session session,int memory)throws Exception {
        return launch(pack,session,memory,"mc.aeromon.cc");
    }
    Process launch(JsonObject pack,Session session,int memory,String serverHost)throws Exception {
        if(!Set.of("mc.aeromon.cc","test.aeromon.cc").contains(serverHost))throw new IOException("Unsupported Aeromon server target");
        return launch(pack,session,memory,false,root,home.resolve("instance"),serverHost);
    }
    Process launchOffline(JsonObject pack,int memory)throws Exception {
        String name="AeromonTest";String uuid=UUID.nameUUIDFromBytes(("OfflinePlayer:"+name).getBytes(StandardCharsets.UTF_8)).toString().replace("-","");
        return launch(pack,new Session(name,uuid,"0",null),memory,true);
    }
    Process launch(JsonObject pack,Session session,int memory,boolean offline)throws Exception {
        return launch(pack,session,memory,offline,root,home.resolve("instance"));
    }
    Process launch(JsonObject pack,Session session,int memory,boolean offline,Path runtimeRoot,Path gameDir)throws Exception {
        return launch(pack,session,memory,offline,runtimeRoot,gameDir,"mc.aeromon.cc");
    }
    Process launch(JsonObject pack,Session session,int memory,boolean offline,Path runtimeRoot,Path gameDir,String serverHost)throws Exception {
        prepare(pack,runtimeRoot);String mc=pack.get("minecraft").getAsString(),neo=pack.get("neoforge").getAsString();var base=JsonParser.parseString(Files.readString(runtimeRoot.resolve("versions/"+mc+"/"+mc+".json"))).getAsJsonObject();var mod=JsonParser.parseString(Files.readString(runtimeRoot.resolve("versions/neoforge-"+neo+"/neoforge-"+neo+".json"))).getAsJsonObject();
        Map<String,Path> libraries=new LinkedHashMap<>();for(var version:List.of(base,mod))for(var e:version.getAsJsonArray("libraries")){var lib=e.getAsJsonObject();if(!allowed(lib))continue;String name=lib.get("name").getAsString();Path path=lib.has("downloads")&&lib.getAsJsonObject("downloads").has("artifact")?runtimeRoot.resolve("libraries/"+lib.getAsJsonObject("downloads").getAsJsonObject("artifact").get("path").getAsString()):runtimeRoot.resolve("libraries/"+name.split(":")[0].replace('.','/')+"/"+name.split(":")[1]+"/"+name.split(":")[2]+"/"+name.split(":")[1]+"-"+name.split(":")[2]+(name.split(":").length>3?"-"+name.split(":")[3]:"")+".jar");if(Files.exists(path))libraries.put(name.split(":")[0]+":"+name.split(":")[1]+(name.split(":").length>3?":"+name.split(":")[3]:""),path);}
        String cp=String.join(File.pathSeparator,libraries.values().stream().map(Path::toString).toList());
        Map<String,String> vars=new HashMap<>();vars.put("auth_player_name",session.name());vars.put("auth_uuid",session.uuid());vars.put("auth_access_token",session.token());vars.put("auth_xuid","");vars.put("clientid","");vars.put("user_type","msa");vars.put("version_name","neoforge-"+neo);vars.put("version_type","release");vars.put("game_directory",gameDir.toString());vars.put("assets_root",runtimeRoot.resolve("assets").toString());vars.put("assets_index_name",base.getAsJsonObject("assetIndex").get("id").getAsString());vars.put("natives_directory",runtimeRoot.resolve("natives").toString());vars.put("launcher_name","Aeromon");vars.put("launcher_version","0.1.0");vars.put("classpath",cp);vars.put("library_directory",runtimeRoot.resolve("libraries").toString());vars.put("classpath_separator",File.pathSeparator);
        var args=new ArrayList<String>();args.add(gameJava().toString());args.add("-Xmx"+memory+"M");args.addAll(arguments(base,"jvm",vars));args.addAll(arguments(mod,"jvm",vars));if(os().equals("osx"))args.add("-XstartOnFirstThread");args.add(mod.get("mainClass").getAsString());args.addAll(arguments(base,"game",vars));args.addAll(arguments(mod,"game",vars));if(offline){args.add("--disableMultiplayer");args.add("--disableChat");}else{if(!Set.of("mc.aeromon.cc","test.aeromon.cc").contains(serverHost))throw new IOException("Unsupported Aeromon server target");mergeServerList(gameDir.resolve("servers.dat"));args.add("--quickPlayMultiplayer");args.add(serverHost);}
        Files.createDirectories(runtimeRoot.resolve("natives"));Files.createDirectories(gameDir.resolve("logs"));Path log=gameDir.resolve("logs/launcher-console.log");progress.accept("Launching Aeromon…");var process=new ProcessBuilder(args).directory(gameDir.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        Path pid=home.resolve("launcher/game.pid");Files.writeString(pid,Long.toString(process.pid()));process.onExit().thenRun(()->{try{Files.deleteIfExists(pid);}catch(IOException ignored){}});return process;
    }
}
