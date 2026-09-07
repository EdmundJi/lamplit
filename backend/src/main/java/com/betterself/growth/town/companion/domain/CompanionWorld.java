package com.betterself.growth.town.companion.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/** Serializable state; domain rules receive time explicitly and have no I/O. */
public class CompanionWorld {
    public String id, name, timezone, weather, period, offlineSummary;
    public Instant joinedAt, updatedAt;
    public long revision, lastEncounterSlot;
    public Actor avatar;
    public List<Actor> residents = new ArrayList<>();
    public List<Intent> intents = new ArrayList<>();
    public List<Entry> diary = new ArrayList<>();
    public List<Memory> memories = new ArrayList<>();
    public Focus focus;
    public int simulationVersion;
    public boolean modelConversationsEnabled;
    public long intentRevision, eventSequence, modelSequence;
    public Instant simulatedAt, modelRequestedAt;
    public String modelBudgetDay;
    public int modelCallsToday, modelFailuresToday, modelConsecutiveFailures;
    public Instant modelRetryAfter;
    public String modelStatus = "规则生活正在继续";
    public List<ResidentState> residentStates = new ArrayList<>();
    public List<Project> projects = new ArrayList<>();
    public List<Conversation> conversations = new ArrayList<>();
    public List<WorldEvent> events = new ArrayList<>();
    public List<WorldObject> objects = new ArrayList<>();
    public static class ResidentState {
        public String id, mood, goal, thought, desiredAction;
        public double energy, social, curiosity;
        public long revision;
        public Instant lastSocialAt, lastReflectionAt;
        public Plan plan;
        public Map<String,Integer> relationships = new LinkedHashMap<>();
        public Map<String,ProjectKnowledge> knownProjects = new LinkedHashMap<>();
    }
    public record ProjectKnowledge(String id,String place,String status,int progress,Instant at,String sourceId) {}
    public record Plan(String id,String action,String place,String targetId,String reason,Instant startedAt,Instant endsAt) {}
    public static class Project {
        public String id,title,kind,place,ownerId,status,objectKind,description;
        public int progress,needed;
        public List<String> contributors = new ArrayList<>();
        public List<String> members = new ArrayList<>();
        public Map<String,Instant> invitationHistory = new LinkedHashMap<>();
        public Instant completedAt;
    }
    public static class Conversation {
        public String id,place,topicId,status;
        public String mode="rules",nextSpeakerId,pendingOperationId,pendingSpeakerId,endReason;
        public long turnVersion,pendingIntentRevision;
        public Instant operationStartedAt,endedAt;
        public String summaryOperationId,summarySpeakerId;
        public Instant summaryStartedAt;
        public List<String> summarizedParticipants=new ArrayList<>();
        public Map<String,List<String>> turnMemoryIds=new LinkedHashMap<>();
        public Map<String,String> feelings=new LinkedHashMap<>();
        public Map<String,String> recollectionSources=new LinkedHashMap<>();
        public List<String> participantIds = new ArrayList<>();
        public Instant startedAt,updatedAt;
        public List<Turn> turns = new ArrayList<>();
        public boolean accepted;
        public int stage = 1;
    }
    public record Turn(String speakerId,String text,Instant at,String source,String emoji) {
        public Turn(String speakerId,String text,Instant at){this(speakerId,text,at,"rules",null);}
        public Turn(String speakerId,String text,Instant at,String source){this(speakerId,text,at,source,null);}
    }
    public record WorldEvent(String id,Instant at,String type,String place,List<String> actorIds,String text,String projectId) {}
    public record WorldObject(String id,String kind,String place,String label,String state,String projectId) {}
    public record Actor(String id, String name, String role, String place, String activity, String label,
                        double x, double y, Instant until) {}
    public static class Intent {
        public String id, kind, priority, status, feedback, taskId, text, resolvedKind;
        public int durationMinutes;
        public Instant createdAt;
        public Intent() {}
        public Intent(String id, String kind, String priority, String taskId, int durationMinutes, Instant now) {
            this.id=id; this.kind=kind; this.priority=priority; this.taskId=taskId;
            this.durationMinutes=durationMinutes; this.createdAt=now; this.status="pending";
            this.feedback="记住了，等手上的安排告一段落。";
        }
    }
    public record Focus(String taskId, Instant startedAt, Instant endsAt) {}
    public record Entry(String id, Instant at, String text) {}
    public record Memory(String id, String ownerId, String sourceId, String sourceType, Instant at, String text, String topicId, List<String> evidenceIds, int importance) {
        public Memory(String id,String ownerId,String sourceId,String sourceType,Instant at,String text,String topicId){
            this(id,ownerId,sourceId,sourceType,at,text,topicId,List.of(),sourceType.equals("seed")?6:5);
        }
    }
}
