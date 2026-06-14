/*
* Copyright (C) 2026 Alexander Verbruggen
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Lesser General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Lesser General Public License for more details.
*
* You should have received a copy of the GNU Lesser General Public License
* along with this program. If not, see <https://www.gnu.org/licenses/>.
*/

package be.celerex.polymr.modelregistry.provider;

import be.celerex.polymr.model.SessionEventType;
import be.celerex.polymr.modelregistry.dto.ProviderProperty;
import be.celerex.polymr.modelregistry.dto.ProviderPropertyType;
import be.celerex.polymr.modelregistry.telemetry.ModelThreshold;
import be.celerex.polymr.modelregistry.telemetry.PriceResult;
import be.celerex.polymr.modelregistry.telemetry.PriceBreakdownItem;
import be.celerex.polymr.modelregistry.telemetry.TokenCount;
import be.celerex.polymr.modelregistry.telemetry.TokenTotals;
import be.celerex.polymr.modelregistry.telemetry.ResponseUsage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import java.io.IOException;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.anthropic.AnthropicTokenCountEstimator;
import dev.langchain4j.model.googleai.GoogleAiGeminiTokenCountEstimator;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.HashMap;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

final class ProviderTelemetrySupport {
	private ProviderTelemetrySupport() {}

	static List<ProviderProperty> telemetryProperties() {
		return List.of(
			new ProviderProperty(
				"warning_limit",
				"Warning limit",
				ProviderPropertyType.NUMBER,
				false,
				null,
				"Optional. When total tokens exceed this, a user warning is raised.",
				null,
				null,
				true,
				0.0,
				null,
				1.0
			)
		);
	}

	static Optional<AiModelTokenEstimator> tokenEstimator(Map<String, Object> config) {
		return Optional.of(new SimpleTokenEstimator());
	}

	static Optional<AiModelTokenEstimator> openAiTokenEstimator(Map<String, Object> config) {
		ProviderConfig cfg = new ProviderConfig(config);
		String modelId = cfg.string("model_id");
		if (modelId == null || modelId.isBlank()) {
			return Optional.empty();
		}
		return Optional.of(tokenEstimatorFrom(new OpenAiTokenCountEstimator(modelId)));
	}

	static Optional<AiModelTokenEstimator> openAiTokenEstimatorForModel(String modelId) {
		if (modelId == null || modelId.isBlank()) {
			return Optional.empty();
		}
		return Optional.of(tokenEstimatorFrom(new OpenAiTokenCountEstimator(modelId)));
	}

	static Optional<AiModelTokenEstimator> anthropicTokenEstimator(Map<String, Object> config) {
		ProviderConfig cfg = new ProviderConfig(config);
		String apiKey = cfg.string("api_key");
		String modelId = cfg.string("model_id");
		if (apiKey == null || apiKey.isBlank() || modelId == null || modelId.isBlank()) {
			return Optional.empty();
		}
		AnthropicTokenCountEstimator estimator = AnthropicTokenCountEstimator.builder()
			.apiKey(apiKey)
			.modelName(modelId)
			.build();
		return Optional.of(tokenEstimatorFrom(estimator));
	}

	static Optional<AiModelTokenEstimator> anthropicTokenEstimatorForModel(String apiKey, String modelId) {
		if (apiKey == null || apiKey.isBlank() || modelId == null || modelId.isBlank()) {
			return Optional.empty();
		}
		AnthropicTokenCountEstimator estimator = AnthropicTokenCountEstimator.builder()
			.apiKey(apiKey)
			.modelName(modelId)
			.build();
		return Optional.of(tokenEstimatorFrom(estimator));
	}

	static Optional<AiModelTokenEstimator> geminiTokenEstimator(Map<String, Object> config) {
		ProviderConfig cfg = new ProviderConfig(config);
		String apiKey = cfg.string("api_key");
		String modelId = cfg.string("model_id");
		if (apiKey == null || apiKey.isBlank() || modelId == null || modelId.isBlank()) {
			return Optional.empty();
		}
		GoogleAiGeminiTokenCountEstimator estimator = GoogleAiGeminiTokenCountEstimator.builder()
			.apiKey(apiKey)
			.modelName(modelId)
			.build();
		return Optional.of(tokenEstimatorFrom(estimator));
	}

	static Optional<AiModelTokenEstimator> geminiTokenEstimatorForModel(String apiKey, String modelId) {
		if (apiKey == null || apiKey.isBlank() || modelId == null || modelId.isBlank()) {
			return Optional.empty();
		}
		GoogleAiGeminiTokenCountEstimator estimator = GoogleAiGeminiTokenCountEstimator.builder()
			.apiKey(apiKey)
			.modelName(modelId)
			.build();
		return Optional.of(tokenEstimatorFrom(estimator));
	}

