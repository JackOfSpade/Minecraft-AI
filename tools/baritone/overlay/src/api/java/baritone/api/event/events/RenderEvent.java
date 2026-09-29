package baritone.api.event.events;

/**
 * Stub for the excluded client-only {@code RenderEvent} (it carried a PoseStack and a projection matrix).
 * A server never renders, so the event only exists to keep the listener interface intact.
 */
public final class RenderEvent {

    private final float partialTicks;

    public RenderEvent(float partialTicks) {
        this.partialTicks = partialTicks;
    }

    public final float getPartialTicks() {
        return this.partialTicks;
    }
}
