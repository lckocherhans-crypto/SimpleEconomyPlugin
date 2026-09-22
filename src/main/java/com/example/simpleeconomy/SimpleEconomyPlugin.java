package com.example.simpleeconomy;

import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.UUID;

public class SimpleEconomyPlugin extends JavaPlugin implements Listener {
    private EconomyManager economy;
    private ShopManager shop;
    private PriceManager prices;
    private Gui gui;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        economy = new EconomyManager(this);
        shop = new ShopManager(this, economy);
        prices = new PriceManager(this);
        economy.load();
        shop.load();
        prices.load();

        Dialogs dialogs = new Dialogs(this);
        Flows flows = new Flows(this, economy, shop, prices, dialogs);
        gui = new Gui(this, economy, shop, dialogs, flows);
        flows.setGui(gui);

        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new MoneyExpansion(this, economy).register();
            getLogger().info("Hooked into PlaceholderAPI.");
        }

        Commands cmds = new Commands(economy, prices, gui, flows);
        for (String name : List.of("balance", "pay", "baltop", "eco", "shop", "sell")) {
            PluginCommand c = getCommand(name);
            if (c != null) {
                c.setExecutor(cmds);
                c.setTabCompleter(cmds);
            }
        }

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(gui, this);

        // autosave every 5 minutes
        getServer().getScheduler().runTaskTimer(this, this::saveAll, 6000L, 6000L);
    }

    @Override
    public void onDisable() {
        saveAll();
    }

    private void saveAll() {
        economy.save();
        shop.save();
        prices.save();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        economy.ensure(id);
        int claimable = shop.claimCount(id);
        if (claimable > 0) p.sendMessage(Msg.c("&eYou have &f" + claimable + " &eitem(s) waiting in your shop claim box. Claim them in &f/shop&e."));
    }
}
