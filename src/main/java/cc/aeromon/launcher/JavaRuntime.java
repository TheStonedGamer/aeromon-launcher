package cc.aeromon.launcher;

import com.google.gson.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.concurrent.TimeUnit;

/** A private, checksum verified Temurin JRE; never installs system-wide Java. */
final class JavaRuntime {
    static Path ensure(Path home,Consumer<String> progress)throws Exception {
        Path storage=home.resolve("runtimes/java21");Path marker=storage.resolve("ready.json");
        if(Files.exists(marker)){var saved=JsonParser.parseString(Files.readString(marker)).getAsJsonObject();Path java=storage.resolve(saved.get("java").getAsString()).normalize();if(java.startsWith(storage)&&Files.isRegularFile(java))return java;}
        String os=Minecraft.os().equals("osx")?"mac":Minecraft.os();String arch=System.getProperty("os.arch").toLowerCase();arch=Set.of("aarch64","arm64").contains(arch)?"aarch64":Set.of("amd64","x86_64").contains(arch)?"x64":"unsupported";
        if(arch.equals("unsupported"))throw new IOException("Aeromon requires a supported 64-bit CPU");
        progress.accept("Finding the current Java 21 runtime");
        var response=Net.bytes("https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture="+arch+"&image_type=jre&os="+os+"&vendor=eclipse");
        var releases=JsonParser.parseString(new String(response,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonArray();if(releases.isEmpty())throw new IOException("No Java runtime is available for this platform");
        var asset=releases.get(0).getAsJsonObject().getAsJsonObject("binary").getAsJsonObject("package");
        Path archive=home.resolve("runtimes/java21-download"+(os.equals("windows")?".zip":".tar.gz"));
        progress.accept("Downloading Aeromon's private Java runtime");Net.download(asset.get("link").getAsString(),archive);
        if(!Pack.hash(archive,"SHA-256").equalsIgnoreCase(asset.get("checksum").getAsString()))throw new SecurityException("Java runtime checksum mismatch");
        Files.createDirectories(storage);
        if(os.equals("windows"))Minecraft.extract(archive,storage);
        else {
            Path listing=home.resolve("runtimes/archive-list.txt");var list=new ProcessBuilder("tar","-tzf",archive.toString()).redirectErrorStream(true).redirectOutput(listing.toFile()).start();if(!list.waitFor(60,TimeUnit.SECONDS)||list.exitValue()!=0)throw new IOException("Unable to read Java runtime archive");
            for(String name:Files.readAllLines(listing)){Path path=storage.resolve(name).normalize();if(!path.startsWith(storage)||name.startsWith("/"))throw new SecurityException("Unsafe runtime archive path");}
            var extraction=new ProcessBuilder("tar","-xzf",archive.toString(),"-C",storage.toString()).redirectErrorStream(true).redirectOutput(home.resolve("runtimes/extract.log").toFile()).start();if(!extraction.waitFor(120,TimeUnit.SECONDS)||extraction.exitValue()!=0)throw new IOException("Unable to extract Java runtime");
        }
        Path java;try(var files=Files.walk(storage)){java=files.filter(p->p.getFileName().toString().equals(os.equals("windows")?"java.exe":"java")&&p.getParent().getFileName().toString().equals("bin")).findFirst().orElseThrow(()->new IOException("Java runtime executable is missing"));}
        var test=new ProcessBuilder(java.toString(),"-version").redirectErrorStream(true).redirectOutput(home.resolve("runtimes/java-version.log").toFile()).start();if(!test.waitFor(30,TimeUnit.SECONDS)||test.exitValue()!=0)throw new IOException("Downloaded Java runtime did not start");
        JsonObject saved=new JsonObject();saved.addProperty("java",storage.relativize(java).toString());saved.addProperty("sha256",asset.get("checksum").getAsString());Files.writeString(marker,saved.toString());Files.deleteIfExists(archive);return java;
    }
}
