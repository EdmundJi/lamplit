package com.betterself.growth.town.companion.application;

import com.betterself.growth.ai.QwenHttpProvider;
import com.betterself.growth.town.companion.domain.CompanionRules;
import com.betterself.growth.town.companion.domain.ResidentSimulation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AcceleratedTownRunnerBudgetTest {
    private static final Instant NOW=Instant.parse("2026-09-09T06:00:00Z");

    @TempDir Path tempDir;

    @Test void manifestNamesLogicalAndWireCountsAndPostUsageTokenSemantics() throws Exception {
        AcceleratedTownRunner.run(AcceleratedTownRunner.RunConfig.ruleOnly(tempDir,0.001));

        var json=new ObjectMapper().readTree(tempDir.resolve("manifest.json").toFile());
        assertThat(json.path("logicalModelCallsCompleted").asLong()).isZero();
        assertThat(json.path("logicalModelCallsStarted").asLong()).isZero();
        assertThat(json.path("wireModelRequestBudget").asLong()).isZero();
        assertThat(json.path("wireModelRequestsStarted").asLong()).isZero();
        assertThat(json.path("inputTokenStopSemantics").asText())
            .isEqualTo("post_usage_threshold_not_a_preflight_hard_limit");
        assertThat(new ObjectMapper().readTree(tempDir.resolve("model-wire-requests.json").toFile())).isEmpty();
    }

    @Test void logicalCallsAreCountedSeparatelyFromWireRequests(){
        AtomicInteger providerCalls=new AtomicInteger();
        ResidentMind delegate=new ResidentMind(){
            public boolean enabled(){return true;}
            public Decision decide(Context context){providerCalls.incrementAndGet();return decision();}
            public Result<Decision> decideMetered(Context context){providerCalls.incrementAndGet();return new Result<>(decision(),new Usage(10,2));}
        };
        var wireBudget=new QwenHttpProvider.WireRequestBudget(2);
        var guarded=new AcceleratedTownRunner.RecordingMind(delegate,new ArrayList<>(),wireBudget,1_000_000);

        guarded.decideMetered(context());guarded.decideMetered(context());
        assertThat(providerCalls).hasValue(2);
        assertThat(guarded.logicalCallsStarted()).isEqualTo(2);
        assertThat(wireBudget.started()).isZero();
    }

    @Test void observedInputThresholdStopsTheNextCallWithoutClaimingItIsAPreflightTokenCount(){
        AtomicInteger providerCalls=new AtomicInteger();
        ResidentMind delegate=new ResidentMind(){
            public boolean enabled(){return true;}
            public Decision decide(Context context){throw new UnsupportedOperationException();}
            public Result<Decision> decideMetered(Context context){providerCalls.incrementAndGet();return new Result<>(decision(),new Usage(12,2));}
        };
        var wireBudget=new QwenHttpProvider.WireRequestBudget(10);
        var guarded=new AcceleratedTownRunner.RecordingMind(delegate,new ArrayList<>(),wireBudget,10);

        guarded.decideMetered(context());
        assertThat(guarded.enabled()).isFalse();
        assertThat(providerCalls).hasValue(1);
        assertThat(guarded.logicalCallsStarted()).isEqualTo(1);
        assertThat(wireBudget.started()).isZero();
    }

    private static ResidentMind.Decision decision(){
        return new ResidentMind.Decision("observe","street",null,"先看看","",List.of(),null,null);
    }
    private static ResidentMind.Context context(){
        var world=CompanionRules.join("runner-budget","我","Asia/Shanghai",NOW,true);
        var state=ResidentSimulation.state(world,"artist");
        return new ResidentMind.Context(world.id,"artist",state.revision,0,NOW,"14:00","sunny",
            ResidentSimulation.actor(world,"artist"),state.goal,state.mood,state.thought,state.energy,state.social,state.relationships,
            world.memories.stream().filter(m->m.ownerId().equals("artist")).toList(),List.of(),List.of(),List.of(),List.of());
    }
}
