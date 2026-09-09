package com.betterself.growth.town.companion.application;

import com.betterself.growth.town.companion.adapters.RoutingResidentMind;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every capability on {@link ResidentMind} has a default implementation that throws, so that adding a
 * new one does not break the dozen test doubles that will never implement it. The cost of that choice
 * lands on the decorators: a wrapper that forgets to forward a method still compiles, still passes its
 * own tests, and silently turns the capability off for everything behind it.
 *
 * <p>That is not hypothetical. Both decorators below once failed to forward {@code planDay}. Neither
 * looked wrong, and a full accelerated day ran with every resident's day plan recorded as
 * "unavailable" - the feature was built, wired, tested in isolation, and dead in the only run that
 * mattered. This test is the guard: a decorator must forward everything, and the compiler cannot ask.
 */
class ResidentMindDecoratorTest {

    @Test
    void everyDecoratorForwardsEveryCapability() {
        for (Class<?> decorator : List.of(RoutingResidentMind.class, recordingMind()))
            assertThat(declaredNames(decorator))
                .as("%s must forward every ResidentMind method; a missing one silently disables it", decorator.getSimpleName())
                .containsAll(capabilityNames());
    }

    /** The recording wrapper inside the accelerated runner - private and nested, but the same hazard. */
    private static Class<?> recordingMind() {
        return Arrays.stream(AcceleratedTownRunner.class.getDeclaredClasses())
            .filter(c -> c.getSimpleName().equals("RecordingMind")).findFirst()
            .orElseThrow(() -> new AssertionError("AcceleratedTownRunner.RecordingMind is gone - update this guard rather than deleting it"));
    }

    private static List<String> capabilityNames() {
        return Stream.of(ResidentMind.class.getDeclaredMethods())
            .filter(m -> !Modifier.isStatic(m.getModifiers()))
            .map(Method::getName).distinct().sorted().toList();
    }

    private static List<String> declaredNames(Class<?> type) {
        return Stream.of(type.getDeclaredMethods()).map(Method::getName).distinct().toList();
    }
}
