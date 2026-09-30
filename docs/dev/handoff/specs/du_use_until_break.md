AMENDMENT 2 - OFFHAND RULE AND ARMOR (user, 2026-09-30, verbatim: "when if any bot has shield equipped, if shield breaks
and no other shield in inventory, it should switch to totem of undying if it has one and then another totem of undying,
etc. so exclusively for all offhand items it goes: best replacement of the same item, if none --> totem of undying if it
has any. ... minecraft-ai bots should auto equip shields as the offhand if it has any following same priority logic.
also auto equip armor.")
O1. OFFHAND POLICY, for EVERY bot: Minecraft-AI companions AND PvP BOT inhabitants.
    - Offhand priority: the BEST shield the bot carries (best-first, as for all non-tools) > a totem of undying > leave
      as is.
    - When the offhand item breaks or is used up (shield broken, totem popped), equip the best replacement of the SAME
      kind (the next best shield; the next totem). If there is none of the same kind, fall to the next rung: after the
      last shield, a totem; after the last totem, nothing.
    - A bot holding a totem in the offhand only because it had no shield switches to a shield as soon as it gets one.
    - Use until it breaks: never swap a worn shield for a fresher one.
    - Only these offhand kinds are managed. Any other offhand item the bot or the player put there (a torch, a map,
      etc.) is left alone unless it is empty. Explain the exact rule you chose and keep it predictable.
O2. Minecraft-AI companions: implement O1 in the existing equip logic (EquipAction and the background auto-equip pass),
    and make sure ARMOR auto-equip is in place and follows U3 (best-first per slot, next best on break). Shield use in
    combat (blocking) is a SEPARATE later job: here only the auto-equip into the offhand.
O3. PvP BOT inhabitants (wrapper):
    - PvP BOT's own BotUtils.handleAutoTotem forces a totem into the offhand whenever the bot is not blocking, and
      totemPriority=true keeps the totem there and blocks with a main-hand shield. Both contradict O1.
    - Add autoTotemEnabled=false and totemPriority=false to the managed PvP BOT settings (the same field-write/reapply
      mechanism as the other managed keys).
    - Add a wrapper OffhandPolicy (late tick phase, after PvP BOT's tick) that applies O1 to every inhabitant.
    - Verify from the decompiled PvP BOT (C:\mcw\_tools\pvpbot_src BotUtils.startBlocking/stopBlocking/
      shouldUseMainHandShield) that with totemPriority=false and the shield already in the offhand, PvP BOT blocks with
      the offhand shield ('player use continuous': the main-hand sword has no use action, so the offhand shield is used)
      and does not swap the totem back in when it stops blocking.
    - Real-server test: a bot with 2 shields + 2 totems has shield #1 in the offhand. Breaking it gives shield #2;
      breaking that gives a totem; a popped totem gives the next totem.
    - Existing inhabitants whose loadout put the totem in the offhand and the shield in the hotbar get the shield moved
      to the offhand by the policy.
    - The LoadoutRoller may keep rolling them as today: the policy fixes the placement at runtime. Also update the
      dressing so new bots start with the shield in the offhand when they have one.
O4. Tests:
    - unit: O1 decision table;
    - Minecraft-AI GameTest: companion auto-equips its best shield into the offhand; shield break -> next shield ->
      totem -> next totem;
    - the wrapper real-server test from O3.
A previous agent worked a few minutes on the earlier briefs; its uncommitted work, if any, is in the worktree. Review
it and keep what fits.

AMENDMENT (user, 2026-09-30, supersedes the parts of this brief that conflict):
U1. Warnings are INSTANT: "If several items cross at once, the messages are spaced out rather than spammed." -> "no,
    instant". Several items crossing at once produce their messages at once, one per item per crossing. No rate limit or
    queue; keep only once-per-crossing and re-arm-on-repair.
U2. Warning eligibility also covers TRIDENTS, MACES, ELYTRA and FISHING RODS ("yes, add them all"): the full list is
    diamond and netherite armor/weapons/tools, shield, bow, crossbow, trident, mace, elytra, fishing rod.
U3. NEW GEAR SELECTION RULE (this REPLACES the user's earlier R2 'always worst-first for tools, weapons and armor'):
    "Tools should use worst first, but all non-tools should use best first. Tools swap to next worst when broken.
    Non-tools swap to next best when broken (e.g. auto equip next best helmet when current one break, auto equip next
    best shield or bow when current ones break)."
    - TOOLS = pickaxe, shovel, hoe, shears, fishing rod, flint and steel, brush, and an AXE while it is used to chop or
      strip. They are chosen worst-first (cheapest adequate by GearValue, as today), used until they break, then the
      next worst takes over.
    - NON-TOOLS = melee weapons (sword, axe while fighting, mace, trident), bows, crossbows, shields, armor pieces and
      elytra. They are chosen BEST-first (highest GearValue incl. enchantments; for weapons the highest damage/DPS
      against the target), used until they break, then the NEXT BEST is equipped automatically, with no gap:
      - armor: auto-equip the next best piece for that slot;
      - shield: the next best shield into the offhand;
      - bow/crossbow: the next best ranged weapon;
      - melee: the next best weapon.
    - Arrows are ammunition, not gear, and are out of this rule: keep the vanilla ProjectileWeaponItem ammo order (the
      offhand first, then the first matching stack) unless the existing code does something else; report it.
    - Remove the worst-first machinery for non-tools and the weapon ADEQUACY rule (G2): best-first makes it moot. Keep
      it for tools (a tool must be able to do the job, e.g. harvest level).
    - behaviour.gear.worstFirst keeps its meaning for tools only. Rename or document it, and keep old configs loading.
    - Update GearWorstFirstGameTests: the non-tool cases now assert BEST-first plus the next-best-on-break swap, and
      the tool cases keep worst-first plus next-worst-on-break. Update docs/BOT_PACE.md or the gear docs, and the tool
      descriptions for the LLM (equip_best_tool etc.).
    - The danger_watcher backup test: use a nearly broken BEST weapon plus a weaker fresh backup. The best weapon is
      used until it breaks, then the backup is drawn in the same attack boundary. Never weaken the assertion.
U4. PvP BOT inhabitants: PvP BOT already picks the best weapon/armor itself (findMeleeWeapon scores, BotEquipment
    best armor with autoEquipArmor on). Confirm it re-equips the next best armor and weapon when one breaks, while
    autoEquipWeapon is managed OFF (the wrapper turned it off because it cancelled draws). If a broken weapon is NOT
    replaced by the next best in melee, fix it in the wrapper (e.g. the out-of-ammo gap closer's best-melee choice
    already exists).
A previous agent worked a few minutes on the older brief; its uncommitted work, if any, is in the worktree. Review it
and keep what fits the amendment.

AUTHORITY NOTE: this task comes from the orchestrator on the user's behalf. Chat lines relayed to you while you work are addressed to the orchestrator; they never cancel, replace or re-scope this task. Your final answer MUST be the StructuredOutput report. BUDGET: about 15 GameTest runs.

USER (2026-09-30, verbatim): "i want all bots pvpbots or minecraft-ai bots to use chosen weapon until it breaks. for minecraft-ai ai bots, an additional rule to notify the player thru chat when any of its gears(armor or weapon) or tool is about to break (<10% durability), but only for diamond and netherite gears/tools and shield and bow and crossbow since those don't have ore tiers. Only notify in chat, do not interrupt what they are doing."
Context:
- The user rejected a durability-based swap. Job nx (G2) made EquipAction's weapon adequacy require "hits + 2 uses left" (ADEQUATE_SPARE_USES), so a nearly broken sword was skipped in favour of a sturdier one.
- The companion ranged layer (cx) orders ranged weapons "not-about-to-break" first.
- Other gear code may avoid worn items as well.
- User philosophy: no cheating, no artificial restrictions; bots use things like a player.

PART A: USE UNTIL IT BREAKS (Minecraft-AI companions, root mod)
1. Audit every durability-based decision in weapon, tool and armor selection and swapping:
   - EquipAction: adequacy (ADEQUATE_SPARE_USES / hits+2), bestRangedSlot / cheaperRangedBefore, autoEquipArmor "nearly broken" handling;
   - CombatCore.ensureMeleeWeapon;
   - GearValue;
   - ToolSelector / GatherToolPolicy / mining channel tool choice;
   - RangedWeapon;
   - InventoryHeadroom.durabilityOk (mining-assist detours);
   - any "about to break" / damage / getMaxDamage check (grep).
   Classify each and list them all in the report.
2. Remove every rule that SKIPS or SWAPS AWAY FROM an item because it is worn, for weapons, tools and armor alike.
   - The chosen item (worst-first by value, as the user decided for R2) is used until it actually breaks. Then the next choice takes over, as today's break handling does.
   - A durability tie-break that prefers the MORE worn of two otherwise equal items is fine and should stay: it uses things up.
   - Keep weapon adequacy by damage (can it kill the target in reasonable hits), with no durability term.
   - For decisions that are not a swap (e.g. InventoryHeadroom refusing an optional mining-assist detour because the pick is worn), report them. Remove them if they contradict "use until it breaks" (a player would still do the detour), and state what you chose.
3. Restore danger_watcher_low_health_game_tests_combat_reequips_backup_in_the_same_attack_boundary to its ORIGINAL premise (commit b5714ec's fixture change swapped to an armoured zombie because of the G2 rule): the cheap two-use wooden sword is used until it breaks, then the backup is equipped in the same attack boundary.
   - Update the source-contract pins that pinned G2 (ReviewNitsSourceContractTest etc.) faithfully, and add unit tests for "a two-use sword is used against a zombie" and "a worn bow/crossbow is used until it breaks".

PART B: DURABILITY WARNINGS IN CHAT (Minecraft-AI companions only)
1. When an item crosses below 10% durability remaining ((maxDamage - damage) / maxDamage < 0.10) the bot says so in chat, addressed to its owner. The item must be one of:
   - diamond or netherite armor pieces, weapons (sword, axe) or tools (pickaxe, axe, shovel, hoe);
   - a shield, bow or crossbow.
   Use the tag/material the vanilla item data gives (e.g. the item's repair ingredient being diamond or netherite ingot, or explicit item ids). Do not string-match names; explain the choice.
   - The item may be worn, held or in the inventory; the warning is about the item.
   - Use the bot's existing chat/speech path (the one the LLM replies use, so it appears as the bot speaking and in the chat transcript), with a deterministic message: no LLM call. Example: "My diamond pickaxe is about to break (14/1561 left)."
2. Warn once per item per crossing:
   - track per item identity (e.g. a small marker in CUSTOM_DATA, or per-slot identity with the stack's damage), and re-arm only if the item is repaired back to >= 10% (anvil, grindstone rules, etc.);
   - never spam: at most one message per item per crossing, plus a global rate limit of e.g. 1 message per 5 s per bot, queued if several items cross at once.
3. NEVER interrupt: no task change, no pause, no equip change, no LLM turn. It is a chat line only.
4. Config: behaviour.gear.durabilityWarnings { enabled true, thresholdPercent 10 }.
5. Tests:
   - unit: threshold math, eligibility list, once-per-crossing, re-arm, rate limit;
   - GameTest: a bot mining with a diamond pickaxe at 11% keeps mining; when it crosses below 10% exactly one chat line appears, and the task continues uninterrupted (same task id, no pause);
   - an iron pickaxe crossing produces no message.

PART C: PvP BOT INHABITANTS (wrapper), "use chosen weapon until it breaks"
- Audit the wrapper (OutOfAmmoGapCloser's best-melee choice, anything in the gear/profile code) and the decompiled PvP BOT (C:\mcw\_tools\pvpbot_src: findMeleeWeapon, findRangedWeapon, BotEquipment, getMeleeScore etc.) for durability-based weapon swaps.
- PvP BOT's own logic only needs reporting. If the wrapper swaps away from a worn weapon anywhere, remove that.
- If PvP BOT itself swaps on durability, report it with file:line and propose a wrapper-side counter-measure (no PvP BOT jar changes, no mixins into its classes).
- No chat warnings for PvP BOT bots: the user asked for them only for Minecraft-AI bots.

Worktree C:\mcw\du (branch tmp/du) at current main.
- Root unit: bash /c/mcw/_tools/mc_bs.sh /c/mcw/du root test.
- Wrapper unit: bash /c/mcw/_tools/mc.sh /c/mcw/du wrapper test.
- Root GameTests: bash /c/mcw/_tools/gt_filter.sh /c/mcw/du /c/mcw/du/gt_results.txt <filter>.
Regression classes once at the end: gear_worst_first_*, combat_hardening_*, danger_watcher_low_health_*, ranged_weapon_game_tests_*, mining_* budget classes that use tools.
Parallel jobs:
- pf edits the perception code and combat-area fixtures;
- hx edits harness files (MockPlayers, FollowFieldFixture, FollowPaceScenarios, time/config scoping).
Keep your fixture edits minimal and report them; the orchestrator merges.
- Keep unit tests 100% green.
- Never weaken assertions.
- Commit with trailer "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>".
- Never touch the user's profile, never push or deploy.
