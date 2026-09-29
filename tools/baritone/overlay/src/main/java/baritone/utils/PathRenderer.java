package baritone.utils;

import baritone.api.event.events.RenderEvent;
import baritone.behavior.PathingBehavior;

/** Stub for the excluded client-only path renderer: there is nothing to draw on a server. */
public final class PathRenderer {

    private PathRenderer() {}

    public static void render(RenderEvent event, PathingBehavior behavior) {}
}
