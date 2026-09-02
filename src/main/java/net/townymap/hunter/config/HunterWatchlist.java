package net.townymap.hunter.config;

import net.townymap.TownyMapConfig;
import java.util.*;
import java.util.regex.Pattern;

/** Validation and case-insensitive watchlist mutations shared by every settings entry point. */
public final class HunterWatchlist {
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private HunterWatchlist() {}
    public static AddResult add(TownyMapConfig config, String input) {
        LinkedHashMap<String,String> names = new LinkedHashMap<>();
        for (String existing : config.hunterWatchlist) if (valid(existing)) names.putIfAbsent(key(existing), existing.trim());
        int before=names.size(), rejected=0;
        for(String raw:(input==null?"":input).split(",",-1)){String name=raw.trim();if(name.isEmpty())continue;if(!valid(name)){rejected++;continue;}names.putIfAbsent(key(name),name);}
        config.hunterWatchlist=new ArrayList<>(names.values()); config.save();
        return new AddResult(names.size()-before,rejected);
    }
    public static void remove(TownyMapConfig config,String name){String k=key(name);config.hunterWatchlist.removeIf(n->key(n).equals(k));config.disabledHunterNames.removeIf(n->key(n).equals(k));config.save();}
    public static boolean enabled(TownyMapConfig config,String name){String k=key(name);return config.disabledHunterNames==null||config.disabledHunterNames.stream().noneMatch(n->key(n).equals(k));}
    public static void setEnabled(TownyMapConfig config,String name,boolean enabled){String k=key(name);config.disabledHunterNames.removeIf(n->key(n).equals(k));if(!enabled)config.disabledHunterNames.add(name);config.save();}
    public static boolean valid(String name){return name!=null&&USERNAME.matcher(name.trim()).matches();}
    private static String key(String value){return value==null?"":value.trim().toLowerCase(Locale.ROOT);}
    public record AddResult(int added,int rejected){}
}
