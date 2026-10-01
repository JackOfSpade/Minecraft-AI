# Shield use

Companions use a shield like a competent player: they block every BLOCKABLE projectile and melee attack they have actually
NOTICED (docs/PERCEPTION.md), and never raise the shield against damage it cannot stop. One owner decides it for every task
(following, escorting, mining, gathering, idle, combat): `task/ShieldGuard`, ticked once per bot per server tick from
`BotTickCoordinator`, right after the equipment pass. The pure decisions are in `action/ShieldRules`, the blockability in
`action/ShieldBlockability`.

No cheating and no invented limits: blocking goes through vanilla's own use path, the head turns at the human aim speed, the reaction
time is the shared perception formula, the block delay and the front arc are the shield item's own (`BLOCKS_ATTACKS`), and a disabled
shield is disabled for vanilla's own item cooldown.

## What blocks, from vanilla's data

Any item with the `BLOCKS_ATTACKS` component is a shield. What a projectile deals is the vanilla damage type and amount of its direct
hit on a player (the same `DamageSources` factory and constant the projectile class itself uses); the shield's `bypassed_by` tag
(`#minecraft:bypasses_shield`) decides, through `DamageSource.is` on the real registry. An arrow with `pierceLevel > 0` bypasses the
block (vanilla's `applyItemBlocking`). A hit of zero damage is nothing to block (`Player.hurtServer` ignores it altogether). The front
arc is the widest `horizontal_blocking_angle` of the damage reductions that apply (a shield: 90 degrees each side of the HEAD), the
delay `block_delay_seconds` (0.25 s = 5 ticks for a shield).

| Kind | Damage type | Blocked |
|---|---|---|
| arrows and bolts without Piercing (tipped, spectral), tridents | `arrow`, `trident` | yes |
| ghast fireball, blaze fireball, wither skull, shulker bullet (blocking prevents levitation), llama spit | `fireball`, `wither_skull`, `mob_projectile`, `spit` | yes |
| wind charge (a player's or a breeze's; the burst still pushes), firework rocket from a crossbow | `wind_charge`, `fireworks` | yes |
| explosions faced (creeper, TNT, fireball) | `explosion`, `player_explosion` | yes |
| melee: `mob_attack`, `mob_attack_no_aggro`, `player_attack`, `sting`, `mace_smash`, `spear` | | yes, from the front |
| guardian and elder guardian beam | `indirect_magic` (1, 3 on Hard, +2 elder: unblockable), then `mob_attack` (6, elder 8) | the `mob_attack` part |
| Piercing arrows and bolts | `arrow` with `pierceLevel > 0` | no |
| snowballs, eggs, ender pearls | `thrown` of ZERO damage to a player (a snowball hurts only blazes) | nothing to block |
| thrown splash and lingering potions, evoker fangs | `indirect_magic` | no |
| a wither skull with no living owner | `magic` (5) | no |
| a firework rocket without explosions, or not shot at an angle (going up, boosting an elytra) | no hit (0) | nothing to block |
| experience bottles, area effect clouds, eyes of ender | no hit | never reacted to |
| warden sonic boom | `sonic_boom` | no |
| dragon fireballs (no hit of their own) and dragon breath clouds | `dragon_breath`, the Harming effect | no |
| lightning (including a Channeling trident's bolt) | `lightning_bolt` | no |
| fire, lava, cactus, berry bushes, falling anvils and stalactites, magic, wither, freeze, starve, fall, drown, ... | the rest of `#bypasses_shield` | no |

`ShieldBlockabilityTest` checks this table against the tag files of the vanilla jar, so a change of the game's data shows up as a
failing test.

## What it reacts to (noticed, never magic)

* **An incoming projectile** the bot senses: any blockable projectile seen in flight inside its view field with a clear line, plus an
  exact vanilla arrow, spectral arrow, trident, or llama spit whose `PROJECTILE_SHOOT` vibration matches both its back-projected
  ballistic event block and the vibration's original travel time. Hearing alone never associates fireballs, wither skulls, shulker
  bullets, rockets, wind charges, or modded subclasses: their source or future target is not information the bot has. The course uses
  the appropriate observable vanilla motion: ordinary ballistic drag/gravity only for those exact arrow/spit types; the public current
  acceleration and inertia for ghast/blaze fireballs and wither skulls; and exact linear wind-charge motion. Homing shulker bullets,
  rockets, snowballs, eggs, and unknown modded motion use a short-horizon ray of the current visible velocity, recomputed every tick
  rather than invented from a hidden target or subclass state.
* **The reaction.** A projectile from a shooter the bot is already tracking (a creature it has noticed) was anticipated: the bot watched
  the release and reacts at once. Anything else is a first sighting and waits the human reaction time of the shared formula
  (`(0.5 + 1.5 d / 64) s x angle factor`, for the projectile's distance and angle; a shot only heard counts with angle factor 1), in
  continuous exposure (`ShieldRules.reacted`). So an arrow from a shooter nobody noticed normally lands first (at 15 blocks the reaction
  is 0.85 s, the flight half a second), while a slow ghast fireball seen far away can still be blocked. A projectile from behind that
  nobody heard or saw just hits. Perception off (`behaviour.perception.enabled=false`): no reaction time, as before.
* **A drawing shooter**: a noticed hostile with a bow drawn or a crossbow charging or loaded (weapon-neutral), its head aimed at the
  bot. The pre-emptive raise of a player in PvP, held until the shot lands or the draw stops. When that shooter is the combat target
  within striking reach, the combat task's melee rhythm owns the shield instead (below), so a shooter in melee range is struck between
  blocks rather than blocked for ever.
* **A registered vanilla guardian or elder guardian beam** locked on the bot (the beam a client draws from its first tick): the shield
  is held until the beam lets go; the bite is blocked from the front, the magic part still hurts.
* **A registered vanilla creeper** with a late, lit fuse (the creeper defence task has its own shield phase through the same raise), or
  a visibly observed registered vanilla primed TNT entity inside its real eight-block damage envelope. TNT first waits the same human visible-object reaction time,
  then uses its synced fuse and the same turn/hotbar/block-delay budget; the actual vanilla explosion source and `TNT_EXPLODES` rule
  decide whether there is anything to block. A hidden fuse is never read through a wall, and custom private saved explosion power is not
  guessed. A modded Java subclass is not evidence that it has vanilla fuse, radius, or beam mechanics, so those specialised models
  decline it rather than guessing.
* **Melee**: the combat task's rhythm (below).

**Following, escorting, escaping and combat retreating keep the sprint (RULES win over the spec).** RULES say a follower or escort
always sprints while hostiles are aggroed, a bot sprints away from a hunting warden, and `CombatRegroupTask` plus `CombatTask.RETREAT`
are fighting retreats. `FollowTask`, `EvadeTask`, `CombatRegroupTask` and a retreating `CombatTask` block only projectiles ALREADY IN
FLIGHT at them (a hold of a few ticks: raised, blocked, lowered, and sprint resumes); none makes a pre-emptive hold against a drawing or
loaded shooter, a charging guardian beam or a fuse (the danger watcher takes a follower off the follow for a creeper). A retreat's
occasional clearance counterstrike likewise does not create a between-swings shield rhythm, because preserving that sprint is the
explicit rule.

A drawing shooter or a guardian is looked for exactly as far as the bot can notice a creature at all, its profile observation radius
(`perception.radius`): no distance limit of the shield's own.

## Timing

A raise is started only when it can be active before the hit: the turn into the front arc (at the human aim speed), the hotbar change
(one tick) and the block delay run together (`ShieldRules.canBeActiveInTime`, with one tick of slack). When it cannot, the hit is
taken: there is no dodge in the game today, and a raise that comes too late would only slow the bot down.

The head turns only as far as the arc needs (the arc less a 20 degree margin). A follower whose source is already in front keeps
looking where it walks; while a walker steers the head (a route, a walk, a step) a source outside the arc cannot be turned to, so no
raise is made for it (a player walking forward blocks what comes from the front half; turning round to walk backwards is not modelled).

## Melee rhythm (CombatTask and GuardTask)

Right after its own swing the bot raises the shield and holds it through the attack cooldown, standing its ground and facing the
target at the human turn speed; when the weapon is ready AND the target is under the crosshair within vanilla reach the shield comes
down, and the swing follows on the next tick (`ShieldRules.meleeStep`; a player cannot attack while using an item, and the client drops
the attack click of the tick the use key is released). A raise is skipped when it could not be active before the next swing. An axe hit
or a warden disables the shield through vanilla's item cooldown (`Weapon.disableBlockingForSeconds`, five seconds for an axe): no raise
and no attempt until it is over (`ShieldGuard.raise` returns before calling the use path), the fight goes on. Companions never fight
wardens, and the sonic boom is not blockable anyway. Combat and guard share this rhythm; hunt's fixed prey set contains only
non-attacking animals, so it never raises a shield against an animal that cannot make a melee hit. A follower in follow mode only
knocks back what reaches it while it keeps sprinting after its player (R4), so it does not stop to block melee.

## The use path and the hands

* `ShieldGuard.raise` tries MAIN_HAND and then OFF_HAND through `gameMode.useItem`, as the client does. A main-hand item that would
  take the use (a bow with ammunition, a loaded or loadable crossbow, food the bot can eat, a usable trident, a spear, armour to swap, a
  throwable, a bucket, ...; each case mirrors the item's own `use`) makes the bot change its hotbar first to its melee weapon, another
  plain item or an empty slot, on the HOTBAR only (slots 0-8, one key press; nothing is fetched from the backpack): one tick, and
  vanilla's own attack-strength reset of a changed main-hand item.
* While a shield is up: no mining (`ActionPack.tickBreak` waits), no attacking (`InteractAction.attackEntity` refuses `hands_busy`
  while any item is in use), `ActionPack.stopAll` does not drop the reactive owner's shield, and every movement is slowed like a
  player's: controller-driven keys by the pace enforcer (`PaceRules`, the vanilla 0.2 item-use factor, no sprint), the combat and guard
  tasks' raw strafe keys by the same factor. So a follower keeps following and a fighter keeps approaching or strafing, only slower.
* What a block may interrupt (`ShieldRules.mayInterrupt`): nothing in use or the shield itself, yes. **Eating in progress, a drawn
  bow or crossbow and any other use are finished, not cancelled, for a hit that only hurts; they are cancelled only for a hit that
  would be lethal** (a sensible player keeps chewing at low health because the food is the heal). An eating pass (`EatTask`) or the
  combat task's heal counts as eating between two bites too; while a lethal hit has the shield up, the eating pass waits (no budget
  spent, its watchdog never cancels the shield) and a bite never starts (`EatAction` refuses `hands_busy`).
* No block is placed and no item used on a block while the shield is up. `BuildAction` reports that as a temporary
  `ActionResult.IN_PROGRESS`, not a placement failure: stateful callers keep the same physical target, retry it after the shield
  lowers, and pause their own retry/watchdog clocks meanwhile. The client drops every other use click while the use key is down.
  An attack command (`AttackEntityTask`) waits for the hands like it waits for the aim.
* A shield a task raised for itself (the combat BLOCK phase, guard's STRIKE phase, or the creeper-defence SHIELD phase) is that task's
  only while it is in that phase: whatever ended, paused or replaced the task (a combat timeout in BLOCK included), the guard lowers it
  on its next tick, so a resumed follower or miner is never left at the item-use pace or unable to break.
* **A ranged exchange** of the combat task (its bow or crossbow out, drawn or loaded) keeps shooting: its arrow took the offhand (the
  shield went into the arrow's slot), a block would need the inventory, which no player opens mid-exchange, and the answer to a shooter
  is the return shot. The emergency tasks that build (shelter, barricade, creeper defence, lava, fire, powder snow) own the hands too.

The offhand content is the equipment rule's (`action/OffhandPolicy`): the best carried item with vanilla's `BLOCKS_ATTACKS` component
goes into an empty offhand or in place of a totem held only for want of one; any other offhand item (the arrow of a ranged loadout, a
torch) stays, and then there is no shield to raise. This is a narrow post-`du` reconciliation, not a second offhand policy: it keeps
`du`'s selection/ownership rules but classifies a shield by the vanilla component rather than the `Items.SHIELD` item identity, so a
modded blockable item follows the same vanilla combat path.
