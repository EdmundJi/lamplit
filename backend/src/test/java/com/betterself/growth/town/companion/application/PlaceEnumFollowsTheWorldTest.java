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
import java.util.Set;
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

    @Test void aPublicBuildingTurnsUpAsAnExactChoiceTheModelCanSelect() {
        CompanionWorld w = CompanionRules.join("place-enum", "我", "Asia/Shanghai", NOW, true);
        var director=new ResidentDirector(new SnapshotStore(w),new DisabledMind(),Clock.fixed(NOW,ZoneOffset.UTC));
        try{
            var context=director.perspective(w,"owner",NOW,List.of());
            assertThat(context.knownPlaces()).extracting(ResidentMind.KnownPlaceView::id).contains("gym");
            assertThat(context.decisionOptions()).anyMatch(option->"gym".equals(option.place())&&option.roomId()!=null);
        }finally{director.close();}
    }

    @Test void theSchemaNeverOffersAPlaceTheSameCallDidNotDescribe() {
        CompanionWorld w = CompanionRules.join("place-enum-2", "我", "Asia/Shanghai", NOW, true);
        var director=new ResidentDirector(new SnapshotStore(w),new DisabledMind(),Clock.fixed(NOW,ZoneOffset.UTC));
        try{
            var context=director.perspective(w,"student",NOW,List.of());
            // Somebody else's house exists as a Location but is not addressable - the model says
            // "home" and a person target is only present for an actual live invitation.
            assertThat(context.decisionOptions()).allSatisfy(option->assertThat(option.place()).doesNotStartWith("home-"));
            assertThat(context.decisionOptions()).filteredOn(option->"home".equals(option.place()))
                .noneMatch(option->option.targetId()!=null&&Set.of("home-owner","home-artist","home-gardener").contains(option.targetId()));
        }finally{director.close();}
    }

    @Test void oneSchemaChoiceSelectsTheWholeLegalActionRoomAndTarget() throws Exception {
        CompanionWorld w=CompanionRules.join("four-level-schema","我","Asia/Shanghai",NOW,true);
        var director=new ResidentDirector(new SnapshotStore(w),new DisabledMind(),Clock.fixed(NOW,ZoneOffset.UTC));
        ResidentMind.Context context;
        try{context=director.perspective(w,"student",NOW,List.of());}finally{director.close();}
        var sleep=context.decisionOptions().stream().filter(option->option.action().equals("sleep")).findFirst().orElseThrow();
        assertThat(sleep.place()).isEqualTo("home");
        assertThat(sleep.roomId()).isEqualTo("home-student-room-student");
        assertThat(sleep.targetId()).isEqualTo("home-student-bed");

        var json=new ObjectMapper().findAndRegisterModules();var provider=new CapturingProvider();
        new QwenResidentMind(provider,json,"qwen",true).decideMetered(context);
        String schema=provider.prompt.get().schemaJson();
        var tree=new ObjectMapper().readTree(schema);
        assertThat(tree.path("required").toString()).contains("choiceId").doesNotContain("roomId","targetId");
        assertThat(tree.path("properties").path("choiceId").path("enum").toString()).contains("\""+sleep.id()+"\"");
        assertThat(schema).doesNotContain("anyOf");
    }

    @Test void fourLevelChoicesStayBoundedInsteadOfDuplicatingTheWholeMatrixInTheSchema() throws Exception {
        CompanionWorld w=CompanionRules.join("four-level-size","我","Asia/Shanghai",NOW,true);
        var json=new ObjectMapper().findAndRegisterModules();
        var director=new ResidentDirector(new SnapshotStore(w),new DisabledMind(),Clock.fixed(NOW,ZoneOffset.UTC));
        try{
            for(String residentId:List.of("owner","student","broker")){
                var context=director.perspective(w,residentId,NOW,List.of());var provider=new CapturingProvider();
                new QwenResidentMind(provider,json,"qwen",true).decideMetered(context);
                int contextChars=json.writeValueAsString(context).length();
                int instructionChars=provider.prompt.get().instruction().length();
                int schemaChars=provider.prompt.get().schemaJson().length();
                System.out.printf("[decision-payload] resident=%s options=%d contextChars=%d instructionChars=%d schemaChars=%d%n",
                    residentId,context.decisionOptions().size(),contextChars,instructionChars,schemaChars);
                assertThat(context.decisionOptions().size()).isBetween(1,160);
                assertThat(contextChars).isLessThan(20_000);
                assertThat(instructionChars).isLessThan(30_000);
                assertThat(schemaChars).as("schema should carry compact choice ids, not repeat full four-level options").isLessThan(3_000);
            }
        }finally{director.close();}
    }

    @Test void aChoiceIdRoundTripsBackToTheExactFourLevelOption(){
        CompanionWorld w=CompanionRules.join("choice-round-trip","我","Asia/Shanghai",NOW,true);
        var director=new ResidentDirector(new SnapshotStore(w),new DisabledMind(),Clock.fixed(NOW,ZoneOffset.UTC));
        ResidentMind.Context context;
        try{context=director.perspective(w,"student",NOW,List.of());}finally{director.close();}
        var sleep=context.decisionOptions().stream().filter(option->option.action().equals("sleep")).findFirst().orElseThrow();
        QwenProvider provider=new QwenProvider(){
            public StructuredResult generateStructured(StructuredPrompt prompt){
                return new StructuredResult("{\"choiceId\":\""+sleep.id()+"\",\"reason\":\"今晚先睡\",\"speech\":\"\",\"evidenceIds\":[],\"projectTitle\":null,\"objectKind\":null}","fake","request",1,1,1);
            }
            public StreamMetadata stream(ChatPrompt p,Consumer<String> consumer){throw new UnsupportedOperationException();}
            public Classification classify(ClassificationPrompt p){throw new UnsupportedOperationException();}
        };
        var decision=new QwenResidentMind(provider,new ObjectMapper().findAndRegisterModules(),"qwen",true).decide(context);
        assertThat(decision.choiceId()).isEqualTo(sleep.id());
        assertThat(ResidentMind.selectedOption(context,decision)).isEqualTo(sleep);
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
