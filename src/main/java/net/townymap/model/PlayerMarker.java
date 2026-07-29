package net.townymap.model;

import java.util.Locale;

/** A live player position from squaremap's players.json. {@code yaw} is Minecraft's facing yaw. */
public record PlayerMarker(String name, String uuid, int x, int z, float yaw, String key) {
    public PlayerMarker(String name, String uuid, int x, int z, float yaw) {
        this(name, uuid, x, z, yaw, name == null ? "" : name.toLowerCase(Locale.ROOT));
    }
    public PlayerMarker(String name, String uuid, int x, int z) {
        this(name, uuid, x, z, 0f);
    }
}
