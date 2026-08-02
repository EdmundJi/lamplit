package com.betterself.growth.ai;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public interface QwenProvider {

    StructuredResult generateStructured(StructuredPrompt prompt);

    StreamMetadata stream(ChatPrompt prompt, Consumer<String> deltaConsumer);

    Classification classify(ClassificationPrompt prompt);

    record StructuredPrompt(String scene, String instruction, String schemaJson) {
    }

    record ChatPrompt(String scene, String systemPrompt, String userMessage) {
    }

    record ClassificationPrompt(String text, List<String> labels) {
    }

    record StructuredResult(
        String json,
        String model,
        String requestId,
        int inputTokens,
        int outputTokens,
        long latencyMs
    ) {
    }

    record StreamMetadata(String model, String requestId, int inputTokens, int outputTokens, long latencyMs) {
    }

    record Classification(String label, Map<String, Double> scores) {
    }
}
