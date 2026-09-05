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
}
