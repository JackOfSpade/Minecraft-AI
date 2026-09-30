# Minecraft-AI Operating Profiles

Minecraft-AI has two operating profiles: the default `strict_survival` and the compatibility-oriented `operator`. The profile controls the privilege boundary; it does not change the basic Goal/Task architecture.

## Configuration

We recommend explicitly writing the profile in `minecraftai.json`:

```json
{
  "profile": "strict_survival",
  "operatorCapabilities": {
    "hiddenBlockScan": false,
    "emergencyTeleport": false,
    "manualTeleport": false
  }
}
```

The file can also be overridden for the current process using an environment variable:

```bash
MINECRAFTAI_PROFILE=strict_survival ./gradlew runServer
```

The only valid values are `strict_survival` and `operator`. Configuration is parsed at startup; the server should be restarted after changing the file or the environment variable.

## Resolution and Migration Rules

| Input State | Final Profile | Behavior |
|---|---|---|
| Fresh install, no config file yet | `strict_survival` | Writes out a default config with an explicit strict profile. |
| Existing file, valid profile | File value | Loads normally. |
| Existing legacy file, `profile` field entirely missing | `operator` | For backward compatibility only, and logs an `operating_profile_legacy_compatibility` warning. Should be migrated explicitly as soon as possible. |
| Profile type or value in the file is invalid | `strict_survival` | Fail closed, and logs `operating_profile_invalid`. |
| `MINECRAFTAI_PROFILE` is valid | Environment variable value | Overrides the file or the legacy resolution result. |
| `MINECRAFTAI_PROFILE` is non-empty but invalid | `strict_survival` | Fail closed; does not fall back to the file's `operator`, and logs `operating_profile_environment_invalid`. |
| Configuration cannot be parsed | `strict_survival`, or a valid environment override value | Logs a configuration read error; a parse failure must never expand privileges. |

"Old configs missing the field fall back to operator for compatibility" applies only to files that **already exist, are parseable, and genuinely lack a `profile` field**. `null`, wrong types, and unknown strings do not count as legacy-missing and must not receive operator privileges.

## Capability matrix

| Capability | `strict_survival` | `operator` default | `operator` explicitly disabled |
|---|---:|---:|---:|
| `hiddenBlockScan` | Deny | Allow | Deny |
| `emergencyTeleport` | Deny | Allow | Deny |
| `manualTeleport` | Deny | Allow | Deny |

The three `operator` defaults are `true`, to preserve legacy behavior; they are three independent switches, not one master switch. For example, to allow only manual teleport:

```json
{
  "profile": "operator",
  "operatorCapabilities": {
    "hiddenBlockScan": false,
    "emergencyTeleport": false,
    "manualTeleport": true
  }
}
```

### Meaning of the Three Capabilities

- `hiddenBlockScan`: Allows bypassing strict's observability filtering to probe for resources. In strict mode, blocks must be within the configured radius, exposed, and hit by a line-of-sight raycast; entities must be within radius and visible.
- `emergencyTeleport`: Allows hazard handling (the suffocation climb, the drowning rescue, dark-trap surfacing and gather surfacing) to perform a long-distance emergency teleport. In strict mode, the relevant code paths instead attempt normal actions and fail explicitly when they cannot be handled safely; the capability decision is made first, so nothing is scanned that the bot cannot see. No profile teleports a bot to correct its path: a route start that is not standable is left by a walked step (`WalkedStep`), a stalled hop or pillar jump is retried with inputs, and a bot inside a block is shoved, walked or dug out (`NavSafetyNet.escapeSuffocationByInputs`).
- `manualTeleport`: Allows initiating a manual teleport via the control panel/network action. When not in effect, the UI button is disabled, and the server still rejects the request again on its own.

## Strict Survival Semantics

`strict_survival` is not just about hiding UI buttons. The server-side capability gate re-checks before the action occurs:

- Resource and entity scans are first filtered by proximity, exposure, and line of sight;
- Emergency teleport and manual teleport are prohibited;
- Death recovery follows the lifecycle and respawns at the world spawn point, without using teleport capability to fake an in-place respawn;
- Survival constraints are not bypassed via a forced time-skip or remote world mutation.

The server's own bot creation, persistence recovery, and normal death lifecycle are not player-invocable operator capabilities; they belong to the static lifecycle boundary.

## Mining Assist and Profiles

Mining Assist ([MINING_ASSIST.md](MINING_ASSIST.md)) does not add any new operator capability and does not relax any profile. Its line-of-sight ray (`castViewRay`) only casts a first-hit ray from the bot's own eyes, with a length not exceeding the perception radius, and it never calls `CapabilityRuntime.decide`, so it behaves identically under both profiles. The post-mining neighbor-cell peek and entity evidence reuse the existing `OreScan.observe` / `canObserveEntity` observation proofs, whose results follow the current profile's capability decision, the same as other mining tasks (under `operator`, if `hiddenBlockScan` is enabled, these observations are relaxed accordingly too). It does not use structure queries, teleport, or forced pickup.

