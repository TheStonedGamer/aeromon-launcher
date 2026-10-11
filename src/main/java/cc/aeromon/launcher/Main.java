package cc.aeromon.launcher;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.nio.file.*;
import java.net.URI;
import java.util.concurrent.*;

public final class Main {
    static {
        if(System.getProperty("os.name").toLowerCase().contains("win")) {
            try {Path sockets=Path.of(System.getProperty("user.home"),".aeromon-sockets");Files.createDirectories(sockets);System.setProperty("jdk.net.unixdomain.tmpdir",sockets.toString());}
            catch(Exception ignored) { /* The runtime will use its default socket directory. */ }
        }
    }
    static final Color INK=new Color(12,20,28), PANEL=new Color(24,37,43), MINT=new Color(115,225,182), PAPER=new Color(235,222,188);
    final ExecutorService worker=Executors.newSingleThreadExecutor();
    final JFrame window=new JFrame("Aeromon Launcher");
    final JLabel status=new JLabel("Connecting to Aeromon…"), version=new JLabel("PACK · Checking release"), account=new JLabel("Not signed in");
    final JTextArea notes=new JTextArea();
    final JButton install=new JButton("INSTALL AEROMON"), repair=new JButton("Repair"), play=new JButton("PLAY AEROMON"), signIn=new JButton("Microsoft sign-in");
    final JButton offline=new JButton("LAUNCH OFFLINE");
    final JButton official=new JButton("OPEN OFFICIAL LAUNCHER");
    final JProgressBar progress=new JProgressBar();
    final JComboBox<String> channel=new JComboBox<>(new String[]{"stable","test","custom"});
    Pack pack;
    final Path baseHome;
    LoadingSplash splash;
    Pack.Release release;
    Minecraft.Session session;
    Process game;
    int pendingTasks;
    Main(Path home)throws Exception {
        baseHome=home;
        String savedChannel=prefs().get("packChannel","stable");if(java.util.Arrays.asList("stable","test","custom").contains(savedChannel))channel.setSelectedItem(savedChannel);
        if("test".equals(savedChannel))home=home.resolve("test");else if("custom".equals(savedChannel))home=home.resolve("custom");
        pack=new Pack(home);
        window.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        Rectangle available=GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();int minWidth=Math.min(1060,Math.max(820,available.width-40));int minHeight=Math.min(770,Math.max(520,available.height-40));window.setMinimumSize(new Dimension(minWidth,minHeight));window.setSize(Math.min(1220,Math.max(minWidth,available.width-80)),Math.min(860,Math.max(minHeight,available.height-40)));window.setLocationRelativeTo(null);
        window.setContentPane(new WindowFrame(this,new LauncherView(this)));
        channel.addActionListener(e->{prefs().put("packChannel",(String)channel.getSelectedItem());if(game==null||!game.isAlive())refresh();});
        signIn.addActionListener(e->run(()->{
            if(session!=null){CredentialStore.clear(baseHome);session=null;ui(()->{account.setText("Not signed in");signIn.setText("Sign in with Microsoft");});return;}
            session=Minecraft.login(clientId(),this::message);
            CredentialStore.save(baseHome,session.refreshToken());
            ui(()->{account.setText(session.name());signIn.setText("Sign out");});
        }));
        install.setEnabled(false);repair.setEnabled(false);play.setEnabled(false);
        install.addActionListener(e->install());repair.addActionListener(e->install());
        offline.addActionListener(e->run(()->{
            pack.install(release,this::message);
            game=new Minecraft(pack.home,this::message).launchOffline(release.manifest(),memory());
            message("Minecraft running in offline test mode");
            watchGame("Offline Minecraft closed");
        }));
        official.addActionListener(e->run(this::openOfficialLauncher));
        play.addActionListener(e->run(()->{
            if(session==null)throw new Exception("Sign in with Microsoft first");
            pack.install(release,this::message);
            String host=selectedServerHost();
            game=new Minecraft(pack.home,this::message).launch(release.manifest(),session,memory(),host);
            message("Minecraft running - connected to "+host);
            watchGame("Minecraft closed");
        }));
        window.addWindowListener(new java.awt.event.WindowAdapter(){public void windowClosed(java.awt.event.WindowEvent e){worker.shutdown();}});
        splash=new LoadingSplash(window);splash.showLoading("Preparing Aeromon runtime");
        worker.submit(()->{
            try{
                JavaRuntime.ensure(pack.home,this::message);
            }catch(Exception failure){
                ui(()->{splash.finish();JOptionPane.showMessageDialog(splash,"Unable to prepare Java: "+failure.getMessage(),"Aeromon needs attention",JOptionPane.ERROR_MESSAGE);splash.dispose();window.dispose();});
                worker.shutdown();return;
            }
            ui(()->run(()->{
                try{if(LauncherUpdate.check(pack.home,this::message)){ui(()->{splash.finish();window.dispose();System.exit(0);});return;}}
                catch(Exception e){message("Launcher update check unavailable; continuing with this version");}
                ui(()->window.setVisible(true));
                restoreSession();
                refreshRelease();
            }));
        });
    }
    static Path defaultHome(){String os=System.getProperty("os.name").toLowerCase();String base=os.contains("win")?System.getenv("APPDATA"):os.contains("mac")?System.getProperty("user.home")+"/Library/Application Support":System.getenv().getOrDefault("XDG_DATA_HOME",System.getProperty("user.home")+"/.local/share");return Path.of(base,"Aeromon");}
    static JLabel label(String text,int size,Color color){var l=new JLabel(text);l.setForeground(color);l.setFont(new Font("Dialog",Font.BOLD,size));return l;}
    static void style(JButton b,Color background,Color foreground){b.setBackground(background);b.setForeground(foreground);b.setFont(LauncherView.font(12,true));b.setFocusPainted(false);b.setBorder(new CompoundBorder(new LineBorder(background.brighter()),new EmptyBorder(12,16,12,16)));}
    static JButton button(String text,Color bg,Color fg){var b=new JButton(text);style(b,bg,fg);b.setAlignmentX(0);b.setMaximumSize(new Dimension(195,45));return b;}
    static void ui(Runnable r){SwingUtilities.invokeLater(r);}
    void message(String message){ui(()->{status.setText(message);if(splash!=null)splash.message=message;});}
    interface Task {void run()throws Exception;}
    void reportFailure(Exception failure){
        String detail=LauncherErrors.describe(failure);Path log=LauncherErrors.record(pack.home,failure);
        message(detail);String dialog=detail+(log==null?"":"\n\nDiagnostic log: "+log);
        ui(()->JOptionPane.showMessageDialog(window,dialog,"Aeromon needs attention",JOptionPane.ERROR_MESSAGE));
    }
    void updateControls(){
        boolean idle=pendingTasks==0;
        boolean stopped=game==null || !game.isAlive();
        channel.setEnabled(idle && stopped);
        signIn.setEnabled(idle);
        official.setEnabled(idle && stopped);
        install.setEnabled(idle && stopped && release!=null);
        repair.setEnabled(install.isEnabled());
        offline.setEnabled(install.isEnabled());
        play.setEnabled(install.isEnabled() && session!=null);
    }
    void watchGame(String closedMessage){
        Process launched=game;
        launched.onExit().thenRun(()->ui(()->{
            if(game!=launched)return;
            game=null;
            updateControls();
            if(pendingTasks==0)status.setText(closedMessage);
        }));
    }
    void run(Task task){
        ui(()->{pendingTasks++;progress.setVisible(true);progress.setStringPainted(false);progress.setIndeterminate(true);updateControls();});
        worker.submit(()->{
            try{task.run();}catch(Exception e){reportFailure(e);}
            finally{ui(()->{
                pendingTasks--;
                progress.setVisible(pendingTasks>0);
                progress.setStringPainted(false);
                progress.setIndeterminate(pendingTasks>0);
                if(pendingTasks==0 && splash!=null)splash.finish();
                updateControls();
            });}
        });
    }
    void transferProgress(String label,long completed,long total){
        ui(()->{
            progress.setIndeterminate(false);
            progress.setStringPainted(true);
            progress.setString(String.format("%s · %d%%",label,total>0?Math.min(100,completed*100/total):0));
            progress.setMaximum(1000);
            progress.setValue(total>0?(int)Math.min(1000,completed*1000/total):0);
            status.setText(label);
        });
    }
    void refresh(){run(this::refreshRelease);}
    String selectedServerHost(){return "test".equals(channel.getSelectedItem())?"test.aeromon.cc":"mc.aeromon.cc";}
    void restoreSession(){
        try{session=Minecraft.restore(baseHome,clientId(),this::message);if(session!=null){CredentialStore.save(baseHome,session.refreshToken());ui(()->{account.setText(session.name());signIn.setText("Sign out");});}}
        catch(Exception failure){session=null;LauncherErrors.record(baseHome,failure);message("Could not restore saved sign-in: "+LauncherErrors.describe(failure)+" Your saved login is retained; restart to retry or sign in again.");}
    }
    void refreshRelease()throws Exception{
        if(game!=null && game.isAlive())throw new java.io.IOException("Close Minecraft before changing packs");
        release=null;
        String selected=(String)channel.getSelectedItem();
        pack=new Pack(switch(selected){case "test"->baseHome.resolve("test");case "custom"->baseHome.resolve("custom");default->baseHome;});
        release=pack.latest(selected);var sharedPacks=new ResourcePacks(baseHome);sharedPacks.ensureDefaults(release.manifest());sharedPacks.syncInstances();String installed=pack.installed();ui(()->{
        version.setText("AEROMON "+release.version()+"  /  MINECRAFT "+release.manifest().get("minecraft").getAsString()+"  /  NEOFORGE "+release.manifest().get("neoforge").getAsString());
        String releaseNotes=release.manifest().get("notes").getAsString();notes.setText(releaseNotes.startsWith("Imported from")?"Pixelmon meets Create Aeronautics. Explore the Aeromon world with the current community pack.":releaseNotes);
        install.setText(installed.equals(release.version())?"CHECK FOR UPDATES":"Not installed".equals(installed)?"INSTALL AEROMON":"UPDATE AEROMON");status.setText("Installed: "+installed+" · Available: "+release.version()+" · "+selectedServerHost());
    });}
    void install(){splash.showLoading("Preparing your Aeromon pack");run(()->{pack.install(release,this::message);new Minecraft(pack.home,this::message).prepare(release.manifest());ui(()->{install.setText("CHECK FOR UPDATES");status.setText("Aeromon "+release.version()+" installed. Sign in to play.");});});}
    String clientId()throws Exception {String id=System.getProperty("aeromon.clientId",prefs().get("clientId", "6e76a2c9-5a48-41d6-9e6a-3aa2e60c36fa"));if(id.isBlank())throw new Exception("Microsoft application registration is pending. Enter Aeromon's public client ID in Settings.");return id;}
    java.util.prefs.Preferences prefs(){return java.util.prefs.Preferences.userNodeForPackage(Main.class);}
    int memory(){return prefs().getInt("memory",8192);}
    void settings(){
        var ram=new JSpinner(new SpinnerNumberModel(memory(),2048,32768,512));
        var content=new JPanel(new GridLayout(0,1,5,8));content.add(new JLabel("Minecraft memory (MB)"));content.add(ram);
        String branch=(String)channel.getSelectedItem();Pack selectedPack=pack;Pack.Release selectedRelease=release;
        var reset=new JButton("Reinitialize "+branch+" branch…");
        reset.setEnabled((branch.equals("stable")||branch.equals("test"))&&pendingTasks==0&&(game==null||!game.isAlive())&&selectedRelease!=null);
        reset.addActionListener(e->{
            String detail="Reinitialize "+branch+"?\nThis deletes this branch's mods, configs, saves, screenshots and other instance files, then reinstalls defaults.\nJourneyMap folders and shared resource packs are retained. Close Minecraft first.";
            if(JOptionPane.showConfirmDialog(window,detail,"Reinitialize branch",JOptionPane.OK_CANCEL_OPTION,JOptionPane.WARNING_MESSAGE)!=JOptionPane.OK_OPTION)return;
            reset.setEnabled(false);
            run(()->{
                if(game!=null&&game.isAlive())throw new java.io.IOException("Close Minecraft before reinitializing");
                Minecraft runtime=new Minecraft(selectedPack.home,this::message);
                Path officialRoot=branch.equals("test")?runtime.root:officialLauncherRoot(runtime.root);
                BranchReset.reset(selectedPack,selectedRelease,officialRoot.resolve("Aeromon"),this::message);
                refreshRelease();message(branch+" reinitialized; JourneyMap preserved");
            });
        });
        content.add(new JLabel("Reset the selected branch while preserving JourneyMap."));content.add(reset);
        var cloud=new JButton("Minecraft account cloud storage...");
        cloud.setEnabled(session!=null&&(branch.equals("stable")||branch.equals("test"))&&pendingTasks==0&&(game==null||!game.isAlive()));
        cloud.addActionListener(e->PlayerSyncView.open(this,selectedPack,branch));
        content.add(new JLabel("Transfer this branch's settings and JourneyMap between computers."));content.add(cloud);
        if(JOptionPane.showConfirmDialog(window,content,"Settings",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION)prefs().putInt("memory",(Integer)ram.getValue());
    }
    void openOfficialLauncher() throws Exception { openOfficialLauncher(pack,release,memory(),this::message); }
    static void openOfficialLauncher(Pack pack,Pack.Release release,int memory,java.util.function.Consumer<String> message) throws Exception {
        if(release==null)throw new java.io.IOException("Wait for the pack release to load");
        pack.install(release,message);
        Minecraft runtime=new Minecraft(pack.home,message);
        Path root=release.version().startsWith("test-")?runtime.root:officialLauncherRoot(runtime.root);
        Path gameDir=root.resolve("Aeromon").toAbsolutePath().normalize();
        runtime.prepare(release.manifest(),root);
        runtime.syncOfficialInstance(release,gameDir);
        Path profiles=root.resolve("launcher_profiles.json");
        com.google.gson.JsonObject document=Files.exists(profiles)
            ?com.google.gson.JsonParser.parseString(Files.readString(profiles)).getAsJsonObject()
            :new com.google.gson.JsonObject();
        if(!document.has("profiles"))document.add("profiles",new com.google.gson.JsonObject());
        var profile=new com.google.gson.JsonObject();
        profile.addProperty("name","Aeromon");
        profile.addProperty("type","custom");
        String neoVersion="neoforge-"+release.manifest().get("neoforge").getAsString();
        Path neoMetadata=root.resolve("versions").resolve(neoVersion).resolve(neoVersion+".json");
        if(!Files.isRegularFile(neoMetadata))throw new java.io.IOException("NeoForge profile is missing. Repair the Aeromon runtime and retry.");
        profile.addProperty("lastVersionId",neoVersion);
        profile.addProperty("gameDir",gameDir.toString());
        profile.addProperty("javaDir",runtime.gameJava().toString());
        profile.addProperty("javaArgs","-Xmx"+memory+"M");
        document.getAsJsonObject("profiles").add("aeromon",profile);
        document.addProperty("selectedProfile","aeromon");
        document.add("launcherVersion",new com.google.gson.Gson().toJsonTree(java.util.Map.of("name","official-launcher","format",21)));
        Path staged=profiles.resolveSibling("launcher_profiles.json.part");
        Files.writeString(staged,new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(document));
        Files.move(staged,profiles,StandardCopyOption.REPLACE_EXISTING);
        Minecraft.verifyOfficialProfile(root,gameDir,release.manifest());
        String os=System.getProperty("os.name").toLowerCase();
        if(os.contains("win")){
            // Query the exact official package identity, never a display-name wildcard.
            Process lookup=new ProcessBuilder("powershell.exe","-NoProfile","-Command",
                "(Get-AppxPackage -Name Microsoft.4297127D64EC6 | Select-Object -First 1).InstallLocation").redirectErrorStream(true).start();
            String location=new String(lookup.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).trim();
            lookup.waitFor();
            if(!location.isBlank()){
                Path executable=Path.of(location,"Minecraft.exe");
                if(Files.isRegularFile(executable)){
                    new ProcessBuilder(executable.toString(),"--workDir",root.toString()).start();
                    message.accept("Aeromon profile prepared: NeoForge "+release.manifest().get("neoforge").getAsString()+" · "+gameDir.resolve("mods")+". Confirm Aeromon is selected before Play.");
                    return;
                }
            }
            for(String base:new String[]{System.getenv("LOCALAPPDATA"),System.getenv("ProgramFiles"),System.getenv("ProgramFiles(x86)")}){
                if(base==null)continue;
                Path executable=Path.of(base,"Minecraft Launcher","MinecraftLauncher.exe");
                if(Files.isRegularFile(executable)){
                    new ProcessBuilder(executable.toString(),"--workDir",root.toString()).start();
                    message.accept("Aeromon profile prepared: NeoForge "+release.manifest().get("neoforge").getAsString()+" · "+gameDir.resolve("mods")+". Confirm Aeromon is selected before Play.");
                    return;
                }
            }
            Path executable=bootstrapOfficialWindows(pack.home.resolve("instance"),message);
            new ProcessBuilder(executable.toString(),"--workDir",root.toString()).directory(executable.getParent().toFile()).start();
            message.accept("Minecraft Launcher installed for this Aeromon instance. Select Aeromon, sign in, then Play.");
            return;
        }
        if(os.contains("mac"))new ProcessBuilder("open","-a","Minecraft","--args","--workDir",root.toString()).start();
        else new ProcessBuilder("minecraft-launcher","--workDir",root.toString()).start();
        message.accept("Official launcher opened. Select the Aeromon installation, then Play.");
    }
    static Path officialLauncherRoot(Path preferred) throws Exception {
        String os=System.getProperty("os.name").toLowerCase();
        if(os.contains("win")){
            Path roaming=Path.of(System.getenv("APPDATA"),".minecraft");if(Files.exists(roaming.resolve("launcher_profiles.json")))return roaming;
            // The Aeromon Microsoft Store launcher is already opened with this work root.
            return preferred;
        }
        return preferred;
    }
    static Path bootstrapOfficialWindows(Path instance,java.util.function.Consumer<String> message)throws Exception {
        Path directory=instance.resolve("official-launcher");Files.createDirectories(directory);
        Path executable=directory.resolve("Minecraft.exe"),staged=directory.resolve("Minecraft.exe.part");
        if(!Files.isRegularFile(executable)){
            message.accept("Installing the official Minecraft Launcher for this instance…");
            try{
                var client=java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(30)).followRedirects(java.net.http.HttpClient.Redirect.NORMAL).build();
                var request=java.net.http.HttpRequest.newBuilder(URI.create("https://launcher.mojang.com/download/Minecraft.exe")).timeout(java.time.Duration.ofMinutes(5)).GET().build();
                var response=client.send(request,java.net.http.HttpResponse.BodyHandlers.ofFile(staged));
                if(response.statusCode()!=200||Files.size(staged)<100000)throw new java.io.IOException("Minecraft Launcher download failed");
                verifyOfficialWindows(staged);
                Files.move(staged,executable,StandardCopyOption.REPLACE_EXISTING);
            }finally{Files.deleteIfExists(staged);}
        }
        verifyOfficialWindows(executable);
        return executable;
    }
    static void verifyOfficialWindows(Path executable)throws Exception {
        String path=executable.toAbsolutePath().toString().replace("'","''");
        String script="$s=Get-AuthenticodeSignature -LiteralPath '"+path+"';if($s.Status -ne 'Valid' -or $s.SignerCertificate.Subject -notmatch 'O=(Mojang AB|Microsoft Corporation)(,|$)'){exit 1}";
        Process process=new ProcessBuilder("powershell.exe","-NoProfile","-NonInteractive","-WindowStyle","Hidden","-Command",script).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        if(process.waitFor()!=0)throw new java.io.IOException("Minecraft Launcher publisher signature could not be verified");
    }
    public static void main(String[] args)throws Exception {
        Path home=defaultHome();for(int i=0;i<args.length;i++)if(args[i].equals("--home"))home=Path.of(args[++i]);
        var options=java.util.Arrays.asList(args);
        String requestedChannel=options.contains("--custom")?"custom":options.contains("--test")?"test":"stable";
        if(options.contains("--test"))home=home.resolve("test");else if(options.contains("--custom"))home=home.resolve("custom");
        if(options.contains("--official-launcher")){var pack=new Pack(home);openOfficialLauncher(pack,pack.latest("stable"),8160,System.out::println);return;}
        if(options.contains("--official-offline-test")){var pack=new Pack(home);var release=pack.latest("stable");pack.install(release,System.out::println);var runtime=new Minecraft(home,System.out::println);Path root=officialLauncherRoot(runtime.root),gameDir=root.resolve("Aeromon");runtime.prepare(release.manifest(),root);runtime.syncOfficialInstance(release,gameDir);var profile=com.google.gson.JsonParser.parseString(Files.readString(root.resolve("launcher_profiles.json"))).getAsJsonObject();if(!"aeromon".equals(profile.get("selectedProfile").getAsString()))throw new java.io.IOException("Aeromon profile is not selected");var process=runtime.launch(release.manifest(),new Minecraft.Session("AeromonTest",java.util.UUID.nameUUIDFromBytes("OfflinePlayer:AeromonTest".getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString().replace("-",""),"0",null),8160,true,root,gameDir);System.out.println("Offline Aeromon NeoForge process "+process.pid()+"; inspect "+gameDir.resolve("logs/latest.log"));return;}
        if(options.contains("--offline-test")){var pack=new Pack(home);var release=pack.latest(requestedChannel);pack.install(release,System.out::println);var process=new Minecraft(home,System.out::println).launchOffline(release.manifest(),6144);System.out.println("Offline Minecraft PID "+process.pid());return;}
        if(options.contains("--activate-update")){LauncherUpdate.activate(home,args[options.indexOf("--update-version")+1],Long.parseLong(args[options.indexOf("--activate-update")+1]));return;}
        if(java.util.Arrays.asList(args).contains("--login-test")){var session=Minecraft.login("6e76a2c9-5a48-41d6-9e6a-3aa2e60c36fa",System.out::println);System.out.println("Minecraft profile verified: "+session.name());return;}
        if(java.util.Arrays.asList(args).contains("--check")||java.util.Arrays.asList(args).contains("--install")||java.util.Arrays.asList(args).contains("--prepare")){
            var pack=new Pack(home);var release=pack.latest(requestedChannel);System.out.println("Verified release "+release.version()+" · "+release.manifest().getAsJsonArray("files").size()+" files");
            if(java.util.Arrays.asList(args).contains("--install"))pack.install(release,System.out::println);
            if(java.util.Arrays.asList(args).contains("--prepare"))new Minecraft(home,System.out::println).prepare(release.manifest());
            return;
        }
        if(LauncherUpdate.bootstrap(home))return;
        final Path target=home;SwingUtilities.invokeLater(()->{try{UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());new Main(target);}catch(Exception e){JOptionPane.showMessageDialog(null,e.getMessage());}});
    }
}

