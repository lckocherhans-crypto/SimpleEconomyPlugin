package com.example.simpleeconomy;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public final class Msg {
    private static final LegacyComponentSerializer L = LegacyComponentSerializer.legacyAmpersand();

    private Msg() {}

    public static Component c(String s) {
        return L.deserialize(s);
    }

    /** Component without the default italic used for item names/lore. */
    public static Component lore(String s) {
        return c(s).decoration(TextDecoration.ITALIC, false);
    }

    public static String time(long ms) {
        long s = Math.max(0, ms / 1000);
        long h = s / 3600, m = (s % 3600) / 60;
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m";
        return s + "s";
    }
}
