package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.command.CommandServices;
import dev.spawnbotswrapper.inhabitants.engine.PopulationView;
import dev.spawnbotswrapper.inhabitants.store.BotRecord;
import dev.spawnbotswrapper.inhabitants.store.StructureRecord;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class GameMessageFilterTest {

    /** A fixed set of "known bot" names; everything else is a stranger. */
    private static CommandServices servicesKnowing(String... botNames) {
        List<String> known = List.of(botNames);
        PopulationView population = new PopulationView() {
            @Override
            public Optional<StructureRecord> find(StructureKey key) {
                return Optional.empty();
            }

            @Override
            public List<Map.Entry<StructureKey, StructureRecord>> nearby(String dimensionId, int chunkX, int chunkZ, int radiusChunks) {
                return List.of();
            }

            @Override
            public Optional<BotLocation> findBot(String botName) {
                return known.stream().anyMatch(n -> n.equalsIgnoreCase(botName))
                        ? Optional.of(new BotLocation(null, new BotRecord(0, botName, 1)))
                        : Optional.empty();
            }

            @Override
            public PopulationCounts counts() {
                return new PopulationCounts(0, 0, 0, 0, 0, 0);
            }
        };
        return new CommandServices(null, population, null, null, null, null, "test");
    }

    @Test
    void aJoinMessageFromAKnownInhabitantIsSuppressed() {
        CommandServices services = servicesKnowing("Inh_DuskRaven");
        assertTrue(GameMessageFilter.isFromOurBot(services, "Inh_DuskRaven joined the game"));
    }

    @Test
    void aLeaveMessageFromAKnownInhabitantIsSuppressed() {
        CommandServices services = servicesKnowing("Inh_DuskRaven");
        assertTrue(GameMessageFilter.isFromOurBot(services, "Inh_DuskRaven left the game"));
    }

    @Test
    void anAdvancementLineFromAKnownInhabitantIsSuppressed() {
        CommandServices services = servicesKnowing("Inh_DuskRaven");
        assertTrue(GameMessageFilter.isFromOurBot(services, "Inh_DuskRaven has made the advancement [Stone Age]"));
    }

    @Test
    void lookupIsCaseInsensitiveLikeTheUnderlyingRegistry() {
        CommandServices services = servicesKnowing("Inh_DuskRaven");
        assertTrue(GameMessageFilter.isFromOurBot(services, "inh_duskraven joined the game"));
    }

    @Test
    void aRealPlayerOrACompanionBotIsNeverSuppressed() {
        CommandServices services = servicesKnowing("Inh_DuskRaven");
        assertFalse(GameMessageFilter.isFromOurBot(services, "JackNotInTheBox joined the game"));
        assertFalse(GameMessageFilter.isFromOurBot(services, "Moss has made the advancement [Stone Age]"));
    }

    @Test
    void nothingIsSuppressedBeforeTheSessionHasInitialised() {
        assertFalse(GameMessageFilter.isFromOurBot(null, "Inh_DuskRaven joined the game"));
    }

    @Test
    void emptyOrBlankMessagesAreNeverSuppressed() {
        CommandServices services = servicesKnowing("Inh_DuskRaven");
        assertFalse(GameMessageFilter.isFromOurBot(services, ""));
        assertFalse(GameMessageFilter.isFromOurBot(services, " leading space"));
    }

    @Test
    void aSingleWordMessageWithNoTrailingTextIsStillCheckedWhole() {
        CommandServices services = servicesKnowing("Inh_DuskRaven");
        assertTrue(GameMessageFilter.isFromOurBot(services, "Inh_DuskRaven"));
        assertFalse(GameMessageFilter.isFromOurBot(services, "SomeoneElse"));
    }
}
