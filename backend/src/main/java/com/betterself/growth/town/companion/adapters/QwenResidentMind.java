package com.betterself.growth.town.companion.adapters;

import com.betterself.growth.ai.QwenProvider;
import com.betterself.growth.town.companion.application.ResidentMind;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;

/**
 * Drives one resident's decide/turn/summary calls through a single {@link QwenProvider}. Despite the
 * class name this is provider-agnostic - it is reused for both the deepseek and qwen3 beans, built
 * explicitly in {@code CompanionModelConfig} (not an auto-detected {@code @Component}: with two
 * providers there is no longer one obvious instance for Spring to wire up by default). Only the
 * {@link QwenProvider} instance and {@code providerCode} passed in differ between the two. Call-type
 * routing and cross-provider failover live one layer up, in {@code RoutingResidentMind}, which is what
 * {@code ResidentDirector} actually depends on.
 */
public class QwenResidentMind implements ResidentMind {
    private record DialogueInput(Context perspective,String partnerName,String topicTitle) {}
    private record SummaryInput(Context perspective,String partnerName,java.util.List<TurnView> transcript,
                                java.util.List<MemoryView> conversationMemories) {}
    private final QwenProvider provider;
    private final ObjectMapper json;
    private final boolean enabled;
    private final String providerCode;
    private final Boolean decisionThinking;
    private final Boolean turnThinking;
    private final Boolean summaryThinking;
    public QwenResidentMind(QwenProvider provider,ObjectMapper json,@Value("${app.ai.provider:mock}")String name,
                            @Value("${app.town.companion-model-enabled:true}")boolean enabled){this(provider,json,name,enabled,null);}
    /** providerCode tags every {@link Usage} this instance produces (e.g. "qwen", "deepseek"); null keeps usage untagged. */
    public QwenResidentMind(QwenProvider provider,ObjectMapper json,String name,boolean enabled,String providerCode){
        this(provider,json,name,enabled,providerCode,null,null,null);}
    /**
     * decisionThinking/turnThinking/summaryThinking are the vendor-agnostic on/off switch (see
     * {@link QwenProvider.StructuredPrompt}), one per call type, applied regardless of which vendor
     * this instance wraps - each {@link QwenProvider} implementation is responsible for translating a
     * non-null value into its own wire field. null means "no opinion, leave the provider's default
     * alone", which is how every existing caller (and turn/summary by this class's own default) behaves.
     */
    public QwenResidentMind(QwenProvider provider,ObjectMapper json,String name,boolean enabled,String providerCode,
                            Boolean decisionThinking,Boolean turnThinking,Boolean summaryThinking){
        this.provider=provider;this.json=json;this.enabled=enabled&&name.equals("qwen");this.providerCode=providerCode;
        this.decisionThinking=decisionThinking;this.turnThinking=turnThinking;this.summaryThinking=summaryThinking;}
    public boolean enabled(){return enabled;}
    /** Only used if a call ever arrives with a null/empty {@code availableActions} - should not
     * happen in practice (ResidentSimulation.availableActions always returns a non-empty base set),
     * but the schema still needs a non-empty enum to stay valid JSON Schema. */
    private static final java.util.List<String> DECISION_ACTION_FALLBACK=java.util.List.of(
        "none","continue","resume","observe","create","help","celebrate","join","rest","study","work","read","make",
        "tend","request_drink","propose","sleep","open_cafe","continue_home","away");
    @Override public com.betterself.growth.town.companion.domain.ConversationLifecycle.Utterance generateTurn(DialogueRequest request){
        return generateTurnMetered(request).value();
    }
    @Override public Result<com.betterself.growth.town.companion.domain.ConversationLifecycle.Utterance> generateTurnMetered(DialogueRequest request){
        return generateMetered("COMPANION_DIALOGUE", """
            你是输入perspective.self中的这一个居民，现在轮到你说话。只生成你自己的这一轮，不能写旁白或代替对方回答。
            输入conversation是截至此刻真实说过的话。先接住对方最后一句里的具体事：一张被占的桌子、一杯做到一半的饮料、一本还没看完的书。没有这样的具体事，就从眼前可见物或自己手上正做的事说起。
            人说话会省略上下文。可以只说“嗯”“行”“等会儿”，可以停顿、没接住话、换题、拒绝或说完就走；不要为了显得完整而替双方总结这段谈话。
            你有自己的性格、好奇心和主见。可以分享生活观察、突发奇想、玩笑、喜恶，产生分歧、误解或意外的话题；多数日常交谈只是处理眼前的小事，不必始终温柔、配合或把话题拉向合作项目。
            只知道自己的memories和眼前可见信息。heard为转述，reflection为个人推测；不猜远处发生了什么，不猜用户Todo/内心念头。
            topicTitle只是可能的开场线索，不是剧本。让自己的记忆、眼前所见与对方的话带出新联想，不必从邀请参加项目开始，也不必最后回到项目。虚构故事、比喻和猜想可以有想象力，但要说清它们不是已发生的事实。
            text是实际说出口的一句话。常见回应只有2至28个汉字；需要把工作范围、误会或具体顾虑说清时可以自然变长。长度由此刻要说的事决定，不凑字数，不解释生成过程。
            不照抄上一轮，也不要每次都先赞同、复述项目意义、解释自己的成长或提炼人生道理。生活压力、职业方向和人设是判断时的背景，只有对方正谈到它们时才会被说出口。
            上面这些"不必"说的是不必**每次**都这样，不是说不该。别人手上没做完的事是这条街上真实的一部分：问一句、说自己看法、甚至说"这个我也来搭把手"，都是正常的一句话，不是在讨好谁，也不是把话题拉回剧本。
            已经谈妥一件事就各自去做：对方确认或致谢且没有新问题时，简短回应并leave=true；对方说回头见/晚安/先去忙，也应道别结束。
            不需要聊满轮数，通常两三次来回已足够。不要为了继续聊天而反复赞美对方。
            每个人的区别在于此刻在乎什么以及怎样回应眼前这个人，不靠固定口头禅，也不在台词里介绍自己的身份。perspective.persona如果存在，actingSelf就是这句话的说话方式（例如用忙碌代替表达、话少动手多、能言善辩），不改变想说的事，戏剧性要少而准；looseningNote描述的情形真的发生过之后，偶尔可以松一点、露出平时收着的一面，不必每次都提。
            stance仅none/consider/accept/decline/adjust。accept只代表你自己明确答应参与当前项目；decline是你自己婉拒；adjust仅项目主人可用，adjustment写自己当面提出的新安排。
            topicTitle为“眼前的生活和工作”时没有公共项目，stance必须为none；职业或吧台安排只用下面的workAction表达。
            不承诺对方会做什么，不声称还没执行的行动已经完成。
            salientPerceptions、routineCues、cafeScheduleCue或cafeNotice也可能在谈话中出现。如果你因此想睡觉、开店、打烊或回家续做，只需把眼前这句话自然说完并leave=true；这一轮仍用workAction=none，离开谈话后的decision才选择实际动作。
            accept会让你现在就把参与项目排进计划，所以只用于现在有意开始的小行动。若只是想明天、改天或等有空再做，stance=consider，不应accept；没有具体未来日程时不要擅自预约日期。
            但consider也不是更稳妥的那个选项，它只是"我还没决定"。如果你此刻确实愿意动手做一点，accept就是那句实话；一直consider下去的结果是你从没参与过任何人的任何事。
            perspective.occupation是你现在对工作的描述，careerIntent是长期职业方向，lifeIntent/currentPlan/suspendedAction是眼前生活线索。你会想到长期需要一件能维持生活的事，但这只是社会生活常识，不是考勤或惩罚；可以休息、拒绝、退出、换方向，也可以重新理解什么算工作。
            perspective.workArrangements只列出与你有关的真实安排：id是后续回应所需的唯一workTarget，status为proposed表示尚未得到双方同意，active才表示已生效。cafeOperatorId是当前经营者，canTend表示你此刻是否有权使用吧台，visibleServiceRequests只是在当前位置可见的真实请求。没有可用id或权限时不要编造。
            workAction仅none/offer_assist/offer_delegate/offer_takeover/accept_work/reject_work/end_work。没有谈工作时用none且workTarget=null。
            offer_assist表示你当面向当前经营者提出帮工；只有你正在和cafeOperatorId交谈时可用，workTarget写对方resident id。offer_delegate或offer_takeover只有你自己是cafeOperatorId且正与拟邀请者交谈时可用，workTarget写对方resident id。提议的text必须说成提议，不能说对方已经答应。
            如果你是前经营者，想回来帮忙可向当前经营者使用offer_assist；想重新受托或拿回经营权时，先用普通话说明愿望且workAction=none，是否提出delegate/takeover必须由当前经营者自己的后续回合决定。
            accept_work或reject_work只能回应workArrangements里一条与你有关、status=proposed的安排，workTarget必须照抄该安排id；只表达你自己的决定。对范围明确的短暂帮工，“行”可以是清楚的同意；正式委托或接手影响长期职责，若同意，text要明确说出自己答应照看或接手，不能用含糊的“行”静默转移责任。end_work只能用于与你有关且status=active的安排，workTarget照抄该安排id。不能代替对方同意，也不能把提议、愿望或未来计划写成已发生的事。
            感觉话说完了，leave=true自然道别，不需要固定轮数。
            feeling写此刻自己的简短感受；evidenceIds选自己memories中0至3条依据，不能引用对方私有记忆。
            emoji由你根据这一句实际谈到的意象、动作或情绪自由生成1至3枚，可用组合emoji，不按身份、地点或预设题材固定选；不要写文字或标签。
            返回JSON字段text,leave,feeling,stance,adjustment,evidenceIds,emoji,workAction,workTarget。adjustment、workTarget不用时为null。
            """,new DialogueInput(request.perspective(),request.partnerName(),request.topicTitle()),"""
            {"type":"object","required":["text","leave","feeling","stance","adjustment","evidenceIds","emoji","workAction","workTarget"],"properties":{"text":{"type":"string"},"leave":{"type":"boolean"},"feeling":{"type":"string"},"stance":{"type":"string","enum":["none","consider","accept","decline","adjust"]},"adjustment":{"type":["string","null"]},"evidenceIds":{"type":"array","items":{"type":"string"}},"emoji":{"type":"string"},"workAction":{"type":"string","enum":["none","offer_assist","offer_delegate","offer_takeover","accept_work","reject_work","end_work"]},"workTarget":{"type":["string","null"]}}}
            """,com.betterself.growth.town.companion.domain.ConversationLifecycle.Utterance.class,turnThinking);
    }
    @Override public com.betterself.growth.town.companion.domain.ConversationLifecycle.Recollection summarizeConversation(SummaryRequest request){
        return summarizeConversationMetered(request).value();
    }
    @Override public Result<com.betterself.growth.town.companion.domain.ConversationLifecycle.Recollection> summarizeConversationMetered(SummaryRequest request){
        return generateMetered("COMPANION_RECOLLECTION","""
            你是perspective.self中的这一个居民。谈话已经结束，请只从你自己的角度回想刚才真实发生的交流。
            用第一人称写通常一两句、最多80个汉字，只记下之后真可能想起的一件具体内容，以及仍没说清的地方或当时的感受。几个字能记清就不要补长；普通闲聊可以只留下一个平淡事实，不必产生新看法。
            不写泛泛的人生道理，不复述项目意义，不替对方断言内心感受，不把愿望/承诺写成已经完成的事；允许你没被说服、理解错了，或觉得这次交流没什么特别。
            perspective.persona.memoryBias如果存在，说明这类场合你通常会记住什么、倾向漏掉什么——用它决定这次留下哪个细节，而不是逐字复述这条说明本身。
            这是主观回忆，不是全知事实。evidenceIds必须选conversationMemories中你自己的1至4条真实证据。
            feeling是这次交流留给你的简短感受。只返回JSON字段text,feeling,evidenceIds，不输出推理过程。
            """,new SummaryInput(request.perspective(),request.partnerName(),ResidentMind.turnViews(request.transcript()),request.conversationMemories()),"""
            {"type":"object","required":["text","feeling","evidenceIds"],"properties":{"text":{"type":"string"},"feeling":{"type":"string"},"evidenceIds":{"type":"array","items":{"type":"string"}}}}
            """,com.betterself.growth.town.companion.domain.ConversationLifecycle.Recollection.class,summaryThinking);
    }
    private <T>Result<T> generateMetered(String scene,String instructions,Object input,String schema,Class<T> resultType,Boolean thinking){
        try{
            var result=provider.generateStructured(new QwenProvider.StructuredPrompt(scene,instructions+"\n输入："+json.writeValueAsString(input),schema,thinking));
            T value=json.readValue(result.json(),resultType);
            return new Result<>(value,usageOf(result));
        }
        catch(Exception e){throw new IllegalStateException("Resident generation unavailable",e);}
    }
    public Decision decide(Context context){
        return decideMetered(context).value();
    }
    private static final java.util.function.Predicate<Context> ALWAYS=c->true;
    private static boolean offered(Context c,String action){var a=c.availableActions();return a!=null&&a.contains(action);}
    private static boolean offeredAny(Context c,String... actions){for(String action:actions)if(offered(c,action))return true;return false;}
    /** Same trigger set Occasions.java documents for the cafe-operator cluster of rules: only
     * relevant to a resident who actually holds, once held, or is standing inside the cafe's
     * operating context - never to a bystander who has nothing to do with how the place runs. */
    private static boolean cafeRoleRelevant(Context c){
        if(c.canTend())return true;
        if(c.cafeOperatorId()!=null&&c.cafeOperatorId().equals(c.residentId()))return true;
        if(c.cafeRoleFacts()!=null&&!c.cafeRoleFacts().isEmpty())return true;
        if(c.self()!=null&&"cafe".equals(c.self().place()))return true;
        String status=c.cafeStatus();
        return status!=null&&!status.isBlank();
    }
    /** One entry per sentence of the decide() instruction: {@code ALWAYS} for what is true of every
     * decision regardless of what this resident can currently do, everything else scoped to exactly
     * the same fact that already gates the action itself in {@code ResidentSimulation.availableActions}
     * (or, for the cafe cluster, the same situational facts Occasions.java uses for the cafe-operator
     * rules). This is the same "menu vs. moment" split Occasions.java documents at the class level,
     * applied to the instruction text instead of the action menu: a measured real decision call carried
     * 4832 characters of instruction against 3517 of perception, and the celebrate paragraph alone was
     * dead weight in 100% of 243 real calls - it explains an action that was never once offered. Order
     * here is fixed and never derived from availableActions itself, so the same availableActions always
     * assembles the same instruction string regardless of the order ResidentSimulation happened to add
     * entries in. Not one word below was reworded from the original single block - only which sentences
     * are asked to appear this time changed; see PromptBalanceTest and DecisionPromptRelevanceTest for
     * the wording this locks in place. */
    private record DecisionPromptLine(java.util.function.Predicate<Context> relevantWhen,String text){}
    private static final java.util.List<DecisionPromptLine> DECISION_PROMPT_LINES=java.util.List.of(
        new DecisionPromptLine(ALWAYS,"你是一个有自己生活的小街居民。只扮演输入的 self 这一个人，不替其他居民作答。"),
        new DecisionPromptLine(ALWAYS,"只知道输入自己的 memories、眼前 nearby/visibleObjects 与当面 conversation。"),
        // The sourceTypes a DECISION can actually see, and only those. Since memory became two layers
        // (docs/01 「心智」) this call's memories are the self-account (belief + seed) plus the raw
        // working set (observed / heard) - a one-off `reflection` is in neither, so the previous version
        // of this line explained a sourceType that can no longer appear here, while `seed` went
        // unexplained. Dead guidance in a prompt is not free: it describes a world the model is not in.
        new DecisionPromptLine(ALWAYS,"sourceType=observed 是你自己看见或做过的；heard 是别人转述给你的，不要当成亲眼所见；seed 是这条街开始之前你就有的经历；belief 是你自己已经下过的判断、此刻仍然这么认为。"),
        new DecisionPromptLine(ALWAYS,"私密 Todo 和用户内心念头不在你的知识里，不得猜测。远处人物在做什么你不知道。"),
        new DecisionPromptLine(c->c.routineCues()!=null&&c.routineCues().stream().anyMatch(cue->cue.startsWith("今天这会儿的打算")),"routineCues里“今天这会儿的打算”是你今天早上自己排的，点名的选项就是去做这件事。眼前没有更要紧的人或事时，人通常照着自己的打算过；遇到了想停下来的事，改掉它也完全正常。"),
        new DecisionPromptLine(ALWAYS,"结合自己的目标、salientPerceptions里的显著体感、routineCues里的个人日常时间提示、当前计划与实际记忆决定下一步。salientPerceptions为空表示此刻没有需要特别注意的体感；routineCues是“到了我平常睡觉的时间”一类习惯事实，不等于困，也不是命令。不要猜测或要求任何隐藏数值。你可以继续投入、好奇地观察、拒绝配合，也可以因一次经历想到与原来不同的愿望。"),
        new DecisionPromptLine(ALWAYS,"给自己的幽默、想象力、偏好和分歧留空间，不必把每个决定写成温柔的小合作。"),
        new DecisionPromptLine(c->offered(c,"propose"),"大胆的创意可以是提案或幻想，不能伪装成已经发生的事件。"),
        new DecisionPromptLine(ALWAYS,"\"不必\"不等于\"不该\"：去动一件别人起头的事、走过去和谁坐一起、约谁碰个面，和读书、休息、做手上的活是同一类普通选择，不比它们更冒昧，也不需要额外的理由。"),
        new DecisionPromptLine(ALWAYS,"只返回一个可执行动作与一句简短理由，不输出推理过程。"),
        new DecisionPromptLine(ALWAYS,"availableActions是这次真能做的动作名，decisionOptions是它们对应的完整地址与目标。这次没列出的就是真的做不到。availableActions因情况实时变化：continue/continue_home/resume/tend等并非总是可选，尤其咖啡馆开始打烊（cafeStatus=closing）后，即使手头还有一件没做完的事，continue也常常不会出现；这时改选实际列出的选项。continue表示按currentPlan继续，不能重置计时或换一件事。reason一句话说清楚就好，不必展开分析，控制在80个汉字以内。"),
        new DecisionPromptLine(c->c.decisionOptions()!=null&&!c.decisionOptions().isEmpty(),"decisionOptions是规则已经算好的完整合法选择，每条把action、place、roomId和targetId锁在一起。只需返回你选的那条id作为choiceId，不要自己重拼地址。currentRoomId是你此刻所在的房间；visibleObjects只是这个房间里你真看得到的东西。"),
        new DecisionPromptLine(c->offered(c,"join"),"join表示走过去挨着某个熟人坐下（对方的桌子或旁边的位置）；在decisionOptions中选targetId是nearby那个人id的一条。这只是想坐得近一些，不代表要开口说话或已经在交谈。"),
        // invite_home / visit_home (docs/01-requirements.md 第二版「世界」「进别人家由所有权和门决定」).
        // No obligation language, no "记得回请"/"欠了一次人情" - that would be the same secrecy breach
        // lend/gift's own prompt line avoids: a resident who later writes about owing somebody must be
        // reaching that thought on their own, not repeating a hint we planted here.
        new DecisionPromptLine(c->offered(c,"invite_home"),"invite_home是请眼前这个人以后来自己家坐坐；在decisionOptions中选targetId是nearby那个人id的一条。这只是开口请一句，对方要不要真的来、什么时候来，是他自己后续的事。"),
        new DecisionPromptLine(c->offered(c,"visit_home"),"visit_home是去一个曾经请过你的人家里坐坐；在decisionOptions中选targetId是那个人id、place是home的一条。想去就去，不想去也完全正常，不去不需要理由；请你的人可能这次刚好不在家，这些都不是你能提前知道的事。"),
        new DecisionPromptLine(c->offered(c,"cook"),"cook表示用家里公用的炉子做点吃的，place写home。炉子一次只能一个人用，如果正好有人在用，你会先在旁边等一等，不是选不了；不想等、换一件事做也完全正常。"),
        new DecisionPromptLine(ALWAYS,"away表示暂时离开这条街去处理自己的事，一段时间后才会回来，回来后只有自己知道那段时间做了什么；不要在away的reason里编造离场期间发生的具体情节，那要等回来后才补一句自己的回忆。"),
        new DecisionPromptLine(c->offeredAny(c,"create","help","join"),"选create/help时用decisionOptions中targetId对应knownProjects的一条；选join时用targetId对应nearby中某人的一条。"),
        new DecisionPromptLine(ALWAYS,"none表示\"没什么特别想做的\"。它和其他选项完全平等：人一天里有大段时间并不打算做什么，这时候选none比硬挑一件事更贴近实情。不必为选它找理由，reason写一句实话就行。"),
        new DecisionPromptLine(ALWAYS,"knownProjects里的事不一定是自己起的头，startedBy写着是谁起的头（为空就是自己的）。"),
        new DecisionPromptLine(c->offered(c,"create"),"别人起头的事你也可以直接用create去添一笔，不用先问过谁、也不用等谁开口邀请你；这里没有\"那是他的事\"这回事。"),
        new DecisionPromptLine(c->offered(c,"celebrate"),"celebrate只在一件你参与做过的事真的做完、而且你人就在它所在的地方时才会出现；选decisionOptions中targetId对应那件事的一条。它是把人叫过来看看做出来的东西，不是又一次动手。"),
        new DecisionPromptLine(c->offered(c,"create"),"有些事一个人做不完：那种事的stage会直接写着\"剩下的得有人一起动手\"。这不是提示你必须去做，只是说明它停在那里的原因就是没有第二个人；要不要成为第二个人是你自己的判断，你也完全可以觉得那不关自己的事。"),
        new DecisionPromptLine(ALWAYS,"如果正在conversation，可在speech写自己接着说的一句话，先回应最后一句里的具体事；可以很短、停顿、不赞同或结束话题，不替双方总结，也不能替另一人说话或声称尚未执行的事已完成。"),
        new DecisionPromptLine(ALWAYS,"如果没在交谈，speech通常留空。reason 是此刻打算，不是执行事实。evidenceIds可从输入自己的记忆ID中选0至3条；因salientPerceptions、currentPlan或眼前事实直接做决定时可以为空，不要硬拿无关历史凑依据。若填写，只能引用自己的真实记忆。"),
        new DecisionPromptLine(c->offered(c,"propose"),"如果实际经历、谈话或记忆让你想到一个新愿望，可以用propose，自由创作projectTitle(36字以内)与缘由。不要复述预设项目或为了提案而提案。"),
        new DecisionPromptLine(c->offered(c,"propose"),"propose是例外，规则比别的动作严：place必须是cafe、street或garden之一（自己家里不算，那是私人空间不是共同的事），objectKind必须从poster/flowers/books/tea里选一个（不能留null），projectTitle不超过36字，evidenceIds填1至3条自己的真实记忆。少任何一条这个提案都不会成立。同时手上未完成的提案最多两个，已经有两个就先把它们做完再说。没有相关记忆、或者上面哪条满足不了，就不要propose。其他即时行动可以只依据当前感知或计划而让evidenceIds为空。"),
        new DecisionPromptLine(c->offered(c,"propose"),"objectKind目前支持poster/flowers/books/tea四种可执行物件底座；这只是世界能表现的形式，不限制主题、风格或想象内容。这是尚未完成的新提案，之后需要真正动手，不能直接变出物件。"),
        new DecisionPromptLine(ALWAYS,"careerIntent是长期职业方向；lifeIntent/currentPlan/pausedAction/portableAction是眼前生活线索。"),
        new DecisionPromptLine(c->offered(c,"resume"),"pausedAction是睡眠、休息或临时服务前真实暂停的任务；只有availableActions含resume时才能选对应的decisionOption，系统按权威原任务和剩余时间恢复，不能用reason改写或重新计时。"),
        new DecisionPromptLine(ALWAYS,"手头被打断后，优先决定是否接着做、推迟或放下，不必每次都回到公共项目上。"),
        new DecisionPromptLine(c->offered(c,"continue"),"反过来也一样：continue只是\"按原计划接着做\"这一个选项，不是默认值，也不比别的选项稳妥；这一天要发生什么完全取决于你什么时候不选它。"),
        new DecisionPromptLine(ALWAYS,"work/read/make 只能描述现有地点里可做的读写、制作或外出工作，不能凭空说新店、设备或收入已经存在。"),
        new DecisionPromptLine(ALWAYS,"knownPlaces是你熟悉的地点和长期用途，不代表那里此刻有空位、有人或正在营业；远处实时情况仍然不知道。"),
        new DecisionPromptLine(QwenResidentMind::cafeRoleRelevant,"咖啡馆营业时，普通居民也可以把它当作有六个独立窗边座位和共享桌的安静读写、学习、制作与见面空间，不必只有想买饮料才去。"),
        new DecisionPromptLine(ALWAYS,"你知道自己长期总得找到能维持生活的事，这是一种日常顾虑而不是考勤指标：可以休息、犹豫、换方向，也可以在真实经历后重新理解它。它通常只影响选择，不要让每个reason或speech都说“维持生活”“给自己留空间”之类的总结。perspective.persona如果存在：wantSelf是自己心底真正想要的，oughtSelf是自己给自己定的规矩而不是谁下的命令，人会在压力或反复经历后违背自己定的规矩；actingSelf只决定reason、speech说出来的方式，不决定能做什么、不能做什么。同样不要让reason变成对这些底层动机的剖析——多数时候它们只是背景。"),
        new DecisionPromptLine(QwenResidentMind::cafeRoleRelevant,"occupation、cafeOperatorId、canTend 与 visibleServiceRequests 是此刻能实际做出的工作边界；若可服务，选decisionOptions中targetId对应一条waiting请求的tend。"),
        new DecisionPromptLine(QwenResidentMind::cafeRoleRelevant,"cafeRoleFacts只陈述自己真实保留的经营权、设备熟悉度或有效帮工身份。若自己仍是cafeOperatorId但曾暂停经营，经营权没有消失；只有availableActions含open_cafe时，才可以自主选择重新开门。若经营权已经通过takeover转给别人，不能靠自己的决定夺回来，但在营业时仍可像普通居民一样去咖啡馆读写、休息、制作或见人。"),
        new DecisionPromptLine(QwenResidentMind::cafeRoleRelevant,"前经营者若想回来帮忙，可以先到店与当前经营者当面谈，再在真实对话里提出offer_assist；要重新受托或拿回经营权，必须由当前经营者在自己的回合提出delegate/takeover、本人再明确接受。这里只提供协商路径，不代表任何一方必定愿意。"),
        new DecisionPromptLine(c->offered(c,"request_drink"),"rest只表示休息，不会自动点饮料。想喝点什么、且availableActions包含request_drink时就可以选它，place必须cafe；这会创建本人真实请求并在店里等，不要假装饮料已经做好。不想喝也完全不必选。"),
        new DecisionPromptLine(QwenResidentMind::cafeRoleRelevant,"咖啡馆的帮工、委托、接手、拒绝和退出只能在两人当面的结构化对话回合里协商，不能用这次decision隔空提出或接受。仅有提议不代表能使用吧台。tend 只在canTend=true时可选。"),
        new DecisionPromptLine(QwenResidentMind::cafeRoleRelevant,"cafeStatus、cafeScheduleCue和cafeNotice是你此刻知道的营业状态、时间提示和真实通知。"),
        // Lending and giving (docs/01-requirements.md 第二版「世界」「有所有权，可借可赠，不引入货币」).
        //
        // These two lines say what the action DOES and nothing whatsoever about what it MEANS. No
        // obligation, no reciprocity, no gratitude, no "他会记得你", no hint that a borrowed thing
        // ought to come back. That omission is the entire point and it is load-bearing: docs/01 makes
        // the measurement 「有没有人写下了一笔规则从没告诉过他的债」 - whether anyone writes down a debt
        // the rules never told them about. The moment this prompt mentions owing, every resident who
        // later writes about owing is quoting us, and the finding is dead before it is measured. Same
        // red line as Deed and as CompanionWorld.Loan, which carries no reason field at all.
        //
        // Not returning something is therefore not discouraged here, and returning it is not urged:
        // return_loan simply appears in availableActions while a loan is outstanding and both people
        // are in the same place, and choosing anything else is as free as choosing it. See
        // LendingPromptBalanceTest, which asserts by keyword that no obligation language ever creeps
        // back in - the cheapest possible guard on the one measurement this mechanism exists for.
        new DecisionPromptLine(c->offeredAny(c,"lend","gift"),"lend是把自己的一件东西先借给此刻和你在同一个地方的另一个人，gift是直接给他、不再是自己的。targetId填visibleObjects里属于你自己的那件东西的id，不是人的id——谁收下由规则按\"此刻还有谁站在这儿\"确定，所以身边不止一个人、或者一个人也没有的时候，这次借或给不会发生。一件东西借出去之后，在回到你手上之前不能再借给别人。"),
        new DecisionPromptLine(c->offered(c,"return_loan"),"return_loan是让一件借出去的东西回到物主手上：借的人和物主都可以做这个动作，它只在两个人此刻在同一个地方时才出现。targetId填那件东西的id。"),
        new DecisionPromptLine(ALWAYS,"sleep表示回自己家睡觉，不要求先出现疲惫体感。"),
        new DecisionPromptLine(c->offered(c,"open_cafe"),"open_cafe只在availableActions允许时选择，去咖啡馆完成开门；这是cafeStatus=closed时唯一合法的、以cafe为place的管理动作。"),
        new DecisionPromptLine(ALWAYS,"打烊、锁门、换一种活法这些只在某一个时刻才谈得上的事，不在这份菜单里，到了那个时刻会单独问你。"),
        new DecisionPromptLine(c->offered(c,"continue_home"),"continue_home只在portableAction存在且availableActions允许时选择，place必须home；系统会按portableAction里的真实原任务和剩余时间续做，不按reason编造新工作。"),
        new DecisionPromptLine(QwenResidentMind::cafeRoleRelevant,"cafeStatus为closing或closed时，除允许的open_cafe外，不要选择其他以cafe为place的新动作。"),
        // What this resident has already spent the day doing. Always relevant, and deliberately
        // stated as a plain fact with no instruction attached to it: a run measured 78 of 243
        // decisions coming back word for word - 「刚搬来，先在家里歇会儿，整理一下心情和住处。」 over
        // and over - because a resident could see the room they were in but not the afternoon they
        // had already had. Telling them what to conclude from it would replace one of our decisions
        // with another; showing them is the whole point. See ResidentSimulation.todaySoFar.
        new DecisionPromptLine(ALWAYS,"todaySoFar是你今天到现在已经做过的事：做了什么、在哪、一共几次、一共多久。这是旁观者看得见的部分，不包含你当时怎么想。它只是事实，不是提示：已经做过三次的事，再做一次完全可以；看完它觉得今天想换点别的，也完全可以。"),
        new DecisionPromptLine(ALWAYS,"已有安排要保持连贯；若新的记忆或眼前发生的事让你改变主意，说出简短缘由即可。其他action的projectTitle和objectKind填null。"),
        new DecisionPromptLine(ALWAYS,"不可发明已完成的物件、承诺或事件。JSON字段严格为 choiceId,reason,speech,evidenceIds,projectTitle,objectKind。"),
        new DecisionPromptLine(ALWAYS,"当前这一个居民的感知输入：")
    );
    @Override public Result<Decision> decideMetered(Context context){
        try {
            StringBuilder promptBuilder=new StringBuilder();
            for(DecisionPromptLine line:DECISION_PROMPT_LINES)if(line.relevantWhen().test(context))promptBuilder.append(line.text()).append('\n');
            String instruction=promptBuilder.toString()+json.writeValueAsString(context);
            // Each choice id resolves to one rule-produced action/place/room/target leaf. A compact id
            // enum keeps those fields coupled without repeating the whole four-level matrix in both
            // context and schema; ResidentDirector resolves and rechecks it against this same snapshot.
            var result=provider.generateStructured(new QwenProvider.StructuredPrompt("COMPANION_RESIDENT",instruction,
                decisionSchema(context),
                decisionThinking));
            Decision decision=json.readValue(result.json(),Decision.class);
            return new Result<>(decision,usageOf(result));
        }catch(Exception e){throw new IllegalStateException("Resident decision unavailable",e);}
    }
    private String decisionSchema(Context context){
        // Source compatibility for hand-built fixtures predating decisionOptions. Production
        // perspectives always have exact choices and therefore always take the compact branch below.
        if(context.decisionOptions()==null||context.decisionOptions().isEmpty()){
            var offered=context.availableActions();
            String actionEnum=writeJson(offered==null||offered.isEmpty()?DECISION_ACTION_FALLBACK:offered);
            return "{\"type\":\"object\",\"required\":[\"action\",\"place\",\"targetId\",\"reason\",\"speech\",\"evidenceIds\"],\"properties\":{\"action\":{\"type\":\"string\",\"enum\":"
                +actionEnum+"},\"place\":{\"type\":\"string\",\"enum\":"+placeEnumJson(context)+"},\"targetId\":{\"type\":[\"string\",\"null\"]},\"reason\":{\"type\":\"string\"},\"speech\":{\"type\":\"string\"},\"evidenceIds\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},\"projectTitle\":{\"type\":[\"string\",\"null\"]},\"objectKind\":{\"type\":[\"string\",\"null\"]}}}";
        }
        var root=json.createObjectNode();root.put("type","object");
        var required=root.putArray("required");
        for(String name:java.util.List.of("choiceId","reason","speech","evidenceIds"))required.add(name);
        var properties=root.putObject("properties");
        var ids=context.decisionOptions()==null?java.util.List.<String>of():context.decisionOptions().stream().map(DecisionOptionView::id).toList();
        properties.putObject("choiceId").put("type","string").set("enum",json.valueToTree(ids.isEmpty()?java.util.List.of("legacy"):ids));
        properties.putObject("reason").put("type","string");properties.putObject("speech").put("type","string");
        properties.putObject("evidenceIds").put("type","array").putObject("items").put("type","string");
        properties.putObject("projectTitle").putArray("type").add("string").add("null");
        properties.putObject("objectKind").putArray("type").add("string").add("null");
        return root.toString();
    }
    /** The place enum for a schema, taken from the very list of places this same call already handed
     * the resident ({@code Context.knownPlaces}, built by {@code ResidentDirector.knownPlaces} out of
     * {@code w.locations}). Deriving it from that list rather than writing the ids out here is what
     * makes a new building addressable at all: the old literal {@code ["home","cafe","street","garden"]}
     * meant a seventh building could exist, be drawn, and still be unnameable. It also makes one thing
     * true by construction that a second hard-coded list could only make true by luck - <b>the model is
     * never offered a place it was not also told about.</b> docs/04-decisions.md 「定位是"规则收窄候选集
     * + 一次调用"…用 JSON schema 的动态 enum 锁死，非法项压根不在选项里」. */
    private String placeEnumJson(Context context){
        var ids=placeIds(context);
        return writeJson(ids.isEmpty()?PLACE_FALLBACK:ids);
    }
    /** Same, for the calls that are about a place two people could meet at or leave something in: a
     * private home is not one of those, and that exclusion is the rule's to make, not the model's. The
     * trailing null is the "I do not want to name anywhere" answer, which must stay exactly as easy to
     * give as naming somewhere (see PromptBalanceTest). */
    private String sharedPlaceEnumJson(Context context){
        var ids=new java.util.ArrayList<Object>(sharedPlaceIds(context));
        if(ids.isEmpty())ids.addAll(SHARED_PLACE_FALLBACK);
        ids.add(null);
        return writeJson(ids);
    }
    /** Serialising a list of place ids cannot actually fail; wrapped rather than declared so the two
     * schema strings that need it stay expressions. */
    private String writeJson(Object value){
        try{return json.writeValueAsString(value);}
        catch(Exception e){throw new IllegalStateException("Cannot encode place enum",e);}
    }
    private static java.util.List<String> placeIds(Context context){
        return context.knownPlaces()==null?java.util.List.of():context.knownPlaces().stream().map(KnownPlaceView::id).filter(java.util.Objects::nonNull).toList();
    }
    private static java.util.List<String> sharedPlaceIds(Context context){
        return placeIds(context).stream().filter(id->!"home".equals(id)).toList();
    }
    /** Read back to the model in its own prose so the sentence and the schema can never disagree - the
     * instruction used to spell out "cafe、street、garden" while the schema spelled out its own copy. */
    private static String sharedPlaceNames(Context context){
        var ids=sharedPlaceIds(context);
        return String.join("、",(java.util.List<CharSequence>)(java.util.List<?>)(ids.isEmpty()?SHARED_PLACE_FALLBACK:ids));
    }
    /** Only ever reached by a context built without any places at all (a hand-rolled test fixture);
     * a real world always has locations. Never a silent substitute for a real answer - see
     * DECISION_ACTION_FALLBACK, which exists for the same reason and says the same thing. */
    private static final java.util.List<String> PLACE_FALLBACK=java.util.List.of("home","cafe","street","garden");
    private static final java.util.List<String> SHARED_PLACE_FALLBACK=java.util.List.of("cafe","street","garden");
    private Usage usageOf(QwenProvider.StructuredResult result){return new Usage(result.inputTokens(),result.outputTokens(),providerCode,result.model(),result.reasoningContentPresent(),result.reasoningTokens());}
    @Override public ReactDraft react(ReactRequest request){return reactMetered(request).value();}
    @Override public Result<ReactDraft> reactMetered(ReactRequest request){
        return generateMetered("COMPANION_RESIDENT_REACT","""
            你是perspective.self这一个居民。刚才你注意到otherName就在同一个地方，他正在otherActivity。
            只回答一件事：你现在要不要为这件事做点什么。
            reaction只能从reactions里选一个。greet=走过去开口说话；join=不说话，在他旁边坐下或站着；none=看见了，继续做自己的事。
            reactions里出现invite时才有第四个选项：invite=走过去，叫他一起做你手上sharedThing这件事。它只在这种时候才问得出口——人就在眼前，你手上确实有件需要搭把手的事。想叫就叫，不想叫也不需要理由，自己做完同样正常。
            在一条小街上遇见认识的人，最常见的反应就是打个招呼。招呼很短，一句"来了""今天挺早"就够了，它不是一场对话，也不需要谁放下手上的事。
            对方在看书、在忙、在专注，不是不能打招呼的理由——一句招呼毁不掉别人的专注，何况说不说得下去是他自己的事。真正让人不出声的是别的：你自己此刻心里有事、跟这个人正别扭着、刚才才聊过、或者你就是这种不主动开口的人。
            所以每个答案都是正常的，但不要把"体谅对方"当成默认答案：绝大多数擦肩而过的熟人之间，是有一声招呼的。
            perspective.peopleHere里有你和在场每个人的关系与你记得的关于他的事；closeness是你自己的感觉，不是一个可以拿来讨价还价的分数。越熟的人越不需要理由才开口。
            perspective.persona如果存在：actingSelf决定你会用什么方式接近人（有人靠动手、有人先开口、有人宁可等对方先说），oughtSelf是你给自己定的规矩而不是命令。
            currentPlan是你手上的事。它重要不代表不能放下，也不代表必须放下。
            reason写你自己此刻的想法，一句话，不要总结人生道理，也不要解释你的性格。
            evidenceIds从perspective.memories里选0至3条真正影响了这个判断的记忆；只是打个招呼可以为空。
            只返回JSON字段reaction,reason,evidenceIds，不输出推理过程。
            """,new ReactInput(request.perspective(),request.otherName(),request.otherActivity(),request.place(),
                    request.reactions()==null||request.reactions().isEmpty()?REACTION_FALLBACK:request.reactions(),request.sharedThing()),
            """
            {"type":"object","required":["reaction","reason","evidenceIds"],"properties":{"reaction":{"type":"string","enum":"""
            +reactionEnum(request)+
            """
            },"reason":{"type":"string"},"evidenceIds":{"type":"array","items":{"type":"string"}}}}
            """,ReactDraft.class,decisionThinking);
    }
    private static final java.util.List<String> REACTION_FALLBACK=java.util.List.of("greet","join","none");
    /** The choices the rules could actually see at this instant, as a JSON enum - never a constant,
     * because invite is only among them while somebody is standing there and there is something for
     * them to be asked into. */
    private String reactionEnum(ReactRequest request){
        var choices=request.reactions()==null||request.reactions().isEmpty()?REACTION_FALLBACK:request.reactions();
        try{return json.writeValueAsString(choices);}catch(Exception e){return "[\"greet\",\"join\",\"none\"]";}
    }
    private record ReactInput(Context perspective,String otherName,String otherActivity,String place,
                              java.util.List<String> reactions,String sharedThing) {}

