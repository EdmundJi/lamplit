package com.betterself.growth.town.companion.domain;

import com.betterself.growth.town.companion.domain.CompanionWorld.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

/** "street" is one room for the whole outdoors, and every walker's place is "street" for the whole
 * trip. Live, 青叔 and 阿禾 each stepped out of homes 737px apart and were greeted into a conversation
 * in the same tick. Being on the street together has to mean being near each other on it. */
class StreetEncounterDistanceTest {
    private final Instant now=Instant.parse("2026-09-14T02:33:11Z");

    @Test void walkersWhoJustLeftFarApartHomesAreNotFaceToFace(){
        var w=world();
        walk(w,"owner","home-owner","cafe");
        walk(w,"gardener","home-gardener","academy");
        w.simulatedAt=now.plusSeconds(6);
        assertThat(ResidentSimulation.sameRoom(w,"owner","gardener")).isFalse();

        CompanionRules.advance(w,now.plusSeconds(12));
        assertThat(w.pendingEncounters).noneMatch(p->pair(p,"owner","gardener"));
        assertThat(w.conversations).noneMatch(c->c.participantIds.contains("owner")&&c.participantIds.contains("gardener"));
    }

    @Test void flatMatesSettingOutTogetherStillMeetOnTheStreet(){
        var w=world();
        walk(w,"botanist","home-botanist","cafe");
        walk(w,"trainer","home-botanist","garden");
        w.simulatedAt=now.plusSeconds(1);
        assertThat(ResidentSimulation.sameRoom(w,"botanist","trainer")).isTrue();
    }

    @Test void peopleStandingOnTheStreetAreTogetherAsBefore(){
        var w=world();
        ResidentSimulation.replaceActor(w,"owner","street","idle","在街上站着",now.plusSeconds(300));
        ResidentSimulation.replaceActor(w,"gardener","street","idle","在街上站着",now.plusSeconds(300));
        assertThat(ResidentSimulation.sameRoom(w,"owner","gardener")).isTrue();
    }

    private CompanionWorld world(){
        var w=CompanionRules.join("street-distance","住客","Asia/Shanghai",now,true);
        w.conversations.clear();w.pendingEncounters.clear();w.encounterCooldowns.clear();
        w.updatedAt=now;w.simulatedAt=now;
        return w;
    }
    private void walk(CompanionWorld w,String id,String from,String to){
        var r=ResidentSimulation.state(w,id);
        r.suspendedAction=null;r.lastSocialAt=null;r.travelFrom=from;
        r.plan=new Plan("trip-"+id,"travel",to,null,"出门",now,now.plusSeconds(ResidentSimulation.travelSeconds(from,to)));
        ResidentSimulation.replaceActor(w,id,"street","walk","出门",r.plan.endsAt());
    }
    private static boolean pair(PendingEncounter p,String a,String b){
        return (p.residentId.equals(a)&&p.otherId.equals(b))||(p.residentId.equals(b)&&p.otherId.equals(a));
    }
}
