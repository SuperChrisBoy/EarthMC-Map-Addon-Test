package net.townymap.hunter.alert;

import java.util.List;
import java.util.Arrays;
import net.minecraft.network.chat.Component;

/** A presentation-neutral hunter event. Trackers never write directly to chat. */
public record HunterEvent(String key, Severity severity, Type type, Component title, List<Component> lines, long atMs,
                          Integer x, Integer z) {
    public enum Severity { INFO, NOTICE, WARNING, CRITICAL }
    public enum Type { STATUS, MOVEMENT, TELEPORT, COMBAT, RISK, CANDIDATE }
    public static HunterEvent normal(String key, String title, long at, String... lines) {
        return normal(key,Component.literal(title),at,Arrays.stream(lines).map(Component::literal).toArray(Component[]::new));
    }
    public static HunterEvent warning(String key, String title, long at, String... lines) {
        return warning(key,Component.literal(title),at,Arrays.stream(lines).map(Component::literal).toArray(Component[]::new));
    }
    public static HunterEvent urgent(String key, String title, long at, String... lines) {
        return urgent(key,Component.literal(title),at,Arrays.stream(lines).map(Component::literal).toArray(Component[]::new));
    }
    public static HunterEvent normal(String key,Component title,long at,Component... lines){return new HunterEvent(key,Severity.INFO,Type.STATUS,title,List.of(lines),at,null,null);}
    public static HunterEvent warning(String key,Component title,long at,Component... lines){return new HunterEvent(key,Severity.WARNING,Type.RISK,title,List.of(lines),at,null,null);}
    public static HunterEvent urgent(String key,Component title,long at,Component... lines){return new HunterEvent(key,Severity.CRITICAL,Type.RISK,title,List.of(lines),at,null,null);}
    public HunterEvent typed(Type value) { return new HunterEvent(key,severity,value,title,lines,atMs,x,z); }
    public HunterEvent positioned(int px,int pz) { return new HunterEvent(key,severity,type,title,lines,atMs,px,pz); }
}
