package com.betterself.growth.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Consumer;

@Component
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "mock", matchIfMissing = true)
public class MockQwenProvider implements QwenProvider {

    @Override
    public StructuredResult generateStructured(StructuredPrompt prompt) {
        return new StructuredResult(
            """
                {"items":[
                  {"title":"专注练习","description":"完成一个清晰的小步骤","estimatedMinutes":25,"difficulty":2,"dimensionWeights":{"KNOWLEDGE":10},"proposedLocalTime":"09:00"},
                  {"title":"简短复盘","description":"记录今天完成了什么","estimatedMinutes":10,"difficulty":1,"dimensionWeights":{"KNOWLEDGE":5},"proposedLocalTime":"20:00"}
                ]}
                """,
            "qwen-mock", "mock-structured", 24, 60, 5
        );
    }

    @Override
    public StreamMetadata stream(ChatPrompt prompt, Consumer<String> deltaConsumer) {
        deltaConsumer.accept("可以。先选择一个今天能完成的最小步骤，");
        deltaConsumer.accept("完成后再决定是否继续。");
        return new StreamMetadata("qwen-mock", "mock-stream", 20, 24, 5);
    }

    @Override
    public Classification classify(ClassificationPrompt prompt) {
        return new Classification("L0", Map.of("L0", 1.0));
    }
}
