package cc.aeromon.launcher;

import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.io.IOException;
import java.util.*;
import java.util.jar.JarFile;
import java.net.URI;

/** User additions are stored independently of signed pack contents. */
final class CustomMods {
    record OptionalMod(String id,String label,String filename,String url,String sha256,boolean enabled) {
        public String toString(){return (enabled?"Installed   ":"Not installed   ")+label;}
    }
    private static final List<OptionalMod> OPTIONAL_MODS=List.of(
        new OptionalMod("sodium","Sodium (NeoForge 1.21.1)","sodium-neoforge-0.8.13+mc1.21.1.jar",
            "https://cdn.modrinth.com/data/AANobbMI/versions/uMOpc5uV/sodium-neoforge-0.8.13%2Bmc1.21.1.jar","575d187b15328d7bddd8a9b1bee64333c22c14735f5780ec49eac0cd5503c08d",false),
        new OptionalMod("sodium-extra","Sodium Extra (NeoForge 1.21.1)","sodium-extra-neoforge-0.9.4+mc1.21.1.jar",
            "https://cdn.modrinth.com/data/PtjYWJkn/versions/ufpcXU9c/sodium-extra-neoforge-0.9.4%2Bmc1.21.1.jar","655b4a25d3d1ccc0f53d0589c676f0a86e46843ae537749048b4b0edc33a0f6f",false)
    );
    record Mod(String name,boolean enabled){public String toString(){return (enabled?"Enabled   ":"Disabled   ")+name;}}
    final Pack pack;
    final Path storage;
    CustomMods(Pack pack)throws IOException{this.pack=pack;storage=pack.state.resolve("custom-mods");Files.createDirectories(storage);}
    List<Mod> list()throws Exception{
        try(var files=Files.list(storage)){var result=new ArrayList<Mod>();for(Path file:files.filter(Files::isRegularFile).sorted().toList())result.add(new Mod(file.getFileName().toString(),Files.exists(active(file.getFileName().toString()))));return result;}
    }
    List<OptionalMod> optionalList()throws Exception {
        Path directory=pack.state.resolve("optional-mods");Files.createDirectories(directory);
        List<OptionalMod> result=new ArrayList<>();
        for(OptionalMod mod:OPTIONAL_MODS){Path active=pack.instance.resolve("mods").resolve(mod.filename()),record=directory.resolve(mod.filename());boolean present=Files.isRegularFile(active),owned=Files.isRegularFile(record);
            if(present&&!owned&&Pack.hash(active,"SHA-256").equals(mod.sha256())){Files.copy(active,record,StandardCopyOption.REPLACE_EXISTING);owned=true;}
            result.add(new OptionalMod(mod.id(),mod.label(),mod.filename(),mod.url(),mod.sha256(),present&&owned));
        }
        return result;
    }
    void toggleOptional(OptionalMod selected)throws Exception {
        OptionalMod mod=OPTIONAL_MODS.stream().filter(item->item.id().equals(selected.id())).findFirst().orElseThrow(()->new IOException("Unknown optional mod"));
        change(()->{
            Path target=pack.safe(pack.instance,"mods/"+mod.filename());Path directory=pack.state.resolve("optional-mods");Files.createDirectories(directory);Path stored=pack.safe(directory,mod.filename());
            if(Files.exists(target)){
                if(!Files.isRegularFile(stored)){if(!Pack.hash(target,"SHA-256").equals(mod.sha256()))throw new IOException("This file was changed outside the launcher. Resolve it in the instance folder first.");Files.copy(target,stored);}
                if(Files.mismatch(stored,target)!=-1)throw new IOException("This file was changed outside the launcher. Resolve it in the instance folder first.");
                Files.delete(target);Files.deleteIfExists(stored);return;
            }
            if(Files.isRegularFile(stored))throw new IOException("The optional mod's ownership record exists but its active file is missing. Repair the pack before changing it.");
            Path staged=pack.state.resolve("staging/optional/"+mod.filename());Files.createDirectories(staged.getParent());
            if(!Files.isRegularFile(staged)||!Pack.hash(staged,"SHA-256").equals(mod.sha256())){Files.deleteIfExists(staged);Net.download(mod.url(),staged);if(!Pack.hash(staged,"SHA-256").equals(mod.sha256())){Files.deleteIfExists(staged);throw new SecurityException("Optional mod checksum mismatch: "+mod.label());}}
            Files.createDirectories(target.getParent());Files.copy(staged,target);Files.copy(staged,stored,StandardCopyOption.REPLACE_EXISTING);
        });
    }
    Path source(String name)throws IOException{return pack.safe(storage,name);}
    Path active(String name)throws IOException{return pack.safe(pack.instance,"mods/"+name);}
    void stopped()throws Exception{Path pid=pack.state.resolve("game.pid");if(Files.exists(pid)&&ProcessHandle.of(Long.parseLong(Files.readString(pid).trim())).map(ProcessHandle::isAlive).orElse(false))throw new IOException("Close Minecraft before changing client mods");}
    interface Change{void run()throws Exception;}
    void change(Change action)throws Exception{try(var file=FileChannel.open(pack.state.resolve("install.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=file.tryLock()){if(lock==null)throw new IOException("An installation is already running");stopped();action.run();}}
    void add(Path input,Pack.Release release)throws Exception{change(()->{
        String name=input.getFileName().toString();source(name);if(!name.toLowerCase(Locale.ROOT).endsWith(".jar"))throw new IOException("Choose a Java JAR file");
        try(var jar=new JarFile(input.toFile())){if(!jar.entries().hasMoreElements())throw new IOException("This JAR is empty");}
        for(var file:release.manifest().getAsJsonArray("files"))if(file.getAsJsonObject().get("path").getAsString().equalsIgnoreCase("mods/"+name))throw new IOException("This filename belongs to the Aeromon pack. Choose a different custom mod.");
        try(var files=Files.list(storage)){if(files.anyMatch(p->p.getFileName().toString().equalsIgnoreCase(name)))throw new IOException("This custom JAR is already imported. Remove it before adding a replacement.");}
        Files.createDirectories(pack.instance.resolve("mods"));try(var files=Files.list(pack.instance.resolve("mods"))){if(files.anyMatch(p->p.getFileName().toString().equalsIgnoreCase(name)))throw new IOException("A mod with this filename already exists.");}
        Files.copy(input,source(name));try{Files.copy(source(name),active(name));}catch(Exception e){Files.deleteIfExists(source(name));throw e;}
    });}
    void toggle(Mod mod)throws Exception{change(()->{Path original=source(mod.name()),target=active(mod.name());if(mod.enabled()){owned(original,target);Files.delete(target);}else{if(Files.exists(target))throw new IOException("A mod with this filename already exists");Files.copy(original,target);}});}
    void remove(Mod mod)throws Exception{change(()->{Path original=source(mod.name()),target=active(mod.name());if(Files.exists(target))owned(original,target);Path trash=pack.state.resolve("custom-mod-trash/"+UUID.randomUUID());Files.createDirectories(trash);Files.move(original,trash.resolve(mod.name()));Files.deleteIfExists(target);});}
    static void owned(Path original,Path active)throws Exception{if(!Pack.hash(original,"SHA-256").equals(Pack.hash(active,"SHA-256")))throw new IOException("The active JAR was changed outside the launcher. Resolve it in the instance folder first.");}
    void checkPack(Pack.Release release)throws Exception{Set<String> names=new HashSet<>();try(var files=Files.list(storage)){files.forEach(p->names.add(("mods/"+p.getFileName()).toLowerCase(Locale.ROOT)));}for(var file:release.manifest().getAsJsonArray("files")){String name=file.getAsJsonObject().get("path").getAsString();if(names.contains(name.toLowerCase(Locale.ROOT)))throw new IOException("The new pack includes "+name+". Remove that custom JAR in Client mods before updating; it will be supplied by the pack.");}}
}
