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
import be.celerex.polymr.modelregistry.telemetry.ModelThreshold;
import be.celerex.polymr.modelregistry.telemetry.TokenTotals;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface AiChatModelDefinition extends AiModelDefinition {

    default Long contextLimit() {
        return null;
    }

    default String useCase() {
        return null;
    }

    default Integer defaultPruningLimit() {
        return null;
    }

    default List<ProviderProperty> properties() {
        return ModelBehaviorConfig.properties();
    }

    ChatModel createChatModel(Map<String, Object> config);

    StreamingChatModel createStreamingChatModel(Map<String, Object> config);

    default Optional<AiModelTokenEstimator> tokenEstimator(Map<String, Object> config) {
        return Optional.empty();
    }

    default Optional<AiModelCostEstimator> costEstimator(Map<String, Object> config) {
        return Optional.empty();
    }

    default Optional<AiModelResponseUsageExtractor> responseUsageExtractor(Map<String, Object> config) {
        return Optional.of((response, modelId) -> ProviderTelemetrySupport.responseUsage(response));
    }

    default Optional<AiModelResponseCostEstimator> responseCostEstimator(Map<String, Object> config) {
        return Optional.empty();
    }

    default Optional<AiModelResponseMetadataExtractor> responseMetadataExtractor(Map<String, Object> config) {
        return Optional.of((response, modelId) -> ProviderTelemetrySupport.rawResponseMetadata(response));
    }

    default List<ModelThreshold> thresholds(Map<String, Object> config, TokenTotals totals) {
        return List.of();
    }
}
