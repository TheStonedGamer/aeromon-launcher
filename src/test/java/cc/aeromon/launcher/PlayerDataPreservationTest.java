package cc.aeromon.launcher;

import com.google.gson.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

public class PlayerDataPreservationTest {
    static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}

    static Pack.Release signed(JsonObject manifest,KeyPair key)throws Exception {
        byte[] raw=manifest.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var signature=Signature.getInstance("Ed25519");signature.initSign(key.getPrivate());signature.update(raw);
        var pointer=new JsonObject();pointer.add("version",manifest.get("version"));
        pointer.addProperty("sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)));
        pointer.addProperty("signature",HexFormat.of().formatHex(signature.sign()));
        byte[] encoded=key.getPublic().getEncoded();String publicKey=HexFormat.of().formatHex(Arrays.copyOfRange(encoded,encoded.length-32,encoded.length));
        return Pack.verify(raw,pointer,publicKey);
    }

    static JsonObject manifest(String version,JsonArray files){
        var manifest=new JsonObject();manifest.addProperty("schema",1);manifest.addProperty("version",version);manifest.add("files",files);return manifest;
    }

    static JsonObject managedFile(Pack pack,Path staged,String path,String content)throws Exception {
        Path file=staged.resolve(path);Files.createDirectories(file.getParent());Files.writeString(file,content);
        var entry=new JsonObject();entry.addProperty("path",path);entry.addProperty("source","aeromon");
        entry.addProperty("size",Files.size(file));entry.addProperty("sha256",Pack.hash(file,"SHA-256"));return entry;
    }

    static JsonObject playerFile(String path){var entry=new JsonObject();entry.addProperty("path",path);return entry;}

    static void writePlayerData(Path root,Map<String,String> files)throws Exception {
        for(var entry:files.entrySet()){
            Path file=root.resolve(entry.getKey());Files.createDirectories(file.getParent());Files.writeString(file,entry.getValue());
        }
    }

    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("aeromon-player-data-test");Pack pack=new Pack(home);
        var key=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Map<String,String> playerFiles=new LinkedHashMap<>();
        playerFiles.put("saves/personal/level.dat","personal world");
        playerFiles.put("screenshots/keep.png","screenshot");
        playerFiles.put("logs/keep.log","client log");
        playerFiles.put("journeymap/data/waypoints.dat","JourneyMap waypoints");
        playerFiles.put("config/journeymap/client.json","JourneyMap preferences");
        playerFiles.put("XaeroWaypoints/world.txt","Xaero waypoints");
        playerFiles.put("XaeroWorldMap/cache.dat","Xaero map data");
        playerFiles.put("voxelmap/cache.dat","VoxelMap data");
        playerFiles.put("resourcepacks/private.zip","personal resource pack");
        playerFiles.put("shaderpacks/private/shader.txt","personal shader config");
        playerFiles.put("schematics/house.litematic","personal schematic");
        playerFiles.put("options.txt","player options");
        playerFiles.put("servers.dat","player server list");
        writePlayerData(pack.instance,playerFiles);

        var initialFiles=new JsonArray();
        Path staged=pack.state.resolve("staging");
        initialFiles.add(managedFile(pack,staged,"mods/example.jar","mod version one"));
        for(String path:playerFiles.keySet())initialFiles.add(playerFile(path));
        pack.install(signed(manifest("test-1",initialFiles),key),x->{});
        for(var entry:playerFiles.entrySet())require(Files.readString(pack.instance.resolve(entry.getKey())).equals(entry.getValue()),"Initial install changed "+entry.getKey());

        var updateFiles=new JsonArray();
        updateFiles.add(managedFile(pack,staged,"mods/example.jar","mod version two"));
        updateFiles.add(playerFile("config/journeymap/client.json"));
        updateFiles.add(playerFile("options.txt"));
        updateFiles.add(playerFile("servers.dat"));
        var update=signed(manifest("test-2",updateFiles),key);
        pack.install(update,x->{});
        require(Files.readString(pack.instance.resolve("mods/example.jar")).equals("mod version two"),"Pack-owned mod was not updated");
        for(var entry:playerFiles.entrySet())require(Files.readString(pack.instance.resolve(entry.getKey())).equals(entry.getValue()),"Pack update changed or removed "+entry.getKey());

        Path rollback=pack.state.resolve("rollback-1234");
        writePlayerData(rollback,Map.of("mods/example.jar","old mod","config/journeymap/client.json","old map data"));
        Files.writeString(pack.instance.resolve("mods/example.jar"),"interrupted mod update");
        Files.writeString(pack.instance.resolve("config/journeymap/client.json"),"current JourneyMap data");
        Files.writeString(pack.state.resolve("transaction.json"),"{\"rollback\":\"rollback-1234\",\"entries\":[{\"path\":\"mods/example.jar\",\"existed\":true},{\"path\":\"config/journeymap/client.json\",\"existed\":true}]}");
        pack.recover();
        require(Files.readString(pack.instance.resolve("mods/example.jar")).equals("old mod"),"Rollback failed for managed file");
        require(Files.readString(pack.instance.resolve("config/journeymap/client.json")).equals("current JourneyMap data"),"Recovery overwrote player data");
        pack.install(update,x->{});

        Path official=home.resolve("official-game");Files.createDirectories(official);
        Map<String,String> officialPlayerFiles=Map.of(
            "journeymap/data/old-waypoints.dat","official JourneyMap waypoints",
            "config/journeymap/client.json","official JourneyMap preferences",
            "XaeroWaypoints/world.txt","official Xaero waypoints",
            "resourcepacks/private.zip","official resource pack",
            "shaderpacks/private/shader.txt","official shader config",
            "options.txt","official player options"
        );
        writePlayerData(official,officialPlayerFiles);
        Files.write(official.resolve("servers.dat"),new byte[]{10,0,0,0});
        Files.createDirectories(official.resolve("mods"));Files.writeString(official.resolve("mods/retired.jar"),"old Aeromon-owned mod");
        var oldOwned=new JsonArray();officialPlayerFiles.keySet().forEach(oldOwned::add);oldOwned.add("mods/retired.jar");
        Files.writeString(pack.state.resolve("official-files.json"),oldOwned.toString());
        new Minecraft(home,x->{}).syncOfficialInstance(update,official);
        for(var entry:officialPlayerFiles.entrySet())require(Files.readString(official.resolve(entry.getKey())).equals(entry.getValue()),"Official sync changed or removed "+entry.getKey());
        require(!Files.exists(official.resolve("mods/retired.jar")),"Official sync did not remove stale pack-owned file");
        JsonArray currentOwned=JsonParser.parseString(Files.readString(pack.state.resolve("official-files.json"))).getAsJsonArray();
        require(currentOwned.size()==1&&currentOwned.get(0).getAsString().equals("mods/example.jar"),"Official sync tracked player data as pack-owned");

        System.out.println("PASS: pack updates, legacy cleanup, recovery, and official sync preserve JourneyMap, Xaero, VoxelMap, saves, screenshots, configs, options, server list, resources, shaders, and schematics");
    }
}
