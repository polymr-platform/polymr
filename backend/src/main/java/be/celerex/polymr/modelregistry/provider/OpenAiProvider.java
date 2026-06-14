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
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import be.celerex.polymr.llm.LlmHttpClientFactory;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class OpenAiProvider implements AiChatModelProvider {
	@jakarta.inject.Inject
	LlmHttpClientFactory httpClientFactory;

	@Override
	public String id() {
		return "openai";
	}

	@Override
	public String displayName() {
		return "OpenAI";
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
					"sk-...",
					"Required for OpenAI",
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
				)
			)
		);
		properties.addAll(ProviderTelemetrySupport.telemetryProperties());
		return properties;
	}

	@Override
	public List<AiChatModelDefinition> supportedChatModels() {
		return List.of(
			new OpenAiModelDefinition(
				"gpt-5.4-pro",
				"GPT-5.4 Pro",
				1_000_000L,
				"High-stakes business analysis, visual document generation, and complex financial modeling.",
				List.of(272_000L),
				"Pricing doubles after this threshold.",
				30.00,
				180.00,
				180.00,
				null,
				272_000L,
				60.00,
				270.00,
				240_000,
				false,
				httpClientFactory
			),
			new OpenAiModelDefinition(
				"gpt-5.4",
				"GPT-5.4",
				1_000_000L,
				"Balanced flagship for advanced reasoning and multimodal chat.",
				List.of(272_000L),
				"Pricing increases after this threshold.",
				2.50,
				15.00,
				15.00,
				0.25,
				272_000L,
				null,
				15.00,
				240_000,
				false,
				httpClientFactory
			),
			new OpenAiModelDefinition(
				"gpt-5.4-mini",
				"GPT-5.4 Mini",
				400_000L,
				"Cost-efficient reasoning for high-volume chat and extraction.",
				null,
				null,
				0.75,
				4.50,
				4.50,
				0.075,
				null,
				null,
				null,
				null,
				false,
				httpClientFactory
			),
			new OpenAiModelDefinition(
				"gpt-5.5-pro",
				"GPT-5.5 Pro",
				1_000_000L,
				"Frontier reasoning and long-context analysis for mission-critical workflows.",
				List.of(272_000L),
				"Pricing doubles after this threshold.",
				30.00,
				180.00,
				180.00,
				null,
				272_000L,
				60.00,
				270.00,
				240_000,
				false,
				httpClientFactory
			),
			new OpenAiModelDefinition(
				"gpt-5.5",
				"GPT-5.5",
				1_000_000L,
				"Improved flagship for complex agents, reasoning, and creative tasks.",
				null,
				null,
				5.00,
				30.00,
				30.00,
				0.50,
				null,
				null,
				30.00,
				null,
				false,
				httpClientFactory
			),
			new OpenAiModelDefinition(
				"gpt-5.3-codex",
				"GPT-5.3 Codex",
				400_000L,
				"Code generation, refactors, and high-accuracy code synthesis.",
				null,
				null,
				1.75,
				14.00,
				14.00,
				0.175,
				null,
				null,
				null,
				null,
				true,
				httpClientFactory
			),
			new OpenAiModelDefinition(
				"gpt-5.2-codex",
				"GPT-5.2 Codex",
				400_000L,
				"Coding assistant for day-to-day development and automation.",
				null,
				null,
				1.75,
				14.00,
				14.00,
				0.175,
				null,
				null,
				null,
				null,
				true,
				httpClientFactory
			),
			new OpenAiModelDefinition(
				"gpt-5.2",
				"GPT-5.2",
				128_000L,
				"General-purpose flagship for complex agents and creative tasks.",
				null,
				null,
				1.75,
				14.00,
				14.00,
				0.175,
				null,
				null,
				null,
				null,
				false,
				httpClientFactory
			),
			new OpenAiModelDefinition(
				"gpt-5.1-codex",
				"GPT-5.1 Codex",
				128_000L,
				"Stable coding model for repositories, tests, and automation.",
				null,
				null,
				1.25,
				10.00,
				10.00,
				0.125,
				null,
				null,
				null,
				null,
				true,
				httpClientFactory
			),
			new OpenAiModelDefinition(
				"gpt-5.1-codex-mini",
				"GPT-5.1 Codex Mini",
				128_000L,
				"Lightweight coding model for quick edits and snippets.",
				null,
				null,
				0.25,
				2.00,
				2.00,
				0.025,
				null,
				null,
				null,
				null,
				true,
				httpClientFactory
			),
			new OpenAiModelDefinition(
				"gpt-5-mini",
				"GPT-5 Mini",
				128_000L,
				"Low-latency chat, fast logic-based classification, and high-volume data scraping.",
				null,
				null,
				0.25,
				2.00,
				2.00,
				0.025,
				null,
				null,
				null,
				null,
				false,
				httpClientFactory
			),
			new OpenAiModelDefinition(
				"o3",
				"o3",
				200_000L,
				"Advanced mathematics, logical proofs, and deep architecture planning.",
				null,
				null,
				2.00,
				8.00,
				8.00,
				0.50,
				null,
				null,
				null,
				null,
				false,
				httpClientFactory
			),
			new OpenAiModelDefinition(
				"o4-mini",
				"o4-mini",
				128_000L,
				"Affordable reasoning for logic-heavy subtasks within larger workflows.",
				null,
				null,
				1.10,
				4.40,
				4.40,
				0.275,
				null,
				null,
				null,
				null,
				false,
				httpClientFactory
			),
			new OpenAiModelDefinition(
				"gpt-4o",
				"GPT-4o",
				128_000L,
				"Legacy stability for mature production pipelines.",
				null,
				null,
				2.50,
				10.00,
				10.00,
				1.25,
				null,
				null,
				null,
				null,
				false,
				httpClientFactory
			)
		);
	}

	private static final class OpenAiModelDefinition implements AiChatModelDefinition {
		private final String id;
		private final String displayName;
		private final Long contextLimit;
		private final String useCase;
		private final List<Long> warningThresholds;
		private final String warningDescription;
		private final double inputRate;
		private final double outputRate;
		private final Double reasoningRate;
		private final Double cachedInputRate;
		private final Long tierThreshold;
		private final Double tierInputRate;
		private final Double tierOutputRate;
		private final Integer defaultPruningLimit;
		private final boolean useResponses;
		private final LlmHttpClientFactory httpClientFactory;

		private OpenAiModelDefinition(
				String id,
				String displayName,
				Long contextLimit,
				String useCase,
				List<Long> warningThresholds,
				String warningDescription,
				double inputRate,
				double outputRate,
				Double reasoningRate,
				Double cachedInputRate,
				Long tierThreshold,
				Double tierInputRate,
				Double tierOutputRate,
				Integer defaultPruningLimit,
				boolean useResponses,
				LlmHttpClientFactory httpClientFactory) {
			this.id = id;
			this.displayName = displayName;
			this.contextLimit = contextLimit;
			this.useCase = useCase;
			this.warningThresholds = warningThresholds == null ? List.of() : warningThresholds;
			this.warningDescription = warningDescription;
			this.inputRate = inputRate;
			this.outputRate = outputRate;
			this.reasoningRate = reasoningRate;
			this.cachedInputRate = cachedInputRate;
			this.tierThreshold = tierThreshold;
			this.tierInputRate = tierInputRate;
			this.tierOutputRate = tierOutputRate;
			this.defaultPruningLimit = defaultPruningLimit;
			this.useResponses = useResponses;
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
			if (useResponses) {
				StreamingChatModel streaming = createStreamingChatModel(config);
				return new StreamingChatModelAdapter(streaming);
			}
			var builder = OpenAiChatModel.builder()
				.apiKey(cfg.requiredString("api_key"))
				.modelName(id);
			ModelBehaviorConfig.apply(builder, cfg);
			Double temperature = cfg.number("temperature");
			if (temperature != null) {
				builder.temperature(temperature);
			}
			return builder.build();
		}

		@Override
		public StreamingChatModel createStreamingChatModel(Map<String, Object> config) {
			ProviderConfig cfg = new ProviderConfig(config);
			if (useResponses) {
				var builder = OpenAiResponsesStreamingChatModel.builder()
					.apiKey(cfg.requiredString("api_key"))
					.modelName(id);
				LlmHttpClientConfigurer.applyIfSupported(builder, httpClientFactory);
				ModelBehaviorConfig.apply(builder, cfg);
				Double temperature = cfg.number("temperature");
				if (temperature != null) {
					builder.temperature(temperature);
				}
				return builder.build();
			}
			var builder = OpenAiStreamingChatModel.builder()
				.apiKey(cfg.requiredString("api_key"))
				.modelName(id);
			LlmHttpClientConfigurer.applyIfSupported(builder, httpClientFactory);
			ModelBehaviorConfig.apply(builder, cfg);
			Double temperature = cfg.number("temperature");
			if (temperature != null) {
				builder.temperature(temperature);
			}
			return builder.build();
		}

		@Override
		public Optional<AiModelTokenEstimator> tokenEstimator(Map<String, Object> config) {
			return ProviderTelemetrySupport.openAiTokenEstimatorForModel(id)
				.or(() -> ProviderTelemetrySupport.fallbackTokenEstimator());
		}

		@Override
		public Optional<AiModelCostEstimator> costEstimator(Map<String, Object> config) {
			return Optional.of(
				(totals, modelId) -> estimateCost(
					totals,
					inputRate,
					outputRate,
					reasoningRate,
					cachedInputRate,
					tierThreshold,
					tierInputRate,
					tierOutputRate
				)
			);
		}

		@Override
		public Optional<AiModelResponseCostEstimator> responseCostEstimator(Map<String, Object> config) {
			return ProviderTelemetrySupport.responseCostEstimator(
				inputRate,
				outputRate,
				reasoningRate,
				tierThreshold,
				tierInputRate,
				tierOutputRate,
				cacheDiscountRate(),
				null
			);
		}

		private Double cacheDiscountRate() {
			if (cachedInputRate == null || inputRate <= 0) {
				return null;
			}
			double ratio = cachedInputRate / inputRate;
			double discount = 1.0 - ratio;
			if (discount <= 0) {
				return null;
			}
			return Math.min(1.0, discount);
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
			Double cachedInputRate,
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
		double resolvedReasoningRate = reasoningRate == null ? 0 : reasoningRate;
		long cachedInputTokens = totals.cachedInput() == null ? 0 : totals.cachedInput();
		long inputTokens = totals.input();
		long billableInputTokens = Math.max(0, inputTokens - Math.max(0, cachedInputTokens));
		BigDecimal total = BigDecimal.ZERO;
		total = total.add(costForTokens(billableInputTokens, resolvedInputRate));
		if (cachedInputTokens > 0) {
			double cachedRate = cachedInputRate == null ? resolvedInputRate : cachedInputRate;
			total = total.add(costForTokens(cachedInputTokens, cachedRate));
		}
		total = total.add(costForTokens(totals.output(), resolvedOutputRate));
		if (totals.reasoning() != null && resolvedReasoningRate > 0) {
			total = total.add(costForTokens(totals.reasoning(), resolvedReasoningRate));
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
