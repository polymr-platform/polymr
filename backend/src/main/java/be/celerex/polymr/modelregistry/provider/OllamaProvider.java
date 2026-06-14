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
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import be.celerex.polymr.llm.LlmHttpClientFactory;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class OllamaProvider implements AiChatModelProvider, AiEmbeddingModelProvider {
	@jakarta.inject.Inject
	LlmHttpClientFactory httpClientFactory;

	@Override
	public String id() {
		return "ollama";
	}

	@Override
	public String displayName() {
		return "Ollama";
	}

	@Override
	public List<ProviderProperty> properties() {
		return List.of(
			new ProviderProperty(
				"url",
				"Ollama URL",
				ProviderPropertyType.STRING,
				true,
				"http://localhost:11434",
				"Ollama base URL",
				"http://localhost:11434",
				null,
				true,
				null,
				null,
				null
			)
		);
	}

	@Override
	public List<AiChatModelDefinition> supportedChatModels() {
		return List.of(
			new OllamaModelDefinition(
				"llama4",
				"Llama 4",
				1_000_000L,
				"Open-weight flagship reasoning; 405B variant supports 1M context.",
				httpClientFactory,
				false
			),
			new OllamaModelDefinition(
				"deepseek-r1",
				"DeepSeek-R1",
				128_000L,
				"Local mathematical proofs, step-by-step logic, and open reasoning.",
				httpClientFactory,
				false
			),
			new OllamaModelDefinition(
				"qwen3.5",
				"Qwen 3.5",
				128_000L,
				"Multilingual performance and efficient MoE coding tasks.",
				httpClientFactory,
				false
			),
			new OllamaModelDefinition(
				"gemma3",
				"Gemma 3",
				128_000L,
				"High-efficiency local multimodal models optimized for NPUs.",
				httpClientFactory,
				false
			),
			new OllamaModelDefinition(
				"gemma4",
				"Gemma 4",
				128_000L,
				"Gemma 4 tuned for compact, local deployments.",
				httpClientFactory,
				false
			),
			new OllamaModelDefinition(
				"gpt-oss",
				"GPT-OSS",
				128_000L,
				"Open-weight GPT-style reasoning for private deployments.",
				httpClientFactory,
				false
			),
			new OllamaModelDefinition(
				"phi4",
				"Phi-4",
				128_000L,
				"Laptop-tier logic performance with strong reasoning.",
				httpClientFactory,
				false
			)
		);
	}

	@Override
	public List<AiEmbeddingModelDefinition> supportedEmbeddingModels() {
		return List.of(
			new OllamaEmbeddingDefinition("nomic-embed-text", "Nomic Embed Text", "Popular local embedding model.", 2048, httpClientFactory),
			new OllamaEmbeddingDefinition(
				"mxbai-embed-large",
				"MXBAI Embed Large",
				"High-quality general-purpose embeddings.",
				512,
				httpClientFactory
			),
			new OllamaEmbeddingDefinition("bge-m3", "BGE M3", "Multilingual and retrieval-friendly embeddings.", 8192, httpClientFactory)
		);
	}

	@Override
	public Optional<AiEmbeddingModelDefinition> resolveEmbeddingModel(String modelId) {
		Optional<AiEmbeddingModelDefinition> supported = AiEmbeddingModelProvider.super.resolveEmbeddingModel(modelId);
		if (supported.isPresent()) {
			return supported;
		}
		if (modelId == null || modelId.isBlank()) {
			return Optional.empty();
		}
		return Optional.of(
			new OllamaEmbeddingDefinition(modelId.trim(), modelId.trim(), "Custom Ollama embedding model id.", null, httpClientFactory)
		);
	}

	@Override
	public Optional<AiChatModelDefinition> resolveChatModel(String modelId) {
		Optional<AiChatModelDefinition> supported = AiChatModelProvider.super.resolveChatModel(modelId);
		if (supported.isPresent()) {
			return supported;
		}
		if (modelId == null || modelId.isBlank()) {
			return Optional.empty();
		}
		return Optional.of(
			new OllamaModelDefinition(modelId.trim(), modelId.trim(), null, "Custom Ollama model id.", httpClientFactory, true)
		);
	}

	private static final class OllamaModelDefinition implements AiChatModelDefinition {
		private final String id;
		private final String displayName;
		private final Long contextLimit;
		private final String useCase;
		private final LlmHttpClientFactory httpClientFactory;
		private final boolean fullyQualifiedModelName;

		@Override
		public List<ProviderProperty> properties() {
			List<ProviderProperty> properties = new java.util.ArrayList<>(ModelBehaviorConfig.properties());
			properties.add(
				new ProviderProperty(
					"context_limit",
					"Context limit",
					ProviderPropertyType.NUMBER,
					false,
					"65536",
					"Optional context window for Ollama (num_ctx).",
					null,
					null,
					true,
					1024.0,
					null,
					1024.0
				)
			);
			properties.add(
				new ProviderProperty(
					"temperature",
					"Temperature",
					ProviderPropertyType.NUMBER,
					false,
					"0.7",
					"Controls randomness. Lower values are more deterministic; "
						+ "higher values are more creative and diverse.",
					null,
					null,
					true,
					0.0,
					2.0,
					0.1
				)
			);
			properties.addAll(ProviderTelemetrySupport.telemetryProperties());
			properties.add(
				new ProviderProperty(
					"model_size",
					"Model size",
					ProviderPropertyType.STRING,
					false,
					"",
					"Optional size tag like 8b, 70b, or 405b. Defaults to latest when omitted.",
					null,
					null,
					true,
					null,
					null,
					null
				)
			);
			return properties;
		}

		private OllamaModelDefinition(
				String id,
				String displayName,
				Long contextLimit,
				String useCase,
				LlmHttpClientFactory httpClientFactory,
				boolean fullyQualifiedModelName) {
			this.id = id;
			this.displayName = displayName;
			this.contextLimit = contextLimit;
			this.useCase = useCase;
			this.httpClientFactory = httpClientFactory;
			this.fullyQualifiedModelName = fullyQualifiedModelName;
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
		public ChatModel createChatModel(Map<String, Object> config) {
			ProviderConfig cfg = new ProviderConfig(config);
			var builder = OllamaChatModel.builder()
				.baseUrl(cfg.requiredString("url"))
				.modelName(resolveModelName(cfg));
			ModelBehaviorConfig.apply(builder, cfg);
			Integer contextLimit = cfg.integer("context_limit");
			if (contextLimit != null && contextLimit > 0) {
				builder.numCtx(contextLimit);
			}
			Double temperature = cfg.number("temperature");
			if (temperature != null) {
				builder.temperature(temperature);
			}
			return builder.build();
		}

		@Override
		public StreamingChatModel createStreamingChatModel(Map<String, Object> config) {
			ProviderConfig cfg = new ProviderConfig(config);
			var builder = OllamaStreamingChatModel.builder()
				.baseUrl(cfg.requiredString("url"))
				.modelName(resolveModelName(cfg));
			LlmHttpClientConfigurer.applyIfSupported(builder, httpClientFactory);
			ModelBehaviorConfig.apply(builder, cfg);
			Integer contextLimit = cfg.integer("context_limit");
			if (contextLimit != null && contextLimit > 0) {
				builder.numCtx(contextLimit);
			}
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
		public List<ModelThreshold> thresholds(Map<String, Object> config, be.celerex.polymr.modelregistry.telemetry.TokenTotals totals) {
			ProviderConfig cfg = new ProviderConfig(config);
			Integer override = cfg.integer("context_limit");
			Long resolvedLimit = override == null ? contextLimit : override.longValue();
			return ProviderTelemetrySupport.thresholdsWithDefaults(config, totals, resolvedLimit, null);
		}

		private String resolveModelName(ProviderConfig cfg) {
			if (fullyQualifiedModelName) {
				return id;
			}
			String modelSize = cfg.string("model_size");
			// Default to latest when the size tag is omitted.
			if (modelSize == null || modelSize.isBlank()) {
				return id + ":latest";
			}
			return id + ":" + modelSize;
		}
	}

	private static final class OllamaEmbeddingDefinition implements AiEmbeddingModelDefinition {
		private final String id;
		private final String displayName;
		private final String useCase;
		private final Integer maxSegmentSize;
		private final LlmHttpClientFactory httpClientFactory;

		private OllamaEmbeddingDefinition(
				String id,
				String displayName,
				String useCase,
				Integer maxSegmentSize,
				LlmHttpClientFactory httpClientFactory) {
			this.id = id;
			this.displayName = displayName;
			this.useCase = useCase;
			this.maxSegmentSize = maxSegmentSize;
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
		public Integer maxSegmentSize() {
			return maxSegmentSize;
		}

		@Override
		public List<ProviderProperty> properties() {
			return List.of(
				new ProviderProperty(
					"model_size",
					"Model size",
					ProviderPropertyType.STRING,
					false,
					"",
					"Optional size tag like 8b or large. Defaults to latest when omitted.",
					null,
					null,
					true,
					null,
					null,
					null
				),
				new ProviderProperty(
					"embedding_dimensions",
					"Embedding dimensions",
					ProviderPropertyType.NUMBER,
					false,
					"",
					"Optional output dimensions when the Ollama model supports it.",
					null,
					null,
					true,
					1.0,
					null,
					1.0
				),
				new ProviderProperty(
					"max_segment_size",
					"Max segment size",
					ProviderPropertyType.NUMBER,
					maxSegmentSize == null,
					"",
					"Maximum input size per embedding segment in tokens.",
					null,
					null,
					true,
					1.0,
					null,
					1.0
				)
			);
		}

		@Override
		public EmbeddingModel createEmbeddingModel(Map<String, Object> config) {
			ProviderConfig cfg = new ProviderConfig(config);
			var builder = OllamaEmbeddingModel.builder()
				.baseUrl(cfg.requiredString("url"))
				.modelName(resolveModelName(cfg));
			LlmHttpClientConfigurer.applyIfSupported(builder, httpClientFactory);
			Integer dimensions = cfg.integer("embedding_dimensions");
			if (dimensions != null && dimensions > 0) {
				builder.dimensions(dimensions);
			}
			return builder.build();
		}

		private String resolveModelName(ProviderConfig cfg) {
			String modelSize = cfg.string("model_size");
			if (modelSize == null || modelSize.isBlank()) {
				return id + ":latest";
			}
			return id + ":" + modelSize;
		}
	}
}
