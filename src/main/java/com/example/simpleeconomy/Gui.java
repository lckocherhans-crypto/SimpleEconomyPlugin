package com.example.simpleeconomy;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

/**
 * Chest menus: browsing the shop (buy menu) and "Your shop" (the lots grid). Everything else -
 * balance, leaderboard, pay, listing, buying quantity, /sell - is a dialog handled by Flows.
 */
public class Gui implements Listener {

    private static final int LOT_SLOTS = 27;

    private enum Sort {
        NEWEST("Newest"), PRICE_LOW("Price: Low to High"), PRICE_HIGH("Price: High to Low"), STOCK("Most in stock");
        final String label;
        Sort(String label) { this.label = label; }
        Sort next() { return values()[(ordinal() + 1) % values().length]; }
    }

    private static abstract class Holder implements InventoryHolder {
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    private static class ShopHolder extends Holder {
        int page;
        final List<ShopListing> shown = new ArrayList<>();
    }

    /** "Your shop": a fixed grid of lots. */
    private static class LotsHolder extends Holder {
        int lots;
        final List<ShopListing> listings = new ArrayList<>();
    }

    private final SimpleEconomyPlugin plugin;
    private final EconomyManager eco;
    private final ShopManager shop;
    private final Dialogs dialogs;
    private final Flows flows;
    private final NamespacedKey guiKey;
    private final Map<UUID, Sort> sorts = new HashMap<>();
    private final Map<UUID, String> shopSearch = new HashMap<>();

    public Gui(SimpleEconomyPlugin plugin, EconomyManager eco, ShopManager shop, Dialogs dialogs, Flows flows) {
        this.plugin = plugin;
        this.eco = eco;
        this.shop = shop;
        this.dialogs = dialogs;
        this.flows = flows;
        this.guiKey = new NamespacedKey(plugin, "gui_item");
    }

    // ------------------------------------------------------------ helpers

    /** Every item shown in a menu carries this tag, so stray copies can always be found and removed. */
    public boolean isMarked(ItemStack it) {
        if (it == null || it.getType().isAir() || !it.hasItemMeta()) return false;
        return it.getItemMeta().getPersistentDataContainer().has(guiKey, PersistentDataType.BYTE);
    }

    private void mark(ItemMeta meta) {
        meta.getPersistentDataContainer().set(guiKey, PersistentDataType.BYTE, (byte) 1);
    }

    private ItemStack button(Material m, String name, String... lore) {
        ItemStack i = new ItemStack(m);
        ItemMeta meta = i.getItemMeta();
        meta.displayName(Msg.lore(name));
        if (lore.length > 0) meta.lore(Arrays.stream(lore).map(Msg::lore).toList());
        mark(meta);
        i.setItemMeta(meta);
        return i;
    }

    private void fillBar(Inventory inv, int from) {
        ItemStack pane = button(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = from; i < inv.getSize(); i++) inv.setItem(i, pane);
    }

    private void later(Runnable r) {
        Bukkit.getScheduler().runTask(plugin, r);
    }

    /** Removes any menu item that ended up in a player's inventory or cursor. */
    private void purge(Player p) {
        PlayerInventory inv = p.getInventory();
        ItemStack[] contents = inv.getContents();
        boolean changed = false;
        for (int i = 0; i < contents.length; i++) {
            if (isMarked(contents[i])) {
                contents[i] = null;
                changed = true;
            }
        }
        if (changed) inv.setContents(contents);
        if (isMarked(p.getItemOnCursor())) p.setItemOnCursor(null);
    }

    // ------------------------------------------------------------ search

    private static boolean matchesAll(String haystack, String query) {
        if (query == null || query.isBlank()) return true;
        String hay = haystack.toLowerCase(Locale.ROOT);
        for (String token : query.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
            if (!hay.contains(token)) return false;
        }
        return true;
    }

    private boolean matchesShop(ShopListing l, String query) {
        return matchesAll(l.material.name().replace('_', ' ') + " " + l.sellerName, query);
    }

    private void setSearch(Map<UUID, String> map, UUID id, String text) {
        if (text == null || text.isBlank()) map.remove(id);
        else map.put(id, text.trim());
    }

    public void searchShop(Player p, String text) {
        setSearch(shopSearch, p.getUniqueId(), text);
        openShop(p, 0);
    }

    // ------------------------------------------------------------ shop (buy menu)

