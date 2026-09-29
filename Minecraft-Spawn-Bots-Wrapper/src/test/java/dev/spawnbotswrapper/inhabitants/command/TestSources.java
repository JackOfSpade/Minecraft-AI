package dev.spawnbotswrapper.inhabitants.command;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * Command sources without a server: no world, no server, no entity, but a real permission predicate and
 * an output that records what was sent. Enough for Brigadier parsing, requirements and feedback.
 */
final class TestSources {
    private TestSources() {
    }

    /** Records everything sent to it and claims to want feedback, errors and op broadcasts. */
    static final class Capture implements CommandSource {
        final List<Component> messages = new ArrayList<>();

        @Override
        public void sendSystemMessage(Component message) {
            messages.add(message);
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        /** True: a command that (wrongly) asked to broadcast to ops would then touch the missing world and fail. */
        @Override
        public boolean shouldInformAdmins() {
            return true;
        }

        List<String> strings() {
            List<String> out = new ArrayList<>();
            for (Component t : messages) {
                out.add(t.getString());
            }
            return out;
        }

        String joined() {
            return String.join("\n", strings());
        }
    }

    /** The predicate a source has at vanilla op {@code level} 0-4. */
    static PermissionSet at(int level) {
        return LevelBasedPermissionSet.forLevel(PermissionLevel.byId(level));
    }

    static CommandSourceStack source(PermissionSet permissions, CommandSource output) {
        return new CommandSourceStack(output, Vec3.ZERO, Vec2.ZERO, null, permissions, "test",
                Component.literal("test"), null, null);
    }

    static CommandSourceStack level(int level, CommandSource output) {
        return source(at(level), output);
    }

    static CommandSourceStack level(int level) {
        return level(level, CommandSource.NULL);
    }
}
