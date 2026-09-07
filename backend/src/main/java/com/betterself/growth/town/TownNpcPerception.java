package com.betterself.growth.town;

/**
 * Turns raw numbers into the coarse impression a person who sees you now and then would
 * actually have — never the numbers themselves, and never a day's task titles or times.
 * Kept separate from {@link TownFacts} so the boundary between "what got measured" and
 * "what the NPC is allowed to say" stays visible at a glance.
 */
final class TownNpcPerception {

    private TownNpcPerception() {
    }

    static String levelBucket(int level) {
        if (level <= 3) {
            return "刚起步不久";
        }
        if (level <= 8) {
            return "已经小有火候";
        }
        if (level <= 14) {
            return "相当扎实";
        }
        return "算是镇上的老资格了";
    }

    static String frequencyBucket(int completedLast7Days) {
        if (completedLast7Days >= 5) {
            return "这周几乎天天见你出来走动";
        }
        if (completedLast7Days >= 2) {
            return "这周偶尔见你出来走动";
        }
        return "有些日子没见你出来走动了";
    }

    static String guidePresenceLine(boolean presentAtAcademyToday) {
        return presentAtAcademyToday ? "今天你在学院附近露过面。" : "今天还没在学院这边看到你。";
    }

    static String postmanSignalLine(int unreadWithFriends) {
        return unreadWithFriends > 0
            ? "路上听说你和朋友之间还有 " + unreadWithFriends + " 条没读的消息。"
            : "朋友那边这两天挺安静，没什么新消息。";
    }

    /**
     * 第三人称的节奏印象。和 {@link #frequencyBucket} 是同一份分桶，区别只在人称：
     * frequencyBucket 是 NPC 当面对你说的（"这周几乎天天见你出来走动"），这一句是 NPC 背着你
     * 对别人说的。会进传播网络的是后者，所以它必须能被第三个人转述出去而不别扭。
     */
    static String rhythmLine(int completedLast7Days) {
        if (completedLast7Days >= 5) {
            return "最近几乎天天都能碰上";
        }
        if (completedLast7Days >= 2) {
            return "最近偶尔能碰上";
        }
        return "有阵子没见着人了";
    }

    // 把五维度换成一句"地点化"的模糊印象——只说"常去哪儿"，不说具体做了什么任务。
    static String dimensionFocusLine(String dimensionCode) {
        if (dimensionCode == null) {
            return null;
        }
        return switch (dimensionCode) {
            case "KNOWLEDGE" -> "好像总往学院那边跑";
            case "HEALTH" -> "好像总往健身房那边跑";
            case "CAREER" -> "好像总在忙工作上的事";
            case "RELATIONSHIP" -> "好像总在张罗朋友之间的事";
            case "WELLBEING" -> "好像总爱一个人在公园待着";
            default -> null;
        };
    }

    // 连续天数分桶后再模糊化——绝不把具体天数说出口。
    static String streakHintLine(int longestStreak) {
        if (longestStreak <= 0) {
            return null;
        }
        if (longestStreak <= 2) {
            return "偶尔来一下";
        }
        if (longestStreak <= 6) {
            return "连着好些天了";
        }
        return "好久没断过了";
    }
}
