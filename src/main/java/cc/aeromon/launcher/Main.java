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
    final JComboBox<String> channel=new JComboBox<>(new String[]{"stable","beta"});
    final Pack pack;
    LoadingSplash splash;
    Pack.Release release;
    Minecraft.Session session;
    Process game;
    Main(Path home)throws Exception {
        pack=new Pack(home);
        window.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        window.setMinimumSize(new Dimension(1060,770));window.setSize(1220,860);window.setLocationRelativeTo(null);
        window.setContentPane(new WindowFrame(this,new LauncherView(this)));
        channel.addActionListener(e->refresh());
        signIn.addActionListener(e->run(()->{
            if(session!=null){session=null;ui(()->{account.setText("Not signed in");signIn.setText("Sign in with Microsoft");});return;}
            session=Minecraft.login(clientId(),this::message);
            ui(()->{account.setText(session.name());signIn.setText("Sign out");});
        }));
        install.setEnabled(false);repair.setEnabled(false);play.setEnabled(false);
        install.addActionListener(e->install());repair.addActionListener(e->install());
        offline.addActionListener(e->run(()->{
            pack.install(release,this::message);
            game=new Minecraft(pack.home,this::message).launchOffline(release.manifest(),memory());
            message("Minecraft running in offline test mode");
            game.onExit().thenRun(()->ui(()->{offline.setEnabled(true);play.setEnabled(session!=null);status.setText("Offline Minecraft closed");}));
        }));
        official.addActionListener(e->run(this::openOfficialLauncher));
        play.addActionListener(e->run(()->{
            if(session==null)throw new Exception("Sign in with Microsoft first");
            pack.install(release,this::message);
            game=new Minecraft(pack.home,this::message).launch(release.manifest(),session,memory());
            message("Minecraft running - welcome to Aeromon");
            game.onExit().thenRun(()->ui(()->{play.setEnabled(true);status.setText("Minecraft closed");}));
        }));
        window.addWindowListener(new java.awt.event.WindowAdapter(){public void windowClosed(java.awt.event.WindowEvent e){worker.shutdown();}});
        window.setVisible(true);splash=new LoadingSplash(window);splash.showLoading("Checking launcher updates");
        run(()->{try{if(LauncherUpdate.check(pack.home,this::message)){ui(()->{splash.finish();window.dispose();System.exit(0);});return;}}catch(Exception e){message("Launcher update check unavailable; continuing with this version");}refreshRelease();});
    }
    static Path defaultHome(){String os=System.getProperty("os.name").toLowerCase();String base=os.contains("win")?System.getenv("APPDATA"):os.contains("mac")?System.getProperty("user.home")+"/Library/Application Support":System.getenv().getOrDefault("XDG_DATA_HOME",System.getProperty("user.home")+"/.local/share");return Path.of(base,"Aeromon");}
    static JLabel label(String text,int size,Color color){var l=new JLabel(text);l.setForeground(color);l.setFont(new Font("Dialog",Font.BOLD,size));return l;}
    static void style(JButton b,Color background,Color foreground){b.setBackground(background);b.setForeground(foreground);b.setFont(LauncherView.font(12,true));b.setFocusPainted(false);b.setBorder(new CompoundBorder(new LineBorder(background.brighter()),new EmptyBorder(12,16,12,16)));}
    static JButton button(String text,Color bg,Color fg){var b=new JButton(text);style(b,bg,fg);b.setAlignmentX(0);b.setMaximumSize(new Dimension(195,45));return b;}
    static void ui(Runnable r){SwingUtilities.invokeLater(r);}
    void message(String message){ui(()->{status.setText(message);if(splash!=null)splash.message=message;});}
    interface Task {void run()throws Exception;}
    void run(Task task){ui(()->{progress.setVisible(true);progress.setIndeterminate(true);install.setEnabled(false);repair.setEnabled(false);play.setEnabled(false);official.setEnabled(false);offline.setEnabled(false);signIn.setEnabled(false);channel.setEnabled(false);});worker.submit(()->{try{task.run();}catch(Exception e){message(e.getMessage());ui(()->JOptionPane.showMessageDialog(window,e.getMessage(),"Aeromon needs attention",JOptionPane.ERROR_MESSAGE));}finally{ui(()->{progress.setVisible(false);if(splash!=null)splash.finish();channel.setEnabled(true);signIn.setEnabled(true);official.setEnabled(true);install.setEnabled(release!=null && (game==null || !game.isAlive()));repair.setEnabled(install.isEnabled());offline.setEnabled(install.isEnabled());play.setEnabled(release!=null && session!=null && (game==null || !game.isAlive()));});}});}
    void refresh(){run(this::refreshRelease);}
    void refreshRelease()throws Exception{release=pack.latest((String)channel.getSelectedItem());String installed=pack.installed();ui(()->{
        version.setText("AEROMON "+release.version()+"  /  MINECRAFT "+release.manifest().get("minecraft").getAsString()+"  /  NEOFORGE "+release.manifest().get("neoforge").getAsString());
        String releaseNotes=release.manifest().get("notes").getAsString();notes.setText(releaseNotes.startsWith("Imported from")?"Pixelmon meets Create Aeronautics. Explore the Aeromon world with the current community pack.":releaseNotes);
        install.setText(installed.equals(release.version())?"CHECK FOR UPDATES":"Not installed".equals(installed)?"INSTALL AEROMON":"UPDATE AEROMON");status.setText("Installed: "+installed+" · Available: "+release.version()+" · mc.aeromon.cc");
    });}
    void install(){splash.showLoading("Preparing your Aeromon pack");run(()->{pack.install(release,this::message);new Minecraft(pack.home,this::message).prepare(release.manifest());ui(()->{install.setText("CHECK FOR UPDATES");status.setText("Aeromon "+release.version()+" installed. Sign in to play.");});});}
    String clientId()throws Exception {String id=System.getProperty("aeromon.clientId",prefs().get("clientId", "6e76a2c9-5a48-41d6-9e6a-3aa2e60c36fa"));if(id.isBlank())throw new Exception("Microsoft application registration is pending. Enter Aeromon's public client ID in Settings.");return id;}
    java.util.prefs.Preferences prefs(){return java.util.prefs.Preferences.userNodeForPackage(Main.class);}
    int memory(){return prefs().getInt("memory",8160);}
    void settings(){var id=new JTextField(prefs().get("clientId","6e76a2c9-5a48-41d6-9e6a-3aa2e60c36fa"));var ram=new JSpinner(new SpinnerNumberModel(memory(),2048,32768,512));var content=new JPanel(new GridLayout(0,1,5,8));content.add(new JLabel("Minecraft memory (MB)"));content.add(ram);if(JOptionPane.showConfirmDialog(window,content,"Settings",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION){prefs().putInt("memory",(Integer)ram.getValue());}}
    void openOfficialLauncher() throws Exception { openOfficialLauncher(pack,release,memory(),this::message); }
    static void openOfficialLauncher(Pack pack,Pack.Release release,int memory,java.util.function.Consumer<String> message) throws Exception {
        if(release==null)throw new java.io.IOException("Wait for the pack release to load");
        pack.install(release,message);
        Minecraft runtime=new Minecraft(pack.home,message);
        Path root=officialLauncherRoot(runtime.root);
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
            throw new java.io.IOException("Official Minecraft Launcher was not found. Install Minecraft Launcher from minecraft.net, then retry.");
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
    public static void main(String[] args)throws Exception {
        Path home=defaultHome();for(int i=0;i<args.length;i++)if(args[i].equals("--home"))home=Path.of(args[++i]);
        var options=java.util.Arrays.asList(args);
        if(options.contains("--official-launcher")){var pack=new Pack(home);openOfficialLauncher(pack,pack.latest("stable"),8160,System.out::println);return;}
        if(options.contains("--official-offline-test")){var pack=new Pack(home);var release=pack.latest("stable");pack.install(release,System.out::println);var runtime=new Minecraft(home,System.out::println);Path root=officialLauncherRoot(runtime.root),gameDir=root.resolve("Aeromon");runtime.prepare(release.manifest(),root);runtime.syncOfficialInstance(release,gameDir);var profile=com.google.gson.JsonParser.parseString(Files.readString(root.resolve("launcher_profiles.json"))).getAsJsonObject();if(!"aeromon".equals(profile.get("selectedProfile").getAsString()))throw new java.io.IOException("Aeromon profile is not selected");var process=runtime.launch(release.manifest(),new Minecraft.Session("AeromonTest",java.util.UUID.nameUUIDFromBytes("OfflinePlayer:AeromonTest".getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString().replace("-",""),"0"),8160,true,root,gameDir);System.out.println("Offline Aeromon NeoForge process "+process.pid()+"; inspect "+gameDir.resolve("logs/latest.log"));return;}
        if(options.contains("--offline-test")){var pack=new Pack(home);var release=pack.latest("stable");pack.install(release,System.out::println);var process=new Minecraft(home,System.out::println).launchOffline(release.manifest(),6144);System.out.println("Offline Minecraft PID "+process.pid());return;}
        if(options.contains("--activate-update")){LauncherUpdate.activate(home,args[options.indexOf("--update-version")+1],Long.parseLong(args[options.indexOf("--activate-update")+1]));return;}
        if(java.util.Arrays.asList(args).contains("--login-test")){var session=Minecraft.login("6e76a2c9-5a48-41d6-9e6a-3aa2e60c36fa",System.out::println);System.out.println("Minecraft profile verified: "+session.name());return;}
        if(java.util.Arrays.asList(args).contains("--check")||java.util.Arrays.asList(args).contains("--install")||java.util.Arrays.asList(args).contains("--prepare")){
            var pack=new Pack(home);var release=pack.latest(java.util.Arrays.asList(args).contains("--beta")?"beta":"stable");System.out.println("Verified release "+release.version()+" · "+release.manifest().getAsJsonArray("files").size()+" files");
            if(java.util.Arrays.asList(args).contains("--install"))pack.install(release,System.out::println);
            if(java.util.Arrays.asList(args).contains("--prepare"))new Minecraft(home,System.out::println).prepare(release.manifest());
            return;
        }
        if(LauncherUpdate.bootstrap(home))return;
        final Path target=home;SwingUtilities.invokeLater(()->{try{UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());new Main(target);}catch(Exception e){JOptionPane.showMessageDialog(null,e.getMessage());}});
    }
}

