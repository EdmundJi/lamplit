package com.betterself.growth.ai;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public interface QwenProvider {

    StructuredResult generateStructured(StructuredPrompt prompt);

    StreamMetadata stream(ChatPrompt prompt, Consumer<String> deltaConsumer);

    Classification classify(ClassificationPrompt prompt);

    /**
     * thinkingEnabled is a vendor-agnostic on/off switch for the model's "thinking"/reasoning pass,
     * null meaning "leave the provider's own default alone". Each QwenProvider implementation
     * translates true/false into whatever wire field its own vendor actually uses (DashScope's
     * enable_thinking boolean, DeepSeek's {"thinking":{"type":...}} object, ...) - callers never write
     * a vendor-specific field name. The 3-arg constructor is the pre-existing shape, kept for every
     * caller that has no opinion on thinking.
     */
    record StructuredPrompt(String scene, String instruction, String schemaJson, Boolean thinkingEnabled) {
        public StructuredPrompt(String scene, String instruction, String schemaJson) {
            this(scene, instruction, schemaJson, null);
        }
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
