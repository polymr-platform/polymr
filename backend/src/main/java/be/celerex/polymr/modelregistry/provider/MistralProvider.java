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
import dev.langchain4j.model.mistralai.MistralAiChatModel;
import dev.langchain4j.model.mistralai.MistralAiStreamingChatModel;
import be.celerex.polymr.llm.LlmHttpClientFactory;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class MistralProvider implements AiChatModelProvider {
	@jakarta.inject.Inject
	LlmHttpClientFactory httpClientFactory;

	@Override
	public String id() {
		return "mistral";
	}

	@Override
	public String displayName() {
		return "Mistral";
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
					"Required for Mistral",
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
			new MistralModelDefinition(
				"mistral-large-2512",
				"Mistral Large 3",
				262_000L,
				"Enterprise-grade RAG and EU/GDPR sovereign deployments.",
				0.50,
				1.50,
				220_000,
				httpClientFactory
			),
			new MistralModelDefinition(
				"devstral-2512",
				"Devstral 2",
				128_000L,
				"Software engineering, refactoring, and agentic debugging.",
				1.00,
				3.00,
				null,
				httpClientFactory
			),
			new MistralModelDefinition(
				"mistral-medium-latest",
				"Mistral Medium 3",
				128_000L,
				"Balanced business automation and internal document handling.",
				0.40,
				2.00,
				null,
				httpClientFactory
			),
			new MistralModelDefinition(
				"ministral-8b-latest",
				"Ministral 3 (8B)",
				128_000L,
				"Edge deployment and low-power, high-speed logic tasks.",
				0.10,
				0.10,
				null,
				httpClientFactory
			)
		);
	}

	private static final class MistralModelDefinition implements AiChatModelDefinition {
		private final String id;
		private final String displayName;
		private final Long contextLimit;
		private final String useCase;
		private final double inputRate;
		private final double outputRate;
		private final Integer defaultPruningLimit;
		private final LlmHttpClientFactory httpClientFactory;

		private MistralModelDefinition(
				String id,
				String displayName,
				Long contextLimit,
				String useCase,
				double inputRate,
				double outputRate,
				Integer defaultPruningLimit,
				LlmHttpClientFactory httpClientFactory) {
			this.id = id;
			this.displayName = displayName;
			this.contextLimit = contextLimit;
			this.useCase = useCase;
			this.inputRate = inputRate;
			this.outputRate = outputRate;
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
			var builder = MistralAiChatModel.builder()
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
			var builder = MistralAiStreamingChatModel.builder()
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
			return ProviderTelemetrySupport.fallbackTokenEstimator();
		}

		@Override
		public Optional<AiModelCostEstimator> costEstimator(Map<String, Object> config) {
			return Optional.of((totals, modelId) -> estimateCost(totals, inputRate, outputRate, null));
		}

		@Override
		public Optional<AiModelResponseCostEstimator> responseCostEstimator(Map<String, Object> config) {
			return ProviderTelemetrySupport.responseCostEstimator(inputRate, outputRate, null, null, null, null, 0.85, null);
		}

		@Override
		public Optional<AiModelResponseMetadataExtractor> responseMetadataExtractor(Map<String, Object> config) {
			return Optional.of((response, modelId) -> ProviderTelemetrySupport.responseMetadata(response));
		}

		@Override
		public List<ModelThreshold> thresholds(Map<String, Object> config, be.celerex.polymr.modelregistry.telemetry.TokenTotals totals) {
			return ProviderTelemetrySupport.thresholdsWithDefaults(config, totals, contextLimit, null);
		}
	}

	private static be.celerex.polymr.modelregistry.telemetry.CostEstimate estimateCost(
			be.celerex.polymr.modelregistry.telemetry.TokenTotals totals,
			double inputRate,
			double outputRate,
			Double reasoningRate) {
		if (totals == null) {
			return null;
		}
		BigDecimal total = BigDecimal.ZERO;
		total = total.add(costForTokens(totals.input(), inputRate));
		total = total.add(costForTokens(totals.output(), outputRate));
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
