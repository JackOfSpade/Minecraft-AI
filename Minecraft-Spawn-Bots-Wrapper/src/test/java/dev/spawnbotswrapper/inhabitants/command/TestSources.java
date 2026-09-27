package dev.spawnbotswrapper.inhabitants.command;

import net.minecraft.command.permission.LeveledPermissionPredicate;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.command.permission.PermissionPredicate;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Command sources without a server: no world, no server, no entity, but a real permission predicate and
 * an output that records what was sent. Enough for Brigadier parsing, requirements and feedback.
 */
final class TestSources {
    private TestSources() {
    }

    /** Records everything sent to it and claims to want feedback, errors and op broadcasts. */
    static final class Capture implements CommandOutput {
        final List<Text> messages = new ArrayList<>();

        @Override
        public void sendMessage(Text message) {
            messages.add(message);
        }

        @Override
        public boolean shouldReceiveFeedback() {
            return true;
        }

        @Override
        public boolean shouldTrackOutput() {
            return true;
        }

        /** True: a command that (wrongly) asked to broadcast to ops would then touch the missing world and fail. */
        @Override
        public boolean shouldBroadcastConsoleToOps() {
            return true;
        }

        List<String> strings() {
            List<String> out = new ArrayList<>();
            for (Text t : messages) {
                out.add(t.getString());
            }
            return out;
        }

        String joined() {
            return String.join("\n", strings());
        }
    }

    /** The predicate a source has at vanilla op {@code level} 0-4. */
    static PermissionPredicate at(int level) {
        return LeveledPermissionPredicate.fromLevel(PermissionLevel.fromLevel(level));
    }

    static ServerCommandSource source(PermissionPredicate permissions, CommandOutput output) {
        return new ServerCommandSource(output, Vec3d.ZERO, Vec2f.ZERO, null, permissions, "test",
                Text.literal("test"), null, null);
    }

    static ServerCommandSource level(int level, CommandOutput output) {
        return source(at(level), output);
    }

    static ServerCommandSource level(int level) {
        return level(level, CommandOutput.DUMMY);
    }
}
