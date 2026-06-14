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

package be.celerex.polymr.workflow.runtime;

import be.celerex.polymr.model.Session;
import be.celerex.polymr.model.SessionEvent;
import be.celerex.polymr.model.SessionEventType;
import be.celerex.polymr.model.SessionStatus;
import be.celerex.polymr.model.User;
import be.celerex.polymr.model.WorkflowRun;
import be.celerex.polymr.model.WorkflowRunCheckpoint;
import be.celerex.polymr.model.WorkflowRunStatus;
import be.celerex.polymr.automation.TitleGenerationService;
import be.celerex.polymr.infra.ServerIdentity;
import be.celerex.polymr.session.SessionActivityService;
import be.celerex.polymr.session.SessionEventService;
import be.celerex.polymr.session.SessionParticipantService;
import be.celerex.polymr.session.SessionTelemetryService;
import be.celerex.polymr.session.dto.SessionEventResponse;
import be.celerex.polymr.session.dto.SessionModelTelemetry;
import be.celerex.polymr.ws.WorkspaceSocketEvent;
import be.celerex.polymr.ws.WorkspaceSocketManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import be.celerex.polymr.workflow.runtime.PendingState;
import be.celerex.polymr.workflow.WorkflowCheckpointService;
import be.celerex.polymr.workflow.WorkflowRunDispatcher;
import be.celerex.polymr.workflow.runtime.ConversationGraphState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.transaction.TransactionSynchronizationRegistry;
import jakarta.transaction.Synchronization;
import jakarta.transaction.Status;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bsc.langgraph4j.GraphDefinition;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.Checkpoint;

@ApplicationScoped
public class WorkflowGraphCheckpointSaver implements BaseCheckpointSaver {
	private static final String NEXT_KEY = "next";
	private static final String UPDATED_AT_KEY = "updated_at";
	private static final org.jboss.logging.Logger LOGGER = org.jboss.logging.Logger.getLogger(WorkflowGraphCheckpointSaver.class);
	@Inject
	EntityManager entityManager;

	@Inject
	ObjectMapper objectMapper;

	@Inject
	SessionEventService eventService;

	@Inject
	SessionTelemetryService telemetryService;

	@Inject
	ServerIdentity serverIdentity;

	@Inject
	WorkspaceSocketManager socketManager;

	@Inject
	SessionParticipantService participantService;

	@Inject
	SessionActivityService sessionActivityService;

	@Inject
	TransactionSynchronizationRegistry txRegistry;

	@Inject
	WorkflowCheckpointService checkpointService;

	@Inject
	WorkflowRunDispatcher runDispatcher;

	@Inject
	TitleGenerationService titleGenerationService;

	@Override
	@Transactional
	public Collection<Checkpoint> list(RunnableConfig config) {
		WorkflowRun run = requireRun(config);
		List<WorkflowRunCheckpoint> checkpoints = entityManager.createQuery(
				"select c from WorkflowRunCheckpoint c where c.workflowRun.id = :runId order by c.stepIndex desc",
				WorkflowRunCheckpoint.class
			)
			.setParameter("runId", run.id)
			.getResultList();
		return checkpoints.stream().map(this::toCheckpoint).toList();
	}

	@Override
	@Transactional
	public Optional<Checkpoint> get(RunnableConfig config) {
		WorkflowRun run = requireRun(config);
		Optional<String> checkpointId = config.checkPointId();
		if (checkpointId.isPresent()) {
			WorkflowRunCheckpoint checkpoint = entityManager.find(WorkflowRunCheckpoint.class, UUID.fromString(checkpointId.get()));
			if (checkpoint == null || !checkpoint.workflowRun.id.equals(run.id)) {
				return Optional.empty();
			}
			return Optional.of(toCheckpoint(checkpoint));
		}
		WorkflowRunCheckpoint latest = entityManager.createQuery(
				"select c from WorkflowRunCheckpoint c where c.workflowRun.id = :runId order by c.stepIndex desc",
				WorkflowRunCheckpoint.class
			)
			.setParameter("runId", run.id)
			.setMaxResults(1)
			.getResultStream()
			.findFirst()
			.orElse(null);
		return latest == null ? Optional.empty() : Optional.of(toCheckpoint(latest));
	}

