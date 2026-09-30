package cc.aeromon.launcher;

import com.google.gson.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

public class UpdaterTest {
    static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    static Pack.Release signed(JsonObject manifest,KeyPair key)throws Exception {
        byte[] raw=manifest.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);var signature=Signature.getInstance("Ed25519");signature.initSign(key.getPrivate());signature.update(raw);var pointer=new JsonObject();pointer.add("version",manifest.get("version"));pointer.addProperty("sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)));pointer.addProperty("signature",HexFormat.of().formatHex(signature.sign()));byte[] encoded=key.getPublic().getEncoded();String publicKey=HexFormat.of().formatHex(Arrays.copyOfRange(encoded,encoded.length-32,encoded.length));return Pack.verify(raw,pointer,publicKey);
    }
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("aeromon-updater-test");var pack=new Pack(home);var key=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();var manifest=new JsonObject();manifest.addProperty("schema",1);manifest.addProperty("version","test-1");var files=new JsonArray();manifest.add("files",files);
        byte[] content="pinned mod content".getBytes();Path staged=pack.state.resolve("staging/mods/example.jar");Files.createDirectories(staged.getParent());Files.write(staged,content);var file=new JsonObject();file.addProperty("path","mods/example.jar");file.addProperty("source","aeromon");file.addProperty("size",content.length);file.addProperty("sha256",Pack.hash(staged,"SHA-256"));files.add(file);
        Path save=pack.instance.resolve("saves/personal/level.dat");Files.createDirectories(save.getParent());Files.writeString(save,"player-owned");
        pack.install(signed(manifest,key),x->{});require(Files.readString(pack.instance.resolve("mods/example.jar")).equals(new String(content)),"Install failed");require(Files.readString(save).equals("player-owned"),"Save changed");
        for(String path:List.of("../escape","/absolute","mods/../../escape","C:/escape","saves/level.dat")){boolean rejected=false;try{pack.safe(pack.instance,path);}catch(Exception e){rejected=true;}require(rejected,"Unsafe path accepted: "+path);}
        var wrongKey=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();byte[] raw=manifest.toString().getBytes();var pointer=new JsonObject();pointer.add("version",manifest.get("version"));pointer.addProperty("sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)));pointer.addProperty("signature","00".repeat(64));boolean rejected=false;try{Pack.verify(raw,pointer,Pack.KEY);}catch(Exception e){rejected=true;}require(rejected,"Invalid signature accepted");
        Path rollback=pack.state.resolve("rollback-1234");Files.createDirectories(rollback.resolve("mods"));Files.writeString(rollback.resolve("mods/example.jar"),"previous");Files.writeString(pack.instance.resolve("mods/example.jar"),"interrupted");Files.writeString(pack.state.resolve("transaction.json"),"{\"rollback\":\"rollback-1234\",\"entries\":[{\"path\":\"mods/example.jar\",\"existed\":true},{\"path\":\"mods/new.jar\",\"existed\":false}]}");Files.writeString(pack.instance.resolve("mods/new.jar"),"partial");pack.recover();require(Files.readString(pack.instance.resolve("mods/example.jar")).equals("previous"),"Rollback failed");require(!Files.exists(pack.instance.resolve("mods/new.jar")),"Partial file remained");
        require(Files.readString(save).equals("player-owned"),"Recovery changed save");System.out.println("PASS: signature rejection, path containment, staged install, player save preservation, interrupted recovery");
    }
}
