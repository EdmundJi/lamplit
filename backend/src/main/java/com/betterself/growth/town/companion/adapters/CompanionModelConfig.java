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
 * The app-wide primary {@link QwenProvider} is Qwen3.8-Flash and is reused by the town's "qwen"
 * route. DeepSeek is an independently configured town fallback. Missing fallback credentials produce
 * an {@link UnavailableModelProvider} rather than preventing the Qwen-primary application from starting.
 *
 * The thinking on/off switch (see {@link QwenProvider.StructuredPrompt}) is configured per call type,
 * not per provider: both minds get the same decision/turn/summary Boolean triple, and each provider's
 * own {@link QwenHttpProvider} translates a non-null value into its own vendor's wire field.
 */
@Configuration
public class CompanionModelConfig {

    @Bean("qwenResidentMind")
    public QwenResidentMind qwenResidentMind(
        QwenProvider provider,
        ObjectMapper json,
        @Value("${app.ai.provider:mock}") String providerName,
        @Value("${app.town.companion-model-enabled:true}") boolean enabled,
        @Value("${app.town.companion-model.thinking.decision:false}") boolean decisionThinking,
        @Value("${app.town.companion-model.thinking.turn:false}") String turnThinkingRaw,
        @Value("${app.town.companion-model.thinking.summary:false}") String summaryThinkingRaw
    ) {
        return new QwenResidentMind(provider, json, providerName, enabled, "qwen", decisionThinking, parseThinking(turnThinkingRaw), parseThinking(summaryThinkingRaw));
    }

    @Bean("deepseekProvider")
    public QwenProvider deepseekProvider(
        ObjectMapper json,
        @Value("${app.town.companion-model.deepseek.base-url:}") String baseUrl,
        @Value("${app.town.companion-model.deepseek.api-key:}") String apiKey,
        @Value("${app.town.companion-model.deepseek.model:deepseek-v4-flash}") String model,
        @Value("${app.town.companion-model.deepseek.timeout:PT120S}") Duration timeout,
        @Value("${app.town.companion-model.deepseek.stream-timeout:PT130S}") Duration streamTimeout,
        @Value("${app.town.companion-model.deepseek.json-mode:true}") boolean jsonMode
    ) {
        if (isBlank(baseUrl) || isBlank(apiKey) || isBlank(model)) {
            return new UnavailableModelProvider("DEEPSEEK_BASE_URL/DEEPSEEK_API_KEY/DEEPSEEK_MODEL not configured");
        }
        return new QwenHttpProvider(json, baseUrl, apiKey, model, timeout, streamTimeout, jsonMode, "deepseek");
    }

    @Bean("deepseekResidentMind")
    public QwenResidentMind deepseekResidentMind(
        @Qualifier("deepseekProvider") QwenProvider provider,
        ObjectMapper json,
        @Value("${app.ai.provider:mock}") String providerName,
        @Value("${app.town.companion-model-enabled:true}") boolean enabled,
        @Value("${app.town.companion-model.thinking.decision:false}") boolean decisionThinking,
        @Value("${app.town.companion-model.thinking.turn:false}") String turnThinkingRaw,
        @Value("${app.town.companion-model.thinking.summary:false}") String summaryThinkingRaw
    ) {
        return new QwenResidentMind(provider, json, providerName, enabled, "deepseek", decisionThinking, parseThinking(turnThinkingRaw), parseThinking(summaryThinkingRaw));
    }

    private static boolean isBlank(String value) { return value == null || value.isBlank(); }

    /** "" (unset) -> null (no opinion, leave the provider's own default alone); otherwise parses "true"/"false". */
    private static Boolean parseThinking(String raw) { return isBlank(raw) ? null : Boolean.valueOf(raw); }
}
