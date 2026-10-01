package cc.aeromon.launcher;

import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.io.IOException;
import java.util.*;
import java.util.jar.JarFile;

/** User additions are stored independently of signed pack contents. */
final class CustomMods {
    record Mod(String name,boolean enabled){public String toString(){return (enabled?"Enabled   ":"Disabled   ")+name;}}
    final Pack pack;
    final Path storage;
    CustomMods(Pack pack)throws IOException{this.pack=pack;storage=pack.state.resolve("custom-mods");Files.createDirectories(storage);}
    List<Mod> list()throws Exception{
        try(var files=Files.list(storage)){var result=new ArrayList<Mod>();for(Path file:files.filter(Files::isRegularFile).sorted().toList())result.add(new Mod(file.getFileName().toString(),Files.exists(active(file.getFileName().toString()))));return result;}
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
