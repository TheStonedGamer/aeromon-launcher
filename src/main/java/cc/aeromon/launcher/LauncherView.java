package cc.aeromon.launcher;

import javax.swing.*;
import javax.swing.border.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;

/** Aeromon's shared desktop presentation. Actions are owned by Main. */
final class LauncherView extends JPanel {
    static final Color BG=new Color(12,18,27), SURFACE=new Color(20,29,42), LINE=new Color(39,52,67);
    static final Color TEXT=new Color(230,236,242), MUTED=new Color(150,166,181), CYAN=new Color(70,199,219), GOLD=new Color(233,190,92);
    final Main app;
    LauncherView(Main app) throws IOException {
        this.app=app;setLayout(new BorderLayout());setBackground(BG);
        app.window.setIconImage(image("icon.png"));
        JPanel rail=new JPanel();rail.setBackground(SURFACE);rail.setLayout(new BoxLayout(rail,BoxLayout.Y_AXIS));rail.setBorder(new CompoundBorder(new MatteBorder(0,0,0,1,LINE),new EmptyBorder(25,20,20,20)));rail.setPreferredSize(new Dimension(224,600));add(rail,BorderLayout.WEST);
        JPanel brand=new JPanel(new BorderLayout(10,0));brand.setOpaque(false);brand.add(new JLabel(icon("icon.png",42,42)),BorderLayout.WEST);JPanel brandText=column();brandText.add(text("AEROMON",20,TEXT,true));brandText.add(text("LAUNCHER",10,MUTED,true));brand.add(brandText,BorderLayout.CENTER);brand.setMaximumSize(new Dimension(185,45));rail.add(brand);
        rail.add(Box.createVerticalStrut(30));rail.add(text("YOUR MODPACK",10,MUTED,true));rail.add(Box.createVerticalStrut(13));
        JLabel cover=new JLabel(icon("cover.png",180,180));cover.setBorder(new LineBorder(new Color(69,104,121)));rail.add(cover);rail.add(Box.createVerticalStrut(12));rail.add(text("Aeromon",17,TEXT,true));rail.add(text("Pixelmon + Create Aeronautics",10,MUTED,false));rail.add(Box.createVerticalStrut(20));
        JButton instance=nav("Instance folder");instance.addActionListener(e->app.run(()->Desktop.getDesktop().open(app.pack.instance.toFile())));rail.add(instance);rail.add(Box.createVerticalStrut(6));JButton settings=nav("Settings");settings.addActionListener(e->app.settings());rail.add(settings);JButton mods=nav("Client mods");mods.addActionListener(e->CustomModsView.open(app));rail.add(mods);rail.add(Box.createVerticalStrut(12));Main.style(app.official,new Color(31,49,66),TEXT);app.official.setMaximumSize(new Dimension(185,40));app.official.setToolTipText("Open the official Minecraft Launcher to complete Microsoft sign-in.");rail.add(app.official);rail.add(Box.createVerticalStrut(6));Main.style(app.offline,SURFACE,TEXT);app.offline.setMaximumSize(new Dimension(185,40));app.offline.setToolTipText("Test the pack locally without signing in. Multiplayer is disabled.");rail.add(app.offline);
        rail.add(Box.createVerticalGlue());rail.add(text("RELEASE CHANNEL",10,MUTED,true));rail.add(Box.createVerticalStrut(8));app.channel.setMaximumSize(new Dimension(180,32));app.channel.setBackground(BG);app.channel.setForeground(TEXT);rail.add(app.channel);rail.add(Box.createVerticalStrut(21));
        app.account.setForeground(MUTED);app.account.setFont(font(12,false));rail.add(app.account);rail.add(Box.createVerticalStrut(9));Main.style(app.signIn,new Color(31,49,66),TEXT);app.signIn.setText("Sign in with Microsoft");app.signIn.setMaximumSize(new Dimension(185,40));rail.add(app.signIn);
        for(Component component:rail.getComponents())if(component instanceof JComponent c)c.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel content=new JPanel(new BorderLayout(0,20));content.setOpaque(false);content.setBorder(new EmptyBorder(25,28,25,28));add(content,BorderLayout.CENTER);
        JPanel heading=new JPanel(new BorderLayout());heading.setOpaque(false);JPanel title=column();title.add(text("Aeromon",28,TEXT,true));title.add(text("Pixelmon meets Create Aeronautics",13,MUTED,false));heading.add(title,BorderLayout.WEST);
        JLabel address=text("mc.aeromon.cc",12,CYAN,false);address.setBorder(new EmptyBorder(0,12,0,0));heading.add(address,BorderLayout.EAST);content.add(heading,BorderLayout.NORTH);
        JPanel center=new JPanel(new BorderLayout(0,16));center.setOpaque(false);content.add(center,BorderLayout.CENTER);
        center.add(new Hero(),BorderLayout.CENTER);
        JPanel release=new JPanel(new BorderLayout(0,10));release.setBackground(SURFACE);release.setBorder(new CompoundBorder(new LineBorder(LINE),new EmptyBorder(18,20,18,20)));app.version.setFont(font(12,true));app.version.setForeground(CYAN);release.add(app.version,BorderLayout.NORTH);
        app.notes.setEditable(false);app.notes.setLineWrap(true);app.notes.setWrapStyleWord(true);app.notes.setBackground(SURFACE);app.notes.setForeground(MUTED);app.notes.setFont(font(12,false));app.notes.setRows(2);release.add(app.notes,BorderLayout.CENTER);center.add(release,BorderLayout.SOUTH);
        JPanel bottom=new JPanel(new BorderLayout(0,12));bottom.setOpaque(false);JPanel buttons=new JPanel(new BorderLayout(12,0));buttons.setOpaque(false);JPanel management=new JPanel(new FlowLayout(FlowLayout.LEFT,8,0));management.setOpaque(false);Main.style(app.install,CYAN,BG);Main.style(app.repair,SURFACE,TEXT);management.add(app.install);management.add(app.repair);buttons.add(management,BorderLayout.WEST);Main.style(app.play,GOLD,BG);app.play.setPreferredSize(new Dimension(195,46));buttons.add(app.play,BorderLayout.EAST);bottom.add(buttons,BorderLayout.NORTH);
        JPanel detail=new JPanel(new BorderLayout(0,7));detail.setOpaque(false);app.progress.setBorderPainted(false);app.progress.setBackground(SURFACE);app.progress.setForeground(CYAN);app.progress.setPreferredSize(new Dimension(10,5));app.progress.setVisible(false);detail.add(app.progress,BorderLayout.NORTH);app.status.setForeground(MUTED);app.status.setFont(font(11,false));detail.add(app.status,BorderLayout.CENTER);bottom.add(detail,BorderLayout.SOUTH);content.add(bottom,BorderLayout.SOUTH);
    }
    static Font font(int size,boolean bold){return new Font(System.getProperty("os.name").contains("Windows")?"Segoe UI":"Dialog",bold?Font.BOLD:Font.PLAIN,size);}
    static JLabel text(String value,int size,Color color,boolean bold){JLabel label=new JLabel(value);label.setFont(font(size,bold));label.setForeground(color);label.setAlignmentX(0);return label;}
    static JPanel column(){JPanel p=new JPanel();p.setLayout(new BoxLayout(p,BoxLayout.Y_AXIS));p.setOpaque(false);return p;}
    static JButton nav(String text){JButton b=new JButton(text);Main.style(b,SURFACE,MUTED);b.setBorder(new EmptyBorder(10,0,10,0));b.setHorizontalAlignment(SwingConstants.LEFT);b.setAlignmentX(0);b.setMaximumSize(new Dimension(180,35));return b;}
    static BufferedImage image(String name)throws IOException {var stream=LauncherView.class.getResourceAsStream("/branding/"+name);if(stream==null)throw new IOException("Missing Aeromon artwork: "+name);try(stream){return ImageIO.read(stream);}}
    static ImageIcon icon(String name,int width,int height)throws IOException {return new ImageIcon(image(name).getScaledInstance(width,height,Image.SCALE_SMOOTH));}
    static final class Hero extends JPanel {
        final BufferedImage art;
        Hero()throws IOException {BufferedImage loaded;try{loaded=image("launcher-banner.png");}catch(IOException e){loaded=image("cover.png");}art=loaded;setPreferredSize(new Dimension(780,360));}
        protected void paintComponent(Graphics graphics){super.paintComponent(graphics);Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);int w=getWidth(),h=getHeight();double scale=Math.max((double)w/art.getWidth(),(double)h/art.getHeight());int aw=(int)(art.getWidth()*scale),ah=(int)(art.getHeight()*scale);g.drawImage(art,(w-aw)/2,(h-ah)/2,aw,ah,null);
            g.setPaint(new GradientPaint(0,h/2,new Color(7,18,30,0),0,h,new Color(7,18,30,235)));g.fillRect(0,0,w,h);g.setColor(TEXT);g.setFont(font(23,true));g.drawString("Your next adventure starts here.",23,h-46);g.setColor(new Color(197,216,226));g.setFont(font(12,false));g.drawString("Catch, build, explore - and take to the skies.",23,h-23);g.setColor(LINE);g.drawRect(0,0,w-1,h-1);g.dispose();
        }
    }
}
