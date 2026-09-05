package com.betterself.growth.town;

import com.betterself.growth.ai.QwenHttpProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M1-7 的人工检查项：<b>同一条事实的第 1/2/3 手文本，逐级走样且每一手都通顺。</b>
 *
 * <p>这条验收没法用断言完全代替——「通顺」只能人看。所以这个测试做两件事：把真实模型生成的三手
 * 文本打印出来供人判断，同时把能自动判的那部分（不许出现数字、不许超长、条数必须对齐）钉死。
 *
 * <p>默认跳过：只有设置了 {@code QWEN_API_KEY} 才会真的发请求，所以它不会在没有 key 的机器上
 * 变成一个会红的测试，也不会在 CI 里悄悄烧钱。本地跑法：
 * <pre>
 *   set -a; source ../.env.local; set +a
 *   ./mvnw test -Dtest=QwenRetellLiveIT
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "QWEN_API_KEY", matches = ".+")
class QwenRetellLiveIT {

    private static final Pattern DIGIT = Pattern.compile("\\d");

    @Test
    void distortsTheSameFactProgressivelyAcrossThreeHands() {
        ObjectMapper mapper = new ObjectMapper();
        QwenHttpProvider provider = new QwenHttpProvider(
            mapper,
            envOr("QWEN_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
            System.getenv("QWEN_API_KEY"),
            envOr("QWEN_MODEL", "qwen-plus"),
            Duration.ofSeconds(60)
        );
        TownRetellGenerator generator = new QwenRetellGenerator(provider, new TemplateRetellGenerator(), mapper);

        // 一条已经模糊化过的玩家事实，交给三个性格不同的人依次转述。
        String source = "小吉最近好像总往健身房那边跑";
        Map<Integer, String> speakers = Map.of(1, "陆夏", 2, "温晴", 3, "纪麦");
        Map<Integer, List<String>> quirks = Map.of(
            1, List.of(), 2, List.of("GOSSIP_HUB"), 3, List.of("NAME_MIXUP", "EXAGGERATE"));

        String previous = source;
        StringBuilder transcript = new StringBuilder("\n原始（已模糊化）: " + source + "\n");
        for (int hops = 1; hops <= 3; hops++) {
            List<TownRetellGenerator.Retold> retold = generator.retell(List.of(new TownRetellGenerator.Request(
                "h" + hops, speakers.get(hops), "你是成长小镇的居民「" + speakers.get(hops) + "」。",
                quirks.get(hops), hops, previous)));

            assertThat(retold).hasSize(1);
            String text = retold.get(0).text();
            assertThat(text).isNotBlank();
            assertThat(DIGIT.matcher(text).find()).as("第 %d 手泄漏了数字: %s", hops, text).isFalse();
            assertThat(text.length()).as("第 %d 手过长: %s", hops, text).isLessThanOrEqualTo(40);

            transcript.append("第 ").append(hops).append(" 手（").append(speakers.get(hops))
                .append("，quirks=").append(quirks.get(hops)).append("）: ").append(text).append('\n');
            previous = text;
        }
        // 打印出来供人工判断"是否都说得通"——这一步机器替不了。
        System.out.println(transcript);
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
