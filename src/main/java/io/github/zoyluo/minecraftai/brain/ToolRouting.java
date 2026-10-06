package io.github.zoyluo.minecraftai.brain;

import io.github.zoyluo.minecraftai.task.GatherThenGiveTask;
import io.github.zoyluo.minecraftai.task.TaskState;
import java.util.LinkedHashSet;
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

    private static final String GIVE_ITEM_BLOCKED = "blocked: give_item only hands over what you already carry, "
            + "but the player asked for newly collected resources. Collect them first: gather_then_give collects "
            + "one resource and hands it to the player (count required), fulfill_items with the player as "
            + "recipient also covers ore and stone, or collect with gather/mine_ore/harvest_crop and hand over "
            + "when it finishes. give_item is offered again once the collection has finished";
    private static final String ACHIEVE_GOAL_BLOCKED = "blocked: achieve_goal can be met by items you already "
            + "carry, but the player asked for newly collected resources; use a collection tool "
            + "(gather, mine_ore, harvest_crop, forage) or fulfill_items instead. It is offered again once the "
            + "collection has finished";
    private static final String SPLIT_COLLECTION_BLOCKED = "blocked: this request collects new resources and "
            + "hands them over; use gather_then_give (one resource, count required) or fulfill_items with the "
            + "player as recipient so the newly collected stack is kept until it is delivered";
    private static final String CRAFTED_RESULT_BLOCKED = "blocked: this request also asks for a crafted result, "
            + "and this tool cannot keep the new raw quota apart from what you carry (gather_then_give would hand "
            + "over the raw resource itself). Collect with gather (count) first; craft and hand over the result "
            + "when the collection has finished";
    private static final String QUOTA_ALREADY_GATHERED_BLOCKED = "blocked: the new quota was already collected "
            + "and is still carried; hand it over with give_item instead of collecting again";

    private RequestIntent intent = RequestIntent.NONE;
    /** A collection of this instruction chain finished: nothing about the request is withheld any more. */
    private boolean collectionFinished;
    /** gather_then_give collected its quota but could not deliver it. */
    private boolean quotaAlreadyGathered;
    /** The direct task (not a goal mission) this chain started, so a stale or unrelated completion is ignored. */
    private String startedTask = "";

    /** A new player instruction: everything starts from what its own wording asks for. */
    void beginInstruction(RequestIntent newIntent) {
        intent = newIntent;
        collectionFinished = false;
        quotaAlreadyGathered = false;
        startedTask = "";
    }

    /** An autonomous goal-continuation wake is not part of the last instruction: nothing carries over. */
    void beginGoalContinuation() {
        beginInstruction(RequestIntent.NONE);
    }

    void noteTaskStarted(String taskName) {
        startedTask = taskName == null ? "" : taskName;
    }

    /** The status of the active or last task. Only the completion of the task this chain started counts. */
    void observeTask(String taskName, TaskState state) {
        if (state == TaskState.COMPLETED && !startedTask.isEmpty() && startedTask.equals(taskName)) {
            collectionFinished = true;
        }
    }

    void observeGoalCompleted() {
        collectionFinished = true;
    }

    /** A failure reason from the task manager; only a failed handoff proves the quota is already in hand. */
    void observeFailure(String reason) {
        if (reason != null && reason.startsWith(GatherThenGiveTask.HANDOFF_FAILED_PREFIX)) {
            collectionFinished = true;
            quotaAlreadyGathered = true;
        }
    }

    Set<String> withheldTools() {
        Set<String> withheld = new LinkedHashSet<>();
        if (intent.acquiresRaw() && !collectionFinished) {
            withheld.add(GIVE_ITEM);
            withheld.add(ACHIEVE_GOAL);
            if (intent.craftsOutcome()) {
                // "Gather 32 logs, craft a table, give it to me": the raw quota is collected first (gather
                // with a count is a new quota). fulfill_items cannot express a new raw quota beside a
                // crafted result, and gather_then_give would hand over the logs instead of the table;
                // the later stages unlock when the collection finishes.
                withheld.add(GATHER_THEN_GIVE);
                withheld.add(FULFILL_ITEMS);
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

    /** The synthetic failure text for a call to a withheld tool, or null when the call may run. */
    String blockedResult(String toolName) {
        if (!withheldTools().contains(toolName)) {
            return null;
        }
        return switch (toolName) {
            case GIVE_ITEM -> GIVE_ITEM_BLOCKED;
            case ACHIEVE_GOAL -> ACHIEVE_GOAL_BLOCKED;
            case GATHER_THEN_GIVE -> quotaAlreadyGathered ? QUOTA_ALREADY_GATHERED_BLOCKED : CRAFTED_RESULT_BLOCKED;
            case FULFILL_ITEMS -> CRAFTED_RESULT_BLOCKED;
            default -> SPLIT_COLLECTION_BLOCKED;
        };
    }
}