    @Override public ConsiderDraft consider(ConsiderRequest request){return considerMetered(request).value();}
    @Override public Result<ConsiderDraft> considerMetered(ConsiderRequest request){
        return generateMetered("COMPANION_RESIDENT_CONSIDER","""
            你是perspective.self这一个居民。fact是刚刚发生、你亲眼看见的一件事。question是要问你的那一个问题。
            只回答这一个问题，不要顺手安排别的事。choice只有两个值：occasionKey表示"做"，none表示"不做"。
            yes和no分别写清楚这两个答案各自意味着什么。两个都是正常答案。
            none不需要理由，也不是消极或者失职；多数时候它就是对的答案。不要因为有人问了你，就觉得应该做点什么。
            只有当你自己此刻确实想这么做、而且做了对你自己说得通的时候，才选occasionKey。别替别人考虑周全，也别因为"顺手"就做。
            reason写你自己此刻的一句想法，不超过40个汉字；选none时可以很短，比如"没必要"。
            speech只在这件事本身需要你当众说一句时才写你真的说出口的话，其余情况留null，不要把心里话写进去。
            evidenceIds从perspective.memories里选0至3条真正影响了这个判断的记忆，没有就留空。
            只返回JSON字段choice,reason,speech,evidenceIds，不输出推理过程。
            """,new ConsiderInput(request.perspective(),request.fact(),request.question(),request.yes(),request.no(),
                    request.key(),request.place()),
            """
            {"type":"object","required":["choice","reason","evidenceIds"],"properties":{"choice":{"type":"string","enum":"""
            +considerEnum(request)+
            """
            },"reason":{"type":"string"},"speech":{"type":["string","null"]},"evidenceIds":{"type":"array","items":{"type":"string"}}}}
            """,ConsiderDraft.class,decisionThinking);
    }
    private String considerEnum(ConsiderRequest request){
        try{return json.writeValueAsString(java.util.List.of(request.key(),"none"));}catch(Exception e){return "[\"none\"]";}
    }
    private record ConsiderInput(Context perspective,String fact,String question,String yes,String no,
                                 String occasionKey,String place) {}
    @Override public boolean plansDays(){return true;}
    @Override public DayPlanDraft planDay(DayPlanRequest request){return planDayMetered(request).value();}
    @Override public Result<DayPlanDraft> planDayMetered(DayPlanRequest request){
        var perspective=request.perspective();
        var places=perspective.knownPlaces()==null?java.util.List.<String>of():perspective.knownPlaces().stream().map(KnownPlaceView::id).distinct().toList();
        String schema;
        try{
            schema="""
            {"type":"object","required":["segments","evidenceIds"],"properties":{"segments":{"type":"array","minItems":3,"maxItems":6,"items":{"type":"object","required":["start","end","place","action","label"],"properties":{"start":{"type":"string"},"end":{"type":"string"},"place":{"type":"string","enum":%s},"action":{"type":"string","enum":%s},"label":{"type":"string"}}}},"evidenceIds":{"type":"array","items":{"type":"string"}}}}
            """.formatted(json.writeValueAsString(places.isEmpty()?java.util.List.of("home"):places),
                json.writeValueAsString(com.betterself.growth.town.companion.domain.ResidentDuties.PLAN_ACTIONS.stream().sorted().toList()));
        }catch(Exception e){throw new IllegalStateException(e);}
        return generateMetered("COMPANION_DAY_PLAN","""
            你是perspective.self中的这一个居民，醒来了，想一想今天怎么过。
            duties是你平常日子的样子：大概几点、在哪、做什么、交给谁。它是你过惯了的日子和别人对你的指望，不是考勤；今天可以照旧、挪时间、少做或不做，但没有特别的理由时，日子通常就是这么过的。
            写3到6段今天的安排，时间在usualWake之后、usualSleep之前；perspective.localTime之前已经过去的时间不用再排。段与段之间可以留空，不必填满。
            每段：start和end是大致的本地时间HH:mm；place只能用perspective.knownPlaces里的id，自己家写home；action是这段主要在做的事；label是一句不超过20个汉字的自己的话。
            依据自己的occupation、careerIntent、lifeIntent、duties和真实记忆想，不要发明还没发生的事；如果今天确实打算去搭把手或者答应过别人什么，那就是你自己的一段安排，照常写。
            evidenceIds可从自己memories中选0到3条依据，没有明显依据时留空。
            只返回JSON字段segments,evidenceIds，不输出推理过程。
            """,new DayPlanInput(perspective,request.duties(),request.usualWake(),request.usualSleep()),schema,DayPlanDraft.class,decisionThinking);
    }
    private record DayPlanInput(Context perspective,java.util.List<DutyView> duties,String usualWake,String usualSleep) {}

