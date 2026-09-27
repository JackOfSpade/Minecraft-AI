package dev.spawnbotswrapper.inhabitants.mc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AdapterBackendTest {

    enum Choice {AUTO, CLASS, COMMAND}

    enum Unrelated {ONE, TWO}

    public static class StringSetter {
        String received;

        public void setSpawnBackend(String value) {
            received = value;
        }
    }

    public static class EnumSetter {
        Choice received;

        public void setBackend(Choice value) {
            received = value;
        }
    }

    public static class NoSetter {
        public void setSomethingElse(String value) {
            fail("must not be called");
        }
    }

    public static class WrongEnum {
        public void setBackendPreference(Unrelated value) {
            fail("must not be called");
        }
    }

    public static class Throwing {
        public void setSpawnBackend(String value) {
            throw new IllegalStateException("refused");
        }
    }

    public static class TwoArguments {
        public void setBackend(String a, String b) {
            fail("must not be called");
        }
    }

    public static class NotASetter {
        public void backend(String value) {
            fail("must not be called");
        }

        public String getBackend() {
            return "AUTO";
        }
    }

    @Test
    void passesAStringToAStringSetter() {
        StringSetter adapter = new StringSetter();
        assertTrue(AdapterBackend.apply(adapter, "COMMAND"));
        assertEquals("COMMAND", adapter.received);
    }

    @Test
    void mapsTheNameOntoAnEnumSetterIgnoringCase() {
        EnumSetter adapter = new EnumSetter();
        assertTrue(AdapterBackend.apply(adapter, "class"));
        assertEquals(Choice.CLASS, adapter.received);
        assertTrue(AdapterBackend.apply(adapter, "AUTO"));
        assertEquals(Choice.AUTO, adapter.received);
    }

    @Test
    void anAdapterWithoutASetterIsNotAnError() {
        assertFalse(AdapterBackend.apply(new NoSetter(), "AUTO"));
        assertFalse(AdapterBackend.apply(new NotASetter(), "AUTO"));
        assertFalse(AdapterBackend.apply(new TwoArguments(), "AUTO"));
        assertFalse(AdapterBackend.apply(new Object(), "AUTO"));
    }

    @Test
    void anEnumWithoutAMatchingConstantIsNotApplied() {
        assertFalse(AdapterBackend.apply(new WrongEnum(), "COMMAND"));
        EnumSetter adapter = new EnumSetter();
        assertFalse(AdapterBackend.apply(adapter, "NONSENSE"));
        assertNull(adapter.received);
    }

    @Test
    void aSetterThatThrowsIsReportedAsNotApplied() {
        assertFalse(AdapterBackend.apply(new Throwing(), "AUTO"));
    }

    @Test
    void nullsAreHarmless() {
        assertFalse(AdapterBackend.apply(null, "AUTO"));
        assertFalse(AdapterBackend.apply(new StringSetter(), null));
    }
}
