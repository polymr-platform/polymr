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
public class TogetherProvider implements AiChatModelProvider {
	@jakarta.inject.Inject
	LlmHttpClientFactory httpClientFactory;

	@Override
	public String id() {
		return "together";
	}

	@Override
	public String displayName() {
		return "Together";
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
					"tg-...",
					"Required for Together",
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
					"https://api.together.ai/v1",
					"Together API base URL.",
					"https://api.together.ai/v1",
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
			new TogetherModelDefinition("moonshotai/Kimi-K2.5", "Kimi K2.5", "Chat + reasoning", httpClientFactory),
			new TogetherModelDefinition("deepseek-ai/DeepSeek-R1", "DeepSeek R1", "Reasoning", httpClientFactory),
			new TogetherModelDefinition("zai-org/GLM-5.1", "GLM 5.1", "Coding + tools", httpClientFactory),
			new TogetherModelDefinition("google/gemma-4-31B-it", "Gemma 4 31B IT", "Small + fast", httpClientFactory),
			new TogetherModelDefinition("openai/gpt-oss-120b", "GPT-OSS 120B", "General purpose", httpClientFactory),
			new TogetherModelDefinition(
				"meta-llama/Llama-3.3-70B-Instruct-Turbo",
				"Llama 3.3 70B Turbo",
				"Open-weight flagship",
				httpClientFactory
			)
		);
	}

	@Override
	public Optional<AiChatModelDefinition> resolveChatModel(String modelId) {
		if (modelId == null || modelId.isBlank()) {
			return Optional.empty();
		}
		return Optional.of(new TogetherModelDefinition(modelId.trim(), modelId.trim(), "Together model", httpClientFactory));
	}

	private static final class TogetherModelDefinition implements AiChatModelDefinition {
		private final String id;
		private final String displayName;
		private final String useCase;
		private final LlmHttpClientFactory httpClientFactory;

		private TogetherModelDefinition(
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