    @Override public ExplainDraft explain(ExplainRequest request){return explainMetered(request).value();}
    @Override public Result<ExplainDraft> explainMetered(ExplainRequest request){
        return generateMetered("COMPANION_RESIDENT_EXPLAIN","""
            你是perspective.self中的这一个居民。deeds是你最近做过、但还没顾得上细想的几件事；每一条只是旁观者会看到的动作和地点，不包含你当时心里到底怎么想——那正是要问你的。
            回头看看这几件事，给自己一个说法：这是怎么回事。
            这不是说给别人听的解释，是你自己回头想给自己的一句话，写下来之后你会真的相信自己当时就是这么想的——哪怕这句话是错的、只是在搪塞自己、含糊其辞，甚至连你自己心里都有点不踏实，这些都完全允许，甚至更真实。不必每次都诚实、周全或深刻；多数时候这就是一句很平淡的话，不必刻意提到自己的心情，也不必上升成什么道理或教训。
            perspective.persona.oughtSelf如果存在，是你自己给自己定的规矩，你的说法会不自觉地顺着它把真正在意的东西盖过去，而不是老老实实报告内心：比如规矩是不能让人看出自己在意，面对反复做同一件小事，你更可能说成是那件事本身需要做，而不是承认自己心里不舒服。不用点破这是在掩饰，正常说出口就好。
            deedIds只填这次账目里你真正想到的那几条，不必是全部；没提到的那部分就是没想起来，随它去，不必解释。
            text是你自己这句说法，一两句话，不超过40个汉字。evidenceIds可从perspective.memories中选0到3条自己的真实记忆作依据，没有明显依据时留空，不能引用不是自己的记忆。
            只返回JSON字段deedIds,text,evidenceIds，不输出推理过程。
            """,new ExplainInput(request.perspective(),request.deeds()),"""
            {"type":"object","required":["deedIds","text","evidenceIds"],"properties":{"deedIds":{"type":"array","items":{"type":"string"}},"text":{"type":"string"},"evidenceIds":{"type":"array","items":{"type":"string"}}}}
            """,ExplainDraft.class,decisionThinking);
    }
    private record ExplainInput(Context perspective,java.util.List<DeedView> deeds) {}

