package com.betterself.growth.safety;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SafetyService {

    private final RiskClassifier classifier;

    public SafetyService(RiskClassifier classifier) {
        this.classifier = classifier;
    }

    public SafetyDecision classifyInput(String scene, String input) {
        RiskClassifier.Classification result = classifier.classify(input);
        return decision(result);
    }

    public SafetyDecision classifyOutput(String scene, String output) {
        RiskClassifier.Classification result = classifier.classify(output);
        return decision(result);
    }

    private SafetyDecision decision(RiskClassifier.Classification result) {
        List<String> actions = result.level() == RiskLevel.L3
            ? List.of("CALL_LOCAL_EMERGENCY", "CONTACT_TRUSTED_PERSON")
            : List.of();
        return new SafetyDecision(result.level(), result.level().ordinal() < RiskLevel.L2.ordinal(), result.ruleCodes(), actions);
    }

    public record SafetyDecision(
        RiskLevel level,
        boolean allowGeneration,
        List<String> ruleCodes,
        List<String> actions
    ) {
    }
}
