package com.betterself.growth.execution;

import com.betterself.growth.shared.api.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskStateMachineTest {

    private final TaskStateMachine stateMachine = new TaskStateMachine();

    @Test
    void supportsDocumentedTransitions() {
        assertThat(stateMachine.next("PLANNED", TaskEventType.STARTED)).isEqualTo("IN_PROGRESS");
        assertThat(stateMachine.next("IN_PROGRESS", TaskEventType.COMPLETED)).isEqualTo("DONE");
        assertThat(stateMachine.next("PLANNED", TaskEventType.DEFERRED)).isEqualTo("DEFERRED");
    }

    @Test
    void rejectsASecondTerminalEvent() {
        assertThatThrownBy(() -> stateMachine.next("DONE", TaskEventType.COMPLETED))
            .isInstanceOf(ApiException.class)
            .extracting(error -> ((ApiException) error).code())
            .isEqualTo("INVALID_TASK_TRANSITION");
    }
}
