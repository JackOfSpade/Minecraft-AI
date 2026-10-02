package io.github.zoyluo.minecraftai.navigation;

/**
 * The physical controller that owned a bot during an opt-in navigation measurement tick.
 *
 * <p>This deliberately is not {@link NavEngine}: {@link #LOCAL_ACTION} means a bounded,
 * player-like action such as directly mining an observed block, taking a safety step, or holding
 * local input. It is evidence that a Baritone-only measurement was not controller-isolated, not a
 * retired navigation engine or a runtime fallback.</p>
 */
public enum NavigationControllerOwner {
    BARITONE,
    LOCAL_ACTION
}
