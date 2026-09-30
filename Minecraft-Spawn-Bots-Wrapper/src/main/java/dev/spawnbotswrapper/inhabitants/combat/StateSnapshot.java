package dev.spawnbotswrapper.inhabitants.combat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A compact, one-line picture of an inhabitant's combat-relevant state, written into the "damage taken" and
 * "ranged loop" diagnostics so a bot that misbehaves in a fight can be understood from the log alone. Pure data
 * plus a formatter: the Minecraft side ({@code BotStateProbe}) fills it, the unit tests pin the text.
 *
 * @param slot            selected hotbar slot (0-8)
 * @param mainHand        main-hand item name ("empty" when nothing)
 * @param mainCharged     for a crossbow in the main hand whether it is loaded; null when it is not a crossbow
 * @param offHand         off-hand item name ("empty" when nothing)
 * @param using           whether the bot is currently using an item (drawing a bow, loading a crossbow, ...)
 * @param usedItem        the item in use ("none" when not using)
 * @param useTicks        ticks the current use has run so far (0 when not using)
 * @param useRemaining    ticks left until the use completes (0 when not using)
 * @param arrows          arrows of every kind in the inventory
 * @param rockets         firework rockets in the inventory (crossbow ammo)
 * @param hasBow          a bow is carried anywhere in the inventory
 * @param hasCrossbow     a crossbow is carried anywhere in the inventory
 * @param hasMelee        a sword, axe, spear, mace or trident is carried anywhere in the inventory
 * @param nearestName     the attacker if there is one, else the nearest real player; null when none
 * @param nearestDistance blocks to it; negative when unknown
 * @param lineOfSight     eye-to-eye line of sight to it; null when unknown
 * @param onGround        standing on solid ground
 * @param inWater         in water
 * @param inWeb           in a cobweb
 * @param upstream        PvP BOT's global switches as text, or null when unreadable (see the README)
 */
public record StateSnapshot(int slot, String mainHand, Boolean mainCharged, String offHand, boolean using,
                            String usedItem, int useTicks, int useRemaining, int arrows, int rockets,
                            boolean hasBow, boolean hasCrossbow, boolean hasMelee, String nearestName,
                            double nearestDistance, Boolean lineOfSight, boolean onGround, boolean inWater,
                            boolean inWeb, String upstream) {

    /** For example {@code slot=2 main=crossbow(unloaded) off=shield using=crossbow(7/25t) ammo=arrows:12,rockets:0 ...}. */
    public String format() {
        StringBuilder sb = new StringBuilder("slot=").append(slot).append(" main=").append(mainHand);
        if (mainCharged != null) {
            sb.append(mainCharged ? "(charged)" : "(unloaded)");
        }
        sb.append(" off=").append(offHand);
        if (using) {
            sb.append(" using=").append(usedItem).append('(').append(useTicks).append('/')
                    .append(useTicks + useRemaining).append("t)");
        } else {
            sb.append(" using=no");
        }
        sb.append(" ammo=arrows:").append(arrows).append(",rockets:").append(rockets);
        List<String> carried = new ArrayList<>();
        if (hasBow) {
            carried.add("bow");
        }
        if (hasCrossbow) {
            carried.add("crossbow");
        }
        if (hasMelee) {
            carried.add("melee");
        }
        sb.append(" carries=").append(carried.isEmpty() ? "none" : String.join(",", carried));
        if (nearestName == null) {
            sb.append(" nearest=none");
        } else {
            sb.append(" nearest=").append(nearestName);
            if (nearestDistance >= 0) {
                sb.append('@').append(String.format(Locale.ROOT, "%.1f", nearestDistance));
            }
            sb.append(" los=").append(lineOfSight == null ? "?" : lineOfSight ? "yes" : "no");
        }
        sb.append(" ground=").append(onGround ? 1 : 0).append(" water=").append(inWater ? 1 : 0)
                .append(" web=").append(inWeb ? 1 : 0);
        sb.append(" pvpbot=").append(upstream == null ? "unreadable" : upstream);
        return sb.toString();
    }
}