    @Override public ReflectDraft reflect(ReflectRequest request){return reflectMetered(request).value();}
    @Override public Result<ReflectDraft> reflectMetered(ReflectRequest request){
        return generateMetered("COMPANION_RESIDENT_REFLECT","""
            你是perspective.self中的这一个居民。source是你自己过去的一段真实记忆，没有特定要回答的问题，就是随手翻一翻，看看有没有想起点什么。
            问题只有一句：**翻完这些，你把这算成什么——随口一想，还是看出了一件反复发生的事？**
            两种答案一样正常，也一样好给：
            ——可以只是随口的感想，说不上"反复"，那就写一句就好，不必提炼出道理，也不必强求有收获；这时把supersedesKey留空，一次性的想法不比长期看法低一等，只是两回事。
            ——也可以是你确实从source里看出一件事反复出现，比如某个人总是坐在某个位置、某件事总在类似的时间发生、面对某类情况自己总是同一种反应；那就照实说出这个重复是什么，把它写成一条你会长期带着走的看法，并给出supersedesKey。
            是不是"反复"、看出的是什么、朝哪个方向，都完全是你自己的事，没有对错：没看出来就别硬凑一个，看出来了也别因为"这么快就下结论"而不写。
            supersedesKey是你自己起的一个简短代号（例如"小川-座位"），之后同一个key会替换你自己之前对同一件事、同一个人的看法。
            standingBeliefs是你现在还带着走的几条长期看法，每条都写着它自己的key（这一项可能是空的）。如果这次翻到的还是其中某一条说的那件事——无论是又一次印证了它，还是让你不再那么想——就把那一条的key原样填回supersedesKey，不要另起一个新代号；只有这件事没有任何一条旧看法覆盖到时，才自己起一个新的。
            habits里是别人眼里你常做的几件事，各带一个现成的key（这一项可能是空的）；这次如果确实又看到自己那样做了一次、并且对这件事本身有了一句自己的看法，就把那条key原样填进supersedesKey，多数时候用不上它，不必为了用它而硬扯上关系。
            text一两句话，不超过60个汉字；如果要记的是别人说过的内容本身，就直接写那句话说的是什么，不要写"提到""说过""表示"这类转述动词——除非"这件事被说出口了"这一点本身就是你要记住的事。
            evidenceIds必须从source中选1条以上、真正让你这么想的自己的记忆，不能是别人的、也不能是source之外的。
            只返回JSON字段text,evidenceIds,supersedesKey，不输出推理过程。
            """,new ReflectInput(request.perspective(),request.source(),request.habits(),request.standingBeliefs()),"""
            {"type":"object","required":["text","evidenceIds","supersedesKey"],"properties":{"text":{"type":"string"},"evidenceIds":{"type":"array","items":{"type":"string"},"minItems":1},"supersedesKey":{"type":["string","null"]}}}
            """,ReflectDraft.class,decisionThinking);
    }
    private record ReflectInput(Context perspective,java.util.List<MemoryView> source,java.util.List<HabitTraitView> habits,java.util.List<StandingBeliefView> standingBeliefs) {}

