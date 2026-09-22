package com.example.simpleeconomy;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class EconomyManager {
    private final SimpleEconomyPlugin plugin;
    private final Map<UUID, Double> balances = new HashMap<>();
    private final File file;

    public EconomyManager(SimpleEconomyPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "economy.yml");
    }

    public void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = y.getConfigurationSection("balances");
        if (sec == null) return;
        for (String k : sec.getKeys(false)) {
            balances.put(UUID.fromString(k), sec.getDouble(k));
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        balances.forEach((k, v) -> y.set("balances." + k, v));
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save economy.yml: " + e.getMessage());
        }
    }

    public void ensure(UUID id) {
        balances.computeIfAbsent(id, k -> plugin.getConfig().getDouble("starting-balance", 0));
    }

    public double get(UUID id) {
        return balances.getOrDefault(id, plugin.getConfig().getDouble("starting-balance", 0));
    }

    public void set(UUID id, double amount) {
        balances.put(id, MoneyUtil.round(Math.max(0, amount)));
    }

    public void deposit(UUID id, double amount) {
        set(id, get(id) + amount);
    }

    public boolean withdraw(UUID id, double amount) {
        if (get(id) + 0.001 < amount) return false;
        set(id, get(id) - amount);
        return true;
    }

    public List<Map.Entry<UUID, Double>> top(int n) {
        return balances.entrySet().stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
                .limit(n)
                .toList();
    }

    public int rank(UUID id) {
        double targetBalance = get(id);
        int rank = 1;
        for (double bal : balances.values()) {
            if (bal > targetBalance) {
                rank++;
            }
        }
        return rank;
    }
}
