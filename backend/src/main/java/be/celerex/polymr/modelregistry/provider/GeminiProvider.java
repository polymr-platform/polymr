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

import be.celerex.polymr.modelregistry.dto.ProviderProperty;
import be.celerex.polymr.modelregistry.dto.ProviderPropertyType;
import be.celerex.polymr.modelregistry.telemetry.ModelThreshold;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
import be.celerex.polymr.llm.LlmHttpClientFactory;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class GeminiProvider implements AiChatModelProvider {
	@jakarta.inject.Inject
	LlmHttpClientFactory httpClientFactory;

	@Override
	public String id() {
		return "gemini";
	}

	@Override
	public String displayName() {
		return "Gemini";
	}

	@Override
	public List<ProviderProperty> properties() {
		List<ProviderProperty> properties = new ArrayList<>(
			List.of(
				new ProviderProperty(
					"api_key",
					"API key",
					ProviderPropertyType.SECRET,
					true,
					"AIza...",
					"Required for Gemini API",
					null,
					null,
					true,
					null,
					null,
					null
				),
				new ProviderProperty(
					"temperature",
					"Temperature",
					ProviderPropertyType.NUMBER,
					false,
					"0.7",
					"Controls randomness. Lower values are more deterministic; higher "
						+ "values are more creative and diverse.",
					null,
					null,
					true,
					0.0,
					2.0,
					0.1
				),
				new ProviderProperty(
					"timeout_seconds",
					"Request timeout (seconds)",
					ProviderPropertyType.NUMBER,
					false,
					"600",
					"Maximum time to wait for Gemini responses.",
					null,
					null,
					true,
					10.0,
					1800.0,
					10.0
				),
				new ProviderProperty(
					"max_retries",
					"Max retries",
					ProviderPropertyType.NUMBER,
					false,
					"2",
					"Retry count for transient failures/timeouts.",
					null,
					null,
					true,
					0.0,
					5.0,
					1.0
				)
			)
		);
		properties.addAll(ProviderTelemetrySupport.telemetryProperties());
		return properties;
	}

	@Override
	public List<AiChatModelDefinition> supportedChatModels() {
		return List.of(
			new GeminiModelDefinition(
				"gemini-3.1-pro-preview",
				"Gemini 3.1 Pro (Preview)",
				2_000_000L,
				List.of(200_000L),
				"2x pricing applies above this threshold.",
				"2M context RAG and multimodal logic.",
				1.25,
				5.00,
				200_000L,
				2.50,
				10.00,
				180_000,
				true,
				httpClientFactory
			),
			new GeminiModelDefinition(
				"gemini-3.1-flash-preview",
				"Gemini 3.1 Flash (Preview)",
				1_000_000L,
				List.of(200_000L),
				"2x pricing applies above this threshold.",
				"Real-time video/audio analysis.",
				0.10,
				0.40,
				200_000L,
				0.20,
				0.80,
				180_000,
				false,
				httpClientFactory
			),
			new GeminiModelDefinition(
				"gemini-3.1-lite-preview",
				"Gemini 3.1 Lite (Preview)",
				1_000_000L,
				null,
				null,
				"Massive bulk translation tasks.",
				0.05,
				0.15,
				null,
				null,
				null,
				null,
				false,
				httpClientFactory
			),
			new GeminiModelDefinition(
				"gemini-3-flash",
				"Gemini 3 Flash",
				1_000_000L,
				List.of(200_000L),
				"2x pricing applies above this threshold.",
				"Real-time video/audio analysis.",
				0.10,
				0.40,
				200_000L,
				0.20,
				0.80,
				180_000,
				false,
				httpClientFactory
			),
			new GeminiModelDefinition(
				"gemini-3-lite",
				"Gemini 3 Lite",
				1_000_000L,
				null,
				null,
				"Massive bulk translation tasks.",
				0.05,
				0.15,
				null,
				null,
				null,
				null,
				false,
				httpClientFactory
			),
			new GeminiModelDefinition(
				"gemini-2.5-pro",
				"Gemini 2.5 Pro",
				1_000_000L,
				List.of(200_000L),
				"2x pricing applies above this threshold.",
				"Stable 2.5 era complex coding.",
				1.25,
				10.00,
				200_000L,
				2.50,
				15.00,
				180_000,
				false,
				httpClientFactory
			),
			new GeminiModelDefinition(
				"gemini-2.5-flash",
				"Gemini 2.5 Flash",
				1_000_000L,
				null,
				null,
				"High-speed multimodal legacy tasks.",
				0.30,
				2.50,
				null,
				null,
				null,
				null,
				false,
				httpClientFactory
			)
		);
	}

	private static final class GeminiModelDefinition implements AiChatModelDefinition {
		private final String id;
		private final String displayName;
		private final Long contextLimit;
		private final List<Long> warningThresholds;
		private final String warningDescription;
		private final String useCase;
		private final double inputRate;
		private final double outputRate;
		private final Long tierThreshold;
		private final Double tierInputRate;
		private final Double tierOutputRate;
		private final Integer defaultPruningLimit;
		private final boolean requiresThoughtSignatures;
		private final LlmHttpClientFactory httpClientFactory;

		private GeminiModelDefinition(
				String id,
				String displayName,
				Long contextLimit,
				List<Long> warningThresholds,
				String warningDescription,
				String useCase,
				double inputRate,
				double outputRate,
				Long tierThreshold,
				Double tierInputRate,
				Double tierOutputRate,
				Integer defaultPruningLimit,
				boolean requiresThoughtSignatures,
				LlmHttpClientFactory httpClientFactory) {
			this.id = id;
			this.displayName = displayName;
			this.contextLimit = contextLimit;
			this.warningThresholds = warningThresholds == null ? List.of() : warningThresholds;
			this.warningDescription = warningDescription;
			this.useCase = useCase;
			this.inputRate = inputRate;
			this.outputRate = outputRate;
			this.tierThreshold = tierThreshold;
			this.tierInputRate = tierInputRate;
			this.tierOutputRate = tierOutputRate;
			this.defaultPruningLimit = defaultPruningLimit;
			this.requiresThoughtSignatures = requiresThoughtSignatures;
			this.httpClientFactory = httpClientFactory;
		}

		@Override
		public String id() {
			return id;
		}

		@Override
		public String displayName() {
			return displayName;
		}

		@Override
		public Long contextLimit() {
			return contextLimit;
		}

		@Override
		public String useCase() {
			return useCase;
		}

		@Override
		public Integer defaultPruningLimit() {
			return defaultPruningLimit;
		}

		@Override
		public ChatModel createChatModel(Map<String, Object> config) {
			ProviderConfig cfg = new ProviderConfig(config);
			var builder = GoogleAiGeminiChatModel.builder()
				.apiKey(cfg.requiredString("api_key"))
				.modelName(id);
			ModelBehaviorConfig.apply(builder, cfg);
			Double temperature = cfg.number("temperature");
			if (temperature != null) {
				builder.temperature(temperature);
			}
			if (requiresThoughtSignatures) {
				builder.returnThinking(true);
				builder.sendThinking(true);
			}
			Double timeoutSeconds = cfg.number("timeout_seconds");
			if (timeoutSeconds != null) {
				builder.timeout(java.time.Duration.ofSeconds(timeoutSeconds.longValue()));
			}
			return builder.build();
		}

		@Override
		public StreamingChatModel createStreamingChatModel(Map<String, Object> config) {
			ProviderConfig cfg = new ProviderConfig(config);
			var builder = GoogleAiGeminiStreamingChatModel.builder()
				.apiKey(cfg.requiredString("api_key"))
				.modelName(id);
			LlmHttpClientConfigurer.applyIfSupported(builder, httpClientFactory);
			ModelBehaviorConfig.apply(builder, cfg);
			Double temperature = cfg.number("temperature");
			if (temperature != null) {
				builder.temperature(temperature);
			}
			if (requiresThoughtSignatures) {
				builder.returnThinking(true);
				builder.sendThinking(true);
			}
			Double timeoutSeconds = cfg.number("timeout_seconds");
			if (timeoutSeconds != null) {
				builder.timeout(java.time.Duration.ofSeconds(timeoutSeconds.longValue()));
			}
			return builder.build();
		}

		@Override
		public Optional<AiModelTokenEstimator> tokenEstimator(Map<String, Object> config) {
			ProviderConfig cfg = new ProviderConfig(config);
			String apiKey = cfg.string("api_key");
			return ProviderTelemetrySupport.geminiTokenEstimatorForModel(apiKey, id)
				.or(() -> ProviderTelemetrySupport.fallbackTokenEstimator());
		}

		@Override
		public Optional<AiModelCostEstimator> costEstimator(Map<String, Object> config) {
			return Optional.of(
				(totals, modelId) -> estimateCost(totals, inputRate, outputRate, null, tierThreshold, tierInputRate, tierOutputRate)
			);
		}

		@Override
		public Optional<AiModelResponseCostEstimator> responseCostEstimator(Map<String, Object> config) {
			return ProviderTelemetrySupport.responseCostEstimator(inputRate, outputRate, null, tierThreshold, tierInputRate, tierOutputRate, 0.90, null);
		}

		@Override
		public Optional<AiModelResponseMetadataExtractor> responseMetadataExtractor(Map<String, Object> config) {
			return Optional.of((response, modelId) -> ProviderTelemetrySupport.responseMetadata(response));
		}

		@Override
		public List<ModelThreshold> thresholds(Map<String, Object> config, be.celerex.polymr.modelregistry.telemetry.TokenTotals totals) {
			return ProviderTelemetrySupport.thresholdsWithDefaults(config, totals, contextLimit, warningThresholds, warningDescription);
		}
	}

	private static be.celerex.polymr.modelregistry.telemetry.CostEstimate estimateCost(
			be.celerex.polymr.modelregistry.telemetry.TokenTotals totals,
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
}
