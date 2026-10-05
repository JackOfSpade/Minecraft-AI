package dev.spawnbotswrapper.inhabitants.mc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Source pins for the deliberately particleless, vanilla-parity inhabitant hearing tick. */
final class SilentVibrationTickerSourceContractTest {
    private static final Path MC = Path.of("src/main/java/dev/spawnbotswrapper/inhabitants/mc");

    @Test
    void inhabitantEarsKeepTheVanillaListenerButUseTheSilentTicker() throws IOException {
        String ears = Files.readString(MC.resolve("InhabitantEars.java"));

        assertTrue(ears.contains("new VibrationSystem.Listener(this)")
                        && ears.contains("new DynamicGameEventListener<>"),
                "InhabitantEars must keep vanilla event listening and selection");
        assertTrue(ears.contains("SilentVibrationTicker.tick(level, ear.data, ear.user)"),
                "inhabitant ears must tick the silent parity implementation");
        assertFalse(ears.contains("VibrationSystem.Ticker.tick(level, ear.data, ear.user)"),
                "the vanilla ticker emits a visible vibration particle for every scheduled sound");
    }

    @Test
    void silentTickerPreservesVanillaHearingStateTransitionsWithoutSendingParticles() throws IOException {
        String ticker = Files.readString(MC.resolve("SilentVibrationTicker.java"));

        assertFalse(ticker.contains("sendParticles("), "the hearing ticker must not produce a particle packet");
        assertTrue(ticker.contains("chosenCandidate(level.getGameTime())")
                        && ticker.contains("setCurrentVibration(vibration)")
                        && ticker.contains("calculateTravelTimeInTicks(vibration.distance())")
                        && ticker.contains("getSelectionStrategy().startOver()"),
                "candidate selection and scheduling stay vanilla-equivalent");
        assertTrue(ticker.contains("data.decrementTravelTime()")
                        && ticker.contains("requiresAdjacentChunksToBeTicking()")
                        && ticker.contains("getEntity(level).orElse(null)")
                        && ticker.contains("getProjectileOwner(level).orElse(null)")
                        && ticker.contains("distanceBetweenInBlocks(source, listener)")
                        && ticker.contains("data.setCurrentVibration(null)"),
                "the physical travel and delayed delivery path stays vanilla-equivalent");
        assertTrue(ticker.contains("user.onDataChanged()") && ticker.contains("data.setReloadVibrationParticle(false)"),
                "all data-change callbacks remain, while a restored particle reload is suppressed");
    }
}
