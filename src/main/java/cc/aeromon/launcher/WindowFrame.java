package cc.aeromon.launcher;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Path2D;

/** Shaped, branded frame with standard desktop window controls. */
final class WindowFrame extends JPanel {
    final JFrame window;
    Point dragOffset;
    Rectangle restoreBounds;
    boolean maximized;
    WindowFrame(Main app,LauncherView view)throws Exception {
        window=app.window;window.setUndecorated(true);
        setLayout(new BorderLayout());setBackground(LauncherView.BG);setBorder(new EmptyBorder(1,1,1,1));
        JPanel header=new JPanel(new BorderLayout());header.setBackground(new Color(15,24,35));header.setPreferredSize(new Dimension(100,42));header.setBorder(new EmptyBorder(0,16,0,10));
        JLabel name=LauncherView.text("AEROMON LAUNCHER",11,LauncherView.MUTED,true);name.setIcon(LauncherView.icon("icon.png",24,24));name.setIconTextGap(9);header.add(name,BorderLayout.WEST);
        JPanel controls=new JPanel(new FlowLayout(FlowLayout.RIGHT,1,2));controls.setOpaque(false);JButton minimize=control("−","Minimize"),maximize=control("□","Maximize or restore"),close=control("×","Close launcher");
        minimize.addActionListener(e->window.setState(Frame.ICONIFIED));maximize.addActionListener(e->maximize());close.addActionListener(e->window.dispatchEvent(new WindowEvent(window,WindowEvent.WINDOW_CLOSING)));controls.add(minimize);controls.add(maximize);controls.add(close);header.add(controls,BorderLayout.EAST);
        MouseAdapter drag=new MouseAdapter(){
            public void mousePressed(MouseEvent e){dragOffset=new Point(e.getXOnScreen()-window.getX(),e.getYOnScreen()-window.getY());}
            public void mouseDragged(MouseEvent e){if(!maximized&&dragOffset!=null)window.setLocation(e.getXOnScreen()-dragOffset.x,e.getYOnScreen()-dragOffset.y);}
            public void mouseClicked(MouseEvent e){if(e.getClickCount()==2)maximize();}
        };header.addMouseListener(drag);header.addMouseMotionListener(drag);name.addMouseListener(drag);name.addMouseMotionListener(drag);add(header,BorderLayout.NORTH);add(view,BorderLayout.CENTER);
        JPanel bottom=new JPanel(new BorderLayout());bottom.setBackground(LauncherView.BG);bottom.setPreferredSize(new Dimension(100,14));bottom.setBorder(new EmptyBorder(0,0,0,24));JLabel grip=LauncherView.text("◢",12,LauncherView.MUTED,false);grip.setToolTipText("Resize launcher");grip.setCursor(Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR));bottom.add(grip,BorderLayout.EAST);add(bottom,BorderLayout.SOUTH);
        MouseAdapter resize=new MouseAdapter(){Point start;Dimension size;public void mousePressed(MouseEvent e){start=e.getLocationOnScreen();size=window.getSize();}public void mouseDragged(MouseEvent e){if(maximized)return;Point point=e.getLocationOnScreen();window.setSize(Math.max(window.getMinimumSize().width,size.width+point.x-start.x),Math.max(window.getMinimumSize().height,size.height+point.y-start.y));}};grip.addMouseListener(resize);grip.addMouseMotionListener(resize);
        window.addComponentListener(new ComponentAdapter(){public void componentResized(ComponentEvent e){shape();}});shape();
    }
    JButton control(String glyph,String tooltip){JButton button=new JButton(){
        protected void paintComponent(Graphics graphics){Graphics2D g=(Graphics2D)graphics.create();if(getModel().isRollover()){g.setColor(tooltip.startsWith("Close")?new Color(113,42,49):new Color(37,53,69));g.fillRect(0,0,getWidth(),getHeight());}g.setColor(LauncherView.TEXT);int x=getWidth()/2-5,y=getHeight()/2-5;if(tooltip.equals("Minimize"))g.drawLine(x,y+9,x+10,y+9);else if(tooltip.startsWith("Maximize"))g.drawRect(x,y,10,10);else{g.drawLine(x,y,x+10,y+10);g.drawLine(x+10,y,x,y+10);}g.dispose();}
    };button.setToolTipText(tooltip);button.getAccessibleContext().setAccessibleName(tooltip);button.setRolloverEnabled(true);button.setContentAreaFilled(false);button.setBorder(new EmptyBorder(0,0,0,0));button.setFocusPainted(false);button.setPreferredSize(new Dimension(38,35));return button;}
    void maximize(){
        if(!maximized){restoreBounds=window.getBounds();Rectangle screen=window.getGraphicsConfiguration().getBounds();Insets insets=Toolkit.getDefaultToolkit().getScreenInsets(window.getGraphicsConfiguration());maximized=true;window.setShape(null);window.setBounds(screen.x+insets.left,screen.y+insets.top,screen.width-insets.left-insets.right,screen.height-insets.top-insets.bottom);}
        else{maximized=false;window.setBounds(restoreBounds);shape();}
    }
    static Shape outline(int w,int h){double cut=22;Path2D path=new Path2D.Double();path.moveTo(cut,0);path.lineTo(w-cut,0);path.lineTo(w,cut);path.lineTo(w,h-cut);path.lineTo(w-cut,h);path.lineTo(cut,h);path.lineTo(0,h-cut);path.lineTo(0,cut);path.closePath();return path;}
    void shape(){if(!maximized&&window.getGraphicsConfiguration().getDevice().isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSPARENT))window.setShape(outline(window.getWidth(),window.getHeight()));repaint();}
    protected void paintChildren(Graphics graphics){super.paintChildren(graphics);Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setColor(new Color(65,90,106));g.draw(maximized?new Rectangle(0,0,getWidth()-1,getHeight()-1):outline(getWidth()-1,getHeight()-1));g.dispose();}
}
