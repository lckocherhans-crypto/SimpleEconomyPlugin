package com.example.simpleeconomy;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/** All the dialog screens: balance, leaderboard, pay, shop listing/buying, and /sell. */
public final class Flows {
    private static final int PICKER_PAGE = 24;

    private final SimpleEconomyPlugin plugin;
    private final EconomyManager eco;
    private final ShopManager shop;
    private final PriceManager prices;
    private final Dialogs dialogs;
    private final List<Material> allItems;
    private Gui gui;

    public Flows(SimpleEconomyPlugin plugin, EconomyManager eco, ShopManager shop, PriceManager prices, Dialogs dialogs) {
        this.plugin = plugin;
        this.eco = eco;
        this.shop = shop;
        this.prices = prices;
        this.dialogs = dialogs;

        Set<Material> blocked = new HashSet<>();
        for (String s : plugin.getConfig().getStringList("shop.blocked-items")) {
            Material m = Material.matchMaterial(s);
            if (m != null) blocked.add(m);
        }
        this.allItems = Arrays.stream(Material.values())
                .filter(m -> !m.name().startsWith("LEGACY_") && m.isItem() && !m.isAir() && !blocked.contains(m))
                .sorted(Comparator.comparing(Material::name))
                .toList();
    }

    public void setGui(Gui gui) {
        this.gui = gui;
    }

    // ------------------------------------------------------------ small helpers

    private int maxListings() {
        return Math.min(27, plugin.getConfig().getInt("shop.max-listings", 10));
    }

    public void notice(Player p, String title, String... lines) {
        dialogs.notice(p, title, Arrays.stream(lines).map(Msg::c).toList());
    }

    private static boolean matchesAll(String haystack, String query) {
        if (query == null || query.isBlank()) return true;
        String hay = haystack.toLowerCase(Locale.ROOT);
        for (String token : query.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
            if (!hay.contains(token)) return false;
        }
        return true;
    }

    // ------------------------------------------------------------ balance hub

    public void balance(Player p) {
        UUID id = p.getUniqueId();
        List<Component> lines = List.of(
                Msg.c("&7Balance: &a" + MoneyUtil.format(eco.get(id))),
                Msg.c("&7Shop lots: &f" + shop.countBy(id) + "/" + maxListings()),
                Msg.c("&7Items waiting in claim box: &f" + shop.claimCount(id)));
        List<ActionButton> actions = List.of(
                dialogs.button(Component.text("Leaderboard"), null, 140, dialogs.click(this::baltop)),
                dialogs.button(Component.text("Pay a player"), null, 140, dialogs.click(pl -> pay(pl, "", "", null))),
                dialogs.button(Component.text("Open Shop"), null, 140, dialogs.click(pl -> gui.openShop(pl, 0))),
                dialogs.button(Component.text("Sell items"), null, 140, dialogs.click(this::sellMenu)));
        dialogs.menu(p, "Your Balance", lines, List.of(), actions,
                dialogs.button("Close", NamedTextColor.RED, null), 2);
    }

    public void balanceOf(Player viewer, OfflinePlayer target) {
        notice(viewer, "Balance", "&7" + target.getName() + "'s balance: &a" + MoneyUtil.format(eco.get(target.getUniqueId())));
    }

    // ------------------------------------------------------------ leaderboard

