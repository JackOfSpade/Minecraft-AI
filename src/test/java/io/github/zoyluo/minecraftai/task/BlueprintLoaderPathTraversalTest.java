package io.github.zoyluo.minecraftai.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BlueprintLoaderPathTraversalTest {
    @Test
    void plainNamesAreValid() {
        assertTrue(BlueprintLoader.isValidBlueprintName("hut_5x5"));
        assertTrue(BlueprintLoader.isValidBlueprintName("small_hut"));
        assertTrue(BlueprintLoader.isValidBlueprintName("My-Blueprint.v2"));
    }

    @Test
    void nullOrBlankIsInvalid() {
        assertFalse(BlueprintLoader.isValidBlueprintName(null));
        assertFalse(BlueprintLoader.isValidBlueprintName(""));
    }

    @Test
    void pathSeparatorsAreInvalid() {
        assertFalse(BlueprintLoader.isValidBlueprintName("../../../../etc/passwd"));
        assertFalse(BlueprintLoader.isValidBlueprintName("sub/dir"));
        assertFalse(BlueprintLoader.isValidBlueprintName("sub\\dir"));
        assertFalse(BlueprintLoader.isValidBlueprintName("..\\..\\windows"));
    }

    @Test
    void dotDotSegmentIsInvalidEvenWithoutSeparators() {
        // ".." alone contains no separator character but must still be rejected outright.
        assertFalse(BlueprintLoader.isValidBlueprintName(".."));
        assertFalse(BlueprintLoader.isValidBlueprintName("foo..bar"));
    }

    @Test
    void absolutePathsAreInvalid() {
        assertFalse(BlueprintLoader.isValidBlueprintName("/etc/passwd"));
        assertFalse(BlueprintLoader.isValidBlueprintName("C:\\secrets"));
    }
}