    @Override public PromiseOfferDraft promiseOffer(PromiseOfferRequest request){return promiseOfferMetered(request).value();}
    @Override public Result<PromiseOfferDraft> promiseOfferMetered(PromiseOfferRequest request){
        return generateMetered("COMPANION_RESIDENT_PROMISE_OFFER","""
            你是perspective.self中的这一个居民。peopleHere是此刻和你在同一个地方、醒着的人。
            问题只有一句：**你想不想跟其中某个人，把一件事说定一个时候？**
            "说定一个时候"就是当面对他说：我什么时候会在哪儿做什么。说出口之后这件事就搁在那儿了——到了那个点，你在或者不在，他会知道，当时听见的人也会知道。
            想说定就说，不想就不说，两种都很正常，而且"没有"是最常见的答案。不必为了回答这个问题凑一件事出来。
            但也别因为"说了就得做到、万一做不到呢"而不说。做不到会怎么样这件事，这里没有规定，也没有人会替你或替他判定什么。
            thingsNeedingHands是镇上那些一个人做不完、还没做完的事，给你看是让你自己看，不是让你从里面挑一件来许诺。你想说定的完全可以是别的：一起吃点什么、把某样东西带给谁、明早陪谁去一趟。
            要说定的话：toId填peopleHere里那个人的id；place只能是<places>之一（自己家里不算）；inHours是从现在算起大约几小时之后，可以是小数，最多24。
            what写**你到时候会去做的那件事**，不超过40个汉字。它会被原样记进在场每个人的记忆里，所以：
            —— 写成做的事（"把新苗种到花园去"），不要写成你对他说的话（不要写"青叔，咱们一起把它种了吧？"）。
            —— **里面不要出现任何时间**（不写"明早9点"、"待会儿"、"晚饭后"）。到点算哪一刻，完全由inHours决定；what里再写一个时间，两个会对不上，到时候你人在不在那儿就成了一笔糊涂账。
            不想说定就把what留空（null），别的字段也留空。
            evidenceIds填0到3条真正让你想到它的自己的记忆，没有就留空数组。
            只返回JSON字段toId,what,place,inHours,evidenceIds，不输出推理过程。
            """.replace("<places>",sharedPlaceNames(request.perspective())),new PromiseOfferInput(request.perspective(),request.peopleHere(),request.thingsNeedingHands()),
            "{\"type\":\"object\",\"required\":[\"toId\",\"what\",\"place\",\"inHours\",\"evidenceIds\"],\"properties\":{\"toId\":{\"type\":[\"string\",\"null\"]},\"what\":{\"type\":[\"string\",\"null\"]},\"place\":{\"type\":[\"string\",\"null\"],\"enum\":"
                +sharedPlaceEnumJson(request.perspective())
                +"},\"inHours\":{\"type\":[\"number\",\"null\"]},\"evidenceIds\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}}}",
            PromiseOfferDraft.class,decisionThinking);
    }
    private record PromiseOfferInput(Context perspective,java.util.List<ActorView> peopleHere,java.util.List<KnownProject> thingsNeedingHands) {}