The default mode is still `sense` (it only records shadow logs and does not change any bot behavior); the switch to `detour` will happen only once GameTest and the four-bot cost gate are passing green, which is the orchestrator's final step and is not part of this repo's current change. Disable it with `MINECRAFTAI_MINING_ASSIST=off` or `miningAssist.mode`; GameTest, verify, and evidence runs default to disabled (scripts other than `assist_mine_lane` are always pinned to `off`). Like the profile, it is resolved at startup, and the server should be restarted after changing it. P1's opportunistic detour (`detour`/`all` modes) likewise adds no capability and relaxes no profile — it only uses the existing `OreScan.observe`/`ObservableWorldQuery` observation proofs and the existing waypoint pathfinding, with no teleport, no forced pickup, and no hidden scanning; behavior is identical under both profiles.

## Behaviour switches

The `behaviour` section of `minecraftai.json` holds the companion behaviour switches. Both operating profiles use the same defaults; the section is optional, and a missing section, key or Boolean is the default. A number that is missing, zero, negative or NaN is the default too. A pair of gaps that is inverted or equal (`routeWalkDistance >= routeSprintDistance`, `walkGap >= sprintGap`) falls back to the defaults of both. The values are read at startup, like the profile.

| Key | Default | Meaning |
| --- | --- | --- |
| `behaviour.pace.enabled` | `true` | The natural sprint/walk/sneak policy for controller-driven travel (a path or route walk). Off: the earlier always-as-asked behaviour. |
| `behaviour.pace.routeSprintDistance` | `8.0` | A route sprints while its goal is at least this far away (blocks). |
| `behaviour.pace.routeWalkDistance` | `4.5` | A route walks once its goal is this close or closer; in between the pace is kept. |
| `behaviour.pace.quietZoneCaution` | `true` | Cap the pace in quiet zones (sculk, an ancient city) so a bot does not wake a warden. |
| `behaviour.targeting.hostileBots` | `true` | A foreign bot (a fake player that is not one of ours, e.g. a PvP bot) that attacks the owner or a Minecraft-AI bot becomes a target. The owner and every Minecraft-AI bot are never targets. |
| `behaviour.targeting.ownerVision` | `true` | What the owner can see may nominate a hostile bot as a target (the bot still needs its own reach and line of sight to strike). |
| `behaviour.targeting.aggressorMemoryTicks` | `600` | How long a hostile bot stays marked after its last hostile act (level game ticks). |
| `behaviour.targeting.ownerVisionRange` | `48` | How far (blocks) the owner's sight nominates a target. |
| `behaviour.targeting.ownerViewConeDot` | `0.5` | Minimum dot product of the owner's look direction and the direction to the target for it to count as seen; in (0, 1]. |
| `behaviour.gear.worstFirst` | `true` | TOOLS (pickaxe, shovel, hoe, shears, fishing rod, an axe that chops) are the cheapest one that can still do the job (enchantments add value), used until it breaks, then the next worst. NON-TOOLS (melee weapons, bows, crossbows, shields, armour, elytra) are always best-first and the next best takes over the moment one breaks; that has no switch. The same switch also keeps plain arrows before tipped and spectral ones (ammunition, not gear). Wear never sets an item aside; only a tool at its LAST use with no other tool of its kind in the pack starts a replacement (craft or fetch) ahead of the break. The offhand is the best shield, else a totem of undying (the next of the same kind when one breaks or pops; any other offhand item is left alone); a held shield is never swapped for another. No escalation of any kind; take an item out of the bot's inventory to control what it uses. |
| `behaviour.gear.durabilityWarnings.enabled` | `true` | The bot says in chat, once per item and crossing and at once, when a diamond or netherite item, a shield, bow, crossbow, trident, mace, elytra or fishing rod drops below the threshold of its durability. Chat only: nothing is interrupted or re-equipped. |
| `behaviour.gear.durabilityWarnings.thresholdPercent` | `10.0` | The threshold, in percent of the maximum durability (re-armed when the item is repaired back to it). |
| `behaviour.follow.escortOnly` | `true` | A following bot only knocks back what is within melee range and keeps following; it does not stop to fight. |
| `behaviour.follow.walkGap` | `6.0` | The follower walks when the gap to the followed player is at or below this (blocks). |
| `behaviour.follow.sprintGap` | `10.0` | The follower sprints from this gap on. |
| `behaviour.warden.sneakAway` | `true` | Wardens are never fought. A bot sneaks away from a calm one and sprints away from one that hunts it. |
| `behaviour.combat.aim.maxTurnDegPerSec` | `540.0` | Human aim: the fastest a companion's head turns (degrees per second) while it aims a weapon or strikes; no instant spin-and-shoot. A shot leaves only when the aim is within 1.5 degrees of the shot direction (with a settle jitter that fades: sigma = 0.3 deg + 2.5 deg * exp(-t/0.25 s) after a fast turn), and a melee strike lands only on the entity under the crosshair (vanilla's pick along the real look vector within the weapon's vanilla attack range). Walking, mining and placing keep their own facing. |
| `behaviour.perception.enabled` | `true` | Realistic noticing of creatures (docs/PERCEPTION.md): a companion notices a mob, player or bot only when it SEES it (inside the view cone of its real look vector, a clear line, within `perception.radius`) for the reaction time, or hears it (vanilla vibrations) and sees it, or is struck by it. Nobody is noticed from behind while silent. Off: the earlier omnidirectional line of sight exactly (no listener is registered). Items, boats and deliberate searches for animals and villagers are always omnidirectional. |
| `behaviour.perception.reactionBaseSeconds` | `0.5` | The reaction time up close (seconds). Every distance has its own value: `(base + (at64 - base) * distance / 64) * angleFactor * sneakFactor / visibility`. |
| `behaviour.perception.reactionAt64Seconds` | `2.0` | The reaction time at 64 blocks (linear in between; 10 blocks is 0.73 s). Must not be below the base. |
| `behaviour.perception.fullAttentionHalfAngleDeg` | `30` | Inside this angle off the look direction the reaction time is not lengthened. 0..180, not above the peripheral angle. |
| `behaviour.perception.peripheralHalfAngleDeg` | `100` | The edge of the view field (a 200 degree field); beyond it (behind) nothing is seen, only heard. |
| `behaviour.perception.peripheralMultiplier` | `2.0` | The reaction time factor at the peripheral edge (linear from 1 at the full attention angle); 1..20. |
| `behaviour.perception.sneakMultiplier` | `2.0` | How much longer a sneaking creature takes to notice; 1..20. |
| `behaviour.perception.hearing.listenerRadius` | `16` | The vanilla vibration radius in blocks (the Warden's; a sculk sensor's is 8). Vanilla decides sneaking (silent), wool (blocks vibrations) and travel time. |

