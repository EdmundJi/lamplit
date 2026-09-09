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
        "continue","resume","observe","create","help","celebrate","invite","join","rest","study","work","read","make",
        "tend","request_drink","change_work","propose","sleep","open_cafe","close_cafe","continue_home","away");
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
            已经谈妥一件事就各自去做：对方确认或致谢且没有新问题时，简短回应并leave=true；对方说回头见/晚安/先去忙，也应道别结束。
            不需要聊满轮数，通常两三次来回已足够。不要为了继续聊天而反复赞美对方。
            四人的区别在于此刻在乎什么以及怎样回应这个熟人，不靠固定口头禅：owner阿禾先留意店里和手上正在做的服务，也会嫌别人替自己安排；student小川先护住复习和安静，可以不展开；artist知夏容易注意颜色、形状和不协调的细节，但不用每句话都比喻；gardener青叔关心东西怎么养、谁来照料，说话实在，偶尔有冷幽默。不要在台词里介绍这些身份。perspective.persona如果存在，actingSelf就是这句话的说话方式（例如用忙碌代替表达、话少动手多、能言善辩），不改变想说的事，戏剧性要少而准；looseningNote描述的情形真的发生过之后，偶尔可以松一点、露出平时收着的一面，不必每次都提。
            stance仅none/consider/accept/decline/adjust。accept只代表你自己明确答应参与当前项目；decline是你自己婉拒；adjust仅项目主人可用，adjustment写自己当面提出的新安排。
            topicTitle为“眼前的生活和工作”时没有公共项目，stance必须为none；职业或吧台安排只用下面的workAction表达。
            不承诺对方会做什么，不声称还没执行的行动已经完成。
            salientPerceptions、routineCues、cafeScheduleCue或cafeNotice也可能在谈话中出现。如果你因此想睡觉、开店、打烊或回家续做，只需把眼前这句话自然说完并leave=true；这一轮仍用workAction=none，离开谈话后的decision才选择实际动作。
            accept会让你现在就把参与项目排进计划，所以只用于现在有意开始的小行动。若只是想明天、改天或等有空再做，stance=consider，不应accept；没有具体未来日程时不要擅自预约日期。
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
    @Override public Result<Decision> decideMetered(Context context){
        try {
            String instruction="""
                你是一个有自己生活的小街居民。只扮演输入的 self 这一个人，不替其他居民作答。
                只知道输入自己的 memories、眼前 nearby/visibleObjects 与当面 conversation。
                sourceType=heard 是转述，reflection 是可能出错的推测；不要把它们变成亲眼所见。
                私密 Todo 和用户内心念头不在你的知识里，不得猜测。远处人物在做什么你不知道。
                结合自己的目标、salientPerceptions里的显著体感、routineCues里的个人日常时间提示、当前计划与实际记忆决定下一步。salientPerceptions为空表示此刻没有需要特别注意的体感；routineCues是“到了我平常睡觉的时间”一类习惯事实，不等于困，也不是命令。不要猜测或要求任何隐藏数值。你可以继续投入、好奇地观察、拒绝配合，也可以因一次经历想到与原来不同的愿望。
                给自己的幽默、想象力、偏好和分歧留空间，不必把每个决定写成温柔的小合作。大胆的创意可以是提案或幻想，不能伪装成已经发生的事件。
                只返回一个可执行动作与一句简短理由，不输出推理过程。
                action必须严格照抄availableActions这次实际给出的字符串之一，不能选择availableActions里没有的动作，哪怕它是别的时候合法的动作名——这次没列出就是这次真的做不到，选了也不会发生，你的意图会完全落空。availableActions因情况实时变化：continue/continue_home/resume/tend等并非总是可选，尤其咖啡馆开始打烊（cafeStatus=closing）后，即使手头还有一件没做完的事，continue也常常不会出现在这次的availableActions里；这种时候如果你仍想做原来那件事（比如还在等一杯已经点的饮料），改选一个这次确实列出的动作（例如rest，重新安排一段等待/休息），而不要选continue或continue_home，那样只会被判定为这次没有发生过。continue表示按currentPlan继续，不能重置计时或换一件事。reason一句话说清楚就好，不必展开分析，控制在80个汉字以内。
                join表示走过去挨着某个熟人坐下（对方的桌子或旁边的位置），targetId填nearby中那个人的id；这只是想坐得近一些，不代表要开口说话或已经在交谈。away表示暂时离开这条街去处理自己的事，一段时间后才会回来，回来后只有自己知道那段时间做了什么；不要在away的reason里编造离场期间发生的具体情节，那要等回来后才补一句自己的回忆。
                create/help 的 targetId 必须是 knownProjects 之一且 place 匹配；invite 只能针对 nearby 中一个人。
                knownProjects里的事不一定是自己起的头，startedBy写着是谁起的头（为空就是自己的）。别人起头的事你也可以直接用create去添一笔，不用先问过谁、也不用等谁开口邀请你；这里没有"那是他的事"这回事。
                celebrate只在一件你参与做过的事真的做完、而且你人就在它所在的地方时才会出现在availableActions里，targetId填那件事。它是把人叫过来看看做出来的东西，不是又一次动手。
                有些事一个人做不完：那种事的stage会直接写着"剩下的得有人一起动手"。这不是提示你必须去做，只是说明它停在那里的原因就是没有第二个人；要不要成为第二个人是你自己的判断，你也完全可以觉得那不关自己的事。
                如果正在conversation，可在speech写自己接着说的一句话，先回应最后一句里的具体事；可以很短、停顿、不赞同或结束话题，不替双方总结，也不能替另一人说话或声称尚未执行的事已完成。
                如果没在交谈，speech通常留空；close_cafe或当前经营者用change_work结束营业时是例外，现场还有清醒的人就用speech写自己真正说出的简短通知。reason 是此刻打算，不是执行事实。evidenceIds可从输入自己的记忆ID中选0至3条；因salientPerceptions、currentPlan或眼前事实直接做决定时可以为空，不要硬拿无关历史凑依据。若填写，只能引用自己的真实记忆。
                如果实际经历、谈话或记忆让你想到一个新愿望，可以用propose，自由创作projectTitle(36字以内)与缘由。不要复述预设项目或为了提案而提案。
                propose是例外，规则比别的动作严：place必须是cafe、street或garden之一（自己家里不算，那是私人空间不是共同的事），objectKind必须从poster/flowers/books/tea里选一个（不能留null），projectTitle不超过36字，evidenceIds填1至3条自己的真实记忆。少任何一条这个提案都不会成立。同时手上未完成的提案最多两个，已经有两个就先把它们做完再说。没有相关记忆、或者上面哪条满足不了，就不要propose。其他即时行动可以只依据当前感知或计划而让evidenceIds为空。
                objectKind目前支持poster/flowers/books/tea四种可执行物件底座；这只是世界能表现的形式，不限制主题、风格或想象内容。这是尚未完成的新提案，之后需要真正动手，不能直接变出物件。
                careerIntent是长期职业方向；lifeIntent/currentPlan/pausedAction/portableAction是眼前生活线索。pausedAction是睡眠、休息或临时服务前真实暂停的任务；只有availableActions含resume时才能选择resume，place照抄pausedAction.place，系统按权威原任务和剩余时间恢复，不能用reason改写或重新计时。手头被打断后，优先决定是否接着做、推迟或放下，不必每次都回到公共项目上。反过来也一样：continue只是"按原计划接着做"这一个选项，不是默认值，也不比别的选项稳妥；这一天要发生什么完全取决于你什么时候不选它。work/read/make 只能描述现有地点里可做的读写、制作或外出工作，不能凭空说新店、设备或收入已经存在。
                knownPlaces是你熟悉的地点和长期用途，不代表那里此刻有空位、有人或正在营业；远处实时情况仍然不知道。咖啡馆营业时，普通居民也可以把它当作有六个独立窗边座位和共享桌的安静读写、学习、制作与见面空间，不必只有想买饮料才去。
                你知道自己长期总得找到能维持生活的事，这是一种日常顾虑而不是考勤指标：可以休息、犹豫、换方向，也可以在真实经历后重新理解它。它通常只影响选择，不要让每个reason或speech都说“维持生活”“给自己留空间”之类的总结。perspective.persona如果存在：wantSelf是自己心底真正想要的，oughtSelf是自己给自己定的规矩而不是谁下的命令，人会在压力或反复经历后违背自己定的规矩；actingSelf只决定reason、speech说出来的方式，不决定能做什么、不能做什么。同样不要让reason变成对这些底层动机的剖析——多数时候它们只是背景。occupation、cafeOperatorId、canTend 与 visibleServiceRequests 是此刻能实际做出的工作边界；若可服务，tend 的 targetId 选一条 waiting 请求。
                cafeRoleFacts只陈述自己真实保留的经营权、设备熟悉度或有效帮工身份。若自己仍是cafeOperatorId但曾暂停经营，经营权没有消失；只有availableActions含open_cafe时，才可以自主选择重新开门。若经营权已经通过takeover转给别人，不能靠自己的决定夺回来，但在营业时仍可像普通居民一样去咖啡馆读写、休息、制作或见人。
                前经营者若想回来帮忙，可以先到店与当前经营者当面谈，再在真实对话里提出offer_assist；要重新受托或拿回经营权，必须由当前经营者在自己的回合提出delegate/takeover、本人再明确接受。这里只提供协商路径，不代表任何一方必定愿意。
                rest只表示休息，不会自动点饮料。想喝点什么、且availableActions包含request_drink时就可以选它，place必须cafe；这会创建本人真实请求并在店里等，不要假装饮料已经做好。不想喝也完全不必选。
                咖啡馆的帮工、委托、接手、拒绝和退出只能在两人当面的结构化对话回合里协商，不能用这次decision隔空提出或接受。仅有提议不代表能使用吧台。tend 只在canTend=true时可选。change_work 用 reason 描述自己想尝试的新生活，它可能让咖啡馆暂时无人服务，不会自动产生接手者。
                cafeStatus、cafeScheduleCue和cafeNotice是你此刻知道的营业状态、时间提示和真实通知。sleep表示回自己家睡觉，不要求先出现疲惫体感。open_cafe只在availableActions允许时选择，去咖啡馆完成开门；这是cafeStatus=closed时唯一合法的、以cafe为place的管理动作。close_cafe是本人决定开始打烊；place必须cafe，现场还有清醒的人时speech要写自己实际说出的简短通知，不能在reason里假装通知过。continue_home只在portableAction存在且availableActions允许时选择，place必须home；系统会按portableAction里的真实原任务和剩余时间续做，不按reason编造新工作。cafeStatus为closing或closed时，除允许的open_cafe外，不要选择其他以cafe为place的新动作。
                已有安排要保持连贯；若新的记忆或眼前发生的事让你改变主意，说出简短缘由即可。其他action的projectTitle和objectKind填null。
                不可发明已完成的物件、承诺或事件。JSON字段严格为 action,place,targetId,reason,speech,evidenceIds,projectTitle,objectKind。
                当前这一个居民的感知输入：
                """+json.writeValueAsString(context);
            // The action enum below used to be the full, static set of every action that is EVER
            // legal somewhere - which meant the schema itself kept telling the model "continue" and
            // "continue_home" were always fine to pick, even on a call where this resident's own
            // availableActions did not offer them (most commonly: waiting on an already-requested
            // drink in the cafe while it starts closing, where the rules deliberately withdraw
            // "continue" and there is no portable substitute). The natural-language instruction above
            // already says "pick only from availableActions", but a schema hint that silently
            // contradicts that instruction is exactly the kind of thing a model follows over prose.
            // Scoping the enum to this call's own context.availableActions() makes the schema agree
            // with the instruction instead of undermining it - the model still freely chooses among
            // its real options, this only stops the schema from advertising fake ones.
            var offeredActions=context.availableActions();
            String actionEnumJson=json.writeValueAsString(offeredActions==null||offeredActions.isEmpty()?DECISION_ACTION_FALLBACK:offeredActions);
            var result=provider.generateStructured(new QwenProvider.StructuredPrompt("COMPANION_RESIDENT",instruction,
                "{\"type\":\"object\",\"required\":[\"action\",\"place\",\"targetId\",\"reason\",\"speech\",\"evidenceIds\"],\"properties\":{\"action\":{\"type\":\"string\",\"enum\":"
                    +actionEnumJson+"},\"place\":{\"type\":\"string\",\"enum\":[\"home\",\"cafe\",\"street\",\"garden\"]},\"targetId\":{\"type\":[\"string\",\"null\"]},\"reason\":{\"type\":\"string\"},\"speech\":{\"type\":\"string\"},\"evidenceIds\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},\"projectTitle\":{\"type\":[\"string\",\"null\"]},\"objectKind\":{\"type\":[\"string\",\"null\"]}}}",
                decisionThinking));
            Decision decision=json.readValue(result.json(),Decision.class);
            return new Result<>(decision,usageOf(result));
        }catch(Exception e){throw new IllegalStateException("Resident decision unavailable",e);}
    }
    private Usage usageOf(QwenProvider.StructuredResult result){return new Usage(result.inputTokens(),result.outputTokens(),providerCode,result.model(),result.reasoningContentPresent(),result.reasoningTokens());}
    @Override public ReactDraft react(ReactRequest request){return reactMetered(request).value();}
    @Override public Result<ReactDraft> reactMetered(ReactRequest request){
        return generateMetered("COMPANION_RESIDENT_REACT","""
            你是perspective.self这一个居民。刚才你注意到otherName就在同一个地方，他正在otherActivity。
            只回答一件事：你现在要不要为这件事做点什么。
            reaction只能是greet/join/none三选一。greet=走过去开口说话；join=不说话，在他旁边坐下或站着；none=看见了，继续做自己的事。
            在一条小街上遇见认识的人，最常见的反应就是打个招呼。招呼很短，一句"来了""今天挺早"就够了，它不是一场对话，也不需要谁放下手上的事。
            对方在看书、在忙、在专注，不是不能打招呼的理由——一句招呼毁不掉别人的专注，何况说不说得下去是他自己的事。真正让人不出声的是别的：你自己此刻心里有事、跟这个人正别扭着、刚才才聊过、或者你就是这种不主动开口的人。
            所以三个答案都是正常的，但不要把"体谅对方"当成默认答案：绝大多数擦肩而过的熟人之间，是有一声招呼的。
            perspective.peopleHere里有你和在场每个人的关系与你记得的关于他的事；closeness是你自己的感觉，不是一个可以拿来讨价还价的分数。越熟的人越不需要理由才开口。
            perspective.persona如果存在：actingSelf决定你会用什么方式接近人（有人靠动手、有人先开口、有人宁可等对方先说），oughtSelf是你给自己定的规矩而不是命令。
            currentPlan是你手上的事。它重要不代表不能放下，也不代表必须放下。
            reason写你自己此刻的想法，一句话，不要总结人生道理，也不要解释你的性格。
            evidenceIds从perspective.memories里选0至3条真正影响了这个判断的记忆；只是打个招呼可以为空。
            只返回JSON字段reaction,reason,evidenceIds，不输出推理过程。
            """,new ReactInput(request.perspective(),request.otherName(),request.otherActivity(),request.place()),"""
            {"type":"object","required":["reaction","reason","evidenceIds"],"properties":{"reaction":{"type":"string","enum":["greet","join","none"]},"reason":{"type":"string"},"evidenceIds":{"type":"array","items":{"type":"string"}}}}
            """,ReactDraft.class,decisionThinking);
    }
    private record ReactInput(Context perspective,String otherName,String otherActivity,String place) {}
    @Override public DayPlanDraft planDay(DayPlanRequest request){return planDayMetered(request).value();}
    @Override public Result<DayPlanDraft> planDayMetered(DayPlanRequest request){
        return generateMetered("COMPANION_DAY_PLAN","""
            你是perspective.self中的这一个居民，早上想一想今天大致想怎么过。
            只写3到4段粗略的想法，不是带时间点的日程表，不必覆盖一整天的每一刻；每段不超过20个汉字。
            这只是此刻的打算，不是承诺；现实随时可能打断、推迟或让你放弃其中一段，这很正常。
            依据自己的occupation、careerIntent、lifeIntent和真实记忆想，不复述别人的项目，也不要发明还没发生的事。
            evidenceIds可从自己memories中选0到3条依据，没有明显依据时留空。
            只返回JSON字段segments,evidenceIds，不输出推理过程。
            """,new DayPlanInput(request.perspective()),"""
            {"type":"object","required":["segments","evidenceIds"],"properties":{"segments":{"type":"array","items":{"type":"string"},"minItems":3,"maxItems":4},"evidenceIds":{"type":"array","items":{"type":"string"}}}}
            """,DayPlanDraft.class,decisionThinking);
    }
    private record DayPlanInput(Context perspective) {}

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
            大多数时候翻完不会有什么特别的结论，那就写一句很随口的感想，不必每次都提炼出道理，也不必强求有收获。
            只有当你自己真的从source里看出一件事反复出现——比如某个人总是坐在某个位置、某件事总在类似的时间发生、面对某类情况自己总是同一种反应——才把它写成一条你会长期带着走的看法，并给出supersedesKey；这必须是你自己从材料里看出来的重复，不是替你数好、指定好方向的规律，没看出反复出现的东西就不要勉强编一个。
            如果只是这一次随口想到的感想，说不上"反复出现"，就把supersedesKey留空——那只是一次性的想法，不要占用长期看法这一层。
            supersedesKey是你自己起的一个简短代号（例如"小川-座位"），之后同一个key会替换你自己之前对同一件事、同一个人的看法；只有真正认定这是长期看法时才给它起名字。
            habits里是别人眼里你常做的几件事，各带一个现成的key（这一项可能是空的）。它不是要你逐条点评的清单，绝大多数次翻记忆都跟它无关，不要为了用它而用它。
            只有当你这次确实从source里看出自己又那样做了一次、并且对这件事本身有了一句自己的看法时，才把那一条的key原样填进supersedesKey，把看法写进text。看法是什么、朝哪个方向，完全是你自己的事，没有对错。
            text一两句话，不超过60个汉字。evidenceIds必须从source中选1条以上、真正让你这么想的自己的记忆，不能是别人的、也不能是source之外的。
            只返回JSON字段text,evidenceIds,supersedesKey，不输出推理过程。
            """,new ReflectInput(request.perspective(),request.source(),request.habits()),"""
            {"type":"object","required":["text","evidenceIds","supersedesKey"],"properties":{"text":{"type":"string"},"evidenceIds":{"type":"array","items":{"type":"string"},"minItems":1},"supersedesKey":{"type":["string","null"]}}}
            """,ReflectDraft.class,decisionThinking);
    }
    private record ReflectInput(Context perspective,java.util.List<MemoryView> source,java.util.List<HabitTraitView> habits) {}
}
