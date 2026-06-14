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

package be.celerex.polymr.session;

import be.celerex.polymr.model.AiModel;
import be.celerex.polymr.model.Session;
import be.celerex.polymr.model.SessionEvent;
import be.celerex.polymr.model.SessionStatus;
import be.celerex.polymr.model.WorkflowRun;
import be.celerex.polymr.modelregistry.ModelConfigService;
import be.celerex.polymr.modelregistry.provider.AiModelCostEstimator;
import be.celerex.polymr.modelregistry.provider.AiChatModelDefinition;
import be.celerex.polymr.modelregistry.provider.AiChatModelProvider;
import be.celerex.polymr.modelregistry.provider.AiChatModelProviderRegistry;
import be.celerex.polymr.modelregistry.telemetry.CostEstimate;
import be.celerex.polymr.modelregistry.telemetry.ModelThreshold;
import be.celerex.polymr.modelregistry.telemetry.TokenTotals;
import be.celerex.polymr.session.dto.SessionModelTelemetry;
import be.celerex.polymr.workflow.runtime.ConversationGraphState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class SessionTelemetryService {
	private static final EnumSet<SessionStatus> ACTIVE_STATUSES = EnumSet.of(SessionStatus.ACTIVE, SessionStatus.PAUSED);
	@Inject
	EntityManager entityManager;

	@Inject
	AiChatModelProviderRegistry providerRegistry;

	@Inject
	ModelConfigService modelConfigService;

	public SessionModelTelemetry buildTelemetry(Session session, WorkflowRun run) {
		ObjectNode checkpoint = run != null && run.checkpointJson instanceof ObjectNode node ? node : null;
		return buildTelemetry(session, checkpoint);
	}

	public SessionModelTelemetry buildTelemetry(Session session, ObjectNode checkpoint) {
		if (session == null) {
			return null;
		}
		LogicalContext context = resolveLogicalContext(checkpoint);
		List<SessionEvent> events = entityManager.createQuery(
				"select e from SessionEvent e where e.session.id = :sessionId order by e.createdAt",
				SessionEvent.class
			)
			.setParameter("sessionId", session.id)
			.getResultList();
		List<SessionEvent> scopedEvents = events.stream()
			.filter(event -> matchesLogicalContext(event, context))
			.toList();

		long inputTotal = 0;
		long outputTotal = 0;
		long reasoningTotal = 0;
		long cachedInputTotal = 0;
		boolean hasReasoning = false;
		boolean hasTokenData = false;
		String referenceModelId = null;
		Set<String> modelIds = new HashSet<>();
		BigDecimal snapshotTotal = null;
		String snapshotCurrency = null;

		for (SessionEvent event : scopedEvents) {
			if (event.inputTokens != null) {
				inputTotal += event.inputTokens;
				hasTokenData = true;
			}
			if (event.outputTokens != null) {
				outputTotal += event.outputTokens;
				hasTokenData = true;
			}
			if (event.cachedInputTokens != null) {
				cachedInputTotal += event.cachedInputTokens;
			}
			if (event.reasoningTokens != null) {
				reasoningTotal += event.reasoningTokens;
				hasReasoning = true;
				hasTokenData = true;
			}
			if (event.tokenizerModelId != null && !event.tokenizerModelId.isBlank()) {
				referenceModelId = event.tokenizerModelId;
				modelIds.add(event.tokenizerModelId);
			}
			if (event.priceSnapshot != null) {
				snapshotTotal = snapshotTotal == null ? BigDecimal.ZERO : snapshotTotal;
				snapshotTotal = snapshotTotal.add(event.priceSnapshot);
				if (snapshotCurrency == null && event.priceCurrency != null && !event.priceCurrency.isBlank()) {
					snapshotCurrency = event.priceCurrency;
				}
			}
		}

		Long cachedInput = cachedInputTotal > 0 ? cachedInputTotal : null;
		TokenTotals totals = new TokenTotals(inputTotal, outputTotal, hasReasoning ? reasoningTotal : null, cachedInput);
		Double cacheRatio = null;
		if (inputTotal > 0) {
			cacheRatio = Math.min(1.0, Math.max(0.0, (double) cachedInputTotal / (double) inputTotal));
		}
		boolean approx = modelIds.size() > 1;
		boolean preferCurrent = ACTIVE_STATUSES.contains(session.status);
		String currentModelId = null;
		AiChatModelProvider provider = null;
		Map<String, Object> config = null;
		AiModelCostEstimator costEstimator = null;
		AiChatModelDefinition modelDefinition = null;
		if (preferCurrent) {
			AiModel currentModel = session.defaultAssistant == null ? null : session.defaultAssistant.model;
			if (currentModel != null && currentModel.enabled) {
				provider = providerRegistry.find(currentModel.provider).orElse(null);
				if (provider != null) {
					config = modelConfigService.resolveConfig(currentModel);
					currentModelId = resolveModelId(config);
					modelDefinition = provider.resolveChatModel(currentModelId).orElse(null);
					if (modelDefinition != null) {
						costEstimator = modelDefinition.costEstimator(config).orElse(null);
					}
				}
			}
		}
		if (preferCurrent
				&& currentModelId != null
				&& referenceModelId != null
				&& !currentModelId.equals(referenceModelId)) {
			approx = true;
		}

		CostEstimate cost = null;
		String costBasis = null;
		List<ModelThreshold> thresholds = List.of();
		if (preferCurrent && modelDefinition != null) {
			thresholds = modelDefinition.thresholds(config, totals);
			if (costEstimator != null) {
				cost = costEstimator.estimate(totals, currentModelId);
				costBasis = cost == null ? null : "current";
			}
		}
		if (cost == null && snapshotTotal != null) {
			cost = new CostEstimate(snapshotTotal, snapshotCurrency == null ? "USD" : snapshotCurrency);
			costBasis = "historical";
		}
		if (session.totalCost != null) {
			cost = new CostEstimate(session.totalCost, session.totalCostCurrency == null ? "USD" : session.totalCostCurrency);
			costBasis = "session_total";
		}

		if (!hasTokenData && cost == null && (thresholds == null || thresholds.isEmpty())) {
			return null;
		}

		return new SessionModelTelemetry(
			totals,
			cachedInput,
			session.cumulativePrunedTokens,
			cacheRatio,
			approx,
			referenceModelId,
			cost,
			costBasis,
			thresholds
		);
	}

	private LogicalContext resolveLogicalContext(ObjectNode checkpoint) {
		if (checkpoint == null) {
			return new LogicalContext(null, null);
		}
		String logicalNodeId = readText(checkpoint.get(ConversationGraphState.LOGICAL_NODE_ID));
		String instanceId = readText(checkpoint.get(ConversationGraphState.LOGICAL_NODE_INSTANCE_ID));
		return new LogicalContext(logicalNodeId, instanceId);
	}

	private boolean matchesLogicalContext(SessionEvent event, LogicalContext context) {
		if (context.logicalNodeId == null || context.logicalNodeId.isBlank()) {
			return true;
		}
		JsonNode payload = event.payloadJson;
		if (payload == null || !payload.isObject()) {
			return false;
		}
		String eventLogicalId = readText(payload.get(ConversationGraphState.LOGICAL_NODE_ID));
		if (!context.logicalNodeId.equals(eventLogicalId)) {
			return false;
		}
		if (context.logicalInstanceId == null || context.logicalInstanceId.isBlank()) {
			return true;
		}
		String eventInstanceId = readText(payload.get(ConversationGraphState.LOGICAL_NODE_INSTANCE_ID));
		return context.logicalInstanceId.equals(eventInstanceId);
	}

	private String readText(JsonNode node) {
		if (node == null || node.isNull()) {
			return null;
		}
		String value = node.asText(null);
		return value == null || value.isBlank() ? null : value;
	}

	private String resolveModelId(Map<String, Object> config) {
		if (config == null) {
			return null;
		}
		Object value = config.get("model_id");
		if (value == null) {
			return null;
		}
		String modelId = value.toString();
		return modelId == null || modelId.isBlank() ? null : modelId;
	}

	private record LogicalContext(String logicalNodeId, String logicalInstanceId) {}
}
