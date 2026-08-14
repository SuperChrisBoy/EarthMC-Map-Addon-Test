package net.townymap.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.townymap.model.VotePartyStatus;

/** Context-aware vote-party display that avoids Minecraft and server UI. */
public final class VotePartyOverlay{
    /** Vertical space reserved before Minecraft starts rendering its boss-bar stack. */
    public static final int HUD_RESERVED_HEIGHT=22;
    private VotePartyOverlay(){}
    public static void renderMenu(GuiGraphicsExtractor g,int sw,int sh,VotePartyStatus s,boolean loading,Screen screen){int y=8;if(screen instanceof TitleScreen){int firstButton=screen.children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast).mapToInt(AbstractWidget::getY).filter(v->v>60).min().orElse(sh/2+20);y=Math.max(58,firstButton-24);}renderCentered(g,sw,y,s,loading);}
    public static void renderWorldMap(GuiGraphicsExtractor g,int sw,VotePartyStatus s,boolean loading){renderCentered(g,sw,8,s,loading);}
    public static void renderHud(GuiGraphicsExtractor g,int sw,VotePartyStatus s,boolean loading){renderCentered(g,sw,3,s,loading);}
    private static void renderCentered(GuiGraphicsExtractor g,int sw,int y,VotePartyStatus s,boolean loading){if(s==null&&!loading)return;Minecraft mc=Minecraft.getInstance();Component text=s==null?Component.translatable("townymapaddon.voteparty.loading"):Component.translatable("townymapaddon.voteparty.remaining",s.remaining(),s.percent());int w=mc.font.width(text)+14,x=Math.max(4,(sw-w)/2);g.fill(x-1,y-1,x+w+1,y+17,0xFF344047);g.fill(x,y,x+w,y+16,0xE810171B);if(s!=null){int progress=(int)Math.round((w-2)*s.percent()/100.0);g.fill(x+1,y+14,x+1+progress,y+16,0xFF55CC88);}g.text(mc.font,text,x+7,y+4,s==null?0xFFFFCC66:0xFFFFFFFF,false);}
}
