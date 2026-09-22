package com.example.simpleeconomy;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

public class Commands implements CommandExecutor, TabCompleter {
    private final EconomyManager eco;
    private final PriceManager prices;
    private final Gui gui;
    private final Flows flows;

    public Commands(EconomyManager eco, PriceManager prices, Gui gui, Flows flows) {
        this.eco = eco;
        this.prices = prices;
        this.gui = gui;
        this.flows = flows;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        switch (cmd.getName().toLowerCase(Locale.ROOT)) {
            case "balance" -> balance(sender, args);
            case "pay" -> pay(sender, args);
            case "baltop" -> baltop(sender);
            case "eco" -> ecoAdmin(sender, args);
            case "shop" -> shopCmd(sender, args);
            case "sell" -> sellCmd(sender, args);
            default -> { return false; }
        }
        return true;
    }

    private boolean playersOnly(CommandSender s) {
        s.sendMessage(Msg.c("&cOnly players can use this."));
        return true;
    }

    // ---------------------------------------------------------- economy

    private void balance(CommandSender sender, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player p)) { playersOnly(sender); return; }
            flows.balance(p);
            return;
        }
        OfflinePlayer t = Bukkit.getOfflinePlayerIfCached(args[0]);
        if (t == null) { sender.sendMessage(Msg.c("&cPlayer not found.")); return; }
        if (sender instanceof Player p) {
            flows.balanceOf(p, t);
        } else {
            sender.sendMessage(Msg.c("&7" + t.getName() + "'s balance: &a" + MoneyUtil.format(eco.get(t.getUniqueId()))));
        }
    }

    private void pay(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) { playersOnly(sender); return; }
        if (args.length < 2) {
            flows.pay(p, args.length > 0 ? args[0] : "", "", null);
            return;
        }
        String err = flows.tryPay(p, args[0], args[1]);
        if (err != null) p.sendMessage(Msg.c(err));
    }

    private void baltop(CommandSender sender) {
        if (sender instanceof Player p) {
            flows.baltop(p);
            return;
        }
        sender.sendMessage(Msg.c("&6&lTop Balances"));
        int i = 1;
        for (Map.Entry<UUID, Double> e : eco.top(10)) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(e.getKey());
            String name = op.getName() != null ? op.getName() : e.getKey().toString().substring(0, 8);
            sender.sendMessage(Msg.c("&e" + i++ + ". &f" + name + " &7- &a" + MoneyUtil.format(e.getValue())));
        }
    }

    private void ecoAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("simpleeconomy.admin")) { sender.sendMessage(Msg.c("&cNo permission.")); return; }
        if (args.length == 0) {
            sender.sendMessage(Msg.c("&cUsage: /eco <give|take|set> <player> <amount>"));
            sender.sendMessage(Msg.c("&cUsage: /eco sellprice <item> <price|remove>"));
            sender.sendMessage(Msg.c("&cUsage: /eco sellmultiplier <value>"));
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "give", "take", "set" -> {
                if (args.length < 3) { sender.sendMessage(Msg.c("&cUsage: /eco " + sub + " <player> <amount>")); return; }
                OfflinePlayer t = Bukkit.getOfflinePlayerIfCached(args[1]);
                if (t == null) { sender.sendMessage(Msg.c("&cPlayer not found.")); return; }
                OptionalDouble amt = MoneyUtil.parse(args[2]);
                if (amt.isEmpty()) { sender.sendMessage(Msg.c("&cInvalid amount.")); return; }
                UUID id = t.getUniqueId();
                switch (sub) {
                    case "give" -> eco.deposit(id, amt.getAsDouble());
                    case "take" -> eco.set(id, eco.get(id) - amt.getAsDouble());
                    case "set" -> eco.set(id, amt.getAsDouble());
                }
                sender.sendMessage(Msg.c("&a" + t.getName() + " now has &2" + MoneyUtil.format(eco.get(id)) + "&a."));
            }
            case "sellprice" -> {
                if (args.length < 3) { sender.sendMessage(Msg.c("&cUsage: /eco sellprice <item> <price|remove>")); return; }
                Material mat = Material.matchMaterial(args[1]);
                if (mat == null || mat.isAir() || !mat.isItem()) { sender.sendMessage(Msg.c("&cUnknown item.")); return; }
                if (args[2].equalsIgnoreCase("remove")) {
                    prices.removeBase(mat);
                    sender.sendMessage(Msg.c("&a" + ItemNames.pretty(mat) + " is no longer sellable via /sell."));
                    return;
                }
                OptionalDouble price = MoneyUtil.parse(args[2]);
                if (price.isEmpty() || price.getAsDouble() < 0) { sender.sendMessage(Msg.c("&cInvalid price.")); return; }
                prices.setBase(mat, price.getAsDouble());
                sender.sendMessage(Msg.c("&aBase sell price for " + ItemNames.pretty(mat) + " set to &2"
                        + MoneyUtil.format(price.getAsDouble()) + " &aeach."));
            }
            case "sellmultiplier" -> {
                if (args.length < 2) {
                    sender.sendMessage(Msg.c("&7Current multiplier: &f" + prices.multiplier()));
                    return;
                }
                OptionalDouble v = MoneyUtil.parse(args[1]);
                if (v.isEmpty()) { sender.sendMessage(Msg.c("&cInvalid value.")); return; }
                prices.setMultiplier(v.getAsDouble());
                sender.sendMessage(Msg.c("&aSell multiplier set to &f" + prices.multiplier() + "&a."));
            }
            default -> sender.sendMessage(Msg.c("&cUsage: /eco <give|take|set|sellprice|sellmultiplier> ..."));
        }
    }

    // ---------------------------------------------------------- shop

    private void shopCmd(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) { playersOnly(sender); return; }
        if (args.length == 0) { gui.openShop(p, 0); return; }
        if (args[0].equalsIgnoreCase("search")) {
            gui.searchShop(p, String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
            return;
        }
        if (args[0].equalsIgnoreCase("mine")) {
            gui.openMyShop(p);
            return;
        }
        if (!args[0].equalsIgnoreCase("list")) {
            p.sendMessage(Msg.c("&cUsage: /shop, /shop mine, /shop search <text>, /shop list [item] [amount] [price each]"));
            return;
        }
        if (args.length == 1) { flows.listPicker(p, "", 0); return; }
        Material mat = Material.matchMaterial(args[1]);
        if (mat == null || mat.isAir() || !mat.isItem()) { p.sendMessage(Msg.c("&cUnknown item.")); return; }
        String amount = args.length >= 3 ? args[2] : "";
        String price = args.length >= 4 ? args[3] : "";
        flows.openListDetails(p, mat, amount, price);
    }

    // ---------------------------------------------------------- sell

    private void sellCmd(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) { playersOnly(sender); return; }
        if (args.length == 0) { flows.sellMenu(p); return; }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "hand" -> flows.sellHandConfirm(p);
            case "all" -> flows.sellAllConfirm(p);
            default -> p.sendMessage(Msg.c("&cUsage: /sell, /sell hand, /sell all"));
        }
    }

    // ---------------------------------------------------------- tab complete

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        switch (name) {
            case "pay", "balance" -> {
                if (args.length == 1) Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
            }
            case "eco" -> {
                if (args.length == 1) out.addAll(List.of("give", "take", "set", "sellprice", "sellmultiplier"));
                else if (args.length == 2 && List.of("give", "take", "set").contains(args[0].toLowerCase(Locale.ROOT))) {
                    Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
                } else if (args.length == 2 && args[0].equalsIgnoreCase("sellprice")) {
                    return matItems(last);
                }
            }
            case "shop" -> {
                if (args.length == 1) out.addAll(List.of("list", "search", "mine"));
                else if (args.length == 2 && args[0].equalsIgnoreCase("list")) return matItems(last);
            }
            case "sell" -> {
                if (args.length == 1) out.addAll(List.of("hand", "all"));
            }
            default -> {}
        }
        return out.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(last)).collect(Collectors.toList());
    }

    private List<String> matItems(String prefix) {
        return Arrays.stream(Material.values())
                .filter(m -> m.isItem() && !m.isAir() && !m.name().startsWith("LEGACY_"))
                .map(m -> m.name().toLowerCase(Locale.ROOT))
                .filter(s -> s.startsWith(prefix))
                .limit(50)
                .collect(Collectors.toList());
    }
}
