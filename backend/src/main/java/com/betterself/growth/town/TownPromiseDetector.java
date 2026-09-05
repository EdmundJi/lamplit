package com.betterself.growth.town;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A promise worth following up on isn't inferred by the model — it's a plain lexical hook:
 * first person, a near-term time word, and a concrete action word. Detecting it costs no
 * extra model call, so it can run on every user turn; a missed one just means one fewer
 * follow-up later, and a false positive is cheap because the NPC only asks about it once
 * (see {@code TownNpcService.pendingPromiseToAsk}).
 */
final class TownPromiseDetector {

    private static final int MAX_CONTENT_LENGTH = 120;
    private static final Pattern TRAILING_QUESTION = Pattern.compile("[吗？?]\\s*$");
    private static final List<String> TIME_WORDS = List.of(
        "等下", "等会", "待会", "一会儿", "一会", "马上", "稍后", "晚点", "回头", "这就去", "现在就"
    );
    private static final List<String> COMMIT_WORDS = List.of(
        "看", "做", "写", "读", "背", "练", "开始", "去", "打卡", "完成", "学"
    );

    private TownPromiseDetector() {
    }

    static Optional<String> detect(String message) {
        String text = message == null ? "" : message.strip();
        if (text.isEmpty() || !text.contains("我") || TRAILING_QUESTION.matcher(text).find()) {
            return Optional.empty();
        }
        boolean hasTimeWord = TIME_WORDS.stream().anyMatch(text::contains);
        boolean hasCommitWord = COMMIT_WORDS.stream().anyMatch(text::contains);
        if (!hasTimeWord || !hasCommitWord) {
            return Optional.empty();
        }
        return Optional.of(text.length() > MAX_CONTENT_LENGTH ? text.substring(0, MAX_CONTENT_LENGTH) : text);
    }
}
