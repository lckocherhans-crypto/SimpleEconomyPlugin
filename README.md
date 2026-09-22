# Simple Economy Plugin

A simple economy for Paper: balances, a player shop, and a fixed-price /sell. The shop's buy menu
and "Your shop" lots grid are chest GUIs; everything else (balance, leaderboard, pay, listing,
buying, /sell) is a dialog.

## Build
**GitHub:** push this project to a repo. The Build action runs automatically; download the jar from
Actions > latest run > Artifacts.

**Locally:** `gradle build`, jar ends up in build/libs/. Drop it into your server's `plugins/` folder.

## Commands
| Command | What it does |
|---|---|
| /bal | Balance dialog (leaderboard, pay, shop, sell shortcuts) |
| /pay [player] [amount] | Pay dialog, or pay directly with both arguments |
| /baltop | Leaderboard dialog |
| /eco give/take/set <player> <amount> | Admin (simpleeconomy.admin) |
| /eco sellprice <item> <price\|remove> | Set (or remove) an item's base /sell price |
| /eco sellmultiplier [value] | View or set the global sell multiplier |
| /shop | Browse the player shop |
| /shop mine | Your shop lots |
| /shop list [item] [amount] [price each] | List items for sale (dialog picker if no args) |
| /shop search <text> | Search the shop |
| /sell | Sell menu (sell held item / sell everything sellable) |
| /sell hand | Sell the item in your hand at its fixed base price |
| /sell all | Sell every plain, sellable item in your inventory |

## How pricing works
`prices.yml` holds a fixed base price per item, seeded with a starter list on first run. Players
never set these - only an admin can, via `/eco sellprice` or by editing the file - so nobody can
"sell" a dirt block for a fortune. `sell.multiplier` in `config.yml` is a single global knob an
admin can tune to adjust the whole economy without touching every item.

The shop (`/shop`) is unrelated to this: it's where players set their own prices, for their own
listings, paid for with their own money - same as a chest shop.

## Shop listings
A listing is a material and a count, not an item stack, so a lot can hold far more than 64 items -
the amount just shows in the item's lore. Only plain items (no rename, no enchants) can be listed
or sold via /sell, so nobody can hide value in item metadata.

## Lots
"Your shop" shows a grid of lots: filled lots show your listing, grey glass is a free lot (click to
list something there), red glass is a locked lot. The number of lots is `shop.max-listings` in
config.yml (max 27).

## Claim box
If a purchase or a cancelled listing can't fully fit in a player's inventory, the leftover items go
to their claim box, visible in the shop menu and "Your shop".