	static Optional<AiModelTokenEstimator> fallbackTokenEstimator() {
		return Optional.of(new SimpleTokenEstimator());
	}

	static Optional<AiModelCostEstimator> costEstimator(Map<String, Object> config) {
		return Optional.empty();
	}

	static List<ModelThreshold> thresholds(Map<String, Object> config, TokenTotals totals) {
		ProviderConfig cfg = new ProviderConfig(config);
		long totalTokens = totals == null ? 0 : totals.total();
		List<ModelThreshold> thresholds = new ArrayList<>();
		Integer limit = cfg.integer("token_limit");
		if (limit != null) {
			String level = totalTokens >= limit ? "critical" : "info";
			thresholds.add(
				new ModelThreshold("TOKEN_LIMIT", "Token limit", level, limit.longValue(), "Maximum context size for this model.")
			);
		}
		Integer userWarning = userWarningLimit(cfg);
		if (userWarning != null && totalTokens >= userWarning) {
			thresholds.add(
				new ModelThreshold(
					"USER_WARNING_LIMIT",
					"Warning limit",
					"warning",
					userWarning.longValue(),
					"User-defined warning limit."
				)
			);
		}
		thresholds.sort(Comparator.comparing(ModelThreshold::value, Comparator.nullsLast(Long::compareTo)));
		return thresholds;
	}

	static List<ModelThreshold> thresholdsWithDefaults(
			Map<String, Object> config,
			TokenTotals totals,
			Long defaultLimit,
			List<Long> defaultWarnings) {
		return thresholdsWithDefaults(config, totals, defaultLimit, defaultWarnings, null);
	}

	static List<ModelThreshold> thresholdsWithDefaults(
			Map<String, Object> config,
			TokenTotals totals,
			Long defaultLimit,
			List<Long> defaultWarnings,
			String warningDescription) {
		ProviderConfig cfg = new ProviderConfig(config);
		long totalTokens = totals == null ? 0 : totals.total();
		List<ModelThreshold> thresholds = new ArrayList<>();
		Integer limit = cfg.integer("token_limit");
		Long resolvedLimit;
		if (limit != null) {
			resolvedLimit = limit.longValue();
		}
		else {
			resolvedLimit = defaultLimit == null ? null : defaultLimit;
		}
		if (resolvedLimit != null && resolvedLimit > 0) {
			String level = totalTokens >= resolvedLimit ? "critical" : "info";
			thresholds.add(
				new ModelThreshold("TOKEN_LIMIT", "Token limit", level, resolvedLimit, "Maximum context size for this model.")
			);
		}
		Integer userWarning = userWarningLimit(cfg);
		if (userWarning != null && totalTokens >= userWarning) {
			thresholds.add(
				new ModelThreshold(
					"USER_WARNING_LIMIT",
					"Warning limit",
					"warning",
					userWarning.longValue(),
					"User-defined warning limit."
				)
			);
		}
		if (defaultWarnings != null && !defaultWarnings.isEmpty()) {
			thresholds.addAll(warningThresholds(totalTokens, defaultWarnings, warningDescription));
		}
		thresholds.sort(Comparator.comparing(ModelThreshold::value, Comparator.nullsLast(Long::compareTo)));
		return thresholds;
	}

	private static final class SimpleTokenEstimator implements AiModelTokenEstimator {
		@Override
		public TokenCount estimate(SessionEventType type, JsonNode payload, String modelId) {
			return estimateFromText(type, payload, ProviderTelemetrySupport::estimateTokens);
		}
	}

	private static AiModelTokenEstimator tokenEstimatorFrom(TokenCountEstimator estimator) {
		return (type, payload, modelId) -> estimateFromEstimator(type, payload, estimator);
	}

	private static TokenCount estimateFromEstimator(
			SessionEventType type,
			JsonNode payload,
			TokenCountEstimator estimator) {
		if (type == SessionEventType.AUDIT) {
			return null;
		}
		if (estimator == null) {
			return estimateFromText(type, payload, ProviderTelemetrySupport::estimateTokens);
		}
		ChatMessage message = buildMessage(type, payload);
		if (message != null) {
			try {
				int tokens = Math.max(0, estimator.estimateTokenCountInMessage(message));
				return switch (type) {
					case ASSISTANT_MESSAGE, TOOL_CALL -> new TokenCount(null, tokens, null);
					case USER_MESSAGE, CONTEXT_MESSAGE, SYSTEM, TOOL_RESULT, DECISION_REQUEST, DECISION_RESULT,
                        SESSION_TAG_CHANGE, STAGE_START, STAGE_END -> new TokenCount(tokens, null, null);
					case AUDIT -> null;
				};
			}
			catch (RuntimeException ignored) {}
		}
		String text = extractTextForType(type, payload);
		if (text == null || text.isBlank()) {
			return null;
		}
		int tokens = Math.max(0, estimator.estimateTokenCountInText(text));
		return switch (type) {
			case ASSISTANT_MESSAGE, TOOL_CALL -> new TokenCount(null, tokens, null);
			case USER_MESSAGE, CONTEXT_MESSAGE, SYSTEM, TOOL_RESULT, DECISION_REQUEST, DECISION_RESULT,
                SESSION_TAG_CHANGE, STAGE_START, STAGE_END -> new TokenCount(tokens, null, null);
			case AUDIT -> null;
		};
	}

