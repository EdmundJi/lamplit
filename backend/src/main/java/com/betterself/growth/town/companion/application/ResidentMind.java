package com.betterself.growth.town.companion.application;

import com.betterself.growth.town.companion.domain.CompanionWorld.*;
import java.time.Instant;
import java.util.List;

/** The model sees one resident's perspective, never the world aggregate or the user's intents/Todos. */
public interface ResidentMind {
    boolean enabled();
    Decision decide(Context context);
    default com.betterself.growth.town.companion.domain.ConversationLifecycle.Utterance generateTurn(DialogueRequest request){throw new UnsupportedOperationException("Dialogue generation unavailable");}
    default com.betterself.growth.town.companion.domain.ConversationLifecycle.Recollection summarizeConversation(SummaryRequest request){throw new UnsupportedOperationException("Conversation recollection unavailable");}

    /**
     * Token-metered variants of the three calls above. Additive on purpose: implementations that only
     * override the plain methods (every existing fake/mock ResidentMind, including test doubles) keep
     * compiling unchanged and simply report no usage, which callers must treat as "nothing to record" -
     * not as zero cost.
     */
    default Result<Decision> decideMetered(Context context){return new Result<>(decide(context),null);}
    default Result<com.betterself.growth.town.companion.domain.ConversationLifecycle.Utterance> generateTurnMetered(DialogueRequest request){return new Result<>(generateTurn(request),null);}
    default Result<com.betterself.growth.town.companion.domain.ConversationLifecycle.Recollection> summarizeConversationMetered(SummaryRequest request){return new Result<>(summarizeConversation(request),null);}

    /**
     * Prompt/completion token counts for one model call. Null usage upstream means "not measured",
     * never zero cost. {@code provider} names which backend actually served the call (e.g. "deepseek",
     * "qwen3") so usage can be broken down per supplier, not just per call type - it is additive and
     * may be null for any caller that predates multi-provider routing (test doubles included).
     */
    record Usage(int inputTokens,int outputTokens,String provider) {
        public Usage(int inputTokens,int outputTokens){this(inputTokens,outputTokens,null);}
    }
    record Result<T>(T value,Usage usage) {}

    record DialogueRequest(Context perspective,String conversationId,long turnVersion,String operationId,String partnerName,String topicTitle) {}
    record SummaryRequest(Context perspective,String conversationId,String partnerName,List<Turn> transcript,List<Memory> conversationMemories) {}
    record Context(String worldId,String residentId,long revision,long intentRevision,Instant at,String localTime,
                   String weather,Actor self,String goal,String mood,String thought,double energy,double social,
                   java.util.Map<String,Integer> relationships,List<Memory> memories,List<Actor> nearby,
                   List<WorldObject> visibleObjects,List<KnownProject> knownProjects,List<Turn> conversation) {}
    record KnownProject(String id,String title,String place) {}
    record Decision(String action,String place,String targetId,String reason,String speech,List<String> evidenceIds,String projectTitle,String objectKind) {}
}
