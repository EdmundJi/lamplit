package com.betterself.growth.safety;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public class RiskClassifier {

    private static final List<String> L3 = List.of(
        "不想活", "伤害自己", "自杀", "结束生命", "伤害别人", "kill myself", "suicide", "hurt myself", "hurt someone"
    );
    private static final List<String> L2 = List.of(
        "诊断", "处方", "药量", "停药", "替我治疗", "diagnose", "prescribe", "dosage"
    );
    private static final List<String> L1 = List.of(
        "焦虑", "抑郁", "崩溃", "绝望", "anxious", "depressed", "hopeless"
    );

    public Classification classify(String input) {
        String normalized = input == null ? "" : input.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, L3)) {
            return new Classification(RiskLevel.L3, List.of("CRISIS_SIGNAL"));
        }
        if (containsAny(normalized, L2)) {
            return new Classification(RiskLevel.L2, List.of("PROFESSIONAL_BOUNDARY"));
        }
        if (containsAny(normalized, L1)) {
            return new Classification(RiskLevel.L1, List.of("EMOTIONAL_DISTRESS"));
        }
        return new Classification(RiskLevel.L0, List.of());
    }

    private boolean containsAny(String input, List<String> terms) {
        return terms.stream().anyMatch(input::contains);
    }

    public record Classification(RiskLevel level, List<String> ruleCodes) {
    }
}
