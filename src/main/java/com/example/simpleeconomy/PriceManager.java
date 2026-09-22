package com.example.simpleeconomy;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Fixed sell prices for /sell. Base prices are per-item and set by an admin (or the starter list
 * below) - a player can never influence them, which is what stops someone from "selling" a dirt
 * block for a million dollars. A single global multiplier can be tuned live to adjust the whole
 * economy without touching every item's base price.
 */
public class PriceManager {
    private final SimpleEconomyPlugin plugin;
    private final File file;
    private final Map<Material, Double> base = new EnumMap<>(Material.class);

    public PriceManager(SimpleEconomyPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "prices.yml");
    }

    public void load() {
        if (!file.exists()) {
            seedDefaults();
            save();
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        var sec = y.getConfigurationSection("prices");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            try {
                base.put(Material.valueOf(key), sec.getDouble(key));
            } catch (Exception e) {
                plugin.getLogger().warning("Skipping bad price entry '" + key + "': " + e.getMessage());
            }
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        base.forEach((mat, price) -> y.set("prices." + mat.name(), price));
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save prices.yml: " + e.getMessage());
        }
    }

    /** The raw admin-set value, with no multiplier applied. */
    public OptionalDouble base(Material m) {
        Double v = base.get(m);
        return v == null ? OptionalDouble.empty() : OptionalDouble.of(v);
    }

    public boolean isSellable(Material m) {
        return base.containsKey(m);
    }

    public double multiplier() {
        return Math.max(0, plugin.getConfig().getDouble("sell.multiplier", 1.0));
    }

    /** The actual price /sell pays right now (base * multiplier), or empty if the item has no base price. */
    public OptionalDouble sellPrice(Material m) {
        Double v = base.get(m);
        if (v == null) return OptionalDouble.empty();
        return OptionalDouble.of(MoneyUtil.round(v * multiplier()));
    }

    public void setBase(Material m, double price) {
        base.put(m, MoneyUtil.round(Math.max(0, price)));
        save();
    }

    public void removeBase(Material m) {
        base.remove(m);
        save();
    }

    public void setMultiplier(double value) {
        plugin.getConfig().set("sell.multiplier", Math.max(0, value));
        plugin.saveConfig();
    }

    public Map<Material, Double> all() {
        return Collections.unmodifiableMap(base);
    }

    /** A modest starter list so /sell works out of the box. Edit prices.yml or use /eco sellprice to tune it. */
    private void seedDefaults() {
        Map<Material, Double> defaults = new LinkedHashMap<>();
        defaults.put(Material.COBBLESTONE, 0.5);
        defaults.put(Material.STONE, 0.5);
        defaults.put(Material.DIRT, 0.1);
        defaults.put(Material.SAND, 0.5);
        defaults.put(Material.GRAVEL, 0.5);
        defaults.put(Material.OAK_LOG, 2.0);
        defaults.put(Material.SPRUCE_LOG, 2.0);
        defaults.put(Material.BIRCH_LOG, 2.0);
        defaults.put(Material.JUNGLE_LOG, 2.0);
        defaults.put(Material.ACACIA_LOG, 2.0);
        defaults.put(Material.DARK_OAK_LOG, 2.0);
        defaults.put(Material.MANGROVE_LOG, 2.0);
        defaults.put(Material.CHERRY_LOG, 2.0);
        defaults.put(Material.COAL, 5.0);
        defaults.put(Material.RAW_IRON, 8.0);
        defaults.put(Material.IRON_INGOT, 10.0);
        defaults.put(Material.RAW_GOLD, 12.0);
        defaults.put(Material.GOLD_INGOT, 15.0);
        defaults.put(Material.RAW_COPPER, 4.0);
        defaults.put(Material.COPPER_INGOT, 5.0);
        defaults.put(Material.REDSTONE, 4.0);
        defaults.put(Material.LAPIS_LAZULI, 4.0);
        defaults.put(Material.QUARTZ, 6.0);
        defaults.put(Material.DIAMOND, 80.0);
        defaults.put(Material.EMERALD, 60.0);
        defaults.put(Material.ANCIENT_DEBRIS, 400.0);
        defaults.put(Material.NETHERITE_SCRAP, 600.0);
        defaults.put(Material.NETHERITE_INGOT, 1500.0);
        defaults.put(Material.WHEAT, 1.0);
        defaults.put(Material.CARROT, 1.0);
        defaults.put(Material.POTATO, 1.0);
        defaults.put(Material.BEETROOT, 1.0);
        defaults.put(Material.SUGAR_CANE, 1.0);
        defaults.put(Material.PUMPKIN, 3.0);
        defaults.put(Material.MELON_SLICE, 0.5);
        defaults.put(Material.EGG, 1.0);
        defaults.put(Material.STRING, 1.0);
        defaults.put(Material.SPIDER_EYE, 2.0);
        defaults.put(Material.GUNPOWDER, 3.0);
        defaults.put(Material.ENDER_PEARL, 20.0);
        defaults.put(Material.BLAZE_ROD, 25.0);
        defaults.put(Material.SLIME_BALL, 4.0);
        defaults.put(Material.ROTTEN_FLESH, 0.2);
        defaults.put(Material.BONE, 1.0);
        defaults.put(Material.LEATHER, 3.0);
        defaults.put(Material.FEATHER, 1.0);
        defaults.put(Material.RABBIT_HIDE, 1.5);
        defaults.put(Material.PRISMARINE_SHARD, 3.0);
        defaults.put(Material.PRISMARINE_CRYSTALS, 4.0);
        defaults.put(Material.NAUTILUS_SHELL, 30.0);
        defaults.put(Material.TURTLE_SCUTE, 15.0);
        defaults.put(Material.PHANTOM_MEMBRANE, 15.0);
        base.putAll(defaults);
    }
}
