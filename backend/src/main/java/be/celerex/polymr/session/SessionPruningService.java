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
import be.celerex.polymr.model.SessionEventType;
import be.celerex.polymr.modelregistry.ModelConfigService;
import be.celerex.polymr.modelregistry.provider.AiChatModelDefinition;
import be.celerex.polymr.modelregistry.provider.AiChatModelProvider;
import be.celerex.polymr.modelregistry.provider.AiChatModelProviderRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jboss.logging.Logger;

@ApplicationScoped
public class SessionPruningService {
	private static final Logger LOGGER = Logger.getLogger(SessionPruningService.class);
	private static final String UNLOADED_MESSAGE = "[SYSTEM: Payload unloaded to save context.]";
	private static final int LIGHT_MAX_TOKENS = 499;
	private static final int MEDIUM_MAX_TOKENS = 1999;
	private static final int LARGE_MIN_TOKENS = 2000;
	private static final int LARGE_MIN_TURNS_OLD = 4;
	private static final int MEDIUM_MIN_TURNS_OLD = 6;
	private static final String MODEL_KEY = "model_id";
	private static final String PRUNING_LIMIT_KEY = "pruning_limit_tokens";
	private static final String PRUNING_THRESHOLD_KEY = "pruning_threshold_ratio";
	private static final double DEFAULT_PRUNING_THRESHOLD_RATIO = 0.8D;
	private static final double PRUNING_OFFSET_INCREMENT = 0.05D;
	private static final double PRUNING_OFFSET_MAX = 0.5D;
	private static final double PRUNING_RESULT_RATIO_TRIGGER = 0.5D;
	@Inject
	EntityManager entityManager;

	@Inject
	ObjectMapper objectMapper;

	@Inject
	ModelConfigService modelConfigService;

	@Inject
	AiChatModelProviderRegistry providerRegistry;

	@Transactional
	public boolean shouldAutoPrune(Session session, List<SessionEvent> events) {
		if (session == null) {
			return false;
		}
		int latestTokens = latestContextTokens(events);
		Integer pruningLimit = resolvePruningLimit(session);
		Integer effectiveLimit = effectivePruningLimit(session, pruningLimit);
		boolean shouldPrune = effectiveLimit != null && effectiveLimit > 0 && latestTokens >= effectiveLimit;
		LOGGER.infof(
			"Auto-prune check session=%s latestTokens=%s pruningLimit=%s effectiveLimit=%s shouldPrune=%s",
			session.id,
			latestTokens,
			pruningLimit,
			effectiveLimit,
			shouldPrune
		);
		return shouldPrune;
	}

	@Transactional
	public PruneResult pruneSession(UUID sessionId) {
		if (sessionId == null) {
			return new PruneResult(0, 0, false, 0, null);
		}
		Session session = entityManager.find(Session.class, sessionId);
		if (session == null) {
			return new PruneResult(0, 0, false, 0, null);
		}
		List<SessionEvent> events = entityManager.createQuery(
				"select e from SessionEvent e where e.session.id = :sessionId order by e.createdAt",
				SessionEvent.class
			)
			.setParameter("sessionId", sessionId)
			.getResultList();
		int totalTokens = latestContextTokens(events);
		Integer pruningLimit = resolvePruningLimit(session);
		Integer effectivePruningLimit = effectivePruningLimit(session, pruningLimit);
		boolean thresholdReached = effectivePruningLimit != null && effectivePruningLimit > 0 && totalTokens >= effectivePruningLimit;
		int prunedEvents = 0;
		int prunedTokens = 0;
		for (int index = 0; index < events.size(); index += 1) {
			SessionEvent event = events.get(index);
			if (!isPrunableType(event)) {
				continue;
			}
			int turnsOld = countTurnsAfter(events, index);
			PruneClass pruneClass = classify(event);
			if (!isEligible(pruneClass, turnsOld)) {
				continue;
			}
			JsonNode nextPayload = prunePayload(event, pruneClass);
			if (nextPayload == null || nextPayload.equals(event.payloadJson)) {
				continue;
			}
			if (event.prunedPayloadJson != null) {
				continue;
			}
			int updated = entityManager.createQuery(
					"update SessionEvent e set e.prunedPayloadJson = :payload where "
						+ "e.id = :eventId and e.prunedPayloadJson is null"
				)
				.setParameter("payload", nextPayload)
				.setParameter("eventId", event.id)
				.executeUpdate();
			if (updated != 1) {
				continue;
			}
			prunedEvents += 1;
			prunedTokens += Math.max(0, estimateTokens(event.payloadJson) - estimateTokens(nextPayload));
		}
		if (prunedTokens > 0) {
			entityManager.createQuery(
					"update Session s set s.cumulativePrunedTokens = "
						+ "coalesce(s.cumulativePrunedTokens, 0) + :delta where s.id = :sessionId"
				)
				.setParameter("delta", Long.valueOf(prunedTokens))
				.setParameter("sessionId", sessionId)
				.executeUpdate();
			if (shouldRaiseThresholdOffset(totalTokens, prunedTokens)) {
				entityManager.createQuery(
						"update Session s set s.pruningThresholdOffsetRatio = "
							+ "least(coalesce(s.pruningThresholdOffsetRatio, 0) + :increment, :maxValue) "
							+ "where s.id = :sessionId"
					)
					.setParameter("increment", PRUNING_OFFSET_INCREMENT)
					.setParameter("maxValue", PRUNING_OFFSET_MAX)
					.setParameter("sessionId", sessionId)
					.executeUpdate();
			}
		}
		return new PruneResult(prunedEvents, prunedTokens, thresholdReached, totalTokens, effectivePruningLimit);
	}

