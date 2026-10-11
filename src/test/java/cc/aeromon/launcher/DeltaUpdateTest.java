package cc.aeromon.launcher;
import java.nio.file.*;
import java.io.*;
import java.util.zip.GZIPOutputStream;
import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.*;

public final class DeltaUpdateTest {
    static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static void patch(Path p,long size,long offset,long length,byte[] addition)throws Exception {
        try(var out=new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(p)))){
            out.writeLong(0x4145524f444c5431L);out.writeLong(size);out.writeByte(1);out.writeLong(offset);out.writeLong(length);
            if(addition.length>0){out.writeByte(2);out.writeLong(addition.length);out.write(addition);}out.writeByte(0);
        }
    }
    static JsonObject metadata(Path p)throws Exception{var m=new JsonObject();m.addProperty("size",Files.size(p));m.addProperty("sha256",Pack.hash(p,"SHA-256"));return m;}
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("aeromon-delta-test"),base=root.resolve("base"),target=root.resolve("target"),delta=root.resolve("patch"),output=root.resolve("output");
        Files.writeString(base,"original-content");Files.writeString(target,"original-content-new");patch(delta,Files.size(target),0,Files.size(base),"-new".getBytes());
        DeltaPatch.apply(base,delta,output,Files.size(target));require(Files.mismatch(output,target)==-1,"copy/add reconstruction failed");
        patch(delta,3,100,3,new byte[0]);boolean rejected=false;try{DeltaPatch.apply(base,delta,output,3);}catch(IOException e){rejected=true;}require(rejected,"out-of-range copy accepted");
        patch(delta,3,0,4,new byte[0]);rejected=false;try{DeltaPatch.apply(base,delta,output,3);}catch(IOException e){rejected=true;}require(rejected,"oversized output accepted");
        patch(delta,Files.size(target),0,Files.size(base),"-new".getBytes());
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{byte[] bytes=Files.readAllBytes(exchange.getRequestURI().getPath().equals("/patch")?delta:target);exchange.sendResponseHeaders(200,bytes.length);try(var body=exchange.getResponseBody()){body.write(bytes);}});server.start();
        try{
            String url="http://127.0.0.1:"+server.getAddress().getPort();var file=metadata(target);file.addProperty("path","aeromon-launcher.jar");file.addProperty("url",url+"/full");
            var p=metadata(delta);p.addProperty("format","aeromon-copy-add-v1");p.addProperty("baseSha256",Pack.hash(base,"SHA-256"));p.addProperty("url",url+"/patch");var patches=new JsonArray();patches.add(p);file.add("patches",patches);var events=new ArrayList<String>();
            LauncherUpdate.downloadFile(file,base,output,events::add);require(Files.mismatch(output,target)==-1,"downloaded patch failed");require(events.stream().anyMatch(x->x.contains("Downloading launcher patch")),"delta not used");
            p.addProperty("sha256","00".repeat(32));events.clear();LauncherUpdate.downloadFile(file,base,output,events::add);require(Files.mismatch(output,target)==-1,"full fallback failed");require(events.stream().anyMatch(x->x.contains("verified full")),"corrupt patch did not fall back");
            Files.writeString(base,"different-base");events.clear();LauncherUpdate.downloadFile(file,base,output,events::add);require(Files.mismatch(output,target)==-1,"wrong-base fallback failed");require(events.isEmpty(),"wrong base attempted patch");
            Files.copy(target,base,StandardCopyOption.REPLACE_EXISTING);LauncherUpdate.downloadFile(file,base,output,events::add);require(Files.mismatch(output,target)==-1,"unchanged reuse failed");
        }finally{server.stop(0);}
        System.out.println("PASS: delta reconstruction, bounds, hash rejection, corrupt-patch fallback, wrong-base fallback, unchanged reuse");
    }
}
