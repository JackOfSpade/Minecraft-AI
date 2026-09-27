package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.structure.StructureKey;

/**
 * One bot the engine has asked the bot side to create and is now waiting for. Only its start tick is kept
 * (not a deadline), so the appear timeout follows the current config.
 */
record SpawnJob(StructureKey structure, int botIndex, String name, BotGateway.SpawnHandle handle, long startedAtTick) {
}