	@Override
	@Transactional
	public RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) {
		try {
			WorkflowRun run = requireRun(config);
			Map<String, Object> stateData = new HashMap<>(checkpoint.getState());
			Object pendingRaw = stateData.remove(ConversationGraphState.PENDING);
			ObjectNode pendingNode = PendingState.toObjectNode(objectMapper, pendingRaw);
			List<PendingEvent> pendingEvents = extractPendingEvents(PendingState.read(pendingNode, PendingState.EVENTS));
			SessionStatus pendingSessionStatus = parseSessionStatus(PendingState.readText(pendingNode, PendingState.SESSION_STATUS));
			WorkflowRunStatus pendingRunStatus = parseRunStatus(PendingState.readText(pendingNode, PendingState.RUN_STATUS));
			Boolean pendingLocked = parseLocked(PendingState.read(pendingNode, PendingState.LOCKED));
			if (pendingLocked == null) {
				if (pendingRunStatus == WorkflowRunStatus.PAUSED
						|| pendingSessionStatus == SessionStatus.PAUSED
						|| pendingRunStatus == WorkflowRunStatus.COMPLETED
						|| pendingSessionStatus == SessionStatus.COMPLETED) {
					pendingLocked = false;
				}
			}
			pendingNode.remove(PendingState.EVENTS);
			pendingNode.remove(PendingState.SESSION_STATUS);
			pendingNode.remove(PendingState.RUN_STATUS);
			pendingNode.remove(PendingState.LOCKED);
			pendingNode.remove(PendingState.TOOL_FAILURE);
			if (!pendingNode.isEmpty()) {
				stateData.put(ConversationGraphState.PENDING, pendingNode);
			}
			ObjectNode stateJson = stateToJson(stateData, checkpoint.getNextNodeId());
			sessionActivityService.clearForNodeTransition(run, checkpoint.getNodeId());
			WorkflowRunCheckpoint record = new WorkflowRunCheckpoint();
			record.workflowRun = run;
			record.stepIndex = nextStepIndex(run.id);
			record.nodeId = checkpoint.getNodeId();
			record.stateJson = stateJson;
			entityManager.persist(record);
			run.currentNode = checkpoint.getNodeId();
			run.checkpointJson = stateJson;
			Session session = run.session;
			SessionStatus previousSessionStatus = session == null ? null : session.status;
			Boolean previousLocked = session == null ? null : session.locked;
			if (pendingRunStatus != null) {
				run.status = pendingRunStatus;
				if (pendingRunStatus == WorkflowRunStatus.RUNNING) {
					run.runtimeServerId = serverIdentity.id();
				}
				else {
					run.runtimeServerId = null;
				}
			}
			if (session != null && pendingSessionStatus != null) {
				session.status = pendingSessionStatus;
			}
			if (session != null && pendingLocked != null) {
				session.locked = pendingLocked;
			}
			if (pendingRunStatus == WorkflowRunStatus.COMPLETED || pendingRunStatus == WorkflowRunStatus.FAILED) {
				notifyParentOnCompletion(run, stateJson);
			}
			List<SessionEvent> createdEvents = new ArrayList<>();
			if (session != null) {
				int epochId = resolveEpochId(run, stateJson);
				for (PendingEvent pending : pendingEvents) {
					SessionEventType type = pending.type;
					if (type == null) {
						continue;
					}
					JsonNode enrichedPayload = enrichPayloadWithContext(pending.payload, stateJson, type);
					User user = pending.userId == null ? null : entityManager.getReference(User.class, pending.userId);
					SessionEvent event = eventService.createEvent(session, user, type, enrichedPayload, pending.locationLat, pending.locationLng);
					applyPendingTelemetry(event, pending);
					createdEvents.add(event);
				}
			}
			java.util.Set<java.util.UUID> participantIds = session != null
				? participantService.listParticipants(session)
					.stream()
					.map(be.celerex.polymr.session.dto.SessionParticipantResponse::user_id)
					.collect(java.util.stream.Collectors.toSet())
				: java.util.Set.of();
			String titleSeed = shouldGenerateTitle(session, pendingEvents) ? extractFirstUserMessage(pendingEvents) : null;
			registerBroadcasts(
				session,
				pendingSessionStatus,
				pendingLocked,
				previousSessionStatus,
				previousLocked,
				createdEvents,
				participantIds,
				record.nodeId,
				stateJson
			);
			registerTitleGeneration(session, titleSeed);
			LOGGER.debugf(
				"Checkpoint persisted run=%s node=%s events=%d session=%s",
				run.id,
				record.nodeId,
				createdEvents.size(),
				session == null ? null : session.id
			);
			return RunnableConfig.builder(config)
				.checkPointId(record.id.toString())
				.build();
		}
		catch (RuntimeException exception) {
			UUID runId = null;
			try {
				runId = UUID.fromString(config.threadId().orElse(null));
			}
			catch (RuntimeException ex) {
				LOGGER.debugf(ex, "Failed to parse run id for checkpoint error reporting");
			}
			LOGGER.errorf(
				exception,
				"Failed to persist workflow checkpoint run=%s node=%s",
				runId,
				checkpoint == null ? null : checkpoint.getNodeId()
			);
			broadcastCheckpointError(runId, checkpoint, exception);
			throw exception;
		}
	}

	private void broadcastCheckpointError(UUID runId, Checkpoint checkpoint, RuntimeException exception) {
		UUID sessionId = resolveSessionId(runId, checkpoint);
		if (sessionId == null) {
			return;
		}
		Session session = null;
		try {
			session = entityManager.find(Session.class, sessionId);
		}
		catch (RuntimeException ex) {
			LOGGER.debugf(ex, "Failed to load session for checkpoint error broadcast");
		}
		if (session == null || session.workspace == null) {
			return;
		}
		Map<String, Object> payload = new HashMap<>();
		payload.put("message", "Failed to persist workflow checkpoint.");
		payload.put("run_id", runId == null ? null : runId.toString());
		payload.put("node_id", checkpoint == null ? null : checkpoint.getNodeId());
		payload.put("exception", exception == null ? null : exception.getClass().getSimpleName());
		socketManager.broadcastToSession(session.id, new WorkspaceSocketEvent("session.error", session.workspace.id, session.id, payload));
	}

	private int resolveEpochId(WorkflowRun run, ObjectNode stateJson) {
		int current = 1;
		if (run != null && run.checkpointJson != null && run.checkpointJson.hasNonNull("epoch_id")) {
			current = Math.max(1, run.checkpointJson.path("epoch_id").asInt(1));
		}
		String previousLogical = run == null || run.checkpointJson == null
			? null
			: run.checkpointJson.path(ConversationGraphState.LOGICAL_NODE_INSTANCE_ID).asText(null);
		String nextLogical = stateJson == null
			? null
			: stateJson.path(ConversationGraphState.LOGICAL_NODE_INSTANCE_ID).asText(null);
		if (nextLogical != null && previousLogical != null && !nextLogical.equals(previousLogical)) {
			current += 1;
		}
		if (stateJson != null) {
			stateJson.put("epoch_id", current);
		}
		return current;
	}

	private UUID resolveSessionId(UUID runId, Checkpoint checkpoint) {
		if (checkpoint != null && checkpoint.getState() != null) {
			Object raw = checkpoint.getState().get(ConversationGraphState.SESSION_ID);
			if (raw != null) {
				try {
					return UUID.fromString(raw.toString());
				}
				catch (RuntimeException ex) {
					LOGGER.debugf(ex, "Failed to parse session id from checkpoint state");
				}
			}
		}
		if (runId == null) {
			return null;
		}
		try {
			WorkflowRun run = entityManager.find(WorkflowRun.class, runId);
			if (run != null && run.session != null) {
				return run.session.id;
			}
		}
		catch (RuntimeException ex) {
			LOGGER.debugf(ex, "Failed to resolve session id from run");
		}
		return null;
	}

	@Override
	@Transactional
	public Tag release(RunnableConfig config) {
		Collection<Checkpoint> checkpoints = list(config);
		return new Tag(config.threadId().orElse(THREAD_ID_DEFAULT), checkpoints);
	}

	private WorkflowRun requireRun(RunnableConfig config) {
		String threadId = config.threadId()
			.orElseThrow(() -> new IllegalStateException("Missing workflow run thread id"));
		UUID runId = UUID.fromString(threadId);
		WorkflowRun run = entityManager.find(WorkflowRun.class, runId);
		if (run == null) {
			throw new IllegalStateException("Workflow run not found for thread id " + threadId);
		}
		return run;
	}

	private ObjectNode stateToJson(Map<String, Object> state, String nextNodeId) {
		ObjectNode json = objectMapper.convertValue(state, ObjectNode.class);
		json.put(NEXT_KEY, nextNodeId == null ? GraphDefinition.END : nextNodeId);
		json.put(UPDATED_AT_KEY, Instant.now().toString());
		return json;
	}

	private void registerBroadcasts(
			Session session,
			SessionStatus status,
			Boolean locked,
			SessionStatus previousStatus,
			Boolean previousLocked,
			List<SessionEvent> events,
			java.util.Set<java.util.UUID> participantIds,
			String nodeId,
			ObjectNode stateJson) {
		if (session == null) {
			return;
		}
		SessionModelTelemetry telemetrySnapshot = safeTelemetrySnapshot(session, stateJson);
		Runnable broadcastTask = () -> {
			for (SessionEvent event : events) {
				SessionEventResponse response = new SessionEventResponse(
					event.id,
					event.eventType,
					eventService.enrichPayload(event),
					event.createdAt,
					event.epochId,
					event.inputTokens,
					event.outputTokens,
					event.reasoningTokens,
					event.cachedInputTokens,
					event.tokenizerModelId,
					event.priceSnapshot,
					event.priceCurrency
				);
				socketManager.broadcastToSession(session.id, new WorkspaceSocketEvent("session.event", session.workspace.id, session.id, response));
			}
			SessionStatus nextStatus = status != null ? status : session.status;
			Boolean nextLocked = locked != null ? locked : session.locked;
			WorkspaceSocketEvent statusEvent = new WorkspaceSocketEvent(
				"session.status",
				session.workspace.id,
				session.id,
				Map.of("id", session.id, "status", nextStatus, "locked", nextLocked)
			);
			if (session.visibility == be.celerex.polymr.model.SessionVisibility.WORKSPACE) {
				socketManager.broadcast(session.workspace.id, statusEvent);
			}
			else if (!participantIds.isEmpty()) {
				socketManager.broadcastToUsers(session.workspace.id, participantIds, statusEvent);
			}
			if (telemetrySnapshot != null) {
				WorkspaceSocketEvent telemetryEvent = new WorkspaceSocketEvent("session.telemetry", session.workspace.id, session.id, telemetrySnapshot);
				if (session.visibility == be.celerex.polymr.model.SessionVisibility.WORKSPACE) {
					socketManager.broadcast(session.workspace.id, telemetryEvent);
				}
				else if (!participantIds.isEmpty()) {
					socketManager.broadcastToUsers(session.workspace.id, participantIds, telemetryEvent);
				}
			}
			if (session.workspace != null) {
				Map<String, Object> tracePayload = new HashMap<>();
				tracePayload.put("severity", "info");
				tracePayload.put("type", "workflow.state");
				String nextNode = stateJson.path(NEXT_KEY).asText("");
				String nextLabel = nextNode.isBlank() ? "" : " -> " + nextNode;
				tracePayload.put("message", "Workflow checkpoint saved: " + nodeId + nextLabel);
				tracePayload.put("timestamp", java.time.Instant.now().toString());
				Map<String, Object> details = new HashMap<>();
				details.put("node_id", nodeId);
				details.put("next", stateJson.path(NEXT_KEY).asText(null));
				details.put("state", stateJson);
				tracePayload.put("details", details);
				socketManager.broadcastTrace(
					session.workspace.id,
					session.id,
					new WorkspaceSocketEvent("session.trace", session.workspace.id, session.id, tracePayload)
				);
			}
		};
		if (txRegistry == null || txRegistry.getTransactionStatus() != Status.STATUS_ACTIVE) {
			broadcastTask.run();
			return;
		}
		txRegistry.registerInterposedSynchronization(
			new Synchronization() {
				@Override
				public void beforeCompletion() {}

				@Override
				public void afterCompletion(int completionStatus) {
					if (completionStatus != Status.STATUS_COMMITTED) {
						return;
					}
					broadcastTask.run();
				}
			}
		);
	}

	private SessionModelTelemetry safeTelemetrySnapshot(Session session, ObjectNode stateJson) {
		try {
			return telemetryService.buildTelemetry(session, stateJson);
		}
		catch (RuntimeException ignored) {
			return null;
		}
	}

	private JsonNode enrichPayloadWithContext(JsonNode payload, ObjectNode stateJson, SessionEventType type) {
		ObjectNode enriched = payload instanceof ObjectNode node ? node.deepCopy() : objectMapper.createObjectNode();
		if (payload != null && payload.isObject()) {
			enriched.setAll((ObjectNode) payload);
		}
		if (stateJson != null) {
			putIfMissing(enriched, ConversationGraphState.LOGICAL_NODE_ID, stateJson);
			putIfMissing(enriched, ConversationGraphState.LOGICAL_NODE_INSTANCE_ID, stateJson);
			putIfMissing(enriched, ConversationGraphState.RUNTIME_NODE_TYPE, stateJson);
			if (type == SessionEventType.USER_MESSAGE) {
				putIfMissing(enriched, ConversationGraphState.USER_ID, stateJson);
			}
		}
		return enriched;
	}

	private void putIfMissing(ObjectNode target, String key, ObjectNode source) {
		if (target == null || source == null || key == null) {
			return;
		}
		if (target.has(key)) {
			return;
		}
		JsonNode value = source.get(key);
		if (value != null && !value.isNull()) {
			target.set(key, value);
		}
	}

	private void registerTitleGeneration(Session session, String titleSeed) {
		if (session == null || titleSeed == null || titleSeed.isBlank()) {
			return;
		}
		Runnable queueTask = () -> titleGenerationService.queueTitleGeneration(session.id, titleSeed);
		if (txRegistry == null || txRegistry.getTransactionStatus() != Status.STATUS_ACTIVE) {
			queueTask.run();
			return;
		}
		txRegistry.registerInterposedSynchronization(
			new Synchronization() {
				@Override
				public void beforeCompletion() {}

				@Override
				public void afterCompletion(int completionStatus) {
					if (completionStatus != Status.STATUS_COMMITTED) {
						return;
					}
					queueTask.run();
				}
			}
		);
	}

	private void notifyParentOnCompletion(WorkflowRun run, ObjectNode stateJson) {
		UUID parentSessionId = readParentSessionId(stateJson);
		if (parentSessionId == null) {
			return;
		}
		try {
			WorkflowRun parentRun = entityManager.createQuery(
					"select r from WorkflowRun r join fetch r.session where r.session.id = :sessionId",
					WorkflowRun.class
				)
				.setParameter("sessionId", parentSessionId)
				.getResultStream()
				.findFirst()
				.orElse(null);
			if (parentRun == null || parentRun.session == null) {
				return;
			}
			ObjectNode parentState = parentRun.checkpointJson instanceof ObjectNode node
				? node.deepCopy()
				: objectMapper.createObjectNode();
			parentState.put("next", "await_children");
			parentState.put("updated_at", Instant.now().toString());
			parentState.set(ConversationGraphState.PAYLOAD, com.fasterxml.jackson.databind.node.NullNode.instance);
			checkpointService.saveCheckpoint(parentRun, "await_children", parentState);
			parentRun.status = WorkflowRunStatus.QUEUED;
			parentRun.runtimeServerId = null;
			parentRun.session.status = SessionStatus.ACTIVE;
			parentRun.session.locked = true;
			broadcastSubassistantProgress(parentRun, run);
			notifyDispatchAfterCommit();
		}
		catch (RuntimeException ex) {
			LOGGER.warnf(ex, "Failed to resume parent session %s after child completion", parentSessionId);
		}
	}

	private void broadcastSubassistantProgress(WorkflowRun parentRun, WorkflowRun childRun) {
		if (parentRun == null || parentRun.session == null) {
			return;
		}
		ObjectNode checkpointJson = parentRun.checkpointJson instanceof ObjectNode node ? node : null;
		List<UUID> childRunIds = readChildRunIds(checkpointJson);
		List<WorkflowRun> runs = childRunIds.isEmpty()
			? List.of()
			: entityManager.createQuery("select r from WorkflowRun r join fetch r.session where r.id in :ids", WorkflowRun.class)
				.setParameter("ids", childRunIds)
				.getResultList();
		java.util.Map<UUID, WorkflowRun> runById = new java.util.HashMap<>();
		for (WorkflowRun run : runs) {
			if (run != null && run.id != null) {
				runById.put(run.id, run);
			}
		}
		ObjectNode payload = objectMapper.createObjectNode();
		payload.put("kind", "worker_progress");
		int done = 0;
		int failed = 0;
		String filterToolCallId = resolveToolCallId(parentRun, childRun);
		ArrayNode childrenArray = payload.putArray("children");
		ObjectNode state = checkpointJson == null
			? null
			: (checkpointJson.get(ConversationGraphState.STATE) instanceof ObjectNode obj ? obj : null);
		JsonNode childrenNode = state == null ? null : state.get("children");
		if (childrenNode != null && childrenNode.isArray()) {
			for (JsonNode entry : childrenNode) {
				if (entry == null || !entry.isObject()) {
					continue;
				}
				JsonNode entryToolCallId = entry.get("tool_call_id");
				if (filterToolCallId != null && !filterToolCallId.isBlank()) {
					if (entryToolCallId == null
							|| !entryToolCallId.isTextual()
							|| !filterToolCallId.equals(entryToolCallId.asText())) {
						continue;
					}
				}
				ObjectNode item = childrenArray.addObject();
				JsonNode runIdNode = entry.get("run_id");
				UUID runId = readUuid(runIdNode);
				WorkflowRun run = runId == null ? null : runById.get(runId);
				if (runIdNode != null && runIdNode.isTextual()) {
					item.set("run_id", runIdNode);
				}
				JsonNode sessionId = entry.get("session_id");
				if (sessionId != null && sessionId.isTextual()) {
					item.set("session_id", sessionId);
				}
				JsonNode assistantName = entry.get("assistant_name");
				if (assistantName != null && assistantName.isTextual()) {
					item.set("assistant_name", assistantName);
				}
				else if (run != null
						&& run.session != null
						&& run.session.defaultAssistant != null
						&& run.session.defaultAssistant.name != null
						&& !run.session.defaultAssistant.name.isBlank()) {
					item.put("assistant_name", run.session.defaultAssistant.name);
				}
				if (run != null && run.session != null && run.session.title != null && !run.session.title.isBlank()) {
					item.put("title", run.session.title);
				}
				if (entryToolCallId != null && entryToolCallId.isTextual()) {
					item.set("tool_call_id", entryToolCallId);
					payload.put("tool_call_id", entryToolCallId.asText());
				}
				if (run != null && run.status != null) {
					item.put("status", run.status.name());
					if (run.status == WorkflowRunStatus.COMPLETED) {
						done += 1;
					}
					else if (run.status == WorkflowRunStatus.FAILED) {
						failed += 1;
					}
				}
				else {
					item.put("status", "unknown");
				}
			}
		}
		payload.put("total", childrenArray.size());
		payload.put("done", done);
		payload.put("failed", failed);
		if (childRun != null && childRun.session != null) {
			ObjectNode childNode = payload.putObject("child");
			childNode.put("session_id", childRun.session.id.toString());
			childNode.put("status", childRun.status == null ? "unknown" : childRun.status.name());
			if (filterToolCallId != null && !filterToolCallId.isBlank()) {
				childNode.put("tool_call_id", filterToolCallId);
				payload.put("tool_call_id", filterToolCallId);
			}
		}
		SessionEvent event = eventService.upsertWorkerProgress(parentRun.session, payload);
		if (event != null) {
			SessionEventResponse response = new SessionEventResponse(
				event.id,
				event.eventType,
				eventService.enrichPayload(event),
				event.createdAt,
				event.epochId,
				event.inputTokens,
				event.outputTokens,
				event.reasoningTokens,
				event.cachedInputTokens,
				event.tokenizerModelId,
				event.priceSnapshot,
				event.priceCurrency
			);
			socketManager.broadcastToSession(
				parentRun.session.id,
				new WorkspaceSocketEvent("session.event", parentRun.session.workspace.id, parentRun.session.id, response)
			);
		}
		socketManager.broadcastToSession(
			parentRun.session.id,
			new WorkspaceSocketEvent("session.worker_progress", parentRun.session.workspace.id, parentRun.session.id, payload)
		);
	}

	private List<UUID> readChildRunIds(ObjectNode checkpointJson) {
		if (checkpointJson == null) {
			return List.of();
		}
		JsonNode state = checkpointJson.get(ConversationGraphState.STATE);
		if (state == null || !state.isObject()) {
			return List.of();
		}
		JsonNode children = state.get("children");
		if (children == null || !children.isArray()) {
			return List.of();
		}
		List<UUID> ids = new ArrayList<>();
		for (JsonNode entry : children) {
			if (entry == null || !entry.isObject()) {
				continue;
			}
			UUID runId = readUuid(entry.get("run_id"));
			if (runId != null) {
				ids.add(runId);
			}
		}
		return ids;
	}

	private String resolveToolCallId(WorkflowRun parentRun, WorkflowRun childRun) {
		if (parentRun == null || childRun == null) {
			return null;
		}
		ObjectNode checkpointJson = parentRun.checkpointJson instanceof ObjectNode node ? node : null;
		if (checkpointJson == null) {
			return null;
		}
		JsonNode state = checkpointJson.get(ConversationGraphState.STATE);
		if (state == null || !state.isObject()) {
			return null;
		}
		JsonNode children = state.get("children");
		if (children == null || !children.isArray()) {
			return null;
		}
		String runId = childRun.id == null ? null : childRun.id.toString();
		if (runId == null) {
			return null;
		}
		for (JsonNode entry : children) {
			if (entry == null || !entry.isObject()) {
				continue;
			}
			JsonNode entryRun = entry.get("run_id");
			if (entryRun != null && entryRun.isTextual() && runId.equals(entryRun.asText())) {
				JsonNode toolCallId = entry.get("tool_call_id");
				return toolCallId != null && toolCallId.isTextual() ? toolCallId.asText() : null;
			}
		}
		JsonNode childState = childRun.checkpointJson == null ? null : childRun.checkpointJson.get(ConversationGraphState.STATE);
		if (childState != null && childState.isObject()) {
			JsonNode toolCallId = childState.get("tool_call_id");
			if (toolCallId != null && toolCallId.isTextual()) {
				return toolCallId.asText();
			}
		}
		return null;
	}

	private UUID readParentSessionId(ObjectNode stateJson) {
		if (stateJson == null) {
			return null;
		}
		JsonNode state = stateJson.get(ConversationGraphState.STATE);
		if (state == null || !state.isObject()) {
			return null;
		}
		JsonNode parent = state.get("parent_session_id");
		return readUuid(parent);
	}

	private UUID readUuid(JsonNode node) {
		if (node == null || node.isNull()) {
			return null;
		}
		String value = node.asText(null);
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return UUID.fromString(value);
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private void notifyDispatchAfterCommit() {
		if (txRegistry == null) {
			runDispatcher.notifyWorkAvailable();
			return;
		}
		int status = txRegistry.getTransactionStatus();
		if (status != Status.STATUS_ACTIVE) {
			runDispatcher.notifyWorkAvailable();
			return;
		}
		txRegistry.registerInterposedSynchronization(
			new Synchronization() {
				@Override
				public void beforeCompletion() {}

				@Override
				public void afterCompletion(int completionStatus) {
					if (completionStatus != Status.STATUS_COMMITTED) {
						return;
					}
					CompletableFuture.runAsync(
						() -> {
							try {
								runDispatcher.notifyWorkAvailable();
							}
							catch (Exception ex) {
								LOGGER.error("Async workflow dispatch notification failed", ex);
								throw ex;
							}
						}
					);
				}
			}
		);
	}

	private boolean shouldGenerateTitle(Session session, List<PendingEvent> pendingEvents) {
		if (session == null) {
			return false;
		}
		if (session.title != null && !session.title.isBlank()) {
			return false;
		}
		if (pendingEvents == null || pendingEvents.isEmpty()) {
			return false;
		}
		long pendingUserCount = pendingEvents.stream()
			.filter(event -> event.type == SessionEventType.USER_MESSAGE)
			.count();
		if (pendingUserCount == 0) {
			return false;
		}
		Long totalUserCount = entityManager.createQuery(
				"select count(e) from SessionEvent e where e.session.id = :sessionId and e.eventType = :type",
				Long.class
			)
			.setParameter("sessionId", session.id)
			.setParameter("type", SessionEventType.USER_MESSAGE)
			.getSingleResult();
		return totalUserCount != null && totalUserCount <= pendingUserCount;
	}

	private String extractFirstUserMessage(List<PendingEvent> pendingEvents) {
		if (pendingEvents == null) {
			return null;
		}
		for (PendingEvent event : pendingEvents) {
			if (event.type != SessionEventType.USER_MESSAGE || event.payload == null) {
				continue;
			}
			JsonNode textNode = event.payload.get("text");
			if (textNode != null && textNode.isTextual()) {
				String value = textNode.asText();
				if (value != null && !value.isBlank()) {
					return value;
				}
			}
		}
		return null;
	}

	private List<PendingEvent> extractPendingEvents(Object raw) {
		if (raw == null) {
			return List.of();
		}
		List<Map<String, Object>> values = objectMapper.convertValue(raw, List.class);
		if (values == null || values.isEmpty()) {
			return List.of();
		}
		List<PendingEvent> result = new ArrayList<>();
		for (Map<String, Object> entry : values) {
			if (entry == null) {
				continue;
			}
			Object typeRaw = entry.get("type");
			String typeName = typeRaw == null ? null : typeRaw.toString();
			SessionEventType type = typeName == null ? null : safeEventType(typeName);
			JsonNode payload = objectMapper.convertValue(entry.get("payload"), JsonNode.class);
			result.add(
				new PendingEvent(
					type,
					payload == null ? objectMapper.createObjectNode() : payload,
					readUuid(entry.get("user_id")),
					readDouble(entry.get("location_lat")),
					readDouble(entry.get("location_lng")),
					readLong(entry.get("input_tokens")),
					readLong(entry.get("output_tokens")),
					readLong(entry.get("reasoning_tokens")),
					readLong(entry.get("cached_input_tokens")),
					readText(entry.get("tokenizer_model_id")),
					readDecimal(entry.get("price_snapshot")),
					readText(entry.get("price_currency"))
				)
			);
		}
		return result;
	}

	private SessionEventType safeEventType(String name) {
		try {
			return SessionEventType.valueOf(name);
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private UUID readUuid(Object raw) {
		if (raw == null) {
			return null;
		}
		try {
			return UUID.fromString(raw.toString());
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private Double readDouble(Object raw) {
		if (raw == null) {
			return null;
		}
		if (raw instanceof Number number) {
			return number.doubleValue();
		}
		try {
			return Double.parseDouble(raw.toString());
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private Long readLong(Object raw) {
		if (raw == null) {
			return null;
		}
		if (raw instanceof Number number) {
			return number.longValue();
		}
		try {
			return Long.parseLong(raw.toString());
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private String readText(Object raw) {
		if (raw == null) {
			return null;
		}
		String value = raw.toString();
		return value.isBlank() ? null : value;
	}

	private java.math.BigDecimal readDecimal(Object raw) {
		if (raw == null) {
			return null;
		}
		if (raw instanceof java.math.BigDecimal decimal) {
			return decimal;
		}
		try {
			return new java.math.BigDecimal(raw.toString());
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private SessionStatus parseSessionStatus(Object raw) {
		if (raw == null) {
			return null;
		}
		try {
			return SessionStatus.valueOf(raw.toString());
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private WorkflowRunStatus parseRunStatus(Object raw) {
		if (raw == null) {
			return null;
		}
		try {
			return WorkflowRunStatus.valueOf(raw.toString());
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private Boolean parseLocked(Object raw) {
		if (raw == null) {
			return null;
		}
		if (raw instanceof Boolean value) {
			return value;
		}
		return Boolean.parseBoolean(raw.toString());
	}

	private void applyPendingTelemetry(SessionEvent event, PendingEvent pending) {
		if (event == null || pending == null) {
			return;
		}
		event.inputTokens = toInteger(pending.inputTokens);
		event.outputTokens = toInteger(pending.outputTokens);
		event.reasoningTokens = toInteger(pending.reasoningTokens);
		event.cachedInputTokens = toInteger(pending.cachedInputTokens);
		event.tokenizerModelId = pending.tokenizerModelId;
		event.priceSnapshot = pending.priceSnapshot;
		event.priceCurrency = pending.priceCurrency;
	}

	private Integer toInteger(Long value) {
		if (value == null) {
			return null;
		}
		long normalized = Math.max(0L, value);
		if (normalized > Integer.MAX_VALUE) {
			return Integer.MAX_VALUE;
		}
		return (int) normalized;
	}

	private record PendingEvent(
			SessionEventType type,
			JsonNode payload,
			UUID userId,
			Double locationLat,
			Double locationLng,
			Long inputTokens,
			Long outputTokens,
			Long reasoningTokens,
			Long cachedInputTokens,
			String tokenizerModelId,
			java.math.BigDecimal priceSnapshot,
			String priceCurrency) {}

	private Checkpoint toCheckpoint(WorkflowRunCheckpoint checkpoint) {
		ObjectNode node = ensureObjectNode(checkpoint.stateJson);
		String nextNode = node.path(NEXT_KEY).asText(GraphDefinition.END);
		Map<String, Object> state = objectMapper.convertValue(node, Map.class);
		state.remove(NEXT_KEY);
		state.remove(UPDATED_AT_KEY);
		return Checkpoint.builder()
			.id(checkpoint.id.toString())
			.state(state)
			.nodeId(checkpoint.nodeId)
			.nextNodeId(nextNode)
			.build();
	}

	private ObjectNode ensureObjectNode(JsonNode node) {
		if (node instanceof ObjectNode objectNode) {
			return objectNode;
		}
		return objectMapper.createObjectNode();
	}

	private int nextStepIndex(UUID runId) {
		Integer current = entityManager.createQuery(
				"select max(c.stepIndex) from WorkflowRunCheckpoint c where c.workflowRun.id = :runId",
				Integer.class
			)
			.setParameter("runId", runId)
			.getSingleResult();
		return current == null ? 1 : current + 1;
	}
}
