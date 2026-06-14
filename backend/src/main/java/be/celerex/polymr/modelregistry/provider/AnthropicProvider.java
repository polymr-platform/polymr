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
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import be.celerex.polymr.llm.LlmHttpClientFactory;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class AnthropicProvider implements AiChatModelProvider {
	@jakarta.inject.Inject
	LlmHttpClientFactory httpClientFactory;

	@Override
	public String id() {
		return "anthropic";
	}

	@Override
	public String displayName() {
		return "Anthropic";
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
					"Required for Anthropic",
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
			new AnthropicModelDefinition(
				"claude-4-6-opus",
				"Claude 4.6 Opus",
				1_000_000L,
				"Deepest reasoning, multi-agent management, and nuanced ethical auditing.",
				List.of(200_000L),
				"Long-context pricing applies above this threshold.",
				5.00,
				25.00,
				200_000L,
				10.00,
				37.50,
				180_000,
				httpClientFactory
			),
			new AnthropicModelDefinition(
				"claude-4-6-sonnet",
				"Claude 4.6 Sonnet",
				1_000_000L,
				"Agentic coding and massive codebase analysis.",
				List.of(200_000L),
				"Long-context pricing applies above this threshold.",
				3.00,
				15.00,
				200_000L,
				6.00,
				22.50,
				180_000,
				httpClientFactory
			),
			new AnthropicModelDefinition(
				"claude-4-5-haiku",
				"Claude 4.5 Haiku",
				200_000L,
				"High-speed reasoning, sub-second responses, and automated support.",
				null,
				null,
				1.00,
				5.00,
				null,
				null,
				null,
				null,
				httpClientFactory
			),
			new AnthropicModelDefinition(
				"claude-3-5-sonnet",
				"Claude 3.5 Sonnet",
				200_000L,
				"Creative voice and stable legacy integrations.",
				null,
				null,
				3.00,
				15.00,
				null,
				null,
				null,
				null,
				httpClientFactory
			)
		);
	}

	private static final class AnthropicModelDefinition implements AiChatModelDefinition {
		private final String id;
		private final String displayName;
		private final Long contextLimit;
		private final String useCase;
		private final List<Long> warningThresholds;
		private final String warningDescription;
		private final double inputRate;
		private final double outputRate;
		private final Long tierThreshold;
		private final Double tierInputRate;
		private final Double tierOutputRate;
		private final Integer defaultPruningLimit;
		private final LlmHttpClientFactory httpClientFactory;

		private AnthropicModelDefinition(
				String id,
				String displayName,
				Long contextLimit,
				String useCase,
				List<Long> warningThresholds,
				String warningDescription,
				double inputRate,
				double outputRate,
				Long tierThreshold,
				Double tierInputRate,
				Double tierOutputRate,
				Integer defaultPruningLimit,
				LlmHttpClientFactory httpClientFactory) {
			this.id = id;
			this.displayName = displayName;
			this.contextLimit = contextLimit;
			this.useCase = useCase;
			this.warningThresholds = warningThresholds == null ? List.of() : warningThresholds;
			this.warningDescription = warningDescription;
			this.inputRate = inputRate;
			this.outputRate = outputRate;
			this.tierThreshold = tierThreshold;
			this.tierInputRate = tierInputRate;
			this.tierOutputRate = tierOutputRate;
			this.defaultPruningLimit = defaultPruningLimit;
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
			var builder = AnthropicChatModel.builder()
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
			var builder = AnthropicStreamingChatModel.builder()
				.apiKey(cfg.requiredString("api_key"))
				.modelName(id);
			ModelBehaviorConfig.apply(builder, cfg);
			LlmHttpClientConfigurer.applyIfSupported(builder, httpClientFactory);
			Double temperature = cfg.number("temperature");
			if (temperature != null) {
				builder.temperature(temperature);
			}
			return builder.build();
		}

		@Override
		public Optional<AiModelTokenEstimator> tokenEstimator(Map<String, Object> config) {
			ProviderConfig cfg = new ProviderConfig(config);
			String apiKey = cfg.string("api_key");
			return ProviderTelemetrySupport.anthropicTokenEstimatorForModel(apiKey, id)
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
			return ProviderTelemetrySupport.responseCostEstimator(inputRate, outputRate, null, tierThreshold, tierInputRate, tierOutputRate, 0.90, 1.25);
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
