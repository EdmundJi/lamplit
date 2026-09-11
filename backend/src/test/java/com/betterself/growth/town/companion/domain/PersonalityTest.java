package com.betterself.growth.town.companion.domain;

import com.betterself.growth.town.companion.domain.CompanionWorld.ResidentState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import static org.assertj.core.api.Assertions.*;

/** Personality is numbers, not prose, and those numbers are each resident's own writable state -
 * fields on ResidentState, not a lookup by id - so a future reflect() has somewhere to nudge them.
 * These tests check: the four residents' starting values are genuinely pulled apart on at least one
 * axis (not four variations on "mild and friendly"); every dimension actually changes what a rule
 * decides; the self-heal never resets a value that has already been seeded, even a low one; and a
 * value written onto a resident's own state survives a save/load round trip. The decisive proof that
 * this produces different people, not just different numbers, is
 * theSameEventStreamLeavesFourDifferentMemoryTrails below. */
class PersonalityTest {
    @Test void startingValuesArePulledApartNotClusteredAroundNeutral() {
        Personality owner=seeded("owner"), student=seeded("student"), artist=seeded("artist"), gardener=seeded("gardener");
        // owner and student sit near opposite ends of extroversion.
        assertThat(Math.abs(owner.extroversion()-student.extroversion())).isGreaterThanOrEqualTo(50);
        // owner and artist sit near opposite ends of conscientiousness - the axis the shopkeeper's
        // forthcoming duty-of-care behavior is expected to read and a future reflect() to move.
        // artist's low score is a genuine design choice, not a placeholder: "higher is better" is
        // deliberately not how this dimension works.
        assertThat(Math.abs(owner.conscientiousness()-artist.conscientiousness())).isGreaterThanOrEqualTo(50);
        assertThat(owner.conscientiousness()).isGreaterThan(70);
        assertThat(artist.conscientiousness()).isLessThan(30);
        // artist and gardener split hard on both sensitivity and emotional volatility.
        assertThat(Math.abs(artist.sensitivity()-gardener.sensitivity())).isGreaterThanOrEqualTo(50);
        assertThat(Math.abs(artist.volatility()-gardener.volatility())).isGreaterThanOrEqualTo(50);
    }

    @Test void unknownOrAvatarIdsFallBackToTheSameNeutralPersonality() {
        assertThat(seeded("self")).isEqualTo(seeded("nobody-in-particular"));
        assertThat(seeded("self").extroversion()).isEqualTo(50);
    }

    @Test void everyDimensionActuallyChangesWhatARuleReads() {
        Personality owner=seeded("owner"), student=seeded("student"), artist=seeded("artist"), gardener=seeded("gardener");
        // extroversion: seeks company sooner (higher threshold), recovers its appetite for another
        // conversation faster, and re-approaches the same person sooner.
        assertThat(owner.socialThreshold()).isGreaterThan(student.socialThreshold());
        assertThat(owner.socialRefractorySeconds()).isLessThan(student.socialRefractorySeconds());
        assertThat(owner.inviteCooldownSeconds()).isLessThanOrEqualTo(student.inviteCooldownSeconds());
        // conscientiousness: the responsible resident essentially never abandons an unfinished
        // project partway through and contributes a bit more per attempt; the least responsible does.
        assertThat(owner.abandonThreshold()).isZero();
        assertThat(artist.abandonThreshold()).isGreaterThan(0);
        assertThat(owner.diligenceBonus()).isGreaterThan(artist.diligenceBonus());
        // volatility: a single relational event moves a volatile resident's own affection number
        // more than it moves a steady resident's.
        assertThat(artist.intensity()).isGreaterThan(gardener.intensity());
    }

    @Test void seedOnlyFillsInAnUnseededResidentNeverOverwritesAnAlreadySeededOrDriftedValue() {
        // A resident freshly deserialized from an old save (no personality fields at all) is missing.
        ResidentState fresh=new ResidentState();fresh.id="owner";
        assertThat(fresh.personalitySeeded).isFalse();
        Personality.of(fresh);
        assertThat(fresh.personalitySeeded).isTrue();
        assertThat(fresh.conscientiousness).isEqualTo(85);
        // Calling it again - as every tick does - must not reset a value a future reflect() has since
        // written, even a low one that happens to look like it "could" be unseeded by coincidence.
        fresh.conscientiousness=2;
        Personality.of(fresh);
        assertThat(fresh.conscientiousness).isEqualTo(2);
        // The artist's own genuinely low initial conscientiousness (25) must never be mistaken for
        // "not yet seeded" and silently reset upward.
        ResidentState artist=new ResidentState();artist.id="artist";
        Personality.of(artist);
        assertThat(artist.conscientiousness).isEqualTo(25);
        Personality.of(artist);
        assertThat(artist.conscientiousness).isEqualTo(25);
    }