	public JsonNode payloadForHistory(SessionEvent event) {
		if (event == null) {
			return null;
		}
		return event.prunedPayloadJson != null ? event.prunedPayloadJson : event.payloadJson;
	}

	public JsonNode payloadForDisplay(SessionEvent event) {
		if (event == null) {
			return null;
		}
		return event.payloadJson;
	}

	private boolean isPrunableType(SessionEvent event) {
		return event != null
			&& (event.eventType == SessionEventType.TOOL_CALL
					|| event.eventType == SessionEventType.TOOL_RESULT);
	}

	private boolean isEligible(PruneClass pruneClass, int turnsOld) {
		return switch (pruneClass) {
			case LARGE -> turnsOld >= LARGE_MIN_TURNS_OLD;
			case MEDIUM -> turnsOld >= MEDIUM_MIN_TURNS_OLD;
			case LIGHT -> false;
		};
	}

	private PruneClass classify(SessionEvent event) {
		int tokens = combinedTokens(event);
		if (tokens >= LARGE_MIN_TOKENS) {
			return PruneClass.LARGE;
		}
		if (tokens > LIGHT_MAX_TOKENS && tokens <= MEDIUM_MAX_TOKENS) {
			return PruneClass.MEDIUM;
		}
		return PruneClass.LIGHT;
	}

	private int combinedTokens(SessionEvent event) {
		if (event == null) {
			return 0;
		}
		int total = safe(event.inputTokens) + safe(event.outputTokens);
		if (total > 0) {
			return total;
		}
		return estimateTokens(event.payloadJson);
	}

	private int latestContextTokens(List<SessionEvent> events) {
		if (events == null || events.isEmpty()) {
			return 0;
		}
		for (int i = events.size() - 1; i >= 0; i -= 1) {
			int tokens = contextTokens(events.get(i));
			if (tokens > 0) {
				return tokens;
			}
		}
		return 0;
	}

	private int countTurnsAfter(List<SessionEvent> events, int index) {
		int turns = 0;
		for (int i = index + 1; i < events.size(); i += 1) {
			SessionEventType type = events.get(i).eventType;
			if (type == SessionEventType.USER_MESSAGE || type == SessionEventType.ASSISTANT_MESSAGE) {
				turns += 1;
			}
		}
		return turns;
	}

	private JsonNode prunePayload(SessionEvent event, PruneClass pruneClass) {
		if (event == null || event.payloadJson == null) {
			return null;
		}
		JsonNode copy = event.payloadJson.deepCopy();
		if (event.eventType == SessionEventType.TOOL_CALL) {
			return pruneToolCall(copy, pruneClass);
		}
		if (event.eventType == SessionEventType.TOOL_RESULT) {
			return pruneToolResult(copy, pruneClass);
		}
		return copy;
	}

