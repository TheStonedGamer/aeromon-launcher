package cc.aeromon.launcher;

import com.google.gson.JsonParser;
import java.nio.file.*;

public final class NeoForgeInstallDetectionTest {
    static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}

    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("aeromon-neoforge-ready");
        Path metadata=root.resolve("versions/neoforge-21.1.248/neoforge-21.1.248.json");
        Path marker=root.resolve("launcher/neoforge-21.1.248.ready");
        Files.createDirectories(metadata.getParent());Files.createDirectories(marker.getParent());
        Files.writeString(metadata,"{\"id\":\"neoforge-21.1.248\",\"mainClass\":\"cpw.mods.bootstraplauncher.BootstrapLauncher\",\"libraries\":[{\"name\":\"net.neoforged:neoforge:21.1.248:client\"}]}");
        Files.writeString(marker,"21.1.248");
        require(!Files.exists(metadata.getParent().resolve("neoforge-21.1.248.jar")),"fixture unexpectedly has the nonexistent version JAR");
        require(Minecraft.neoForgeInstalled(metadata,marker,"21.1.248"),"successful installer layout was treated as missing");
        Files.writeString(marker,"21.1.250");
        require(!Minecraft.neoForgeInstalled(metadata,marker,"21.1.248"),"stale ready marker was accepted");
        Files.writeString(marker,"21.1.248");Files.writeString(metadata,JsonParser.parseString("{}").toString());
        require(!Minecraft.neoForgeInstalled(metadata,marker,"21.1.248"),"incomplete version metadata was accepted");
        System.out.println("PASS: NeoForge readiness uses the installer marker and version metadata without expecting a JAR under versions");
    }
}
