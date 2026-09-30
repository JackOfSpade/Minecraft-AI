UPDATE 2026-09-30 (user): R2 GEAR is REPLACED: TOOLS worst-first (next worst when broken); NON-TOOLS (weapons, armor,
shields, bows/crossbows, trident, mace, elytra) BEST-first (next best auto-equipped when broken); every item is used
until it breaks (no durability-based swaps, all bots incl. PvP BOT inhabitants). Minecraft-AI bots warn the owner in chat
(instantly, once per crossing) below 10% durability for diamond/netherite gear/tools, shield, bow, crossbow, trident,
mace, elytra, fishing rod; chat only, never interrupt.

USER DECISIONS (2026-09-29) - these OVERRIDE anything in the design spec that disagrees:
- R2 GEAR: ALWAYS worst-first (the cheapest/lowest-value item that can still do the job; enchantments add value) for
  tools, weapons and armor. NO escalation of any kind (no danger escalation, no place-based escalation, no escalation
  hold), NO mission stone-pick floor (mining missions are worst-first too; retune mission budget tests only if needed and
  report it), NO human-dressed armor provenance (armor is always the worst adequate piece per slot). Predictable logic;
  the player controls it by taking items out of the bot's inventory. Config: behaviour.gear.worstFirst only.
- R3 WARDENS: never fight. Sneak away from a calm warden; sprint while it hunts the bot (roaring/charging/attacking);
  sneak again once it stops chasing. Near a calm warden (<= 16 blocks) the 'always sprint under aggro' rule is capped to
  walk/sneak unless the warden is hunting the bot or the bot is taking damage.
- R4 FOLLOW doubles as RETREAT: retreat/evade movement uses the same pace policy and the same escort rule (only knock
  back what is in melee range while continuing to move away).
- R5 NO MICRO-TELEPORTS: remove every path-correction teleport in ALL profiles (including the long path-start snap in
  the operator profile). Only the user-triggered panel teleports (MANUAL_TELEPORT) and operator-profile EMERGENCY rescues
  (suffocation/drowning/dark-trap/gather surfacing; never in strict survival) remain.
- R6 NEW: auto-eat when hunger drops to the sprint limit: eat at food <= 7 so food never falls to 6 where vanilla stops
  sprinting; not in the middle of immediate combat (then eat as soon as the fight allows); normal food choice rules
  (reserve foods, poison rules) still apply.