	private JsonNode pruneToolCall(JsonNode payload, PruneClass pruneClass) {
		if (!(payload instanceof ObjectNode objectNode)) {
			return payload;
		}
		JsonNode argumentsNode = objectNode.get("arguments");
		if (argumentsNode == null || argumentsNode.isNull() || pruneClass != PruneClass.LARGE) {
			return objectNode;
		}
		JsonNode parsedArguments = parseArguments(argumentsNode);
		if (parsedArguments instanceof ObjectNode argumentsObject) {
			pruneLargeFields(argumentsObject);
			objectNode.set("arguments", argumentsObject);
		}
		else if (estimateTokens(argumentsNode) >= LARGE_MIN_TOKENS) {
			objectNode.put("arguments", UNLOADED_MESSAGE);
		}
		return objectNode;
	}

	private JsonNode pruneToolResult(JsonNode payload, PruneClass pruneClass) {
		if (!(payload instanceof ObjectNode objectNode)) {
			return payload;
		}
		if (pruneClass == PruneClass.LIGHT) {
			return objectNode;
		}
		if (isLargeOutput(objectNode)) {
			if (objectNode.has("text")) {
				objectNode.put("text", UNLOADED_MESSAGE);
			}
			JsonNode resultNode = objectNode.get("result");
			pruneToolResultContent(resultNode);
		}
		return objectNode;
	}

	private void pruneToolResultContent(JsonNode resultNode) {
		if (resultNode instanceof ArrayNode arrayNode) {
			for (JsonNode entry : arrayNode) {
				if (entry instanceof ObjectNode item && "text".equals(item.path("type").asText(null))) {
					item.put("text", UNLOADED_MESSAGE);
				}
			}
		}
		else if (resultNode instanceof ObjectNode objectNode) {
			if ("text".equals(objectNode.path("type").asText(null))) {
				objectNode.put("text", UNLOADED_MESSAGE);
			}
			if (objectNode.has("content")) {
				pruneToolResultContent(objectNode.get("content"));
			}
		}
	}

	private boolean isLargeOutput(ObjectNode objectNode) {
		return estimateTokens(objectNode.get("text")) >= LARGE_MIN_TOKENS
			|| estimateTokens(objectNode.get("result")) >= LARGE_MIN_TOKENS;
	}

	private JsonNode parseArguments(JsonNode argumentsNode) {
		if (argumentsNode == null || argumentsNode.isNull()) {
			return null;
		}
		if (argumentsNode.isObject() || argumentsNode.isArray()) {
			return argumentsNode.deepCopy();
		}
		if (!argumentsNode.isTextual()) {
			return argumentsNode.deepCopy();
		}
		String value = argumentsNode.asText();
		if (value == null || value.isBlank()) {
			return objectMapper.createObjectNode();
		}
		try {
			return objectMapper.readTree(value);
		}
		catch (Exception ignored) {
			return argumentsNode.deepCopy();
		}
	}

	private void pruneLargeFields(JsonNode node) {
		if (node instanceof ObjectNode objectNode) {
			List<String> fieldNames = new ArrayList<>();
			objectNode.fieldNames().forEachRemaining(fieldNames::add);
			for (String fieldName : fieldNames) {
				JsonNode child = objectNode.get(fieldName);
				if (estimateTokens(child) >= LARGE_MIN_TOKENS) {
					objectNode.put(fieldName, UNLOADED_MESSAGE);
				}
				else {
					pruneLargeFields(child);
				}
			}
		}
		else if (node instanceof ArrayNode arrayNode) {
			for (int i = 0; i < arrayNode.size(); i += 1) {
				JsonNode child = arrayNode.get(i);
				if (estimateTokens(child) >= LARGE_MIN_TOKENS) {
					arrayNode.set(i, objectMapper.getNodeFactory().textNode(UNLOADED_MESSAGE));
				}
				else {
					pruneLargeFields(child);
				}
			}
		}
	}