	private static TokenCount estimateFromText(
			SessionEventType type,
			JsonNode payload,
			java.util.function.ToIntFunction<String> tokenizer) {
		if (type == SessionEventType.AUDIT) {
			return null;
		}
		String text = extractTextForType(type, payload);
		if (text == null || text.isBlank()) {
			return null;
		}
		int tokens = Math.max(0, tokenizer.applyAsInt(text));
		return switch (type) {
			case ASSISTANT_MESSAGE, TOOL_CALL -> new TokenCount(null, tokens, null);
			case USER_MESSAGE, CONTEXT_MESSAGE, SYSTEM, TOOL_RESULT, DECISION_REQUEST, DECISION_RESULT,
                SESSION_TAG_CHANGE, STAGE_START, STAGE_END -> new TokenCount(tokens, null, null);
			case AUDIT -> null;
		};
	}

	private static String extractTextForType(SessionEventType type, JsonNode payload) {
		if (type == SessionEventType.SESSION_TAG_CHANGE) {
			if (payload == null || payload.isNull()) {
				return null;
			}
			JsonNode messageNode = payload.get("llm_message");
			if (messageNode != null && messageNode.isTextual()) {
				String value = messageNode.asText();
				return value == null || value.isBlank() ? null : value;
			}
			return null;
		}
		return extractText(payload);
	}

	private static String extractText(JsonNode payload) {
		if (payload == null || payload.isNull()) {
			return null;
		}
		if (payload.isTextual()) {
			return payload.asText();
		}
		if (!payload.isObject()) {
			return payload.toString();
		}
		JsonNode textNode = payload.get("text");
		if (textNode != null && textNode.isTextual()) {
			return textNode.asText();
		}
		JsonNode resultNode = payload.get("result");
		if (resultNode != null) {
			if (resultNode.isTextual()) {
				return resultNode.asText();
			}
			return resultNode.toString();
		}
		return null;
	}

	static Optional<AiModelResponseCostEstimator> responseCostEstimator(
			double inputRate,
			double outputRate,
			Double reasoningRate,
			Long tierThreshold,
			Double tierInputRate,
			Double tierOutputRate,
			Double cacheDiscountRate,
			Double cacheCreationMultiplier) {
		return Optional.of(
			(response, modelId) -> estimateFromResponse(
				response,
				inputRate,
				outputRate,
				reasoningRate,
				tierThreshold,
				tierInputRate,
				tierOutputRate,
				cacheDiscountRate,
				cacheCreationMultiplier
			)
		);
	}

	static Optional<AiModelResponseCostEstimator> openRouterResponseCostEstimator() {
		return Optional.of(ProviderTelemetrySupport::estimateFromOpenRouterRawResponse);
	}

	private static PriceResult estimateFromOpenRouterRawResponse(ChatResponse response, String modelId) {
		// OpenRouter returns usage and cost in the raw HTTP response body.
		// LangChain4j's internal Usage class does not include 'cost', so we parse the body directly.
		String body = rawHttpResponseBody(response);
		if (body == null || body.isBlank()) {
			return null;
		}
		try {
			ObjectMapper mapper = new ObjectMapper();
			Map<String, Object> parsed = mapper.readValue(body, new TypeReference<Map<String, Object>>() {});
			Object usage = parsed.get("usage");
			if (!(usage instanceof Map<?, ?> usageMap)) {
				return null;
			}
			long inputTokens = longOrZero(parseLong(usageMap.get("prompt_tokens")));
			long outputTokens = longOrZero(parseLong(usageMap.get("completion_tokens")));
			long totalTokens = longOrZero(parseLong(usageMap.get("total_tokens")));
			Long cachedTokens = parseLongNullable(usageMap.get("cached_tokens"));
			if (cachedTokens == null) {
				Object promptDetails = usageMap.get("prompt_tokens_details");
				if (promptDetails instanceof Map<?, ?> detailsMap) {
					cachedTokens = parseLongNullable(detailsMap.get("cached_tokens"));
				}
			}
			Long cacheWriteTokens = null;
			Object promptDetails = usageMap.get("prompt_tokens_details");
			if (promptDetails instanceof Map<?, ?> detailsMap) {
				cacheWriteTokens = parseLongNullable(detailsMap.get("cache_write_tokens"));
			}
			Long reasoningTokens = null;
			Object completionDetails = usageMap.get("completion_tokens_details");
			if (completionDetails instanceof Map<?, ?> detailsMap) {
				reasoningTokens = parseLongNullable(detailsMap.get("reasoning_tokens"));
			}
			Object cost = usageMap.get("cost");
			if (cost == null) {
				return null;
			}
			BigDecimal totalCost = parseBigDecimal(cost);
			if (totalCost == null) {
				return null;
			}
			List<PriceBreakdownItem> breakdown = new ArrayList<>();
			breakdown.add(new PriceBreakdownItem("openrouter", totalCost));
			return new PriceResult(
				totalCost,
				"USD",
				inputTokens,
				outputTokens,
				reasoningTokens,
				cachedTokens == null ? 0L : cachedTokens,
				null,
				breakdown
			);
		}
		catch (IOException ignored) {}
		return null;
	}

