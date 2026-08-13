package net.townymap.hunter.alert;

import net.townymap.TownyMapConfig;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;

/** Routes routine events to a short HUD queue and warnings to HUD plus optional chat. */
public final class HunterNotificationManager {
    private static final long DUPLICATE_COOLDOWN_MS = 15_000L;
    private static final int MAX_QUEUE = 6;
    private final TownyMapConfig config;
    private final Consumer<String> chat;
    private final Deque<HunterEvent> queue = new ArrayDeque<>();
    private final Deque<HunterEvent> history = new ArrayDeque<>();
    private final Map<String,Long> last = new HashMap<>();

    public HunterNotificationManager(TownyMapConfig config, Consumer<String> chat) {
        this.config = config; this.chat = chat;
    }
    public void publish(HunterEvent event) {
        Long previous = last.get(event.key());
        if (previous != null && event.atMs() - previous < DUPLICATE_COOLDOWN_MS) return;
        last.put(event.key(), event.atMs());
        queue.addLast(event);
        while (queue.size() > MAX_QUEUE) queue.removeFirst();
        history.addFirst(event);
        while (history.size() > config.hunterActivityMaxEvents) history.removeLast();
        boolean warning = event.severity().ordinal() >= HunterEvent.Severity.WARNING.ordinal();
        if ((warning && config.hunterWarningsInChat) || (!warning && config.hunterNotificationsInChat)) {
            chat.accept((warning ? "§c"+Component.translatable("townymapaddon.hunter.chat.warning_prefix").getString()+" §f" : "§e"+Component.translatable("townymapaddon.hunter.chat.notification_prefix").getString()+" §f") + event.title()
                    .getString() + (event.lines().isEmpty() ? "" : ": " + event.lines().getFirst().getString().replaceAll("§.", "")));
        }
    }
    public List<String> hudLines(long now) {
        long duration = Math.max(2, config.hunterNormalEventDurationSecs) * 1000L;
        queue.removeIf(e -> now - e.atMs() > (e.severity().ordinal() < HunterEvent.Severity.WARNING.ordinal() ? duration : Math.max(duration, 10_000L)));
        if (!config.hunterShowRecentEvents || queue.isEmpty()) return List.of();
        HunterEvent event = queue.stream().max(Comparator.comparingInt((HunterEvent e) -> e.severity().ordinal())
                .thenComparingLong(HunterEvent::atMs)).orElse(null);
        if (event == null) return List.of();
        ArrayList<String> out = new ArrayList<>();
        out.add(event.severity() == HunterEvent.Severity.CRITICAL ? "§c§l⚠ " + event.title().getString()
                : event.severity() == HunterEvent.Severity.WARNING ? "§c§l"+Component.translatable("townymapaddon.hunter.hud.warning").getString() : "§e§l"+Component.translatable("townymapaddon.hunter.hud.recent_event").getString());
        if (event.severity() != HunterEvent.Severity.CRITICAL) out.add("§f" + event.title().getString());
        for (var line : event.lines()) out.add("§7" + line.getString());
        return out;
    }
    public List<HunterEvent> history() { return List.copyOf(history); }
}
