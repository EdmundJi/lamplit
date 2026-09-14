package com.betterself.growth.town.companion.domain;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;
import static org.assertj.core.api.Assertions.assertThat;

/** After a long absence every sleeper's plan ends in the same catch-up tick, so the whole town reaches
 * a junction at once. The queue was sized for six: 21 residents were marked as asked, 12 questions
 * survived, and the other nine were never asked that day. */
class OccasionPopulationTest {
    @Test void everyResidentMarkedAskedStillHasTheirQuestion(){
        Instant now=Instant.parse("2026-09-14T02:33:11Z");
        var w=CompanionRules.join("occasion-population","住客","Asia/Shanghai",now,true);
        w.pendingOccasions.clear();w.askedOccasions.clear();w.projects.clear();
        for(var r:w.residentStates){
            if("self".equals(r.id))continue;
            r.plan=null;
            var a=ResidentSimulation.actor(w,r.id);
            ResidentSimulation.replaceActor(w,r.id,a.place(),"idle","刚醒",now.plusSeconds(300));
        }
        Occasions.scan(w,now);
        Set<String> asked=w.askedOccasions.keySet().stream().filter(k->k.endsWith("|change_work")).map(k->k.substring(0,k.indexOf('|'))).collect(Collectors.toSet());
        Set<String> pending=w.pendingOccasions.stream().filter(p->p.key.equals("change_work")).map(p->p.residentId).collect(Collectors.toSet());
        assertThat(asked).hasSizeGreaterThan(12);
        assertThat(pending).isEqualTo(asked);
    }
}
