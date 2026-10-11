package cc.aeromon.launcher;

import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;

/** Transactional reset of a branch's game directories, retaining JourneyMap. */
final class BranchReset {
    record Saved(Path target,Path original,List<String> maps){}
    static void deleteTree(Path root)throws IOException {
        if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS))return;
        try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}
    }
    static void reset(Pack pack,Pack.Release release,Path officialGame,Consumer<String> progress)throws Exception {
        new CustomMods(pack).stopped();
        List<Path> targets=new ArrayList<>();targets.add(pack.instance);
        if(!officialGame.toAbsolutePath().normalize().equals(pack.instance))targets.add(officialGame.toAbsolutePath().normalize());
        for(Path target:targets){
            if(Files.isSymbolicLink(target))throw new IOException("Cannot reset a linked instance directory: "+target);
            try(var processes=ProcessHandle.allProcesses()){
                if(processes.anyMatch(p->p.isAlive()&&p.info().arguments().map(args->Arrays.stream(args).anyMatch(a->a.equalsIgnoreCase(target.toString()))).orElse(false)))throw new IOException("Close Minecraft before reinitializing this branch");
            }
        }
        String id=UUID.randomUUID().toString();List<Saved> saved=new ArrayList<>();
        Path metadata=pack.state.resolve("reinitialize-"+id);Files.createDirectories(metadata);
        List<String> tracked=List.of("installed.json","transaction.json","custom-mods","optional-mods","staging","official-files.json","official-custom-files.json","official-optional-files.json");
        boolean installing=false;
        try{
            for(Path target:targets){
                if(!Files.exists(target))continue;
                Path original=target.resolveSibling(target.getFileName()+".reinitialize-"+id);
                List<String> maps=new ArrayList<>();
                try(var children=Files.list(target)){for(Path child:children.toList())if(child.getFileName().toString().equalsIgnoreCase("journeymap")){
                    if(Files.isSymbolicLink(child))throw new IOException("JourneyMap folder is a link; resolve it before resetting");
                    maps.add(child.getFileName().toString());
                }}
                Files.move(target,original);saved.add(new Saved(target,original,maps));Files.createDirectories(target);
                for(String name:maps)Files.move(original.resolve(name),target.resolve(name));
            }
            for(String name:tracked){Path path=pack.state.resolve(name);if(Files.exists(path))Files.move(path,metadata.resolve(name));}
            progress.accept("Reinstalling branch defaults; JourneyMap data preserved");
            installing=true;
            pack.install(release,progress);
            Minecraft runtime=new Minecraft(pack.home,progress);runtime.prepare(release.manifest());runtime.syncOfficialInstance(release,officialGame);
        }catch(Exception failure){
            try{
                for(Saved item:saved){
                    for(String name:item.maps())if(Files.exists(item.target().resolve(name)))Files.move(item.target().resolve(name),item.original().resolve(name));
                    deleteTree(item.target());Files.move(item.original(),item.target());
                }
                for(String name:tracked){Path previous=metadata.resolve(name);if(installing||Files.exists(previous))deleteTree(pack.state.resolve(name));if(Files.exists(previous))Files.move(previous,pack.state.resolve(name));}
            }catch(Exception recovery){failure.addSuppressed(recovery);throw new IOException("Reinitialize failed; original data retained in .reinitialize-"+id+" folders. "+failure.getMessage(),failure);}
            throw failure;
        }
        for(Saved item:saved)deleteTree(item.original());deleteTree(metadata);
        progress.accept("Branch reinitialized; JourneyMap retained");
    }
}
