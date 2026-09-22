package com.example.simpleeconomy;

import org.bukkit.Material;

import java.util.UUID;

/**
 * A player shop listing. Only plain materials (no custom name, no enchants, no NBT) are supported -
 * this keeps a listing to a material + a count, so amounts can go far past a 64-item stack without
 * the plugin ever having to hold real oversized ItemStacks.
 */
public class ShopListing {
    public final UUID id;
    public final UUID seller;
    public final String sellerName;
    public final Material material;
    public final double pricePerItem;
    public final long listedAt;
    public int amount; // remaining amount for sale

    public ShopListing(UUID id, UUID seller, String sellerName, Material material, int amount, double pricePerItem, long listedAt) {
        this.id = id;
        this.seller = seller;
        this.sellerName = sellerName;
        this.material = material;
        this.amount = amount;
        this.pricePerItem = pricePerItem;
        this.listedAt = listedAt;
    }
}
