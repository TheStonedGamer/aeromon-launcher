package cc.aeromon.launcher;

import javax.swing.*;
import java.awt.*;

final class PlayerSyncView {
    static void open(Main app,Pack pack,String branch){
        Minecraft.Session account=app.session;
        if(account==null){JOptionPane.showMessageDialog(app.window,"Sign in with Microsoft first");return;}
        Object[] actions={"Upload this computer","Restore cloud snapshot","Open recovery copies","Cancel"};
        String description="Minecraft account: "+account.name()+"\nBranch: "+branch+" (stored separately from the other branch)\n\n"
            +"Stores Minecraft options/keybinds, JourneyMap maps, waypoints and preferences on Aeromon.\n"
            +"RAM limits, sign-in credentials, mods, worlds and shared resource packs stay local.\n"
            +"Your Minecraft sign-in is checked by Aeromon to protect access to your UUID.\n"
            +"Close Minecraft first. These controls transfer the Aeromon launcher's instance.";
        int action=JOptionPane.showOptionDialog(app.window,description,"Player cloud storage",JOptionPane.DEFAULT_OPTION,JOptionPane.PLAIN_MESSAGE,null,actions,actions[0]);
        if(action<0||action==3)return;
        if(action==1&&JOptionPane.showConfirmDialog(app.window,"Restore "+branch+" settings and JourneyMap from cloud?\nMatching local files will be replaced. A recovery copy is saved first.\nLocal map files absent from the snapshot are kept.","Restore player data",JOptionPane.OK_CANCEL_OPTION,JOptionPane.WARNING_MESSAGE)!=JOptionPane.OK_OPTION)return;
        app.run(()->{
            if(app.game!=null&&app.game.isAlive())throw new java.io.IOException("Close Minecraft before transferring player data");
            var sync=new PlayerSync(pack,account,branch,(completed,total)->app.transferProgress(action==0?"Uploading cloud backup":"Restoring cloud backup",completed,total));
            if(action==0){app.message("Uploading "+branch+" settings and JourneyMap...");sync.upload();app.message(branch+" player data uploaded for "+account.name());}
            else if(action==1){app.message("Restoring "+branch+" settings and JourneyMap...");sync.restore();app.message(branch+" player data restored; previous files retained in recovery copies");}
            else Desktop.getDesktop().open(sync.state.toFile());
        });
    }
}
