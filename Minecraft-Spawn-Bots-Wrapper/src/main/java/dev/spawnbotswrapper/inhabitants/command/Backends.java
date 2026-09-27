package dev.spawnbotswrapper.inhabitants.command;

import dev.spawnbotswrapper.inhabitants.catalog.SettingCatalog;
import dev.spawnbotswrapper.inhabitants.profile.BotProfile;
import dev.spawnbotswrapper.inhabitants.profile.GlobalCapabilities;
import dev.spawnbotswrapper.inhabitants.profile.ProfileFormatter;
import net.minecraft.server.command.ServerCommandSource;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * The few things the command layer takes from outside the services - the setting catalog, the profile
 * formatter and "where was this command run" - behind seams so the whole tree can be exercised with fakes
 * and no server. Production always uses {@link #standard()}.
 *
 * @param senders resolves the world and position a command was run from (needs a real world in production)
 */
record Backends(CatalogSource catalog, ProfileRenderer profiles, Function<ServerCommandSource, Sender> senders) {

    static Backends standard() {
        return new Backends(
                new CatalogSource() {
                    @Override
                    public List<SettingCatalog.SettingSpec> all() {
                        return SettingCatalog.all();
                    }

                    @Override
                    public String auditedVersion() {
                        return SettingCatalog.auditedVersion();
                    }

                    @Override
                    public SettingCatalog.AuditReport audit(Set<String> upstreamFieldNames) {
                        return SettingCatalog.audit(upstreamFieldNames);
                    }
                },
                ProfileFormatter::format,
                Sender::of);
    }

    /** Read access to the setting catalog. */
    interface CatalogSource {
        List<SettingCatalog.SettingSpec> all();

        String auditedVersion();

        SettingCatalog.AuditReport audit(Set<String> upstreamFieldNames);
    }

    /** Renders a profile as text lines. */
    @FunctionalInterface
    interface ProfileRenderer {
        List<String> render(BotProfile profile, GlobalCapabilities capabilities);
    }
}
