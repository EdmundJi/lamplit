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
 * any provider itself - it holds one {@link QwenResidentMind} per named provider ("deepseek", "qwen3")
 * and, for each call type (decision/turn/summary), tries them in the configured order, falling back to
 * the next provider on failure. Routing and failover are therefore the same list: "the order to try
 * providers in" doubles as "what to fail over to".
 *
 * Default routes (see docs/05-notes.md for the measurements behind this):
 * - decision: qwen3 first, deepseek second - qwen3 as the requested primary, deepseek as its backup.
 *   With thinking off (see app.town.companion-model.thinking.*) the two are measured near-identical on
 *   latency/tokens, so this is a configuration choice (the user's "Qwen as primary" request), not a
 *   claim that one is faster than the other - both can disable thinking, each via its own wire field.
 * - turn / summary: deepseek first, qwen3 second (unchanged from before this change). These are the
 *   creative, in-character calls (dialogue, first-person recollection); whether disabling thinking
 *   holds up on quality there is untested for either provider, so neither the default provider nor the
 *   default thinking setting for these call types moves without evidence.
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
        @Qualifier("qwen3ResidentMind") QwenResidentMind qwen3,
        @Value("${app.ai.provider:mock}") String providerName,
        @Value("${app.town.companion-model-enabled:true}") boolean modelEnabled,
        @Value("${app.town.companion-model.routes.decision:qwen3,deepseek}") String decisionRoute,
        @Value("${app.town.companion-model.routes.turn:deepseek,qwen3}") String turnRoute,
        @Value("${app.town.companion-model.routes.summary:deepseek,qwen3}") String summaryRoute
    ) {
        this.mindsByProvider = new LinkedHashMap<>();
        this.mindsByProvider.put("deepseek", deepseek);
        this.mindsByProvider.put("qwen3", qwen3);
        this.routes = Map.of(
            "decision", parseRoute(decisionRoute),
            "turn", parseRoute(turnRoute),
            "summary", parseRoute(summaryRoute)
        );
        // Matches the pre-routing gate exactly: the companion model as a whole is only ever "on" when
        // the general AI provider is the real one (app.ai.provider=qwen) and the town toggle allows it.
        // Which of the two named providers actually answers a given call is decided per call type below.
        this.enabled = modelEnabled && "qwen".equals(providerName);
    }

    private static List<String> parseRoute(String csv) {
        List<String> order = Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList();
        return order.isEmpty() ? List.of("deepseek") : order;
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

    /**
     * Tries each provider configured for callType in order, returning the first success. A failure on
     * a provider that still has a fallback logs a warning and moves on; a failure on the last one
     * propagates, which is exactly what ResidentDirector's existing consecutive-failure backoff expects.
     */
    private <T> Result<T> attempt(String callType, Function<QwenResidentMind, Result<T>> call) {
        List<String> order = routes.getOrDefault(callType, List.of("deepseek"));
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
