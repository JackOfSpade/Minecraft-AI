package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.structure.StructureKey;

/**
 * What {@link PopulationDriver} asks of the allocation: may this structure start another bot now, and who goes first.
 * While the allocation is not running (switched off, or no real player known) it answers "anything goes" and the driver
 * spawns first come, first served exactly as before nearest-first population existed.
 */
interface AllocationView {
    /** Nothing limits and nothing orders: the pre-allocation behaviour. */
    AllocationView INACTIVE = new AllocationView() {
        @Override
        public boolean active() {
            return false;
        }

        @Override
        public java.util.List<StructureKey> allowedInOrder() {
            return java.util.List.of();
        }

        @Override
        public int allowed(StructureKey key) {
            return Integer.MAX_VALUE;
        }

    };

    boolean active();

    /** The structures that may start bots now (allowed > 0), nearest first. Empty while not active. */
    java.util.List<StructureKey> allowedInOrder();

    /** The most live inhabitants (with spawns and wakes in flight) the structure may have now; 0 for one outside the allocation. */
    int allowed(StructureKey key);
}
