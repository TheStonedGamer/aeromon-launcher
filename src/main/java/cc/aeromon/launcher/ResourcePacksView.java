package cc.aeromon.launcher;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;

final class ResourcePacksView {
    static void open(Main app){try{
        var packs=new ResourcePacks(app.baseHome);
        var dialog=new JDialog(app.window,"Shared resource packs",false);
        var model=new DefaultListModel<String>();var list=new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        Runnable reload=()->{try{model.clear();packs.list().forEach(model::addElement);}catch(Exception e){JOptionPane.showMessageDialog(dialog,e.getMessage());}};
        var content=new JPanel(new BorderLayout(12,12));content.setBorder(BorderFactory.createEmptyBorder(18,18,18,18));
        content.add(new JLabel("Shared by Stable and Test. Close Minecraft to change packs. Select packs in Minecraft's Resource Packs settings."),BorderLayout.NORTH);
        content.add(new JScrollPane(list),BorderLayout.CENTER);
        var buttons=new JPanel(new FlowLayout(FlowLayout.LEFT));var add=new JButton("Add ZIPs…");var remove=new JButton("Remove");var folder=new JButton("Open shared folder");var sync=new JButton("Sync instances");
        buttons.add(add);buttons.add(remove);buttons.add(folder);buttons.add(sync);content.add(buttons,BorderLayout.SOUTH);
        add.addActionListener(e->{var chooser=new JFileChooser();chooser.setMultiSelectionEnabled(true);chooser.setFileFilter(new FileNameExtensionFilter("Resource packs (*.zip)","zip"));if(chooser.showOpenDialog(dialog)==JFileChooser.APPROVE_OPTION){var selected=chooser.getSelectedFiles();app.run(()->{try{for(var file:selected)packs.add(file.toPath());app.message("Shared resource packs installed");}finally{Main.ui(reload);}});}});
        remove.addActionListener(e->{String selected=list.getSelectedValue();if(selected!=null&&JOptionPane.showConfirmDialog(dialog,"Remove "+selected+" from the shared collection and managed instance copies?","Remove resource pack",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION)app.run(()->{try{packs.remove(selected);}finally{Main.ui(reload);}});});
        folder.addActionListener(e->app.run(()->Desktop.getDesktop().open(packs.storage.toFile())));
        sync.addActionListener(e->app.run(()->{packs.stopped();packs.syncInstances();app.message("Shared resource packs synced");}));
        reload.run();dialog.setContentPane(content);dialog.setSize(850,390);dialog.setLocationRelativeTo(app.window);dialog.setVisible(true);
    }catch(Exception e){JOptionPane.showMessageDialog(app.window,e.getMessage());}}
}
