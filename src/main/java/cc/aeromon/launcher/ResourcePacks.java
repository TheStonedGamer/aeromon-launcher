package cc.aeromon.launcher;

import com.google.gson.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.zip.ZipFile;
import java.io.IOException;

/** Shared ZIP collection; instance copies are tracked separately from player files. */
final class ResourcePacks {
    final Path base, storage;
    final Pack paths;
    ResourcePacks(Path base)throws IOException {
        this.base=base.toAbsolutePath().normalize();
        paths=new Pack(this.base);storage=this.base.resolve("shared/resourcepacks");Files.createDirectories(storage);
    }
    static Path baseFor(Path home){
        Path absolute=home.toAbsolutePath().normalize();
        String name=absolute.getFileName().toString();
        return name.equals("test")||name.equals("custom")?absolute.getParent():absolute;
    }
    List<String> list()throws IOException {
        try(var files=Files.list(storage)){return files.filter(Files::isRegularFile).filter(p->p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")).map(p->p.getFileName().toString()).sorted(String.CASE_INSENSITIVE_ORDER).toList();}
    }
    void ensureDefaults(JsonObject manifest)throws Exception {
        if(!manifest.has("shared_resource_packs"))return;
        Path state=base.resolve("shared/resource-pack-defaults.json");
        JsonObject installed=Files.exists(state)?JsonParser.parseString(Files.readString(state)).getAsJsonObject():new JsonObject();
        for(var element:manifest.getAsJsonArray("shared_resource_packs")){
            JsonObject file=element.getAsJsonObject();String name=file.get("name").getAsString();String hash=file.get("sha256").getAsString();
            if(installed.has(name))continue; // A player can remove a default without it returning.
            Path target=paths.resolveSafe(storage,name);
            if(Files.exists(target)){
                if(!Pack.hash(target,"SHA-256").equals(hash))throw new IOException("Shared default pack conflicts with an existing file: "+name);
            }else{
                Path temporary=Files.createTempFile(storage,".default-",".part");
                try{
                    String url=file.get("url").getAsString();if(!url.startsWith("https://"))throw new IOException("Invalid default resource-pack URL");
                    Net.download(url,temporary);
                    if(!Pack.hash(temporary,"SHA-256").equals(hash))throw new IOException("Default resource-pack checksum mismatch: "+name);
                    try(var zip=new ZipFile(temporary.toFile())){if(zip.getEntry("pack.mcmeta")==null)throw new IOException("Default resource pack has no metadata");}
                    Files.move(temporary,target);
                }finally{Files.deleteIfExists(temporary);}
            }
            installed.addProperty(name,hash);Pack.atomic(state,installed.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
    void stopped()throws Exception {
        for(Path home:List.of(base,base.resolve("test"),base.resolve("custom")))new CustomMods(new Pack(home)).stopped();
    }
    void add(Path input)throws Exception {
        stopped();String name=input.getFileName().toString();
        if(!name.toLowerCase(Locale.ROOT).endsWith(".zip"))throw new IOException("Choose a resource-pack ZIP");
        try(var zip=new ZipFile(input.toFile())){
            var metadata=zip.getEntry("pack.mcmeta");
            if(metadata==null||metadata.isDirectory())throw new IOException("This ZIP has no pack.mcmeta at its root");
            try(var in=zip.getInputStream(metadata)){
                byte[] bytes=in.readNBytes(1048577);
                if(bytes.length>1048576)throw new IOException("Resource-pack metadata is too large");
                var document=JsonParser.parseString(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                if(!document.has("pack")||!document.getAsJsonObject("pack").has("pack_format"))throw new IOException("Invalid resource-pack metadata");
            }
        }
        Path destination=paths.resolveSafe(storage,name);
        try(var files=Files.list(storage)){if(files.anyMatch(p->p.getFileName().toString().equalsIgnoreCase(name)))throw new IOException("A shared pack with this filename already exists");}
        Files.copy(input,destination);
        syncInstances();
    }
    void remove(String name)throws Exception {
        stopped();Path source=paths.resolveSafe(storage,name);
        Path trash=base.resolve("shared/resource-pack-trash/"+UUID.randomUUID());Files.createDirectories(trash);
        Files.move(source,trash.resolve(source.getFileName()));syncInstances();
    }
    void syncInstances()throws Exception {
        for(Path home:List.of(base,base.resolve("test"),base.resolve("custom")))sync(home.resolve("instance"));
    }
    void sync(Path instance)throws Exception {
        Path directory=instance.resolve("resourcepacks");Files.createDirectories(directory);
        Path record=instance.resolve(".aeromon-shared-resourcepacks.json");
        JsonObject old=Files.exists(record)?JsonParser.parseString(Files.readString(record)).getAsJsonObject():new JsonObject();
        JsonObject next=new JsonObject();
        for(String name:list())next.addProperty(name,Pack.hash(paths.resolveSafe(storage,name),"SHA-256"));
        // Check every conflict before modifying any instance copy.
        Set<String> names=new HashSet<>(old.keySet());names.addAll(next.keySet());
        for(String name:names){
            Path target=paths.resolveSafe(directory,name);
            if(!Files.exists(target))continue;
            String current=Pack.hash(target,"SHA-256");
            String expected=old.has(name)?old.get(name).getAsString():next.get(name).getAsString();
            if(!current.equals(expected)&&(!next.has(name)||!current.equals(next.get(name).getAsString())))throw new IOException("Resource pack was changed outside the launcher: "+target);
        }
        for(String name:old.keySet())if(!next.has(name))Files.deleteIfExists(paths.resolveSafe(directory,name));
        for(String name:next.keySet()){
            Path target=paths.resolveSafe(directory,name);
            if(Files.isRegularFile(target)&&Pack.hash(target,"SHA-256").equals(next.get(name).getAsString()))continue;
            Path temporary=Files.createTempFile(directory,".aeromon-resource-",".part");
            try{Files.copy(paths.resolveSafe(storage,name),temporary,StandardCopyOption.REPLACE_EXISTING);Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temporary);}
        }
        Pack.atomic(record,next.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
