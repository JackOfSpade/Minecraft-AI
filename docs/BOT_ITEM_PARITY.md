# Bot item and interaction parity

A bot is bound by exactly the rules a survival player is bound by: no shortcut a player lacks, and no loss a player would not suffer.
This page lists where the mod used to differ and what a bot does now. The tests are in `VanillaParityGameTests`,
`BuildActionEdgeVisibilityGameTests`, `BotRespawnStateGameTests`, `BotPersistenceRestoreGameTests`, `TradeRulesTest` and
`VanillaParitySourceContractTest`.

## Placing and using blocks

* A block is only ever placed by a real right click on a real support face (`BuildAction.placeBlock` and friends). The operator profile
  used to have a fallback that wrote a block into mid-air with `Level.setBlock`; it is gone, in every profile. A caller that has no
  support face fails with the failure of the click attempts and builds real support (pillar, bridge, scaffold) like a player does.
* Every block use (`useItemOnHit`, `useItemOnFace`, `useItemOnCell`, `placeBlock`, and opening a container) repeats the check the packet
  handler makes before a click reaches the game mode (`ServerLevel.mayInteract`): spawn protection for a non-operator and the world
  border. The refusal is `protected_area`. `spawn-protection=0` in `server.properties` switches the vanilla rule off.

## Picking up

* There is no forced pickup. A bot collects a drop by walking onto it, inside vanilla's pickup range and after the pickup delay
  (`HarvestCore.chaseDropAnyOf`, `sweepPickupAnyOf`, `walkOverDrops`). The `forcedPickup` operator capability and `pickup.forceRadiusH/V`
  are removed; an old key is ignored and reported once (`config_removed_keys_ignored`).

## Hunger and item use

* The hunger cost of moving and the 0.2 movement scale while using an item are always applied (`behaviour.pace.movementExhaustion` and
  `behaviour.pace.itemUseSlowdown` are removed, with the same one-line report for an old key).

## Dying and restoring

* A bot revived in place after dying comes back like a vanilla respawn: no status effects, XP level, progress and total zero (`die()` has
  already dropped the orbs, so keeping the level duplicated it), food 20, saturation 5, no fire, full air, no absorption or frostbite.
* A restored bot takes its record as the single source of truth: when the record carries the full player state, the main inventory,
  armor, offhand, ender chest, effects, XP, fire and absorption the fresh player holds are discarded before the record is applied, so an
  item that was moved out since (an armor piece put into a chest) cannot exist twice. (In 1.21.11 `PlayerList.placeNewPlayer` does not
  read the vanilla playerdata file for a fake player; the discard keeps the guarantee independent of that.)

## Smelting

* Fuel above the slot maximum stays in the inventory instead of being deleted by the slot's clamp; only what moved into the furnace
  leaves the inventory. The real stacks move (components stay), a slot only takes a stack that matches what it holds.
* Taking the output removes only what the inventory really took (`Inventory.add` answers true on a partial insert) and pays out the
  recipe experience the furnace stored, as orbs at the bot, as it does for a player at the output slot.

## Trading

* The bot trades only with a villager that would open its trades for a player (`Villager.mobInteract`): awake, adult, not trading with
  someone else, with offers.
* The offers are priced for the bot (`updateSpecialPrices`: reputation, hero of the village) and the bot is the trading player for the
  duration of the trade, so the level-up reputation event names it.
* The payment is real stacks matching the offer's cost. The whole result has to fit after the payment; otherwise the payment is given
  back and the trade fails with `inventory_full`.
* `Villager.notifyTrade` does the bookkeeping (uses, villager experience and its orb, ambient sound timer, trade criterion), and the
  traded-with-villager statistic is awarded.

## Crafting

* A consumed container leaves its remainder (an empty bucket for every milk bucket of a cake), in the preview and on commit; a craft whose
  remainder does not fit is refused as a whole (`craft_remainder_capacity`).
* The crafted stack carries the components of the vanilla recipe result (`Recipe.resultComponents`, filled from the runtime recipe index).
* Ingredients are matched with vanilla's ingredient test (the item only, as in a crafting grid); plain stacks are used before a renamed,
  damaged or enchanted one.
* The oak fence is 4 oak planks and 2 sticks.

## Not done (feature gap, not parity)

Bots never drink or throw potions, use ender pearls, tridents or milk to cure effects, and cannot craft arrows or bows. (A totem of
undying is not used actively: `OffhandPolicy` keeps the best shield, else a totem, in the offhand, and a held totem pops through vanilla.) Natural entry points: `EquipAction` (the bow and arrow selection), `CombatTask` (the draw and release of a bow, the
threat handling), `EatAction` and `SurvivalGuard` for consumables, `BuildAction.useItemOnHit` and `ServerPlayerController.useItem` for
the use of an item, `RecipeRegistry` (arrows, bows and crossbows are missing from the handwritten table; the runtime index covers them).
