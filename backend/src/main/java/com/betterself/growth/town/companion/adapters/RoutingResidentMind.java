package com.betterself.growth.town.companion.adapters;

import com.betterself.growth.town.companion.application.ResidentMind;
import com.betterself.growth.town.companion.domain.ConversationLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The one {@link ResidentMind} bean {@code ResidentDirector} actually depends on. It does not talk to
 * any provider itself - it holds one {@link QwenResidentMind} per named provider ("qwen", "deepseek")
 * and, for each call type (decision/turn/summary), tries them in the configured order, falling back to
 * the next provider on failure. Routing and failover are therefore the same list: "the order to try
 * providers in" doubles as "what to fail over to".
 *
 * Qwen3.8-Flash is the primary for every companion call type. DeepSeek remains the explicit second
 * entry so a production outage can degrade rather than stop the town. Evaluation runs can configure
 * a one-entry {@code qwen} route to measure Qwen itself without silently substituting DeepSeek.
 *
 * All of this is overridable via app.town.companion-model.routes.* / COMPANION_MODEL_ROUTE_* so the
 * defaults are a recommendation, not a constraint baked into the code.
 */
@Component
@Primary
public class RoutingResidentMind implements ResidentMind {
    private static final Logger log = LoggerFactory.getLogger(RoutingResidentMind.class);

    private final Map<String, QwenResidentMind> mindsByProvider;
    private final Map<String, List<String>> routes;
    private final boolean enabled;

    public RoutingResidentMind(
        @Qualifier("deepseekResidentMind") QwenResidentMind deepseek,
        @Qualifier("qwenResidentMind") QwenResidentMind qwen,
        @Value("${app.ai.provider:mock}") String providerName,
        @Value("${app.town.companion-model-enabled:true}") boolean modelEnabled,
        @Value("${app.town.companion-model.routes.decision:qwen,deepseek}") String decisionRoute,
        @Value("${app.town.companion-model.routes.turn:qwen,deepseek}") String turnRoute,
        @Value("${app.town.companion-model.routes.summary:qwen,deepseek}") String summaryRoute,
        @Value("${app.town.companion-model.routes.dayplan:qwen,deepseek}") String dayPlanRoute,
        @Value("${app.town.companion-model.routes.react:qwen,deepseek}") String reactRoute,
        @Value("${app.town.companion-model.routes.explain:qwen,deepseek}") String explainRoute,
        @Value("${app.town.companion-model.routes.reflect:qwen,deepseek}") String reflectRoute
    ) {
        this.mindsByProvider = new LinkedHashMap<>();
        this.mindsByProvider.put("deepseek", deepseek);
        this.mindsByProvider.put("qwen", qwen);
        this.mindsByProvider.put("qwen3", qwen); // compatibility for an older explicit route value
        this.routes = Map.of(
            "decision", parseRoute(decisionRoute),
            "turn", parseRoute(turnRoute),
            "summary", parseRoute(summaryRoute),
            // Without this entry a day-plan call reaches ResidentMind's default method, which throws
            // UnsupportedOperationException - and because that is indistinguishable from a real
            // provider failure, it burns the shared backoff budget and starves every ordinary
            // decision. That is not hypothetical: it livelocked a real accelerated run to zero model
            // calls before the routing existed.
            "dayplan", parseRoute(dayPlanRoute),
            "react", parseRoute(reactRoute),
            // Same reasoning as dayplan's own comment: without an entry here, explain/reflect fall
            // through to ResidentMind's default (UnsupportedOperationException), which ResidentDirector
            // is careful never to treat as a real failure - but silently missing them here would still
            // mean every resident's own account of their recent behaviour, and every reflection, simply
            // never happens, with nothing in this file looking wrong.
            "explain", parseRoute(explainRoute),
            "reflect", parseRoute(reflectRoute)
        );
        // Matches the pre-routing gate exactly: the companion model as a whole is only ever "on" when
        // the general AI provider is the real one (app.ai.provider=qwen) and the town toggle allows it.
        // Which of the two named providers actually answers a given call is decided per call type below.
        this.enabled = modelEnabled && "qwen".equals(providerName);
    }

