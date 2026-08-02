package com.betterself.growth.execution;

import org.springframework.stereotype.Component;

@Component
public class ExperienceCalculator {

    public int earned(
        TaskEventType type,
        int estimatedMinutes,
        int difficulty,
        double completionRatio,
        int remainingDailyCap
    ) {
        int base = Math.min(30, Math.max(2, (int) Math.ceil(estimatedMinutes / 10.0) * difficulty));
        double ratio = switch (type) {
            case COMPLETED -> 1.0;
            case PARTIAL -> completionRatio;
            default -> 0.0;
        };
        return Math.min(Math.max(0, remainingDailyCap), (int) Math.round(base * ratio));
    }
}
