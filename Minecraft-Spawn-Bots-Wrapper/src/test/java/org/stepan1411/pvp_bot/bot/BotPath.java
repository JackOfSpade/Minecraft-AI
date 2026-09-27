package org.stepan1411.pvp_bot.bot;

import net.minecraft.util.math.Vec3d;
import org.stepan1411.testdouble.Recorder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test-only fake of the upstream path registry with the behaviour the adapter depends on: a path must exist
 * before points are added, an empty path cannot be followed, deleting a path drops its followers, a
 * follower is tracked per bot NAME (case-sensitive). Like upstream's it stores whatever it is given, so a
 * test can see exactly what reached "upstream" (the {@code loop} flag of a one-point path, for instance).
 */
public class BotPath {

    public static class PathData {
        public String name;
        public List<Vec3d> points = new ArrayList<>();
        public boolean loop = false;
        public boolean attack = true;
        public String walkType = "bhop";

        public PathData(String name) {
            this.name = name;
        }
    }

    private static final Map<String, PathData> PATHS = new HashMap<>();
    private static final Map<String, String> FOLLOWERS = new HashMap<>();

    /** Test control: drops all paths and followers. */
    public static void resetAll() {
        PATHS.clear();
        FOLLOWERS.clear();
    }

    /** Test control: seeds a path as if another mod or an earlier session had created it. */
    public static void seed(String name, boolean loop, Vec3d... points) {
        PathData p = new PathData(name);
        p.loop = loop;
        p.points.addAll(List.of(points));
        PATHS.put(name, p);
    }

    /** Test control: seeds a follower for a bot. */
    public static void seedFollower(String bot, String path) {
        FOLLOWERS.put(bot, path);
    }

    /** Test control: which path a bot follows, or null. */
    public static String followerOf(String bot) {
        return FOLLOWERS.get(bot);
    }

    public static boolean createPath(String name) {
        if (!Recorder.guard("createPath", name) || PATHS.containsKey(name)) {
            return false;
        }
        PATHS.put(name, new PathData(name));
        return true;
    }

    public static boolean deletePath(String name) {
        if (!Recorder.guard("deletePath", name) || !PATHS.containsKey(name)) {
            return false;
        }
        PATHS.remove(name);
        FOLLOWERS.values().removeIf(name::equals);
        return true;
    }

    public static boolean addPoint(String pathName, Vec3d point) {
        boolean ok = Recorder.guard("addPoint", pathName);
        PathData p = PATHS.get(pathName);
        if (!ok || p == null) {
            return false;
        }
        p.points.add(point);
        return true;
    }

    public static boolean setLoop(String pathName, boolean loop) {
        boolean ok = Recorder.guard("setLoop", pathName + "=" + loop);
        PathData p = PATHS.get(pathName);
        if (!ok || p == null) {
            return false;
        }
        p.loop = loop;
        return true;
    }

    public static boolean setAttack(String pathName, boolean attack) {
        boolean ok = Recorder.guard("setAttack", pathName + "=" + attack);
        PathData p = PATHS.get(pathName);
        if (!ok || p == null) {
            return false;
        }
        p.attack = attack;
        return true;
    }

    public static boolean setWalkType(String pathName, String walkType) {
        boolean ok = Recorder.guard("setWalkType", pathName + "=" + walkType);
        PathData p = PATHS.get(pathName);
        if (!ok || p == null) {
            return false;
        }
        p.walkType = walkType;
        return true;
    }

    public static boolean startFollowing(String botName, String pathName) {
        boolean ok = Recorder.guard("startFollowing", botName + "->" + pathName);
        PathData p = PATHS.get(pathName);
        if (!ok || p == null || p.points.isEmpty()) {
            return false;
        }
        FOLLOWERS.put(botName, pathName);
        return true;
    }

    public static boolean stopFollowing(String botName) {
        Recorder.guard("stopFollowing", botName);
        return FOLLOWERS.remove(botName) != null;
    }

    public static boolean isFollowing(String botName, String pathName) {
        return pathName.equals(FOLLOWERS.get(botName));
    }

    public static PathData getPath(String name) {
        return PATHS.get(name);
    }
}
