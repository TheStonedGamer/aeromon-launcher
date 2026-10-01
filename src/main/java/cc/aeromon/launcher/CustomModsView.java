package cc.aeromon.launcher;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;

final class CustomModsView {
    static void open(Main app){try{
        var mods=new CustomMods(app.pack);var dialog=new JDialog(app.window,"Client mods",false);var model=new DefaultListModel<CustomMods.Mod>();var list=new JList<>(model);list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        Runnable reload=()->{try{model.clear();for(var mod:mods.list())model.addElement(mod);}catch(Exception e){JOptionPane.showMessageDialog(dialog,e.getMessage());}};
        JPanel content=new JPanel(new BorderLayout(12,12));content.setBorder(BorderFactory.createEmptyBorder(18,18,18,18));content.add(new JLabel("Your additions are preserved during pack updates. Close Minecraft to change them."),BorderLayout.NORTH);content.add(new JScrollPane(list),BorderLayout.CENTER);
        JPanel buttons=new JPanel(new FlowLayout(FlowLayout.LEFT));JButton add=new JButton("Add JARs…"),toggle=new JButton("Enable / disable"),remove=new JButton("Remove"),folder=new JButton("Open mods folder");buttons.add(add);buttons.add(toggle);buttons.add(remove);buttons.add(folder);content.add(buttons,BorderLayout.SOUTH);
        add.addActionListener(e->{if(app.release==null)return;JFileChooser chooser=new JFileChooser();chooser.setFileFilter(new FileNameExtensionFilter("Java mod files (*.jar)","jar"));chooser.setMultiSelectionEnabled(true);if(chooser.showOpenDialog(dialog)==JFileChooser.APPROVE_OPTION)app.run(()->{try{for(var file:chooser.getSelectedFiles())mods.add(file.toPath(),app.release);app.message("Custom client mods imported");}finally{Main.ui(reload);}});});
        toggle.addActionListener(e->{var selected=list.getSelectedValue();if(selected!=null)app.run(()->{try{mods.toggle(selected);}finally{Main.ui(reload);}});});
        remove.addActionListener(e->{var selected=list.getSelectedValue();if(selected!=null)app.run(()->{try{mods.remove(selected);}finally{Main.ui(reload);}});});
        folder.addActionListener(e->app.run(()->{java.nio.file.Files.createDirectories(app.pack.instance.resolve("mods"));Desktop.getDesktop().open(app.pack.instance.resolve("mods").toFile());}));
        reload.run();dialog.setContentPane(content);dialog.setSize(720,390);dialog.setLocationRelativeTo(app.window);dialog.setVisible(true);
    }catch(Exception e){JOptionPane.showMessageDialog(app.window,e.getMessage());}}
}
