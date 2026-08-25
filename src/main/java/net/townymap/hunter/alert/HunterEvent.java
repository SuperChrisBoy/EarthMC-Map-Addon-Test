package net.townymap.hunter.alert;

import java.util.List;
import java.util.Arrays;
import net.minecraft.text.Text;

/** A presentation-neutral hunter event. Trackers never write directly to chat. */
public record HunterEvent(String key, Severity severity, Type type, Text title, List<Text> lines, long atMs,
                          Integer x, Integer z) {
    public enum Severity { INFO, NOTICE, WARNING, CRITICAL }
    public enum Type { STATUS, MOVEMENT, TELEPORT, COMBAT, RISK, CANDIDATE }
    public static HunterEvent normal(String key, String title, long at, String... lines) {
        return normal(key,Text.literal(title),at,Arrays.stream(lines).map(Text::literal).toArray(Text[]::new));
    }
    public static HunterEvent warning(String key, String title, long at, String... lines) {
        return warning(key,Text.literal(title),at,Arrays.stream(lines).map(Text::literal).toArray(Text[]::new));
    }
    public static HunterEvent urgent(String key, String title, long at, String... lines) {
        return urgent(key,Text.literal(title),at,Arrays.stream(lines).map(Text::literal).toArray(Text[]::new));
    }
    public static HunterEvent normal(String key,Text title,long at,Text... lines){return new HunterEvent(key,Severity.INFO,Type.STATUS,title,List.of(lines),at,null,null);}
    public static HunterEvent warning(String key,Text title,long at,Text... lines){return new HunterEvent(key,Severity.WARNING,Type.RISK,title,List.of(lines),at,null,null);}
    public static HunterEvent urgent(String key,Text title,long at,Text... lines){return new HunterEvent(key,Severity.CRITICAL,Type.RISK,title,List.of(lines),at,null,null);}
    public HunterEvent typed(Type value) { return new HunterEvent(key,severity,value,title,lines,atMs,x,z); }
    public HunterEvent positioned(int px,int pz) { return new HunterEvent(key,severity,type,title,lines,atMs,px,pz); }
}
