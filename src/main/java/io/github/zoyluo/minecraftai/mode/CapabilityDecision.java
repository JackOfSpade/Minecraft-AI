package io.github.zoyluo.minecraftai.mode;

/** Structured audit seam emitted before any privileged operation can run. */
public record CapabilityDecision(
        OperatingProfile profile,
        PrivilegedCapability capability,
        boolean allowed,
        Reason reason
) {
    public enum Reason {
        ALLOWED_OPERATOR_FLAG,
        /**
         * The capability used to grant knowledge a survival player could not have.  Its JSON
         * field is still parsed so an existing profile loads safely, but it can no longer run.
         */
        DENIED_RETIRED_CAPABILITY,
        DENIED_STRICT_SURVIVAL,
        DENIED_OPERATOR_FLAG,
        DENIED_MISSING_CONFIGURATION
    }
}