    @Test void aValueWrittenOntoAResidentsOwnStateSurvivesASaveLoadRoundTrip() throws Exception {
        // Simulates the shape of the future reflect() write this batch only makes room for: a
        // resident's own conscientiousness nudged down after a bad day, then the world saved and
        // reloaded exactly the way JdbcWorldStore does (plain Jackson, no special config).
        Instant now=Instant.parse("2026-09-08T06:00:00Z");
        CompanionWorld world=CompanionRules.join("persist-personality","住客","Asia/Shanghai",now);
        ResidentState owner=ResidentSimulation.state(world,"owner");
        Personality.of(owner); // first touch seeds it, same as any tick would
        assertThat(owner.conscientiousness).isEqualTo(85);
        owner.conscientiousness=41; // stand-in for a future reflect() writing a lower value
        ObjectMapper json=new ObjectMapper().findAndRegisterModules();
        String saved=json.writeValueAsString(world);
        CompanionWorld reloaded=json.readValue(saved,CompanionWorld.class);
        ResidentState reloadedOwner=ResidentSimulation.state(reloaded,"owner");
        // The written value survived - it was not reset back to the 85 the initial-value table holds.
        assertThat(reloadedOwner.conscientiousness).isEqualTo(41);
        assertThat(reloadedOwner.personalitySeeded).isTrue();
        assertThat(Personality.of(reloadedOwner).conscientiousness()).isEqualTo(41);
    }

    @Test void theSameEventStreamLeavesFourDifferentMemoryTrails() {
        Instant start=Instant.parse("2026-09-08T06:00:00Z");
        CompanionWorld world=CompanionRules.join("divergence-1","住客","Asia/Shanghai",start);
        // CompanionRules.join's own warm-start leaves owner and artist mid-conversation about this
        // exact project (ResidentSeed.initialize's own closing startConversation call) - step() skips
        // a resident's plan entirely while they are in one (see ResidentSimulation.step's
        // activeConversation guard), so the injected "create" plan below would otherwise never run.
        world.conversations.clear();
        // "create"/"help" - and so any project contribution, and so witnessContribution() itself - are
        // decision actions only the model ever chooses (see ResidentSimulation.DECISION_ACTIONS and its
        // one caller, applyDecision); nothing rule-only ever schedules them. PersonalityDriftTest's own
        // "project_complete"/"noticed_detail"/"missed_detail" tests document the same fact directly - a
        // full simulated day with no model measured zero such events - and pin their mount points the
        // same way this authors one below: as the resident's own already-decided action, exactly what a
        // real applied model decision would have produced, rather than waiting on a purely rule-driven
        // loop to invent a contribution it structurally never will. Gardener is moved to the cafe so his
        // low sensitivity is a real, witnessed-but-forgotten case below, not a vacuous one.
        ResidentSimulation.replaceActor(world,"gardener","cafe","observe","过来看看邻居们在忙什么",start.plusSeconds(300));
        ResidentState owner=ResidentSimulation.state(world,"owner");
        CompanionWorld.Project project=world.projects.get(0); // "reading-night": owner's own project, place=cafe
        owner.plan=new CompanionWorld.Plan("qa-create","create","cafe",project.id,"再添一点",start,start.plusSeconds(6));
        ResidentSimulation.replaceActor(world,"owner","cafe","create","再添一点",start.plusSeconds(6));

        for(int second=6;second<=1800;second+=6)CompanionRules.advance(world,start.plusSeconds(second));
        List<String> ids=List.of("owner","student","artist","gardener");
        Map<String,Set<String>> byResident=new HashMap<>();
        for(String id:ids)byResident.put(id, world.memories.stream().filter(m->m.ownerId().equals(id))
            .map(m->m.sourceType()+":"+m.text()).collect(Collectors.toSet()));
        // No two residents wrote down an identical memory set out of the same shared thirty minutes -
        // not just a different count, an actually different collection of texts.
        for(int i=0;i<ids.size();i++)for(int j=i+1;j<ids.size();j++)
            assertThat(byResident.get(ids.get(i))).as(ids.get(i)+" vs "+ids.get(j)).isNotEqualTo(byResident.get(ids.get(j)));
        // The concrete case the task calls out: some events leave no trace at all for some people.
        // witnessContribution() is the mechanism: the same "someone else just added to their project"
        // moment is witnessed by everyone present, but gardener's low sensitivity means he never
        // bothers writing it down at all, while the two high-sensitivity residents do.
        assertThat(witnessedContributionMemories(world,"gardener")).isZero();
        assertThat(witnessedContributionMemories(world,"student")).isGreaterThan(0);
        assertThat(witnessedContributionMemories(world,"artist")).isGreaterThan(0);
    }

    /** Counts only memories written by witnessContribution() in ResidentSimulation - identified by
     * its two fixed text prefixes - not every "observed" memory (celebration moments, for instance,
     * are written unconditionally for every contributor and are not part of this claim). */
    private static long witnessedContributionMemories(CompanionWorld world,String id) {
        return world.memories.stream().filter(m->m.ownerId().equals(id)&&m.sourceType().equals("observed")&&!m.sourceId().equals(id)
            &&(m.text().startsWith("我看见")||m.text().startsWith("隐约感觉到"))).count();
    }

    /** A fresh, never-before-seen ResidentState for id, seeded once via Personality.of - mirrors how
     * ResidentSimulation always encounters a resident (through its own mutable state), never through
     * a lookup by bare id. */
    private static Personality seeded(String id) {
        ResidentState r=new ResidentState();r.id=id;
        return Personality.of(r);
    }
}
