package com.betterself.growth.execution;

import com.betterself.growth.shared.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
public class TaskStateMachine {

    private static final Map<String, Set<TaskEventType>> ALLOWED = Map.of(
        "PLANNED", Set.of(TaskEventType.STARTED, TaskEventType.COMPLETED, TaskEventType.PARTIAL,
            TaskEventType.DEFERRED, TaskEventType.SKIPPED, TaskEventType.CANCELLED),
        "IN_PROGRESS", Set.of(TaskEventType.COMPLETED, TaskEventType.PARTIAL,
            TaskEventType.DEFERRED, TaskEventType.CANCELLED)
    );

    public String next(String current, TaskEventType eventType) {
        if (!ALLOWED.getOrDefault(current, Set.of()).contains(eventType)) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_TASK_TRANSITION", "Task state transition is not allowed");
        }
        return switch (eventType) {
            case STARTED -> "IN_PROGRESS";
            case COMPLETED -> "DONE";
            case PARTIAL -> "PARTIAL";
            case DEFERRED -> "DEFERRED";
            case SKIPPED -> "SKIPPED";
            case CANCELLED -> "CANCELLED";
            default -> throw new ApiException(HttpStatus.CONFLICT, "INVALID_TASK_TRANSITION", "Task state transition is not allowed");
        };
    }
}
