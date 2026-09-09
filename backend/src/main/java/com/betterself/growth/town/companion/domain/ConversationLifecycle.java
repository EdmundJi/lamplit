package com.betterself.growth.town.companion.domain;

import java.time.*;
import java.util.*;
import static com.betterself.growth.town.companion.domain.CompanionWorld.*;
import static com.betterself.growth.town.companion.domain.ResidentSimulation.*;

/**
 * Independently implemented Java conversation lifecycle, informed by AI Town's per-operation
 * identity and typing ownership (a16z-infra/ai-town, commit 8e05997f). No Convex code is embedded.
 * The model proposes only its current speaker's turn; rules commit speech and its effects.
 */
public final class ConversationLifecycle {
    private ConversationLifecycle() {}
    public static final int OPERATION_TIMEOUT_SECONDS=45;
    public record Operation(String conversationId,String speakerId,long turnVersion,String operationId,long intentRevision,Instant startedAt) {}
    public record Utterance(String text,boolean leave,String feeling,String stance,String adjustment,List<String> evidenceIds,String emoji,String workAction,String workTarget) {
        public Utterance(String text,boolean leave,String feeling,String stance,String adjustment,List<String> evidenceIds,String emoji){this(text,leave,feeling,stance,adjustment,evidenceIds,emoji,"none",null);}
        public Utterance(String text,boolean leave,String feeling,String stance,String adjustment,List<String> evidenceIds){this(text,leave,feeling,stance,adjustment,evidenceIds,null);}
    }
    public record Recollection(String text,String feeling,List<String> evidenceIds) {}

    public static boolean tick(CompanionWorld w,Conversation c,Instant now) {
        if("rules".equals(c.mode))return false;
        if(!"active".equals(c.status))return true;
        if(!together(w,c)){finish(w,c,now,"对方已经离开了");return true;}
        if(Duration.between(c.startedAt,now).getSeconds()>240||c.turns.size()>=8){finish(w,c,now,"聊过一会儿，各自继续今天的事");return true;}
        if("model".equals(c.mode)) {
            if(c.pendingOperationId!=null&&(w.intentRevision!=c.pendingIntentRevision||Duration.between(c.operationStartedAt,now).getSeconds()>OPERATION_TIMEOUT_SECONDS))
                fallback(w,c,now,"这次组织语言花了太久，先自然结束这一段");
            else if(c.pendingOperationId==null&&(!w.modelConversationsEnabled||w.modelRetryAfter!=null&&now.isBefore(w.modelRetryAfter)||Duration.between(c.updatedAt,now).getSeconds()>OPERATION_TIMEOUT_SECONDS))
                fallback(w,c,now,"先按自己的习惯聊完这一段");
            else return true;
        }
        if("fallback".equals(c.mode)&&Duration.between(c.updatedAt,now).getSeconds()>=8) {
            String speaker=c.nextSpeakerId==null?c.participantIds.getLast():c.nextSpeakerId;
            String line=c.turns.isEmpty()?switch(speaker){case "student"->"我先把这页看完。";case "owner"->"等一下，我先看着手上这杯。";case "artist"->"刚才那个颜色……算了，等会儿再说。";case "gardener"->"我先去看看那盆苗。";default->"我先忙手上这点。";}:"嗯，先这样吧。";
            appendSpeech(w,c,speaker,line,"rules",List.of(),null,now);
            finish(w,c,now,"这段谈话先告一段落");
        }
        return true;
    }

    public static Operation reserveTurn(CompanionWorld w,Conversation c,Instant now) {
        if(!"active".equals(c.status)||!"model".equals(c.mode)||c.pendingOperationId!=null||!together(w,c))return null;
        if(!c.turns.isEmpty()&&Duration.between(c.updatedAt,now).getSeconds()<6)return null;
        if(c.nextSpeakerId==null)c.nextSpeakerId=c.turns.isEmpty()?c.participantIds.getFirst():other(c,c.turns.getLast().speakerId());
        c.pendingOperationId="dialogue-op-"+(++w.eventSequence);c.pendingSpeakerId=c.nextSpeakerId;c.operationStartedAt=now;c.pendingIntentRevision=w.intentRevision;
        w.revision++;
        return new Operation(c.id,c.pendingSpeakerId,c.turnVersion,c.pendingOperationId,w.intentRevision,now);
    }