These switches only exist as configuration from this version on; the behaviours they name are switched on by the jobs that implement them, and until then a switch has no effect.

Two records support these switches and are not configuration: `RecentDamage` (who hurt whom, in level game time, from Fabric's `AFTER_DAMAGE`) and `TeleportAudit`. `TeleportAudit` classifies every bot teleport as `LIFECYCLE` (spawn, respawn), `USER` (the panel's recall), `VANILLA` (portals, pearls), `PRIVILEGED` (the operator emergency rescues, which are capability-gated and refused in `strict_survival`), `TEST` (a GameTest fixture move) or `CORRECTION` (anything else: a path or position correction), logs it as `bot_teleport kind=... caller=Class#method dist=...` and keeps per-bot counters.

### Removed switches

A bot is bound by the same rules as a survival player, so the switches that could turn those rules off are gone. A key of an older `minecraftai.json` that names one of them is ignored, and the startup log carries one `config_removed_keys_ignored` line listing the keys found:

- `behaviour.pace.itemUseSlowdown` and `behaviour.pace.movementExhaustion`: the 0.2 movement scale while using an item and the hunger cost of moving are always applied.
- `operatorCapabilities.forcedPickup`, `pickup.forceRadiusH`, `pickup.forceRadiusV`: no profile can vacuum a drop into the inventory. A bot collects an item by walking onto it, inside vanilla's pickup range and after its pickup delay.

## Observability and Auditing

At startup, `operating_profile_resolved` is logged, containing the profile source, config switches, and effective capabilities; specific migration/invalid-config warnings are logged by adjacent, separate configuration events. At runtime, the capability gate emits throttled, structured `capability_decision` records stating the capability, profile, allow/deny, and reason.

In-game snapshots and the Bob control panel display `operatingProfile` and `effectiveCapabilities`. Do not judge permissions from the config file alone; when troubleshooting, treat the startup log and the final effective values shown in the UI as authoritative.

## Verification

The test-only harness covers both strict and operator:

```bash
bash scripts/evidence_run.sh \
  --scenario capability_profile+runtime_control_suite \
  --profile strict_survival

bash scripts/evidence_run.sh \
  --scenario capability_profile+runtime_control_suite \
  --profile operator \
  --operator-capabilities all
```

operator can also take a comma-separated subset, for example `--operator-capabilities manualTeleport`; `none` means all three are disabled. strict does not accept a parameter that enables an operator capability.

The strict/operator local diagnostics retained in the current working tree are both `7/7 PASS`, but because the working tree was not clean at runtime, the bundle is correctly marked `UNVERIFIED`. This result demonstrates that the harness and policy passed under this local state; it is not equivalent to a capability certification of a published commit.
