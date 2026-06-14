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
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class OpenRouterProvider implements AiChatModelProvider {
	@jakarta.inject.Inject
	LlmHttpClientFactory httpClientFactory;

	@Override
	public String id() {
		return "openrouter";
	}

	@Override
	public String displayName() {
		return "OpenRouter";
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
					"sk-or-...",
					"Required for OpenRouter",
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
					"https://openrouter.ai/api/v1",
					"OpenRouter API base URL.",
					"https://openrouter.ai/api/v1",
					null,
					true,
					null,
					null,
					null
				),
				new ProviderProperty(
					"referer",
					"HTTP-Referer",
					ProviderPropertyType.STRING,
					false,
					"https://your.app",
					"Optional attribution header required by OpenRouter for analytics.",
					null,
					null,
					true,
					null,
					null,
					null
				),
				new ProviderProperty(
					"title",
					"X-Title",
					ProviderPropertyType.STRING,
					false,
					"Your App Name",
					"Optional attribution header shown in OpenRouter usage dashboards.",
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

	// e.g. deepseek/deepseek-v4-pro
	@Override
	public List<AiChatModelDefinition> supportedChatModels() {
		return List.of(
			new OpenRouterModelDefinition("openrouter/auto", "OpenRouter Auto", "Auto routing", httpClientFactory),
			new OpenRouterModelDefinition("openai/gpt-4o", "GPT-4o", "Balanced flagship chat", httpClientFactory),
			new OpenRouterModelDefinition("openai/gpt-4o-mini", "GPT-4o Mini", "Fast, cost-effective chat", httpClientFactory),
			new OpenRouterModelDefinition("anthropic/claude-3.5-sonnet", "Claude 3.5 Sonnet", "Reasoning + writing", httpClientFactory),
			new OpenRouterModelDefinition("anthropic/claude-3.5-haiku", "Claude 3.5 Haiku", "Fast assistant", httpClientFactory),
			new OpenRouterModelDefinition("google/gemini-1.5-pro", "Gemini 1.5 Pro", "Long context + multimodal", httpClientFactory),
			new OpenRouterModelDefinition("google/gemini-1.5-flash", "Gemini 1.5 Flash", "Low-latency assistant", httpClientFactory),
			new OpenRouterModelDefinition(
				"meta-llama/llama-3.1-70b-instruct",
				"Llama 3.1 70B Instruct",
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
		return Optional.of(
			new OpenRouterModelDefinition(modelId.trim(), modelId.trim(), "OpenRouter model", httpClientFactory)
		);
	}

	private static final class OpenRouterModelDefinition implements AiChatModelDefinition {
		private final String id;
		private final String displayName;
		private final String useCase;
		private final LlmHttpClientFactory httpClientFactory;

		private OpenRouterModelDefinition(
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
			applyHeaders(builder, cfg);
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
			applyHeaders(builder, cfg);
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
		public Optional<AiModelResponseCostEstimator> responseCostEstimator(Map<String, Object> config) {
			return ProviderTelemetrySupport.openRouterResponseCostEstimator();
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

		private void applyHeaders(Object builder, ProviderConfig cfg) {
			String referer = cfg.string("referer");
			String title = cfg.string("title");
			if ((referer == null || referer.isBlank()) && (title == null || title.isBlank())) {
				return;
			}
			java.util.Map<String, String> headers = new java.util.HashMap<>();
			if (referer != null && !referer.isBlank()) {
				headers.put("HTTP-Referer", referer.trim());
			}
			if (title != null && !title.isBlank()) {
				headers.put("X-Title", title.trim());
			}
			applyHeadersIfSupported(builder, headers);
		}

		private void applyHeadersIfSupported(Object builder, Map<String, String> headers) {
			if (builder == null || headers == null || headers.isEmpty()) {
				return;
			}
			invokeIfPresent(builder, "customHeaders", headers);
			invokeIfPresent(builder, "defaultHeaders", headers);
			invokeIfPresent(builder, "headers", headers);
		}

		private void invokeIfPresent(Object target, String methodName, Map<String, String> headers) {
			try {
				Method method = target.getClass().getMethod(methodName, Map.class);
				method.invoke(target, headers);
			}
			catch (NoSuchMethodException ignored) {}
			catch (Exception ex) {
				org.jboss.logging.Logger
					.getLogger(OpenRouterProvider.class)
					.debugf(ex, "Failed to apply %s headers", methodName);
			}
		}
	}
}
