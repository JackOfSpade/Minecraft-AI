package dev.spawnbotswrapper.inhabitants.command;

import dev.spawnbotswrapper.inhabitants.adapter.PvpBotOperations;
import dev.spawnbotswrapper.inhabitants.catalog.SettingCatalog;
import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import dev.spawnbotswrapper.inhabitants.engine.EngineControl.ProcessOutcome;
import dev.spawnbotswrapper.inhabitants.engine.ForceMode;
import dev.spawnbotswrapper.inhabitants.engine.PopulationView;
import dev.spawnbotswrapper.inhabitants.profile.GlobalCapabilities;
import dev.spawnbotswrapper.inhabitants.store.StructureRecord;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;
import dev.spawnbotswrapper.inhabitants.structure.StructureSnapshot;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What each subcommand does: gather data from the services, hand it to the pure formatters, and answer
 * through a {@link Reply}. There is no Brigadier and no chat formatting in here, and no state: an instance
 * is made per command run from the services that exist at that moment.
 * <p>
 * Every method returns the Brigadier result: 1 when it did something or showed something, 0 when there was
 * nothing to do or it failed. Failures the caller could act on are reported through {@link Reply#error};
 * exceptions are the caller's ({@link InhabitantsCommand}) last line of defence, not this class's control flow.
 */
final class CommandActions {
    private final CommandServices services;
    private final Backends backends;

    CommandActions(CommandServices services, Backends backends) {
        this.services = services;
        this.backends = backends;
    }

    // ---------------------------------------------------------------- read-only reports

    int info(Reply reply) {
        reply.lines(InfoFormatter.format(services.addonVersion(), services.adapter().status(), config(),
                services.engine().stats(), services.population().counts()));
        return 1;
    }

    int adapter(Reply reply) {
        PvpBotOperations.Status status = services.adapter().status();
        GlobalCapabilities caps = status != null && status.usable() ? readCapabilities() : null;
        reply.lines(AdapterFormatter.format(status, caps));
        return 1;
    }

    int structureHere(Sender at, Reply reply) {
        List<StructureSnapshot> structures = services.locator().at(at.world(), at.blockPos());
        List<StructureFormatter.Found> found = new ArrayList<>();
        for (StructureSnapshot s : structures) {
            found.add(new StructureFormatter.Found(s, services.population().find(s.key()).orElse(null)));
        }
        BlockPos pos = at.blockPos();
        reply.lines(StructureFormatter.here(at.dimensionId(), pos.getX(), pos.getY(), pos.getZ(), found, config()));
        return found.isEmpty() ? 0 : 1;
    }

    int nearby(Sender at, int requestedRadius, Reply reply) {
        int radius = CommandArgs.radius(requestedRadius);
        List<Map.Entry<StructureKey, StructureRecord>> entries =
                services.population().nearby(at.dimensionId(), at.chunkX(), at.chunkZ(), radius);
        reply.lines(StructureFormatter.nearby(radius, at.x(), at.z(), entries));
        return entries.isEmpty() ? 0 : 1;
    }

    int profile(String botName, Reply reply) {
        Optional<PopulationView.BotLocation> location = services.population().findBot(botName);
        if (location.isEmpty()) {
            reply.error(ProfileReport.unknownBot(botName));
            return 0;
        }
        PopulationView.BotLocation loc = location.get();
        GlobalCapabilities caps = readCapabilities();
        reply.lines(ProfileReport.format(loc, () -> backends.profiles().render(loc.bot().profile, caps)));
        return 1;
    }

    int catalog(String categoryWord, Reply reply) {
        if (categoryWord == null) {
            reply.lines(catalogSummary(backends.catalog().all()));
            return 1;
        }
        Optional<SettingCatalog.Category> category = CommandArgs.parseCategory(categoryWord);
        if (category.isEmpty()) {
            reply.error("Unknown category '" + Markup.esc(categoryWord) + "'. Categories: "
                    + CatalogFormatter.categoryNames().replace('|', ' '));
            return 0;
        }
        reply.lines(CatalogFormatter.category(category.get(), backends.catalog().all()));
        return 1;
    }

    private List<String> catalogSummary(List<SettingCatalog.SettingSpec> all) {
        PvpBotOperations.Status status = services.adapter().status();
        SettingCatalog.AuditReport audit = null;
        String skipped = null;
        if (status == null || !status.usable()) {
            skipped = "PvP BOT is unavailable";
        } else {
            Set<String> upstream = upstreamNames();
            if (upstream.isEmpty()) {
                skipped = "PvP BOT reported no settings";
            } else {
                audit = backends.catalog().audit(upstream);
            }
        }
        return CatalogFormatter.summary(backends.catalog().auditedVersion(), all,
                status == null ? null : status.pvpBotVersion(), audit, skipped);
    }

    // ---------------------------------------------------------------- state-changing

    int processNearest(Sender at, ForceMode mode, Reply reply) {
        List<StructureSnapshot> near = services.locator().near(at.world(), at.blockPos(), CommandArgs.PROCESS_RADIUS);
        StructureSnapshot target = null;
        for (StructureSnapshot s : near) {
            if (services.population().find(s.key()).isEmpty()) {
                target = s;
                break;
            }
        }
        if (target == null) {
            reply.lines(AdminFormatter.noProcessCandidate(CommandArgs.PROCESS_RADIUS, near.size()));
            return 0;
        }
        ProcessOutcome outcome = services.engine().process(target, mode);
        reply.lines(AdminFormatter.processed(target, mode, outcome));
        return AdminFormatter.processSucceeded(outcome) ? 1 : 0;
    }

    int resetHere(Sender at, boolean removeBots, Reply reply) {
        List<StructureSnapshot> structures = services.locator().at(at.world(), at.blockPos());
        if (structures.isEmpty()) {
            reply.error("No registered structure contains your position (only loaded chunks are searched).");
            return 0;
        }
        StructureKey target = null;
        int withRecords = 0;
        for (StructureSnapshot s : structures) {
            if (services.population().find(s.key()).isPresent()) {
                if (target == null) {
                    target = s.key();
                }
                withRecords++;
            }
        }
        if (target == null) {
            List<String> ids = new ArrayList<>();
            for (StructureSnapshot s : structures) {
                ids.add(s.key().structureId());
            }
            reply.lines(List.of(Markup.warn("Nothing to reset: ") + Markup.plain(String.join(", ", ids))
                    + Markup.label(" here " + (structures.size() == 1 ? "has" : "have") + " no record yet.")));
            return 0;
        }
        List<String> extra = new ArrayList<>();
        if (withRecords > 1) {
            extra.add(Markup.label("  " + (withRecords - 1) + " more overlapping structure(s) here have records too: "
                    + "the smallest was reset; use /inhabitants reset structure for the others."));
        }
        return reset(target, removeBots, reply, extra);
    }

    int resetNearest(Sender at, boolean removeBots, Reply reply) {
        List<Map.Entry<StructureKey, StructureRecord>> near =
                services.population().nearby(at.dimensionId(), at.chunkX(), at.chunkZ(), CommandArgs.RESET_RADIUS);
        if (near.isEmpty()) {
            reply.error("No processed structure within " + CommandArgs.RESET_RADIUS + " chunks. See /inhabitants "
                    + "nearby, or reset one by identity with /inhabitants reset structure.");
            return 0;
        }
        return reset(near.get(0).getKey(), removeBots, reply, List.of());
    }

    int resetStructure(Sender at, String structureId, int chunkX, int chunkZ, boolean removeBots, Reply reply) {
        return reset(new StructureKey(at.dimensionId(), structureId, chunkX, chunkZ), removeBots, reply, List.of());
    }

    private int reset(StructureKey key, boolean removeBots, Reply reply, List<String> extraLines) {
        AdminFormatter.Before before = AdminFormatter.Before.of(services.population().find(key).orElse(null));
        if (!services.engine().reset(key, removeBots)) {
            reply.lines(AdminFormatter.resetNothing(key));
            return 0;
        }
        InhabitantsConfig cfg = config();
        List<String> lines = new ArrayList<>(AdminFormatter.resetDone(key, before, removeBots,
                cfg.deterministic != null && cfg.deterministic.enabled));
        lines.addAll(extraLines);
        reply.lines(lines);
        return 1;
    }

    int reload(Reply reply) {
        reply.lines(AdminFormatter.reloaded(services.reloadConfig().get()));
        return 1;
    }

    // ---------------------------------------------------------------- helpers

    private InhabitantsConfig config() {
        InhabitantsConfig c = services.config().get();
        return c != null ? c : new InhabitantsConfig();
    }

    /** The reflective reads may fail in odd PvP BOT versions; fall back to upstream's defaults, never throw. */
    private GlobalCapabilities readCapabilities() {
        try {
            GlobalCapabilities c = services.adapter().readCapabilities();
            return c != null ? c : GlobalCapabilities.upstreamDefaults();
        } catch (RuntimeException | LinkageError e) {
            return GlobalCapabilities.upstreamDefaults();
        }
    }

    private Set<String> upstreamNames() {
        try {
            Set<String> names = services.adapter().discoverUpstreamSettingNames();
            return names != null ? names : Set.of();
        } catch (RuntimeException | LinkageError e) {
            return Set.of();
        }
    }
}
