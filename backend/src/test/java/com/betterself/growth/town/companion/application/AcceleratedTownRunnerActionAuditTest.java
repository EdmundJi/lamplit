package com.betterself.growth.town.companion.application;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct, offline test of {@link AcceleratedTownRunner}'s per-action offered/selected/applied/
 * rejected/failed/unsupported accounting - the observability deliverable this batch was asked for
 * ("哪个动作被提供了多少次、被选了多少次、被拒了多少次"). Builds hand-made rows in exactly the shape
 * {@code AcceleratedTownRunner.RecordingMind.capture()} produces (plus the "outcome" field
 * {@link ResidentDirector#setOutcomeListener} adds) rather than running a real model - no network,
 * no credentials, no token spend, and no dependency on any specific day's live-model behaviour.
 */
class AcceleratedTownRunnerActionAuditTest {
    private static ResidentMind.Context contextWithActions(List<String> availableActions) {
        return new ResidentMind.Context(null,null,null,null,null,
            List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),
            null,null,null,List.of(),
            null,null,availableActions,null,List.of(),false,List.of(),
            null,null,null,null,null);
    }
    private static Map<String,Object> row(String callType,Object input,Object output,String outcome){
        Map<String,Object> row=new LinkedHashMap<>();
        row.put("callType",callType);row.put("input",input);row.put("output",output);row.put("outcome",outcome);
        return row;
    }

    @Test void offeredCountsEveryAvailableActionRegardlessOfWhatWasPicked() {
        // Exactly item 2's own shape ("invite 被提供 96 次、选中 0 次"): three decision calls each
        // offer the same three-action menu, but only one of them is ever picked.
        var menu = List.of("observe","rest","invite");
        List<Map<String,Object>> calls = new ArrayList<>();
        calls.add(row("decision", contextWithActions(menu), new ResidentMind.Decision("observe","street",null,"r","",List.of(),null,null), "applied"));
        calls.add(row("decision", contextWithActions(menu), new ResidentMind.Decision("observe","street",null,"r","",List.of(),null,null), "applied"));
        calls.add(row("decision", contextWithActions(menu), new ResidentMind.Decision("rest","home",null,"r","",List.of(),null,null), "applied"));

        var audit = AcceleratedTownRunner.buildActionAudit(calls);

        var invite = audit.stream().filter(r->"decision".equals(r.get("callType"))&&"invite".equals(r.get("action"))).findFirst().orElseThrow();
        assertThat(invite.get("offered")).isEqualTo(3);
        assertThat(invite.get("selected")).isEqualTo(0);
        var observe = audit.stream().filter(r->"decision".equals(r.get("callType"))&&"observe".equals(r.get("action"))).findFirst().orElseThrow();
        assertThat(observe.get("offered")).isEqualTo(3);
        assertThat(observe.get("selected")).isEqualTo(2);
        assertThat(observe.get("applied")).isEqualTo(2);
        assertThat(observe.get("rejected")).isEqualTo(0);
    }

    @Test void selectedActionNotOnTheMenuStillCountsAsSelectedAndRejectedWithoutInflatingItsOfferedCount() {
        // Exactly this batch's own dominant finding: the model answers "continue", which this call's
        // own availableActions never listed (offered stays 0 for it), and the listener says rejected.
        var menu = List.of("observe","rest","sleep");
        List<Map<String,Object>> calls = new ArrayList<>();
        calls.add(row("decision", contextWithActions(menu), new ResidentMind.Decision("continue","cafe",null,"r","",List.of(),null,null), "rejected"));

        var audit = AcceleratedTownRunner.buildActionAudit(calls);

        var continueRow = audit.stream().filter(r->"decision".equals(r.get("callType"))&&"continue".equals(r.get("action"))).findFirst().orElseThrow();
        assertThat(continueRow.get("offered")).isEqualTo(0);
        assertThat(continueRow.get("selected")).isEqualTo(1);
        assertThat(continueRow.get("rejected")).isEqualTo(1);
        assertThat(continueRow.get("applied")).isEqualTo(0);
        for(String neverOffered:menu)
            assertThat(audit).filteredOn(r->"decision".equals(r.get("callType"))&&neverOffered.equals(r.get("action")))
                .singleElement().satisfies(r->assertThat(r.get("offered")).isEqualTo(1));
    }

    @Test void reactMenuIsWhateverThatCallActuallyOfferedAndCallTypesWithoutAMenuStillGetATotalsRow() {
        List<Map<String,Object>> calls = new ArrayList<>();
        // Not a constant any more: invite is among the answers only when somebody is standing there
        // and there is something to be asked into, so the menu comes off each call's own record. A
        // hardcoded three would report invite as offered 0 times while it was being chosen - the same
        // table lying, just in the other direction.
        calls.add(row("react", reactMenu(List.of("greet","join","none")), new ResidentMind.ReactDraft("greet","r",List.of()), "applied"));
        calls.add(row("react", reactMenu(List.of("greet","join","none")), new ResidentMind.ReactDraft("none","r",List.of()), "applied"));
        calls.add(row("summary", null, null, "applied"));
        calls.add(row("dayplan", null, null, "rejected"));
        calls.add(row("explain", null, null, "unsupported"));
        calls.add(row("reflect", null, null, "failed"));

        var audit = AcceleratedTownRunner.buildActionAudit(calls);

        var greet = audit.stream().filter(r->"react".equals(r.get("callType"))&&"greet".equals(r.get("action"))).findFirst().orElseThrow();
        assertThat(greet.get("offered")).isEqualTo(2);assertThat(greet.get("selected")).isEqualTo(1);assertThat(greet.get("applied")).isEqualTo(1);
        var join = audit.stream().filter(r->"react".equals(r.get("callType"))&&"join".equals(r.get("action"))).findFirst().orElseThrow();
        assertThat(join.get("offered")).isEqualTo(2);assertThat(join.get("selected")).isEqualTo(0);
        assertThat(audit).as("这两次都没人可邀请，invite 就不该出现在这张表里")
            .filteredOn(r->"react".equals(r.get("callType"))&&"invite".equals(r.get("action"))).isEmpty();

        // Call kinds with no menu of their own still surface a callType-level "*" total row, so
        // summary/dayplan/explain/reflect's applied/rejected/failed/unsupported counts are visible
        // even though there is nothing to break them down by action.
        assertThat(rowFor(audit,"summary")).containsEntry("selected",1).containsEntry("applied",1);
        assertThat(rowFor(audit,"dayplan")).containsEntry("selected",1).containsEntry("rejected",1);
        assertThat(rowFor(audit,"explain")).containsEntry("selected",1).containsEntry("unsupported",1);
        assertThat(rowFor(audit,"reflect")).containsEntry("selected",1).containsEntry("failed",1);
    }

    @Test void reactCountsInviteOnlyForTheCallsThatActuallyOfferedIt() {
        List<Map<String,Object>> calls = new ArrayList<>();
        calls.add(row("react", reactMenu(List.of("greet","join","none")), new ResidentMind.ReactDraft("greet","r",List.of()), "applied"));
        calls.add(row("react", reactMenu(List.of("greet","join","invite","none")), new ResidentMind.ReactDraft("invite","r",List.of()), "applied"));

        var audit = AcceleratedTownRunner.buildActionAudit(calls);

        var invite = audit.stream().filter(r->"react".equals(r.get("callType"))&&"invite".equals(r.get("action"))).findFirst().orElseThrow();
        assertThat(invite.get("offered")).as("只有真的给了这个选项的那一次算数").isEqualTo(1);
        assertThat(invite.get("selected")).isEqualTo(1);
        assertThat(invite.get("applied")).isEqualTo(1);
    }

    @Test void everyOccasionGetsItsOwnRowWithBothAnswersCounted() {
        // The row this whole mechanism exists to produce. lock_door once read 470 offers / 0 taken
        // off this table, and that number measured our timing rather than anybody's indifference -
        // the moment it belongs to came round 7 times in those two days. The question now is how
        // often somebody took it when the moment really came, and that answer only exists if the
        // occasion's key has its own row instead of being swallowed into a callType total.
        List<Map<String,Object>> calls = new ArrayList<>();
        calls.add(row("consider", occasion("lock_door"), new ResidentMind.ConsiderDraft("lock_door","锁上再走",null,List.of()), "applied"));
        calls.add(row("consider", occasion("lock_door"), new ResidentMind.ConsiderDraft("none","没必要",null,List.of()), "applied"));
        calls.add(row("consider", occasion("close_cafe"), new ResidentMind.ConsiderDraft("none","再开一会儿",null,List.of()), "applied"));

        var audit = AcceleratedTownRunner.buildActionAudit(calls);

        var lock = audit.stream().filter(r->"consider".equals(r.get("callType"))&&"lock_door".equals(r.get("action"))).findFirst().orElseThrow();
        assertThat(lock.get("offered")).as("锁门这个时机来了两次").isEqualTo(2);
        assertThat(lock.get("selected")).as("其中一次真锁了").isEqualTo(1);
        var none = audit.stream().filter(r->"consider".equals(r.get("callType"))&&"none".equals(r.get("action"))).findFirst().orElseThrow();
        assertThat(none.get("offered")).as("每一问都摆着一个免费的不做").isEqualTo(3);
        assertThat(none.get("selected")).as("三次里两次答的是不做").isEqualTo(2);
        var close = audit.stream().filter(r->"consider".equals(r.get("callType"))&&"close_cafe".equals(r.get("action"))).findFirst().orElseThrow();
        assertThat(close.get("offered")).isEqualTo(1);assertThat(close.get("selected")).isEqualTo(0);
    }

    private static Map<String,Object> reactMenu(List<String> reactions){
        Map<String,Object> input=new LinkedHashMap<>();input.put("residentId","artist");input.put("reactions",reactions);
        return input;
    }
    private static Map<String,Object> occasion(String key){
        Map<String,Object> input=new LinkedHashMap<>();input.put("residentId","owner");input.put("key",key);
        return input;
    }

    private static Map<String,Object> rowFor(List<Map<String,Object>> audit,String callType){
        return audit.stream().filter(r->callType.equals(r.get("callType"))&&"*".equals(r.get("action"))).findFirst().orElseThrow();
    }
}
