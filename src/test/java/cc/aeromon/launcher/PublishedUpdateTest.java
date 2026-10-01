package cc.aeromon.launcher;
import java.nio.file.*;
import com.google.gson.*;
import java.util.*;

/** Explicit release smoke check against signed public update artifacts. */
public final class PublishedUpdateTest {
    public static void main(String[] args)throws Exception {
        Path signed=Path.of(args[0]),base=Path.of(args[1]),output=Path.of(args[2]);Files.createDirectories(output);
        byte[] raw=Files.readAllBytes(signed.resolve("launcher-manifest.json"));
        var pointer=JsonParser.parseString(Files.readString(signed.resolve("launcher-channel.json"))).getAsJsonObject();
        var release=Pack.verify(raw,pointer,Pack.KEY);var events=new ArrayList<String>();
        for(var e:release.manifest().getAsJsonArray("files")){
            var file=e.getAsJsonObject();String name=file.get("path").getAsString();
            LauncherUpdate.downloadFile(file,base.resolve(name),output.resolve(name),message->{events.add(message);System.out.println(message);});
        }
        if(events.stream().noneMatch(s->s.contains("Downloading launcher patch")))throw new AssertionError("Published delta was not selected");
        if(events.stream().anyMatch(s->s.contains("verified full")))throw new AssertionError("Published delta fell back to a full download");
        Files.write(output.resolve("manifest.json"),raw);Files.copy(signed.resolve("launcher-channel.json"),output.resolve("channel.json"),StandardCopyOption.REPLACE_EXISTING);
        LauncherUpdate.verified(output);
        System.out.println("PASS: published signature, downloaded delta, reconstructed JAR hashes and embedded version "+release.version());
    }
}
