package io.github.zoyluo.minecraftai.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks BoatLaunchTask's board() to the same vehicle-confirmation guard BoardBoatTask already
 * carries, so a boarding that succeeds on the final attempt completes instead of spuriously
 * failing with "boat_board_not_confirmed" on the exact tick the bot actually mounted.
 */
final class BoatLaunchBoardConfirmationSourceContractTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/minecraftai");

    @Test
    void boardDoesNotFailOnTheTickItActuallySucceeds() throws IOException {
        String launch = read("task/BoatLaunchTask.java");
        String board = read("task/BoardBoatTask.java");

        int boardMethod = launch.indexOf("private void board(AIPlayerEntity bot)");
        assertTrue(boardMethod >= 0, "BoatLaunchTask must still define board()");
        String boardBody = launch.substring(boardMethod);

        // A non-failed boardBoat() result must be checked against the bot's actual vehicle before
        // the attempt-count guard can fail the task -- mirroring BoardBoatTask's own guard -- so a
        // successful mount on the final attempt is never reported as "not confirmed".
        assertTrue(boardBody.contains("bot.getVehicle() == boat"),
                "a board attempt that already mounted the bot must not fail as not-confirmed");
        int notConfirmed = boardBody.indexOf("\"boat_board_not_confirmed\"");
        int vehicleCheck = boardBody.indexOf("bot.getVehicle() == boat");
        assertTrue(vehicleCheck >= 0 && vehicleCheck < notConfirmed,
                "the vehicle check must guard the not-confirmed failure, not follow it");

        assertTrue(board.contains("bot.getVehicle() != boat"),
                "BoardBoatTask's own guard is the reference implementation this mirrors");
    }

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }
}
