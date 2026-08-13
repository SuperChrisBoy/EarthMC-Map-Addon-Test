package net.townymap.hunter.tracking;

import java.util.ArrayDeque;
import java.util.Deque;

/** Five-minute bounded exposure samples, updated at most once per second. */
public final class UserExposureTracker {
    private final Deque<Sample> history = new ArrayDeque<>();
    private boolean visible;
    private long stateSince;
    private long lastSample;
    public void update(boolean nowVisible, boolean wilderness, long now) {
        if (stateSince == 0 || visible != nowVisible) { visible = nowVisible; stateSince = now; }
        if (now - lastSample >= 1000) { history.addLast(new Sample(now, visible, visible && wilderness)); lastSample = now; }
        while (!history.isEmpty() && now - history.peekFirst().atMs > 300_000) history.removeFirst();
    }
    public boolean visible() { return visible; }
    public long stateDurationMs(long now) { return stateSince == 0 ? 0 : now - stateSince; }
    public int exposurePercent() { if (history.isEmpty()) return 0; return (int)Math.round(100.0 * history.stream().filter(Sample::visible).count() / history.size()); }
    public record Sample(long atMs, boolean visible, boolean wildernessVisible) {}
}
