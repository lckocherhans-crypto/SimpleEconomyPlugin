package com.example.simpleeconomy;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Player shop: list a custom amount of a plain item at a custom price each. Money changes hands
 * instantly on purchase (no escrow, unlike a buy order). A per-player "claim box" holds items that
 * couldn't be handed over directly (inventory full, or a cancelled listing), tracked as plain
 * material -> count so it never needs to store oversized ItemStacks either.
 */
public class ShopManager {
    private final SimpleEconomyPlugin plugin;
    private final EconomyManager eco;
    private final File file;
    private final Map<UUID, ShopListing> listings = new LinkedHashMap<>();
    private final Map<UUID, Map<Material, Integer>> claimBox = new HashMap<>();

    public ShopManager(SimpleEconomyPlugin plugin, EconomyManager eco) {
        this.plugin = plugin;
        this.eco = eco;
        this.file = new File(plugin.getDataFolder(), "shop.yml");
    }

    public void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection ls = y.getConfigurationSection("listings");
        if (ls != null) {
            for (String key : ls.getKeys(false)) {
                ConfigurationSection s = ls.getConfigurationSection(key);
                if (s == null) continue;
                try {
                    UUID id = UUID.fromString(key);
                    ShopListing l = new ShopListing(id, UUID.fromString(s.getString("seller")),
                            s.getString("sellerName", "?"), Material.valueOf(s.getString("material")),
                            s.getInt("amount"), s.getDouble("price"), s.getLong("listedAt", System.currentTimeMillis()));
                    listings.put(id, l);
                } catch (Exception e) {
                    plugin.getLogger().warning("Skipping bad shop listing " + key + ": " + e.getMessage());
                }
            }
        }
        ConfigurationSection cb = y.getConfigurationSection("claim");
        if (cb != null) {
            for (String uid : cb.getKeys(false)) {
                ConfigurationSection items = cb.getConfigurationSection(uid);
                if (items == null) continue;
                Map<Material, Integer> map = new EnumMap<>(Material.class);
                for (String mat : items.getKeys(false)) {
                    try {
                        map.put(Material.valueOf(mat), items.getInt(mat));
                    } catch (Exception ignored) {
                    }
                }
                if (!map.isEmpty()) claimBox.put(UUID.fromString(uid), map);
            }
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (ShopListing l : listings.values()) {
            String p = "listings." + l.id;
            y.set(p + ".seller", l.seller.toString());
            y.set(p + ".sellerName", l.sellerName);
            y.set(p + ".material", l.material.name());
            y.set(p + ".amount", l.amount);
            y.set(p + ".price", l.pricePerItem);
            y.set(p + ".listedAt", l.listedAt);
        }
        claimBox.forEach((uid, items) -> items.forEach((mat, count) ->
                y.set("claim." + uid + "." + mat.name(), count)));
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save shop.yml: " + e.getMessage());
        }
    }

    public Collection<ShopListing> all() {
        return listings.values();
    }

    public long countBy(UUID seller) {
        return listings.values().stream().filter(l -> l.seller.equals(seller)).count();
    }

    public int claimCount(UUID id) {
        Map<Material, Integer> m = claimBox.get(id);
        return m == null ? 0 : m.values().stream().mapToInt(Integer::intValue).sum();
    }

    // ---------------------------------------------------------------- item counting helpers

    /** Only plain items (no display name, no enchants, no other meta) count, so a listing can't hide value in NBT. */
    private static boolean isPlain(ItemStack it) {
        return it != null && !it.getType().isAir() && (!it.hasItemMeta() || it.getItemMeta().getPersistentDataContainer().isEmpty() && !it.getItemMeta().hasDisplayName() && !it.getItemMeta().hasEnchants() && !it.getItemMeta().hasLore());
    }

    public int countMatching(Player p, Material mat) {
        int n = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) {
            if (isPlain(it) && it.getType() == mat) n += it.getAmount();
        }
        return n;
    }

    private void removeMatching(Player p, Material mat, int amount) {
        PlayerInventory inv = p.getInventory();
        ItemStack[] contents = inv.getStorageContents();
        for (int i = 0; i < contents.length && amount > 0; i++) {
            ItemStack it = contents[i];
            if (!isPlain(it) || it.getType() != mat) continue;
            int take = Math.min(amount, it.getAmount());
            amount -= take;
            if (take == it.getAmount()) contents[i] = null;
            else it.setAmount(it.getAmount() - take);
        }
        inv.setStorageContents(contents);
    }

    /** Gives up to `amount` of material to the player, putting whatever doesn't fit in their claim box. */
    private int give(Player p, Material mat, int amount) {
        int given = 0;
        int max = mat.getMaxStackSize();
        while (amount > 0) {
            int size = Math.min(amount, max);
            Map<Integer, ItemStack> left = p.getInventory().addItem(new ItemStack(mat, size));
            int notGiven = left.values().stream().mapToInt(ItemStack::getAmount).sum();
            given += size - notGiven;
            amount -= size;
            if (notGiven > 0) {
                addToClaim(p.getUniqueId(), mat, notGiven + amount);
                break;
            }
        }
        return given;
    }

    private void addToClaim(UUID id, Material mat, int amount) {
        if (amount <= 0) return;
        claimBox.computeIfAbsent(id, k -> new EnumMap<>(Material.class)).merge(mat, amount, Integer::sum);
    }

    /** Hands out everything in a player's claim box that fits. @return items actually handed over */
    public int claim(Player p) {
        Map<Material, Integer> box = claimBox.get(p.getUniqueId());
        if (box == null || box.isEmpty()) return 0;
        // Snapshot first: give() may write back into this same map (via addToClaim) if the
        // inventory fills up partway through, so we must not iterate the live map while it mutates.
        Map<Material, Integer> snapshot = new EnumMap<>(box);
        box.clear();
        int total = 0;
        for (Map.Entry<Material, Integer> e : snapshot.entrySet()) {
            total += give(p, e.getKey(), e.getValue());
        }
        if (claimBox.getOrDefault(p.getUniqueId(), Map.of()).isEmpty()) claimBox.remove(p.getUniqueId());
        return total;
    }

    // ---------------------------------------------------------------- listing lifecycle

    /** @return error message, or null on success */
    public String create(Player p, Material mat, int amount, double pricePerItem) {
        int maxListings = plugin.getConfig().getInt("shop.max-listings", 10);
        double minPrice = plugin.getConfig().getDouble("shop.min-price", 0.01);
        if (countBy(p.getUniqueId()) >= maxListings) return "&cYou can only have " + maxListings + " shop lots.";
        if (amount < 1) return "&cAmount must be at least 1.";
        if (pricePerItem < minPrice) return "&cPrice each must be at least " + MoneyUtil.format(minPrice) + ".";
        int has = countMatching(p, mat);
        if (has < amount) return "&cYou only have " + has + "x " + ItemNames.pretty(mat) + ".";
        removeMatching(p, mat, amount);
        ShopListing l = new ShopListing(UUID.randomUUID(), p.getUniqueId(), p.getName(), mat, amount, pricePerItem, System.currentTimeMillis());
        listings.put(l.id, l);
        return null;
    }

    /** Cancels the listing and returns whatever is left to the seller (or their claim box). */
    public String cancel(Player p, ShopListing l) {
        if (!listings.containsKey(l.id)) return "&cThat listing no longer exists.";
        listings.remove(l.id);
        int given = give(p, l.material, l.amount);
        return "&aListing cancelled. Returned &f" + l.amount + "x " + ItemNames.pretty(l.material) + "&a"
                + (given < l.amount ? " &e(some items went to your claim box - inventory was full)." : ".");
    }

    /** @return error message, or null on success */
    public String buy(Player buyer, ShopListing l, int qty) {
        if (!listings.containsKey(l.id)) return "&cThat listing is no longer available.";
        if (l.seller.equals(buyer.getUniqueId())) return "&cYou can't buy your own listing.";
        if (qty < 1 || qty > l.amount) return "&cInvalid amount - only &f" + l.amount + "&c left.";
        double cost = MoneyUtil.round(qty * l.pricePerItem);
        if (!eco.withdraw(buyer.getUniqueId(), cost)) return "&cYou can't afford that (" + MoneyUtil.format(cost) + ").";

        l.amount -= qty;
        boolean soldOut = l.amount <= 0;
        if (soldOut) listings.remove(l.id);

        int given = give(buyer, l.material, qty);
        eco.deposit(l.seller, cost);

        Player seller = Bukkit.getPlayer(l.seller);
        if (seller != null) {
            seller.sendMessage(Msg.c("&a" + buyer.getName() + " bought &f" + qty + "x "
                    + ItemNames.pretty(l.material) + " &afrom your shop for &2" + MoneyUtil.format(cost) + "&a."
                    + (soldOut ? " &7(sold out)" : "")));
        }
        buyer.sendMessage(Msg.c("&aBought &f" + qty + "x " + ItemNames.pretty(l.material) + " &afor &2"
                + MoneyUtil.format(cost) + "&a."
                + (given < qty ? " &eSome items went to your claim box - inventory was full." : "")));
        return null;
    }
}