    public void baltop(Player p) {
        List<Component> lines = new ArrayList<>();
        int i = 1;
        for (Map.Entry<UUID, Double> e : eco.top(10)) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(e.getKey());
            String name = op.getName() != null ? op.getName() : e.getKey().toString().substring(0, 8);
            lines.add(Msg.c("&e" + i++ + ". &f" + name + " &7- &a" + MoneyUtil.format(e.getValue())));
        }
        if (lines.isEmpty()) lines.add(Msg.c("&7Nobody has any money yet."));
        lines.add(Component.empty());
        lines.add(Msg.c("&7You: &a" + MoneyUtil.format(eco.get(p.getUniqueId())) + " &7(rank #" + eco.rank(p.getUniqueId()) + ")"));
        dialogs.notice(p, "Top Balances", lines);
    }

    // ------------------------------------------------------------ pay

    public void pay(Player p, String name, String amount, String error) {
        List<Component> lines = new ArrayList<>();
        if (error != null) lines.add(Msg.c(error));
        lines.add(Msg.c("&7Your balance: &a" + MoneyUtil.format(eco.get(p.getUniqueId()))));
        lines.add(Msg.c("&7Amounts accept 1k, 2.5m, 1b..."));
        List<DialogInput> inputs = List.of(
                dialogs.textInput("player", "Player (online)", name, 16),
                dialogs.textInput("amount", "Amount", amount, 16));
        ActionButton yes = dialogs.button("Send", NamedTextColor.GREEN,
                dialogs.clickInputs(List.of("player", "amount"), (pl, m) -> {
                    String err = tryPay(pl, m.get("player"), m.get("amount"));
                    if (err != null) pay(pl, m.get("player"), m.get("amount"), err);
                }));
        dialogs.confirm(p, "Pay a Player", lines, inputs, yes, dialogs.button("Cancel", NamedTextColor.RED, null));
    }

    /** @return error message, or null if the payment went through */
    public String tryPay(Player p, String targetName, String amountText) {
        Player t = Bukkit.getPlayerExact(targetName == null ? "" : targetName.trim());
        if (t == null) return "&cThat player is not online.";
        if (t.equals(p)) return "&cYou can't pay yourself.";
        OptionalDouble amt = MoneyUtil.parse(amountText);
        if (amt.isEmpty() || amt.getAsDouble() < 0.01) return "&cInvalid amount.";
        if (!eco.withdraw(p.getUniqueId(), amt.getAsDouble())) return "&cYou can't afford that.";
        eco.deposit(t.getUniqueId(), amt.getAsDouble());
        p.sendMessage(Msg.c("&aYou paid &f" + t.getName() + " &2" + MoneyUtil.format(amt.getAsDouble()) + "&a."));
        t.sendMessage(Msg.c("&aYou received &2" + MoneyUtil.format(amt.getAsDouble()) + " &afrom &f" + p.getName() + "&a."));
        return null;
    }

    // ------------------------------------------------------------ listing on the shop

    /** No item/amount/price given: full picker flow. Otherwise used by /shop list <item> <amount> <price>. */
    public void listPicker(Player p, String query, int page) {
        if (shop.countBy(p.getUniqueId()) >= maxListings()) {
            notice(p, "Shop", "&cYou already use all " + maxListings() + " of your shop lots.");
            return;
        }
        String q = query == null ? "" : query.trim();
        List<Material> matches = allItems.stream()
                .filter(m -> matchesAll(m.name().replace('_', ' '), q))
                .toList();
        int pages = Math.max(1, (matches.size() + PICKER_PAGE - 1) / PICKER_PAGE);
        final int pg = Math.max(0, Math.min(page, pages - 1));

        List<ActionButton> buttons = new ArrayList<>();
        int from = pg * PICKER_PAGE;
        int to = Math.min(matches.size(), from + PICKER_PAGE);
        for (int i = from; i < to; i++) {
            Material m = matches.get(i);
            int have = shop.countMatching(p, m);
            Component label = Component.text(have + "x ").append(Component.translatable(m.translationKey()));
            buttons.add(dialogs.button(label, null, 130, dialogs.click(pl -> listDetails(pl, m, "", "", null))));
        }
        List<String> keys = List.of("query");
        buttons.add(dialogs.button(Component.text("< Previous", NamedTextColor.YELLOW), null, 130,
                dialogs.clickInputs(keys, (pl, m) -> listPicker(pl, m.get("query"), pg - 1))));
        buttons.add(dialogs.button(Component.text("Search", NamedTextColor.GREEN), null, 130,
                dialogs.clickInputs(keys, (pl, m) -> listPicker(pl, m.get("query"), 0))));
        buttons.add(dialogs.button(Component.text("Next >", NamedTextColor.YELLOW), null, 130,
                dialogs.clickInputs(keys, (pl, m) -> listPicker(pl, m.get("query"), pg + 1))));

        List<Component> lines = List.of(Msg.c("&7Pick what to sell in your shop. Numbers show how much you're carrying. &f"
                + matches.size() + " items &7- page &f" + (pg + 1) + "/" + pages));
        dialogs.menu(p, "Add to Shop", lines, List.of(dialogs.textInput("query", "Search items", q, 40)), buttons,
                dialogs.button("Back", NamedTextColor.RED, dialogs.click(pl -> gui.openMyShop(pl))), 3);
    }

    /** Step 2: amount and price per item. */
    private void listDetails(Player p, Material mat, String amount, String price, String error) {
        int have = shop.countMatching(p, mat);
        List<Component> lines = new ArrayList<>();
        if (error != null) lines.add(Msg.c(error));
        lines.add(Component.text("Item: ").append(Component.translatable(mat.translationKey())));
        lines.add(Msg.c("&7You're carrying &f" + have + "&7 (renamed/enchanted items don't count)."));
        lines.add(Msg.c("&7How many do you want to list, and how much per item?"));
        List<DialogInput> inputs = List.of(
                dialogs.textInput("amount", "Amount", amount.isEmpty() ? String.valueOf(have) : amount, 8),
                dialogs.textInput("price", "Price per item", price, 16));
        ActionButton yes = dialogs.button("Review", NamedTextColor.GREEN,
                dialogs.clickInputs(List.of("amount", "price"), (pl, m) -> reviewListing(pl, mat, m.get("amount"), m.get("price"))));
        ActionButton no = dialogs.button("Back", NamedTextColor.RED, dialogs.click(pl -> listPicker(pl, "", 0)));
        dialogs.confirm(p, "List Item", lines, inputs, yes, no);
    }

    /** Step 3: confirm, taking the items from the seller only once they accept. */
    private void reviewListing(Player p, Material mat, String amountText, String priceText) {
        int amount;
        try {
            amount = Integer.parseInt(amountText.trim());
        } catch (NumberFormatException e) {
            listDetails(p, mat, amountText, priceText, "&cAmount must be a whole number.");
            return;
        }
        double minPrice = plugin.getConfig().getDouble("shop.min-price", 0.01);
        OptionalDouble price = MoneyUtil.parse(priceText);
        if (amount < 1) {
            listDetails(p, mat, amountText, priceText, "&cAmount must be at least 1.");
            return;
        }
        if (price.isEmpty() || price.getAsDouble() < minPrice) {
            listDetails(p, mat, amountText, priceText, "&cPrice each must be at least " + MoneyUtil.format(minPrice) + ".");
            return;
        }
        int have = shop.countMatching(p, mat);
        if (have < amount) {
            listDetails(p, mat, amountText, priceText, "&cYou only have " + have + ".");
            return;
        }
        double each = price.getAsDouble();
        List<Component> lines = List.of(
                Component.text(amount + "x ").append(Component.translatable(mat.translationKey())),
                Msg.c("&7Price each: &a" + MoneyUtil.format(each)),
                Msg.c("&7Total if it all sells: &e" + MoneyUtil.format(MoneyUtil.round(amount * each))),
                Msg.c("&7These items leave your inventory now and go up for sale."));
        final int amt = amount;
        ActionButton yes = dialogs.button("List it", NamedTextColor.GREEN,
                dialogs.click(pl -> finishListing(pl, mat, amt, each, amountText, priceText)));
        ActionButton no = dialogs.button("Back", NamedTextColor.RED,
                dialogs.click(pl -> listDetails(pl, mat, amountText, priceText, null)));
        dialogs.confirm(p, "Confirm Listing", lines, List.of(), yes, no);
    }

    private void finishListing(Player p, Material mat, int amount, double each, String amountText, String priceText) {
        String err = shop.create(p, mat, amount, each);
        if (err != null) {
            listDetails(p, mat, amountText, priceText, err);
            return;
        }
        p.sendMessage(Msg.c("&aListed &f" + amount + "x " + ItemNames.pretty(mat) + " &aat &2"
                + MoneyUtil.format(each) + " &aeach."));
        gui.openMyShop(p);
    }

    /** Used by /shop list <item> <amount> <price>. */
    public void openListDetails(Player p, Material mat, String amountText, String priceText) {
        listDetails(p, mat, amountText, priceText, null);
    }

    // ------------------------------------------------------------ buying from the shop

    /** Step 1: how many to buy. */
    public void buyPrompt(Player p, ShopListing l, String error) {
        if (!shop.all().contains(l)) {
            notice(p, "Shop", "&cThat listing is no longer available.");
            return;
        }
        List<Component> lines = new ArrayList<>();
        if (error != null) lines.add(Msg.c(error));
        lines.add(Component.text(l.amount + "x ").append(Component.translatable(l.material.translationKey()))
                .append(Component.text(" available")));
        lines.add(Msg.c("&7Price each: &a" + MoneyUtil.format(l.pricePerItem)));
        lines.add(Msg.c("&7Seller: &f" + l.sellerName));
        lines.add(Msg.c("&7Your balance: &a" + MoneyUtil.format(eco.get(p.getUniqueId()))));
        ActionButton yes = dialogs.button("Continue", NamedTextColor.GREEN,
                dialogs.clickInputs(List.of("qty"), (pl, m) -> buyReview(pl, l, m.get("qty"))));
        ActionButton no = dialogs.button("Back", NamedTextColor.RED, dialogs.click(pl -> gui.openShop(pl, 0)));
        dialogs.confirm(p, "Buy Items", lines, List.of(dialogs.textInput("qty", "Amount to buy", String.valueOf(l.amount), 8)), yes, no);
    }

    private void buyReview(Player p, ShopListing l, String qtyText) {
        int qty;
        try {
            qty = Integer.parseInt(qtyText.trim());
        } catch (NumberFormatException e) {
            buyPrompt(p, l, "&cAmount must be a whole number.");
            return;
        }
        if (qty < 1 || qty > l.amount) {
            buyPrompt(p, l, "&cOnly " + l.amount + " left.");
            return;
        }
        double cost = MoneyUtil.round(qty * l.pricePerItem);
        if (eco.get(p.getUniqueId()) + 0.001 < cost) {
            buyPrompt(p, l, "&cYou need " + MoneyUtil.format(cost) + " for that.");
            return;
        }
        List<Component> lines = List.of(
                Component.text(qty + "x ").append(Component.translatable(l.material.translationKey())),
                Msg.c("&7Total: &a" + MoneyUtil.format(cost)));
        ActionButton yes = dialogs.button("Buy", NamedTextColor.GREEN,
                dialogs.click(pl -> {
                    String err = shop.buy(pl, l, qty);
                    if (err != null) notice(pl, "Shop", err);
                    gui.openShop(pl, 0);
                }));
        ActionButton no = dialogs.button("Back", NamedTextColor.RED, dialogs.click(pl -> buyPrompt(pl, l, null)));
        dialogs.confirm(p, "Confirm Purchase", lines, List.of(), yes, no);
    }

    // ------------------------------------------------------------ /sell (fixed base prices)

    public void sellMenu(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        List<Component> lines = new ArrayList<>();
        if (!hand.getType().isAir() && prices.isSellable(hand.getType())) {
            OptionalDouble each = prices.sellPrice(hand.getType());
            double total = each.getAsDouble() * hand.getAmount();
            lines.add(Msg.c("&7In hand: &f" + hand.getAmount() + "x " + ItemNames.pretty(hand.getType())
                    + " &7- &a" + MoneyUtil.format(total)));
        } else {
            lines.add(Msg.c("&7Nothing sellable is in your hand right now."));
        }
        double sellAllTotal = sellAllValue(p);
        lines.add(Msg.c("&7Everything sellable in your inventory: &a" + MoneyUtil.format(sellAllTotal)));
        List<ActionButton> actions = new ArrayList<>();
        actions.add(dialogs.button(Component.text("Sell held item"), null, 140, dialogs.click(this::sellHandConfirm)));
        actions.add(dialogs.button(Component.text("Sell all sellable items"), null, 140, dialogs.click(this::sellAllConfirm)));
        dialogs.menu(p, "Sell Items", lines, List.of(), actions,
                dialogs.button("Back", NamedTextColor.RED, dialogs.click(this::balance)), 1);
    }

    public void sellHandConfirm(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            notice(p, "Sell", "&cYou aren't holding anything.");
            return;
        }
        OptionalDouble each = prices.sellPrice(hand.getType());
        if (each.isEmpty()) {
            notice(p, "Sell", "&cThat item can't be sold here.");
            return;
        }
        int amount = hand.getAmount();
        double total = MoneyUtil.round(each.getAsDouble() * amount);
        List<Component> lines = List.of(
                Component.text(amount + "x ").append(Component.translatable(hand.getType().translationKey())),
                Msg.c("&7Price each: &a" + MoneyUtil.format(each.getAsDouble())),
                Msg.c("&7Total: &2" + MoneyUtil.format(total)));
        ActionButton yes = dialogs.button("Sell", NamedTextColor.GREEN, dialogs.click(pl -> {
            ItemStack cur = pl.getInventory().getItemInMainHand();
            if (cur.getType() != hand.getType() || cur.getAmount() < amount) {
                notice(pl, "Sell", "&cThe item in your hand changed - nothing was sold.");
                return;
            }
            cur.setAmount(cur.getAmount() - amount);
            eco.deposit(pl.getUniqueId(), total);
            pl.sendMessage(Msg.c("&aSold &f" + amount + "x " + ItemNames.pretty(hand.getType()) + " &afor &2"
                    + MoneyUtil.format(total) + "&a."));
        }));
        ActionButton no = dialogs.button("Cancel", NamedTextColor.RED, dialogs.click(this::sellMenu));
        dialogs.confirm(p, "Confirm Sale", lines, List.of(), yes, no);
    }

    private double sellAllValue(Player p) {
        double total = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) {
            if (it == null || it.getType().isAir()) continue;
            if (it.hasItemMeta() && (it.getItemMeta().hasDisplayName() || it.getItemMeta().hasEnchants())) continue;
            OptionalDouble each = prices.sellPrice(it.getType());
            if (each.isPresent()) total += each.getAsDouble() * it.getAmount();
        }
        return MoneyUtil.round(total);
    }

    public void sellAllConfirm(Player p) {
        double total = sellAllValue(p);
        if (total <= 0) {
            notice(p, "Sell", "&cNothing sellable in your inventory.");
            return;
        }
        List<Component> lines = List.of(Msg.c("&7This sells every plain, sellable item in your inventory."),
                Msg.c("&7Total: &2" + MoneyUtil.format(total)));
        ActionButton yes = dialogs.button("Sell all", NamedTextColor.GREEN, dialogs.click(pl -> {
            double actual = 0;
            ItemStack[] contents = pl.getInventory().getStorageContents();
            for (int i = 0; i < contents.length; i++) {
                ItemStack it = contents[i];
                if (it == null || it.getType().isAir()) continue;
                if (it.hasItemMeta() && (it.getItemMeta().hasDisplayName() || it.getItemMeta().hasEnchants())) continue;
                OptionalDouble each = prices.sellPrice(it.getType());
                if (each.isEmpty()) continue;
                actual += each.getAsDouble() * it.getAmount();
                contents[i] = null;
            }
            pl.getInventory().setStorageContents(contents);
            actual = MoneyUtil.round(actual);
            eco.deposit(pl.getUniqueId(), actual);
            pl.sendMessage(Msg.c("&aSold everything sellable for &2" + MoneyUtil.format(actual) + "&a."));
        }));
        ActionButton no = dialogs.button("Cancel", NamedTextColor.RED, dialogs.click(this::sellMenu));
        dialogs.confirm(p, "Confirm Sale", lines, List.of(), yes, no);
    }
}
