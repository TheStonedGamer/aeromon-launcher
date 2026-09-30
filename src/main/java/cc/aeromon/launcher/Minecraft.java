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
    // Session tokens remain in memory and are never written to pack files or logs.
    record Session(String name,String uuid,String token){}
    final Path home, root; final Consumer<String> progress;
    Minecraft(Path home,Consumer<String> progress){this.home=home;this.root=home.resolve("minecraft");this.progress=progress;}
    static String encode(String s){return URLEncoder.encode(s,StandardCharsets.UTF_8);}
    static JsonObject post(String url,String type,String body)throws Exception {
        var response=Net.HTTP.send(Net.request(url).header("Content-Type",type).POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()!=200)throw new IOException("Sign-in service rejected the request (HTTP "+response.statusCode()+", "+URI.create(url).getHost()+"). Check Aeromon application approval and account access.");
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }
    static Session login(String clientId,Consumer<String> progress)throws Exception {
        String verifier=Base64.getUrlEncoder().withoutPadding().encodeToString(random(48));String challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));String state=HexFormat.of().formatHex(random(24));
        var callback=new CompletableFuture<String>();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);String redirect="http://localhost:"+server.getAddress().getPort();
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
            var profile=JsonParser.parseString(profileResponse.body()).getAsJsonObject();return new Session(profile.get("name").getAsString(),profile.get("id").getAsString(),token);
        }finally{server.stop(0);}
    }
    static byte[] random(int count){byte[] bytes=new byte[count];new SecureRandom().nextBytes(bytes);return bytes;}
    Path java(){return Path.of(System.getProperty("java.home"),"bin",os().equals("windows")?"java.exe":"java");}
    static String os(){String value=System.getProperty("os.name").toLowerCase();return value.contains("win")?"windows":value.contains("mac")?"osx":"linux";}
    void artifact(JsonObject download,Path target)throws Exception {
        String hash=download.has("sha1")?download.get("sha1").getAsString():null;
        if(Files.exists(target)&&hash!=null&&Pack.hash(target,"SHA-1").equals(hash))return;
        progress.accept("Installing runtime · "+target.getFileName());Path temp=target.resolveSibling(target.getFileName()+".part");Net.download(download.get("url").getAsString(),temp);
        if(hash!=null&&!Pack.hash(temp,"SHA-1").equals(hash))throw new SecurityException("Runtime checksum mismatch: "+target.getFileName());
        Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);
    }
    JsonObject vanilla(String version)throws Exception {
        var list=Net.json("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
        for(var value:list.getAsJsonArray("versions")){var entry=value.getAsJsonObject();if(entry.get("id").getAsString().equals(version)){
            Path file=root.resolve("versions/"+version+"/"+version+".json");artifact(entry,file);return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        }}throw new IOException("Minecraft version unavailable");
    }
    void prepare(JsonObject pack)throws Exception {
        String minecraft=pack.get("minecraft").getAsString(),neo=pack.get("neoforge").getAsString();if(!minecraft.matches("[0-9.]+")||!neo.matches("[0-9.]+"))throw new IOException("Unsupported runtime version");
        Files.createDirectories(root);var base=vanilla(minecraft);artifact(base.getAsJsonObject("downloads").getAsJsonObject("client"),root.resolve("versions/"+minecraft+"/"+minecraft+".jar"));
        libraries(base);
        var index=base.getAsJsonObject("assetIndex");Path indexPath=root.resolve("assets/indexes/"+index.get("id").getAsString()+".json");artifact(index,indexPath);var objects=JsonParser.parseString(Files.readString(indexPath)).getAsJsonObject().getAsJsonObject("objects");int count=0;
        for(var entry:objects.entrySet()){var object=entry.getValue().getAsJsonObject();String hash=object.get("hash").getAsString();var download=new JsonObject();download.addProperty("url","https://resources.download.minecraft.net/"+hash.substring(0,2)+"/"+hash);download.addProperty("sha1",hash);artifact(download,root.resolve("assets/objects/"+hash.substring(0,2)+"/"+hash));if(++count%100==0)progress.accept("Minecraft assets · "+count+" / "+objects.size());}
        Path neoJson=root.resolve("versions/neoforge-"+neo+"/neoforge-"+neo+".json");
        Path ready=home.resolve("launcher/neoforge-"+neo+".ready");
        if(!Files.exists(ready)){
            String url="https://maven.neoforged.net/releases/net/neoforged/neoforge/"+neo+"/neoforge-"+neo+"-installer.jar";Path installer=home.resolve("launcher/neoforge-installer.jar");var download=new JsonObject();download.addProperty("url",url);download.addProperty("sha1",new String(Net.bytes(url+".sha1"),StandardCharsets.US_ASCII).trim());artifact(download,installer);
            // NeoForge's client installer requires a launcher profile marker in the isolated runtime root.
            Path profiles=root.resolve("launcher_profiles.json");if(!Files.exists(profiles))Files.writeString(profiles,"{\"profiles\":{},\"selectedProfile\":null}");
            progress.accept("Installing NeoForge "+neo);Path log=home.resolve("launcher/neoforge-install.log");var process=new ProcessBuilder(java().toString(),"-jar",installer.toString(),"--installClient",root.toString()).directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if(!process.waitFor(15,TimeUnit.MINUTES)){process.destroy();throw new IOException("NeoForge installer timed out");}if(process.exitValue()!=0)throw new IOException("NeoForge installation failed. See "+log);
            Files.writeString(ready,neo);
        }
        libraries(JsonParser.parseString(Files.readString(neoJson)).getAsJsonObject());
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
        if(!version.has("libraries"))return;
        for(var element:version.getAsJsonArray("libraries")){var lib=element.getAsJsonObject();if(!allowed(lib))continue;
            if(lib.has("downloads")){var downloads=lib.getAsJsonObject("downloads");if(downloads.has("artifact")){var a=downloads.getAsJsonObject("artifact");artifact(a,root.resolve("libraries/"+a.get("path").getAsString()));}
                if(lib.has("natives")&&lib.getAsJsonObject("natives").has(os())){String classifier=lib.getAsJsonObject("natives").get(os()).getAsString().replace("${arch}",System.getProperty("os.arch").contains("64")?"64":"32");var a=downloads.getAsJsonObject("classifiers").getAsJsonObject(classifier);Path jar=root.resolve("libraries/"+a.get("path").getAsString());artifact(a,jar);extract(jar,root.resolve("natives"));}
            } else {Path path=libPath(lib.get("name").getAsString());if(!Files.exists(path))throw new IOException("NeoForge installer did not produce "+path.getFileName());}
        }
    }
    static void extract(Path zip,Path root)throws Exception {Files.createDirectories(root);try(var in=new ZipInputStream(Files.newInputStream(zip))){for(ZipEntry entry;(entry=in.getNextEntry())!=null;){if(entry.isDirectory()||entry.getName().startsWith("META-INF/"))continue;Path path=root.resolve(entry.getName()).normalize();if(!path.startsWith(root))throw new IOException("Unsafe native archive");Files.createDirectories(path.getParent());Files.copy(in,path,StandardCopyOption.REPLACE_EXISTING);}}}
    List<String> arguments(JsonObject version,String kind,Map<String,String> variables){var args=new ArrayList<String>();if(!version.has("arguments"))return args;var values=version.getAsJsonObject("arguments").getAsJsonArray(kind);if(values==null)return args;
        for(var element:values){if(element.isJsonPrimitive())args.add(element.getAsString());else{var object=element.getAsJsonObject();if(allowed(object)){var value=object.get("value");if(value.isJsonArray())value.getAsJsonArray().forEach(x->args.add(x.getAsString()));else args.add(value.getAsString());}}}
        args.replaceAll(s->{for(var e:variables.entrySet())s=s.replace("${"+e.getKey()+"}",e.getValue());if(s.contains("${"))throw new IllegalArgumentException("Unknown Minecraft argument: "+s);return s;});return args;
    }
    Process launch(JsonObject pack,Session session,int memory)throws Exception {
        prepare(pack);String mc=pack.get("minecraft").getAsString(),neo=pack.get("neoforge").getAsString();var base=JsonParser.parseString(Files.readString(root.resolve("versions/"+mc+"/"+mc+".json"))).getAsJsonObject();var mod=JsonParser.parseString(Files.readString(root.resolve("versions/neoforge-"+neo+"/neoforge-"+neo+".json"))).getAsJsonObject();
        Map<String,Path> libraries=new LinkedHashMap<>();for(var version:List.of(base,mod))for(var e:version.getAsJsonArray("libraries")){var lib=e.getAsJsonObject();if(!allowed(lib))continue;String name=lib.get("name").getAsString();Path path=lib.has("downloads")&&lib.getAsJsonObject("downloads").has("artifact")?root.resolve("libraries/"+lib.getAsJsonObject("downloads").getAsJsonObject("artifact").get("path").getAsString()):libPath(name);if(Files.exists(path))libraries.put(name.split(":")[0]+":"+name.split(":")[1],path);}
        libraries.put("minecraft",root.resolve("versions/"+mc+"/"+mc+".jar"));String cp=String.join(File.pathSeparator,libraries.values().stream().map(Path::toString).toList());
        Map<String,String> vars=new HashMap<>();vars.put("auth_player_name",session.name());vars.put("auth_uuid",session.uuid());vars.put("auth_access_token",session.token());vars.put("auth_xuid","");vars.put("clientid","");vars.put("user_type","msa");vars.put("version_name","neoforge-"+neo);vars.put("version_type","release");vars.put("game_directory",home.resolve("instance").toString());vars.put("assets_root",root.resolve("assets").toString());vars.put("assets_index_name",base.getAsJsonObject("assetIndex").get("id").getAsString());vars.put("natives_directory",root.resolve("natives").toString());vars.put("launcher_name","Aeromon");vars.put("launcher_version","0.1.0");vars.put("classpath",cp);vars.put("library_directory",root.resolve("libraries").toString());vars.put("classpath_separator",File.pathSeparator);
        var args=new ArrayList<String>();args.add(java().toString());args.add("-Xmx"+memory+"M");args.addAll(arguments(base,"jvm",vars));args.addAll(arguments(mod,"jvm",vars));if(os().equals("osx"))args.add("-XstartOnFirstThread");args.add(mod.get("mainClass").getAsString());args.addAll(arguments(base,"game",vars));args.addAll(arguments(mod,"game",vars));args.add("--quickPlayMultiplayer");args.add("mc.aeromon.cc");
        Files.createDirectories(root.resolve("natives"));Path log=home.resolve("launcher/game.log");progress.accept("Launching Aeromon…");var process=new ProcessBuilder(args).directory(home.resolve("instance").toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        Path pid=home.resolve("launcher/game.pid");Files.writeString(pid,Long.toString(process.pid()));process.onExit().thenRun(()->{try{Files.deleteIfExists(pid);}catch(IOException ignored){}});return process;
    }
}
