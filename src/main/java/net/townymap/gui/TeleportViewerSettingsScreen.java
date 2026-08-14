package net.townymap.gui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.*;
import net.townymap.*;

/** Persistent Teleport Viewer preferences. Route results intentionally do not live here. */
public final class TeleportViewerSettingsScreen extends Screen {
    private final Screen parent; private TownyMapConfig cfg; private EditBox primary; private String status="";
    public TeleportViewerSettingsScreen(Screen parent){super(Component.translatable("townymapaddon.teleport.settings.title"));this.parent=parent;}
    @Override protected void init(){cfg=TownyMapMod.getConfig();int left=Math.max(12,(width-560)/2),x=left+270,y=58;
        primary=new EditBox(font,x,y,100,20,Component.translatable("townymapaddon.teleport.primary_town"));primary.setValue(cfg.teleportPrimaryHomeTown);primary.setMaxLength(64);addRenderableWidget(primary);
        addRenderableWidget(Button.builder(Component.translatable("townymapaddon.teleport.save_primary"),b->{String v=primary.getValue().trim();if(v.isBlank()||TownyMapMod.currentTownSnapshot().stream().anyMatch(t->t.name().equalsIgnoreCase(v))){cfg.teleportPrimaryHomeTown=v;cfg.save();status=Component.translatable("townymapaddon.teleport.saved").getString();}else status=Component.translatable("townymapaddon.teleport.invalid_town").getString();}).bounds(x+104,y,176,20).build());y=96;
        y=toggle(left,x,y,"townymapaddon.teleport.settings.advanced_enabled",cfg.teleportAdvancedEnabled,v->{cfg.teleportAdvancedEnabled=v;if(!v)cfg.teleportDefaultAdvanced=false;});
        y=commandMode(x,y);
        y=toggle(left,x,y,"townymapaddon.teleport.settings.town_spawns",cfg.teleportShowTownSpawns,v->cfg.teleportShowTownSpawns=v);
        y=toggle(left,x,y,"townymapaddon.teleport.settings.nation_spawns",cfg.teleportShowNationSpawns,v->cfg.teleportShowNationSpawns=v);
        y=toggle(left,x,y,"townymapaddon.teleport.settings.uncertain",cfg.teleportShowUncertain,v->cfg.teleportShowUncertain=v);
        y=toggle(left,x,y,"townymapaddon.teleport.settings.route_line",cfg.teleportRouteLineVisible,v->cfg.teleportRouteLineVisible=v);
        y=toggle(left,x,y,"townymapaddon.teleport.settings.target_marker",cfg.teleportDestinationMarkerVisible,v->cfg.teleportDestinationMarkerVisible=v);
        y=toggle(left,x,y,"townymapaddon.teleport.settings.arrival_marker",cfg.teleportArrivalMarkerVisible,v->cfg.teleportArrivalMarkerVisible=v);
        if(!TownyMapMod.isOnEarthMcServer())addRenderableWidget(Button.builder(Component.translatable(cfg.teleportAllowNonEarthMc?"options.on":"options.off"),b->{cfg.teleportAllowNonEarthMc=!cfg.teleportAllowNonEarthMc;cfg.save();rebuildWidgets();}).bounds(x,323,280,20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE,b->onClose()).bounds(width/2-50,height-30,100,20).build());
    }
    private int commandMode(int x,int y){addRenderableWidget(Button.builder(commandModeName(),b->{cfg.teleportCommandAction=(cfg.teleportCommandAction+1)%3;cfg.save();rebuildWidgets();}).bounds(x,y,280,20).build());return y+40;}
    private Component commandModeName(){Component name=Component.translatable(switch(cfg.teleportCommandAction){case 1->"townymapaddon.teleport.command_mode.chat";case 2->"townymapaddon.teleport.command_mode.execute";default->"townymapaddon.teleport.command_mode.clipboard";});return cfg.teleportCommandAction==2?name.copy().withStyle(ChatFormatting.RED,ChatFormatting.BOLD):name;}
    private int toggle(int left,int x,int y,String key,boolean value,java.util.function.Consumer<Boolean> setter){addRenderableWidget(Button.builder(Component.translatable(value?"options.on":"options.off"),b->{setter.accept(!value);cfg.save();rebuildWidgets();}).bounds(x,y,280,20).build());return y+27;}
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float d){int left=Math.max(12,(width-560)/2),right=Math.min(width-12,left+560);g.fill(left-12,16,right+12,height-8,0xF2080C10);g.fill(left-9,19,right+9,height-11,0xFA10171D);super.extractRenderState(g,mx,my,d);g.centeredText(font,title,width/2,30,0xFFFFFFFF);g.text(font,Component.translatable("townymapaddon.teleport.primary_town"),left+18,64,0xFFE4E9ED,false);String[] keys={"townymapaddon.teleport.settings.advanced_enabled","townymapaddon.teleport.settings.command_action","townymapaddon.teleport.settings.town_spawns","townymapaddon.teleport.settings.nation_spawns","townymapaddon.teleport.settings.uncertain","townymapaddon.teleport.settings.route_line","townymapaddon.teleport.settings.target_marker","townymapaddon.teleport.settings.arrival_marker"};int py=102;for(String key:keys){g.text(font,Component.translatable(key),left+18,py,0xFFE0E6EA,false);if(key.endsWith("command_action")){if(cfg.teleportCommandAction==2)g.text(font,Component.translatable("townymapaddon.settings.execute_command_warning"),left+288,py+20,0xFFFF5555,false);py+=40;}else py+=27;}g.text(font,Component.translatable("townymapaddon.teleport.settings.hint"),left+18,py+12,0xFFAFBAC2,false);if(!TownyMapMod.isOnEarthMcServer()){g.text(font,Component.translatable("townymapaddon.safety.non_earthmc_warning"),left+18,305,0xFFFF5555,false);g.text(font,Component.translatable("townymapaddon.safety.teleport_override"),left+18,329,0xFFFF7777,false);}if(!status.isBlank())g.text(font,status,left+18,height-48,0xFFFFCC66,false);}
    @Override public void onClose(){cfg.save();minecraft.gui.setScreen(parent);}
}
