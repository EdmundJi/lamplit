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

    // ---- deterministic exit: a speaker reduced to placeholder replies (docs/04-decisions.md 2026-09-14
    // 「对话出口」) --------------------------------------------------------------------------------------
    /** A parenthesised stage direction, either full-width （…） or ASCII (…) - Chinese dialogue in this
     * town writes both. Assumed non-nested: a stage direction describes one beat of physical business
     * ("翻过一页，没抬头"), never another line of dialogue inside it. */
    private static final java.util.regex.Pattern STAGE_DIRECTION=java.util.regex.Pattern.compile("（[^（）]*）|\\([^()]*\\)");
    /** Unicode punctuation (covers full-width ，。！？、…—～ as well as ASCII) plus whitespace - what is
     * left over after stripping this and stage directions is the turn's actual content, if any. */
    private static final java.util.regex.Pattern PUNCTUATION_OR_SPACE=java.util.regex.Pattern.compile("[\\p{IsPunctuation}\\s]+");
    private static String coreText(String text){return PUNCTUATION_OR_SPACE.matcher(STAGE_DIRECTION.matcher(text).replaceAll("")).replaceAll("");}
    /** A reply that carries no content of its own once its stage direction and punctuation are
     * stripped away - "嗯。", "（翻过一页，没抬头）嗯。", "行", "好的". This is the live bug's actual shape: one
     * party is genuinely busy (reading, working) and keeps technically answering, one placeholder grunt
     * at a time, while the other keeps asking real questions and - because ending a conversation used
     * to rely solely on the model choosing leave=true - the exchange never naturally stopped. A question
     * mark anywhere in the ORIGINAL text (before stripping) is never a token acknowledgement, however
     * short the rest is: "行？" is someone checking something, not brushing you off, and must still be
     * able to trigger the ordinary reply it deserves. See {@link #applyTurn}, which is the one place
     * this and {@link #isNearDuplicate} are read to end a conversation - the same {@link #finish} path
     * {@code leave=true} already takes, never a second one. */
    public static boolean isTokenAcknowledgement(String text){
        if(text==null)return false;
        if(text.indexOf('?')>=0||text.indexOf('？')>=0)return false;
        String core=coreText(text);
        return core.codePointCount(0,core.length())<=4;
    }
    /** Two turns that say the exact same thing once stage directions and punctuation are stripped -
     * "（翻过一页，没抬头）嗯。" is not a different token acknowledgement each time it is said, it is the same
     * one repeated. Catches a stalled exchange even when the repeated line runs longer than four
     * characters, which {@link #isTokenAcknowledgement} alone would miss. */
    public static boolean isNearDuplicate(String a,String b){
        if(a==null||b==null)return false;
        String ca=coreText(a),cb=coreText(b);
        return !ca.isEmpty()&&ca.equals(cb);
    }
    /** True once THIS speaker's own last two turns in the conversation (not the conversation's last two
     * turns overall, which strictly alternate speakers) are both placeholder replies, or are near-
     * duplicates of each other - the two ways a busy party's side of the exchange goes visibly stale.
     * Reads only turns already committed by {@link #appendSpeech}, so the turn just spoken is included. */
    private static boolean speakerHasStalled(Conversation c,String speaker){
        List<String> own=c.turns.stream().filter(t->t.speakerId().equals(speaker)).map(Turn::text).toList();
        if(own.size()<2)return false;
        String last=own.getLast(),previous=own.get(own.size()-2);
        return (isTokenAcknowledgement(last)&&isTokenAcknowledgement(previous))||isNearDuplicate(last,previous);
    }

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
        // 没有模型答案的时候就不说话。这里原来有六句兜底台词（一句通用的"嗯，先这样吧。"，
        // 外加按角色分的开场白，其中 default 分支落在周野和阿满身上），规则等 8 秒就替居民
        // 说出来。它们不是无害的占位符：appendSpeech 会把那句话写成一条对白、一条"我对他说过"
        // 的记忆、一条"他当面对我说过"的记忆和说话人此刻的可见状态，finish 再经 fallbackSummary
        // 写一条引用它的反思。于是模型不是误以为他说过——在记忆里他确实说过，然后会顺着它
        // 往下问，当事人再为我们的句子找补。
        //
        // 2026-09-11 两个互不知情、都没读过这个仓库的人各读了一跑的时间线，两人排在最前面的
        // 两条"这镇上的规矩"，正是这里的两句字符串常量（"我先忙手上这点"被读成"跨角色共用的
        // 固定开场白"，"嗯，先这样吧"被读成"四个角色共用的收尾语"）。那一跑 330 句对白里有
        // 87 句一字不差是这六句。
        //
        // 所以这一段现在只是结束，不再开口。两个人站在一起、谁也没说话就各自走开，是一件
        // 真实发生过的事；一句通用台词不是。规矩和 NormDetector 在回放不可信时拒答是同一条：
        // 拒绝作答，不要编一个。
        if("fallback".equals(c.mode)&&Duration.between(c.updatedAt,now).getSeconds()>=8)
            finish(w,c,now,c.turns.isEmpty()?"谁也没先开口，就各自走开了":"话没接下去，这段就停在这里");
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
        // Rule-level exit (docs/04-decisions.md 2026-09-14): the model is not asked and does not need
        // to agree - one party visibly stuck on placeholder replies is a fact the rules can see for
        // themselves, exactly like the timeout and the 8-turn cap just above. A distinct endReason so
        // this is not read back as an ordinary leave=true goodbye.
        else if(speakerHasStalled(c,speaker))finish(w,c,now,"对方接连只是应一声，不再多问，各自去忙");
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
        replaceActor(w,speaker,c.place,"talk",text,now.plusSeconds(OPERATION_TIMEOUT_SECONDS),now);
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
