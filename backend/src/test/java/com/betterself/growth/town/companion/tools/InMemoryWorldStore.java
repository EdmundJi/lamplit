package com.betterself.growth.town.companion.tools;

import com.betterself.growth.town.companion.application.WorldStore;
import com.betterself.growth.town.companion.domain.CompanionWorld;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * An entirely in-process {@link WorldStore}: every {@link CompanionWorld} lives only in this
 * object's heap and disappears when the JVM exits. No JDBC, no MySQL, no transaction, no shared
 * table - see {@code JdbcWorldStore} for what this deliberately does NOT do.
 *
 * This is the isolation boundary for the accelerated runner (see AcceleratedTownRunner): as long
 * as the runner is only ever wired to this store (never to JdbcWorldStore / the real Spring
 * DataSource), it is architecturally impossible for it to touch {@code town_companion_world} or
 * any other production table, no matter how many simulated days it drives through.
 */
public final class InMemoryWorldStore implements WorldStore {
    private final Map<Long, CompanionWorld> worlds = new ConcurrentHashMap<>();
    private final Map<Long, String> timezones = new ConcurrentHashMap<>();

    /**
     * Seeds a world directly, bypassing {@code CompanionService.join()} (which always mints a
     * random UUID as the world id). The accelerated runner needs a fixed, caller-chosen world id
     * instead, so that two runs with the same inputs are byte-for-byte comparable.
     */
    public synchronized void seed(long userId, CompanionWorld world) {
        worlds.put(userId, world);
        timezones.put(userId, world.timezone);
    }

    @Override
    public synchronized CompanionWorld read(long userId) {
        return worlds.get(userId);
    }

    @Override
    public synchronized CompanionWorld update(long userId, Supplier<CompanionWorld> initial, UnaryOperator<CompanionWorld> operation) {
        CompanionWorld w = worlds.get(userId);
        if (w == null) {
            if (initial == null) throw new IllegalStateException("No world for user " + userId + " - call seed() first");
            w = initial.get();
            worlds.put(userId, w);
        }
        w = operation.apply(w);
        worlds.put(userId, w);
        return w;
    }

    @Override
    public boolean ownsTask(long userId, String taskId) {
        // The accelerated runner never has a real user_task row to point at; focus intents are
        // simply not exercised by the scripted avatar actions this runner submits.
        return false;
    }

    @Override
    public String timezone(long userId) {
        return timezones.getOrDefault(userId, "Asia/Shanghai");
    }
}