    public static boolean applyTurn(CompanionWorld w,Operation op,Utterance reply,Instant now) {
        Conversation c=find(w,op.conversationId());
        if(!validPending(w,c,op,now)||reply==null||reply.text()==null||reply.text().isBlank()||reply.text().length()>360
            ||reply.feeling()==null||reply.feeling().length()>30||!Set.of("none","consider","accept","decline","adjust").contains(reply.stance()==null?"":reply.stance())
            ||!validEvidence(w,op.speakerId(),reply.evidenceIds())||!EmojiSequence.valid(reply.emoji()))return false;
        String speaker=op.speakerId();ResidentState self=state(w,speaker);Project p=project(w,c.topicId);
        // An agreement is only this speaker's commitment, not a claim about anyone else's decision.
        if((reply.stance().equals("accept")||reply.stance().equals("adjust"))&&(p==null||!knows(w,speaker,c.topicId)))return false;
        if(reply.stance().equals("adjust")&&(!p.ownerId.equals(speaker)||reply.adjustment()==null||reply.adjustment().isBlank()||reply.adjustment().length()>160))return false;
        if(!validWorkTurn(w,c,speaker,reply))return false;
        appendSpeech(w,c,speaker,reply.text(),"model",reply.evidenceIds(),reply.emoji(),now);
        if(!applyWorkTurn(w,c,speaker,reply,now))throw new IllegalStateException("validated work turn could not apply");
        self.mood=reply.feeling();c.feelings.put(speaker,reply.feeling());
        self.social=Math.min(100,self.social+6);
        boolean deferredCommitment=reply.text().matches("(?s).*(明天|后天|下周|改天|改日|过几天|明早|明晚|等有空|等考完|等忙完).*");
        if(reply.stance().equals("accept")&&deferredCommitment)self.thought="刚才提到以后参与的可能，暂时还没有安排现在开始。";
        if(reply.stance().equals("accept")&&!deferredCommitment) {
            boolean newlyCommitted=!p.members.contains(speaker)||!Objects.equals(self.goal,p.id);
            if(!p.members.contains(speaker))p.members.add(speaker);self.goal=p.id;self.thought="我刚答应为「"+p.title+"」做一点自己的贡献。";
            self.relationships.compute(other(c,speaker),(key,value)->Math.min(100,(value==null?40:value)+4));
            if(newlyCommitted){
                event(w,now,"agreement",c.place,List.of(speaker,other(c,speaker)),actor(w,speaker).name()+"在交谈中答应参与「"+p.title+"」。",p.id);
                // Saying yes to someone is one of the few things in this town that can actually change
                // a person. This call was missing: the drift lived only in the rule-scripted
                // conversation path, which no real conversation ever takes.
                ResidentSimulation.driftOnConversationOutcome(w,speaker,other(c,speaker),true,now);
            }
        } else if(reply.stance().equals("decline")) {
            event(w,now,"declined",c.place,List.of(speaker),actor(w,speaker).name()+"说出了自己的顾虑，这次先不答应。",c.topicId);
            // Being turned down is a fact about whoever asked, not about whoever said no.
            ResidentSimulation.driftOnConversationOutcome(w,speaker,other(c,speaker),false,now);
        } else if(reply.stance().equals("adjust")) {
            p.description=reply.adjustment();self.thought="听完邻居的话，我想调整一下做法："+reply.adjustment();
            event(w,now,"change_of_mind",c.place,List.of(speaker,other(c,speaker)),actor(w,speaker).name()+"调整了「"+p.title+"」的安排："+reply.adjustment(),p.id);
        }
        clearPending(c);c.turnVersion++;c.nextSpeakerId=other(c,speaker);c.updatedAt=now;
        w.modelStatus=""+actor(w,speaker).name()+"刚接着说了一句";w.revision++;
        if(reply.leave()||c.turns.size()>=8)finish(w,c,now,"说完这一句，彼此道别了");
        return true;
    }
    /** Work authority is born only in a real, validated turn between the two people at the same
     * place.  Text is still their own; these fields merely make its operational meaning unambiguous. */
    private static boolean applyWorkTurn(CompanionWorld w,Conversation c,String speaker,Utterance reply,Instant now){
        String action=reply.workAction()==null?"none":reply.workAction();if("none".equals(action))return true;
        String other=other(c,speaker);if(!"cafe".equals(c.place)||!together(w,c)||!Set.of("offer_assist","offer_delegate","offer_takeover","accept_work","reject_work","end_work").contains(action))return false;
        if(action.startsWith("offer_"))return ResidentSimulation.proposeWorkArrangement(w,speaker,action.substring(6),other,reply.text(),reply.evidenceIds(),now);
        if("accept_work".equals(action))return ResidentSimulation.acceptWorkArrangement(w,speaker,reply.workTarget(),reply.evidenceIds(),now);
        return ResidentSimulation.endWorkArrangement(w,speaker,reply.workTarget(),"reject_work".equals(action)?"rejected":"ended",now);
    }
    private static boolean validWorkTurn(CompanionWorld w,Conversation c,String speaker,Utterance reply){
        String action=reply.workAction()==null?"none":reply.workAction();if("none".equals(action))return true;
        if(!"cafe".equals(c.place)||!together(w,c)||!Set.of("offer_assist","offer_delegate","offer_takeover","accept_work","reject_work","end_work").contains(action))return false;
        String other=other(c,speaker);
        if(action.startsWith("offer_")){String kind=action.substring(6);String operator=CafeService.operatorId(w);return Objects.equals(reply.workTarget(),other)&&("assist".equals(kind)?other.equals(operator):speaker.equals(operator));}
        WorkArrangement a=w.workArrangements.stream().filter(x->Objects.equals(x.id,reply.workTarget())&&Set.of("proposed","active").contains(x.status)).findFirst().orElse(null);
        if(a==null)return false;
        if("accept_work".equals(action))return "proposed".equals(a.status)&&speaker.equals("assist".equals(a.kind)?CafeService.operatorId(w):a.workerId);
        if("reject_work".equals(action))return "proposed".equals(a.status)&&(speaker.equals(a.proposerId)||speaker.equals(a.workerId)||speaker.equals(CafeService.operatorId(w)));
        return "active".equals(a.status)&&(speaker.equals(a.proposerId)||speaker.equals(a.workerId)||speaker.equals(CafeService.operatorId(w)));
    }
    public static void failTurn(CompanionWorld w,Operation op,Instant now) {
        Conversation c=find(w,op.conversationId());
        if(c!=null&&Objects.equals(c.pendingOperationId,op.operationId()))fallback(w,c,now,"暂时没想好怎样接下去，先顺着生活聊完");
    }
    private static void fallback(CompanionWorld w,Conversation c,Instant now,String reason){clearPending(c);c.mode="fallback";c.endReason=reason;c.updatedAt=now;c.turnVersion++;w.revision++;}
    private static boolean validPending(CompanionWorld w,Conversation c,Operation op,Instant now) {
        return c!=null&&"active".equals(c.status)&&"model".equals(c.mode)&&Objects.equals(c.pendingOperationId,op.operationId())
            &&Objects.equals(c.pendingSpeakerId,op.speakerId())&&Objects.equals(c.nextSpeakerId,op.speakerId())&&c.turnVersion==op.turnVersion()
            &&w.intentRevision==op.intentRevision()&&Duration.between(op.startedAt(),now).getSeconds()<=OPERATION_TIMEOUT_SECONDS&&together(w,c);
    }
    private static void appendSpeech(CompanionWorld w,Conversation c,String speaker,String text,String source,List<String> evidence,String emoji,Instant now) {
        c.turns.add(new Turn(speaker,text,now,source,emoji));
        String listener=other(c,speaker);
        String own=memory(w,speaker,speaker,"observed",now,c.topicId,"我对"+actor(w,listener).name()+"说：“"+text+"”",evidence,6);
        String heard=memory(w,listener,speaker,"heard",now,c.topicId,actor(w,speaker).name()+"当面说：“"+text+"”",List.of(own),7);
        c.turnMemoryIds.computeIfAbsent(speaker,k->new ArrayList<>()).add(own);c.turnMemoryIds.computeIfAbsent(listener,k->new ArrayList<>()).add(heard);
        ProjectKnowledge shared=state(w,speaker).knownProjects.get(c.topicId);
        if(shared!=null)state(w,listener).knownProjects.put(c.topicId,new ProjectKnowledge(shared.id(),shared.place(),shared.status(),shared.progress(),now,speaker));
        replaceActor(w,speaker,c.place,"talk",text,now.plusSeconds(OPERATION_TIMEOUT_SECONDS));
        state(w,speaker).revision++;state(w,listener).revision++;
    }
    public static void finish(CompanionWorld w,Conversation c,Instant now,String reason) {
        if(!"active".equals(c.status))return;
        c.status="ended";c.endedAt=now;c.endReason=reason;clearPending(c);c.turnVersion++;
        for(String id:c.participantIds){ResidentState r=state(w,id);r.plan=null;ResidentSimulation.resumeSuspended(w,r,now);r.lastSocialAt=now;r.revision++;}
        if(!c.turns.isEmpty()) {
            // Rule conversations have the same source-backed recollection boundary; no fake model label.
            ensureTurnMemories(w,c,now);
            if(!"model".equals(c.mode))for(String id:c.participantIds)fallbackSummary(w,c,id,now);
        }
        w.revision++;
    }
    public static Operation reserveSummary(CompanionWorld w,Conversation c,String speaker,Instant now) {
        if(!"ended".equals(c.status)||!"model".equals(c.mode)||c.turns.isEmpty()||!c.participantIds.contains(speaker)||c.summarizedParticipants.contains(speaker))return null;
        if(c.summaryOperationId!=null&&Duration.between(c.summaryStartedAt,now).getSeconds()<OPERATION_TIMEOUT_SECONDS)return null;
        c.summaryOperationId="recollection-op-"+(++w.eventSequence);c.summarySpeakerId=speaker;c.summaryStartedAt=now;w.revision++;
        return new Operation(c.id,speaker,c.turnVersion,c.summaryOperationId,w.intentRevision,now);
    }
    public static boolean applySummary(CompanionWorld w,Operation op,Recollection summary,Instant now) {
        Conversation c=find(w,op.conversationId());
        if(c==null||!"ended".equals(c.status)||!Objects.equals(c.summaryOperationId,op.operationId())||!Objects.equals(c.summarySpeakerId,op.speakerId())
            ||c.turnVersion!=op.turnVersion()||c.summarizedParticipants.contains(op.speakerId())||Duration.between(op.startedAt(),now).getSeconds()>OPERATION_TIMEOUT_SECONDS
            ||summary==null||summary.text()==null||summary.text().isBlank()||summary.text().length()>360||summary.feeling()==null||summary.feeling().length()>30
            ||!validEvidence(w,op.speakerId(),summary.evidenceIds())||summary.evidenceIds().isEmpty()
            ||!c.turnMemoryIds.getOrDefault(op.speakerId(),List.of()).containsAll(summary.evidenceIds()))return false;
        memory(w,op.speakerId(),c.id,"reflection",now,c.topicId,summary.text(),summary.evidenceIds(),8);
        // "I remember that exchange" and "I looked back over a stretch of my life and concluded
        // something" are different acts - see ResidentSimulation.needsReflection/applyReflection.
        // This used to also set r.lastReflectionAt, which meant every ordinary conversation summary
        // reset the same three-hour clock that gates a real reflection: a resident who simply talks
        // often could push that clock forward indefinitely and never accumulate the gap needed to
        // reach one. Only applyReflection may advance lastReflectionAt now.
        ResidentState r=state(w,op.speakerId());r.thought=summary.text();r.mood=summary.feeling();
        c.summarizedParticipants.add(op.speakerId());c.recollectionSources.put(op.speakerId(),"model");c.summaryOperationId=null;c.summarySpeakerId=null;w.revision++;return true;
    }
    public static void failSummary(CompanionWorld w,Operation op,Instant now) {
        Conversation c=find(w,op.conversationId());if(c!=null&&Objects.equals(c.summaryOperationId,op.operationId())){fallbackSummary(w,c,op.speakerId(),now);c.summaryOperationId=null;c.summarySpeakerId=null;w.revision++;}
    }
    private static void ensureTurnMemories(CompanionWorld w,Conversation c,Instant now){
        for(String id:c.participantIds)if(c.turnMemoryIds.getOrDefault(id,List.of()).isEmpty()){
            for(Turn turn:c.turns){String type=turn.speakerId().equals(id)?"observed":"heard";String prefix=type.equals("heard")?actor(w,turn.speakerId()).name()+"当面说：":"我当时说：";
                String memoryId=memory(w,id,turn.speakerId(),type,turn.at(),c.topicId,prefix+turn.text(),List.of(),6);
                c.turnMemoryIds.computeIfAbsent(id,k->new ArrayList<>()).add(memoryId);
            }
        }
    }
    private static void fallbackSummary(CompanionWorld w,Conversation c,String id,Instant now) {
        if(c.summarizedParticipants.contains(id)||c.turns.isEmpty())return;
        String other=other(c,id);Turn last=c.turns.stream().filter(t->t.speakerId().equals(other)).reduce((a,b)->b).orElse(c.turns.getLast());
        String quote=last.text().length()>90?last.text().substring(0,90)+"…":last.text();
        String text=last.speakerId().equals(id)?"我当时说：“"+quote+"”":actor(w,other).name()+"刚才提到：“"+quote+"”";
        var allIds=c.turnMemoryIds.getOrDefault(id,List.of());int quotedTurn=c.turns.indexOf(last);
        List<String> evidence=new ArrayList<>();
        if(quotedTurn<allIds.size()&&w.memories.stream().anyMatch(m->m.id().equals(allIds.get(quotedTurn))))evidence.add(allIds.get(quotedTurn));
        for(int i=allIds.size()-1;i>=0&&evidence.size()<4;i--){String memoryId=allIds.get(i);if(!evidence.contains(memoryId)&&w.memories.stream().anyMatch(m->m.id().equals(memoryId)))evidence.add(memoryId);}
        if(!evidence.isEmpty())memory(w,id,c.id,"reflection",now,c.topicId,text,evidence,7);
        c.summarizedParticipants.add(id);c.recollectionSources.put(id,"rules");
    }
    public static void recoverSummaries(CompanionWorld w,Instant now) {
        for(Conversation c:w.conversations)if(c.status.equals("ended")&&!c.turns.isEmpty()&&c.summarizedParticipants.size()<c.participantIds.size()) {
            boolean timedOut=c.summaryStartedAt!=null&&Duration.between(c.summaryStartedAt,now).getSeconds()>OPERATION_TIMEOUT_SECONDS;
            boolean deferred=c.endedAt!=null&&Duration.between(c.endedAt,now).getSeconds()>90;
            if(timedOut||deferred||!w.modelConversationsEnabled||w.modelRetryAfter!=null&&now.isBefore(w.modelRetryAfter)) {
                for(String id:c.participantIds)fallbackSummary(w,c,id,now);
                c.summaryOperationId=null;c.summarySpeakerId=null;w.revision++;
            }
        }
    }
    public static Conversation find(CompanionWorld w,String id){return w.conversations.stream().filter(c->c.id.equals(id)).findFirst().orElse(null);}
    private static boolean validEvidence(CompanionWorld w,String owner,List<String> evidence){return evidence!=null&&evidence.size()<=6&&evidence.stream().allMatch(id->w.memories.stream().anyMatch(m->m.id().equals(id)&&m.ownerId().equals(owner)));}
    private static boolean together(CompanionWorld w,Conversation c){return c.participantIds.size()==2&&c.participantIds.stream().allMatch(id->actor(w,id).place().equals(c.place)&&!actor(w,id).activity().equals("walk"));}
    private static String other(Conversation c,String id){return c.participantIds.stream().filter(candidate->!candidate.equals(id)).findFirst().orElseThrow();}
    private static void clearPending(Conversation c){c.pendingOperationId=null;c.pendingSpeakerId=null;c.operationStartedAt=null;}
}
