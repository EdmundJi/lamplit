package com.betterself.growth.town.companion.adapters;

import com.betterself.growth.ai.QwenHttpProvider;
import com.betterself.growth.ai.QwenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Wires the two named {@link QwenResidentMind} instances {@link RoutingResidentMind} chooses between.
 *
 * "deepseek" wraps whichever {@link QwenProvider} bean is already active app-wide ({@code
 * QwenHttpProvider} when app.ai.provider=qwen, {@code MockQwenProvider} otherwise) - this is exactly
 * today's single-provider setup, unchanged behavior, unchanged credentials.
 *
 * "qwen3" is a second, independent {@link QwenProvider} hitting DashScope's OpenAI-compatible endpoint
 * for qwen3.8-flash, built straight from QWEN3_* credentials. When those are not set (an environment
 * that has not been updated to the second provider yet, e.g. a shared dev container) it resolves to
 * {@link UnavailableModelProvider} instead of failing application startup, so {@link
 * RoutingResidentMind}'s ordinary cross-provider failover covers "not configured" the same way it
 * covers "provider is down mid-run".
 *
 * The thinking on/off switch (see {@link QwenProvider.StructuredPrompt}) is configured per call type,
 * not per provider: both minds get the same decision/turn/summary Boolean triple, and each provider's
 * own {@link QwenHttpProvider} translates a non-null value into its own vendor's wire field.
 */
@Configuration
public class CompanionModelConfig {

    @Bean("deepseekResidentMind")
    public QwenResidentMind deepseekResidentMind(
        QwenProvider provider,
        ObjectMapper json,
        @Value("${app.ai.provider:mock}") String providerName,
        @Value("${app.town.companion-model-enabled:true}") boolean enabled,
        @Value("${app.town.companion-model.thinking.decision:false}") boolean decisionThinking,
        @Value("${app.town.companion-model.thinking.turn:}") String turnThinkingRaw,
        @Value("${app.town.companion-model.thinking.summary:}") String summaryThinkingRaw
    ) {
        return new QwenResidentMind(provider, json, providerName, enabled, "deepseek", decisionThinking, parseThinking(turnThinkingRaw), parseThinking(summaryThinkingRaw));
    }

    @Bean("qwen3Provider")
    public QwenProvider qwen3Provider(
        ObjectMapper json,
        @Value("${app.town.companion-model.qwen3.base-url:}") String baseUrl,
        @Value("${app.town.companion-model.qwen3.api-key:}") String apiKey,
        @Value("${app.town.companion-model.qwen3.model:qwen3.8-flash}") String model,
        @Value("${app.town.companion-model.qwen3.timeout:PT60S}") Duration timeout,
        @Value("${app.town.companion-model.qwen3.stream-timeout:PT70S}") Duration streamTimeout,
        @Value("${app.town.companion-model.qwen3.json-mode:true}") boolean jsonMode
    ) {
        if (isBlank(baseUrl) || isBlank(apiKey) || isBlank(model)) {
            return new UnavailableModelProvider("QWEN3_BASE_URL/QWEN3_API_KEY/QWEN3_MODEL not configured");
        }
        // "qwen" is not configurable here: this bean is, by construction, always the DashScope/Qwen
        // endpoint (QWEN3_* credentials), so there is no ambiguity to leave open the way the shared
        // app.ai.* slot above has to (it could point at any OpenAI-compatible gateway in principle).
        return new QwenHttpProvider(json, baseUrl, apiKey, model, timeout, streamTimeout, jsonMode, "qwen");
    }

    @Bean("qwen3ResidentMind")
    public QwenResidentMind qwen3ResidentMind(
        @Qualifier("qwen3Provider") QwenProvider provider,
        ObjectMapper json,
        @Value("${app.ai.provider:mock}") String providerName,
        @Value("${app.town.companion-model-enabled:true}") boolean enabled,
        @Value("${app.town.companion-model.thinking.decision:false}") boolean decisionThinking,
        @Value("${app.town.companion-model.thinking.turn:}") String turnThinkingRaw,
        @Value("${app.town.companion-model.thinking.summary:}") String summaryThinkingRaw
    ) {
        return new QwenResidentMind(provider, json, providerName, enabled, "qwen3", decisionThinking, parseThinking(turnThinkingRaw), parseThinking(summaryThinkingRaw));
    }

    private static boolean isBlank(String value) { return value == null || value.isBlank(); }

    /** "" (unset) -> null (no opinion, leave the provider's own default alone); otherwise parses "true"/"false". */
    private static Boolean parseThinking(String raw) { return isBlank(raw) ? null : Boolean.valueOf(raw); }
}
