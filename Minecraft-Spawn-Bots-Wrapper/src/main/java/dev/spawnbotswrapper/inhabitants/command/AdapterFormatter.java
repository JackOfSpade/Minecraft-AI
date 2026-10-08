package dev.spawnbotswrapper.inhabitants.command;

import dev.spawnbotswrapper.inhabitants.adapter.PvpBotOperations;
import dev.spawnbotswrapper.inhabitants.profile.GlobalCapabilities;

import java.util.ArrayList;
import java.util.List;

import static dev.spawnbotswrapper.inhabitants.command.Markup.bad;
import static dev.spawnbotswrapper.inhabitants.command.Markup.good;
import static dev.spawnbotswrapper.inhabitants.command.Markup.label;
import static dev.spawnbotswrapper.inhabitants.command.Markup.plain;
import static dev.spawnbotswrapper.inhabitants.command.Markup.title;
import static dev.spawnbotswrapper.inhabitants.command.Markup.warn;

/**
 * Output of {@code /inhabitants adapter}: the complete PvP BOT / HeroBot integration report - versions,
 * API compatibility result, spawn tier, everything that was probed, every warning - plus, when PvP BOT is
 * usable, which of its GLOBAL behaviour switches are off (the addon reads these but never writes them).
 */
final class AdapterFormatter {
    /** Detail lines are diagnostics; a runaway adapter must still not flood the chat. */
    static final int MAX_DETAIL_LINES = 40;
    static final int MAX_WARNING_LINES = 20;

    private AdapterFormatter() {
    }

    /**
     * @param caps the global switches, or null when they were not read; only shown for a usable adapter
     *             because "defaults" reported by an unreachable PvP BOT would be misleading
     */
    static List<String> format(PvpBotOperations.Status s, GlobalCapabilities caps) {
        List<String> out = new ArrayList<>();
        out.add(title("PvP BOT adapter"));
        if (s == null) {
            out.add(bad("No adapter status is available (the integration has not been probed yet)."));
            return out;
        }
        out.add(label("Availability: ") + InfoFormatter.availability(s.availability())
                + label(s.usable() ? "" : "  (nothing is rolled or spawned)"));
        out.add(label("PvP BOT: ") + plain(Fmt.orDash(s.pvpBotVersion()))
                + label("   HeroBot: ") + plain(Fmt.orDash(s.heroBotVersion()))
                + label("   Addon: ") + plain(Fmt.orDash(s.addonVersion())));
        out.add(label("Spawn tier: ") + plain(Fmt.orDash(s.spawnTier())));
        if (s.summary() != null && !s.summary().isBlank()) {
            out.add(label("Summary: ") + plain(s.summary()));
        }

        List<String> details = Fmt.lines(s.details());
        if (details.isEmpty()) {
            out.add(label("Details: none reported"));
        } else {
            out.add(label("Details:"));
            List<String> shown = new ArrayList<>();
            for (String d : details) {
                shown.add(label("  ") + plain(d));
            }
            Fmt.addCapped(out, shown, MAX_DETAIL_LINES, "");
        }

        List<String> warnings = Fmt.lines(s.warnings());
        if (warnings.isEmpty()) {
            out.add(label("Warnings: ") + good("none"));
        } else {
            out.add(warn("Warnings (" + warnings.size() + "):"));
            List<String> shown = new ArrayList<>();
            for (String w : warnings) {
                shown.add(warn("  ! ") + plain(w));
            }
            Fmt.addCapped(out, shown, MAX_WARNING_LINES, "");
        }

        if (!s.usable()) {
            out.add(label("Global PvP BOT switches: not read (PvP BOT is unusable)"));
        } else if (caps != null) {
            switchLines(out, caps);
        }
        return out;
    }

    private static void switchLines(List<String> out, GlobalCapabilities c) {
        List<String> off = new ArrayList<>();
        addIfOff(off, "autoEquipArmor", c.autoEquipArmor());
        addIfOff(off, "autoEquipWeapon", c.autoEquipWeapon());
        addIfOff(off, "combat", c.combatEnabled());
        addIfOff(off, "autoTarget", c.autoTargetEnabled());
        addIfOff(off, "ranged", c.rangedEnabled());
        addIfOff(off, "mace", c.maceEnabled());
        addIfOff(off, "spear", c.spearEnabled());
        addIfOff(off, "crystalPvp", c.crystalPvpEnabled());
        addIfOff(off, "anchorPvp", c.anchorPvpEnabled());
        addIfOff(off, "cobweb", c.cobwebEnabled());
        addIfOff(off, "autoTotem (managed)", c.autoTotemEnabled());
        addIfOff(off, "autoShield", c.autoShieldEnabled());
        addIfOff(off, "autoEat", c.autoEatEnabled());
        addIfOff(off, "autoPotion", c.autoPotionEnabled());
        addIfOff(off, "autoMend", c.autoMendEnabled());
        addIfOff(off, "shieldBreak", c.shieldBreakEnabled());
        addIfOff(off, "retreat", c.retreatEnabled());
        addIfOff(off, "botsRelogs", c.botsRelogs());
        addIfOff(off, "botLeaveOnDeath", c.botLeaveOnDeath());
        addIfOff(off, "clearOnRemove", c.clearOnRemove());
        out.add(label("Global PvP BOT switches OFF (read from PvP BOT; this addon manages only pvpbotSettings): ")
                + (off.isEmpty() ? good("none") : plain(String.join(", ", off))));
        if (!c.autoTotemEnabled()) {
            out.add(label("  autoTotem is off: that is the intended state, this addon manages it (pvpbotSettings.autoTotemEnabled) and places "
                    + "the offhand itself: the best shield, else a totem of undying (see the README, \"The offhand\")."));
        }
        if (!c.autoTargetEnabled()) {
            out.add(label("  autoTarget is off: PvP BOT itself never picks a target on sight. That is the intended state while this "
                    + "addon's line-of-sight hunter (config aggro) is on, which notices players by line of sight "
                    + "(sight has no block limit in the view cone; engagement is limited to 16 blocks) and reacts to whoever hits "
                    + "them; with the hunter off inhabitants stay passive until "
                    + "something attacks them (pvpbotSettings.autoTargetEnabled in this addon's config, or pvpbot settings "
                    + "auto-target true, hands acquisition back to PvP BOT)."));
        }
    }

    private static void addIfOff(List<String> off, String name, boolean on) {
        if (!on) {
            off.add(name);
        }
    }
}
