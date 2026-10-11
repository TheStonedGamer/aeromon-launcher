package cc.aeromon.launcher;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;

/** Time-driven animation stays responsive while downloads run on the worker. */
final class LoadingSplash extends JWindow {
    String message="Preparing Aeromon";
    final Timer animation;
    final long started=System.nanoTime();
    final Image emblem;
    LoadingSplash(JFrame owner)throws Exception {
        super(owner);emblem=LauncherView.icon("icon.png",92,92).getImage();
        setSize(480,240);setLocationRelativeTo(owner);
        if(getGraphicsConfiguration().getDevice().isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSPARENT))setShape(new RoundRectangle2D.Double(0,0,480,240,36,36));
        setContentPane(new JPanel(){protected void paintComponent(Graphics graphics){
            Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.setPaint(new GradientPaint(0,0,new Color(18,37,52),480,240,new Color(8,17,27)));g.fillRect(0,0,480,240);
            double t=(System.nanoTime()-started)/1e9;
            g.setColor(new Color(86,137,155,30));for(int i=0;i<7;i++){int x=(int)((i*103-t*24)%650);if(x<0)x+=650;g.fillRoundRect(x-100,38+i%3*25,120,13,13,13);}
            int y=43+(int)(Math.sin(t*1.8)*4);g.drawImage(emblem,194,y,null);
            g.setFont(LauncherView.font(18,true));g.setColor(LauncherView.TEXT);center(g,"AEROMON",166);
            g.setFont(LauncherView.font(12,false));g.setColor(LauncherView.MUTED);String text=message.length()>62?message.substring(0,59)+"…":message;center(g,text,190);
            g.setColor(new Color(35,55,70));g.fillRoundRect(110,211,260,3,3,3);g.setColor(new Color(102,211,220));int x=110+(int)((Math.sin(t*2)+1)*.5*200);g.fillRoundRect(x,211,60,3,3,3);g.dispose();
        }});
        animation=new Timer(30,e->repaint());
    }
    static void center(Graphics2D g,String text,int y){g.drawString(text,(480-g.getFontMetrics().stringWidth(text))/2,y);}
    void showLoading(String text){message=text;setLocationRelativeTo(getOwner());setVisible(true);animation.start();}
    void finish(){animation.stop();setVisible(false);}
}
