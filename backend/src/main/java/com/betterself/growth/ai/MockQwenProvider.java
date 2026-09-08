package com.betterself.growth.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Consumer;

// @Primary for the same reason as QwenHttpProvider's (see its comment): a second QwenProvider bean
// now exists for the companion town's qwen3 route, and every unqualified QwenProvider injection point
// must still resolve to this one in mock mode. Mutually exclusive with QwenHttpProvider's @Primary.
@Primary
@Component
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "mock", matchIfMissing = true)
public class MockQwenProvider implements QwenProvider {

    @Override
    public StructuredResult generateStructured(StructuredPrompt prompt) {
        if ("GOAL_TEMPLATE".equals(prompt.scene())) {
            return new StructuredResult(
                """
                    {
                      "title":"四周建立稳定学习节奏",
                      "description":"每周完成三次可复盘的学习行动，并在第四周整理一份总结。",
                      "dimensionCode":"KNOWLEDGE",
                      "durationDays":28,
                      "weeklyFocus":"先稳定频率，再逐步增加难度。",
                      "starterTasks":[
                        {"title":"完成一次专注练习","estimatedMinutes":25,"difficulty":2},
                        {"title":"记录本次学习收获","estimatedMinutes":10,"difficulty":1}
                      ]
                    }
                    """,
                "qwen-mock", "mock-goal-template", 36, 92, 6
            );
        }
        if ("TOWN_REFLECTION".equals(prompt.scene())) {
            return new StructuredResult(
                """
                    {
                      "greeting":"晚上好，今天也认真生活了一天。",
                      "insights":["完成了一次专注练习，状态保持得不错。","有一件事推迟了，但你及时做了调整。"]
                    }
                    """,
                "qwen-mock", "mock-town-reflection", 30, 48, 4
            );
        }
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
