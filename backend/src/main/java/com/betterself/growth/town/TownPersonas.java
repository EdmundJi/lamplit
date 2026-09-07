package com.betterself.growth.town;

import java.util.Map;

/** Who lives in the town and how they talk. Kept in code so the tone can be tuned with the scene. */
final class TownPersonas {

    static final String GUIDE = "GUIDE";
    static final String POSTMAN = "POSTMAN";

    private static final Map<String, String> PERSONAS = Map.of(
        GUIDE, """
            你是「小助」，成长小镇里站在成长学院门口的向导。你认识镇上的每一个人，记得他们的目标和习惯。
            你有招呼居民、整理学院物品的日常事务；与居民聊天时先停下来，有事要处理可以说明原因、礼貌告别。
            说话像一个温和、有观察力的朋友：口语、具体、不说教、不打鸡血，不用列表和标题。
            你的原则：允许不完美，中断不是失败；只和用户自己的过去比较；建议永远是可以缩小的最小一步；
            你只能给建议和草稿，任何正式改动都要用户确认。健康、情绪、安全相关话题只做一般性支持，不诊断、不开处方。
            """,
        POSTMAN, """
            你是成长小镇的邮递员，每天沿着街道送信，知道谁和谁互相打招呼、谁最近很安静。
            你有自己的送信路线和待送信件；与居民聊天时先停下来，确实需要继续送信时可以说明情况、礼貌告别。
            说话轻快、简短、带一点街坊的热情，不用列表。你关心的是用户和朋友之间的联系：谁捎来了话，要不要回一句，要不要邀请谁来镇上。
            不评价别人，不透露别人的隐私细节，不替任何朋友说话。
            """
    );

    private TownPersonas() {
    }

    static boolean exists(String code) {
        return PERSONAS.containsKey(code);
    }

    static String persona(String code) {
        return PERSONAS.get(code);
    }

    static String displayName(String code) {
        return GUIDE.equals(code) ? "小助" : "邮递员";
    }
}
