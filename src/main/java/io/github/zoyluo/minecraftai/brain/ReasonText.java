package io.github.zoyluo.minecraftai.brain;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ReasonText {
    private static final Map<String, String> EXACT = Map.ofEntries(
            Map.entry("pickup_timeout", "I could not pick up the dropped items, so I will try another approach."),
            Map.entry("stuck", "I am stuck, so I will try another approach."),
            Map.entry("no_flat_site", "There is no suitable flat area nearby."),
            Map.entry("no_reachable_target_block_in_range", "I could not find a reachable target block nearby."),
            Map.entry("pathfinding_throttled", "Pathfinding just failed, so I will pause briefly before trying again."),
            Map.entry("did_not_reach", "I could not reach the target location."),
            Map.entry("mine_timeout", "Mining took too long, so I will stop and reassess."),
            Map.entry("craft_timeout", "Crafting took too long, so I will stop and reassess."),
            Map.entry("move_timeout", "Moving took too long, so I will stop and reassess."),
            Map.entry("build_timeout", "Building took too long, so I will stop and reassess."),
            Map.entry("forage_timeout", "Foraging took too long, so I will stop and reassess."),
            Map.entry("aborted", "The task was stopped manually."),
            Map.entry("invalid_block_id", "The block ID is invalid, so I cannot identify it."),
            Map.entry("unknown_block_id", "I do not recognize that block ID."),
            Map.entry("unknown_palette", "I cannot identify the blueprint material palette."),
            Map.entry("inventory_full", "My inventory is full; I need to store items first."),
            Map.entry("no_resource_nearby", "I could not find a collectable resource nearby."),
            Map.entry("no_resource_after_explore", "I searched several areas and still could not find a collectable resource."),
            Map.entry("unsupported_resource_type", "That resource type cannot be collected automatically yet."),
            Map.entry("gather_timeout", "Gathering took too long, so I will stop and reassess."),
            Map.entry("no_base", "No base is marked yet; I need to remember the base position first."),
            Map.entry("no_base_container", "I could not find a usable chest near the base."),
            Map.entry("container_full", "The nearby container is full, so the items remain in my inventory."),
            Map.entry("partial_stockpile_container_full", "I stored some items, but the base chest has no room for the rest."),
            Map.entry("stockpile_timeout", "Storing items took too long, so I will stop and reassess."),
            Map.entry("no_supply", "There are no usable supplies at the base and not enough materials to make them."),
            Map.entry("no_food", "There is no edible food in my inventory."),
            Map.entry("resupply_timeout", "Resupplying took too long, so I will stop and reassess."),
            Map.entry("out_of_fuel", "There is not enough fuel in my inventory or the base chest."),
            Map.entry("no_valuables_in_radius", "I could not see any valuable blocks nearby."),
            Map.entry("mine_valuables_scan_timeout", "Scanning the area took too long, so I will stop and reassess."),
            Map.entry("mine_valuables_timeout", "Mining the valuables I found took too long, so I will stop and reassess."),
            Map.entry("mine_valuables_timeout_partial", "I mined some valuables, but ran out of time before finishing the rest."),
            Map.entry("mine_valuables_need_better_tool", "I need a better tool to mine the valuables I could see."),
            Map.entry("mine_valuables_all_gone", "The valuables I saw were gone by the time I got there."),
            Map.entry("mine_valuables_none_reachable", "I could not reach any of the valuables I saw.")
    );

    private ReasonText() {
    }

    public static String friendly(String reason) {
        if (reason == null || reason.isBlank()) {
            return "No specific failure reason was provided.";
        }
        String trimmed = reason.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("need:")) {
            return "Still need " + itemText(trimmed.substring(trimmed.indexOf(':') + 1).trim()) + ".";
        }
        if (lower.startsWith("stuck:")) {
            return "I am stuck, so I will use another approach for " + taskName(trimmed.substring(trimmed.indexOf(':') + 1).trim()) + ".";
        }
        if (lower.startsWith("pathfinding_failed:")) {
            return "I could not path there: " + pathReason(trimmed.substring(trimmed.indexOf(':') + 1).trim()) + ".";
        }
        if (lower.startsWith("place_crafting_table_failed:")) {
            return "I could not place the crafting table: " + friendly(trimmed.substring(trimmed.indexOf(':') + 1).trim());
        }
        String exact = EXACT.get(lower);
        if (exact != null) {
            return exact;
        }
        return "Failure reason: " + itemText(trimmed) + ".";
    }

    public static String taskName(String name) {
        return switch (name) {
            case "mine" -> "mining";
            case "craft" -> "crafting";
            case "smelt" -> "smelting";
            case "move" -> "moving";
            case "eat" -> "eating";
            case "sleep" -> "sleeping";
            case "combat" -> "fighting";
            case "evade" -> "avoiding danger";
            case "light_area" -> "lighting the area";
            case "build" -> "building";
            case "forage" -> "foraging";
            case "container" -> "organizing containers";
            case "stockpile" -> "storing supplies";
            case "resupply" -> "resupplying";
            case "strip_mine" -> "strip mining";
            case "farm" -> "farming";
            case "breed" -> "breeding animals";
            case "fish" -> "fishing";
            case "trade" -> "trading with villagers";
            case "follow" -> "following";
            case "hold" -> "waiting";
            case "guard" -> "guarding";
            case "shelter" -> "seeking emergency shelter";
            case "gather" -> "gathering";
            case "hunt" -> "hunting";
            case "dig_down" -> "digging downward";
            case "descend_to_y" -> "descending to the mining layer";
            case "ore_dig" -> "mining ore";
            case "mine_valuables" -> "mining valuables";
            default -> itemText(name);
        };
    }

    private static final Pattern ID_PATTERN = Pattern.compile("minecraft:[a-z0-9_./]+");

    public static String itemText(String text) {
        if (text == null || text.isBlank()) {
            return "unknown";
        }
        if (text.contains("minecraft:")) {
            Matcher m = ID_PATTERN.matcher(text);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(englishId(m.group())));
            }
            m.appendTail(sb);
            return sb.toString().trim();
        }
        return text.replace('_', ' ').trim();
    }

    /** minecraft:xxx becomes a readable, locale-independent English identifier. */
    private static String englishId(String id) {
        return id.replace("minecraft:", "").replace('_', ' ');
    }

    private static String pathReason(String reason) {
        return switch (reason.toUpperCase(Locale.ROOT)) {
            case "NO_START" -> "the starting position is not safe to stand on";
            case "GOAL_UNREACHABLE" -> "the goal is unreachable";
            case "SEARCH_LIMIT" -> "the search area is too large";
            case "TIMEOUT" -> "pathfinding timed out";
            case "GOAL_NOT_STANDABLE" -> "the goal position is not safe to stand on";
            default -> itemText(reason);
        };
    }
}
