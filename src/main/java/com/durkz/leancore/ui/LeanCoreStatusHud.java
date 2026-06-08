package com.durkz.leancore.ui;

import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

public class LeanCoreStatusHud extends CustomUIHud {

    public static final String KEY = "durkz:leancore_status";

    private String line1 = "";
    private String line2 = "";

    public LeanCoreStatusHud(PlayerRef playerRef) {
        super(playerRef, KEY, 40);
    }

    public void setLines(String line1, String line2) {
        this.line1 = line1 == null ? "" : line1;
        this.line2 = line2 == null ? "" : line2;
    }

    public void pushUpdate() {
        UICommandBuilder builder = new UICommandBuilder();
        build(builder);
        update(false, builder);
    }

    @Override
    protected void build(UICommandBuilder ui) {
        ui.append("Hud/Durkz_LeanCore_Status.ui");
        ui.set("#LcLine1.Text", line1);
        ui.set("#LcLine2.Text", line2);
    }
}
