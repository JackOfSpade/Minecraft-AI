package dev.spawnbotswrapper.inhabitants.engine;

/** Scriptable stand-in for {@link TpsGateway}. Defaults to "not enough samples yet" (-1), like a fresh server. */
final class FakeTps implements TpsGateway {
    Double millis;

    @Override
    public double averageTickMillis() {
        return millis == null ? -1 : millis;
    }
}
