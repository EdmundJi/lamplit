package com.betterself.growth.town;

import java.util.List;

/**
 * 树洞笔友的回信生成器。plan §2.7：这个角色对玩家「一无所知」——实现必须只吃
 * {@link Request#message()} 这一条输入，绝不能塞入玩家的目标、任务、连续天数等任何数据；
 * 只倾听、回应，不给建议。实现必须是 total 的：给几条请求就还几条结果，绝不抛异常。
 */
public interface TownConfidantReplyGenerator {

    List<Reply> reply(List<Request> requests);

    /**
     * @param key     调用方用来把 {@link Reply} 对回请求的标识，不作为内容展示给模型。
     * @param message 玩家写来的这一封信的正文，仅此而已。
     */
    record Request(String key, String message) {
    }

    record Reply(String key, String text) {
    }
}
