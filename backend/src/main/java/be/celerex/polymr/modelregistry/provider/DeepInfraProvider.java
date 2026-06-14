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

import be.celerex.polymr.llm.LlmHttpClientFactory;
import be.celerex.polymr.modelregistry.dto.ProviderProperty;
import be.celerex.polymr.modelregistry.dto.ProviderPropertyType;
import be.celerex.polymr.modelregistry.telemetry.ModelThreshold;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class DeepInfraProvider implements AiChatModelProvider {
	@jakarta.inject.Inject
	LlmHttpClientFactory httpClientFactory;

	@Override
	public String id() {
		return "deepinfra";
	}

	@Override
	public String displayName() {
		return "DeepInfra";
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
					"di-...",
					"Required for DeepInfra",
					null,
					null,
					true,
					null,
					null,
					null
				),
				new ProviderProperty(
					"base_url",
					"Base URL",
					ProviderPropertyType.STRING,
					true,
					"https://api.deepinfra.com/v1",
					"DeepInfra OpenAI-compatible API base URL.",
					"https://api.deepinfra.com/v1",
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
			new DeepInfraModelDefinition(
				"meta-llama/Meta-Llama-3.1-70B-Instruct",
				"Llama 3.1 70B Instruct",
				"General purpose",
				httpClientFactory
			),
			new DeepInfraModelDefinition(
				"meta-llama/Meta-Llama-3.1-8B-Instruct",
				"Llama 3.1 8B Instruct",
				"Fast + low cost",
				httpClientFactory
			),
			new DeepInfraModelDefinition("Qwen/Qwen2.5-72B-Instruct", "Qwen 2.5 72B Instruct", "Reasoning + coding", httpClientFactory),
			new DeepInfraModelDefinition(
				"mistralai/Mixtral-8x22B-Instruct-v0.1",
				"Mixtral 8x22B Instruct",
				"Mixture of experts",
				httpClientFactory
			)
		);
	}

	@Override
	public Optional<AiChatModelDefinition> resolveChatModel(String modelId) {
		if (modelId == null || modelId.isBlank()) {
			return Optional.empty();
		}
		return Optional.of(new DeepInfraModelDefinition(modelId.trim(), modelId.trim(), "DeepInfra model", httpClientFactory));
	}

	private static final class DeepInfraModelDefinition implements AiChatModelDefinition {
		private final String id;
		private final String displayName;
		private final String useCase;
		private final LlmHttpClientFactory httpClientFactory;

		private DeepInfraModelDefinition(
				String id,
				String displayName,
				String useCase,
				LlmHttpClientFactory httpClientFactory) {
			this.id = id;
			this.displayName = displayName;
			this.useCase = useCase;
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
			return null;
		}

		@Override
		public String useCase() {
			return useCase;
		}

		@Override
		public ChatModel createChatModel(Map<String, Object> config) {
			ProviderConfig cfg = new ProviderConfig(config);
			var builder = OpenAiChatModel.builder()
				.apiKey(cfg.requiredString("api_key"))
				.baseUrl(cfg.requiredString("base_url"))
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
			var builder = OpenAiStreamingChatModel.builder()
				.apiKey(cfg.requiredString("api_key"))
				.baseUrl(cfg.requiredString("base_url"))
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
			return ProviderTelemetrySupport.fallbackTokenEstimator();
		}

		@Override
		public Optional<AiModelCostEstimator> costEstimator(Map<String, Object> config) {
			return Optional.empty();
		}

		@Override
		public Optional<AiModelResponseMetadataExtractor> responseMetadataExtractor(Map<String, Object> config) {
			return Optional.of((response, modelId) -> ProviderTelemetrySupport.responseMetadata(response));
		}

		@Override
		public List<ModelThreshold> thresholds(
				Map<String, Object> config,
				be.celerex.polymr.modelregistry.telemetry.TokenTotals totals) {
			return ProviderTelemetrySupport.thresholdsWithDefaults(config, totals, null, null);
		}
	}
}
