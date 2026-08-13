package net.townymap.hunter.cache;

import net.townymap.model.PlayerMarker;
import java.util.*;

/** Bounded, time-evicted map-position history for combat inference. */
public final class PositionHistoryCache {
    private static final long WINDOW_MS = 5 * 60_000L;
    private static final int MAX_SAMPLES = 180;
    private final Map<String, Deque<Sample>> samples = new HashMap<>();
    public void record(List<PlayerMarker> players, long now) {
        for (PlayerMarker p : players) {
            if (p.name() == null || p.name().isBlank()) continue;
            Deque<Sample> q = samples.computeIfAbsent(p.name().toLowerCase(Locale.ROOT), k -> new ArrayDeque<>());
            Sample last = q.peekLast();
            if (last == null || last.x != p.x() || last.z != p.z() || now - last.atMs >= 1000) q.addLast(new Sample(p.x(), p.z(), now));
            while (q.size() > MAX_SAMPLES || (!q.isEmpty() && now - q.peekFirst().atMs > WINDOW_MS)) q.removeFirst();
        }
    }
    public Sample latestBefore(String name, long now) {
        Deque<Sample> q = samples.get(name == null ? "" : name.toLowerCase(Locale.ROOT));
        if (q == null) return null;
        Iterator<Sample> it = q.descendingIterator();
        while (it.hasNext()) { Sample s = it.next(); if (s.atMs <= now) return s; }
        return null;
    }
    public record Sample(int x, int z, long atMs) {}
}
