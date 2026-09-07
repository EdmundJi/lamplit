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
    record DialogueRequest(Context perspective,String conversationId,long turnVersion,String operationId,String partnerName,String topicTitle) {}
    record SummaryRequest(Context perspective,String conversationId,String partnerName,List<Turn> transcript,List<Memory> conversationMemories) {}
    record Context(String worldId,String residentId,long revision,long intentRevision,Instant at,String localTime,
                   String weather,Actor self,String goal,String mood,String thought,double energy,double social,
                   java.util.Map<String,Integer> relationships,List<Memory> memories,List<Actor> nearby,
                   List<WorldObject> visibleObjects,List<KnownProject> knownProjects,List<Turn> conversation) {}
    record KnownProject(String id,String title,String place) {}
    record Decision(String action,String place,String targetId,String reason,String speech,List<String> evidenceIds,String projectTitle,String objectKind) {}
}