	private static long longOrZero(Long value) {
		return value == null ? 0 : value;
	}

	private static BigDecimal parseBigDecimal(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof Number number) {
			return BigDecimal.valueOf(number.doubleValue());
		}
		try {
			String text = value.toString();
			if (text == null || text.isBlank()) {
				return null;
			}
			return new BigDecimal(text);
		}
		catch (NumberFormatException ignored) {
			return null;
		}
	}

	private static Long parseLongNullable(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof Number number) {
			return number.longValue();
		}
		try {
			String text = value.toString();
			if (text == null || text.isBlank()) {
				return null;
			}
			return Long.parseLong(text);
		}
		catch (NumberFormatException ignored) {
			return null;
		}
	}

	static Map<String, Object> rawResponseMetadata(ChatResponse response) {
		Map<String, Object> merged = new HashMap<>();
		readMetadata(response)
			.forEach((key, value) -> merged.put(key, toSerializableValue(value)));
		Object usageMetadata = readUsageMetadataObject(response);
		if (usageMetadata != null) {
			merged.put("usageMetadata", toSerializableMap(usageMetadata));
		}
		Object tokenUsage = readTokenUsageObject(response);
		if (tokenUsage != null) {
			merged.put("tokenUsage", toSerializableMap(tokenUsage));
		}
		return merged;
	}

	static Map<String, Object> responseMetadata(ChatResponse response) {
		return rawResponseMetadata(response);
	}

	static String rawHttpResponseBody(ChatResponse response) {
		if (response == null) {
			return null;
		}
		try {
			Object metadata = response.getClass().getMethod("metadata").invoke(response);
			if (metadata == null) {
				return null;
			}
			Method rawHttpResponseMethod = metadata.getClass().getMethod("rawHttpResponse");
			Object rawHttpResponse = rawHttpResponseMethod.invoke(metadata);
			if (rawHttpResponse == null) {
				return null;
			}
			Method bodyMethod = rawHttpResponse.getClass().getMethod("body");
			Object body = bodyMethod.invoke(rawHttpResponse);
			return body instanceof String ? (String) body : null;
		}
		catch (Exception ignored) {}
		return null;
	}

	static List<String> rawServerSentEventData(ChatResponse response) {
		if (response == null) {
			return List.of();
		}
		List<String> result = new ArrayList<>();
		try {
			Object metadata = response.getClass().getMethod("metadata").invoke(response);
			if (metadata == null) {
				return result;
			}
			Method sseMethod = metadata.getClass().getMethod("rawServerSentEvents");
			Object sseList = sseMethod.invoke(metadata);
			if (sseList instanceof Iterable<?> events) {
				for (Object event : events) {
					if (event == null) {
						continue;
					}
					try {
						Method dataMethod = event.getClass().getMethod("data");
						Object data = dataMethod.invoke(event);
						if (data instanceof String) {
							result.add((String) data);
						}
					}
					catch (Exception ignored) {}
				}
			}
		}
		catch (Exception ignored) {}
		return result;
	}

	private static PriceResult estimateFromResponse(
			ChatResponse response,
			double inputRate,
			double outputRate,
			Double reasoningRate,
			Long tierThreshold,
			Double tierInputRate,
			Double tierOutputRate,
			Double cacheDiscountRate,
			Double cacheCreationMultiplier) {
		ResponseUsage usage = responseUsage(response);
		if (usage == null) {
			return null;
		}
		long inputTokens = usage.input_tokens() == null ? 0 : usage.input_tokens();
		long outputTokens = usage.output_tokens() == null ? 0 : usage.output_tokens();
		Long reasoningTokens = usage.reasoning_tokens();
		long cachedInputTokens = usage.cached_input_tokens() == null ? 0 : usage.cached_input_tokens();
		long cacheCreationTokens = usage.cache_creation_input_tokens() == null ? 0 : usage.cache_creation_input_tokens();
		TokenTotals totals = new TokenTotals(inputTokens, outputTokens, reasoningTokens, cachedInputTokens > 0 ? cachedInputTokens : null);
		be.celerex.polymr.modelregistry.telemetry.CostEstimate cost = estimateCost(totals, inputRate, outputRate, reasoningRate, tierThreshold, tierInputRate, tierOutputRate);
		if (cost == null || cost.amount() == null) {
			return null;
		}
		BigDecimal cacheCreationCost = null;
		if (cacheCreationTokens > 0 && cacheCreationMultiplier != null && cacheCreationMultiplier > 1.0) {
			double surchargeRate = inputRate * (cacheCreationMultiplier - 1.0);
			cacheCreationCost = costForTokens(cacheCreationTokens, surchargeRate);
		}
		BigDecimal total = cost.amount();
		List<PriceBreakdownItem> breakdown = new ArrayList<>();
		breakdown.add(new PriceBreakdownItem("base", total));
		if (cacheDiscountRate != null && cacheDiscountRate > 0 && cachedInputTokens > 0) {
			boolean useTier = tierThreshold != null
				&& totals.total() > tierThreshold
				&& tierInputRate != null
				&& tierOutputRate != null;
			double resolvedInputRate = useTier ? tierInputRate : inputRate;
			long discountedTokens = Math.min(cachedInputTokens, inputTokens);
			BigDecimal cacheDiscount = costForTokens(discountedTokens, resolvedInputRate * cacheDiscountRate);
			if (cacheDiscount.compareTo(BigDecimal.ZERO) > 0) {
				total = total.subtract(cacheDiscount);
				breakdown.add(new PriceBreakdownItem("cache_discount", cacheDiscount.negate()));
			}
		}
		if (cacheCreationCost != null && cacheCreationCost.compareTo(BigDecimal.ZERO) > 0) {
			total = total.add(cacheCreationCost);
			breakdown.add(new PriceBreakdownItem("cache_creation_surcharge", cacheCreationCost));
		}
		return new PriceResult(
			total,
			cost.currency(),
			inputTokens,
			outputTokens,
			reasoningTokens,
			cachedInputTokens,
			cacheDiscountRate,
			breakdown
		);
	}

	private static long readCachedInputTokens(ChatResponse response) {
		Long usageValue = readCachedInputTokensFromUsage(response);
		if (usageValue != null) {
			return Math.max(0, usageValue);
		}
		Map<String, Object> metadata = readMetadata(response);
		if (metadata.isEmpty()) {
			return 0;
		}
		long fromMetadata = readLong(metadata, "cached_input_tokens", "cached_prompt_tokens", "cached_tokens", "cache_input_tokens");
		if (fromMetadata > 0) {
			return fromMetadata;
		}
		// Fallback: OpenRouter may return cached_tokens in the raw body even if langchain4j didn't map it.
		return Math.max(0, readOpenRouterCachedTokens(response));
	}

	private static long readOpenRouterCachedTokens(ChatResponse response) {
		String body = rawHttpResponseBody(response);
		if (body == null || body.isBlank()) {
			return 0;
		}
		try {
			ObjectMapper mapper = new ObjectMapper();
			Map<String, Object> parsed = mapper.readValue(body, new TypeReference<Map<String, Object>>() {});
			Object usage = parsed.get("usage");
			if (!(usage instanceof Map<?, ?> usageMap)) {
				return 0;
			}
			Object promptDetails = usageMap.get("prompt_tokens_details");
			if (promptDetails instanceof Map<?, ?> detailsMap) {
				Long value = parseLongNullable(detailsMap.get("cached_tokens"));
				if (value != null) {
					return value;
				}
			}
			Long flatCached = parseLongNullable(usageMap.get("cached_tokens"));
			return flatCached == null ? 0 : flatCached;
		}
		catch (IOException ignored) {}
		return 0;
	}

	private static Long readCachedInputTokensFromUsage(ChatResponse response) {
		if (response == null) {
			return null;
		}
		try {
			Method usageMethod = response.getClass().getMethod("tokenUsage");
			Object usage = usageMethod.invoke(response);
			if (usage == null) {
				return null;
			}
			Long openAi = readNestedLong(usage, "inputTokensDetails", "cachedTokens");
			if (openAi != null) {
				return openAi;
			}
			Long anthropic = readDirectLong(usage, "cacheReadInputTokens");
			if (anthropic != null) {
				return anthropic;
			}
			Long mistral = readNestedLong(usage, "promptTokensDetails", "cachedTokens");
			if (mistral != null) {
				return mistral;
			}
		}
		catch (Exception ignored) {}
		try {
			Method usageMetadataMethod = response.getClass().getMethod("usageMetadata");
			Object usageMetadata = usageMetadataMethod.invoke(response);
			if (usageMetadata != null) {
				Long gemini = readDirectLong(usageMetadata, "cachedContentTokenCount");
				if (gemini != null) {
					return gemini;
				}
			}
		}
		catch (Exception ignored) {}
		return null;
	}

	private static Object readUsageMetadataObject(ChatResponse response) {
		if (response == null) {
			return null;
		}
		try {
			Method usageMetadataMethod = response.getClass().getMethod("usageMetadata");
			return usageMetadataMethod.invoke(response);
		}
		catch (Exception ignored) {}
		return null;
	}

	private static Object readTokenUsageObject(ChatResponse response) {
		if (response == null) {
			return null;
		}
		try {
			Method usageMethod = response.getClass().getMethod("tokenUsage");
			return usageMethod.invoke(response);
		}
		catch (Exception ignored) {}
		return null;
	}

	private static Map<String, Object> toSerializableMap(Object value) {
		if (value == null) {
			return Map.of();
		}
		if (value instanceof Map<?, ?> raw) {
			Map<String, Object> result = new HashMap<>();
			raw.forEach((key, item) -> {
				if (key != null) {
					result.put(key.toString(), toSerializableValue(item));
				}
			});
			return result;
		}
		if (value.getClass().isRecord()) {
			Map<String, Object> result = new HashMap<>();
			try {
				var components = value.getClass().getRecordComponents();
				if (components != null) {
					for (var component : components) {
						Method accessor = component.getAccessor();
						if (accessor == null) {
							continue;
						}
						Object fieldValue = accessor.invoke(value);
						result.put(component.getName(), toSerializableValue(fieldValue));
					}
				}
			}
			catch (Exception ignored) {}
			if (!result.isEmpty()) {
				return result;
			}
		}
		Map<String, Object> result = new HashMap<>();
		for (Method method : value.getClass()
			.getMethods()) {
			if (method.getParameterCount() != 0) {
				continue;
			}
			String name = method.getName();
			if (name.equals("getClass")) {
				continue;
			}
			if (!(name.startsWith("get") || name.startsWith("is"))) {
				if (!isDataMethod(method)) {
					continue;
				}
			}
			try {
				Object fieldValue = method.invoke(value);
				String key = name.startsWith("get")
					? decapitalize(name.substring(3))
					: name.startsWith("is") ? decapitalize(name.substring(2)) : name;
				result.put(key, toSerializableValue(fieldValue));
			}
			catch (Exception ignored) {}
		}
		return result;
	}

	private static boolean isDataMethod(Method method) {
		String name = method.getName();
		if (name.equals("hashCode") || name.equals("toString") || name.equals("equals")) {
			return false;
		}
		Class<?> declaring = method.getDeclaringClass();
		if (declaring == Object.class) {
			return false;
		}
		if (method.getReturnType() == Void.TYPE) {
			return false;
		}
		return true;
	}

	private static Object toSerializableValue(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof String || value instanceof Number || value instanceof Boolean) {
			return value;
		}
		if (value instanceof Map<?, ?> raw) {
			return toSerializableMap(raw);
		}
		if (value instanceof Iterable<?> items) {
			List<Object> list = new ArrayList<>();
			for (Object item : items) {
				list.add(toSerializableValue(item));
			}
			return list;
		}
		return value.toString();
	}

	private static String decapitalize(String value) {
		if (value == null || value.isBlank()) {
			return "";
		}
		return value.substring(0, 1).toLowerCase(Locale.ROOT) + value.substring(1);
	}

	private static Long readDirectLong(Object target, String methodName) {
		if (target == null || methodName == null) {
			return null;
		}
		try {
			Method method = target.getClass().getMethod(methodName);
			Object value = method.invoke(target);
			return parseLong(value);
		}
		catch (Exception ignored) {}
		return null;
	}

	private static Long readNestedLong(Object target, String getterName, String nestedGetterName) {
		if (target == null || getterName == null || nestedGetterName == null) {
			return null;
		}
		try {
			Method getter = target.getClass().getMethod(getterName);
			Object nested = getter.invoke(target);
			if (nested == null) {
				return null;
			}
			Method nestedGetter = nested.getClass().getMethod(nestedGetterName);
			Object value = nestedGetter.invoke(nested);
			return parseLong(value);
		}
		catch (Exception ignored) {}
		return null;
	}

	private static long readCacheCreationTokens(ChatResponse response) {
		Map<String, Object> metadata = readMetadata(response);
		if (metadata.isEmpty()) {
			return 0;
		}
		return readLong(
			metadata,
			"cache_creation_input_tokens",
			"cache_write_input_tokens",
			"cache_write_tokens",
			"cache_creation_tokens"
		);
	}

	private static long readOpenRouterCacheCreationTokens(ChatResponse response) {
		String body = rawHttpResponseBody(response);
		if (body == null || body.isBlank()) {
			return 0;
		}
		try {
			ObjectMapper mapper = new ObjectMapper();
			Map<String, Object> parsed = mapper.readValue(body, new TypeReference<Map<String, Object>>() {});
			Object usage = parsed.get("usage");
			if (!(usage instanceof Map<?, ?> usageMap)) {
				return 0;
			}
			Object promptDetails = usageMap.get("prompt_tokens_details");
			if (!(promptDetails instanceof Map<?, ?> detailsMap)) {
				return 0;
			}
			Long value = parseLongNullable(detailsMap.get("cache_write_tokens"));
			return value == null ? 0 : value;
		}
		catch (IOException ignored) {}
		return 0;
	}

	private static Map<String, Object> readMetadata(ChatResponse response) {
		if (response == null) {
			return Map.of();
		}
		try {
			Method method = response.getClass().getMethod("metadata");
			Object value = method.invoke(response);
			if (value instanceof Map<?, ?> raw) {
				Map<String, Object> result = new HashMap<>();
				raw.forEach((key, item) -> {
					if (key != null) {
						result.put(key.toString(), item);
					}
				});
				return result;
			}
		}
		catch (Exception ignored) {}
		return Map.of();
	}

	private static long readLong(Map<String, Object> metadata, String...keys) {
		if (metadata == null || metadata.isEmpty()) {
			return 0;
		}
		for (String key : keys) {
			Object value = metadata.get(key);
			Long parsed = parseLong(value);
			if (parsed != null) {
				return parsed;
			}
		}
		return 0;
	}

	private static Long parseLong(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof Number number) {
			return number.longValue();
		}
		try {
			String text = value.toString();
			if (text == null || text.isBlank()) {
				return null;
			}
			return Long.parseLong(text);
		}
		catch (NumberFormatException ignored) {
			return null;
		}
	}

	static ResponseUsage responseUsage(ChatResponse response) {
		TokenUsage usage = readTokenUsage(response);
		if (usage == null) {
			return null;
		}
		long cachedInputTokens = Math.max(0, readCachedInputTokens(response));
		long cacheCreationTokens = Math.max(0, readCacheCreationTokens(response));
		// OpenRouter returns cache_write_tokens in prompt_tokens_details, which langchain4j drops.
		// Try to read it from the raw HTTP response body as a fallback.
		if (cacheCreationTokens == 0) {
			cacheCreationTokens = Math.max(0, readOpenRouterCacheCreationTokens(response));
		}
		return new ResponseUsage(
			Math.max(0, usage.inputTokens),
			Math.max(0, usage.outputTokens),
			usage.reasoningTokens == null ? null : Math.max(0, usage.reasoningTokens),
			cachedInputTokens,
			cacheCreationTokens,
			false
		);
	}

	private static TokenUsage readTokenUsage(ChatResponse response) {
		if (response == null) {
			return null;
		}
		try {
			Method method = response.getClass().getMethod("tokenUsage");
			Object value = method.invoke(response);
			return TokenUsage.from(value);
		}
		catch (Exception ignored) {}
		return null;
	}

	static be.celerex.polymr.modelregistry.telemetry.CostEstimate estimateCost(
			TokenTotals totals,
			double inputRate,
			double outputRate,
			Double reasoningRate,
			Long tierThreshold,
			Double tierInputRate,
			Double tierOutputRate) {
		if (totals == null) {
			return null;
		}
		boolean useTier = tierThreshold != null
			&& totals.total() > tierThreshold
			&& tierInputRate != null
			&& tierOutputRate != null;
		double resolvedInputRate = useTier ? tierInputRate : inputRate;
		double resolvedOutputRate = useTier ? tierOutputRate : outputRate;
		BigDecimal total = BigDecimal.ZERO;
		total = total.add(costForTokens(totals.input(), resolvedInputRate));
		total = total.add(costForTokens(totals.output(), resolvedOutputRate));
		if (totals.reasoning() != null && reasoningRate != null && reasoningRate > 0) {
			total = total.add(costForTokens(totals.reasoning(), reasoningRate));
		}
		if (total.compareTo(BigDecimal.ZERO) <= 0) {
			return null;
		}
		return new be.celerex.polymr.modelregistry.telemetry.CostEstimate(total, "USD");
	}

	private static BigDecimal costForTokens(long tokens, double costPer1M) {
		if (tokens <= 0 || costPer1M <= 0) {
			return BigDecimal.ZERO;
		}
		BigDecimal cost = BigDecimal.valueOf(costPer1M);
		BigDecimal tokenCount = BigDecimal.valueOf(tokens);
		return cost.multiply(tokenCount)
			.divide(BigDecimal.valueOf(1_000_000L), 6, RoundingMode.HALF_UP);
	}

	private record TokenUsage(long inputTokens, long outputTokens, Long reasoningTokens) {
		static TokenUsage from(Object raw) {
			if (raw == null) {
				return null;
			}
			return new TokenUsage(
				readUsageValue(raw, "inputTokenCount", "inputTokens"),
				readUsageValue(raw, "outputTokenCount", "outputTokens"),
				readUsageValueNullable(raw, "reasoningTokenCount", "reasoningTokens")
			);
		}
	}

	private static long readUsageValue(Object usage, String...methodNames) {
		Long value = readUsageValueNullable(usage, methodNames);
		return value == null ? 0 : value;
	}

	private static Long readUsageValueNullable(Object usage, String...methodNames) {
		for (String name : methodNames) {
			try {
				Method method = usage.getClass().getMethod(name);
				Object value = method.invoke(usage);
				Long parsed = parseLong(value);
				if (parsed != null) {
					return parsed;
				}
			}
			catch (Exception ignored) {}
		}
		return null;
	}

	private static int estimateTokens(String text) {
		int length = text == null ? 0 : text.length();
		if (length <= 0) {
			return 0;
		}
		return Math.max(1, (int) Math.ceil(length / 4.0));
	}

	private static List<ModelThreshold> warningThresholds(
			long totalTokens,
			List<Long> defaultWarnings,
			String warningDescription) {
		List<ModelThreshold> warnings = new ArrayList<>();
		if (defaultWarnings != null) {
			for (Long threshold : defaultWarnings) {
				if (threshold == null) {
					continue;
				}
				if (totalTokens >= threshold) {
					warnings.add(
						new ModelThreshold(
							"TOKEN_WARNING_" + threshold,
							"Token warning",
							"warning",
							threshold,
							warningDescription == null || warningDescription.isBlank()
								? "Total tokens reached a warning threshold."
								: warningDescription
						)
					);
				}
			}
		}
		return warnings;
	}

	private static Integer userWarningLimit(ProviderConfig cfg) {
		return cfg.integer("warning_limit");
	}

	private static ChatMessage buildMessage(SessionEventType type, JsonNode payload) {
		if (payload == null || payload.isNull()) {
			return null;
		}
		String text = extractTextForType(type, payload);
		List<Content> contents = new ArrayList<>();
		if (text != null && !text.isBlank()) {
			contents.add(TextContent.from(text));
		}
		if (type == SessionEventType.USER_MESSAGE) {
			contents.addAll(extractImageContents(payload));
		}
		if (contents.isEmpty()) {
			return null;
		}
		return switch (type) {
			case USER_MESSAGE, CONTEXT_MESSAGE -> UserMessage.from(contents);
			case SYSTEM -> SystemMessage.from(text == null ? "" : text);
			case SESSION_TAG_CHANGE -> text == null || text.isBlank() ? null : SystemMessage.from(text);
			case ASSISTANT_MESSAGE, TOOL_CALL -> AiMessage.from(text == null ? "" : text);
			case TOOL_RESULT, DECISION_REQUEST, DECISION_RESULT, STAGE_START, STAGE_END ->
                UserMessage.from(contents);
			case AUDIT -> null;
		};
	}

	private static List<Content> extractImageContents(JsonNode payload) {
		JsonNode attachments = payload.get("attachments");
		if (attachments == null || !attachments.isArray()) {
			return List.of();
		}
		List<Content> contents = new ArrayList<>();
		for (JsonNode entry : attachments) {
			if (entry == null || !entry.isObject()) {
				continue;
			}
			String type = entry.path("type").asText("");
			if (!type.startsWith("image/")) {
				continue;
			}
			String data = entry.path("data").asText(null);
			String base64 = extractBase64(data);
			if (base64 != null && !base64.isBlank()) {
				Image image = Image.builder().base64Data(base64).mimeType(type).build();
				contents.add(ImageContent.from(image));
				continue;
			}
			String url = entry.path("user_url").asText(null);
			if (url == null || url.isBlank()) {
				url = entry.path("public_url").asText(null);
			}
			if (url == null || url.isBlank()) {
				url = entry.path("uri").asText(null);
			}
			if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
				contents.add(ImageContent.from(url));
			}
		}
		return contents;
	}

	private static String extractBase64(String data) {
		if (data == null || data.isBlank()) {
			return null;
		}
		int commaIndex = data.indexOf(',');
		if (data.startsWith("data:") && commaIndex > 0) {
			return data.substring(commaIndex + 1);
		}
		return data;
	}
}