    @Override public PromiseThought promiseSettled(PromiseSettledRequest request){return promiseSettledMetered(request).value();}
    @Override public Result<PromiseThought> promiseSettledMetered(PromiseSettledRequest request){
        return generateMetered("COMPANION_RESIDENT_PROMISE_SETTLED","""
            你是perspective.self中的这一个居民。promise是一件当时当面说定的事：byName说了他会在什么时候、在哪儿、做什么。
            那个时候到了。outcome记的只有一个事实——"came"是他人在那儿，"did_not_come"是他人不在那儿。没有别的意思，也没有人替你判断这算什么。
            role是你当时的位置："promised_to"是他答应的是你，"witnessed"是你当时在旁边听见了，"made_it"是这话是你自己说的。
            问题只有一句：**你怎么想？**
            怎么想都行，而且各种想法一样正常：可以觉得没什么、本来也没多当真；可以替他想到一个理由；可以不痛快；可以觉得他做到了是理所当然的，也可以记住他真的来了；可以想到自己下次还找不找他。
            也完全可以什么想法都没有——那就把text留空，那是真话。不要因为被问到了就凑一句出来，也不要因为"不该计较"或者"该大度一点"就把想说的话咽回去。
            aboutThem是你自己关于这个人的一些记忆，可能有关也可能无关，不必每条都用上。
            supersedesKey只在一种情况下填：你觉得这件事让你对**这个人**（或者对这条街上的事该怎么办）有了一个会长期带着走的看法。那就自己起一个简短代号（比如"周野-说定的事"），以后同一个key会替换你之前对同一件事的看法。
            只是这一次的一点感想，就把supersedesKey留空。一次感想不比一个长期看法低一等，只是两回事。
            text一两句话，不超过60个汉字，用你自己说话的方式写，不要写成对事情的总结或评语。
            evidenceIds从aboutThem里选0到3条真正让你这么想的自己的记忆；没有就留空数组。
            只返回JSON字段text,supersedesKey,evidenceIds，不输出推理过程。
            """,new PromiseSettledInput(request.perspective(),request.promise(),request.aboutThem()),"""
            {"type":"object","required":["text","supersedesKey","evidenceIds"],"properties":{"text":{"type":["string","null"]},"supersedesKey":{"type":["string","null"]},"evidenceIds":{"type":"array","items":{"type":"string"}}}}
            """,PromiseThought.class,decisionThinking);
    }
    private record PromiseSettledInput(Context perspective,PromiseView promise,java.util.List<MemoryView> aboutThem) {}

