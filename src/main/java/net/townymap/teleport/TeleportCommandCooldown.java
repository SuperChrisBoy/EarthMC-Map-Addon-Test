package net.townymap.teleport;

/** Shared one-second gate for clipboard, chat-prefill, and command execution actions. */
public final class TeleportCommandCooldown {
    public static final long COOLDOWN_MS=1_000L;
    private long lastActionAt=Long.MIN_VALUE;
    public boolean tryAcquire(long now){if(lastActionAt!=Long.MIN_VALUE&&now-lastActionAt<COOLDOWN_MS)return false;lastActionAt=now;return true;}
    public long remaining(long now){return lastActionAt==Long.MIN_VALUE?0:Math.max(0,COOLDOWN_MS-(now-lastActionAt));}
}
