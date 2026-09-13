package com.betterself.growth.town.companion.application;

import com.betterself.growth.ai.QwenProvider;
import com.betterself.growth.town.companion.adapters.QwenResidentMind;
import com.betterself.growth.town.companion.domain.CompanionRules;
import com.betterself.growth.town.companion.domain.CompanionWorld;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The other half of {@link KnownPlacesTest}: that the JSON schema actually sent to the model is built
 * from the same derived list, not from a second hard-coded copy that merely happens to agree today.
 *
 * <p>This is the wiring docs/04-decisions.md asks for - 「定位是"规则收窄候选集 + 一次调用"…用 JSON
 * schema 的动态 enum 锁死，非法项压根不在选项里」 - and it is worth pinning at the payload rather than at
 * {@code knownPlaces()}, because the failure it replaces was precisely two lists in two files drifting
 * apart: the {@code action} enum next door had already been made dynamic while {@code place} stayed
 * literal, and nothing noticed.
 */
class PlaceEnumFollowsTheWorldTest {
    private static final Instant NOW = Instant.parse("2026-09-09T06:00:00Z");

    /** Captures the prompt instead of calling anything. No network, no key, no tokens. */
    private static final class CapturingProvider implements QwenProvider {
        final AtomicReference<StructuredPrompt> prompt = new AtomicReference<>();
        public StructuredResult generateStructured(StructuredPrompt p) {
            prompt.set(p);
            return new StructuredResult("{\"action\":\"observe\",\"place\":\"street\",\"targetId\":null,"
                + "\"reason\":\"先看看\",\"speech\":\"\",\"evidenceIds\":[]}", "fake", "request", 10, 5, 1);
        }
        public StreamMetadata stream(ChatPrompt p, Consumer<String> consumer) { throw new UnsupportedOperationException(); }
        public Classification classify(ClassificationPrompt p) { throw new UnsupportedOperationException(); }
    }

    private static String schemaFor(CompanionWorld w, String residentId) {
        var json = new ObjectMapper().findAndRegisterModules();
        var provider = new CapturingProvider();
        var mind = new QwenResidentMind(provider, json, "qwen", true);
        var director = new ResidentDirector(new SnapshotStore(w), new DisabledMind(), Clock.fixed(NOW, ZoneOffset.UTC));
        try {
            mind.decideMetered(director.perspective(w, residentId, NOW, List.of()));
        } finally { director.close(); }
        return provider.prompt.get().schemaJson();
    }

    @Test void aBuildingAddedToTheWorldTurnsUpInTheSchemaTheModelIsHeldTo() {
        CompanionWorld w = CompanionRules.join("place-enum", "我", "Asia/Shanghai", NOW, true);
        w.locations.add(new CompanionWorld.Location("bathhouse", "bathhouse", null));

        String schema = schemaFor(w, "owner");
        assertThat(schema).as("the new building must be nameable").contains("\"bathhouse\"");
        assertThat(schema).contains("\"cafe\"", "\"street\"", "\"garden\"", "\"home\"");
    }

    @Test void theSchemaNeverOffersAPlaceTheSameCallDidNotDescribe() {
        CompanionWorld w = CompanionRules.join("place-enum-2", "我", "Asia/Shanghai", NOW, true);
        String schema = schemaFor(w, "student");

        // Somebody else's house exists as a Location but is not addressable - the model says "home" and
        // the rules resolve it to the speaker's own. If it ever leaks into the enum, the model can name
        // a destination the rules will then silently reject, which is the exact waste the dynamic enum
        // exists to remove.
        assertThat(schema).doesNotContain("home-owner", "home-artist", "home-gardener");
    }

    private static final class DisabledMind implements ResidentMind {
        public boolean enabled() { return false; }
        public Decision decide(Context context) { throw new UnsupportedOperationException(); }
    }
    private record SnapshotStore(CompanionWorld world) implements WorldStore {
        public CompanionWorld read(long userId) { return world; }
        public CompanionWorld update(long userId, java.util.function.Supplier<CompanionWorld> initial, java.util.function.UnaryOperator<CompanionWorld> change) { return change.apply(world); }
        public boolean ownsTask(long userId, String taskId) { return false; }
        public String timezone(long userId) { return world.timezone; }
    }
}
