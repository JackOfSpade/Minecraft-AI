package org.stepan1411.testdouble;

/** Test-only variants of the upstream settings singleton, each deviating in one way. */
public final class Settings {

    private Settings() {
    }

    /** Only a handful of getters survived; the rest were renamed or removed upstream. */
    public static class FewGetters {
        private boolean maceEnabled = false;
        private boolean botsRelogs = false;
        private int checkInterval = 5;
        private boolean cobwebEnabled = false;

        private static final FewGetters INSTANCE = new FewGetters();

        public static FewGetters get() {
            return INSTANCE;
        }

        public boolean isMaceEnabled() {
            return maceEnabled;
        }

        public boolean isBotsRelogs() {
            return botsRelogs;
        }

        public int getCheckInterval() {
            return checkInterval;
        }

        public boolean isCobwebEnabled() {
            return cobwebEnabled;
        }
    }

    /** Getters exist but with the wrong type, or a static one where an instance getter is expected. */
    public static class WrongTypes {
        private static final WrongTypes INSTANCE = new WrongTypes();

        public static WrongTypes get() {
            return INSTANCE;
        }

        public String isMaceEnabled() {
            return "false";
        }

        public long getCheckInterval() {
            return 1L;
        }

        public static boolean isBotsRelogs() {
            return false;
        }
    }

    /** One getter throws; the others answer normally. */
    public static class ThrowingGetter {
        private static final ThrowingGetter INSTANCE = new ThrowingGetter();

        public static ThrowingGetter get() {
            return INSTANCE;
        }

        public boolean isMaceEnabled() {
            throw new IllegalStateException("getter blew up");
        }

        public boolean isSpearEnabled() {
            return true;
        }
    }

    /** The static accessor itself throws. */
    public static class ThrowingGet {
        public static ThrowingGet get() {
            throw new IllegalStateException("settings could not be loaded");
        }

        public boolean isMaceEnabled() {
            return false;
        }
    }

    /** The static accessor is gone (only instance getters remain). */
    public static class NoGet {
        private boolean maceEnabled = true;
        private static boolean staticNotASetting = true;

        public boolean isMaceEnabled() {
            return maceEnabled;
        }
    }

    /** The static accessor returns null (settings not loaded yet). */
    public static class NullGet {
        public static NullGet get() {
            return null;
        }

        public boolean isMaceEnabled() {
            return false;
        }
    }
}
