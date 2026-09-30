# The user's rules (current versions, 2026-09-30)

These are product requirements. Where an older spec or log entry disagrees, this file wins. Where a job spec in
`specs/` is more detailed, follow the spec, as long as it does not contradict this file.

## Philosophy (all bots)
- **No cheating and no artificial restrictions.** Bots play by the game's own rules.
  - No cheating: no teleports, no x-ray, no seeing behind, no instant reactions, no refills, no superhuman stats.
  - No artificial handicaps: no invented rate caps, leashes or distance limits.
  - Limits come only from vanilla mechanics (charge times, cooldowns, physics, reach) or from the realism rules below.
- **No magic knowledge.** A bot acts only on what it perceives: sees, hears, or remembers as a last known position.
  - An arrow from an unseen shooter tells it the direction only.
  - A logout or death it did not see is unknown to it.
- **Call vanilla, don't reimplement it.** Example: hearing uses vanilla's VibrationSystem, the same classes as the
  sculk sensor and the warden. The user's words: "not borrow, call them".
- **No micro-teleports** to correct pathing. Movement is real inputs.

## Perception (both mods, same math, same golden vectors in docs/perception/vectors.json)
- **Sight:**
  - Unlimited distance inside the view cone, up to the mod maximum. PvP BOT sight is unlimited in the cone.
    Companions see as far as the profile observation radius.
  - Full attention up to 30°, peripheral vision to 100°, nothing behind.
  - Occlusion by colliders; sneaking reduces what the bot notices.
- **Reaction time** (continuous) = (0.5 + 1.5·d/64) s × angle factor × 2 if the target is sneaking, or scaled by vanilla
  visibility.
  - The angle factor is 1 up to 30° and rises linearly to 2 at 100°.
  - It applies on EVERY re-sighting, mid-fight included: no instant sniping or sword hit when you reappear.
- **Hearing:** vanilla vibrations, radius 16. A heard but unseen sound is only a clue for where to search.
- **Human aim:**
  - Turn rate about 540°/s; no instant 180° or 360° spins.
  - Fire only once the aim has settled.
  - Melee only hits what is under the crosshair, within vanilla per-weapon reach (AttackRange). No hits through walls.

## Hostile PvP BOT inhabitants (Minecraft-Spawn-Bots-Wrapper, mod id pvpbot_inhabitants)
- **Engagement:**
  - The only distance rule: never ENGAGE beyond 64 blocks, judged only from a sighting.
  - Engagement starts when the bot has seen the target for its reaction time, or when the bot is hit.
- **Hunt cycle:**
  - Chase while the target is in sight.
  - When sight is lost: go to the last known position, search smartly for 10 s, then walk back to the FIRST aggro start.
  - A re-sighting while returning restarts the cycle; the home point stays the same.
- **Weapons:**
  - Natural fire rates, no caps: Quick Charge III ≈ 12 ticks, bow 20-tick draw.
  - Switch to a melee weapon within 5 blocks (managed meleeRange 2.5). Out of ammo → sword.
- **Items:**
  - Arrows 0–32 at spawn. PvP BOT has no pickup logic; leave it.
  - Consume like a player: no refills, no eating at full hunger; survival mode is forced.
- **No Piercing and no Mending** on wrapper-issued gear. XP bottles stay as loot. Items a bot picks up from players are
  never touched.
- **Population:**
  - No per-structure cap and no "where are my bots" command.
  - The nearest structure (3D distance) fills to its size target, the rest goes to the next nearest; recompute
    dynamically.
  - A bot the player has SEEN persists until death. It goes dormant with its full state and position when its chunk
    unloads, and comes back as the same bot, with the same loot, in the same place.
  - UNSEEN bots are deleted when unloaded or evicted.
  - No loot or XP farm: removal is never a death, deaths are never refilled, and each structure has a lifetime cap N.
- **Mod boundaries:** never modify the PvP BOT or HeroBot jars, and no mixins into their classes. Behaviour changes go
  through the wrapper, managed settings and events.

## Minecraft-AI companions
- **Targeting:**
  - Never target the player or other companions.
  - DO target hostile bots that aggro onto the player or companions and are seen by the bot or the player.
- **Wardens:** never fought. Sneak away while the warden is calm, sprint away while it hunts.
- **Follow and escort pace:**
  - In follow mode, do not go off to fight; only hit what gets into melee range, while continuing to follow.
  - Walk when close, sprint when far, and match the player's pace (sneak or walk carefully when they do).
  - Always sprint while hostiles are aggroed.
  - Retreat uses the same follow logic.
- **Auto-eat** at food ≤ 7 (vanilla: sprinting needs food > 6).
- **Bots never sleep.** Only humans count for night skipping.

## Gear rule (ALL bots, companions and PvP BOT inhabitants)
- **Tools** (pickaxe, shovel, hoe, shears, fishing rod, axe while chopping): used WORST-first. When one breaks, the next
  worst takes over.
- **Non-tools** (weapons, armor, shields, bows/crossbows, trident, mace, elytra): used BEST-first. When one breaks, the
  next best is auto-equipped.
- **Use until it breaks:** a bot never swaps an item because it is worn. A nearly broken weapon is still used.
- **Offhand (all bots):** the best shield → the next shield when it breaks → a totem of undying if no shield is left →
  the next totem after a pop.
  - PvP BOT's own totem priority (totem in the offhand, shield in the main hand) is overridden.
  - Companions auto-equip shields into the offhand, and armor best-first.
- **Durability warnings (companions only):**
  - Warn the owner in chat instantly, once per item, when it drops below 10% durability.
  - Covers diamond and netherite gear and tools, plus shield, bow, crossbow, trident, mace, elytra and fishing rod.
  - Chat only; never interrupt what the bot is doing.

## Shield use (companions)
- **What to block:** every NOTICED blockable projectile and melee hit, following vanilla's BlocksAttacks rules
  (#bypasses_shield, block delay, front angle).
- **Never try against:**
  - splash and lingering potions;
  - the warden's sonic boom;
  - Piercing arrows and bolts;
  - evoker fangs;
  - dragon breath and dragon fireballs;
  - magic, lightning and environmental damage.
- **How:**
  - Human reaction time and turn rate.
  - Lower the shield to swing.
  - An axe hit disables the shield for the vanilla cooldown.
  - A bow or food in the main hand takes the use first, exactly as vanilla does.

## Process rules
- **Commits:**
  - Commit as `Claude <noreply@anthropic.com>`, ending with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
  - Never weaken test assertions to make them pass; fix the product or the fixture premise, and say which.
- **Secrets and licences:**
  - Never read, print or commit `.env` or any API key.
  - Never commit third-party jars, decompiled PvP BOT or HeroBot code, or Mojang mapping files: this repository is public.
- **End state:** only `main` remains on GitHub (delete the `tmp/*` branches after merging).
