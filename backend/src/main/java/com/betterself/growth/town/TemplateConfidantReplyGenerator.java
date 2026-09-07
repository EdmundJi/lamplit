package com.betterself.growth.town;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link TownConfidantReplyGenerator} 的确定性兜底：没有 LLM 网关、或者网关这次没给出可用结果时，
 * 用这一套模板保证「隔天一定有回信」不会因为外部服务而落空。
 *
 * <p>只按来信的表层特征（长短、有没有问号）分桶挑一句，不解析任何语义——这就是「不知道玩家任何
 * 数据」在兜底路径上的体现：连"理解"都不做，只做"接住"。
 */
@Component
public class TemplateConfidantReplyGenerator implements TownConfidantReplyGenerator {

    private static final List<String> QUESTION_REPLIES = List.of(
        "这个问题你心里其实已经翻来覆去想过了吧。我给不出答案，但你说出来的这一刻，我在听。",
        "有些问题本来就没有标准答案。你愿意写下来问一问，本身就不容易。",
        "我没法替你回答，但我看到你在认真地问自己这件事。"
    );

    private static final List<String> LONG_REPLIES = List.of(
        "这么多话憋在心里，写下来已经不容易了——我都看到了。",
        "你写了这么长一段，说明这件事在心里占了不小的地方。我在这儿，慢慢说都行。",
        "谢谢你愿意把这么多事讲给我听，一件一件的，我都收到了。"
    );

    private static final List<String> SHORT_REPLIES = List.of(
        "短短几句，我也听进去了。",
        "嗯，我在。",
        "收到了，谢谢你愿意跟我说这些。"
    );

    private static final List<String> DEFAULT_REPLIES = List.of(
        "谢谢你愿意跟我说这些。不管是什么，我都听着呢。",
        "写给我的这些话，我都好好留着了。",
        "你说的我都听到了，不急着说别的，你想说多少就说多少。"
    );

    private static final int LONG_THRESHOLD = 60;
    private static final int SHORT_THRESHOLD = 8;

    @Override
    public List<Reply> reply(List<Request> requests) {
        List<Reply> replies = new ArrayList<>(requests.size());
        for (Request request : requests) {
            replies.add(new Reply(request.key(), pick(request)));
        }
        return replies;
    }

    private String pick(Request request) {
        String message = request.message() == null ? "" : request.message().strip();
        List<String> pool = bucket(message);
        // 内容本身只用来分桶，具体挑哪一句仍由哈希决定，保证同一封信重跑得到同一句回信。
        int index = Math.floorMod(message.hashCode(), pool.size());
        return pool.get(index);
    }

    private List<String> bucket(String message) {
        if (message.isEmpty()) {
            return DEFAULT_REPLIES;
        }
        if (message.endsWith("?") || message.endsWith("？")) {
            return QUESTION_REPLIES;
        }
        if (message.length() >= LONG_THRESHOLD) {
            return LONG_REPLIES;
        }
        if (message.length() <= SHORT_THRESHOLD) {
            return SHORT_REPLIES;
        }
        return DEFAULT_REPLIES;
    }
}
