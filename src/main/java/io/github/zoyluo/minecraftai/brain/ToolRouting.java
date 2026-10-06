package io.github.zoyluo.minecraftai.brain;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.zoyluo.minecraftai.task.GatherThenGiveTask;
import io.github.zoyluo.minecraftai.task.TaskState;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Which tools one player instruction may use, so that stock the bot already carries can never
 * stand in for a request to collect new resources ("gather 32 logs" must not hand over 32 logs
 * that were in the inventory before the request).
 *
 * <p>While such a request is unfinished the tools a carried stack can satisfy are withheld, both
 * from the tool list the model sees and, as a backstop, at dispatch. The restriction is lifted for
 * the rest of the instruction chain once the collection has finished, so a handoff the wording did
 * not make explicit (or one that failed) cannot dead-end the request.</p>
 *
 * <p>The guard is per requested resource: "get 32 logs and 10 coal" asks for two collections, and the
 * logs finishing does not open the carried coal. Each collection the chain starts names the items it
 * collects (the tool's own arguments), and its completion answers the requested resources those items
 * are. A collection that names no item, or one that matches no requested resource, answers the whole
 * request: the instruction can never be left withholding a handoff it has no way to finish.</p>
 */
final class ToolRouting {
    static final String GIVE_ITEM = "give_item";
    static final String ACHIEVE_GOAL = "achieve_goal";
    static final String GATHER_THEN_GIVE = "gather_then_give";
    static final String FULFILL_ITEMS = "fulfill_items";
    /**
     * Entry points that start an ordinary collection task. A request that collects and hands over
     * one resource is served by gather_then_give / fulfill_items, which keep the new stack
     * reserved through delivery; these would split it into a collection and a later direct give.
     */
    private static final Set<String> SPLIT_COLLECTION_TOOLS = Set.of(
            "gather", "assign_task", "forage", "mine_ore", "mine_valuables_in_radius", "harvest_crop", "mine_block");
    /**
     * Work-start tools that collect nothing, so their task finishing says nothing about the request: a
     * walk, a craft or a handoff ending must not open the carried stock. Every other tool may collect
     * (a fish, a hunt, a farm), and the completion of what it starts answers the request.
     */
    private static final Set<String> NON_COLLECTING_TOOLS = Set.of(
            "follow", "hold", "guard", "move_to", "goto_place", "show_location", "find", "eat", "craft", "smelt",
            "place_block", "light_area", "launch_boat", "board_boat", "boat_follow", "exit_boat", "deposit_all",
            "deposit", "withdraw", "inspect_container", "give_item");
    /** The arguments that name the item a collection tool collects. */
    private static final List<String> ITEM_ARGUMENTS = List.of("item", "ore", "crop", "block", "target_item");

    private static final String GIVE_ITEM_BLOCKED = "blocked: give_item only hands over what you already carry, "
            + "but the player asked for newly collected resources. Collect them first: gather_then_give collects "
            + "one resource and hands it to the player (count required when the player stated one), fulfill_items "
            + "with the player as recipient also covers ore and stone, or collect with gather/mine_ore/harvest_crop "
            + "and hand over when it finishes. give_item is offered again once every requested resource has been "
            + "collected";
    private static final String ACHIEVE_GOAL_BLOCKED = "blocked: achieve_goal can be met by items you already "
            + "carry, but the player asked for newly collected resources; use a collection tool "
            + "(gather, mine_ore, harvest_crop, forage) or fulfill_items instead. It is offered again once every "
            + "requested resource has been collected";
    private static final String SPLIT_COLLECTION_BLOCKED = "blocked: this request collects new resources and "
            + "hands them over; use gather_then_give (one resource, count only if the player stated one) or "
            + "fulfill_items with the player as recipient so the newly collected stack is kept until it is delivered";
    private static final String CRAFTED_RESULT_BLOCKED = "blocked: this request also asks for a crafted result, "
            + "and gather_then_give would hand over the raw resource itself. Call fulfill_items with the raw "
            + "resource as its own allocation without a recipient (it is always newly collected above what you "
            + "carry) next to the crafted item for the player, or collect with gather (count) first and craft and "
            + "hand over the result when the collection has finished";
    private static final String RAW_QUOTA_MISSING_BLOCKED = "blocked: this request collects new raw resources "
            + "and also asks for a crafted result, but the fulfill_items manifest has no raw allocation for them, so "
            + "carried stock could stand in. Add the raw resource as its own item without a recipient (it stays "
            + "with you and is always newly collected) next to the crafted item for the player";
    private static final String KEPT_RESULT_BLOCKED = "blocked: this request collects new raw resources and also crafts "
            + "a result you keep: gather_then_give would hand over the raw resource, and fulfill_items counts a crafted "
            + "item you already carry as done. Collect with gather (count) first; craft the result when the collection "
            + "has finished";
    private static final String PARTIAL_HANDOFF_BLOCKED = "blocked: the player wants only part of the collection "
            + "handed over, and this tool hands over everything it collects. Collect with gather (count) first; "
            + "hand over the part with give_item when the collection has finished";
    private static final String QUOTA_ALREADY_GATHERED_BLOCKED = "blocked: the new quota was already collected "
            + "and is still carried; hand it over with give_item instead of collecting again";

    private RequestIntent intent = RequestIntent.NONE;
    /** The raw resources of this instruction chain that no finished collection has answered yet. */
    private final List<Set<String>> pending = new ArrayList<>();
    /** gather_then_give collected its quota but could not deliver it. */
    private boolean quotaAlreadyGathered;
    /** The direct task (not a goal mission) this chain started, so a stale or unrelated completion is ignored. */
    private String startedTask = "";
    /** What the collection the chain started is collecting; null when it names no item. */
    private Set<String> startedItems;

    /** A new player instruction: everything starts from what its own wording asks for. */
    void beginInstruction(RequestIntent newIntent) {
        intent = newIntent;
        pending.clear();
        if (newIntent.acquiresRaw()) {
            pending.addAll(newIntent.resources());
        }
        quotaAlreadyGathered = false;
        startedTask = "";
        startedItems = null;
    }

    /** An autonomous goal-continuation wake is not part of the last instruction: nothing carries over. */
    void beginGoalContinuation() {
        beginInstruction(RequestIntent.NONE);
    }

    /** A direct task named {@code taskName} was started; what it collects is not known. */
    void noteTaskStarted(String taskName) {
        noteTaskStarted(taskName, null);
    }

    /** A direct task was started that collects {@code items} (item ids), or null when its tool names none. */
    void noteTaskStarted(String taskName, Collection<String> items) {
        startedTask = taskName == null ? "" : taskName;
        startedItems = items == null ? null : Set.copyOf(items);
    }

    /** A goal mission was started that collects {@code items}, or null when its tool names none. */
    void noteGoalStarted(Collection<String> items) {
        startedTask = "";
        startedItems = items == null ? null : Set.copyOf(items);
    }

    /** The status of the active or last task. Only the completion of the task this chain started counts. */
    void observeTask(String taskName, TaskState state) {
        if (state == TaskState.COMPLETED && !startedTask.isEmpty() && startedTask.equals(taskName)) {
            collectionFinished();
        }
    }

    void observeGoalCompleted() {
        collectionFinished();
    }

    /** A failure reason from the task manager; only a failed handoff proves the quota is already in hand. */
    void observeFailure(String reason) {
        if (reason != null && reason.startsWith(GatherThenGiveTask.HANDOFF_FAILED_PREFIX)) {
            pending.clear();
            quotaAlreadyGathered = true;
        }
    }

    /**
     * The collection the chain started has finished: it answers the requested resources its items are. An
     * unknown or unmatched collection answers the whole request (the instruction must not be left withholding a
     * handoff it cannot finish), and a finished collection is answered once, however often its status is read.
     */
    private void collectionFinished() {
        Set<String> items = startedItems;
        startedTask = "";
        startedItems = null;
        if (items == null || items.isEmpty()) {
            pending.clear();
            return;
        }
        boolean answered = false;
        for (String item : items) {
            for (Iterator<Set<String>> resources = pending.iterator(); resources.hasNext(); ) {
                if (ItemNouns.matchesItem(resources.next(), item)) {
                    resources.remove();
                    answered = true;
                    break;
                }
            }
        }
        if (!answered) {
            pending.clear();
        }
    }

    Set<String> withheldTools() {
        Set<String> withheld = new LinkedHashSet<>();
        if (collecting()) {
            withheld.add(GIVE_ITEM);
            withheld.add(ACHIEVE_GOAL);
            if (intent.handsOverPart()) {
                // "Gather 32 logs and give me 16": the raw quota is collected first (gather with a count is a
                // new quota). gather_then_give would hand over everything it collected (32 instead of 16) and
                // fulfill_items cannot express a new raw quota beside a different handoff of the same item; the
                // later stage unlocks when the collection finishes.
                withheld.add(GATHER_THEN_GIVE);
                withheld.add(FULFILL_ITEMS);
            } else if (intent.craftsOutcome()) {
                // "Gather 32 logs, craft a table, give it to me": gather_then_give would hand over the logs, not
                // the table. fulfill_items expresses it in one call (the raw quota as an allocation without a
                // recipient, always new, beside the crafted item for the player), so it stays; the dispatch check
                // holds the manifest to that. A crafted result the bot keeps ("gather 32 logs and craft a table") is
                // different: fulfill_items counts one the bot already carries as done, so collect first.
                withheld.add(GATHER_THEN_GIVE);
                if (!intent.handsOverAcquired()) {
                    withheld.add(FULFILL_ITEMS);
                }
            } else if (intent.handsOverAcquired() && intent.quantityStated()) {
                // gather_then_give needs the number, so without one the plain collection tools stay.
                withheld.addAll(SPLIT_COLLECTION_TOOLS);
            }
        }
        if (quotaAlreadyGathered) {
            withheld.add(GATHER_THEN_GIVE);
        }
        return Set.copyOf(withheld);
    }

    private boolean collecting() {
        return intent.acquiresRaw() && !pending.isEmpty();
    }

    /** The synthetic failure text for a call to a withheld tool, or null when the call may run. */
    String blockedResult(String toolName) {
        return blockedResult(toolName, null);
    }

    /**
     * The same, for a call with its arguments: a fulfill_items manifest that leaves out the raw resource a
     * request collects (next to a crafted result) is held back too, because the carried stock could then
     * stand in for the quota.
     */
    String blockedResult(String toolName, JsonObject arguments) {
        if (FULFILL_ITEMS.equals(toolName) && collecting() && intent.craftsOutcome() && intent.handsOverAcquired()
                && !intent.handsOverPart() && arguments != null && !hasRawAllocation(arguments)) {
            return RAW_QUOTA_MISSING_BLOCKED;
        }
        if (!withheldTools().contains(toolName)) {
            return null;
        }
        return switch (toolName) {
            case GIVE_ITEM -> GIVE_ITEM_BLOCKED;
            case ACHIEVE_GOAL -> ACHIEVE_GOAL_BLOCKED;
            case GATHER_THEN_GIVE -> quotaAlreadyGathered ? QUOTA_ALREADY_GATHERED_BLOCKED : collectFirstBlocked();
            case FULFILL_ITEMS -> collectFirstBlocked();
            default -> SPLIT_COLLECTION_BLOCKED;
        };
    }

    private String collectFirstBlocked() {
        if (intent.handsOverPart()) {
            return PARTIAL_HANDOFF_BLOCKED;
        }
        return intent.handsOverAcquired() ? CRAFTED_RESULT_BLOCKED : KEPT_RESULT_BLOCKED;
    }

    /**
     * True when a fulfill_items manifest holds a raw resource (an item the world yields), kept or handed over: either
     * way it is a quota of new units above what is carried, which is what the carried stock must not stand in for.
     */
    private static boolean hasRawAllocation(JsonObject arguments) {
        JsonElement items = arguments.get("items");
        if (items == null || !items.isJsonArray()) {
            return false;
        }
        for (JsonElement element : items.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            if (ItemNouns.isRawItemId(text(element.getAsJsonObject(), "item"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * What the successful tool calls of one round start as collection: the item ids they name (empty when they
     * name none), or empty when none of them could collect (a follow, a craft, a walk).
     */
    static Optional<Set<String>> collectionStartedBy(List<ChatToolCall> calls, Set<String> succeededCallIds) {
        boolean started = false;
        Set<String> items = new LinkedHashSet<>();
        for (ChatToolCall call : calls) {
            if (!succeededCallIds.contains(call.id()) || NON_COLLECTING_TOOLS.contains(call.name())
                    || !BrainCoordinator.isWorkStartTool(call.name())) {
                continue;
            }
            started = true;
            JsonObject arguments = call.parsedArguments();
            for (String argument : ITEM_ARGUMENTS) {
                String item = text(arguments, argument);
                if (item != null && !item.isBlank()) {
                    items.add(item.trim());
                }
            }
            JsonElement allocations = arguments.get("items");
            if (allocations != null && allocations.isJsonArray()) {
                JsonArray array = allocations.getAsJsonArray();
                for (JsonElement allocation : array) {
                    String item = allocation.isJsonObject() ? text(allocation.getAsJsonObject(), "item") : null;
                    if (item != null && !item.isBlank()) {
                        items.add(item.trim());
                    }
                }
            }
        }
        return started ? Optional.of(items) : Optional.empty();
    }

    private static String text(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }
}