    public void openShop(Player p, int page) {
        ShopHolder h = new ShopHolder();
        Sort sort = sorts.getOrDefault(p.getUniqueId(), Sort.NEWEST);
        Comparator<ShopListing> cmp = switch (sort) {
            case NEWEST -> Comparator.<ShopListing>comparingLong(l -> l.listedAt).reversed();
            case PRICE_LOW -> Comparator.comparingDouble(l -> l.pricePerItem);
            case PRICE_HIGH -> Comparator.<ShopListing>comparingDouble(l -> l.pricePerItem).reversed();
            case STOCK -> Comparator.<ShopListing>comparingInt(l -> l.amount).reversed();
        };
        final String query = shopSearch.get(p.getUniqueId());
        List<ShopListing> list = shop.all().stream()
                .filter(l -> matchesShop(l, query))
                .sorted(cmp).toList();

        int pages = Math.max(1, (list.size() + 44) / 45);
        page = Math.max(0, Math.min(page, pages - 1));
        h.page = page;

        Inventory inv = Bukkit.createInventory(h, 54,
                Component.text("Shop" + (query != null ? " - \"" + query + "\"" : "")
                        + " (" + (page + 1) + "/" + pages + ")"));
        h.inv = inv;

        for (int i = 0; i < 45; i++) {
            int idx = page * 45 + i;
            if (idx >= list.size()) break;
            ShopListing l = list.get(idx);
            h.shown.add(l);
            inv.setItem(i, listingDisplay(l, false));
        }

        fillBar(inv, 45);
        if (page > 0) inv.setItem(45, button(Material.ARROW, "&ePrevious page"));
        inv.setItem(46, button(Material.CHEST, "&eYour shop", "&7Manage your lots"));
        inv.setItem(47, button(Material.OAK_SIGN, "&bSearch",
                "&7Current: &f" + (query == null ? "none" : query), "&eClick to search", "&eRight-click to clear"));
        inv.setItem(48, button(Material.ENDER_CHEST, "&6Claim box",
                "&7Items waiting: &f" + shop.claimCount(p.getUniqueId()), "&eClick to claim"));
        inv.setItem(49, button(Material.SUNFLOWER, "&aBalance: " + MoneyUtil.format(eco.get(p.getUniqueId())),
                "&eClick for your balance menu"));
        inv.setItem(50, button(Material.HOPPER, "&bSort: &f" + sort.label, "&eClick to change"));
        inv.setItem(51, button(Material.EMERALD, "&aAdd to shop", "&7List items for sale"));
        if (page < pages - 1) inv.setItem(53, button(Material.ARROW, "&eNext page"));
        p.openInventory(inv);
    }

    private ItemStack listingDisplay(ShopListing l, boolean mine) {
        ItemStack it = new ItemStack(l.material);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Msg.lore("&f" + ItemNames.pretty(l.material)));
        List<Component> lore = new ArrayList<>();
        lore.add(Msg.lore("&7Amount: &f" + l.amount));
        lore.add(Msg.lore("&7Price each: &a" + MoneyUtil.format(l.pricePerItem)));
        lore.add(Msg.lore("&7Total: &a" + MoneyUtil.format(MoneyUtil.round(l.amount * l.pricePerItem))));
        if (mine) {
            lore.add(Component.empty());
            lore.add(Msg.lore("&cClick to cancel and get the items back"));
        } else {
            lore.add(Msg.lore("&7Seller: &f" + l.sellerName));
            lore.add(Component.empty());
            lore.add(Msg.lore("&eClick to buy"));
        }
        meta.lore(lore);
        mark(meta);
        it.setItemMeta(meta);
        return it;
    }

    private void claimBox(Player p) {
        int given = shop.claim(p);
        if (given == 0) p.sendMessage(Msg.c("&cYour claim box is empty."));
        else p.sendMessage(Msg.c("&aClaimed &f" + given + "&a item(s)."
                + (shop.claimCount(p.getUniqueId()) > 0 ? " &eInventory full - claim the rest later." : "")));
    }

    // ------------------------------------------------------------ your shop (lots grid)

    private ItemStack openLotPane(String hint) {
        return button(Material.GRAY_STAINED_GLASS_PANE, "&7Empty lot", hint);
    }

    private ItemStack lockedLotPane() {
        return button(Material.RED_STAINED_GLASS_PANE, "&cLocked lot");
    }