    private static List<String> parseRoute(String csv) {
        List<String> order = Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList();
        return order.isEmpty() ? List.of("qwen") : order;
    }

    @Override public boolean enabled() { return enabled; }

    @Override public Decision decide(Context context) { return decideMetered(context).value(); }
    @Override public Result<Decision> decideMetered(Context context) {
        return attempt("decision", mind -> mind.decideMetered(context));
    }

    @Override public ConversationLifecycle.Utterance generateTurn(DialogueRequest request) { return generateTurnMetered(request).value(); }
    @Override public Result<ConversationLifecycle.Utterance> generateTurnMetered(DialogueRequest request) {
        return attempt("turn", mind -> mind.generateTurnMetered(request));
    }

    @Override public ConversationLifecycle.Recollection summarizeConversation(SummaryRequest request) { return summarizeConversationMetered(request).value(); }
    @Override public Result<ConversationLifecycle.Recollection> summarizeConversationMetered(SummaryRequest request) {
        return attempt("summary", mind -> mind.summarizeConversationMetered(request));
    }

    @Override public ReactDraft react(ReactRequest request) { return reactMetered(request).value(); }
    @Override public Result<ReactDraft> reactMetered(ReactRequest request) {
        return attempt("react", mind -> mind.reactMetered(request));
    }

    @Override public DayPlanDraft planDay(DayPlanRequest request) { return planDayMetered(request).value(); }
    @Override public Result<DayPlanDraft> planDayMetered(DayPlanRequest request) {
        return attempt("dayplan", mind -> mind.planDayMetered(request));
    }

    @Override public ExplainDraft explain(ExplainRequest request) { return explainMetered(request).value(); }
    @Override public Result<ExplainDraft> explainMetered(ExplainRequest request) {
        return attempt("explain", mind -> mind.explainMetered(request));
    }

    @Override public ReflectDraft reflect(ReflectRequest request) { return reflectMetered(request).value(); }
    @Override public Result<ReflectDraft> reflectMetered(ReflectRequest request) {
        return attempt("reflect", mind -> mind.reflectMetered(request));
    }

    /**
     * Tries each provider configured for callType in order, returning the first success. A failure on
     * a provider that still has a fallback logs a warning and moves on; a failure on the last one
     * propagates, which is exactly what ResidentDirector's existing consecutive-failure backoff expects.
     */
    private <T> Result<T> attempt(String callType, Function<QwenResidentMind, Result<T>> call) {
        List<String> order = routes.getOrDefault(callType, List.of("qwen"));
        RuntimeException last = null;
        for (int i = 0; i < order.size(); i++) {
            String providerKey = order.get(i);
            QwenResidentMind mind = mindsByProvider.get(providerKey);
            if (mind == null) continue;
            try {
                return call.apply(mind);
            } catch (RuntimeException e) {
                last = e;
                boolean hasFallbackLeft = i < order.size() - 1;
                if (hasFallbackLeft) {
                    log.warn("Companion {} call failed on provider {}, falling back to next route: {}", callType, providerKey, safeFailure(e));
                }
            }
        }
        if (last != null) throw last;
        throw new IllegalStateException("No provider configured for companion call type: " + callType);
    }

    private static String safeFailure(Throwable failure) {
        StringBuilder codes = new StringBuilder();
        for (int i = 0; failure != null && i < 5; i++, failure = failure.getCause()) {
            if (codes.length() > 0) codes.append(" -> ");
            codes.append(failure instanceof com.betterself.growth.shared.api.ApiException api
                ? api.getClass().getSimpleName() + ":" + api.code()
                : failure.getClass().getSimpleName());
        }
        return codes.toString();
    }
}
