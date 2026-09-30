package cc.aeromon.launcher;

import com.google.gson.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.io.*;
import java.net.http.HttpResponse;
import java.util.function.Consumer;

/** Signed JAR updates live in user storage, so native installs need no elevation. */
final class LauncherUpdate {
    static final String VERSION=LauncherUpdate.class.getPackage().getImplementationVersion()==null?"1.0.0":LauncherUpdate.class.getPackage().getImplementationVersion();
    static final String FEED="https://github.com/TheStonedGamer/aeromon-launcher/releases/latest/download/launcher-channel.json";
    static Path java(){return Path.of(System.getProperty("java.home"),"bin",Minecraft.os().equals("windows")?"java.exe":"java");}
    static Pack.Release verified(Path directory)throws Exception {
        var pointer=JsonParser.parseString(Files.readString(directory.resolve("channel.json"))).getAsJsonObject();
        var release=Pack.verify(Files.readAllBytes(directory.resolve("manifest.json")),pointer,Pack.KEY);
        for(var element:release.manifest().getAsJsonArray("files")){var file=element.getAsJsonObject();String name=file.get("path").getAsString();if(!Set.of("aeromon-launcher.jar","gson.jar").contains(name))throw new SecurityException("Unsupported launcher update file");if(!Pack.matches(directory.resolve(name),file))throw new SecurityException("Launcher update checksum mismatch");}
        if(!Files.isRegularFile(directory.resolve("aeromon-launcher.jar"))||!Files.isRegularFile(directory.resolve("gson.jar")))throw new IOException("Incomplete launcher update");
        try(var jar=new java.util.jar.JarFile(directory.resolve("aeromon-launcher.jar").toFile())){if(!release.version().equals(jar.getManifest().getMainAttributes().getValue("Implementation-Version")))throw new SecurityException("Launcher JAR version does not match its release");}return release;
    }
    static boolean newer(String next,String current){String[] a=next.split("\\."),b=current.split("\\.");for(int i=0;i<3;i++){int x=Integer.parseInt(a[i]),y=Integer.parseInt(b[i]);if(x!=y)return x>y;}return false;}
    static boolean bootstrap(Path home)throws Exception {
        Path active=home.resolve("launcher/active-launcher.txt");if(!Files.exists(active))return false;
        String version=Files.readString(active).trim();if(!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+"))throw new SecurityException("Invalid active launcher version");
        Path directory=home.resolve("launcher/updates/"+version);Pack.Release release=verified(directory);if(!release.version().equals(version))throw new SecurityException("Launcher version mismatch");
        if(!newer(version,VERSION))return false;
        new ProcessBuilder(java().toString(),"-jar",directory.resolve("aeromon-launcher.jar").toString(),"--home",home.toString()).start();return true;
    }
    static boolean check(Path home,Consumer<String> progress)throws Exception {
        var response=Net.HTTP.send(Net.request(FEED).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        if(response.statusCode()==404)return false; // No public launcher release has been published yet.
        if(response.statusCode()!=200)throw new IOException("Launcher update service is unavailable");
        var pointer=JsonParser.parseString(new String(response.body(),StandardCharsets.UTF_8)).getAsJsonObject();String version=pointer.get("version").getAsString();if(!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+"))throw new SecurityException("Invalid launcher release version");if(!newer(version,VERSION))return false;
        byte[] raw=Net.bytes(pointer.get("manifestUrl").getAsString());var release=Pack.verify(raw,pointer,Pack.KEY);Path directory=home.resolve("launcher/updates/"+version);Files.createDirectories(directory);
        progress.accept("Downloading Aeromon launcher "+version);
        for(var element:release.manifest().getAsJsonArray("files")){var file=element.getAsJsonObject();String name=file.get("path").getAsString();if(!Set.of("aeromon-launcher.jar","gson.jar").contains(name))throw new SecurityException("Unsupported launcher update file");Path target=directory.resolve(name);if(!Pack.matches(target,file)){Path temp=directory.resolve(name+".part");Net.download(file.get("url").getAsString(),temp);if(!Pack.matches(temp,file))throw new SecurityException("Launcher download checksum mismatch");Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);}}
        Files.write(directory.resolve("manifest.json"),raw);Files.write(directory.resolve("channel.json"),response.body());verified(directory);
        // The replacement starts only after this process exits. No running executable is overwritten.
        new ProcessBuilder(java().toString(),"-jar",directory.resolve("aeromon-launcher.jar").toString(),"--activate-update",Long.toString(ProcessHandle.current().pid()),"--home",home.toString(),"--update-version",version).start();return true;
    }
    static void activate(Path home,String version,long pid)throws Exception {
        if(!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+"))throw new SecurityException("Invalid launcher version");Path directory=home.resolve("launcher/updates/"+version);verified(directory);
        var parent=ProcessHandle.of(pid);if(parent.isPresent())parent.get().onExit().get(2,java.util.concurrent.TimeUnit.MINUTES);
        Path pointer=home.resolve("launcher/active-launcher.txt"),temp=pointer.resolveSibling("active-launcher.txt.part");Files.writeString(temp,version);Files.move(temp,pointer,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        new ProcessBuilder(java().toString(),"-jar",directory.resolve("aeromon-launcher.jar").toString(),"--home",home.toString()).start();
    }
}
