package com.example.simpleeconomy;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.util.Locale;

public class MoneyExpansion extends PlaceholderExpansion {
    private final SimpleEconomyPlugin plugin;
    private final EconomyManager eco;

    public MoneyExpansion(SimpleEconomyPlugin plugin, EconomyManager eco) {
        this.plugin = plugin;
        this.eco = eco;
    }

    @Override
    public String getIdentifier() {
        return "simpleeconomy";
    }

    @Override
    public String getAuthor() {
        return "SimpleEconomy";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true; // survives /papi reload
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null) return "";
        double bal = eco.get(player.getUniqueId());
        return switch (params.toLowerCase(Locale.ROOT)) {
            case "balance" -> MoneyUtil.format(bal);                                  // $1.5K
            case "balance_raw" -> String.format(Locale.US, "%.2f", bal);              // 1500.00
            case "balance_commas" -> "$" + String.format(Locale.US, "%,.2f", bal);    // $1,500.00
            case "rank" -> String.valueOf(eco.rank(player.getUniqueId()));            // 3
            default -> null;
        };
    }
}