    @Override public VentureDraft venture(VentureRequest request){return ventureMetered(request).value();}
    @Override public Result<VentureDraft> ventureMetered(VentureRequest request){
        return generateMetered("COMPANION_RESIDENT_VENTURE","""
            你是perspective.self中的这一个居民。这条街上现在没有任何一件"需要不止一个人才做得成、而且还没做完"的事了——sharedThingsLeft是空的，你可以自己看。
            问题只有一个：**你自己有没有想要一件什么事，是你一个人做不成的？**
            会被问到这句话，本身就说明这条街上大家一起做的事刚好全部告一段落了。接下来这里会发生什么，取决于有没有人想到点什么；没人想到，就什么都不会发生。
            想不出来也没关系，把title留空就行，那是真话，不是失败——但也别因为觉得"应该谦虚"或者"这不该由我起头"就留空。你自己的记忆里但凡有什么一直搁在那儿——某次谈话里没接住的话、某个反复出现的念头、你羡慕过或者遗憾过的某件事、你一直想试试但一个人试不了的——那就是它，写出来。
            这件事必须是**要有别人一起才成立**的：一个人关起门来能做完的，不属于这里。
            place只能是<places>之一（自己家里不算，那是私人空间不是共同的地方）。objectKind从poster/flowers/books/tea里选一个，这只是世界能表现的形式，不限制主题。
            title不超过36个汉字，reason一两句说清楚你为什么想要它，不超过80个汉字。evidenceIds填1到3条真正让你想到它的自己的记忆。
            不要复述已经存在过的项目，也不要把愿望写成已经发生的事。
            只返回JSON字段title,place,objectKind,reason,evidenceIds，不输出推理过程。
            """.replace("<places>",sharedPlaceNames(request.perspective())),new VentureInput(request.perspective(),request.sharedThingsLeft()),
            "{\"type\":\"object\",\"required\":[\"title\",\"place\",\"objectKind\",\"reason\",\"evidenceIds\"],\"properties\":{\"title\":{\"type\":[\"string\",\"null\"]},\"place\":{\"type\":[\"string\",\"null\"],\"enum\":"
                +sharedPlaceEnumJson(request.perspective())
                +"},\"objectKind\":{\"type\":[\"string\",\"null\"],\"enum\":[\"poster\",\"flowers\",\"books\",\"tea\",null]},\"reason\":{\"type\":[\"string\",\"null\"]},\"evidenceIds\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}}}",
            VentureDraft.class,decisionThinking);
    }
    private record VentureInput(Context perspective,java.util.List<KnownProject> sharedThingsLeft) {}
}
