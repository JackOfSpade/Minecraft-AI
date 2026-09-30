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
    "forcedPickup": false,
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
| `forcedPickup` | Deny | Allow | Deny |
| `manualTeleport` | Deny | Allow | Deny |

The four `operator` defaults are `true`, to preserve legacy behavior; they are four independent switches, not one master switch. For example, to allow only manual teleport:

```json
{
  "profile": "operator",
  "operatorCapabilities": {
    "hiddenBlockScan": false,
    "emergencyTeleport": false,
    "forcedPickup": false,
    "manualTeleport": true
  }
}
```

### Meaning of the Four Capabilities

- `hiddenBlockScan`: Allows bypassing strict's observability filtering to probe for resources. In strict mode, blocks must be within the configured radius, exposed, and hit by a line-of-sight raycast; entities must be within radius and visible.
- `emergencyTeleport`: Allows hazard handling or navigation fallback to perform a long-distance emergency teleport. In strict mode, the relevant code paths instead attempt normal actions and fail explicitly when they cannot be handled safely.
- `forcedPickup`: Allows directly transferring nearby dropped items into the bot's inventory. Strict mode only uses the normal world pickup process.
- `manualTeleport`: Allows initiating a manual teleport via the control panel/network action. When not in effect, the UI button is disabled, and the server still rejects the request again on its own.

## Strict Survival Semantics

`strict_survival` is not just about hiding UI buttons. The server-side capability gate re-checks before the action occurs:

- Resource and entity scans are first filtered by proximity, exposure, and line of sight;
- Emergency teleport, manual teleport, and forced pickup are prohibited;
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
| `behaviour.pace.itemUseSlowdown` | `true` | Movement scale 0.2 while a bot uses an item (eating, drawing a bow, blocking), as vanilla applies on the client. |
| `behaviour.pace.movementExhaustion` | `true` | Moving costs hunger like a player's (`checkMovementStatistics`), and hunger stops sprinting at the vanilla threshold. |
| `behaviour.pace.routeSprintDistance` | `8.0` | A route sprints while its goal is at least this far away (blocks). |
| `behaviour.pace.routeWalkDistance` | `4.5` | A route walks once its goal is this close or closer; in between the pace is kept. |
| `behaviour.pace.quietZoneCaution` | `true` | Cap the pace in quiet zones (sculk, an ancient city) so a bot does not wake a warden. |
| `behaviour.targeting.hostileBots` | `true` | A foreign bot (a fake player that is not one of ours, e.g. a PvP bot) that attacks the owner or a Minecraft-AI bot becomes a target. The owner and every Minecraft-AI bot are never targets. |
| `behaviour.targeting.ownerVision` | `true` | What the owner can see may nominate a hostile bot as a target (the bot still needs its own reach and line of sight to strike). |
| `behaviour.targeting.aggressorMemoryTicks` | `600` | How long a hostile bot stays marked after its last hostile act (level game ticks). |
| `behaviour.targeting.ownerVisionRange` | `48` | How far (blocks) the owner's sight nominates a target. |
| `behaviour.targeting.ownerViewConeDot` | `0.5` | Minimum dot product of the owner's look direction and the direction to the target for it to count as seen; in (0, 1]. |
| `behaviour.gear.worstFirst` | `true` | Tools, weapons and armour are always the cheapest item that can still do the job (enchantments add value). No escalation of any kind; take an item out of the bot's inventory to control what it uses. |
| `behaviour.follow.escortOnly` | `true` | A following bot only knocks back what is within melee range and keeps following; it does not stop to fight. |
| `behaviour.follow.walkGap` | `6.0` | The follower walks when the gap to the followed player is at or below this (blocks). |
| `behaviour.follow.sprintGap` | `10.0` | The follower sprints from this gap on. |
| `behaviour.warden.sneakAway` | `true` | Wardens are never fought. A bot sneaks away from a calm one and sprints away from one that hunts it. |

These switches only exist as configuration from this version on; the behaviours they name are switched on by the jobs that implement them, and until then a switch has no effect.

Two records support these switches and are not configuration: `RecentDamage` (who hurt whom, in level game time, from Fabric's `AFTER_DAMAGE`) and `TeleportAudit`. `TeleportAudit` classifies every bot teleport as `LIFECYCLE` (spawn, respawn), `USER` (the panel's recall), `VANILLA` (portals, pearls), `PRIVILEGED` (the operator emergency rescues, which are capability-gated and refused in `strict_survival`), `TEST` (a GameTest fixture move) or `CORRECTION` (anything else: a path or position correction), logs it as `bot_teleport kind=... caller=Class#method dist=...` and keeps per-bot counters.

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

operator can also take a comma-separated subset, for example `--operator-capabilities manualTeleport`; `none` means all four are disabled. strict does not accept a parameter that enables an operator capability.

The strict/operator local diagnostics retained in the current working tree are both `7/7 PASS`, but because the working tree was not clean at runtime, the bundle is correctly marked `UNVERIFIED`. This result demonstrates that the harness and policy passed under this local state; it is not equivalent to a capability certification of a published commit.