	private boolean shouldRaiseThresholdOffset(int totalTokens, int prunedTokens) {
		if (totalTokens <= 0 || prunedTokens <= 0) {
			return false;
		}
		return ((double) prunedTokens / (double) totalTokens) >= PRUNING_RESULT_RATIO_TRIGGER;
	}

	private Integer effectivePruningLimit(Session session, Integer pruningLimit) {
		if (session == null || pruningLimit == null || pruningLimit <= 0) {
			return pruningLimit;
		}
		double offset = session.pruningThresholdOffsetRatio == null ? 0D : session.pruningThresholdOffsetRatio;
		if (offset <= 0D) {
			return pruningLimit;
		}
		return Math.max(1, (int) Math.floor(pruningLimit * (1D + offset)));
	}

	private Integer resolvePruningLimit(Session session) {
		AiModel model = resolveModel(session);
		if (model == null || !model.enabled) {
			return null;
		}
		Map<String, Object> config = modelConfigService.resolveConfig(model);
		Integer configuredLimit = integerValue(config.get(PRUNING_LIMIT_KEY));
		if (configuredLimit != null && configuredLimit > 0) {
			return configuredLimit;
		}
		AiChatModelProvider provider = providerRegistry.find(model.provider).orElse(null);
		if (provider == null) {
			return null;
		}
		String modelId = stringValue(config.get(MODEL_KEY));
		AiChatModelDefinition definition = provider.resolveChatModel(modelId).orElse(null);
		if (definition == null) {
			return null;
		}
		Integer providerDefaultLimit = definition.defaultPruningLimit();
		if (providerDefaultLimit != null && providerDefaultLimit > 0) {
			return providerDefaultLimit;
		}
		if (definition.contextLimit() == null || definition.contextLimit() <= 0) {
			return null;
		}
		Double thresholdRatio = doubleValue(config.get(PRUNING_THRESHOLD_KEY));
		if (thresholdRatio == null || thresholdRatio <= 0D) {
			thresholdRatio = DEFAULT_PRUNING_THRESHOLD_RATIO;
		}
		return Math.max(1, (int) Math.floor(definition.contextLimit() * thresholdRatio));
	}

	private AiModel resolveModel(Session session) {
		if (session == null || session.id == null) {
			return null;
		}
		return entityManager.createQuery(
				"select m from Session s join s.defaultAssistant a join a.model m where s.id = :sessionId",
				AiModel.class
			)
			.setParameter("sessionId", session.id)
			.getResultStream()
			.findFirst()
			.orElse(null);
	}

	private int contextTokens(SessionEvent event) {
		if (event == null) {
			return 0;
		}
		int input = safe(event.inputTokens);
		int output = safe(event.outputTokens);
		int reasoning = safe(event.reasoningTokens);
		int total = input + output + reasoning;
		if (total > 0) {
			return total;
		}
		return 0;
	}

	private Integer integerValue(Object value) {
		if (value instanceof Number number) {
			return number.intValue();
		}
		if (value instanceof String text && !text.isBlank()) {
			try {
				return Integer.parseInt(text.trim());
			}
			catch (NumberFormatException ignored) {
				return null;
			}
		}
		return null;
	}

	private Double doubleValue(Object value) {
		if (value instanceof Number number) {
			return number.doubleValue();
		}
		if (value instanceof String text && !text.isBlank()) {
			try {
				return Double.parseDouble(text.trim());
			}
			catch (NumberFormatException ignored) {
				return null;
			}
		}
		return null;
	}

	private String stringValue(Object value) {
		if (value == null) {
			return null;
		}
		String text = String.valueOf(value);
		return text.isBlank() ? null : text;
	}

	private int estimateTokens(JsonNode node) {
		if (node == null || node.isNull()) {
			return 0;
		}
		String text = node.isTextual() ? node.asText() : node.toString();
		if (text == null || text.isBlank()) {
			return 0;
		}
		return Math.max(1, (text.length() + 3) / 4);
	}

	private int safe(Integer value) {
		return value == null ? 0 : Math.max(0, value);
	}

	private enum PruneClass {
		LIGHT,
		MEDIUM,
		LARGE
	}

	public record PruneResult(
			int prunedEvents,
			int prunedTokens,
			boolean thresholdReached,
			int estimatedTokens,
			Integer pruningLimit) {}
}