    public void openMyShop(Player p) {
        LotsHolder h = new LotsHolder();
        int max = Math.min(LOT_SLOTS, plugin.getConfig().getInt("shop.max-listings", 10));
        List<ShopListing> mine = shop.all().stream()
                .filter(l -> l.seller.equals(p.getUniqueId()))
                .limit(LOT_SLOTS).toList();
        h.listings.addAll(mine);
        h.lots = Math.min(LOT_SLOTS, Math.max(max, mine.size()));

        Inventory inv = Bukkit.createInventory(h, 36, Component.text("Your Shop (" + mine.size() + "/" + max + ")"));
        h.inv = inv;
        for (int i = 0; i < LOT_SLOTS; i++) {
            if (i < mine.size()) inv.setItem(i, listingDisplay(mine.get(i), true));
            else if (i < h.lots) inv.setItem(i, openLotPane("&eClick to list an item"));
            else inv.setItem(i, lockedLotPane());
        }
        fillBar(inv, 27);
        inv.setItem(27, button(Material.ARROW, "&eBack to Shop"));
        inv.setItem(29, button(Material.ENDER_CHEST, "&6Claim box",
                "&7Items waiting: &f" + shop.claimCount(p.getUniqueId()), "&eClick to claim"));
        inv.setItem(31, button(Material.SUNFLOWER, "&aBalance: " + MoneyUtil.format(eco.get(p.getUniqueId()))));
        p.openInventory(inv);
    }

    // ------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Inventory top = e.getView().getTopInventory();

        if (!(top.getHolder() instanceof Holder holder)) {
            // A menu item somehow got out: block it and delete it.
            if (isMarked(e.getCurrentItem()) || isMarked(e.getCursor())) {
                e.setCancelled(true);
                later(() -> purge(p));
            }
            return;
        }

        e.setCancelled(true);
        later(p::updateInventory);   // resync the client so nothing looks picked up
        if (e.getClickedInventory() != top) return;
        int slot = e.getRawSlot();

        if (holder instanceof ShopHolder h) handleShop(p, h, slot, e.isRightClick());
        else if (holder instanceof LotsHolder h) handleLots(p, h, slot);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) {
            e.setCancelled(true);
            if (e.getWhoClicked() instanceof Player p) later(p::updateInventory);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof Holder && e.getPlayer() instanceof Player p) {
            later(() -> purge(p));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        purge(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrop(PlayerDropItemEvent e) {
        if (isMarked(e.getItemDrop().getItemStack())) {
            e.getItemDrop().remove();
            e.setCancelled(true);
            Player p = e.getPlayer();
            later(() -> purge(p));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPickup(EntityPickupItemEvent e) {
        if (isMarked(e.getItem().getItemStack())) {
            e.setCancelled(true);
            e.getItem().remove();
        }
    }

    // ------------------------------------------------------------ click handlers

    private void handleShop(Player p, ShopHolder h, int slot, boolean right) {
        if (slot < 45) {
            if (slot >= h.shown.size()) return;
            ShopListing l = h.shown.get(slot);
            if (l.seller.equals(p.getUniqueId())) {
                p.sendMessage(Msg.c("&eThat's your own lot. Manage it under &fYour shop&e."));
                return;
            }
            later(() -> flows.buyPrompt(p, l, null));
            return;
        }
        switch (slot) {
            case 45 -> { if (h.page > 0) later(() -> openShop(p, h.page - 1)); }
            case 53 -> later(() -> openShop(p, h.page + 1));
            case 46 -> later(() -> openMyShop(p));
            case 47 -> {
                if (right) {
                    shopSearch.remove(p.getUniqueId());
                    later(() -> openShop(p, 0));
                } else {
                    later(() -> dialogs.search(p, "Search Shop", shopSearch.get(p.getUniqueId()),
                            (pl, text) -> {
                                setSearch(shopSearch, pl.getUniqueId(), text);
                                openShop(pl, 0);
                            }));
                }
            }
            case 48 -> {
                claimBox(p);
                later(() -> openShop(p, h.page));
            }
            case 49 -> later(() -> flows.balance(p));
            case 50 -> {
                sorts.put(p.getUniqueId(), sorts.getOrDefault(p.getUniqueId(), Sort.NEWEST).next());
                later(() -> openShop(p, 0));
            }
            case 51 -> later(() -> flows.listPicker(p, "", 0));
            default -> {}
        }
    }

    private void handleLots(Player p, LotsHolder h, int slot) {
        if (slot < LOT_SLOTS) {
            if (slot < h.listings.size()) {
                ShopListing l = h.listings.get(slot);
                p.sendMessage(Msg.c(shop.cancel(p, l)));
                later(() -> openMyShop(p));
            } else if (slot < h.lots) {
                later(() -> flows.listPicker(p, "", 0));
            }
            return;
        }
        if (slot == 27) {
            later(() -> openShop(p, 0));
        } else if (slot == 29) {
            claimBox(p);
            later(() -> openMyShop(p));
        }
    }
}
