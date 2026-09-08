package com.betterself.growth.town.companion.adapters;

import com.betterself.growth.ai.QwenProvider;
import com.betterself.growth.town.companion.application.ResidentMind;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class QwenResidentMind implements ResidentMind {
    private final QwenProvider provider;
    private final ObjectMapper json;
    private final boolean enabled;
    public QwenResidentMind(QwenProvider provider,ObjectMapper json,@Value("${app.ai.provider:mock}")String name,
                            @Value("${app.town.companion-model-enabled:true}")boolean enabled){this.provider=provider;this.json=json;this.enabled=enabled&&name.equals("qwen");}
    public boolean enabled(){return enabled;}
    @Override public com.betterself.growth.town.companion.domain.ConversationLifecycle.Utterance generateTurn(DialogueRequest request){
        return generateTurnMetered(request).value();
    }
    @Override public Result<com.betterself.growth.town.companion.domain.ConversationLifecycle.Utterance> generateTurnMetered(DialogueRequest request){
        return generateMetered("COMPANION_DIALOGUE", """
            你是输入perspective.self中的这一个居民，现在轮到你说话。只生成你自己的这一轮，不能写旁白或代替对方回答。
            输入conversation是截至此刻真实说过的话。认真回应对方刚才的具体内容，不要重复开场邀请或机械地答应合作。
            你有自己的性格、好奇心和主见。可以分享生活观察、突发奇想、玩笑、喜恶，产生分歧、误解或意外的话题；不必始终温柔、配合或以合作项目为中心。
            只知道自己的memories和眼前可见信息。heard为转述，reflection为个人推测；不猜远处发生了什么，不猜用户Todo/内心念头。
            topicTitle只是可能的开场线索，不是剧本。让自己的记忆、眼前所见与对方的话带出新联想，不必从邀请参加项目开始，也不必最后回到项目。虚构故事、比喻和猜想可以有想象力，但要说清它们不是已发生的事实。
            text为实际说出口的一句话，通常8至45个汉字，必要时两句；没有最低长度，不凑字数，不解释生成过程。
            不照抄自己或对方上一轮的句子，不反复引用已确定的文案，不循环说“踏实了”“听你这么说”“这个主意好”。
            已经谈妥一件事就各自去做：对方确认或致谢且没有新问题时，简短回应并leave=true；对方说回头见/晚安/先去忙，也应道别结束。
            不需要聊满轮数，通常两三次来回已足够。不要为了继续聊天而反复赞美对方。
            四人口吻有差异，但不是话题限制：owner阿禾会照顾人，也有自己的倔劲；student小川言简意赅，有边界也会吐槽；artist知夏联想跳跃、有主见，愿意追问奇怪的细节；gardener青叔实在、有一点冷幽默。让性格通过这次经历自然表现，不重复人设口号。
            stance仅none/consider/accept/decline/adjust。accept只代表你自己明确答应参与当前项目；decline是你自己婉拒；adjust仅项目主人可用，adjustment写自己当面提出的新安排。
            不承诺对方会做什么，不声称还没执行的行动已经完成。
            accept会让你现在就把参与项目排进计划，所以只用于现在有意开始的小行动。若只是想明天、改天或等有空再做，stance=consider，不应accept；没有具体未来日程时不要擅自预约日期。
            感觉话说完了，leave=true自然道别，不需要固定轮数。
            feeling写此刻自己的简短感受；evidenceIds选自己memories中0至3条依据，不能引用对方私有记忆。
            emoji由你根据这一句实际谈到的意象、动作或情绪自由生成1至3枚，可用组合emoji，不按身份、地点或预设题材固定选；不要写文字或标签。
            返回JSON字段text,leave,feeling,stance,adjustment,evidenceIds,emoji。adjustment不用时为null。
            """,request,"""
            {"type":"object","required":["text","leave","feeling","stance","adjustment","evidenceIds","emoji"],"properties":{"text":{"type":"string"},"leave":{"type":"boolean"},"feeling":{"type":"string"},"stance":{"type":"string"},"adjustment":{"type":["string","null"]},"evidenceIds":{"type":"array","items":{"type":"string"}},"emoji":{"type":"string"}}}
            """,com.betterself.growth.town.companion.domain.ConversationLifecycle.Utterance.class);
    }
    @Override public com.betterself.growth.town.companion.domain.ConversationLifecycle.Recollection summarizeConversation(SummaryRequest request){
        return summarizeConversationMetered(request).value();
    }
    @Override public Result<com.betterself.growth.town.companion.domain.ConversationLifecycle.Recollection> summarizeConversationMetered(SummaryRequest request){
        return generateMetered("COMPANION_RECOLLECTION","""
            你是perspective.self中的这一个居民。谈话已经结束，请只从你自己的角度回想刚才真实发生的交流。
            用第一人称写60至140个汉字：对方实际说了什么让你记住，你喜欢/担心/还不确定什么，这是否改变了你原来的看法。
            不写泛泛的人生道理，不替对方断言内心感受，不把愿望/承诺写成已经完成的事；允许你觉得这次交流普通或仍有分歧。
            这是主观回忆，不是全知事实。evidenceIds必须选conversationMemories中你自己的1至4条真实证据。
            feeling是这次交流留给你的简短感受。只返回JSON字段text,feeling,evidenceIds，不输出推理过程。
            """,request,"""
            {"type":"object","required":["text","feeling","evidenceIds"],"properties":{"text":{"type":"string"},"feeling":{"type":"string"},"evidenceIds":{"type":"array","items":{"type":"string"}}}}
            """,com.betterself.growth.town.companion.domain.ConversationLifecycle.Recollection.class);
    }
    private <T>T generate(String scene,String instructions,Object input,String schema,Class<T> resultType){
        return generateMetered(scene,instructions,input,schema,resultType).value();
    }
    private <T>Result<T> generateMetered(String scene,String instructions,Object input,String schema,Class<T> resultType){
        try{
            var result=provider.generateStructured(new QwenProvider.StructuredPrompt(scene,instructions+"\n输入："+json.writeValueAsString(input),schema));
            T value=json.readValue(result.json(),resultType);
            return new Result<>(value,usageOf(result));
        }
        catch(Exception e){throw new IllegalStateException("Resident generation unavailable",e);}
    }
    public Decision decide(Context context){
        return decideMetered(context).value();
    }
    @Override public Result<Decision> decideMetered(Context context){
        try {
            String instruction="""
                你是一个有自己生活的小街居民。只扮演输入的 self 这一个人，不替其他居民作答。
                只知道输入自己的 memories、眼前 nearby/visibleObjects 与当面 conversation。
                sourceType=heard 是转述，reflection 是可能出错的推测；不要把它们变成亲眼所见。
                私密 Todo 和用户内心念头不在你的知识里，不得猜测。远处人物在做什么你不知道。
                结合自己的目标、能量、情绪、关系亲疏与实际记忆决定下一步。你可以继续投入、好奇地观察、拒绝配合，也可以因一次经历想到与原来不同的愿望。
                给自己的幽默、想象力、偏好和分歧留空间，不必把每个决定写成温柔的小合作。大胆的创意可以是提案或幻想，不能伪装成已经发生的事件。
                只返回一个可执行动作与一句简短理由，不输出推理过程。
                action 仅 observe/create/help/invite/rest/study/propose；place 仅 home/cafe/street/garden。
                create/help 的 targetId 必须是 knownProjects 之一且 place 匹配；invite 只能针对 nearby 中一个人。
                如果正在conversation，可在speech写自己接着说的一句话，必须回应实际已说的话；不能替另一人说话，也不能声称尚未执行的事已完成。
                如果没在交谈，speech 留空。reason 是此刻打算，不是执行事实。evidenceIds 从输入自己的记忆ID中选 1-3 条依据。
                如果实际经历、谈话或记忆让你想到一个新愿望，可以用propose，自由创作projectTitle(36字以内)与缘由。不要复述预设项目或为了提案而提案。
                objectKind目前支持poster/flowers/books/tea四种可执行物件底座；这只是世界能表现的形式，不限制主题、风格或想象内容。这是尚未完成的新提案，之后需要真正动手，不能直接变出物件。
                已有安排要保持连贯；若新的记忆或眼前发生的事让你改变主意，说出简短缘由即可。其他action的projectTitle和objectKind填null。
                不可发明已完成的物件、承诺或事件。JSON字段严格为 action,place,targetId,reason,speech,evidenceIds,projectTitle,objectKind。
                当前这一个居民的感知输入：
                """+json.writeValueAsString(context);
            var result=provider.generateStructured(new QwenProvider.StructuredPrompt("COMPANION_RESIDENT",instruction,"""
                {"type":"object","required":["action","place","targetId","reason","speech","evidenceIds"],"properties":{"action":{"type":"string"},"place":{"type":"string"},"targetId":{"type":["string","null"]},"reason":{"type":"string"},"speech":{"type":"string"},"evidenceIds":{"type":"array","items":{"type":"string"}},"projectTitle":{"type":["string","null"]},"objectKind":{"type":["string","null"]}}}
                """));
            Decision decision=json.readValue(result.json(),Decision.class);
            return new Result<>(decision,usageOf(result));
        }catch(Exception e){throw new IllegalStateException("Resident decision unavailable",e);}
    }
    private static Usage usageOf(QwenProvider.StructuredResult result){return new Usage(result.inputTokens(),result.outputTokens());}
}
