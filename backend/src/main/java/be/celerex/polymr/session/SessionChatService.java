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

import be.celerex.polymr.infra.ServerIdentity;
import be.celerex.polymr.model.AiModel;
import be.celerex.polymr.model.Assistant;
import be.celerex.polymr.model.AssistantRule;
import be.celerex.polymr.model.AssistantSkill;
import be.celerex.polymr.model.McpServer;
import be.celerex.polymr.model.McpServerTool;
import be.celerex.polymr.model.Rule;
import be.celerex.polymr.model.RuleScope;
import be.celerex.polymr.model.Script;
import be.celerex.polymr.model.Session;
import be.celerex.polymr.model.SessionEvent;
import be.celerex.polymr.model.SessionEventType;
import be.celerex.polymr.model.SessionVisibility;
import be.celerex.polymr.model.Skill;
import be.celerex.polymr.model.Tenant;
import be.celerex.polymr.model.WorkflowDefinition;
import be.celerex.polymr.model.WorkflowRun;
import be.celerex.polymr.model.WorkflowRunStatus;
import be.celerex.polymr.model.User;
import be.celerex.polymr.model.WorkspaceAssetBundle;
import be.celerex.polymr.model.WorkspaceAssetBundleEntry;
import be.celerex.polymr.modelregistry.ModelConfigService;
import be.celerex.polymr.session.dto.SessionEventResponse;
import be.celerex.polymr.session.dto.SessionPromptResponse;
import be.celerex.polymr.session.attachment.AttachmentHandlerRegistry;
import be.celerex.polymr.session.attachment.AttachmentPayload;
import be.celerex.polymr.session.attachment.AttachmentHandlerResult;
import be.celerex.polymr.storage.PublicBlobLink;
import be.celerex.polymr.storage.PublicWorkspaceBlobStore;
import be.celerex.polymr.storage.WorkspaceBlobStore;
import be.celerex.polymr.storage.StoredBlob;
import be.celerex.polymr.storage.AttachmentLinkService;
import be.celerex.polymr.workflow.WorkflowCheckpointService;
import be.celerex.polymr.workflow.runtime.ConversationGraphState;
import be.celerex.polymr.workflow.runtime.PendingState;
import be.celerex.polymr.workflow.runtime.WorkflowGraphCheckpointSaver;
import be.celerex.polymr.llm.LlmCallRegistry;
import be.celerex.polymr.ws.WorkspaceSocketEvent;
import be.celerex.polymr.ws.WorkspaceSocketManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.NullNode;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.AudioContent;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.PdfFileContent;
import dev.langchain4j.data.message.VideoContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.audio.Audio;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.pdf.PdfFile;
import dev.langchain4j.data.video.Video;
import java.util.Optional;
import java.util.Objects;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.request.ChatRequest;
import be.celerex.polymr.modelregistry.provider.AiChatModelDefinition;
import be.celerex.polymr.modelregistry.provider.AiChatModelProvider;
import be.celerex.polymr.modelregistry.provider.AiChatModelProviderRegistry;
import be.celerex.polymr.mcp.client.JsonRpcException;
import be.celerex.polymr.modelregistry.provider.AiModelResponseCostEstimator;
import be.celerex.polymr.modelregistry.provider.AiModelResponseMetadataExtractor;
import be.celerex.polymr.modelregistry.provider.AiModelResponseUsageExtractor;
import be.celerex.polymr.modelregistry.telemetry.PriceResult;
import be.celerex.polymr.modelregistry.telemetry.ResponseUsage;
import be.celerex.polymr.mcp.McpToolSpecificationService;
import be.celerex.polymr.mcp.ScopePermissionEvaluator;
import be.celerex.polymr.mcp.McpCallRegistry;
import be.celerex.polymr.mcp.WorkspaceMcpRegistry;
import be.celerex.polymr.mcp.McpPolicyResolverService;
import be.celerex.polymr.mcp.WorkflowMcpSnapshotService;
import be.celerex.polymr.pages.SfcPageCatalogService;
import be.celerex.polymr.prompt.PromptTemplateService;
import be.celerex.polymr.model.PromptTemplateSection;
import be.celerex.polymr.assistant.AssistantSlug;
import be.celerex.polymr.workflow.WorkflowRunDispatcher;
import be.celerex.polymr.lock.LockService;
import org.eclipse.microprofile.context.ManagedExecutor;
import io.smallrye.context.api.ManagedExecutorConfig;
import org.eclipse.microprofile.context.ThreadContext;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import jakarta.transaction.Transactional;
import jakarta.transaction.TransactionSynchronizationRegistry;
import jakarta.transaction.Synchronization;
import jakarta.transaction.Status;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.lang.reflect.Method;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphDefinition;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncCommandAction;
import org.bsc.langgraph4j.action.AsyncNodeActionWithConfig;
import org.bsc.langgraph4j.action.Command;
import org.bsc.langgraph4j.state.AgentState;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

@ApplicationScoped
public class SessionChatService {
	private static final org.jboss.logging.Logger LOGGER = org.jboss.logging.Logger.getLogger(SessionChatService.class);
	private static final String UI_BLOB_PREFIX = "ui://polymr-blob/";
	private static final Set<String> HTML_IGNORED_SCHEMES = Set.of("data", "blob", "mailto", "tel", "javascript", "about");
	private static final String SKILL_ACTIVATE_TOOL = "skill_activate";
	private static final String SKILL_DEACTIVATE_TOOL = "skill_deactivate";
	private static final String SPAWN_CHILDREN_TOOL = "spawn_workers";
	private static final String COMPLETE_GOAL_TOOL = "complete_goal";
	private static final String FAIL_GOAL_TOOL = "fail_goal";
	private static final String CREATE_OR_UPDATE_CANVAS_TOOL = "create_or_update_canvas";
	private static final String REFRESH_CANVAS_TOOL = "refresh_canvas";
	private static final String SESSION_PRUNE_LOCK_SCOPE = "session-prune";
	private static final Set<String> autoApprovedToolNames = Set.of(CREATE_OR_UPDATE_CANVAS_TOOL, REFRESH_CANVAS_TOOL);
	private static final String STATE_CHILDREN_KEY = "children";
	private static final String WORKER_FLAG_KEY = "worker";
	private static final String WORKER_ALLOWED_TOOLS_KEY = "worker_allowed_tools";
	private static final String WORKER_COMPLETION_KEY = "worker_completion";
	private static final String WORKER_FEEDBACK_KEY = "worker_feedback";
	private static final String WORKER_TURN_COUNT_KEY = "worker_turn_count";
	private static final String WORKER_REQUESTED_TOOLS_MODE_LEADING = "leading";
	private static final String INTERNAL_ACTIVE_SKILLS = "active_skills";
	private final Map<UUID, Boolean> aborting = new ConcurrentHashMap<>();
	@Inject
	EntityManager entityManager;

	@Inject
	SessionProcessingGate processingGate;

	@Inject
	Instance<SessionChatService> self;

	@Inject
	ObjectMapper objectMapper;

	@Inject
	SessionEventService eventService;

	@Inject
	SessionParticipantService participantService;

	@Inject
	SessionActivityService sessionActivityService;

	@Inject
	WorkflowCheckpointService checkpointService;

	@Inject
	be.celerex.polymr.workflow.WorkflowDefinitionService workflowDefinitionService;

	@Inject
	WorkspaceSocketManager socketManager;

	@Inject
	McpToolSpecificationService toolSpecificationService;

	@ConfigProperty(name = "polymr.worker.requested-tools-mode", defaultValue = "leading")
	String workerRequestedToolsMode;

	@Inject
	WorkspaceMcpRegistry mcpRegistry;

	@Inject
	ScopePermissionEvaluator scopePermissionEvaluator;

	@Inject
	McpPolicyResolverService policyResolverService;

	@Inject
	SessionCostService sessionCostService;

	@Inject
	LockService lockService;

	@Inject
	WorkflowMcpSnapshotService snapshotService;

	@Inject
	SfcPageCatalogService catalogService;

	@Inject
	be.celerex.polymr.scripts.ScriptCatalogService scriptCatalogService;

	@Inject
	PromptTemplateService promptTemplateService;

	@Inject
	be.celerex.polymr.mcp.VirtualMcpService virtualMcpService;

	@Inject
	WorkflowGraphCheckpointSaver graphCheckpointSaver;

	@Inject
	WorkflowRunDispatcher runDispatcher;

	@Inject
	@ManagedExecutorConfig(cleared = ThreadContext.TRANSACTION) // Avoid propagating a completed transaction to
	// post-commit async dispatch.
	ManagedExecutor dispatchExecutor;

	@Inject
	LlmCallRegistry callRegistry;

	@Inject
	McpCallRegistry mcpCallRegistry;

	@Inject
	AttachmentHandlerRegistry attachmentHandlerRegistry;

	@Inject
	WorkspaceBlobStore blobStore;

	@Inject
	AttachmentLinkService attachmentLinkService;

	@Inject
	AiChatModelProviderRegistry providerRegistry;

	@Inject
	ModelConfigService modelConfigService;

	@Inject
	SessionPruningService pruningService;

	@Inject
	ServerIdentity serverIdentity;

	@Inject
	jakarta.enterprise.inject.Instance<Tracer> tracer;

	@Inject
	TransactionSynchronizationRegistry txRegistry;

	@ConfigProperty(name = "polymr.tools.log-denied", defaultValue = "true")
	boolean logDeniedTools;

	@ConfigProperty(name = "polymr.tools.result-mode", defaultValue = "smart")
	String toolResultMode;

	@ConfigProperty(name = "polymr.tools.limit-decisions-to-conversation", defaultValue = "false")
	boolean limitToolDecisionsToConversation;

	@ConfigProperty(name = "polymr.sessions.include-tag-change-in-llm-history", defaultValue = "false")
	boolean includeTagChangeInLlmHistory;

	@ConfigProperty(name = "polymr.sessions.include-system-events-in-llm-history", defaultValue = "false")
	boolean includeSystemEventsInLlmHistory;

	@ConfigProperty(name = "polymr.sessions.include-unknown-tool-failures-in-llm-history", defaultValue = "false")
	boolean includeUnknownToolFailuresInLlmHistory;

	@ConfigProperty(name = "polymr.llm.system-info", defaultValue = "true")
	boolean includeSystemInfo;

	@ConfigProperty(name = "polymr.workflow.max-iterations", defaultValue = "200")
	int workflowMaxIterations;

	@ConfigProperty(name = "polymr.sessions.ui-rewrite-enabled", defaultValue = "true")
	boolean uiRewriteEnabled;

	@ConfigProperty(name = "polymr.tools.preview-failure-mode", defaultValue = "tool_failure")
	String previewFailureMode;

	@ConfigProperty(name = "polymr.tools.hard-error-turn", defaultValue = "llm")
	String hardErrorTurn;

	private final Map<UUID, LocationInfo> pendingLocations = new ConcurrentHashMap<>();
	private final Map<UUID, CompiledGraph<ConversationGraphState>> graphCache = new ConcurrentHashMap<>();

	@Transactional
	public void handleChatSend(Session session, UUID userId, JsonNode payload) {
		LOGGER.infof("Chat send session=%s user=%s", session == null ? null : session.id, userId);
		Span span = resolveTracer().spanBuilder("ws.chat.send").startSpan();
		try (Scope scope = span.makeCurrent()) {
			span.setAttribute("session.id", session.id.toString());
			span.setAttribute("user.id", userId.toString());
			LocationInfo location = null;
			if (payload instanceof ObjectNode payloadObject) {
				location = extractLocation(payloadObject);
				persistLocation(payloadObject, location);
			}
			if (location != null) {
				pendingLocations.put(session.id, location);
			}
			else {
				pendingLocations.remove(session.id);
			}
			WorkflowRun run = checkpointService.requireRun(session);
			WorkflowDefinition definition = run.workflowDefinition == null
				? null
				: workflowDefinitionService.loadDefinition(run.workflowDefinition.id);
			JsonNode definitionJson = resolveDefinitionJson(run, definition);
			if (definitionJson == null) {
				SessionEvent errorEvent = eventService.createEvent(
					session,
					SessionEventType.SYSTEM,
					objectMapper.createObjectNode().put("text", "Workflow definition is missing or invalid.")
				);
				broadcastEvent(session, errorEvent);
				updateStatus(session.id, be.celerex.polymr.model.SessionStatus.PAUSED);
				return;
			}
			enqueueRun(session, run, definition, definitionJson, userId, payload);
		}
		finally {
			span.end();
		}
	}

	void enqueueRun(
			Session session,
			WorkflowRun run,
			WorkflowDefinition definition,
			JsonNode definitionJson,
			UUID userId,
			JsonNode payload) {
		CompiledGraph<ConversationGraphState> graph = compiledGraph(run, definition, definitionJson);
		RunnableConfig config = buildRunnableConfig(run.id);
		Map<String, Object> updates = new HashMap<>();
		JsonNode effectivePayload = payload;
		String startNodeOverride = null;
		if (payload instanceof ObjectNode payloadObject) {
			if (payloadObject.hasNonNull("start_node")) {
				startNodeOverride = payloadObject.path("start_node").asText(null);
				payloadObject.remove("start_node");
			}
			effectivePayload = payloadObject.isEmpty() ? null : payloadObject;
		}
		if (effectivePayload != null && !effectivePayload.isNull()) {
			updates.put(ConversationGraphState.PAYLOAD, effectivePayload);
		}
		if (session.id != null) {
			updates.put(ConversationGraphState.SESSION_ID, session.id.toString());
		}
		if (userId != null) {
			updates.put(ConversationGraphState.USER_ID, userId.toString());
		}
		ObjectNode snapshot = resolveSnapshotFromRun(run, definitionJson, session);
		if (snapshot != null) {
			updates.put(ConversationGraphState.MCP_SNAPSHOT, snapshot);
		}
		String logicalStart = logicalStart(definitionJson);
		if (logicalStart != null && !logicalStart.isBlank()) {
			ObjectNode existing = run.checkpointJson instanceof ObjectNode node ? node : objectMapper.createObjectNode();
			if (!existing.has(ConversationGraphState.LOGICAL_NODE_ID)) {
				updates.put(ConversationGraphState.LOGICAL_NODE_ID, logicalStart);
				updates.put(ConversationGraphState.LOGICAL_NODE_INSTANCE_ID, UUID.randomUUID().toString());
			}
		}
		if (isRecovery(run)) {
			ObjectNode noticePayload = objectMapper.createObjectNode();
			noticePayload.put("text", "Workflow resumed after restart. Please continue.");
			PendingState.Builder pending = PendingState.builder(objectMapper);
			pending.events(List.of(pendingEvent(SessionEventType.SYSTEM, noticePayload)));
			pending.apply(updates);
		}
		String nodeId = resolveStartNode(definitionJson, startNodeOverride, run.currentNode);
		if (nodeId == null) {
			LOGGER.warnf("No start node available for run %s; skipping enqueue", run.id);
			return;
		}
		ObjectNode state = run.checkpointJson instanceof ObjectNode node ? node.deepCopy() : objectMapper.createObjectNode();
		ObjectNode updateNode = objectMapper.convertValue(updates, ObjectNode.class);
		state.setAll(updateNode);
		state.put("next", nodeId);
		state.put("updated_at", java.time.Instant.now().toString());
		checkpointService.saveCheckpoint(run, nodeId, state);
		run.status = WorkflowRunStatus.QUEUED;
		run.runtimeServerId = null;
		if (session != null) {
			session.status = be.celerex.polymr.model.SessionStatus.ACTIVE;
			session.locked = true;
			participantService.broadcastSessionState(session, "session.status", Map.of("id", session.id, "status", session.status, "locked", true));
		}
		aborting.put(session.id, false);
		scheduleDispatchAfterCommit();
	}

	private void scheduleDispatchAfterCommit() {
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
					dispatchExecutor.runAsync(
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

	private String resolveStartNode(JsonNode definitionJson, String override, String currentNode) {
		if (override != null && !override.isBlank()) {
			return override.trim();
		}
		if (currentNode != null && !currentNode.isBlank()) {
			return currentNode;
		}
		if (definitionJson == null) {
			return null;
		}
		ObjectNode enriched = workflowDefinitionService.enrichDefinition(definitionJson);
		String start = enriched.path("start").asText(null);
		if (start == null || start.isBlank()) {
			start = enriched.path("start_node").asText(null);
		}
		return start == null || start.isBlank() ? null : start.trim();
	}

	private ObjectNode resolveSnapshotFromRun(WorkflowRun run, JsonNode definitionJson, Session session) {
		if (run != null
				&& run.checkpointJson != null
				&& run.checkpointJson.has(ConversationGraphState.MCP_SNAPSHOT)) {
			JsonNode node = run.checkpointJson.get(ConversationGraphState.MCP_SNAPSHOT);
			if (node instanceof ObjectNode objectNode) {
				return objectNode.deepCopy();
			}
			if (node != null && node.isObject()) {
				return objectMapper.convertValue(node, ObjectNode.class);
			}
		}
		if (definitionJson != null && session != null) {
			return snapshotService.buildSnapshot(definitionJson, session);
		}
		return objectMapper.createObjectNode();
	}

	@ActivateRequestContext
	public void runLeased(UUID runId) {
		if (runId == null) {
			return;
		}
		if (!markRunning(runId)) {
			LOGGER.warnf("Failed to mark leased run as running %s", runId);
			recoverLeasedRun(runId, new IllegalStateException("Failed to mark run as running"));
			return;
		}
		WorkflowRun run = entityManager.find(WorkflowRun.class, runId);
		if (run == null) {
			LOGGER.warnf("Leased run not found %s", runId);
			return;
		}
		WorkflowDefinition definition = run.workflowDefinition == null
			? null
			: workflowDefinitionService.loadDefinition(run.workflowDefinition.id);
		if (!workflowDefinitionService.isConversationDefinition(definition)) {
			LOGGER.warnf("Leased run %s is not a conversation workflow", runId);
			return;
		}
		JsonNode definitionJson = resolveDefinitionJson(run, definition);
		if (definitionJson == null) {
			LOGGER.warnf("Leased run %s has no workflow definition", runId);
			return;
		}
		CompiledGraph<ConversationGraphState> graph = compiledGraph(run, definition, definitionJson);
		RunnableConfig config = buildRunnableConfig(run.id);
		if (isRecovery(run)) {
			RunnableConfig recoveredConfig = applyRecovery(graph, config, run, new HashMap<>());
			if (recoveredConfig != null) {
				if (runGraphWithCheckpointRepair(run, graph, recoveredConfig)) {
					resumeToolChainIfNeeded(run.session, run, graph, config);
				}
				return;
			}
		}
		if (runGraphWithCheckpointRepair(run, graph, config)) {
			resumeToolChainIfNeeded(run.session, run, graph, config);
		}
	}

	private boolean runGraphWithCheckpointRepair(
			WorkflowRun run,
			CompiledGraph<ConversationGraphState> graph,
			RunnableConfig config) {
		try {
			LOGGER.debugf("Run graph start run=%s", run == null ? null : run.id);
			graph.stream(GraphInput.resume(), config)
				.stream()
				.forEach(output -> {});
			LOGGER.debugf("Run graph complete run=%s", run == null ? null : run.id);
			return true;
		}
		catch (IllegalStateException ex) {
			if (ex.getMessage() != null && ex.getMessage().contains("Maximum number of iterations")) {
				LOGGER.warnf(ex, "Maximum number of iterations reached");
				handleIterationLimit(run, ex.getMessage());
				return false;
			}
			if (ex.getMessage() != null && ex.getMessage().contains("Resume request without a valid checkpoint")) {
				if (ensureCheckpoint(run.id)) {
					try {
						graph.stream(GraphInput.resume(), config)
							.stream()
							.forEach(output -> {});
						return true;
					}
					catch (IllegalStateException retryEx) {
						LOGGER.warnf(retryEx, "Failed to resume workflow run %s after checkpoint repair", run.id);
					}
				}
				handleMissingCheckpoint(run.id);
				return false;
			}
			throw ex;
		}
		catch (java.util.concurrent.CompletionException ex) {
			Throwable cause = ex.getCause();
			if (cause instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			LOGGER.warnf(ex, "Run graph failed run=%s", run == null ? null : run.id);
			throw ex;
		}
	}

	private RunnableConfig buildRunnableConfig(UUID runId) {
		RunnableConfig.Builder builder = RunnableConfig.builder().threadId(runId == null ? null : runId.toString());
		if (workflowMaxIterations > 0) {
			applyMaxIterations(builder, workflowMaxIterations);
		}
		return builder.build();
	}

	private void applyMaxIterations(RunnableConfig.Builder builder, int value) {
		try {
			Method method = builder.getClass().getMethod("recursionLimit", int.class);
			method.invoke(builder, value);
			return;
		}
		catch (NoSuchMethodException ignored) {}
		catch (Exception ex) {
			LOGGER.debugf(ex, "Failed to apply recursionLimit");
			return;
		}
		try {
			Method method = builder.getClass().getMethod("maxIterations", int.class);
			method.invoke(builder, value);
		}
		catch (NoSuchMethodException ignored) {}
		catch (Exception ex) {
			LOGGER.debugf(ex, "Failed to apply maxIterations");
		}
	}

	private void applyMaxIterations(CompileConfig.Builder builder, int value) {
		if (builder == null) {
			return;
		}
		try {
			Method method = builder.getClass().getMethod("recursionLimit", int.class);
			method.invoke(builder, value);
			return;
		}
		catch (NoSuchMethodException ignored) {}
		catch (Exception ex) {
			LOGGER.debugf(ex, "Failed to apply recursionLimit on compile builder");
			return;
		}
		try {
			Method method = builder.getClass().getMethod("maxIterations", int.class);
			method.invoke(builder, value);
		}
		catch (NoSuchMethodException ignored) {}
		catch (Exception ex) {
			LOGGER.debugf(ex, "Failed to apply maxIterations on compile builder");
		}
	}

	private void handleIterationLimit(WorkflowRun run, String message) {
		if (run == null) {
			return;
		}
		Session session = run.session;
		if (session != null) {
			SessionEvent event = eventService.createEvent(session, SessionEventType.SYSTEM, objectMapper.createObjectNode().put("text", message));
			broadcastEvent(session, event);
			session.status = be.celerex.polymr.model.SessionStatus.PAUSED;
			session.locked = false;
		}
		run.status = WorkflowRunStatus.PAUSED;
		run.runtimeServerId = null;
		checkpointService.updateProjectionFromCheckpoint(
			run,
			checkpoint -> {
				checkpoint.put(ConversationGraphState.ROUTE, "pause");
				checkpoint.put(ConversationGraphState.RUNTIME_NODE_TYPE, "user");
				checkpoint.put(ConversationGraphState.LOGICAL_NODE_ID, "user_input");
				checkpoint.put(ConversationGraphState.LOGICAL_NODE_INSTANCE_ID, UUID.randomUUID().toString());
				checkpoint.remove(ConversationGraphState.PAYLOAD);
				checkpoint.put("next", "user_input");
				checkpoint.put("updated_at", java.time.Instant.now().toString());
				return checkpoint;
			}
		);
	}

	@Transactional
	boolean markRunning(UUID runId) {
		if (runId == null) {
			return false;
		}
		int updated = entityManager.createQuery(
				"update WorkflowRun r set r.status = :running "
					+ "where r.id = :id and r.status = :leased and r.runtimeServerId = :serverId"
			)
			.setParameter("running", WorkflowRunStatus.RUNNING)
			.setParameter("leased", WorkflowRunStatus.LEASED)
			.setParameter("serverId", serverIdentity.id())
			.setParameter("id", runId)
			.executeUpdate();
		if (updated != 1) {
			LOGGER.warnf("markRunning failed for run=%s server=%s", runId, serverIdentity.id());
		}
		return updated == 1;
	}

	@Transactional
	public void recoverLeasedRun(UUID runId, Exception error) {
		if (runId == null) {
			return;
		}
		WorkflowRun run = entityManager.find(WorkflowRun.class, runId);
		if (run == null) {
			return;
		}
		run.status = WorkflowRunStatus.PAUSED;
		run.runtimeServerId = null;
		if (run.session != null) {
			run.session.locked = false;
			run.session.status = be.celerex.polymr.model.SessionStatus.PAUSED;
			participantService.broadcastSessionState(
				run.session,
				"session.status",
				Map.of("id", run.session.id, "status", run.session.status, "locked", false)
			);
		}
		LOGGER.warnf(error, "Recovered leased run after failure run=%s", runId);
	}

	@Transactional
	boolean ensureCheckpoint(UUID runId) {
		if (runId == null) {
			return false;
		}
		WorkflowRun run = entityManager.find(WorkflowRun.class, runId);
		if (run == null) {
			return false;
		}
		Long count = entityManager.createQuery("select count(c) from WorkflowRunCheckpoint c where c.workflowRun.id = :runId", Long.class)
			.setParameter("runId", runId)
			.getSingleResult();
		if (count != null && count > 0) {
			return false;
		}
		ObjectNode state = run.checkpointJson instanceof ObjectNode node ? node : objectMapper.createObjectNode();
		String nodeId = state.path("next").asText(null);
		if (nodeId == null || nodeId.isBlank()) {
			nodeId = "user_input";
			state.put("next", nodeId);
		}
		checkpointService.saveCheckpoint(run, nodeId, state);
		return true;
	}

	@Transactional
	void handleMissingCheckpoint(UUID runId) {
		if (runId == null) {
			return;
		}
		WorkflowRun run = entityManager.find(WorkflowRun.class, runId);
		if (run == null) {
			return;
		}
		run.status = WorkflowRunStatus.PAUSED;
		run.runtimeServerId = null;
		if (run.session != null) {
			run.session.locked = false;
			run.session.status = be.celerex.polymr.model.SessionStatus.PAUSED;
			ObjectNode payload = objectMapper.createObjectNode();
			payload.put("text", "Workflow checkpoint was missing. Please resend your last message.");
			eventService.createEvent(run.session, SessionEventType.SYSTEM, payload);
		}
	}

	private void resumeToolChainIfNeeded(
			Session session,
			WorkflowRun run,
			CompiledGraph<ConversationGraphState> graph,
			RunnableConfig config) {
		if (session == null || run == null) {
			return;
		}
		for (int i = 0; i < 5; i++) {
			WorkflowRun refreshed = entityManager.find(WorkflowRun.class, run.id);
			JsonNode checkpoint = refreshed == null ? null : refreshed.checkpointJson;
			if (checkpoint == null || checkpoint.isNull()) {
				return;
			}
			JsonNode pending = PendingState.pendingFromCheckpoint(checkpoint, objectMapper);
			boolean approvalPending = PendingState.readBoolean(pending, PendingState.TOOL_APPROVAL, PendingState.TOOL_APPROVAL_PENDING);
			if (approvalPending) {
				return;
			}
			JsonNode pendingRequests = PendingState.read(pending, PendingState.TOOL_REQUESTS);
			if (pendingRequests == null
					|| pendingRequests.isNull()
					|| !pendingRequests.isArray()
					|| pendingRequests.isEmpty()) {
				return;
			}
			graph.stream(GraphInput.resume(), config)
				.stream()
				.forEach(output -> {});
		}
	}

	@Transactional
	public void handleAbort(Session session, UUID userId) {
		Span span = resolveTracer().spanBuilder("session.abort").startSpan();
		try (Scope scope = span.makeCurrent()) {
			span.setAttribute("session.id", session.id.toString());
			span.setAttribute("user.id", userId.toString());
			boolean canceled = callRegistry.cancel(session.id);
			boolean mcpCanceled = mcpCallRegistry.cancel(session.id);
			canceled = canceled || mcpCanceled;
			SessionProcessingGate.Gate gate = processingGate.tryAcquire(session.id);
			if (gate == null) {
				aborting.put(session.id, true);
				participantService.broadcastSessionState(session, "session.abort", Map.of("aborting", true));
				if (!canceled) {
					aborting.put(session.id, false);
					Map<String, Object> errorPayload = new HashMap<>();
					errorPayload.put("message", "Abort requested but no active call was found.");
					errorPayload.put("session_id", session.id.toString());
					socketManager.broadcastToSession(
						session.id,
						new WorkspaceSocketEvent("session.error", session.workspace.id, session.id, errorPayload)
					);
				}
				return;
			}
			try (gate) {
				WorkflowRun run = checkpointService.requireRun(session);
				ObjectNode state = run.checkpointJson instanceof ObjectNode node ? node.deepCopy() : objectMapper.createObjectNode();
				state.put("next", "user_input");
				state.put("updated_at", java.time.Instant.now().toString());
				state.set(ConversationGraphState.PAYLOAD, NullNode.instance);
				state.put(ConversationGraphState.STATUS, "normal");
				state.put(ConversationGraphState.ROUTE, "default");
				clearPendingToolState(state);
				checkpointService.saveCheckpoint(run, "user_input", state);
				run.status = WorkflowRunStatus.QUEUED;
				run.runtimeServerId = null;
				session.locked = false;
				session.status = be.celerex.polymr.model.SessionStatus.PAUSED;
				participantService.broadcastSessionState(session, "session.status", Map.of("id", session.id, "status", session.status, "locked", false));
				runDispatcher.notifyWorkAvailable();
			}
		}
		finally {
			span.end();
		}
	}

	public void resumeRecovery(WorkflowRun run) {
		if (run == null || run.session == null || run.workflowDefinition == null) {
			return;
		}
		run.status = WorkflowRunStatus.QUEUED;
		run.runtimeServerId = null;
		if (run.session != null) {
			run.session.locked = false;
		}
		scheduleDispatchAfterCommit();
	}

	private boolean isRecovery(WorkflowRun run) {
		JsonNode checkpoint = run.checkpointJson;
		return checkpoint != null && "recovery".equals(checkpoint.path("status").asText());
	}

	private CompiledGraph<ConversationGraphState> compiledGraph(
			WorkflowRun run,
			WorkflowDefinition definition,
			JsonNode definitionJson) {
		UUID cacheKey = run != null && run.workflowDefinitionVersion != null
			? run.workflowDefinitionVersion.id
			: (definition == null ? null : definition.id);
		if (cacheKey == null) {
			return buildGraph(definitionJson, "workflow");
		}
		return graphCache.computeIfAbsent(cacheKey, id -> buildGraph(definitionJson, id.toString()));
	}

	private CompiledGraph<ConversationGraphState> buildGraph(JsonNode rawDefinition, String graphId) {
		ObjectNode definitionJson = workflowDefinitionService.enrichDefinition(rawDefinition);
		String start = requireText(definitionJson, "start");
		ObjectNode nodesJson = requireObjectNode(definitionJson.path("nodes"), "nodes");
		ObjectNode edgesJson = requireObjectNode(definitionJson.path("edges"), "edges");
		ObjectNode recoveryJson = definitionJson.path("recovery") instanceof ObjectNode recoveryNode
			? recoveryNode
			: objectMapper.createObjectNode();
		Map<String, String> recoveryMap = new HashMap<>();
		recoveryJson.fields()
			.forEachRemaining(entry -> recoveryMap.put(entry.getKey(), entry.getValue().asText()));

		StateGraph<ConversationGraphState> graph = new StateGraph<>(ConversationGraphState.SCHEMA, ConversationGraphState::new);
		nodesJson.fields()
			.forEachRemaining(
				entry -> {
					String nodeId = entry.getKey();
					JsonNode node = entry.getValue();
					String type = node.path("type").asText(null);
					if (type == null || type.isBlank()) {
						throw new IllegalStateException("Workflow node type missing for " + nodeId);
					}
					try {
						graph.addNode(nodeId, nodeAction(nodeId, type));
					}
					catch (Exception exception) {
						throw new IllegalStateException("Failed to add workflow node " + nodeId, exception);
					}
				}
			);
		try {
			graph.addEdge(GraphDefinition.START, start);
		}
		catch (Exception exception) {
			throw new IllegalStateException("Failed to add workflow start edge", exception);
		}
		edgesJson.fields()
			.forEachRemaining(
				entry -> {
					String nodeId = entry.getKey();
					JsonNode edge = entry.getValue();
					String recoveryTarget = recoveryMap.get(nodeId);
					if (edge.isObject()) {
						Map<String, String> mappings = new HashMap<>();
						edge.fields()
							.forEachRemaining(
								mapping -> {
									JsonNode targets = mapping.getValue();
									if (targets.isArray() && targets.size() > 0) {
										mappings.put(mapping.getKey(), targets.get(0).asText());
									}
								}
							);
						if (recoveryTarget != null) {
							mappings.put("recovery", recoveryTarget);
						}
						try {
							graph.addConditionalEdges(nodeId, edgeAction(nodeId, mappings, recoveryTarget), mappings);
						}
						catch (Exception exception) {
							throw new IllegalStateException("Failed to add workflow edges for node " + nodeId, exception);
						}
						return;
					}
					if (!edge.isArray()) {
						throw new IllegalStateException("Invalid edge definition for node " + nodeId);
					}
					List<String> targets = new ArrayList<>();
					edge.forEach(target -> targets.add(target.asText()));
					if (targets.isEmpty()) {
						throw new IllegalStateException("Edge definition for node " + nodeId + " has no targets");
					}
					if (recoveryTarget != null) {
						Map<String, String> mappings = new HashMap<>();
						mappings.put("default", targets.get(0));
						mappings.put("recovery", recoveryTarget);
						try {
							graph.addConditionalEdges(nodeId, edgeAction(nodeId, mappings, recoveryTarget), mappings);
						}
						catch (Exception exception) {
							throw new IllegalStateException("Failed to add workflow edges for node " + nodeId, exception);
						}
						return;
					}
					for (String target : targets) {
						try {
							graph.addEdge(nodeId, target);
						}
						catch (Exception exception) {
							throw new IllegalStateException("Failed to add workflow edge for node " + nodeId, exception);
						}
					}
				}
			);
		CompileConfig.Builder compileBuilder = CompileConfig.builder()
			.checkpointSaver(graphCheckpointSaver)
			.graphId(graphId);
		if (workflowMaxIterations > 0) {
			applyMaxIterations(compileBuilder, workflowMaxIterations);
		}
		CompileConfig config = compileBuilder.build();
		try {
			return graph.compile(config);
		}
		catch (Exception exception) {
			throw new IllegalStateException("Failed to compile workflow graph", exception);
		}
	}

	private String logicalStart(JsonNode definitionJson) {
		if (definitionJson == null) {
			return null;
		}
		ObjectNode enriched = workflowDefinitionService.enrichDefinition(definitionJson);
		if (enriched.has("logical_start")) {
			JsonNode node = enriched.get("logical_start");
			if (node != null && node.isTextual()) {
				return node.asText();
			}
		}
		return null;
	}

	private void updateLogicalNodeOnCompletion(
			JsonNode definitionJson,
			ConversationGraphState state,
			Map<String, Object> updates) {
		if (definitionJson == null || state == null || updates == null) {
			return;
		}
		ObjectNode enriched = workflowDefinitionService.enrichDefinition(definitionJson);
		ObjectNode logicalNodes = enriched.get("logical_nodes") instanceof ObjectNode node ? node : null;
		ObjectNode logicalEdges = enriched.get("logical_edges") instanceof ObjectNode node ? node : null;
		if (logicalNodes == null) {
			return;
		}
		String logicalId = state.value(ConversationGraphState.LOGICAL_NODE_ID).map(Object::toString).orElse(null);
		if (logicalId == null || logicalId.isBlank()) {
			return;
		}
		JsonNode logicalNode = logicalNodes.get(logicalId);
		if (logicalNode == null || !logicalNode.isObject()) {
			return;
		}
		String type = logicalNode.path("type").asText(null);
		if (isWorkflowEndNode(enriched, logicalId)) {
			markWorkflowCompleted(updates);
			return;
		}
		if (isWorkflowReturnNode(enriched, logicalId)) {
			String target = popControlMarker(state, updates);
			if (target == null || target.isBlank()) {
				markWorkflowCompleted(updates);
				return;
			}
			updates.put(ConversationGraphState.LOGICAL_NODE_ID, target);
			updates.put(ConversationGraphState.LOGICAL_NODE_INSTANCE_ID, UUID.randomUUID().toString());
			return;
		}
		if ("for_each".equals(type)) {
			String next = advanceForEach(enriched, logicalId, (ObjectNode) logicalNode, state, updates);
			if (next != null) {
				updates.put(ConversationGraphState.LOGICAL_NODE_ID, next);
				updates.put(ConversationGraphState.LOGICAL_NODE_INSTANCE_ID, UUID.randomUUID().toString());
			}
			return;
		}
		if ("router".equals(type)) {
			String next = advanceRouter(enriched, logicalId, (ObjectNode) logicalNode, state, updates);
			if (next != null) {
				updates.put(ConversationGraphState.LOGICAL_NODE_ID, next);
				updates.put(ConversationGraphState.LOGICAL_NODE_INSTANCE_ID, UUID.randomUUID().toString());
			}
			return;
		}
		if (!"ai".equals(type) && !"user".equals(type) && !"script".equals(type) && !"step".equals(type)) {
			return;
		}
		String next = resolveNextLogicalNode(logicalEdges, logicalId, state, "default");
		if (next == null || next.isBlank()) {
			if (hasControlMarker(state)) {
				next = popControlMarker(state, updates);
			}
			else {
				markWorkflowCompleted(updates);
				return;
			}
		}
		if (next == null || next.isBlank() || next.equals(logicalId)) {
			return;
		}
		updates.put(ConversationGraphState.LOGICAL_NODE_ID, next);
		updates.put(ConversationGraphState.LOGICAL_NODE_INSTANCE_ID, UUID.randomUUID().toString());
	}

	private String resolveNextLogicalNode(
			ObjectNode logicalEdges,
			String nodeId,
			ConversationGraphState state,
			String requestedRoute) {
		if (logicalEdges == null || nodeId == null || nodeId.isBlank()) {
			return null;
		}
		JsonNode edge = logicalEdges.get(nodeId);
		if (edge == null) {
			return null;
		}
		if (edge.isArray()) {
			String resolved = resolveConditionalTargets(edge, state, requestedRoute);
			if (resolved != null) {
				return resolved;
			}
		}
		if (edge.isObject()) {
			JsonNode conditions = edge.get("conditions");
			String resolved = resolveConditionalTargets(conditions, state, requestedRoute);
			if (resolved != null) {
				return resolved;
			}
			JsonNode routes = edge.get("routes");
			if (routes != null && routes.isObject() && requestedRoute != null && routes.has(requestedRoute)) {
				JsonNode routeTargets = routes.get(requestedRoute);
				if (routeTargets != null && routeTargets.isArray() && routeTargets.size() > 0) {
					return routeTargets.get(0).asText(null);
				}
			}
			if ("default".equals(requestedRoute) || requestedRoute == null || requestedRoute.isBlank()) {
				JsonNode targets = edge.get("default");
				if (targets != null && targets.isArray() && targets.size() > 0) {
					return targets.get(0).asText(null);
				}
			}
		}
		return null;
	}

	private String resolveConditionalTargets(JsonNode conditions, ConversationGraphState state, String requestedRoute) {
		if (conditions == null || !conditions.isArray()) {
			return null;
		}
		ObjectNode stateNode = readStateObject(state);
		for (JsonNode entry : conditions) {
			if (entry == null || entry.isNull()) {
				continue;
			}
			if (entry.isTextual()) {
				return entry.asText(null);
			}
			if (!entry.isObject()) {
				continue;
			}
			JsonNode when = entry.get("when");
			String mode = entry.path("mode").asText(null);
			String route = entry.path("route").asText("default");
			if (requestedRoute != null && !requestedRoute.isBlank() && !requestedRoute.equals(route)) {
				continue;
			}
			String target = entry.path("target").asText(null);
			if (target == null || target.isBlank()) {
				continue;
			}
			if (when == null || when.isNull()) {
				return target;
			}
			if (evaluateCondition(when, stateNode, mode)) {
				return target;
			}
		}
		return null;
	}

	private boolean isWorkflowEndNode(ObjectNode enriched, String nodeId) {
		return hasLogicalTerminalNode(enriched, "logical_end_nodes", nodeId);
	}

	private boolean isWorkflowReturnNode(ObjectNode enriched, String nodeId) {
		return hasLogicalTerminalNode(enriched, "logical_return_nodes", nodeId);
	}

	private boolean hasLogicalTerminalNode(ObjectNode enriched, String key, String nodeId) {
		if (enriched == null || key == null || nodeId == null || nodeId.isBlank()) {
			return false;
		}
		JsonNode nodeIds = enriched.get(key);
		if (nodeIds == null || !nodeIds.isArray()) {
			return false;
		}
		for (JsonNode entry : nodeIds) {
			if (entry != null && nodeId.equals(entry.asText(null))) {
				return true;
			}
		}
		return false;
	}

	private void markWorkflowCompleted(Map<String, Object> updates) {
		PendingState.Builder pending = PendingState.builder(objectMapper);
		pending.sessionStatus(be.celerex.polymr.model.SessionStatus.COMPLETED.name());
		pending.runStatus(WorkflowRunStatus.COMPLETED.name());
		pending.locked(false);
		pending.apply(updates);
	}

	private boolean hasControlMarker(ConversationGraphState state) {
		ObjectNode internal = readInternalObject(state);
		JsonNode stack = internal.path("control").path("stack");
		return stack.isArray() && stack.size() > 0;
	}

	private String popControlMarker(ConversationGraphState state, Map<String, Object> updates) {
		ObjectNode internal = readInternalObject(state);
		ArrayNode stack = internal.with("control").withArray("stack");
		if (stack.isEmpty()) {
			return null;
		}
		JsonNode frame = stack.remove(stack.size() - 1);
		updates.put(ConversationGraphState.INTERNAL, internal);
		if (frame instanceof ObjectNode objectNode) {
			clearControlNodeState(internal, objectNode.path("node_id").asText(null));
			updates.put(ConversationGraphState.INTERNAL, internal);
			return objectNode.path("node_id").asText(null);
		}
		return frame == null ? null : frame.asText(null);
	}

	private void pushControlMarker(ConversationGraphState state, Map<String, Object> updates, String nodeId) {
		pushControlMarker(state, updates, nodeId, null);
	}

	private void pushControlMarker(
			ConversationGraphState state,
			Map<String, Object> updates,
			String nodeId,
			String kind) {
		if (nodeId == null || nodeId.isBlank()) {
			return;
		}
		ObjectNode internal = readInternalObject(state);
		ObjectNode frame = objectMapper.createObjectNode();
		frame.put("node_id", nodeId);
		if (kind != null && !kind.isBlank()) {
			frame.put("kind", kind);
		}
		internal.with("control").withArray("stack").add(frame);
		updates.put(ConversationGraphState.INTERNAL, internal);
	}

	private ObjectNode controlNodeState(ObjectNode internal, String nodeId) {
		if (internal == null || nodeId == null || nodeId.isBlank()) {
			return objectMapper.createObjectNode();
		}
		ObjectNode control = internal.with("control");
		ObjectNode nodes = control.with("nodes");
		JsonNode existing = nodes.get(nodeId);
		if (existing instanceof ObjectNode objectNode) {
			return objectNode;
		}
		return nodes.putObject(nodeId);
	}

	private void clearControlNodeState(ObjectNode internal, String nodeId) {
		if (internal == null || nodeId == null || nodeId.isBlank()) {
			return;
		}
		ObjectNode nodes = internal.with("control").with("nodes");
		nodes.remove(nodeId);
	}

	private ObjectNode readInternalObject(ConversationGraphState state) {
		Object internal = state.value(ConversationGraphState.INTERNAL).orElse(null);
		if (internal instanceof ObjectNode node) {
			return node.deepCopy();
		}
		if (internal instanceof JsonNode jsonNode && jsonNode.isObject()) {
			return objectMapper.convertValue(jsonNode, ObjectNode.class);
		}
		if (internal instanceof Map<?, ?> map) {
			return objectMapper.convertValue(map, ObjectNode.class);
		}
		return objectMapper.createObjectNode();
	}

	private String advanceForEach(
			ObjectNode enriched,
			String nodeId,
			ObjectNode node,
			ConversationGraphState state,
			Map<String, Object> updates) {
		ObjectNode stateNode = readStateObject(state);
		ObjectNode internal = readInternalObject(state);
		ObjectNode controlState = controlNodeState(internal, nodeId);
		String itemsField = node.path("items_field").asText("");
		String itemField = node.path("item_field").asText("item");
		String indexField = node.path("index_field").asText("item_index");
		JsonNode items = readStatePath(stateNode, itemsField);
		ArrayNode pendingTargets = controlState.withArray("pending_targets");
		if (!pendingTargets.isEmpty()) {
			String next = pendingTargets.remove(0).asText(null);
			updates.put(ConversationGraphState.INTERNAL, internal);
			return next;
		}
		int index = controlState.path("index").asInt(0);
		if (items instanceof ArrayNode array && index > 0) {
			JsonNode currentItemValue = readStatePath(stateNode, itemField);
			if (currentItemValue != null) {
				ArrayNode nextItems = array.deepCopy();
				nextItems.set(index - 1, currentItemValue.deepCopy());
				setStatePath(stateNode, itemsField, nextItems);
				items = nextItems;
			}
		}
		if (!(items instanceof ArrayNode array) || index >= array.size()) {
			ObjectNode nextState = stateNode.deepCopy();
			clearForEachIterationState(nextState, itemField, indexField);
			updates.put(ConversationGraphState.STATE, nextState);
			clearControlNodeState(internal, nodeId);
			updates.put(ConversationGraphState.INTERNAL, internal);
			String done = resolveNextLogicalNode(enriched.get("logical_edges") instanceof ObjectNode edges ? edges : null, nodeId, state, "done");
			if (done == null && hasControlMarker(state)) {
				done = popControlMarker(state, updates);
			}
			return done;
		}
		ObjectNode nextState = stateNode.deepCopy();
		JsonNode currentItem = array.get(index);
		nextState.set(itemField, currentItem);
		nextState.put(indexField, index);
		updates.put(ConversationGraphState.STATE, nextState);
		ConversationGraphState scopedState = stateWithOverrides(state, nextState);
		List<String> loopTargets = resolveMatchingLogicalTargets(
			enriched.get("logical_edges") instanceof ObjectNode edges ? edges : null,
			nodeId,
			scopedState,
			"loop"
		);
		controlState.put("index", index + 1);
		pendingTargets.removeAll();
		if (loopTargets.size() > 1) {
			for (int i = 1; i < loopTargets.size(); i++) {
				pendingTargets.add(loopTargets.get(i));
			}
		}
		updates.put(ConversationGraphState.INTERNAL, internal);
		if (loopTargets.isEmpty()) {
			return resolveNextLogicalNode(
				enriched.get("logical_edges") instanceof ObjectNode edges ? edges : null,
				nodeId,
				scopedState,
				"done"
			);
		}
		pushControlMarker(state, updates, nodeId, "for_each");
		return loopTargets.get(0);
	}

	private String advanceRouter(
			ObjectNode enriched,
			String nodeId,
			ObjectNode node,
			ConversationGraphState state,
			Map<String, Object> updates) {
		ObjectNode internal = readInternalObject(state);
		ObjectNode controlState = controlNodeState(internal, nodeId);
		ArrayNode queue = controlState.withArray("queue");
		if (queue.isEmpty()) {
			List<String> actions = resolveMatchingLogicalTargets(enriched.get("logical_edges") instanceof ObjectNode edges ? edges : null, nodeId, state, "action");
			for (String action : actions) {
				queue.add(action);
			}
		}
		if (queue.isEmpty()) {
			clearControlNodeState(internal, nodeId);
			updates.put(ConversationGraphState.INTERNAL, internal);
			String done = resolveNextLogicalNode(enriched.get("logical_edges") instanceof ObjectNode edges ? edges : null, nodeId, state, "done");
			if (done == null && hasControlMarker(state)) {
				done = popControlMarker(state, updates);
			}
			return done;
		}
		String next = queue.remove(0).asText(null);
		updates.put(ConversationGraphState.INTERNAL, internal);
		pushControlMarker(state, updates, nodeId, "router");
		return next;
	}

	private ConversationGraphState stateWithOverrides(ConversationGraphState state, ObjectNode overriddenState) {
		if (state == null || overriddenState == null) {
			return state;
		}
		java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
		for (java.util.Map.Entry<String, Object> entry : state.data()
			.entrySet()) {
			values.put(entry.getKey(), entry.getValue());
		}
		values.put(ConversationGraphState.STATE, overriddenState);
		return new ConversationGraphState(values);
	}

	private void clearForEachIterationState(ObjectNode state, String itemField, String indexField) {
		if (state == null) {
			return;
		}
		if (itemField != null && !itemField.isBlank()) {
			removeStatePath(state, itemField);
		}
		if (indexField != null && !indexField.isBlank()) {
			removeStatePath(state, indexField);
		}
	}

	private List<String> resolveMatchingLogicalTargets(
			ObjectNode logicalEdges,
			String nodeId,
			ConversationGraphState state,
			String route) {
		List<String> targets = new ArrayList<>();
		if (logicalEdges == null || nodeId == null || nodeId.isBlank()) {
			return targets;
		}
		JsonNode edge = logicalEdges.get(nodeId);
		if (!(edge instanceof ObjectNode edgeObject)) {
			return targets;
		}
		JsonNode conditions = edgeObject.get("conditions");
		if (conditions != null && conditions.isArray()) {
			ObjectNode stateNode = readStateObject(state);
			for (JsonNode entry : conditions) {
				if (entry == null || !entry.isObject()) {
					continue;
				}
				if (!route.equals(entry.path("route").asText("default"))) {
					continue;
				}
				JsonNode when = entry.get("when");
				if (when != null
						&& !when.isNull()
						&& !evaluateCondition(when, stateNode, entry.path("mode").asText(null))) {
					continue;
				}
				String target = entry.path("target").asText(null);
				if (target != null && !target.isBlank()) {
					targets.add(target);
				}
			}
		}
		JsonNode routes = edgeObject.get("routes");
		if (routes != null && routes.isObject() && routes.has(route)) {
			JsonNode routeTargets = routes.get(route);
			if (routeTargets != null && routeTargets.isArray()) {
				for (JsonNode target : routeTargets) {
					if (target != null) {
						targets.add(target.asText(null));
					}
				}
			}
		}
		return targets;
	}

	private boolean evaluateCondition(JsonNode condition, ObjectNode state, String mode) {
		if (condition == null || !condition.isObject()) {
			return false;
		}
		JsonNode conditions = condition.get("conditions");
		if (conditions != null && conditions.isArray()) {
			boolean useAny = "or".equalsIgnoreCase(mode) || "any".equalsIgnoreCase(mode);
			boolean hasEntries = false;
			for (JsonNode entry : conditions) {
				hasEntries = true;
				boolean result = evaluateCondition(entry, state, condition.path("mode").asText(null));
				if (useAny && result) {
					return true;
				}
				if (!useAny && !result) {
					return false;
				}
			}
			return hasEntries && !useAny;
		}
		String path = condition.path("path").asText(null);
		if (path == null || path.isBlank()) {
			return false;
		}
		JsonNode value = state == null ? null : readConditionPath(state, path);
		if (condition.path("is_empty").asBoolean(false)) {
			return isEmptyArrayValue(value);
		}
		if (condition.path("is_not_empty").asBoolean(false)) {
			return !isEmptyArrayValue(value);
		}
		if (condition.path("has_value").asBoolean(false)) {
			return hasScalarValue(value);
		}
		if (condition.path("has_no_value").asBoolean(false)) {
			return !hasScalarValue(value);
		}
		if (condition.has("exists")) {
			boolean expected = condition.path("exists").asBoolean(false);
			return expected == hasScalarValue(value);
		}
		String valueType = condition.path("value_type").asText("string");
		JsonNode equals = condition.get("equals");
		if (equals != null) {
			JsonNode right = resolveRightValue(valueType, equals, state);
			return scalarEquals(value, right);
		}
		JsonNode notEquals = condition.get("not_equals");
		if (notEquals != null) {
			JsonNode right = resolveRightValue(valueType, notEquals, state);
			return value != null && right != null && !scalarEquals(value, right);
		}
		JsonNode in = condition.get("in");
		if (in != null) {
			if (value == null) {
				return false;
			}
			if ("variable".equalsIgnoreCase(valueType)) {
				JsonNode raw = condition.get("value") != null ? condition.get("value") : in;
				JsonNode right = resolveRightValue(valueType, raw, state);
				if (right == null || right.isNull()) {
					return false;
				}
				if (right.isArray()) {
					for (JsonNode option : right) {
						if (scalarEquals(value, option)) {
							return true;
						}
					}
					return false;
				}
				return scalarEquals(value, right);
			}
			if (in.isArray()) {
				for (JsonNode option : in) {
					if (scalarEquals(value, option)) {
						return true;
					}
				}
				return false;
			}
			return scalarEquals(value, in);
		}
		JsonNode contains = condition.get("contains");
		if (contains != null) {
			if (value == null || !value.isArray()) {
				return false;
			}
			JsonNode right = resolveRightValue(valueType, contains, state);
			if (right == null || right.isNull()) {
				return false;
			}
			for (JsonNode option : value) {
				if (scalarEquals(option, right)) {
					return true;
				}
			}
			return false;
		}
		return false;
	}

	private boolean isEmptyArrayValue(JsonNode value) {
		if (value == null || value.isNull()) {
			return true;
		}
		return value.isArray() && value.isEmpty();
	}

	private boolean hasScalarValue(JsonNode value) {
		if (value == null || value.isNull()) {
			return false;
		}
		if (value.isTextual()) {
			return !value.asText().isBlank();
		}
		return true;
	}

	private boolean scalarEquals(JsonNode left, JsonNode right) {
		if (left == null || right == null || left.isNull() || right.isNull()) {
			return false;
		}
		if (left.isContainerNode() || right.isContainerNode()) {
			return left.equals(right);
		}
		if (left.isBoolean() || right.isBoolean()) {
			Boolean leftValue = coerceBoolean(left);
			Boolean rightValue = coerceBoolean(right);
			return leftValue != null && rightValue != null && leftValue.equals(rightValue);
		}
		if (left.isNumber() || right.isNumber()) {
			java.math.BigDecimal leftValue = coerceNumber(left);
			java.math.BigDecimal rightValue = coerceNumber(right);
			return leftValue != null && rightValue != null && leftValue.compareTo(rightValue) == 0;
		}
		java.time.OffsetDateTime leftDateTime = coerceDateTime(left);
		java.time.OffsetDateTime rightDateTime = coerceDateTime(right);
		if (leftDateTime != null || rightDateTime != null) {
			return leftDateTime != null && rightDateTime != null && leftDateTime.isEqual(rightDateTime);
		}
		java.time.LocalDate leftDate = coerceDate(left);
		java.time.LocalDate rightDate = coerceDate(right);
		if (leftDate != null || rightDate != null) {
			return leftDate != null && rightDate != null && leftDate.equals(rightDate);
		}
		java.time.OffsetTime leftTime = coerceTime(left);
		java.time.OffsetTime rightTime = coerceTime(right);
		if (leftTime != null || rightTime != null) {
			return leftTime != null
				&& rightTime != null
				&& leftTime.withOffsetSameInstant(java.time.ZoneOffset.UTC)
					.toLocalTime()
					.equals(rightTime.withOffsetSameInstant(java.time.ZoneOffset.UTC).toLocalTime());
		}
		return left.asText().equals(right.asText());
	}

	private java.math.BigDecimal coerceNumber(JsonNode value) {
		if (value == null || value.isNull() || value.isContainerNode() || value.isBoolean()) {
			return null;
		}
		try {
			if (value.isNumber()) {
				return value.decimalValue();
			}
			String text = value.asText();
			if (text == null || text.isBlank()) {
				return null;
			}
			return new java.math.BigDecimal(text.trim());
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private Boolean coerceBoolean(JsonNode value) {
		if (value == null || value.isNull() || value.isContainerNode()) {
			return null;
		}
		if (value.isBoolean()) {
			return value.booleanValue();
		}
		String text = value.asText();
		if (text == null) {
			return null;
		}
		String normalized = text.trim().toLowerCase(java.util.Locale.ROOT);
		if (normalized.isEmpty()) {
			return null;
		}
		if ("true".equals(normalized)) {
			return true;
		}
		if ("false".equals(normalized)) {
			return false;
		}
		return null;
	}

	private java.time.LocalDate coerceDate(JsonNode value) {
		if (value == null
				|| value.isNull()
				|| value.isContainerNode()
				|| value.isBoolean()
				|| value.isNumber()) {
			return null;
		}
		try {
			String text = value.asText();
			if (text == null || text.isBlank()) {
				return null;
			}
			return java.time.LocalDate.parse(text.trim());
		}
		catch (java.time.format.DateTimeParseException ex) {
			return null;
		}
	}

	private java.time.OffsetTime coerceTime(JsonNode value) {
		if (value == null
				|| value.isNull()
				|| value.isContainerNode()
				|| value.isBoolean()
				|| value.isNumber()) {
			return null;
		}
		try {
			String text = value.asText();
			if (text == null || text.isBlank()) {
				return null;
			}
			return java.time.OffsetTime.parse(text.trim());
		}
		catch (java.time.format.DateTimeParseException ex) {
			try {
				String text = value.asText();
				if (text == null || text.isBlank()) {
					return null;
				}
				return java.time.LocalTime.parse(text.trim()).atOffset(java.time.ZoneOffset.UTC);
			}
			catch (java.time.format.DateTimeParseException inner) {
				return null;
			}
		}
	}

	private java.time.OffsetDateTime coerceDateTime(JsonNode value) {
		if (value == null
				|| value.isNull()
				|| value.isContainerNode()
				|| value.isBoolean()
				|| value.isNumber()) {
			return null;
		}
		try {
			String text = value.asText();
			if (text == null || text.isBlank()) {
				return null;
			}
			return java.time.OffsetDateTime.parse(text.trim());
		}
		catch (java.time.format.DateTimeParseException ex) {
			return null;
		}
	}

	private JsonNode resolveRightValue(String valueType, JsonNode raw, ObjectNode state) {
		if (raw == null) {
			return null;
		}
		if ("variable".equalsIgnoreCase(valueType)) {
			String path = raw.isTextual() ? raw.asText() : raw.toString();
			return readConditionPath(state, path);
		}
		return raw;
	}

	private ObjectNode resolveLogicalNode(WorkflowDefinition definition, ConversationGraphState state) {
		return resolveLogicalNodeFromJson(resolveDefinitionJson(null, definition), state);
	}

	private ObjectNode resolveLogicalNodeFromJson(JsonNode definitionJson, ConversationGraphState state) {
		if (definitionJson == null || state == null) {
			return null;
		}
		ObjectNode enriched = workflowDefinitionService.enrichDefinition(definitionJson);
		ObjectNode logicalNodes = enriched.get("logical_nodes") instanceof ObjectNode node ? node : null;
		if (logicalNodes == null) {
			return null;
		}
		String logicalId = state.value(ConversationGraphState.LOGICAL_NODE_ID).map(Object::toString).orElse(null);
		if (logicalId == null || logicalId.isBlank()) {
			return null;
		}
		JsonNode node = logicalNodes.get(logicalId);
		if (node instanceof ObjectNode obj) {
			return obj;
		}
		return null;
	}

	private ChatMessage buildAiSystemPrompt(
			Session session,
			ConversationGraphState state,
			WorkflowDefinition definition,
			ObjectNode logicalNode) {
		if (session == null || logicalNode == null) {
			return null;
		}
		String text = buildCompiledPromptText(session, state, definition, logicalNode, true);
		return text == null || text.isBlank() ? null : SystemMessage.from(text);
	}

	private ChatMessage buildUserSystemPrompt(
			Session session,
			ConversationGraphState state,
			WorkflowDefinition definition,
			ObjectNode logicalNode) {
		if (session == null) {
			return null;
		}
		String text = buildCompiledPromptText(session, state, definition, logicalNode, false);
		return text == null || text.isBlank() ? null : SystemMessage.from(text);
	}

	private void injectSystemInfoBeforeLastUserMessage(
			List<ChatMessage> history,
			UUID userId,
			LocationInfo location,
			List<SystemInfoMessage> injectedMessages) {
		ChatMessage systemInfo = buildSystemInfoMessage(userId, location, injectedMessages);
		if (systemInfo == null || history == null || history.isEmpty()) {
			return;
		}
		for (int i = history.size() - 1; i >= 0; i--) {
			if (history.get(i) instanceof UserMessage) {
				history.add(i, systemInfo);
				return;
			}
		}
		history.add(systemInfo);
	}

	private ChatMessage buildSystemInfoMessage(
			UUID userId,
			LocationInfo location,
			List<SystemInfoMessage> injectedMessages) {
		String systemInfo = buildSystemInfoBlock(userId, location, injectedMessages);
		if (systemInfo == null || systemInfo.isBlank()) {
			return null;
		}
		return SystemMessage.from(systemInfo);
	}

	private String buildCompiledPromptText(
			Session session,
			ConversationGraphState state,
			WorkflowDefinition definition,
			ObjectNode logicalNode,
			boolean isAiNode) {
		if (session == null) {
			return null;
		}
		Assistant assistant = resolveAssistantForNode(session, logicalNode, isAiNode);
		List<Skill> assistantSkills = loadAssistantSkills(session, assistant);
		PromptParts prompt = buildPromptParts(session, assistant, assistantSkills, definition, logicalNode, state);
		String personaPrompt = normalizePrompt(prompt.personaPrompt);
		String assistantPrompt = normalizePrompt(prompt.assistantPrompt);
		List<String> directiveContents = prompt.directives == null
			? List.of()
			: prompt.directives
				.stream()
				.map((rule) -> normalizePrompt(rule == null ? null : rule.content))
				.filter(Objects::nonNull)
				.toList();
		String coreRulesText = joinRules(directiveContents);
		List<Skill> visibleSkills = prompt.skills == null ? List.of() : prompt.skills.stream().filter(this::isSkillRenderable).toList();
		List<Skill> availableSkills = isAiNode ? List.of() : visibleSkills;
		JsonNode schema = isAiNode && logicalNode != null ? logicalNode.get("output_schema") : null;
		String outputSchema = hasOutputSchema(schema) ? schema.toPrettyString() : null;
		String task = isAiNode && logicalNode != null ? logicalNode.path("goal").asText(null) : null;
		ObjectNode contextNode = null;
		String contextJson = contextNode == null || contextNode.isEmpty() ? null : contextNode.toPrettyString();

		Map<String, Object> context = new HashMap<>();
		context.put("persona_prompt", personaPrompt);
		context.put("assistant_prompt", assistantPrompt);
		context.put("core_rules", coreRulesText);
		context.put("task", normalizePrompt(task));
		context.put("context_json", contextJson);
		context.put("output_schema", outputSchema);
		context.put("available_skills", buildSkillEntries(availableSkills));
		context.put("skills_tools_enabled", !isAiNode);
		if (!isAiNode) {
			context.put("available_workers", listEligibleWorkers(session));
		}
		String promptNodeId = state.value(ConversationGraphState.LOGICAL_NODE_ID)
			.map(Object::toString)
			.filter((value) -> value != null && !value.isBlank())
			.orElse("llm");
		McpActivationContext mcpActivation = buildMcpActivationContext(session, state, promptNodeId);
		if (mcpActivation.toolName != null && !mcpActivation.toolName.isBlank()) {
			context.put("activate_mcp_tool_name", mcpActivation.toolName);
		}
		if (mcpActivation.servers != null && !mcpActivation.servers.isEmpty()) {
			context.put("available_mcp_servers", mcpActivation.servers);
		}
		List<Map<String, Object>> activeMcpPrompts = buildActiveMcpPromptEntries(session, state, promptNodeId);
		if (!activeMcpPrompts.isEmpty()) {
			context.put("active_mcp_servers", activeMcpPrompts);
		}
		String channelPrompt = session.channel == null ? null : normalizePrompt(session.channel.prompt);
		if (isDesignSession(session)) {
			ObjectNode catalog = catalogService.buildCatalog(session.workspace.id);
			context.put("design_mode", true);
			context.put("design_catalog_json", catalog.toPrettyString());
			context.put("design_rules", loadDesignRules());
		}
		if (isScriptSession(session)) {
			ObjectNode catalog = buildScriptSessionCatalog(session);
			context.put("script_mode", true);
			context.put("script_catalog_json", catalog.toPrettyString());
			context.put("script_rules", loadScriptRules());
		}

		List<String> sections = new ArrayList<>();
		if (channelPrompt != null) {
			sections.add(channelPrompt);
		}
		if (personaPrompt != null) {
			String rendered = renderSection(PromptTemplateSection.PERSONALITY, context, session);
			addSection(sections, rendered);
		}
		if (coreRulesText != null) {
			String rendered = renderSection(PromptTemplateSection.CORE_RULES, context, session);
			addSection(sections, rendered);
		}
		if (isWorker(session, state)) {
			String rendered = renderSection(PromptTemplateSection.WORKER_AUTONOMY, context, session);
			addSection(sections, rendered);
		}
		if (!availableSkills.isEmpty()) {
			String rendered = renderSection(PromptTemplateSection.SKILLS, context, session);
			addSection(sections, rendered);
		}
		if (outputSchema != null) {
			String rendered = renderSection(PromptTemplateSection.FORMATTING, context, session);
			addSection(sections, rendered);
		}
		if (sections.isEmpty()) {
			return null;
		}
		String joined = String.join("\n\n", sections).trim();
		return normalizeSpacing(joined);
	}

	SessionPromptResponse buildCompiledPrompt(Session session, WorkflowRun run) {
		if (session == null || run == null) {
			return new SessionPromptResponse(null, null, null);
		}
		WorkflowDefinition definition = run.workflowDefinition;
		ObjectNode checkpoint = run.checkpointJson instanceof ObjectNode node ? node : objectMapper.createObjectNode();
		ConversationGraphState state = stateFromCheckpoint(checkpoint);
		ObjectNode logicalNode = resolveLogicalNodeFromJson(resolveDefinitionJson(run, definition), state);
		String nodeType = logicalNode == null ? null : logicalNode.path("type").asText(null);
		String logicalNodeId = resolveSkillNodeId(state, definition, logicalNode);
		boolean isAi = nodeType != null && "ai".equalsIgnoreCase(nodeType);
		String text = buildCompiledPromptText(session, state, definition, logicalNode, isAi);
		return new SessionPromptResponse(text, logicalNodeId, nodeType);
	}

	private ConversationGraphState stateFromCheckpoint(ObjectNode checkpoint) {
		if (checkpoint == null) {
			return new ConversationGraphState(new HashMap<>());
		}
		Map<String, Object> data = objectMapper.convertValue(checkpoint, new TypeReference<>() {});
		return new ConversationGraphState(data == null ? new HashMap<>() : data);
	}

	private record PromptParts(
			String personaPrompt,
			String assistantPrompt,
			List<Rule> directives,
			List<Skill> skills,
			Skill activeSkill) {}

	private record SystemInfoMessage(String type, String content) {}

	private PromptParts buildPromptParts(
			Session session,
			Assistant assistant,
			List<Skill> assistantSkills,
			WorkflowDefinition definition,
			ObjectNode logicalNode,
			ConversationGraphState state) {
		String personaPrompt = null;
		if (assistant != null && assistant.persona != null) {
			personaPrompt = assistant.persona.promptText;
		}
		String assistantPrompt = assistant == null ? null : assistant.promptText;
		List<Rule> directives = loadDirectiveRules(session, assistant);
		Skill activeSkill = resolveActiveSkill(state, definition, logicalNode, assistantSkills);
		return new PromptParts(personaPrompt, assistantPrompt, directives, assistantSkills, activeSkill);
	}

	private String assemblePromptText(
			PromptParts prompt,
			String goal,
			ObjectNode context,
			Skill pinnedSkill,
			JsonNode outputSchema,
			boolean includeSkillList) {
		if (prompt == null) {
			return null;
		}
		StringBuilder builder = new StringBuilder();
		String personaPrompt = normalizePrompt(prompt.personaPrompt);
		if (personaPrompt != null) {
			builder.append("## Personality\n\n").append(personaPrompt).append("\n\n");
		}
		List<Rule> directives = prompt.directives == null ? List.of() : prompt.directives;
		List<String> directiveContents = directives.stream()
			.map((rule) -> normalizePrompt(rule == null ? null : rule.content))
			.filter(Objects::nonNull)
			.toList();
		boolean hasDirectives = !directiveContents.isEmpty();
		if (hasDirectives) {
			builder.append("## Core Rules\n\n");
			if (goal != null && !goal.isBlank()) {
				builder.append("Task: ").append(goal.trim()).append("\n");
			}
			if (context != null && !context.isEmpty()) {
				builder.append("Context:\n").append(context.toPrettyString()).append("\n");
			}
			String assistantPrompt = normalizePrompt(prompt.assistantPrompt);
			if (assistantPrompt != null) {
				builder.append(assistantPrompt).append("\n");
			}
			boolean firstRule = true;
			for (String content : directiveContents) {
				if (!firstRule) {
					builder.append("--\n\n");
				}
				builder.append(content).append("\n\n");
				firstRule = false;
			}
			builder.append("\n");
		}
		List<Skill> skills = prompt.skills == null ? List.of() : prompt.skills;
		List<Skill> visibleSkills = skills.stream()
			.filter((skill) -> normalizePrompt(skill == null ? null : skill.promptText) != null)
			.toList();
		boolean hasSkills = includeSkillList && !visibleSkills.isEmpty();
		if (hasSkills) {
			builder.append("## Skills\n\n");
			builder.append(
				"- **Match:** Review the list of `[AVAILABLE_SKILLS]`. Determine if the user's request matches the `When to use` section of an available skill.\n"
			);
			builder.append("- **Execute Skill:** If a skill is matched, you MUST follow this sub-protocol:\n");
			builder.append("\t- **a. Check if Loaded:** Check the injected `<system-info>` messages for active skills.\n");
			builder.append(
				"\t- **b. Load if Necessary:** If the skill is not yet loaded, you MUST use the `skill_activate` tool to retrieve its instructions.\n"
			);
			builder.append(
				"\t- **c. Follow Instructions:** Once the skill's instructions are loaded, you MUST follow them precisely. The skill's defined procedure takes precedence over the general execution plan.\n"
			);
			builder.append(
				"\t- **d. When done:** If your task has been completed as verified by the user, call `skill_deactivate` to deactivate the current skill.\n\n"
			);
			builder.append("[AVAILABLE_SKILLS]\n\n");
			if (hasSkills) {
				for (Skill skill : visibleSkills) {
					if (skill == null) {
						continue;
					}
					String skillName = skillKey(skill);
					String desc = normalizePrompt(skill.description);
					String trigger = normalizePrompt(skill.trigger);
					String skillPrompt = normalizePrompt(skill.promptText);
					if (skillPrompt == null) {
						continue;
					}
					builder.append("Skill: **").append(skillName).append("**:\n");
					builder.append("- **Description**: ").append(desc == null ? "" : desc).append("\n");
					builder.append("- **When to use**: ").append(trigger == null ? "" : trigger).append("\n\n");
				}
			}
			builder.append("\n");
		}
		if (outputSchema != null && !outputSchema.isNull()) {
			builder.append("## Formatting\n\nRespond with JSON matching this schema:\n")
				.append(outputSchema.toPrettyString())
				.append("\n");
		}
		String text = builder.toString().trim();
		return text.isBlank() ? null : text;
	}

	private String normalizePrompt(String prompt) {
		if (prompt == null) {
			return null;
		}
		String trimmed = prompt.trim();
		return trimmed.isBlank() ? null : trimmed;
	}

	private String normalizeSpacing(String text) {
		if (text == null) {
			return null;
		}
		return text.replaceAll("\n{3,}", "\n\n");
	}

	private String joinRules(List<String> rules) {
		if (rules == null || rules.isEmpty()) {
			return null;
		}
		String joined = String.join("\n\n--\n\n", rules);
		return joined + "\n";
	}

	private boolean isSkillRenderable(Skill skill) {
		return normalizePrompt(skill == null ? null : skill.promptText) != null;
	}

	private List<Map<String, Object>> buildSkillEntries(List<Skill> skills) {
		if (skills == null || skills.isEmpty()) {
			return List.of();
		}
		List<Map<String, Object>> entries = new ArrayList<>();
		for (Skill skill : skills) {
			if (!isSkillRenderable(skill)) {
				continue;
			}
			Map<String, Object> entry = new HashMap<>();
			entry.put("name", skillKey(skill));
			entry.put("description", normalizePrompt(skill.description));
			entry.put("trigger", normalizePrompt(skill.trigger));
			entry.put("prompt", normalizePrompt(skill.promptText));
			entries.add(entry);
		}
		return entries;
	}

	private Map<String, Object> buildActiveSkillEntry(Skill skill) {
		if (!isSkillRenderable(skill)) {
			return null;
		}
		Map<String, Object> entry = new HashMap<>();
		entry.put("name", skillKey(skill));
		entry.put("description", normalizePrompt(skill.description));
		entry.put("trigger", normalizePrompt(skill.trigger));
		entry.put("prompt", normalizePrompt(skill.promptText));
		return entry;
	}

	private String renderSection(PromptTemplateSection section, Map<String, Object> context, Session session) {
		if (section == null || session == null) {
			return null;
		}
		UUID tenantId = session.tenant == null ? null : session.tenant.id;
		UUID workspaceId = session.workspace == null ? null : session.workspace.id;
		var template = promptTemplateService.resolveTemplate(tenantId, workspaceId, section);
		return promptTemplateService.render(template, context);
	}

	private void addSection(List<String> sections, String rendered) {
		if (rendered == null) {
			return;
		}
		String trimmed = rendered.trim();
		if (trimmed.isBlank()) {
			return;
		}
		sections.add(trimmed);
	}

	private boolean hasOutputSchema(JsonNode schema) {
		if (schema == null || schema.isNull()) {
			return false;
		}
		if (schema.isObject() && schema.size() == 0) {
			return false;
		}
		return true;
	}

	private List<Rule> loadDirectiveRules(Session session, Assistant assistant) {
		if (session == null) {
			return List.of();
		}
		List<Rule> rules = new ArrayList<>();
		if (session.tenant != null) {
			rules.addAll(
				entityManager.createQuery(
						"select r from Rule r where r.scope = :scope and r.tenant.id = :tenantId "
							+ "and r.alwaysIncluded = true and r.enabled = true",
						Rule.class
					)
					.setParameter("scope", RuleScope.TENANT)
					.setParameter("tenantId", session.tenant.id)
					.getResultList()
			);
		}
		if (session.workspace != null) {
			rules.addAll(
				entityManager.createQuery(
						"select r from Rule r where r.scope = :scope and r.workspace.id = :workspaceId "
							+ "and r.alwaysIncluded = true and r.enabled = true",
						Rule.class
					)
					.setParameter("scope", RuleScope.WORKSPACE)
					.setParameter("workspaceId", session.workspace.id)
					.getResultList()
			);
		}
		if (session.createdBy != null) {
			rules.addAll(
				entityManager.createQuery(
						"select r from Rule r where r.scope = :scope and r.user.id = :userId and r.enabled = true",
						Rule.class
					)
					.setParameter("scope", RuleScope.USER)
					.setParameter("userId", session.createdBy.id)
					.getResultList()
			);
		}
		if (assistant != null) {
			rules.addAll(
				entityManager.createQuery(
						"select r.rule from AssistantRule r where r.assistant.id = :assistantId and r.rule.enabled = true",
						Rule.class
					)
					.setParameter("assistantId", assistant.id)
					.getResultList()
			);
		}
		rules.sort(
			(a, b) -> {
				int orderA = a == null || a.order == null ? 0 : a.order;
				int orderB = b == null || b.order == null ? 0 : b.order;
				if (orderA != orderB) {
					return Integer.compare(orderB, orderA);
				}
				String nameA = a == null || a.name == null ? "" : a.name;
				String nameB = b == null || b.name == null ? "" : b.name;
				return nameA.compareToIgnoreCase(nameB);
			}
		);
		return rules;
	}

	private Assistant resolveAssistantForNode(Session session, ObjectNode logicalNode, boolean preferNodeAssistant) {
		if (session == null) {
			return null;
		}
		UUID assistantId = null;
		if (preferNodeAssistant && logicalNode != null) {
			assistantId = readUuid(logicalNode.get("assistant_id"));
		}
		if (assistantId == null && session.defaultAssistant != null) {
			assistantId = session.defaultAssistant.id;
		}
		if (assistantId == null) {
			return null;
		}
		return entityManager.createQuery("select a from Assistant a left join fetch a.persona where a.id = :id", Assistant.class)
			.setParameter("id", assistantId)
			.getResultStream()
			.findFirst()
			.orElse(null);
	}

	private List<Skill> loadAssistantSkills(Session session, Assistant assistant) {
		if (assistant == null || assistant.id == null) {
			return List.of();
		}
		UUID tenantId = assistant.tenant == null ? null : assistant.tenant.id;
		UUID workspaceId = session == null || session.workspace == null ? null : session.workspace.id;
		if (tenantId == null) {
			return List.of();
		}
		return entityManager.createQuery(
				"select distinct s from Skill s where s.tenant.id = :tenantId and ("
					+ "s.id in (select link.skill.id from AssistantSkill link where link.assistant.id = :assistantId) "
					+ "or (s.workspace is null and s.alwaysIncluded = true) "
					+ "or (s.workspace.id = :workspaceId and s.alwaysIncluded = true))",
				Skill.class
			)
			.setParameter("tenantId", tenantId)
			.setParameter("assistantId", assistant.id)
			.setParameter("workspaceId", workspaceId)
			.getResultList();
	}

	private Skill resolvePinnedSkill(ObjectNode logicalNode, List<Skill> skills) {
		if (logicalNode == null || skills == null || skills.isEmpty()) {
			return null;
		}
		UUID pinnedId = readUuid(logicalNode.get("pinned_skill_id"));
		if (pinnedId == null) {
			return null;
		}
		for (Skill skill : skills) {
			if (skill != null && pinnedId.equals(skill.id) && isSkillRenderable(skill)) {
				return skill;
			}
		}
		return null;
	}

	private Skill resolveActiveSkill(
			ConversationGraphState state,
			WorkflowDefinition definition,
			ObjectNode logicalNode,
			List<Skill> skills) {
		List<Skill> activeSkills = resolveActiveSkills(state, definition, logicalNode, skills);
		return activeSkills.isEmpty() ? null : activeSkills.get(0);
	}

	private List<Skill> resolveActiveSkills(
			ConversationGraphState state,
			WorkflowDefinition definition,
			ObjectNode logicalNode,
			List<Skill> skills) {
		if (state == null || skills == null || skills.isEmpty()) {
			return List.of();
		}
		String logicalNodeId = resolveSkillNodeId(state, definition, logicalNode);
		if (logicalNodeId == null || logicalNodeId.isBlank()) {
			return List.of();
		}
		ObjectNode internal = readInternalObject(state);
		if (internal == null) {
			return List.of();
		}
		JsonNode activeSkills = internal.get(INTERNAL_ACTIVE_SKILLS);
		if (activeSkills == null || !activeSkills.isObject()) {
			return List.of();
		}
		JsonNode value = activeSkills.get(logicalNodeId);
		List<UUID> activeSkillIds = new ArrayList<>();
		if (value != null && value.isArray()) {
			for (JsonNode item : value) {
				UUID skillId = readUuid(item);
				if (skillId != null) {
					activeSkillIds.add(skillId);
				}
			}
		}
		else {
			UUID skillId = readUuid(value);
			if (skillId != null) {
				activeSkillIds.add(skillId);
			}
		}
		if (activeSkillIds.isEmpty()) {
			return List.of();
		}
		List<Skill> resolved = new ArrayList<>();
		for (UUID activeSkillId : activeSkillIds) {
			for (Skill skill : skills) {
				if (skill != null && activeSkillId.equals(skill.id) && isSkillRenderable(skill)) {
					resolved.add(skill);
					break;
				}
			}
		}
		return resolved;
	}

	private String resolveSkillNodeId(
			ConversationGraphState state,
			WorkflowDefinition definition,
			ObjectNode logicalNode) {
		if (state != null) {
			String logicalNodeId = state.value(ConversationGraphState.LOGICAL_NODE_ID)
				.map(Object::toString)
				.orElse(null);
			if (logicalNodeId != null && !logicalNodeId.isBlank()) {
				return logicalNodeId;
			}
		}
		if (definition != null && workflowDefinitionService.isConversationDefinition(definition)) {
			return "conversation";
		}
		if (logicalNode != null && logicalNode.has("id")) {
			String id = logicalNode.path("id").asText(null);
			if (id != null && !id.isBlank()) {
				return id;
			}
		}
		return null;
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
		catch (IllegalArgumentException error) {
			return null;
		}
	}

	private String skillKey(Skill skill) {
		if (skill == null) {
			return "unknown_skill";
		}
		String name = skill.name == null ? "" : skill.name.trim().toLowerCase();
		if (name.isBlank()) {
			return "unknown_skill";
		}
		String normalized = name.replaceAll("[^a-z0-9]+", "_");
		normalized = normalized.replaceAll("_+", "_");
		normalized = normalized.replaceAll("^_", "");
		normalized = normalized.replaceAll("_$", "");
		return normalized.isBlank() ? "unknown_skill" : normalized;
	}

	private ObjectNode readStateObject(ConversationGraphState state) {
		Object value = state.value(ConversationGraphState.STATE).orElse(null);
		if (value instanceof ObjectNode node) {
			return node.deepCopy();
		}
		if (value instanceof JsonNode json && json.isObject()) {
			return objectMapper.convertValue(json, ObjectNode.class);
		}
		if (value instanceof Map<?, ?> map) {
			return objectMapper.convertValue(map, ObjectNode.class);
		}
		return objectMapper.createObjectNode();
	}

	private boolean isWorker(ConversationGraphState state) {
		if (state == null) {
			return false;
		}
		ObjectNode stateNode = readStateObject(state);
		JsonNode flag = stateNode.get(WORKER_FLAG_KEY);
		return flag != null && flag.asBoolean(false);
	}

	private boolean isWorker(Session session, ConversationGraphState state) {
		if (session != null && session.parentSession != null) {
			return true;
		}
		if (state == null) {
			return false;
		}
		ObjectNode stateNode = readStateObject(state);
		JsonNode parentId = stateNode.get("parent_session_id");
		if (parentId != null && parentId.isTextual() && !parentId.asText().isBlank()) {
			return true;
		}
		JsonNode toolCallId = stateNode.get("tool_call_id");
		if (toolCallId != null && toolCallId.isTextual() && !toolCallId.asText().isBlank()) {
			return true;
		}
		return isWorker(state);
	}

	private List<String> readWorkerAllowedTools(ConversationGraphState state) {
		if (state == null) {
			return List.of();
		}
		ObjectNode stateNode = readStateObject(state);
		JsonNode node = stateNode.get(WORKER_ALLOWED_TOOLS_KEY);
		if (node == null || !node.isArray()) {
			return List.of();
		}
		List<String> tools = new ArrayList<>();
		for (JsonNode entry : node) {
			if (entry != null && entry.isTextual()) {
				String value = entry.asText();
				if (value != null && !value.isBlank()) {
					tools.add(value);
				}
			}
		}
		return tools;
	}

	private boolean isWorkerToolListLeading() {
		String mode = workerRequestedToolsMode == null ? WORKER_REQUESTED_TOOLS_MODE_LEADING : workerRequestedToolsMode;
		if (mode == null || mode.isBlank()) {
			return true;
		}
		return WORKER_REQUESTED_TOOLS_MODE_LEADING.equalsIgnoreCase(mode.trim());
	}

	private List<ToolSpecification> filterWorkerToolSpecifications(
			List<ToolSpecification> specs,
			List<String> allowedTools) {
		if (specs == null || specs.isEmpty()) {
			return List.of();
		}
		if (allowedTools == null || allowedTools.isEmpty()) {
			return List.of();
		}
		java.util.Set<String> allowed = new java.util.HashSet<>(allowedTools);
		List<ToolSpecification> filtered = new ArrayList<>();
		for (ToolSpecification spec : specs) {
			if (spec == null) {
				continue;
			}
			String name = spec.name();
			if (name != null && allowed.contains(name)) {
				filtered.add(spec);
			}
		}
		return filtered;
	}

	private List<Map<String, String>> listEligibleWorkers(Session session) {
		if (session == null || session.workspace == null) {
			return List.of();
		}
		List<Assistant> assistants = entityManager.createQuery(
				"select a from Assistant a where a.tenant.id = :tenantId and a.workerEnabled = true",
				Assistant.class
			)
			.setParameter("tenantId", session.tenant.id)
			.getResultList();
		List<Map<String, String>> results = new ArrayList<>();
		for (Assistant assistant : assistants) {
			if (assistant == null) {
				continue;
			}
			if (assistant.workspace != null && !assistant.workspace.id.equals(session.workspace.id)) {
				continue;
			}
			String slug = AssistantSlug.fromName(assistant.name);
			if (slug.isBlank()) {
				continue;
			}
			String trigger = assistant.workerTrigger == null ? "" : assistant.workerTrigger.trim();
			results.add(Map.of("name", slug, "trigger", trigger));
		}
		return results;
	}

	private List<UUID> readChildRunIds(ObjectNode stateNode) {
		if (stateNode == null) {
			return List.of();
		}
		JsonNode childrenNode = stateNode.get(STATE_CHILDREN_KEY);
		if (childrenNode == null || !childrenNode.isArray()) {
			return List.of();
		}
		List<UUID> ids = new ArrayList<>();
		for (JsonNode entry : childrenNode) {
			if (entry == null || entry.isNull()) {
				continue;
			}
			UUID runId = readUuid(entry.get("run_id"));
			if (runId != null) {
				ids.add(runId);
			}
		}
		return ids;
	}

	private boolean isSpawnChildrenTool(ToolCall call) {
		if (call == null) {
			return false;
		}
		if (call.toolName != null && SPAWN_CHILDREN_TOOL.equals(call.toolName)) {
			return true;
		}
		return call.fullName != null && SPAWN_CHILDREN_TOOL.equals(call.fullName);
	}

	private List<ObjectNode> extractChildRunRefs(JsonNode result) {
		if (result == null || result.isNull() || !result.isObject()) {
			return List.of();
		}
		List<ObjectNode> refs = new ArrayList<>();
		JsonNode children = result.get(STATE_CHILDREN_KEY);
		if (children != null && children.isArray()) {
			for (JsonNode entry : children) {
				if (entry == null || entry.isNull() || !entry.isObject()) {
					continue;
				}
				ObjectNode ref = objectMapper.createObjectNode();
				JsonNode runId = entry.get("run_id");
				JsonNode sessionId = entry.get("session_id");
				if (runId != null && runId.isTextual()) {
					ref.set("run_id", runId);
				}
				if (sessionId != null && sessionId.isTextual()) {
					ref.set("session_id", sessionId);
				}
				JsonNode toolCallId = entry.get("tool_call_id");
				if (toolCallId != null && toolCallId.isTextual()) {
					ref.set("tool_call_id", toolCallId);
				}
				if (!ref.isEmpty()) {
					refs.add(ref);
				}
			}
		}
		JsonNode runIds = result.get("run_ids");
		if (runIds != null && runIds.isArray()) {
			for (JsonNode entry : runIds) {
				if (entry == null || !entry.isTextual()) {
					continue;
				}
				ObjectNode ref = objectMapper.createObjectNode();
				ref.set("run_id", entry);
				refs.add(ref);
			}
		}
		return refs;
	}

	private ObjectNode mergeChildRunRefs(ObjectNode stateNode, List<ObjectNode> refs) {
		if (stateNode == null || refs == null || refs.isEmpty()) {
			return stateNode;
		}
		ArrayNode children = stateNode.withArray(STATE_CHILDREN_KEY);
		java.util.Set<String> existing = new java.util.HashSet<>();
		for (JsonNode entry : children) {
			if (entry != null && entry.isObject()) {
				JsonNode runId = entry.get("run_id");
				if (runId != null && runId.isTextual()) {
					existing.add(runId.asText());
				}
			}
		}
		for (ObjectNode ref : refs) {
			if (ref == null || ref.isEmpty()) {
				continue;
			}
			JsonNode runId = ref.get("run_id");
			if (runId != null && runId.isTextual()) {
				String id = runId.asText();
				if (!existing.add(id)) {
					continue;
				}
			}
			children.add(ref);
		}
		return stateNode;
	}

	private Map<String, List<ObjectNode>> groupChildrenByToolCall(ObjectNode stateNode) {
		if (stateNode == null) {
			return Map.of();
		}
		JsonNode children = stateNode.get(STATE_CHILDREN_KEY);
		if (children == null || !children.isArray()) {
			return Map.of();
		}
		Map<String, List<ObjectNode>> groups = new HashMap<>();
		for (JsonNode entry : children) {
			if (entry == null || !entry.isObject()) {
				continue;
			}
			String toolCallId = entry.path("tool_call_id").asText(null);
			if (toolCallId == null || toolCallId.isBlank()) {
				toolCallId = "";
			}
			groups.computeIfAbsent(toolCallId, key -> new ArrayList<>())
				.add((ObjectNode) entry);
		}
		return groups;
	}

	private List<String> readCompletedToolCalls(ObjectNode stateNode) {
		if (stateNode == null) {
			return List.of();
		}
		JsonNode completed = stateNode.get("completed_tool_calls");
		if (completed == null || !completed.isArray()) {
			return List.of();
		}
		List<String> ids = new ArrayList<>();
		for (JsonNode entry : completed) {
			if (entry != null && entry.isTextual()) {
				String value = entry.asText();
				if (!value.isBlank()) {
					ids.add(value);
				}
			}
		}
		return ids;
	}

	private void addCompletedToolCall(ObjectNode stateNode, String toolCallId) {
		if (stateNode == null || toolCallId == null || toolCallId.isBlank()) {
			return;
		}
		ArrayNode completed = stateNode.withArray("completed_tool_calls");
		for (JsonNode entry : completed) {
			if (entry != null && toolCallId.equals(entry.asText())) {
				return;
			}
		}
		completed.add(toolCallId);
	}

	private boolean isWorkerProgressPayload(JsonNode payload) {
		if (payload == null || payload.isNull()) {
			return false;
		}
		JsonNode kind = payload.get("kind");
		return kind != null && kind.isTextual() && "worker_progress".equals(kind.asText());
	}

	private ObjectNode buildWorkerToolResult(List<ObjectNode> children, Map<UUID, WorkflowRun> runById) {
		ObjectNode result = objectMapper.createObjectNode();
		StringBuilder content = new StringBuilder();
		ArrayNode childArray = result.putArray("children");
		int failed = 0;
		int workerIndex = 0;
		for (ObjectNode child : children) {
			ObjectNode item = childArray.addObject();
			String sessionId = child.path("session_id").asText(null);
			String runId = child.path("run_id").asText(null);
			item.put("session_id", sessionId);
			item.put("run_id", runId);
			if (child.hasNonNull("assistant_name")) {
				item.put("assistant_name", child.path("assistant_name").asText());
			}
			UUID runUuid = readUuid(child.get("run_id"));
			WorkflowRun run = runUuid == null ? null : runById.get(runUuid);
			WorkflowRunStatus status = run == null ? null : run.status;
			String statusText = status == null ? "unknown" : status.name().toLowerCase();
			item.put("status", statusText);
			if (status == WorkflowRunStatus.FAILED) {
				failed += 1;
			}
			if (run != null && run.session != null) {
				JsonNode state = resolveSubassistantState(run);
				if (state != null) {
					item.set("state", state);
				}
				JsonNode feedback = extractWorkerFeedback(state);
				if (feedback != null) {
					item.set("feedback", feedback);
					String feedbackStatus = feedback.path("status").asText("inconclusive");
					String feedbackMessage = feedback.path("message").asText("");
					if (content.length() > 0) {
						content.append("\n\n");
					}
					content.append("# Worker ").append(workerIndex).append(" done\n\n");
					content.append("Status: ").append(feedbackStatus).append("\n");
					content.append("Result:\n\n```\n");
					content.append(feedbackMessage);
					content.append("\n```\n--\n");
				}
				else {
					JsonNode summary = resolveSubassistantSummary(run.session.id);
					if (summary != null) {
						item.set("summary", summary);
					}
				}
			}
			workerIndex += 1;
		}
		result.put("failed", failed);
		result.put("total", children.size());
		int succeeded = Math.max(0, children.size() - failed);
		ObjectNode meta = result.putObject("_meta");
		meta.put("displayMessage", "Workers: " + succeeded + " succeeded, " + failed + " failed");
		if (content.length() > 0) {
			result.put("content", content.toString());
		}
		else {
			result.put("content", "Worker states could not be resolved.");
		}
		ObjectNode structured = result.deepCopy();
		structured.remove("content");
		result.set("structuredContent", structured);
		return result;
	}

	private JsonNode resolveSubassistantSummary(UUID sessionId) {
		if (sessionId == null) {
			return null;
		}
		List<SessionEvent> events = entityManager.createQuery(
				"select e from SessionEvent e where e.session.id = :sessionId and "
					+ "e.eventType = :type order by e.createdAt desc",
				SessionEvent.class
			)
			.setParameter("sessionId", sessionId)
			.setParameter("type", SessionEventType.ASSISTANT_MESSAGE)
			.setMaxResults(1)
			.getResultList();
		if (events.isEmpty()) {
			return null;
		}
		SessionEvent event = events.get(0);
		if (event == null || event.payloadJson == null) {
			return null;
		}
		ObjectNode summary = objectMapper.createObjectNode();
		JsonNode text = event.payloadJson.get("text");
		if (text != null) {
			summary.set("text", text);
		}
		return summary.isEmpty() ? null : summary;
	}

	private JsonNode resolveSubassistantState(WorkflowRun run) {
		if (run == null || run.checkpointJson == null) {
			return null;
		}
		JsonNode state = run.checkpointJson.get(ConversationGraphState.STATE);
		if (state == null || state.isNull()) {
			return null;
		}
		return state.deepCopy();
	}

	private JsonNode extractWorkerFeedback(JsonNode state) {
		if (state == null || !state.isObject()) {
			return null;
		}
		JsonNode feedback = state.get(WORKER_FEEDBACK_KEY);
		if (feedback == null || feedback.isNull()) {
			return null;
		}
		return feedback.deepCopy();
	}

	private void preserveSubassistantMarkers(ConversationGraphState state, ObjectNode nextState) {
		if (state == null || nextState == null) {
			return;
		}
		ObjectNode current = readStateObject(state);
		copyIfMissing(nextState, WORKER_FLAG_KEY, current);
		copyIfMissing(nextState, "parent_session_id", current);
		copyIfMissing(nextState, "tool_call_id", current);
		copyIfMissing(nextState, WORKER_ALLOWED_TOOLS_KEY, current);
		copyIfMissing(nextState, WORKER_COMPLETION_KEY, current);
	}

	private void copyIfMissing(ObjectNode target, String field, ObjectNode source) {
		if (target == null || source == null || field == null) {
			return;
		}
		if (target.has(field)) {
			return;
		}
		JsonNode value = source.get(field);
		if (value != null && !value.isNull()) {
			target.set(field, value.deepCopy());
		}
	}

	private ObjectNode readSubassistantCompletion(ObjectNode stateNode) {
		if (stateNode == null) {
			return null;
		}
		JsonNode completion = stateNode.get(WORKER_COMPLETION_KEY);
		if (completion == null || !completion.isObject()) {
			return null;
		}
		return (ObjectNode) completion;
	}

	private int readToolCallCount(ConversationGraphState state, String instanceId) {
		if (state == null || instanceId == null || instanceId.isBlank()) {
			return 0;
		}
		ObjectNode internal = readInternalObject(state);
		if (internal == null) {
			return 0;
		}
		JsonNode counts = internal.get("tool_call_counts");
		if (counts == null || !counts.isObject()) {
			return 0;
		}
		JsonNode value = counts.get(instanceId);
		return value != null && value.isNumber() ? value.asInt(0) : 0;
	}

	private void setStatePath(ObjectNode state, String path, JsonNode value) {
		if (state == null || path == null || path.isBlank()) {
			return;
		}
		String[] parts = path.split("\\.");
		ObjectNode current = state;
		for (int index = 0; index < parts.length - 1; index++) {
			current = current.withObject(parts[index]);
		}
		current.set(parts[parts.length - 1], value);
	}

	private JsonNode readConditionPath(ObjectNode state, String path) {
		if (state == null || path == null || path.isBlank()) {
			return null;
		}
		if (path.endsWith(".defined")) {
			String basePath = path.substring(0, path.length() - ".defined".length());
			JsonNode baseValue = readStatePath(state, basePath);
			return objectMapper.getNodeFactory().booleanNode(baseValue != null && !baseValue.isNull());
		}
		return readStatePath(state, path);
	}

	private void removeStatePath(ObjectNode state, String path) {
		if (state == null || path == null || path.isBlank()) {
			return;
		}
		String[] parts = path.split("\\.");
		ObjectNode current = state;
		for (int index = 0; index < parts.length - 1; index++) {
			String part = parts[index];
			JsonNode child = current.get(part);
			if (!(child instanceof ObjectNode objectChild)) {
				return;
			}
			current = objectChild;
		}
		current.remove(parts[parts.length - 1]);
	}

	private JsonNode readStatePath(ObjectNode state, String path) {
		if (state == null || path == null || path.isBlank()) {
			return null;
		}
		String normalized = path.trim();
		if (normalized.startsWith("state.")) {
			normalized = normalized.substring("state.".length());
		}
		else if ("state".equals(normalized)) {
			return state;
		}
		String[] parts = normalized.split("\\.");
		JsonNode current = state;
		for (String part : parts) {
			if (current == null || current.isNull()) {
				return null;
			}
			if (!current.isObject()) {
				return null;
			}
			current = current.get(part);
		}
		return current;
	}

	private AiOutputResult handleAiOutput(ConversationGraphState state, ObjectNode logicalNode, String text) {
		JsonNode schema = logicalNode == null ? null : logicalNode.get("output_schema");
		if (schema == null || schema.isNull()) {
			return new AiOutputResult(true, null);
		}
		if (text == null || text.isBlank()) {
			return new AiOutputResult(false, null);
		}
		JsonNode parsed = extractJson(text, schema);
		if (parsed == null || parsed.isNull()) {
			return new AiOutputResult(false, null);
		}
		return new AiOutputResult(true, null);
	}

	private JsonNode extractJson(String text, JsonNode schema) {
		if (text == null || text.isBlank()) {
			return null;
		}
		List<String> candidates = new ArrayList<>();
		int index = 0;
		while (true) {
			int start = text.indexOf("```", index);
			if (start < 0) {
				break;
			}
			int langEnd = text.indexOf("\n", start + 3);
			if (langEnd < 0) {
				break;
			}
			int end = text.indexOf("```", langEnd + 1);
			if (end < 0) {
				break;
			}
			String content = text.substring(langEnd + 1, end).trim();
			if (!content.isBlank()) {
				candidates.add(content);
			}
			index = end + 3;
		}
		List<String> balanced = extractBalancedJson(text);
		if (!balanced.isEmpty()) {
			candidates.addAll(balanced);
		}
		JsonNode parsed = null;
		for (String candidate : candidates) {
			JsonNode attempt = parseJson(candidate);
			if (attempt == null || attempt.isNull()) {
				continue;
			}
			if (schema == null || schema.isNull()) {
				return attempt;
			}
			if (validateJsonSchema(schema, attempt)) {
				return attempt;
			}
		}
		return parsed;
	}

	private List<String> extractBalancedJson(String text) {
		if (text == null) {
			return List.of();
		}
		List<String> candidates = new ArrayList<>();
		boolean inString = false;
		int start = -1;
		char open = 0;
		int depth = 0;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '"' && (i == 0 || text.charAt(i - 1) != '\\')) {
				inString = !inString;
			}
			if (inString) {
				continue;
			}
			if (start < 0 && (c == '{' || c == '[')) {
				start = i;
				open = c;
				depth = 1;
				continue;
			}
			if (start >= 0) {
				if (c == open) {
					depth++;
				}
				else if ((open == '{' && c == '}') || (open == '[' && c == ']')) {
					depth--;
					if (depth == 0) {
						candidates.add(text.substring(start, i + 1));
						start = -1;
						open = 0;
						depth = 0;
					}
				}
			}
		}
		return candidates;
	}

	private JsonNode parseJson(String text) {
		try {
			return objectMapper.readTree(text);
		}
		catch (Exception ignored) {
			return null;
		}
	}

	private boolean validateJsonSchema(JsonNode schemaNode, JsonNode instance) {
		if (schemaNode == null || schemaNode.isNull() || instance == null) {
			return false;
		}
		try {
			JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
			JsonSchema schema = factory.getSchema(schemaNode);
			Set<ValidationMessage> errors = schema.validate(instance);
			return errors == null || errors.isEmpty();
		}
		catch (Exception ex) {
			LOGGER.debugf(ex, "Failed to validate JSON schema");
			return false;
		}
	}

	private void writeStatePath(ObjectNode state, String path, JsonNode value) {
		if (state == null || path == null || path.isBlank()) {
			return;
		}
		String normalized = path.trim();
		if (normalized.startsWith("state.")) {
			normalized = normalized.substring("state.".length());
		}
		String[] parts = normalized.split("\\.");
		ObjectNode current = state;
		for (int i = 0; i < parts.length; i++) {
			String part = parts[i];
			if (part.isBlank()) {
				continue;
			}
			if (i == parts.length - 1) {
				current.set(part, value);
				return;
			}
			JsonNode next = current.get(part);
			if (!(next instanceof ObjectNode)) {
				ObjectNode created = objectMapper.createObjectNode();
				current.set(part, created);
				current = created;
			}
			else {
				current = (ObjectNode) next;
			}
		}
	}

	private record AiOutputResult(boolean valid, ObjectNode state) {}

	private AsyncNodeActionWithConfig<ConversationGraphState> nodeAction(String nodeId, String type) {
		AsyncNodeActionWithConfig<ConversationGraphState> action = switch (type) {
			case "user_input" -> userInputAction();
			case "llm" -> llmAction();
			case "tool_exec" -> toolExecAction();
			case "await_children" -> awaitChildrenAction();
			default -> throw new IllegalStateException("Unsupported workflow node type: " + type);
		};
		return (state, config) -> action.apply(state, config)
			.thenApply(
				updates -> {
					Map<String, Object> mutable = ensureMutableUpdates(updates);
					applyRuntimeContext(mutable, state, nodeId, type);
					return mutable;
				}
			);
	}

	private Map<String, Object> ensureMutableUpdates(Map<String, Object> updates) {
		if (updates == null) {
			return new HashMap<>();
		}
		if (updates instanceof HashMap) {
			return updates;
		}
		return new HashMap<>(updates);
	}

	private void applyRuntimeContext(
			Map<String, Object> updates,
			ConversationGraphState state,
			String nodeId,
			String type) {
		if (updates == null || state == null) {
			return;
		}
		String currentLogicalId = state.value(ConversationGraphState.LOGICAL_NODE_ID)
			.map(Object::toString)
			.orElse(null);
		String currentInstanceId = state.value(ConversationGraphState.LOGICAL_NODE_INSTANCE_ID)
			.map(Object::toString)
			.orElse(null);
		String logicalId = resolveLogicalNodeId(type, nodeId, currentLogicalId);
		if (logicalId != null && !logicalId.equals(currentLogicalId)) {
			currentInstanceId = UUID.randomUUID().toString();
		}
		if (logicalId != null) {
			updates.put(ConversationGraphState.LOGICAL_NODE_ID, logicalId);
			if (currentInstanceId != null) {
				updates.put(ConversationGraphState.LOGICAL_NODE_INSTANCE_ID, currentInstanceId);
			}
		}
		updates.put(ConversationGraphState.RUNTIME_NODE_TYPE, type);
	}

	private String resolveLogicalNodeId(String type, String nodeId, String currentLogicalId) {
		if (type == null) {
			return currentLogicalId;
		}
		if (currentLogicalId != null && !currentLogicalId.isBlank()) {
			return currentLogicalId;
		}
		if ("llm".equals(type)) {
			return nodeId;
		}
		return currentLogicalId;
	}

	private AsyncNodeActionWithConfig<ConversationGraphState> userInputAction() {
		return (state, config) -> {
			Session session = resolveSession(state);
			if (session == null) {
				return CompletableFuture.completedFuture(Map.of());
			}
			try (SessionProcessingGate.Gate gate = processingGate.acquire(session.id)) {
				JsonNode payload = payloadFromState(state);
				if (payload == null || payload.isNull()) {
					Map<String, Object> updates = new HashMap<>();
					updates.put(ConversationGraphState.STATUS, "normal");
					updates.put(ConversationGraphState.ROUTE, "pause");
					updates.put(ConversationGraphState.PAYLOAD, AgentState.MARK_FOR_REMOVAL);
					if (isWorker(session, state)) {
						ObjectNode stateNode = readStateObject(state);
						stateNode.put(WORKER_TURN_COUNT_KEY, 0);
						updates.put(ConversationGraphState.STATE, stateNode);
					}
					PendingState.Builder pending = PendingState.builder(objectMapper);
					// No payload means we are waiting for user input.
					// Clear any pending side effects from the prior node so they are not
					// re-persisted.
					pending.sessionStatus(be.celerex.polymr.model.SessionStatus.PAUSED.name());
					pending.runStatus(WorkflowRunStatus.PAUSED.name());
					pending.locked(false);
					if (pendingApprovalPending(state)) {
						pending.toolRequests(readPendingToolRequests(state));
						pending.toolApprovalPending(true);
						String requestId = pendingApprovalRequestId(state);
						if (requestId != null) {
							pending.toolApprovalRequestId(requestId);
						}
						if (pendingApprovalBypass(state)) {
							pending.toolApprovalBypass(true);
						}
						List<String> allowScopes = pendingApprovalAllowScopes(state);
						if (!allowScopes.isEmpty()) {
							pending.toolApprovalAllowScopes(allowScopes);
						}
						List<String> denyScopes = pendingApprovalDenyScopes(state);
						if (!denyScopes.isEmpty()) {
							pending.toolApprovalDenyScopes(denyScopes);
						}
					}
					pending.apply(updates);
					return CompletableFuture.completedFuture(updates);
				}
				ObjectNode pendingState = PendingState.pendingFromState(state, objectMapper);
				boolean approvalPending = PendingState.readBoolean(pendingState, PendingState.TOOL_APPROVAL, PendingState.TOOL_APPROVAL_PENDING);
				ApprovalDecision approval = approvalDecision(payload);
				String decision = approval == null ? null : approval.decision;
				List<Map<String, Object>> approvalDeniedEvents = null;
				UUID triggeringUserId = readUserId(state);
				if (approvalPending || decision != null) {
					if ("allow".equals(decision)) {
						ObjectNode snapshot = readSnapshot(state);
						ApprovalScopePolicy policy = resolveApprovalPolicy(approval, readPendingToolRequests(state), List.of());
						boolean hasRememberedScopes = !policy.rememberAllowScopes.isEmpty()
							|| !policy.rememberDenyScopes.isEmpty();
						if (hasRememberedScopes) {
							String logicalNodeId = state.value(ConversationGraphState.LOGICAL_NODE_ID)
								.map(Object::toString)
								.orElse(null);
							List<String> logicalNodeIds = resolveLogicalNodeIds(readPendingToolRequests(state), logicalNodeId);
							snapshot = applyApprovalScopes(snapshot, policy.rememberAllowScopes, logicalNodeIds);
							snapshot = applyApprovalDenyScopes(snapshot, policy.rememberDenyScopes, logicalNodeIds);
						}
						List<Map<String, Object>> events = new ArrayList<>();
						String approvedBy = state.value(ConversationGraphState.USER_ID)
							.map(Object::toString)
							.orElse(null);
						events.add(decisionResultEvent(payload, "allow", approvedBy));
						Map<String, Object> updates = new HashMap<>();
						updates.put(ConversationGraphState.STATUS, "normal");
						updates.put(ConversationGraphState.ROUTE, "tool_exec");
						updates.put(ConversationGraphState.MCP_SNAPSHOT, snapshot);
						PendingState.Builder pending = PendingState.builder(objectMapper);
						pending.events(events);
						pending.sessionStatus(be.celerex.polymr.model.SessionStatus.ACTIVE.name());
						pending.runStatus(WorkflowRunStatus.RUNNING.name());
						pending.locked(true);
						pending.toolRequests(readPendingToolRequests(state));
						pending.toolApprovalPending(false);
						pending.toolApprovalAllowScopes(policy.allowScopes);
						pending.toolApprovalDenyScopes(policy.denyScopes);
						if (!hasRememberedScopes) {
							pending.toolApprovalBypass(true);
						}
						pending.apply(updates);
						updates.put(ConversationGraphState.PAYLOAD, AgentState.MARK_FOR_REMOVAL);
						return CompletableFuture.completedFuture(updates);
					}
					if ("deny".equals(decision)) {
						List<Map<String, Object>> denied = new ArrayList<>();
						ObjectNode snapshot = readSnapshot(state);
						ApprovalScopePolicy policy = resolveApprovalPolicy(approval, readPendingToolRequests(state), List.of());
						if (!policy.rememberDenyScopes.isEmpty()) {
							String logicalNodeId = state.value(ConversationGraphState.LOGICAL_NODE_ID)
								.map(Object::toString)
								.orElse(null);
							List<String> logicalNodeIds = resolveLogicalNodeIds(readPendingToolRequests(state), logicalNodeId);
							snapshot = applyApprovalDenyScopes(snapshot, policy.rememberDenyScopes, logicalNodeIds);
						}
						String defaultLogicalNodeId = state.value(ConversationGraphState.LOGICAL_NODE_ID)
							.map(Object::toString)
							.orElse(null);
						for (Map<String, Object> request : readPendingToolRequests(state)) {
							String logicalNodeId = readRequestText(request, "logical_node_id");
							if (logicalNodeId == null || logicalNodeId.isBlank()) {
								logicalNodeId = defaultLogicalNodeId;
							}
							ObjectNode sourceNodeSnapshot = resolveLogicalNodeSnapshot(snapshot, logicalNodeId);
							if (sourceNodeSnapshot == null) {
								sourceNodeSnapshot = resolvePrimaryNodeSnapshot(snapshot);
							}
							List<UUID> serverIds = resolveServerIds(session.workspace.id, snapshot, sourceNodeSnapshot);
							ToolCall call = parseToolCall(request, serverIds);
							if (logDeniedTools) {
								if (call != null) {
									denied.add(toolCallEvent(call));
								}
								denied.add(
									toolErrorEvent(
										call == null ? null : call.id,
										call == null ? null : call.fullName,
										call != null && call.errorMessage != null ? call.errorMessage : "Tool call denied by user.",
										call != null && call.errorCode != null ? call.errorCode : "permission_denied"
									)
								);
							}
						}
						String approvedBy = state.value(ConversationGraphState.USER_ID)
							.map(Object::toString)
							.orElse(null);
						denied.add(decisionResultEvent(payload, "deny", approvedBy));
						Map<String, Object> updates = new HashMap<>();
						if (snapshot != null && !policy.rememberDenyScopes.isEmpty()) {
							updates.put(ConversationGraphState.MCP_SNAPSHOT, snapshot);
						}
						updates.put(ConversationGraphState.STATUS, "normal");
						updates.put(ConversationGraphState.ROUTE, "pause");
						PendingState.Builder pending = PendingState.builder(objectMapper);
						pending.events(denied);
						pending.sessionStatus(be.celerex.polymr.model.SessionStatus.PAUSED.name());
						pending.runStatus(WorkflowRunStatus.PAUSED.name());
						pending.locked(false);
						pending.apply(updates);
						updates.put(ConversationGraphState.PAYLOAD, AgentState.MARK_FOR_REMOVAL);
						return CompletableFuture.completedFuture(updates);
					}
					approvalDeniedEvents = new ArrayList<>();
					String defaultLogicalNodeId = state.value(ConversationGraphState.LOGICAL_NODE_ID)
						.map(Object::toString)
						.orElse(null);
					for (Map<String, Object> request : readPendingToolRequests(state)) {
						String logicalNodeId = readRequestText(request, "logical_node_id");
						if (logicalNodeId == null || logicalNodeId.isBlank()) {
							logicalNodeId = defaultLogicalNodeId;
						}
						ObjectNode sourceNodeSnapshot = resolveLogicalNodeSnapshot(readSnapshot(state), logicalNodeId);
						if (sourceNodeSnapshot == null) {
							sourceNodeSnapshot = resolvePrimaryNodeSnapshot(readSnapshot(state));
						}
						List<UUID> serverIds = resolveServerIds(session.workspace.id, readSnapshot(state), sourceNodeSnapshot);
						ToolCall call = parseToolCall(request, serverIds);
						if (logDeniedTools) {
							if (call != null) {
								approvalDeniedEvents.add(toolCallEvent(call));
							}
							approvalDeniedEvents.add(
								toolErrorEvent(
									call == null ? null : call.id,
									call == null ? null : call.fullName,
									"Tool call denied because a message was sent while approval was pending.",
									"permission_denied"
								)
							);
						}
					}
					String approvedBy = state.value(ConversationGraphState.USER_ID).map(Object::toString).orElse(null);
					ObjectNode decisionPayload = objectMapper.createObjectNode();
					String requestId = pendingApprovalRequestId(state);
					if (requestId != null && !requestId.isBlank()) {
						decisionPayload.put("request_id", requestId);
					}
					decisionPayload.put("remember", false);
					approvalDeniedEvents.add(decisionResultEvent(decisionPayload, "deny", approvedBy));
				}
				ObjectNode messagePayload = objectMapper.createObjectNode();
				if (payload.isObject()) {
					messagePayload.setAll((ObjectNode) payload);
				}
				else if (payload.isTextual()) {
					messagePayload.put("text", payload.asText());
				}
				normalizeAttachments(messagePayload, session.workspace.id);
				List<String> attachmentErrors = validateAttachments(messagePayload);
				WorkflowRun run = checkpointService.requireRun(session);
				run.currentNode = "user_input";
				Map<String, Object> updates = new HashMap<>();
				updates.put(ConversationGraphState.STATUS, "normal");
				updates.put(ConversationGraphState.ROUTE, "default");
				updates.put(ConversationGraphState.PAYLOAD, AgentState.MARK_FOR_REMOVAL);
				PendingState.Builder pending = PendingState.builder(objectMapper);
				List<Map<String, Object>> events = new ArrayList<>();
				if (approvalDeniedEvents != null) {
					events.addAll(approvalDeniedEvents);
				}
				events.add(pendingEvent(SessionEventType.USER_MESSAGE, messagePayload, triggeringUserId));
				for (String error : attachmentErrors) {
					events.add(systemEvent(error));
				}
				pending.events(events);
				pending.sessionStatus(be.celerex.polymr.model.SessionStatus.ACTIVE.name());
				pending.runStatus(WorkflowRunStatus.RUNNING.name());
				pending.locked(true);
				pending.apply(updates);
				return CompletableFuture.completedFuture(updates);
			}
		};
	}

	private AsyncNodeActionWithConfig<ConversationGraphState> llmAction() {
		return (state, config) -> {
			Session session = resolveSession(state);
			if (session == null) {
				return CompletableFuture.completedFuture(Map.of());
			}
			SessionProcessingGate.Gate gate = processingGate.acquire(session.id);
			Span span = resolveTracer().spanBuilder("llm.stream").startSpan();
			span.setAttribute("session.id", session.id.toString());
			try {
				WorkflowRun run = checkpointService.requireRun(session);
				run.currentNode = "llm";
				run.status = WorkflowRunStatus.RUNNING;
				WorkflowDefinition definition = workflowDefinitionService.loadDefinition(run.workflowDefinition.id);
				CompletableFuture<Map<String, Object>> turnLimitFuture = new CompletableFuture<>();
				if (applyAssistantTurnLimit(session, state, turnLimitFuture)) {
					return turnLimitFuture;
				}
				StreamingChatModel model = resolveModel(session);
				if (model == null) {
					Map<String, Object> updates = new HashMap<>();
					updates.put(ConversationGraphState.ROUTE, "default");
					PendingState.Builder pending = PendingState.builder(objectMapper);
					pending.events(
						List.of(
							pendingEvent(
								SessionEventType.SYSTEM,
								objectMapper.createObjectNode().put("text", "No model configured for this session.")
							)
						)
					);
					pending.sessionStatus(be.celerex.polymr.model.SessionStatus.PAUSED.name());
					pending.runStatus(WorkflowRunStatus.PAUSED.name());
					pending.locked(false);
					pending.apply(updates);
					gate.close();
					span.end();
					return CompletableFuture.completedFuture(updates);
				}

				List<ChatMessage> history = buildHistory(session);
				ObjectNode logicalNode = resolveLogicalNodeFromJson(resolveDefinitionJson(run, definition), state);
				boolean isAiNode = logicalNode != null && "ai".equals(logicalNode.path("type").asText(null));
				ChatMessage system = isAiNode
					? buildAiSystemPrompt(session, state, definition, logicalNode)
					: buildUserSystemPrompt(session, state, definition, logicalNode);
				if (system != null) {
					history.add(0, system);
				}
				SessionEvent lastUserEvent = findLastUserEvent(session);
				LocationInfo location = takePendingLocation(session.id);
				injectSystemInfoBeforeLastUserMessage(
					history,
					lastUserEvent == null || lastUserEvent.user == null ? null : lastUserEvent.user.id,
					lastUserEvent == null ? location : firstNonNull(eventLocation(lastUserEvent), location),
					buildInjectedSystemInfoMessages(session, state, definition, logicalNode)
				);
				String logicalNodeId = state.value(ConversationGraphState.LOGICAL_NODE_ID)
					.map(Object::toString)
					.orElse(null);
				String toolSpecNodeId = logicalNodeId == null || logicalNodeId.isBlank() ? "llm" : logicalNodeId;
				List<ToolSpecification> toolSpecs = new ArrayList<>(toolSpecificationsForNode(state, session, toolSpecNodeId));
				toolSpecs.addAll(skillToolSpecifications(session, definition, logicalNode));
				ObjectNode pendingState = PendingState.pendingFromState(state, objectMapper);
				String toolFailure = PendingState.readText(pendingState, PendingState.TOOL_FAILURE);
				final ObjectNode toolFailurePayload = toolFailure != null && !toolFailure.isBlank()
					? objectMapper.createObjectNode().put("text", toolFailure)
					: null;
				if (toolFailurePayload != null) {
					history.add(SystemMessage.from(toolFailure));
				}
				CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
				StringBuilder buffer = new StringBuilder();
				ChatRequest.Builder requestBuilder = ChatRequest.builder().messages(history);
				if (!toolSpecs.isEmpty()) {
					requestBuilder.toolSpecifications(toolSpecs);
				}
				Integer maxOutputTokens = resolveMaxOutputTokens(session);
				if (maxOutputTokens != null) {
					requestBuilder.maxOutputTokens(maxOutputTokens);
				}
				ChatRequest request = requestBuilder.build();
				traceEvent(session, "info", "llm.request", "Sending request to LLM", traceDetails(request));
				callRegistry.withSession(
					session.id,
					() -> model.chat(
						request,
						new StreamingChatResponseHandler() {
							@Override
							public void onPartialResponse(String token) {
								if (isAborting(session.id)) {
									return;
								}
								if (buffer.length() == 0 && token != null && !token.isBlank()) {
									LOGGER.debugf("LLM first token session=%s length=%d", session.id, token.length());
								}
								buffer.append(token);
								broadcastToken(session, token);
							}

							@Override
							public void onCompleteResponse(ChatResponse response) {
								try {
									LOGGER.debugf(
										"LLM response handler start session=%s buffer=%d response=%s",
										session.id,
										buffer.length(),
										response == null ? "null" : "present"
									);
									traceEvent(session, "info", "llm.response", "Received response from LLM", traceDetails(response));
									boolean aborted = isAborting(session.id);
									List<Map<String, Object>> events = new ArrayList<>();
									if (toolFailurePayload != null) {
										events.add(pendingEvent(SessionEventType.SYSTEM, toolFailurePayload));
									}
									Map<String, Object> updates = new HashMap<>();
									List<ToolExecutionRequest> toolRequests = response != null && response.aiMessage() != null
										? response.aiMessage().toolExecutionRequests()
										: List.of();
									String text = response == null || response.aiMessage() == null
										? buffer.toString()
										: safeText(response.aiMessage());
									if ((text == null || text.isBlank()) && buffer.length() > 0) {
										text = buffer.toString();
									}
									boolean ignoreTools = aborted && toolRequests != null && !toolRequests.isEmpty();
									boolean emptyResponse = (text == null || text.isBlank())
										&& (toolRequests == null || toolRequests.isEmpty());
									UsageAccounting usageAccounting = applySessionPricing(session, response);
									if (emptyResponse && !aborted) {
										ObjectNode notice = objectMapper.createObjectNode();
										notice.put("text", "Assistant returned no response.");
										events.add(pendingEvent(SessionEventType.SYSTEM, notice));
										updates.put(ConversationGraphState.ROUTE, "complete");
										boolean isWorkerSession = isWorker(session, state);
										if (isWorkerSession) {
											ObjectNode stateNode = readStateObject(state);
											ObjectNode completion = readSubassistantCompletion(stateNode);
											String completionStatus = completion == null ? null : completion.path("status").asText(null);
											String feedbackStatus;
											WorkflowRunStatus runStatus;
											String message = null;
											if ("failed".equalsIgnoreCase(completionStatus)) {
												feedbackStatus = "failed";
												runStatus = WorkflowRunStatus.FAILED;
											}
											else if ("completed".equalsIgnoreCase(completionStatus)) {
												feedbackStatus = "success";
												runStatus = WorkflowRunStatus.COMPLETED;
											}
											else {
												feedbackStatus = "failed";
												runStatus = WorkflowRunStatus.FAILED;
											}
											if (completion != null && completion.hasNonNull("message")) {
												message = completion.path("message").asText(null);
											}
											if (message == null || message.isBlank()) {
												message = "No response from the worker.";
											}
											ObjectNode feedback = stateNode.putObject(WORKER_FEEDBACK_KEY);
											feedback.put("status", feedbackStatus);
											feedback.put("message", message);
											if (completion != null && completion.hasNonNull("data")) {
												feedback.set("data", completion.get("data"));
											}
											updates.put(ConversationGraphState.STATE, stateNode);
											PendingState.Builder pending = PendingState.builder(objectMapper);
											pending.events(events);
											pending.sessionStatus(be.celerex.polymr.model.SessionStatus.COMPLETED.name());
											pending.runStatus(runStatus.name());
											pending.locked(false);
											pending.apply(updates);
											future.complete(updates);
											LOGGER.debugf("LLM response handler empty response worker session=%s", session.id);
											return;
										}
										PendingState.Builder pending = PendingState.builder(objectMapper);
										pending.events(events);
										pending.sessionStatus(be.celerex.polymr.model.SessionStatus.PAUSED.name());
										pending.runStatus(WorkflowRunStatus.PAUSED.name());
										pending.locked(false);
										pending.apply(updates);
										future.complete(updates);
										LOGGER.debugf("LLM response handler empty response session=%s", session.id);
										return;
									}
									if (text != null && !text.isBlank()) {
										ObjectNode payload = objectMapper.createObjectNode();
										payload.put("text", text);
										if (response != null) {
											ObjectNode metadata = responseMetadata(session, response);
											if (metadata != null && metadata.size() > 0) {
												payload.set("metadata", metadata);
											}
										}
										applyUsageToPayload(payload, usageAccounting);
										if (aborted) {
											payload.put("aborted", true);
											if (ignoreTools) {
												payload.put("comment", "Tool calls ignored due to abort.");
											}
										}
										events.add(pendingAssistantEvent(payload, usageAccounting));
									}
									triggerAutoPruneAfterLlmResponse(session, usageAccounting);
									PendingState.Builder pending = PendingState.builder(objectMapper);
									pending.events(events);
									boolean isWorkerSession = isWorker(session, state);
									if (isWorkerSession && (toolRequests == null || toolRequests.isEmpty())) {
										ObjectNode stateNode = readStateObject(state);
										ObjectNode completion = readSubassistantCompletion(stateNode);
										String completionStatus = completion == null ? null : completion.path("status").asText(null);
										String feedbackStatus;
										WorkflowRunStatus runStatus;
										if ("failed".equalsIgnoreCase(completionStatus)) {
											feedbackStatus = "failed";
											runStatus = WorkflowRunStatus.FAILED;
										}
										else if ("completed".equalsIgnoreCase(completionStatus)) {
											feedbackStatus = "success";
											runStatus = WorkflowRunStatus.COMPLETED;
										}
										else {
											feedbackStatus = "inconclusive";
											runStatus = WorkflowRunStatus.COMPLETED;
										}
										ObjectNode feedback = stateNode.putObject(WORKER_FEEDBACK_KEY);
										feedback.put("status", feedbackStatus);
										String message = null;
										if (completion != null && completion.hasNonNull("message")) {
											message = completion.path("message").asText(null);
										}
										if (message == null || message.isBlank()) {
											message = text == null ? "" : text;
										}
										feedback.put("message", message);
										if (completion != null && completion.hasNonNull("data")) {
											feedback.set("data", completion.get("data"));
										}
										updates.put(ConversationGraphState.STATE, stateNode);
										updates.put(ConversationGraphState.ROUTE, "complete");
										pending.sessionStatus(be.celerex.polymr.model.SessionStatus.COMPLETED.name());
										pending.runStatus(runStatus.name());
										pending.locked(false);
										if (aborted) {
											aborting.put(session.id, false);
										}
										pending.apply(updates);
										future.complete(updates);
										return;
									}
									updates.put(ConversationGraphState.STATUS, "normal");
									aborted = isAborting(session.id);
									ignoreTools = aborted && toolRequests != null && !toolRequests.isEmpty();
									if (ignoreTools) {
										updates.put(ConversationGraphState.ROUTE, "default");
										pending.sessionStatus(be.celerex.polymr.model.SessionStatus.PAUSED.name());
										pending.runStatus(WorkflowRunStatus.PAUSED.name());
										pending.locked(false);
										aborting.put(session.id, false);
									}
									else if (toolRequests != null && !toolRequests.isEmpty()) {
										String logicalNodeId = state.value(ConversationGraphState.LOGICAL_NODE_ID).map(Object::toString).orElse(null);
										String logicalNodeInstanceId = state.value(ConversationGraphState.LOGICAL_NODE_INSTANCE_ID).map(Object::toString).orElse(null);
										updates.put(ConversationGraphState.ROUTE, "tool_exec");
										pending.toolRequests(toPendingToolRequests(toolRequests, logicalNodeId, logicalNodeInstanceId));
										pending.toolUsage(pendingToolUsagePayload(usageAccounting));
										pending.sessionStatus(be.celerex.polymr.model.SessionStatus.ACTIVE.name());
										pending.runStatus(WorkflowRunStatus.RUNNING.name());
										pending.locked(true);
									}
									else {
										boolean advanced = false;
										if (logicalNode != null && "ai".equals(logicalNode.path("type").asText(null))) {
											AiOutputResult outputResult = handleAiOutput(state, logicalNode, text);
											if (outputResult.valid) {
												if (outputResult.state != null) {
													ObjectNode nextState = outputResult.state instanceof ObjectNode node ? node.deepCopy() : outputResult.state.deepCopy();
													preserveSubassistantMarkers(state, nextState);
													updates.put(ConversationGraphState.STATE, nextState);
												}
												updateLogicalNodeOnCompletion(resolveDefinitionJson(run, definition), state, updates);
												if (isAborting(session.id)) {
													updates.put(ConversationGraphState.ROUTE, "default");
													pending.sessionStatus(be.celerex.polymr.model.SessionStatus.PAUSED.name());
													pending.runStatus(WorkflowRunStatus.PAUSED.name());
													pending.locked(false);
													aborting.put(session.id, false);
												}
												else {
													updates.put(ConversationGraphState.ROUTE, "continue");
													pending.sessionStatus(be.celerex.polymr.model.SessionStatus.ACTIVE.name());
													pending.runStatus(WorkflowRunStatus.RUNNING.name());
													pending.locked(true);
													advanced = true;
												}
											}
											else {
												ObjectNode notice = objectMapper.createObjectNode();
												notice.put("text", "AI step needs more input to continue.");
												events.add(pendingEvent(SessionEventType.SYSTEM, notice));
											}
										}
										if (!advanced) {
											updates.put(ConversationGraphState.ROUTE, "default");
											pending.sessionStatus(be.celerex.polymr.model.SessionStatus.PAUSED.name());
											pending.runStatus(WorkflowRunStatus.PAUSED.name());
											pending.locked(false);
											if (aborted) {
												aborting.put(session.id, false);
											}
										}
									}
									pending.apply(updates);
									future.complete(updates);
									LOGGER.debugf(
										"LLM response handler complete session=%s route=%s events=%d",
										session.id,
										updates.get(ConversationGraphState.ROUTE),
										events.size()
									);
								}
								catch (Exception error) {
									LOGGER.errorf(error, "LLM response handling failed for session %s", session.id);
									Map<String, Object> updates = new HashMap<>();
									updates.put(ConversationGraphState.ROUTE, "default");
									updates.put(ConversationGraphState.STATUS, "normal");
									PendingState.Builder pending = PendingState.builder(objectMapper);
									ObjectNode payload = objectMapper.createObjectNode();
									String message = error.getMessage() == null ? "Unknown error" : error.getMessage();
									payload.put("text", "Assistant error: " + message);
									payload.put("error_type", error.getClass().getSimpleName());
									pending.events(List.of(pendingEvent(SessionEventType.SYSTEM, payload)));
									pending.sessionStatus(be.celerex.polymr.model.SessionStatus.PAUSED.name());
									pending.runStatus(WorkflowRunStatus.PAUSED.name());
									pending.locked(false);
									pending.apply(updates);
									Map<String, Object> errorPayload = new HashMap<>();
									errorPayload.put("message", "LLM response handling failed.");
									errorPayload.put("session_id", session.id.toString());
									errorPayload.put("exception", error.getClass().getSimpleName());
									socketManager.broadcastToSession(
										session.id,
										new WorkspaceSocketEvent("session.error", session.workspace.id, session.id, errorPayload)
									);
									future.complete(updates);
								}
								finally {
									gate.close();
									span.end();
								}
							}

							@Override
							public void onError(Throwable error) {
								try {
									LOGGER.errorf(
										error,
										"LLM response handler error session=%s error=%s",
										session.id,
										error == null ? "null" : error.getClass().getSimpleName()
									);
									boolean aborted = session != null && isAborting(session.id);
									ObjectNode payload = null;
									if (!aborted) {
										payload = objectMapper.createObjectNode();
										String message = error == null || error.getMessage() == null ? "Unknown error" : error.getMessage();
										payload.put("text", "Assistant error: " + message);
										if (error != null) {
											payload.put("error_type", error.getClass().getSimpleName());
										}
									}
									else {
										payload = objectMapper.createObjectNode();
										payload.put("text", "Request aborted.");
										payload.put("aborted", true);
									}
									Map<String, Object> updates = new HashMap<>();
									updates.put(ConversationGraphState.ROUTE, "default");
									updates.put(ConversationGraphState.STATUS, "normal");
									PendingState.Builder pending = PendingState.builder(objectMapper);
									if (payload != null) {
										pending.events(List.of(pendingEvent(SessionEventType.SYSTEM, payload)));
									}
									pending.sessionStatus(be.celerex.polymr.model.SessionStatus.PAUSED.name());
									pending.runStatus(WorkflowRunStatus.PAUSED.name());
									pending.locked(false);
									pending.apply(updates);
									if (aborted) {
										aborting.put(session.id, false);
									}
									future.complete(updates);
								}
								finally {
									gate.close();
									span.end();
								}
							}
						}
					)
				);
				return future;
			}
			catch (Exception error) {
				ObjectNode payload = objectMapper.createObjectNode();
				String message = error.getMessage() == null ? "Unknown error" : error.getMessage();
				payload.put("text", "Assistant error: " + message);
				payload.put("error_type", error.getClass().getSimpleName());
				Map<String, Object> updates = new HashMap<>();
				updates.put(ConversationGraphState.ROUTE, "default");
				updates.put(ConversationGraphState.STATUS, "normal");
				PendingState.Builder pending = PendingState.builder(objectMapper);
				pending.events(List.of(pendingEvent(SessionEventType.SYSTEM, payload)));
				pending.sessionStatus(be.celerex.polymr.model.SessionStatus.PAUSED.name());
				pending.runStatus(WorkflowRunStatus.PAUSED.name());
				pending.locked(false);
				pending.apply(updates);
				gate.close();
				span.end();
				return CompletableFuture.completedFuture(updates);
			}
		};
	}

	private AsyncNodeActionWithConfig<ConversationGraphState> awaitChildrenAction() {
		return (state, config) -> {
			Session session = resolveSession(state);
			if (session == null) {
				return CompletableFuture.completedFuture(Map.of(ConversationGraphState.ROUTE, "default"));
			}
			try (SessionProcessingGate.Gate gate = processingGate.acquire(session.id)) {
				ObjectNode stateNode = readStateObject(state);
				List<UUID> childRunIds = readChildRunIds(stateNode);
				Map<String, Object> updates = new HashMap<>();
				PendingState.Builder pending = PendingState.builder(objectMapper);
				if (childRunIds.isEmpty()) {
					updates.put(ConversationGraphState.ROUTE, "default");
					pending.sessionStatus(be.celerex.polymr.model.SessionStatus.ACTIVE.name());
					pending.runStatus(WorkflowRunStatus.RUNNING.name());
					pending.locked(true);
					pending.apply(updates);
					return CompletableFuture.completedFuture(updates);
				}
				List<WorkflowRun> runs = entityManager.createQuery("select r from WorkflowRun r where r.id in :runIds", WorkflowRun.class)
					.setParameter("runIds", childRunIds)
					.getResultList();
				boolean allComplete = true;
				Map<UUID, WorkflowRun> runById = new HashMap<>();
				for (WorkflowRun run : runs) {
					if (run != null && run.id != null) {
						runById.put(run.id, run);
					}
					if (run == null
							|| (run.status != WorkflowRunStatus.COMPLETED && run.status != WorkflowRunStatus.FAILED)) {
						allComplete = false;
					}
				}
				if (!allComplete || runs.size() < childRunIds.size()) {
					updates.put(ConversationGraphState.ROUTE, "pause");
					updates.put(ConversationGraphState.STATE, stateNode);
					pending.sessionStatus(be.celerex.polymr.model.SessionStatus.ACTIVE.name());
					pending.runStatus(WorkflowRunStatus.PAUSED.name());
					pending.locked(true);
					pending.apply(updates);
					return CompletableFuture.completedFuture(updates);
				}
				List<Map<String, Object>> completionEvents = new ArrayList<>();
				List<String> completedToolCalls = readCompletedToolCalls(stateNode);
				java.util.Set<String> completedToolCallSet = new java.util.HashSet<>(completedToolCalls);
				Map<String, List<ObjectNode>> groups = groupChildrenByToolCall(stateNode);
				for (Map.Entry<String, List<ObjectNode>> entry : groups.entrySet()) {
					String toolCallId = entry.getKey();
					if (toolCallId.isBlank() || completedToolCallSet.contains(toolCallId)) {
						continue;
					}
					boolean groupComplete = true;
					for (ObjectNode child : entry.getValue()) {
						UUID runId = readUuid(child.get("run_id"));
						WorkflowRun run = runId == null ? null : runById.get(runId);
						if (run == null
								|| (run.status != WorkflowRunStatus.COMPLETED
										&& run.status != WorkflowRunStatus.FAILED)) {
							groupComplete = false;
							break;
						}
					}
					if (!groupComplete) {
						continue;
					}
					ObjectNode resultPayload = buildWorkerToolResult(entry.getValue(), runById);
					ObjectNode toolPayload = objectMapper.createObjectNode();
					toolPayload.put("tool_call_id", toolCallId);
					toolPayload.put("tool_name", SPAWN_CHILDREN_TOOL);
					toolPayload.set("result", resultPayload);
					completionEvents.add(pendingEvent(SessionEventType.TOOL_RESULT, toolPayload));
					addCompletedToolCall(stateNode, toolCallId);
				}
				ArrayNode nextChildren = objectMapper.createArrayNode();
				for (Map.Entry<String, List<ObjectNode>> entry : groups.entrySet()) {
					String toolCallId = entry.getKey();
					if (toolCallId.isBlank() || !completedToolCallSet.contains(toolCallId)) {
						for (ObjectNode child : entry.getValue()) {
							nextChildren.add(child);
						}
					}
				}
				if (nextChildren.isEmpty()) {
					stateNode.remove(STATE_CHILDREN_KEY);
				}
				else {
					stateNode.set(STATE_CHILDREN_KEY, nextChildren);
				}
				updates.put(ConversationGraphState.ROUTE, "default");
				updates.put(ConversationGraphState.STATE, stateNode);
				if (!completionEvents.isEmpty()) {
					pending.events(completionEvents);
				}
				pending.sessionStatus(be.celerex.polymr.model.SessionStatus.ACTIVE.name());
				pending.runStatus(WorkflowRunStatus.RUNNING.name());
				pending.locked(true);
				pending.apply(updates);
				return CompletableFuture.completedFuture(updates);
			}
		};
	}

	private AsyncNodeActionWithConfig<ConversationGraphState> toolExecAction() {
		return (state, config) -> {
			Session session = resolveSession(state);
			boolean aborted = session != null && isAborting(session.id);
			if (session != null) {
				WorkflowRun run = checkpointService.requireRun(session);
				run.currentNode = "tool_exec";
				run.status = WorkflowRunStatus.RUNNING;
			}
			if (session == null) {
				return CompletableFuture.completedFuture(Map.of(ConversationGraphState.ROUTE, "default"));
			}
			try (SessionProcessingGate.Gate gate = processingGate.acquire(session.id)) {
				if (isAborting(session.id)) {
					Map<String, Object> updates = new HashMap<>();
					updates.put(ConversationGraphState.ROUTE, "default");
					updates.put(ConversationGraphState.STATUS, "normal");
					PendingState.Builder pending = PendingState.builder(objectMapper);
					ObjectNode payload = objectMapper.createObjectNode();
					payload.put("text", "Request aborted.");
					payload.put("aborted", true);
					pending.events(List.of(pendingEvent(SessionEventType.SYSTEM, payload)));
					pending.sessionStatus(be.celerex.polymr.model.SessionStatus.PAUSED.name());
					pending.runStatus(WorkflowRunStatus.PAUSED.name());
					pending.locked(false);
					pending.apply(updates);
					aborting.put(session.id, false);
					return CompletableFuture.completedFuture(updates);
				}
				List<Map<String, Object>> pendingRequests = readPendingToolRequests(state);
				if (pendingRequests.isEmpty()) {
					Map<String, Object> updates = new HashMap<>();
					updates.put(ConversationGraphState.ROUTE, "default");
					PendingState.Builder pending = PendingState.builder(objectMapper);
					pending.sessionStatus(be.celerex.polymr.model.SessionStatus.PAUSED.name());
					pending.runStatus(WorkflowRunStatus.PAUSED.name());
					pending.locked(false);
					if (aborted) {
						aborting.put(session.id, false);
					}
					pending.apply(updates);
					return CompletableFuture.completedFuture(updates);
				}
				WorkflowRun run = checkpointService.requireRun(session);
				WorkflowDefinition definition = workflowDefinitionService.loadDefinition(run.workflowDefinition.id);
				ObjectNode logicalNode = resolveLogicalNodeFromJson(resolveDefinitionJson(run, definition), state);
				boolean allowSkillTools = shouldAllowSkillTools(definition, logicalNode);
				Assistant skillAssistant = allowSkillTools ? resolveAssistantForNode(session, logicalNode, false) : null;
				List<Skill> assistantSkills = allowSkillTools
					? loadAssistantSkills(session, skillAssistant).stream().filter(this::isSkillRenderable).toList()
					: List.of();
				Map<String, Skill> skillByName = buildSkillNameMap(assistantSkills);
				int maxToolCalls = logicalNode == null ? -1 : logicalNode.path("max_tool_calls").asInt(-1);
				String instanceId = state.value(ConversationGraphState.LOGICAL_NODE_INSTANCE_ID)
					.map(Object::toString)
					.orElse(null);
				ObjectNode internalUpdate = null;
				ObjectNode completionUpdate = null;
				if (maxToolCalls > 0 && instanceId != null) {
					int currentCount = readToolCallCount(state, instanceId);
					int nextCount = currentCount + pendingRequests.size();
					if (nextCount > maxToolCalls) {
						Map<String, Object> updates = new HashMap<>();
						updates.put(ConversationGraphState.ROUTE, "needs_approval");
						PendingState.Builder pending = PendingState.builder(objectMapper);
						pending.events(List.of(systemEvent("AI step reached its tool call limit. Please review or provide guidance.")));
						pending.sessionStatus(be.celerex.polymr.model.SessionStatus.PAUSED.name());
						pending.runStatus(WorkflowRunStatus.PAUSED.name());
						pending.locked(false);
						pending.toolRequests(List.of());
						pending.apply(updates);
						return CompletableFuture.completedFuture(updates);
					}
					ObjectNode internal = readInternalObject(state);
					if (internal == null) {
						internal = objectMapper.createObjectNode();
					}
					ObjectNode counts = internal.get("tool_call_counts") instanceof ObjectNode node
						? node
						: internal.putObject("tool_call_counts");
					counts.put(instanceId, nextCount);
					internalUpdate = internal;
				}
				String approvalDecision = pendingApprovalDecision(state);
				boolean approvalPending = pendingApprovalPending(state);
				boolean bypassApproval = pendingApprovalBypass(state);
				List<String> decisionAllowScopes = pendingApprovalAllowScopes(state);
				List<String> decisionDenyScopes = pendingApprovalDenyScopes(state);
				boolean hasDecisionScopes = !decisionAllowScopes.isEmpty() || !decisionDenyScopes.isEmpty();
				if (approvalPending && (approvalDecision == null || !"allow".equalsIgnoreCase(approvalDecision))) {
					Map<String, Object> updates = new HashMap<>();
					updates.put(ConversationGraphState.ROUTE, "needs_approval");
					if (internalUpdate != null) {
						updates.put(ConversationGraphState.INTERNAL, internalUpdate);
					}
					PendingState.Builder pending = PendingState.builder(objectMapper);
					pending.toolRequests(pendingRequests);
					pending.toolApprovalPending(true);
					String requestId = pendingApprovalRequestId(state);
					if (requestId != null) {
						pending.toolApprovalRequestId(requestId);
					}
					if (bypassApproval) {
						pending.toolApprovalBypass(true);
					}
					if (hasDecisionScopes) {
						pending.toolApprovalAllowScopes(decisionAllowScopes);
						pending.toolApprovalDenyScopes(decisionDenyScopes);
					}
					pending.apply(updates);
					return CompletableFuture.completedFuture(updates);
				}
				boolean isConversation = workflowDefinitionService.isConversationDefinition(run.workflowDefinition);
				boolean allowToolDecisions = isConversation || !limitToolDecisionsToConversation;
				UsageAccounting pendingToolUsage = readPendingToolUsage(state);
				ObjectNode snapshot = readSnapshot(state);
				String defaultLogicalNodeId = state.value(ConversationGraphState.LOGICAL_NODE_ID)
					.map(Object::toString)
					.orElse(null);
				SessionEvent lastUserEvent = findLastUserEvent(session);
				UUID userId = lastUserEvent == null || lastUserEvent.user == null ? readUserId(state) : lastUserEvent.user.id;

				List<Map<String, Object>> events = new ArrayList<>();
				List<Map<String, Object>> deniedRequests = new ArrayList<>();
				boolean hadToolCall = false;
				boolean hadToolFailure = false;
				boolean abortedDuringTools = false;
				boolean snapshotUpdated = false;
				boolean isWorker = isWorker(session, state);
				boolean enforceWorkerToolList = isWorker && isWorkerToolListLeading();
				List<String> workerAllowedTools = readWorkerAllowedTools(state);
				List<ObjectNode> spawnedChildren = new ArrayList<>();
				boolean sawCompletionToolCall = false;
				for (Map<String, Object> request : pendingRequests) {
					if (aborted) {
						abortedDuringTools = true;
						break;
					}
					String logicalNodeId = readRequestText(request, "logical_node_id");
					if (logicalNodeId == null || logicalNodeId.isBlank()) {
						logicalNodeId = defaultLogicalNodeId;
					}
					String toolName = readRequestText(request, "name");
					if (enforceWorkerToolList) {
						if (toolName == null || !workerAllowedTools.contains(toolName)) {
							String requestId = readRequestText(request, "id");
							String argumentsText = readRequestText(request, "arguments");
							JsonNode argumentsNode = parseArgumentsNode(argumentsText);
							ToolCall deniedCall = new ToolCall(requestId, toolName, null, toolName, argumentsText, argumentsNode, null, null);
							events.add(toolCallEvent(deniedCall, null, pendingToolUsage));
							pendingToolUsage = null;
							events.add(toolErrorEvent(requestId, toolName, "Tool not allowed for this worker", "tool_denied"));
							hadToolFailure = true;
							continue;
						}
					}
					if (isSkillToolName(toolName)) {
						String resolvedNodeId = resolveSkillNodeId(state, definition, logicalNode);
						String requestId = readRequestText(request, "id");
						String argumentsText = readRequestText(request, "arguments");
						JsonNode argumentsNode = parseArgumentsNode(argumentsText);
						ToolCall call = new ToolCall(requestId, toolName, null, toolName, argumentsText, argumentsNode, null, null);
						events.add(toolCallEvent(call, null, pendingToolUsage));
						pendingToolUsage = null;
						hadToolCall = true;
						if (!allowSkillTools || assistantSkills.isEmpty()) {
							events.add(toolErrorEvent(call.id, call.fullName, "No skills available", "skill_unavailable"));
							continue;
						}
						ObjectNode internal = prepareInternalUpdate(internalUpdate, state);
						internalUpdate = internal;
						if (SKILL_ACTIVATE_TOOL.equals(toolName)) {
							String skillName = argumentsNode == null ? null : argumentsNode.path("skill_name").asText(null);
							if (skillName != null) {
								skillName = skillName.trim().toLowerCase();
							}
							Skill skill = skillName == null ? null : skillByName.get(skillName);
							if (skill == null) {
								events.add(toolErrorEvent(call.id, call.fullName, "Skill not found", "skill_not_found"));
								continue;
							}
							updateActiveSkill(internal, resolvedNodeId, skill.id);
							events.add(internalToolResultEvent(call.id, call.fullName, "Activated skill " + skillName + "."));
						}
						else if (SKILL_DEACTIVATE_TOOL.equals(toolName)) {
							updateActiveSkill(internal, resolvedNodeId, null);
							events.add(internalToolResultEvent(call.id, call.fullName, "Deactivated skill."));
						}
						continue;
					}
					ObjectNode sourceNodeSnapshot = resolveLogicalNodeSnapshot(snapshot, logicalNodeId);
					if (sourceNodeSnapshot == null) {
						sourceNodeSnapshot = resolvePrimaryNodeSnapshot(snapshot);
					}
					List<UUID> serverIds = resolveServerIds(session.workspace.id, snapshot, sourceNodeSnapshot);
					List<UUID> tagIds = readTagIds(sourceNodeSnapshot);
					List<String> allowScopes = readScopeList(sourceNodeSnapshot, "allow_scopes");
					List<String> denyScopes = readScopeList(sourceNodeSnapshot, "deny_scopes");
					boolean requiresExplicitApproval = allowScopes.isEmpty() && denyScopes.isEmpty();
					ToolCall call = parseToolCall(request, serverIds);
					if (call == null) {
						events.add(toolErrorEvent(null, null, "Invalid tool request", "tool_invalid"));
						continue;
					}
					if (call.errorMessage != null) {
						events.add(toolCallEvent(call, null, pendingToolUsage));
						pendingToolUsage = null;
						events.add(toolErrorEvent(call.id, call.fullName, call.errorMessage, call.errorCode));
						continue;
					}
					McpServerTool tool = loadTool(call.serverId, call.toolName);
					if (tool == null || tool.deleted || tool.disabled) {
						events.add(toolCallEvent(call, null, pendingToolUsage));
						pendingToolUsage = null;
						events.add(toolErrorEvent(call.id, call.fullName, "Tool not available", "tool_unavailable"));
						continue;
					}
					List<String> toolScopes = resolveToolScopes(tool);
					boolean toolDynamicScopes = tool.dynamicScopes;
					boolean autoApprovedTool = isAutoApprovedCanvasTool(tool, call);
					List<String> effectiveAllowScopes = hasDecisionScopes ? mergeScopes(allowScopes, decisionAllowScopes) : allowScopes;
					List<String> effectiveDenyScopes = hasDecisionScopes ? mergeScopes(denyScopes, decisionDenyScopes) : denyScopes;
					ToolScopeDecision decision = evaluateToolDecision(toolScopes, effectiveAllowScopes, effectiveDenyScopes);
					if (autoApprovedTool && decision.decision != ToolDecision.DENY) {
						decision = ToolScopeDecision.allow();
					}
					if (requiresExplicitApproval
							&& !bypassApproval
							&& !hasDecisionScopes
							&& !toolDynamicScopes
							&& !autoApprovedTool) {
						decision = ToolScopeDecision.dynamic(toolScopes);
					}
					if (toolDynamicScopes && decision.decision == ToolDecision.DYNAMIC) {
						decision = ToolScopeDecision.allow();
					}
					if (bypassApproval && decision.decision != ToolDecision.DENY) {
						decision = ToolScopeDecision.allow();
					}
					if (decision.decision == ToolDecision.DENY) {
						if (allowToolDecisions) {
							String summary = resolveToolCallSummary(tool, call.argumentsNode);
							events.add(toolCallEvent(call, summary, pendingToolUsage));
							pendingToolUsage = null;
							events.add(toolErrorEvent(call.id, call.fullName, "Tool denied by user", "permission_denied"));
							continue;
						}
						String summary = resolveToolCallSummary(tool, call.argumentsNode);
						events.add(toolCallEvent(call, summary, pendingToolUsage));
						pendingToolUsage = null;
						events.add(toolErrorEvent(call.id, call.fullName, "Tool denied by user", "permission_denied"));
						continue;
					}
					if (decision.decision == ToolDecision.DYNAMIC) {
						if (allowToolDecisions) {
							deniedRequests.add(request);
							continue;
						}
						continue;
					}
					ObjectNode meta = objectMapper.createObjectNode();
					ObjectNode policy = policyResolverService.resolve(call.serverId, tagIds);
					if (policy != null && !policy.isEmpty()) {
						meta.set("policy", policy);
					}
					java.util.Set<String> declaredScopes = declaredScopesForServers(serverIds);
					List<String> allowedMetaScopes = mergeScopes(
						relevantScopes(toolScopes, effectiveAllowScopes),
						dynamicChildScopes(toolScopes, effectiveAllowScopes, declaredScopes)
					);
					if (hasDecisionScopes && !decisionAllowScopes.isEmpty()) {
						allowedMetaScopes = mergeScopes(allowedMetaScopes, decisionAllowScopes);
					}
					if (!allowedMetaScopes.isEmpty()) {
						ArrayNode allowedArray = meta.putArray("allowed_scopes");
						allowedMetaScopes.forEach(allowedArray::add);
					}
					List<String> deniedMetaScopes = mergeScopes(
						relevantScopes(toolScopes, effectiveDenyScopes),
						dynamicChildScopes(toolScopes, effectiveDenyScopes, declaredScopes)
					);
					if (!deniedMetaScopes.isEmpty()) {
						ArrayNode deniedArray = meta.putArray("denied_scopes");
						deniedMetaScopes.forEach(deniedArray::add);
					}
					JsonNode result;
					String summary = resolveToolCallSummary(tool, call.argumentsNode);
					events.add(toolCallEvent(call, summary, pendingToolUsage));
					pendingToolUsage = null;
					sessionActivityService.update(session, toolActivity(call, summary));
					try {
						if (isSpawnChildrenTool(call) && call.argumentsNode instanceof ObjectNode argumentsObject) {
							argumentsObject.put("tool_call_id", call.id);
							if (!decisionAllowScopes.isEmpty()) {
								ArrayNode approvedScopes = argumentsObject.putArray("approved_scopes");
								decisionAllowScopes.forEach(approvedScopes::add);
							}
						}
						result = callTool(session, call, call.argumentsNode, meta, userId, tagIds);
					}
					catch (RuntimeException ex) {
						ToolFailure failure = parseToolFailure(ex);
						if (failure != null) {
							if (failure.requestedScopes() != null
									&& !failure.requestedScopes().isEmpty()
									&& allowToolDecisions) {
								Map<String, Object> requested = new HashMap<>(request);
								requested.put("requested_scopes", failure.requestedScopes());
								if (failure.preview()) {
									requested.put("preview", true);
								}
								if (failure.uiResourceUri() != null && !failure.uiResourceUri().isBlank()) {
									requested.put("preview_review_uri", failure.uiResourceUri());
								}
								if (failure.diffUri() != null && !failure.diffUri().isBlank()) {
									requested.put("preview_diff_uri", failure.diffUri());
								}
								if (failure.structuredContent() != null && !failure.structuredContent().isNull()) {
									requested.put("structuredContent", failure.structuredContent());
								}
								deniedRequests.add(requested);
								hadToolFailure = true;
								continue;
							}
							events.add(toolErrorEvent(call.id, call.fullName, failure.message(), failure.code()));
							hadToolFailure = true;
							continue;
						}
						String message = "Tool call failed: " + ex.getMessage();
						events.add(toolErrorEvent(call.id, call.fullName, message, "tool_error"));
						hadToolFailure = true;
						continue;
					}
					if (isSpawnChildrenTool(call)) {
						spawnedChildren.addAll(extractChildRunRefs(result));
					}
					else {
						events.add(toolResultEvent(session, call, result, tagIds));
					}
					boolean isCompletionTool = COMPLETE_GOAL_TOOL.equals(call.toolName)
						|| FAIL_GOAL_TOOL.equals(call.toolName)
						|| COMPLETE_GOAL_TOOL.equals(call.fullName)
						|| FAIL_GOAL_TOOL.equals(call.fullName);
					if (isCompletionTool) {
						sawCompletionToolCall = true;
					}
					if (isWorker && isCompletionTool) {
						ObjectNode stateNode = completionUpdate == null ? readStateObject(state) : completionUpdate;
						ObjectNode completion = stateNode.putObject(WORKER_COMPLETION_KEY);
						completion.put(
							"status",
							FAIL_GOAL_TOOL.equals(call.toolName) || FAIL_GOAL_TOOL.equals(call.fullName)
								? "failed"
								: "completed"
						);
						if (call.argumentsNode != null && call.argumentsNode.isObject()) {
							JsonNode message = call.argumentsNode.get("message");
							if (message != null && message.isTextual()) {
								completion.set("message", message);
							}
							JsonNode data = call.argumentsNode.get("data");
							if (data != null && !data.isNull()) {
								completion.set("data", data);
							}
						}
						completionUpdate = stateNode;
					}
					hadToolCall = true;
					if (be.celerex.polymr.mcp.VirtualMcpService.TOOL_ACTIVATE_MCP_SERVER.equals(call.toolName)) {
						snapshotUpdated |= addActivatedServer(snapshot, result);
					}
					if (snapshot != null && sourceNodeSnapshot != null && call.fullName != null) {
						snapshotUpdated |= addUsedTool(snapshot, sourceNodeSnapshot, call.fullName);
					}
					if (isAborting(session.id)) {
						abortedDuringTools = true;
						break;
					}
				}

				if (!deniedRequests.isEmpty() && allowToolDecisions) {
					String requestId = UUID.randomUUID().toString();
					ObjectNode decisionPayload = objectMapper.createObjectNode();
					decisionPayload.put("request_id", requestId);
					decisionPayload.put("message", "Tool execution requires approval.");
					ArrayNode requestsArray = decisionPayload.putArray("requests");
					for (Iterator<Map<String, Object>> requestIterator = deniedRequests.iterator(); requestIterator.hasNext();) {
						Map<String, Object> request = requestIterator.next();
						String logicalNodeId = readRequestText(request, "logical_node_id");
						if (logicalNodeId == null || logicalNodeId.isBlank()) {
							logicalNodeId = defaultLogicalNodeId;
						}
						ObjectNode sourceNodeSnapshot = resolveLogicalNodeSnapshot(snapshot, logicalNodeId);
						if (sourceNodeSnapshot == null) {
							sourceNodeSnapshot = resolvePrimaryNodeSnapshot(snapshot);
						}
						List<UUID> serverIds = resolveServerIds(session.workspace.id, snapshot, sourceNodeSnapshot);
						List<UUID> tagIds = readTagIds(sourceNodeSnapshot);
						List<String> allowScopes = readScopeList(sourceNodeSnapshot, "allow_scopes");
						List<String> denyScopes = readScopeList(sourceNodeSnapshot, "deny_scopes");
						ToolCall call = parseToolCall(request, serverIds);
						if (call == null) {
							continue;
						}
						ObjectNode entry = requestsArray.addObject();
						entry.put("id", call.id);
						entry.put("tool_name", call.fullName);
						entry.set("arguments", call.argumentsNode);
						if (call.errorMessage != null) {
							entry.put("error", call.errorMessage);
							continue;
						}
						McpServerTool tool = loadTool(call.serverId, call.toolName);
						if (tool == null || tool.deleted || tool.disabled) {
							entry.put("error", "Tool not available");
							continue;
						}
						String summary = resolveToolCallSummary(tool, call.argumentsNode);
						if (summary != null && !summary.isBlank()) {
							entry.put("summary", summary);
						}
						String inputTemplate = renderToolTemplate(tool.inputTemplate, call.argumentsNode, null);
						if (inputTemplate != null && !inputTemplate.isBlank()) {
							entry.put("input_template", inputTemplate);
						}
						List<String> toolScopes = resolveToolScopes(tool);
						List<String> requestedScopes = readRequestScopes(request, "requested_scopes");
						List<String> promptScopes;
						if (!requestedScopes.isEmpty()) {
							promptScopes = requestedScopes;
						}
						else {
							ToolScopeDecision decision = evaluateToolDecision(toolScopes, allowScopes, denyScopes);
							List<String> dynamicScopes = decision.dynamicScopes;
							promptScopes = dynamicScopes.isEmpty() ? toolScopes : dynamicScopes;
						}
						ArrayNode scopeArray = entry.putArray("scopes");
						promptScopes.forEach(scopeArray::add);
						List<String> scopeOptions = scopeOptions(promptScopes);
						ArrayNode optionArray = entry.putArray("scope_options");
						scopeOptions.forEach(optionArray::add);
						entry.put("preview_supported", tool.previewSupported);
						boolean previewMetaHandled = false;
						boolean errorStructuredContentHandled = false;
						String errorReviewUri = readRequestText(request, "preview_review_uri");
						StoredMetaResource review = storeMetaResource(session, call, errorReviewUri, null, tagIds);
						if (review != null) {
							entry.put("review_uri", review.uri());
							if (review.mimeType() != null && !review.mimeType().isBlank()) {
								entry.put("review_mime_type", review.mimeType());
							}
							previewMetaHandled = true;
						}
						JsonNode structuredContent = readRequestStructuredContent(request, "structuredContent");
						if (structuredContent != null && !structuredContent.isNull()) {
							ObjectNode preview = objectMapper.createObjectNode();
							ObjectNode previewResult = preview.putObject("result");
							previewResult.set("structuredContent", structuredContent);
							if (errorReviewUri != null && !errorReviewUri.isBlank()) {
								ObjectNode meta = previewResult.putObject("_meta");
								meta.putObject("ui").put("resourceUri", errorReviewUri);
							}
							entry.set("preview", preview);
							errorStructuredContentHandled = true;
						}
						String errorDiffUri = readRequestText(request, "preview_diff_uri");
						StoredMetaResource diff = storeMetaResource(session, call, errorDiffUri, null, tagIds);
						if (diff != null) {
							entry.put("diff_uri", diff.uri());
							if (diff.mimeType() != null && !diff.mimeType().isBlank()) {
								entry.put("diff_mime_type", diff.mimeType());
							}
							previewMetaHandled = true;
						}
						if (tool.previewSupported && !previewMetaHandled && !errorStructuredContentHandled) {
							ObjectNode meta = objectMapper.createObjectNode();
							meta.put("preview", true);
							ObjectNode policy = policyResolverService.resolve(call.serverId, tagIds);
							if (policy != null && !policy.isEmpty()) {
								meta.set("policy", policy);
							}
							java.util.Set<String> declaredScopes = declaredScopesForServers(serverIds);
							List<String> previewAllowScopes = hasDecisionScopes ? mergeScopes(allowScopes, decisionAllowScopes) : allowScopes;
							List<String> previewDenyScopes = hasDecisionScopes ? mergeScopes(denyScopes, decisionDenyScopes) : denyScopes;
							List<String> allowedMetaScopes = mergeScopes(
								relevantScopes(toolScopes, previewAllowScopes),
								dynamicChildScopes(toolScopes, previewAllowScopes, declaredScopes)
							);
							if (!allowedMetaScopes.isEmpty()) {
								ArrayNode allowedArray = meta.putArray("allowed_scopes");
								allowedMetaScopes.forEach(allowedArray::add);
							}
							List<String> deniedMetaScopes = mergeScopes(
								relevantScopes(toolScopes, previewDenyScopes),
								dynamicChildScopes(toolScopes, previewDenyScopes, declaredScopes)
							);
							if (!deniedMetaScopes.isEmpty()) {
								ArrayNode deniedArray = meta.putArray("denied_scopes");
								deniedMetaScopes.forEach(deniedArray::add);
							}
							try {
								JsonNode preview = callTool(session, call, call.argumentsNode, meta, userId, tagIds);
								if (preview != null) {
									entry.set("preview", preview);
									String previewText = extractToolText(preview);
									if (previewText != null && !previewText.isBlank()) {
										entry.put("preview_text", previewText);
									}
									if (shouldFailPreview(preview)) {
										entry.put("preview_failed", true);
										if (isPreviewFailureToolFailure()) {
											requestsArray.remove(requestsArray.size() - 1);
											requestIterator.remove();
											String message = previewErrorMessage(preview);
											if (message == null || message.isBlank()) {
												message = "Tool failed";
											}
											events.add(toolCallEvent(call, summary));
											events.add(toolErrorEvent(call.id, call.fullName, message, "tool_failed"));
											hadToolCall = true;
											hadToolFailure = true;
											continue;
										}
									}
									boolean previewResponseMetaHandled = false;
									JsonNode previewMeta = preview.get("_meta");
									if (previewMeta != null && previewMeta.isObject()) {
										String reviewUri = primaryMetaResourceUri(previewMeta, "reviewUri", "previewUri");
										StoredMetaResource previewReview = storeMetaResource(session, call, reviewUri, previewMeta.path("ui").path("csp"), tagIds);
										if (previewReview != null) {
											entry.put("review_uri", previewReview.uri());
											if (previewReview.mimeType() != null
													&& !previewReview.mimeType().isBlank()) {
												entry.put("review_mime_type", previewReview.mimeType());
											}
											previewResponseMetaHandled = true;
										}
										String diffUri = readMetaUri(previewMeta, "diffUri", "diff_uri");
										StoredMetaResource previewDiff = storeMetaResource(session, call, diffUri, null, tagIds);
										if (previewDiff != null) {
											entry.put("diff_uri", previewDiff.uri());
											if (previewDiff.mimeType() != null && !previewDiff.mimeType().isBlank()) {
												entry.put("diff_mime_type", previewDiff.mimeType());
											}
											previewResponseMetaHandled = true;
										}
									}
									if (!previewResponseMetaHandled) {
										StoredMetaResource fallback = storeMetaFromContent(session, call, preview, "review", tagIds);
										if (fallback != null) {
											entry.put("review_uri", fallback.uri());
											if (fallback.mimeType() != null && !fallback.mimeType().isBlank()) {
												entry.put("review_mime_type", fallback.mimeType());
											}
											previewMetaHandled = true;
										}
									}
								}
							}
							catch (JsonRpcException ex) {
								if (!isPreviewUnsupported(ex)) {
									LOGGER.warnf("Preview failed for tool %s: %s", call.fullName, ex.getMessage());
									boolean previewErrorHandled = false;
									ToolFailure failure = parseToolFailure(ex);
									if (failure != null
											&& failure.structuredContent() != null
											&& !failure.structuredContent().isNull()) {
										ObjectNode preview = objectMapper.createObjectNode();
										ObjectNode previewResult = preview.putObject("result");
										previewResult.set("structuredContent", failure.structuredContent());
										if (failure.uiResourceUri() != null && !failure.uiResourceUri().isBlank()) {
											previewResult.putObject("_meta")
												.putObject("ui")
												.put("resourceUri", failure.uiResourceUri());
										}
										entry.set("preview", preview);
										previewErrorHandled = true;
									}
									String raw = ex.getMessage();
									if (raw != null) {
										String trimmed = raw.trim();
										if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
											try {
												JsonNode errorNode = objectMapper.readTree(trimmed);
												if (errorNode != null && errorNode.isObject()) {
													JsonNode errorMeta = errorNode.get("_meta");
													if (errorMeta == null || errorMeta.isNull()) {
														JsonNode data = errorNode.get("data");
														if (data != null && data.isObject()) {
															errorMeta = data.get("_meta");
														}
													}
													if (errorMeta != null && errorMeta.isObject()) {
														String reviewUri = primaryMetaResourceUri(errorMeta, "reviewUri", "previewUri");
														StoredMetaResource errorReview = storeMetaResource(session, call, reviewUri, errorMeta.path("ui").path("csp"), tagIds);
														if (errorReview != null) {
															entry.put("review_uri", errorReview.uri());
															if (errorReview.mimeType() != null
																	&& !errorReview.mimeType().isBlank()) {
																entry.put("review_mime_type", errorReview.mimeType());
															}
															previewErrorHandled = true;
														}
														String diffUri = readMetaUri(errorMeta, "diffUri", "diff_uri");
														StoredMetaResource errorDiff = storeMetaResource(session, call, diffUri, null, tagIds);
														if (errorDiff != null) {
															entry.put("diff_uri", errorDiff.uri());
															if (errorDiff.mimeType() != null
																	&& !errorDiff.mimeType().isBlank()) {
																entry.put("diff_mime_type", errorDiff.mimeType());
															}
															previewErrorHandled = true;
														}
													}
												}
											}
											catch (Exception parseEx) {
												LOGGER.debugf(parseEx, "Failed to parse tool preview error metadata");
											}
										}
									}
									if (!previewErrorHandled) {
										entry.put("preview_failed", true);
										if (isPreviewFailureToolFailure()) {
											requestsArray.remove(requestsArray.size() - 1);
											requestIterator.remove();
											String message = ex.getMessage();
											if (message == null || message.isBlank()) {
												message = "Tool failed";
											}
											events.add(toolCallEvent(call, summary));
											events.add(toolErrorEvent(call.id, call.fullName, message, "tool_failed"));
											hadToolCall = true;
											hadToolFailure = true;
											continue;
										}
									}
								}
							}
						}
					}
					if (!requestsArray.isEmpty()) {
						List<Map<String, Object>> previewEvents = new ArrayList<>();
						previewEvents.add(pendingEvent(SessionEventType.DECISION_REQUEST, decisionPayload));
						Map<String, Object> updates = new HashMap<>();
						updates.put(ConversationGraphState.ROUTE, "needs_approval");
						PendingState.Builder pending = PendingState.builder(objectMapper);
						pending.events(previewEvents);
						pending.sessionStatus(be.celerex.polymr.model.SessionStatus.PAUSED.name());
						pending.runStatus(WorkflowRunStatus.PAUSED.name());
						pending.locked(false);
						pending.toolRequests(deniedRequests);
						pending.toolApprovalPending(true);
						pending.toolApprovalRequestId(requestId);
						pending.apply(updates);
						return CompletableFuture.completedFuture(updates);
					}
				}

				Map<String, Object> updates = new HashMap<>();
				updates.put(ConversationGraphState.ROUTE, "default");
				if (snapshotUpdated) {
					updates.put(ConversationGraphState.MCP_SNAPSHOT, snapshot);
				}
				if (!spawnedChildren.isEmpty()) {
					ObjectNode nextState = completionUpdate == null ? readStateObject(state) : completionUpdate;
					mergeChildRunRefs(nextState, spawnedChildren);
					completionUpdate = nextState;
				}
				if (completionUpdate != null) {
					updates.put(ConversationGraphState.STATE, completionUpdate);
				}
				if (internalUpdate != null) {
					updates.put(ConversationGraphState.INTERNAL, internalUpdate);
				}
				PendingState.Builder pending = PendingState.builder(objectMapper);
				pending.events(events);
				pending.sessionStatus(be.celerex.polymr.model.SessionStatus.ACTIVE.name());
				pending.runStatus(WorkflowRunStatus.RUNNING.name());
				pending.locked(true);
				if (!spawnedChildren.isEmpty()) {
					updates.put(ConversationGraphState.ROUTE, "await_children");
				}
				if (isWorker && hadToolFailure && !sawCompletionToolCall) {
					ObjectNode stateNode = readStateObject(state);
					ObjectNode feedback = stateNode.putObject(WORKER_FEEDBACK_KEY);
					feedback.put("status", "failed");
					feedback.put("message", "Subassistant failed to execute required tools.");
					updates.put(ConversationGraphState.STATE, stateNode);
					updates.put(ConversationGraphState.ROUTE, "default");
					pending.sessionStatus(be.celerex.polymr.model.SessionStatus.COMPLETED.name());
					pending.runStatus(WorkflowRunStatus.FAILED.name());
					pending.locked(false);
					pending.toolRequests(List.of());
				}
				else if (hadToolFailure
						&& !hadToolCall
						&& shouldReturnHardToolErrorsToUser()
						&& shouldPauseAfterToolFailure(session)) {
					updates.put(ConversationGraphState.ROUTE, "needs_approval");
					pending.sessionStatus(be.celerex.polymr.model.SessionStatus.PAUSED.name());
					pending.runStatus(WorkflowRunStatus.PAUSED.name());
					pending.locked(false);
					pending.toolRequests(List.of());
				}
				else if (hadToolFailure && !hadToolCall) {
					pending.toolRequests(List.of());
				}
				if (aborted || abortedDuringTools) {
					updates.put(ConversationGraphState.ROUTE, "needs_approval");
					pending.sessionStatus(be.celerex.polymr.model.SessionStatus.PAUSED.name());
					pending.runStatus(WorkflowRunStatus.PAUSED.name());
					pending.locked(false);
					if (abortedDuringTools) {
						pending.events(List.of(systemEvent("Abort requested. Remaining tool calls were skipped.")));
					}
					if (session != null) {
						aborting.put(session.id, false);
					}
				}
				pending.apply(updates);
				return CompletableFuture.completedFuture(updates);
			}
			catch (Exception error) {
				LOGGER.errorf(error, "Tool execution failed for session %s", session.id);
				Map<String, Object> updates = new HashMap<>();
				updates.put(ConversationGraphState.ROUTE, "default");
				updates.put(ConversationGraphState.STATUS, "normal");
				PendingState.Builder pending = PendingState.builder(objectMapper);
				ObjectNode payload = objectMapper.createObjectNode();
				String errorMessage = error.getMessage() == null ? "Unknown error" : error.getMessage();
				payload.put("text", "Tool execution failed: " + errorMessage);
				pending.events(List.of(pendingEvent(SessionEventType.SYSTEM, payload)));
				if (isWorker(session, state)) {
					ObjectNode stateNode = readStateObject(state);
					ObjectNode feedback = stateNode.putObject(WORKER_FEEDBACK_KEY);
					feedback.put("status", "failed");
					feedback.put("message", "Subassistant failed to execute required tools: " + errorMessage);
					updates.put(ConversationGraphState.STATE, stateNode);
					pending.sessionStatus(be.celerex.polymr.model.SessionStatus.COMPLETED.name());
					pending.runStatus(WorkflowRunStatus.FAILED.name());
				}
				else {
					pending.sessionStatus(be.celerex.polymr.model.SessionStatus.PAUSED.name());
					pending.runStatus(WorkflowRunStatus.PAUSED.name());
				}
				pending.locked(false);
				pending.apply(updates);
				Map<String, Object> errorPayload = new HashMap<>();
				errorPayload.put("message", "Tool execution failed.");
				errorPayload.put("session_id", session.id.toString());
				errorPayload.put("exception", error.getClass().getSimpleName());
				socketManager.broadcastToSession(
					session.id,
					new WorkspaceSocketEvent("session.error", session.workspace.id, session.id, errorPayload)
				);
				return CompletableFuture.completedFuture(updates);
			}
		};
	}

	private boolean addUsedTool(ObjectNode snapshot, ObjectNode nodeSnapshot, String fullName) {
		if (snapshot == null || nodeSnapshot == null || fullName == null || fullName.isBlank()) {
			return false;
		}
		ObjectNode mcp = nodeSnapshot.get("mcp") instanceof ObjectNode mcpNode ? mcpNode : nodeSnapshot.putObject("mcp");
		ArrayNode allowed = mcp.get("allowed_tools") instanceof ArrayNode arr ? arr : mcp.putArray("allowed_tools");
		for (JsonNode entry : allowed) {
			if (entry != null && entry.isTextual() && fullName.equals(entry.asText())) {
				return false;
			}
		}
		allowed.add(fullName);
		return true;
	}

	private boolean addActivatedServer(ObjectNode snapshot, JsonNode result) {
		if (snapshot == null || result == null || !result.isObject()) {
			return false;
		}
		JsonNode serverIdNode = result.get("server_id");
		if (serverIdNode == null || !serverIdNode.isTextual()) {
			return false;
		}
		String serverId = serverIdNode.asText();
		if (serverId == null || serverId.isBlank()) {
			return false;
		}
		ObjectNode mcp = snapshot.get("mcp") instanceof ObjectNode mcpNode ? mcpNode : snapshot.putObject("mcp");
		ArrayNode servers = mcp.get("servers") instanceof ArrayNode arr ? arr : mcp.putArray("servers");
		for (JsonNode entry : servers) {
			if (entry.isTextual() && serverId.equals(entry.asText())) {
				return false;
			}
		}
		servers.add(serverId);
		return true;
	}

	private AsyncCommandAction<ConversationGraphState> edgeAction(
			String nodeId,
			Map<String, String> mappings,
			String recoveryTarget) {
		return (state, config) -> {
			Map<String, Object> updates = new HashMap<>();
			String status = state.value(ConversationGraphState.STATUS).map(Object::toString).orElse("normal");
			if ("recovery".equals(status) && recoveryTarget != null) {
				updates.put(ConversationGraphState.STATUS, "normal");
				if (!"user_input".equals(recoveryTarget)) {
					updates.put(ConversationGraphState.PAYLOAD, AgentState.MARK_FOR_REMOVAL);
				}
				if ("tool_exec".equals(nodeId)) {
					PendingState.Builder pending = PendingState.builder(objectMapper);
					pending.toolFailure("Tool call failed after a server restart. Please retry or adjust the request.");
					pending.apply(updates);
				}
				return CompletableFuture.completedFuture(new Command("recovery", updates));
			}
			String route = state.value(ConversationGraphState.ROUTE).map(Object::toString).orElse("default");
			if (!mappings.containsKey(route)) {
				route = "default";
			}
			updates.put(ConversationGraphState.ROUTE, AgentState.MARK_FOR_REMOVAL);
			return CompletableFuture.completedFuture(new Command(route, updates));
		};
	}

	private Session resolveSession(ConversationGraphState state) {
		String sessionId = state.value(ConversationGraphState.SESSION_ID).map(Object::toString).orElse(null);
		if (sessionId == null || sessionId.isBlank()) {
			return null;
		}
		return entityManager.find(Session.class, UUID.fromString(sessionId));
	}

	private JsonNode payloadFromState(ConversationGraphState state) {
		Object payload = state.value(ConversationGraphState.PAYLOAD).orElse(null);
		if (payload instanceof JsonNode jsonNode) {
			return jsonNode;
		}
		if (payload == null) {
			return null;
		}
		return objectMapper.convertValue(payload, JsonNode.class);
	}

	private ObjectNode requireObjectNode(JsonNode node, String label) {
		if (node instanceof ObjectNode objectNode) {
			return objectNode;
		}
		throw new IllegalStateException("Invalid workflow definition: " + label);
	}

	private String requireText(ObjectNode node, String field) {
		String value = node.path(field).asText(null);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException("Missing workflow definition field: " + field);
		}
		return value;
	}

	private RunnableConfig applyRecovery(
			CompiledGraph<ConversationGraphState> graph,
			RunnableConfig config,
			WorkflowRun run,
			Map<String, Object> inputs) {
		String nodeId = run.currentNode;
		if (nodeId == null || nodeId.isBlank()) {
			return config;
		}
		try {
			Map<String, Object> updates = new HashMap<>(inputs);
			if ("tool_exec".equals(nodeId)) {
				PendingState.Builder pending = PendingState.builder(objectMapper);
				pending.toolFailure("Tool call failed after a server restart. Please retry or adjust the request.");
				pending.apply(updates);
			}
			return graph.updateState(config, updates, nodeId);
		}
		catch (Exception exception) {
			LOGGER.warnf("Failed to apply recovery routing for run %s: %s", run.id, exception.getMessage());
			return null;
		}
	}

	private boolean isAborting(UUID sessionId) {
		return aborting.getOrDefault(sessionId, false);
	}

	private StreamingChatModel resolveModel(Session session) {
		Assistant assistant = session.defaultAssistant;
		if (assistant == null || assistant.model == null) {
			return null;
		}
		AiModel model = assistant.model;
		if (!model.enabled) {
			return null;
		}
		AiChatModelProvider provider = providerRegistry.find(model.provider).orElse(null);
		if (provider == null) {
			return null;
		}
		Map<String, Object> config = modelConfigService.resolveConfig(model);
		String modelId = resolveModelId(config);
		AiChatModelDefinition definition = provider.resolveChatModel(modelId).orElse(null);
		if (definition == null) {
			return null;
		}
		return definition.createStreamingChatModel(config);
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

	private List<ChatMessage> buildHistory(Session session) {
		List<ChatMessage> history = new ArrayList<>();
		List<ToolExecutionRequest> pendingToolRequests = new ArrayList<>();
		String[] pendingAssistantText = new String[1];
		List<SessionEvent> events = entityManager.createQuery(
				"select e from SessionEvent e where e.session.id = :sessionId order by e.createdAt",
				SessionEvent.class
			)
			.setParameter("sessionId", session.id)
			.getResultList();
		triggerAutoPrune(session, events);
		java.util.function.Consumer<Boolean> flushPending = includeToolRequests -> {
			if (includeToolRequests && !pendingToolRequests.isEmpty()) {
				history.add(AiMessage.from(pendingAssistantText[0], new ArrayList<>(pendingToolRequests)));
				pendingToolRequests.clear();
				pendingAssistantText[0] = null;
				return;
			}
			if (pendingToolRequests.isEmpty()
					&& pendingAssistantText[0] != null
					&& !pendingAssistantText[0].isBlank()) {
				history.add(AiMessage.from(pendingAssistantText[0]));
				pendingAssistantText[0] = null;
			}
		};
		for (SessionEvent event : events) {
			JsonNode payload = pruningService.payloadForHistory(event);
			String text = payload != null && payload.has("text") ? payload.get("text").asText() : null;
			if (event.eventType == SessionEventType.USER_MESSAGE) {
				List<Content> attachments = readAttachmentContents(payload, session.workspace.id);
				if (text == null && attachments.isEmpty()) {
					continue;
				}
				flushPending.accept(false);
				if (attachments.isEmpty()) {
					history.add(UserMessage.from(text));
				}
				else {
					List<Content> contents = new ArrayList<>();
					if (text != null && !text.isBlank()) {
						contents.add(TextContent.from(text));
					}
					contents.addAll(attachments);
					history.add(UserMessage.from(contents));
				}
				List<ChatMessage> attachmentMessages = readAttachmentMessages(payload, session.workspace.id);
				if (!attachmentMessages.isEmpty()) {
					history.addAll(attachmentMessages);
				}
			}
			else if (event.eventType == SessionEventType.CONTEXT_MESSAGE && text != null && !text.isBlank()) {
				flushPending.accept(false);
				history.add(UserMessage.from(text));
			}
			else if (event.eventType == SessionEventType.ASSISTANT_MESSAGE && text != null) {
				flushPending.accept(false);
				pendingAssistantText[0] = text;
			}
			else if (event.eventType == SessionEventType.SYSTEM && text != null) {
				if (isWorkerProgressPayload(payload)) {
					continue;
				}
				flushPending.accept(false);
				if (includeSystemEventsInLlmHistory) {
					history.add(SystemMessage.from(text));
				}
			}
			else if (event.eventType == SessionEventType.AUDIT) {
				continue;
			}
			else if (event.eventType == SessionEventType.SESSION_TAG_CHANGE) {
				flushPending.accept(false);
				String message = buildTagChangeMessage(payload);
				if (message != null && !message.isBlank()) {
					history.add(UserMessage.from(message));
				}
			}
			else if (event.eventType == SessionEventType.TOOL_CALL) {
				ToolExecutionRequest request = toolExecutionRequestFromPayload(payload);
				if (request != null) {
					pendingToolRequests.add(request);
				}
			}
			else if (event.eventType == SessionEventType.TOOL_RESULT) {
				if (text == null && payload != null && !payload.isNull()) {
					JsonNode resultNode = payload.has("result") ? payload.get("result") : null;
					if (resultNode != null && !resultNode.isNull()) {
						JsonNode resolved = resolveToolResultPayload(resultNode);
						text = resolved == null || resolved.isNull() ? null : resolved.toString();
					}
					if (text == null || text.isBlank()) {
						text = extractToolText(payload);
					}
				}
				if (text == null || text.isBlank()) {
					boolean isError = payload != null && (payload.has("code") || payload.has("error"));
					text = isError ? "Tool failed: no response was provided." : "No response was provided.";
				}
				flushPending.accept(true);
				String toolName = payload != null && payload.has("tool_name") ? payload.get("tool_name").asText(null) : null;
				String toolCallId = payload != null && payload.has("tool_call_id") ? payload.get("tool_call_id").asText(null) : null;
				boolean isError = payload != null && (payload.has("code") || payload.has("error"));
				if (toolName != null && toolCallId != null) {
					ToolExecutionResultMessage result = ToolExecutionResultMessage.builder()
						.id(toolCallId)
						.toolName(toolName)
						.text(text)
						.isError(isError)
						.build();
					history.add(result);
				}
				else if (includeUnknownToolFailuresInLlmHistory) {
					history.add(UserMessage.from(text));
				}
			}
		}
		flushPending.accept(false);
		return history;
	}

	private void triggerAutoPrune(Session session, List<SessionEvent> events) {
		if (session == null || session.id == null || events == null || events.isEmpty()) {
			return;
		}
		if (!pruningService.shouldAutoPrune(session, events)) {
			return;
		}
		String lockKey = session.id.toString();
		if (!lockService.tryAcquire(SESSION_PRUNE_LOCK_SCOPE, lockKey)) {
			LOGGER.debugf("Auto-prune skipped (lock busy) session=%s", session.id);
			return;
		}
		LOGGER.infof("Auto-prune triggered session=%s", session.id);
		dispatchExecutor.runAsync(
			() -> {
				try {
					self.get().pruneSessionAsync(session.id);
				}
				catch (Exception ex) {
					LOGGER.errorf(ex, "Auto-prune failed session=%s", session.id);
					throw ex;
				}
				finally {
					lockService.release(SESSION_PRUNE_LOCK_SCOPE, lockKey);
				}
			}
		);
	}

	private void triggerAutoPruneAfterLlmResponse(Session session, UsageAccounting usageAccounting) {
		if (session == null
				|| session.id == null
				|| usageAccounting == null
				|| usageAccounting.usage() == null) {
			return;
		}
		ResponseUsage usage = usageAccounting.usage();
		if (usage.input_tokens() == null && usage.output_tokens() == null && usage.reasoning_tokens() == null) {
			return;
		}
		SessionEvent latestContextEvent = new SessionEvent();
		latestContextEvent.inputTokens = usage.input_tokens() == null ? null : Math.toIntExact(usage.input_tokens());
		latestContextEvent.outputTokens = usage.output_tokens() == null ? null : Math.toIntExact(usage.output_tokens());
		latestContextEvent.reasoningTokens = usage.reasoning_tokens() == null ? null : Math.toIntExact(usage.reasoning_tokens());
		triggerAutoPrune(session, List.of(latestContextEvent));
	}

	@Transactional
	void pruneSessionAsync(UUID sessionId) {
		SessionPruningService.PruneResult result = pruningService.pruneSession(sessionId);
		LOGGER.infof(
			"Auto-prune result session=%s thresholdReached=%s estimatedTokens=%s "
				+ "pruningLimit=%s prunedEvents=%s prunedTokens=%s",
			sessionId,
			result.thresholdReached(),
			result.estimatedTokens(),
			result.pruningLimit(),
			result.prunedEvents(),
			result.prunedTokens()
		);
	}

	private void injectSystemInfo(List<ChatMessage> history, UUID userId, LocationInfo location) {
		if (!includeSystemInfo || history == null || history.isEmpty()) {
			return;
		}
		String systemInfo = buildSystemInfoBlock(userId, location, List.of());
		if (systemInfo == null || systemInfo.isBlank()) {
			return;
		}
		for (int i = history.size() - 1; i >= 0; i -= 1) {
			ChatMessage message = history.get(i);
			if (!(message instanceof UserMessage userMessage)) {
				continue;
			}
			List<Content> contents = userMessage.contents();
			if (contents != null && !contents.isEmpty()) {
				List<Content> next = new ArrayList<>(contents);
				next.add(TextContent.from(systemInfo));
				history.set(i, UserMessage.from(next));
				return;
			}
			history.set(i, UserMessage.from(systemInfo));
			return;
		}
	}

	private String buildSystemInfoBlock(
			UUID userId,
			LocationInfo location,
			List<SystemInfoMessage> injectedMessages) {
		if (!includeSystemInfo && (injectedMessages == null || injectedMessages.isEmpty())) {
			return null;
		}
		ZoneId zone = ZoneId.systemDefault();
		ZonedDateTime now = ZonedDateTime.now(zone);
		String timestamp = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(now);
		String zoneId = zone.getId();
		String userEmail = null;
		if (userId != null) {
			User user = entityManager.find(User.class, userId);
			userEmail = user == null ? null : user.email;
		}
		StringBuilder builder = new StringBuilder();
		builder.append("<system-info>\n");
		builder.append("  <current_datetime>").append(timestamp).append("</current_datetime>\n");
		builder.append("  <timezone>").append(zoneId).append("</timezone>\n");
		boolean hasUser = (userEmail != null && !userEmail.isBlank()) || location != null;
		if (hasUser) {
			builder.append("  <user>\n");
			if (userEmail != null && !userEmail.isBlank()) {
				builder.append("    <email>").append(userEmail).append("</email>\n");
			}
			if (location != null) {
				builder.append("    <lat>").append(location.lat).append("</lat>\n");
				builder.append("    <lng>").append(location.lng).append("</lng>\n");
			}
			builder.append("  </user>\n");
		}
		if (injectedMessages != null) {
			for (SystemInfoMessage injectedMessage : injectedMessages) {
				if (injectedMessage == null) {
					continue;
				}
				String type = normalizePrompt(injectedMessage.type());
				String content = normalizePrompt(injectedMessage.content());
				if (type == null || content == null) {
					continue;
				}
				builder.append("  <message type=\"")
					.append(escapeXml(type))
					.append("\">")
					.append(escapeXml(content))
					.append("</message>\n");
			}
		}
		builder.append("</system-info>");
		return builder.toString();
	}

	private List<SystemInfoMessage> buildInjectedSystemInfoMessages(
			Session session,
			ConversationGraphState state,
			WorkflowDefinition definition,
			ObjectNode logicalNode) {
		return buildActiveSkillSystemInfoMessages(session, state, definition, logicalNode);
	}

	private List<SystemInfoMessage> buildActiveSkillSystemInfoMessages(
			Session session,
			ConversationGraphState state,
			WorkflowDefinition definition,
			ObjectNode logicalNode) {
		if (session == null) {
			return List.of();
		}
		Assistant assistant = resolveAssistantForNode(session, logicalNode, false);
		List<Skill> assistantSkills = loadAssistantSkills(session, assistant);
		if (assistantSkills.isEmpty()) {
			return List.of();
		}
		List<Skill> activeSkills = resolveActiveSkills(state, definition, logicalNode, assistantSkills);
		if (activeSkills.isEmpty()) {
			return List.of();
		}
		List<SystemInfoMessage> messages = new ArrayList<>();
		for (Skill activeSkill : activeSkills) {
			String prompt = normalizePrompt(activeSkill == null ? null : activeSkill.promptText);
			if (prompt == null) {
				continue;
			}
			messages.add(new SystemInfoMessage("active_skill", "Skill: **" + skillKey(activeSkill) + "**:\n" + prompt));
		}
		return messages;
	}

	private String escapeXml(String value) {
		if (value == null || value.isEmpty()) {
			return value;
		}
		return value.replace("&", "&amp;")
			.replace("<", "&lt;")
			.replace(">", "&gt;")
			.replace("\"", "&quot;")
			.replace("'", "&apos;");
	}

	private LocationInfo extractLocation(ObjectNode payload) {
		if (payload == null) {
			return null;
		}
		JsonNode meta = payload.get("meta");
		if (!(meta instanceof ObjectNode metaNode)) {
			return null;
		}
		JsonNode locationNode = metaNode.get("location");
		if (!(locationNode instanceof ObjectNode locationObject)) {
			return null;
		}
		Double lat = readLocationValue(locationObject.get("lat"));
		Double lng = readLocationValue(locationObject.get("lng"));
		metaNode.remove("location");
		if (metaNode.isEmpty()) {
			payload.remove("meta");
		}
		if (lat == null || lng == null) {
			return null;
		}
		return new LocationInfo(lat, lng);
	}

	private Double readLocationValue(JsonNode node) {
		if (node == null) {
			return null;
		}
		if (node.isNumber()) {
			return node.asDouble();
		}
		if (node.isTextual()) {
			try {
				return Double.parseDouble(node.asText());
			}
			catch (NumberFormatException ignored) {}
		}
		return null;
	}

	private LocationInfo takePendingLocation(UUID sessionId) {
		if (sessionId == null) {
			return null;
		}
		return pendingLocations.remove(sessionId);
	}

	private void persistLocation(ObjectNode payload, LocationInfo location) {
		if (payload == null || location == null) {
			return;
		}
		ObjectNode metaNode = payload.get("meta") instanceof ObjectNode existing ? existing : payload.putObject("meta");
		ObjectNode locationNode = metaNode.putObject("location");
		locationNode.put("lat", location.lat);
		locationNode.put("lng", location.lng);
		payload.put("location_lat", location.lat);
		payload.put("location_lng", location.lng);
	}

	private SessionEvent findLastUserEvent(Session session) {
		if (session == null || session.id == null) {
			return null;
		}
		return entityManager.createQuery(
				"select e from SessionEvent e left join fetch e.user where e.session.id = "
					+ ":sessionId and e.eventType = :eventType order by e.createdAt desc",
				SessionEvent.class
			)
			.setParameter("sessionId", session.id)
			.setParameter("eventType", SessionEventType.USER_MESSAGE)
			.setMaxResults(1)
			.getResultStream()
			.findFirst()
			.orElse(null);
	}

	private LocationInfo extractLocationFromPayload(JsonNode payload) {
		if (!(payload instanceof ObjectNode payloadObject)) {
			return null;
		}
		JsonNode locationLatNode = payloadObject.get("location_lat");
		JsonNode locationLngNode = payloadObject.get("location_lng");
		Double lat = readLocationValue(locationLatNode);
		Double lng = readLocationValue(locationLngNode);
		if (lat != null && lng != null) {
			return new LocationInfo(lat, lng);
		}
		JsonNode meta = payloadObject.get("meta");
		if (!(meta instanceof ObjectNode metaNode)) {
			return null;
		}
		JsonNode locationNode = metaNode.get("location");
		if (!(locationNode instanceof ObjectNode locationObject)) {
			return null;
		}
		lat = readLocationValue(locationObject.get("lat"));
		lng = readLocationValue(locationObject.get("lng"));
		if (lat == null || lng == null) {
			return null;
		}
		return new LocationInfo(lat, lng);
	}

	private LocationInfo eventLocation(SessionEvent event) {
		if (event == null) {
			return null;
		}
		if (event.locationLat != null && event.locationLng != null) {
			return new LocationInfo(event.locationLat, event.locationLng);
		}
		return extractLocationFromPayload(event.payloadJson);
	}

	private <T> T firstNonNull(T primary, T fallback) {
		return primary != null ? primary : fallback;
	}

	private String buildTagChangeMessage(JsonNode payload) {
		if (!includeTagChangeInLlmHistory) {
			return null;
		}
		if (payload == null || payload.isNull()) {
			return null;
		}
		JsonNode llmMessageNode = payload.get("llm_message");
		if (llmMessageNode != null && llmMessageNode.isTextual()) {
			String value = llmMessageNode.asText();
			if (value != null && !value.isBlank()) {
				return value;
			}
		}
		return null;
	}

	private ToolExecutionRequest toolExecutionRequestFromPayload(JsonNode payload) {
		if (payload == null || payload.isNull()) {
			return null;
		}
		JsonNode nameNode = payload.get("tool_name");
		String name = nameNode != null && nameNode.isTextual() ? nameNode.asText() : null;
		if (name == null || name.isBlank()) {
			return null;
		}
		String id = payload.has("tool_call_id") ? payload.get("tool_call_id").asText(null) : null;
		String arguments = null;
		JsonNode argumentsNode = payload.get("arguments");
		if (argumentsNode != null && !argumentsNode.isNull()) {
			arguments = argumentsNode.isTextual() ? argumentsNode.asText() : argumentsNode.toString();
		}
		return ToolExecutionRequest.builder().id(id).name(name).arguments(arguments).build();
	}

	private boolean applyAssistantTurnLimit(
			Session session,
			ConversationGraphState state,
			CompletableFuture<Map<String, Object>> future) {
		Integer maxTurns = resolveMaxTurns(session);
		if (maxTurns == null) {
			return false;
		}
		ObjectNode stateNode = readStateObject(state);
		int currentTurns = stateNode.path(WORKER_TURN_COUNT_KEY).asInt(0);
		if (currentTurns >= maxTurns) {
			Map<String, Object> updates = new HashMap<>();
			updates.put(ConversationGraphState.ROUTE, "default");
			updates.put(
				ConversationGraphState.STATE,
				completeAssistantDueToTurnLimit(stateNode, maxTurns, isWorker(session, state))
			);
			PendingState.Builder pending = PendingState.builder(objectMapper);
			pending.events(
				List.of(
					systemEvent("Assistant reached its max autonomous turns limit of " + maxTurns + ". Waiting for user input.")
				)
			);
			if (isWorker(session, state)) {
				pending.sessionStatus(be.celerex.polymr.model.SessionStatus.COMPLETED.name());
				pending.runStatus(WorkflowRunStatus.FAILED.name());
			}
			else {
				pending.sessionStatus(be.celerex.polymr.model.SessionStatus.PAUSED.name());
				pending.runStatus(WorkflowRunStatus.PAUSED.name());
			}
			pending.locked(false);
			pending.toolRequests(List.of());
			pending.apply(updates);
			future.complete(updates);
			return true;
		}
		stateNode.put(WORKER_TURN_COUNT_KEY, currentTurns + 1);
		return false;
	}

	private ObjectNode completeAssistantDueToTurnLimit(ObjectNode stateNode, int maxTurns, boolean worker) {
		ObjectNode nextState = stateNode == null ? objectMapper.createObjectNode() : stateNode.deepCopy();
		nextState.put(WORKER_TURN_COUNT_KEY, maxTurns);
		if (worker) {
			ObjectNode feedback = nextState.putObject(WORKER_FEEDBACK_KEY);
			feedback.put("status", "failed");
			feedback.put("message", "Subassistant reached its max autonomous turns limit of " + maxTurns + ".");
			ObjectNode completion = nextState.putObject(WORKER_COMPLETION_KEY);
			completion.put("status", "failed");
			completion.put("message", "Subassistant reached its max autonomous turns limit of " + maxTurns + ".");
		}
		return nextState;
	}

	private Integer resolveMaxTurns(Session session) {
		if (session == null) {
			return null;
		}
		Assistant assistant = session.defaultAssistant;
		if (assistant == null || assistant.maxTurns == null || assistant.maxTurns <= 0) {
			return null;
		}
		return assistant.maxTurns;
	}

	private Integer resolveMaxOutputTokens(Session session) {
		if (session == null) {
			return null;
		}
		Assistant assistant = session.defaultAssistant;
		if (assistant != null && assistant.maxOutputTokens != null) {
			return assistant.maxOutputTokens > 0 ? assistant.maxOutputTokens : null;
		}
		Tenant tenant = assistant != null ? assistant.tenant : session.tenant;
		if (tenant == null || tenant.maxOutputTokens == null) {
			return null;
		}
		return tenant.maxOutputTokens > 0 ? tenant.maxOutputTokens : null;
	}

	@Transactional
	void lockSession(UUID sessionId, boolean locked) {
		Session session = entityManager.find(Session.class, sessionId);
		if (session == null) {
			return;
		}
		session.locked = locked;
	}

	@Transactional
	void updateStatus(UUID sessionId, be.celerex.polymr.model.SessionStatus status) {
		Session session = entityManager.find(Session.class, sessionId);
		if (session == null || status == null) {
			return;
		}
		session.status = status;
	}

	private void broadcastToken(Session session, String token) {
		WorkspaceSocketEvent socketEvent = new WorkspaceSocketEvent("chat.token", session.workspace.id, session.id, Map.of("text", token));
		socketManager.broadcastToSession(session.id, socketEvent);
	}

	private void traceEvent(Session session, String severity, String type, String message, Object details) {
		if (session == null || session.workspace == null) {
			return;
		}
		Map<String, Object> payload = new HashMap<>();
		payload.put("severity", severity);
		payload.put("type", type);
		payload.put("message", message);
		payload.put("timestamp", java.time.Instant.now().toString());
		if (details != null) {
			payload.put("details", details);
		}
		socketManager.broadcastTrace(
			session.workspace.id,
			session.id,
			new WorkspaceSocketEvent("session.trace", session.workspace.id, session.id, payload)
		);
	}

	private Object traceDetails(Object raw) {
		if (raw == null) {
			return null;
		}
		try {
			JsonNode tree = objectMapper.valueToTree(raw);
			if (raw instanceof ChatResponse response && tree instanceof ObjectNode objectNode) {
				ArrayNode keys = objectMapper.createArrayNode();
				metadataKeys(response).forEach(keys::add);
				objectNode.set("metadata_keys", keys);
			}
			return tree;
		}
		catch (IllegalArgumentException ignored) {
			return raw.toString();
		}
	}

	private List<String> metadataKeys(ChatResponse response) {
		if (response == null) {
			return List.of();
		}
		try {
			java.lang.reflect.Method method = response.getClass().getMethod("metadata");
			Object value = method.invoke(response);
			if (value instanceof Map<?, ?> raw) {
				return raw.keySet()
					.stream()
					.filter(key -> key != null)
					.map(Object::toString)
					.sorted()
					.toList();
			}
		}
		catch (Exception ex) {
			LOGGER.debugf(ex, "Failed to read response metadata keys");
		}
		return List.of();
	}

	private ObjectNode responseMetadata(Session session, ChatResponse response) {
		if (response == null) {
			return null;
		}
		if (session != null && session.defaultAssistant != null && session.defaultAssistant.model != null) {
			AiModel model = session.defaultAssistant.model;
			AiChatModelProvider provider = providerRegistry.find(model.provider).orElse(null);
			if (provider != null) {
				Map<String, Object> config = modelConfigService.resolveConfig(model);
				String modelId = resolveModelId(config);
				AiChatModelDefinition definition = provider.resolveChatModel(modelId).orElse(null);
				if (definition != null) {
					AiModelResponseMetadataExtractor extractor = definition.responseMetadataExtractor(config)
						.orElse(null);
					if (extractor != null) {
						Map<String, Object> metadata = extractor.extract(response, modelId);
						if (metadata != null && !metadata.isEmpty()) {
							return objectMapper.convertValue(metadata, ObjectNode.class);
						}
					}
				}
			}
		}
		try {
			java.lang.reflect.Method method = response.getClass().getMethod("metadata");
			Object value = method.invoke(response);
			if (value instanceof Map<?, ?> raw) {
				return objectMapper.convertValue(raw, ObjectNode.class);
			}
		}
		catch (Exception ex) {
			LOGGER.debugf(ex, "Failed to read response metadata");
		}
		return null;
	}

	private void broadcastEvent(Session session, SessionEvent event) {
		SessionEventResponse response = new SessionEventResponse(
			event.id,
			event.eventType,
			event.payloadJson,
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

	private Map<String, Object> pendingEvent(SessionEventType type, JsonNode payload) {
		return pendingEvent(type, payload, null);
	}

	private Map<String, Object> pendingEvent(SessionEventType type, JsonNode payload, UUID userId) {
		Map<String, Object> event = new HashMap<>();
		event.put("type", type == null ? null : type.name());
		event.put("payload", payload == null ? objectMapper.createObjectNode() : payload);
		if (userId != null) {
			event.put("user_id", userId.toString());
		}
		return event;
	}

	private List<Map<String, Object>> readPendingToolRequests(ConversationGraphState state) {
		ObjectNode pending = PendingState.pendingFromState(state, objectMapper);
		JsonNode requestsNode = PendingState.read(pending, PendingState.TOOL_REQUESTS);
		if (requestsNode == null || requestsNode.isNull()) {
			return List.of();
		}
		List<Map<String, Object>> list = objectMapper.convertValue(requestsNode, List.class);
		return list == null ? List.of() : list;
	}

	private boolean pendingApprovalPending(ConversationGraphState state) {
		ObjectNode pending = PendingState.pendingFromState(state, objectMapper);
		return PendingState.readBoolean(pending, PendingState.TOOL_APPROVAL, PendingState.TOOL_APPROVAL_PENDING);
	}

	private void clearPendingToolState(ObjectNode state) {
		if (state == null) {
			return;
		}
		ObjectNode pending = state.get(ConversationGraphState.PENDING) instanceof ObjectNode pendingNode ? pendingNode : null;
		if (pending == null) {
			return;
		}
		pending.remove(PendingState.TOOL_REQUESTS);
		pending.remove(PendingState.TOOL_APPROVAL);
		pending.remove(PendingState.TOOL_FAILURE);
		pending.remove(PendingState.TOOL_USAGE);
		if (pending.isEmpty()) {
			state.remove(ConversationGraphState.PENDING);
		}
	}

	@Transactional
	boolean restoreMissingApprovalState(WorkflowRun run, JsonNode pendingRequestsNode) {
		if (run == null
				|| run.session == null
				|| pendingRequestsNode == null
				|| pendingRequestsNode.isNull()
				|| !pendingRequestsNode.isArray()
				|| pendingRequestsNode.isEmpty()) {
			return false;
		}
		JsonNode pending = PendingState.pendingFromCheckpoint(run.checkpointJson, objectMapper);
		if (PendingState.read(pending, PendingState.TOOL_APPROVAL) != null) {
			return false;
		}
		List<Map<String, Object>> pendingRequests = objectMapper.convertValue(pendingRequestsNode, List.class);
		if (pendingRequests == null || pendingRequests.isEmpty()) {
			return false;
		}
		ObjectNode checkpoint = run.checkpointJson instanceof ObjectNode node ? node.deepCopy() : objectMapper.createObjectNode();
		ObjectNode decisionPayload = objectMapper.createObjectNode();
		String requestId = UUID.randomUUID().toString();
		decisionPayload.put("request_id", requestId);
		decisionPayload.put("message", "Tool execution requires approval.");
		ArrayNode requestsArray = decisionPayload.putArray("requests");
		for (Map<String, Object> request : pendingRequests) {
			String toolCallId = readRequestText(request, "id");
			String toolName = readRequestText(request, "name");
			String argumentsText = readRequestText(request, "arguments");
			JsonNode argumentsNode = parseArgumentsNode(argumentsText);
			ObjectNode entry = requestsArray.addObject();
			if (toolCallId != null && !toolCallId.isBlank()) {
				entry.put("id", toolCallId);
			}
			if (toolName != null && !toolName.isBlank()) {
				entry.put("tool_name", toolName);
			}
			if (argumentsNode != null && !argumentsNode.isNull()) {
				entry.set("arguments", argumentsNode);
			}
		}
		PendingState.Builder pendingBuilder = PendingState.builder(objectMapper);
		pendingBuilder.events(List.of(pendingEvent(SessionEventType.DECISION_REQUEST, decisionPayload)));
		pendingBuilder.sessionStatus(be.celerex.polymr.model.SessionStatus.PAUSED.name());
		pendingBuilder.runStatus(WorkflowRunStatus.PAUSED.name());
		pendingBuilder.locked(false);
		pendingBuilder.toolRequests(pendingRequests);
		pendingBuilder.toolApprovalPending(true);
		pendingBuilder.toolApprovalRequestId(requestId);
		Map<String, Object> updates = new HashMap<>();
		pendingBuilder.apply(updates);
		checkpoint.set(
			ConversationGraphState.PENDING,
			objectMapper.valueToTree(updates.get(ConversationGraphState.PENDING))
		);
		checkpoint.put(ConversationGraphState.ROUTE, "needs_approval");
		checkpoint.put(ConversationGraphState.STATUS, "normal");
		if (!checkpoint.has("next") || checkpoint.path("next").asText("").isBlank()) {
			checkpoint.put("next", "user_input");
		}
		checkpoint.put("updated_at", java.time.Instant.now().toString());
		checkpointService.updateProjectionFromCheckpoint(run, state -> checkpoint);
		run.status = WorkflowRunStatus.PAUSED;
		run.runtimeServerId = null;
		run.session.status = be.celerex.polymr.model.SessionStatus.PAUSED;
		run.session.locked = false;
		Integer epochId = null;
		JsonNode epochNode = checkpoint.get("epoch_id");
		if (epochNode != null && epochNode.canConvertToInt()) {
			epochId = epochNode.asInt();
		}
		SessionEvent event = epochId == null
			? eventService.createEvent(run.session, SessionEventType.DECISION_REQUEST, decisionPayload)
			: eventService.createEvent(run.session, SessionEventType.DECISION_REQUEST, decisionPayload, epochId);
		broadcastEvent(run.session, event);
		participantService.broadcastSessionState(
			run.session,
			"session.status",
			Map.of("id", run.session.id, "status", run.session.status, "locked", false)
		);
		return true;
	}

	private String pendingApprovalDecision(ConversationGraphState state) {
		ObjectNode pending = PendingState.pendingFromState(state, objectMapper);
		return PendingState.readText(pending, PendingState.TOOL_APPROVAL, PendingState.TOOL_APPROVAL_DECISION);
	}

	private boolean pendingApprovalBypass(ConversationGraphState state) {
		ObjectNode pending = PendingState.pendingFromState(state, objectMapper);
		return PendingState.readBoolean(pending, PendingState.TOOL_APPROVAL, PendingState.TOOL_APPROVAL_BYPASS);
	}

	private String pendingApprovalRequestId(ConversationGraphState state) {
		ObjectNode pending = PendingState.pendingFromState(state, objectMapper);
		return PendingState.readText(pending, PendingState.TOOL_APPROVAL, PendingState.TOOL_APPROVAL_REQUEST_ID);
	}

	private List<String> pendingApprovalAllowScopes(ConversationGraphState state) {
		ObjectNode pending = PendingState.pendingFromState(state, objectMapper);
		JsonNode node = PendingState.read(pending, PendingState.TOOL_APPROVAL, PendingState.TOOL_APPROVAL_ALLOW_SCOPES);
		return readStringArray(node);
	}

	private List<String> pendingApprovalDenyScopes(ConversationGraphState state) {
		ObjectNode pending = PendingState.pendingFromState(state, objectMapper);
		JsonNode node = PendingState.read(pending, PendingState.TOOL_APPROVAL, PendingState.TOOL_APPROVAL_DENY_SCOPES);
		return readStringArray(node);
	}

	private ToolCall parseToolCall(Map<String, Object> request, List<UUID> allowedServerIds) {
		if (request == null) {
			return null;
		}
		String id = request.get("id") == null ? null : request.get("id").toString();
		String name = request.get("name") == null ? null : request.get("name").toString();
		String args = request.get("arguments") == null ? null : request.get("arguments").toString();
		if (name == null || name.isBlank()) {
			return null;
		}
		JsonNode argumentsNode = parseArguments(args);
		if (name.startsWith("mcp:")) {
			String[] parts = name.split(":", 3);
			if (parts.length < 3) {
				return new ToolCall(id, name, null, null, args, argumentsNode, "tool_invalid", "Invalid tool name");
			}
			UUID serverId;
			try {
				serverId = UUID.fromString(parts[1]);
			}
			catch (IllegalArgumentException ex) {
				return new ToolCall(id, name, null, null, args, argumentsNode, "tool_invalid", "Invalid tool name");
			}
			return new ToolCall(id, name, serverId, parts[2], args, argumentsNode, null, null);
		}
		List<McpServerTool> matches = resolveToolsByName(name, allowedServerIds);
		if (matches.isEmpty()) {
			return new ToolCall(id, name, null, null, args, argumentsNode, "tool_unavailable", "Tool not available");
		}
		if (matches.size() > 1) {
			return new ToolCall(
				id,
				name,
				null,
				null,
				args,
				argumentsNode,
				"tool_ambiguous",
				"Tool name is ambiguous. Set a custom alias to disambiguate."
			);
		}
		McpServerTool tool = matches.get(0);
		if (tool.mcpServer == null) {
			return new ToolCall(id, name, null, null, args, argumentsNode, "tool_unavailable", "Tool not available");
		}
		return new ToolCall(id, name, tool.mcpServer.id, tool.toolName, args, argumentsNode, null, null);
	}

	private List<McpServerTool> resolveToolsByName(String name, List<UUID> allowedServerIds) {
		if (name == null || name.isBlank() || allowedServerIds == null || allowedServerIds.isEmpty()) {
			return List.of();
		}
		return entityManager.createQuery(
				"select t from McpServerTool t join fetch t.mcpServer "
					+ "where t.mcpServer.id in :serverIds and t.deleted = false and t.disabled = false "
					+ "and lower(coalesce(t.mcpServer.toolNamePrefix, '') || coalesce(t.toolAlias, t.toolName)) = "
					+ "lower(:name)",
				McpServerTool.class
			)
			.setParameter("serverIds", allowedServerIds)
			.setParameter("name", name)
			.getResultList();
	}

	private JsonNode parseArguments(String args) {
		if (args == null || args.isBlank()) {
			return objectMapper.createObjectNode();
		}
		try {
			return objectMapper.readTree(args);
		}
		catch (Exception ex) {
			ObjectNode payload = objectMapper.createObjectNode();
			payload.put("raw", args);
			return payload;
		}
	}

	private McpServerTool loadTool(UUID serverId, String toolName) {
		if (serverId == null || toolName == null) {
			return null;
		}
		return entityManager.createQuery(
				"select t from McpServerTool t where t.mcpServer.id = :serverId and t.toolName = :toolName",
				McpServerTool.class
			)
			.setParameter("serverId", serverId)
			.setParameter("toolName", toolName)
			.getResultStream()
			.findFirst()
			.orElse(null);
	}

	private JsonNode callTool(
			Session session,
			ToolCall call,
			JsonNode arguments,
			ObjectNode meta,
			UUID userId,
			List<UUID> tagIds) {
		return mcpRegistry.call(session.workspace.id, call.serverId, call.toolName, arguments, meta, session.id, userId, tagIds);
	}

	private UUID readUserId(ConversationGraphState state) {
		if (state == null) {
			return null;
		}
		String value = state.value(ConversationGraphState.USER_ID).map(Object::toString).orElse(null);
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

	private List<UUID> readTagIds(ObjectNode nodeSnapshot) {
		if (nodeSnapshot == null) {
			return List.of();
		}
		ObjectNode mcp = nodeSnapshot.get("mcp") instanceof ObjectNode obj ? obj : null;
		if (mcp == null) {
			return List.of();
		}
		return readUuidArray(mcp.get("tags"));
	}

	private List<String> readScopeList(ObjectNode nodeSnapshot, String field) {
		if (nodeSnapshot == null) {
			return List.of();
		}
		ObjectNode scopes = nodeSnapshot.get("scopes") instanceof ObjectNode obj ? obj : null;
		if (scopes == null) {
			return List.of();
		}
		return readScopeList(scopes.get(field));
	}

	private List<String> readScopeList(JsonNode node) {
		if (node == null || node.isNull() || !node.isArray()) {
			return List.of();
		}
		List<String> scopes = new ArrayList<>();
		node.forEach(entry -> {
			if (entry.isTextual()) {
				scopes.add(entry.asText());
			}
		});
		return scopes;
	}

	private List<String> resolveToolScopes(McpServerTool tool) {
		if (tool == null) {
			return List.of();
		}
		List<String> scopes = readScopeList(tool.customScopes);
		if (scopes.isEmpty()) {
			scopes = readScopeList(tool.scopes);
		}
		if (scopes.isEmpty()) {
			String toolName = tool.toolName;
			if (toolName != null && !toolName.isBlank()) {
				return List.of(toolName);
			}
		}
		return scopes;
	}

	private Map<String, Object> toolErrorEvent(String id, String toolName, String message, String code) {
		ObjectNode payload = objectMapper.createObjectNode();
		if (id != null) {
			payload.put("tool_call_id", id);
		}
		if (toolName != null) {
			payload.put("tool_name", toolName);
		}
		if (message != null && !message.isBlank()) {
			payload.put("text", message);
		}
		if (code != null) {
			payload.put("code", code);
		}
		return pendingEvent(SessionEventType.TOOL_RESULT, payload);
	}

	private String resolveToolCallInputTemplate(ToolCall call) {
		if (call == null || call.serverId == null || call.toolName == null) {
			return null;
		}
		McpServerTool tool = loadTool(call.serverId, call.toolName);
		if (tool == null || tool.inputTemplate == null || tool.inputTemplate.isBlank()) {
			return null;
		}
		String inputTemplate = renderToolTemplate(tool.inputTemplate, call.argumentsNode, null);
		if (inputTemplate == null || inputTemplate.isBlank()) {
			return null;
		}
		return inputTemplate;
	}

	private ObjectNode toolActivity(ToolCall call, String summary) {
		ObjectNode payload = objectMapper.createObjectNode();
		payload.put("kind", "tool_call");
		payload.put("status", "running");
		if (call != null && call.id != null) {
			payload.put("id", call.id);
			payload.put("tool_call_id", call.id);
		}
		if (call != null && call.fullName != null) {
			payload.put("tool_name", call.fullName);
		}
		if (summary != null && !summary.isBlank()) {
			payload.put("summary", summary);
		}
		String inputTemplate = resolveToolCallInputTemplate(call);
		if (inputTemplate != null) {
			payload.put("input_template", inputTemplate);
		}
		if (call != null && call.argumentsNode != null) {
			payload.set("arguments", call.argumentsNode.deepCopy());
		}
		payload.put("started_at", java.time.Instant.now().toString());
		return payload;
	}

	private Map<String, Object> toolCallEvent(ToolCall call) {
		return toolCallEvent(call, null, null);
	}

	private Map<String, Object> toolCallEvent(ToolCall call, String summary) {
		return toolCallEvent(call, summary, null);
	}

	private Map<String, Object> toolCallEvent(ToolCall call, String summary, UsageAccounting accounting) {
		ObjectNode payload = objectMapper.createObjectNode();
		if (call == null) {
			Map<String, Object> event = pendingEvent(SessionEventType.TOOL_CALL, payload);
			applyUsageToEvent(event, accounting);
			return event;
		}
		if (call.id != null) {
			payload.put("tool_call_id", call.id);
		}
		if (call.fullName != null) {
			payload.put("tool_name", call.fullName);
		}
		if (call.argumentsNode != null) {
			payload.set("arguments", call.argumentsNode);
		}
		if (summary != null && !summary.isBlank()) {
			payload.put("summary", summary);
		}
		String inputTemplate = resolveToolCallInputTemplate(call);
		if (inputTemplate != null) {
			payload.put("input_template", inputTemplate);
		}
		Map<String, Object> event = pendingEvent(SessionEventType.TOOL_CALL, payload);
		applyUsageToEvent(event, accounting);
		return event;
	}

	private boolean isSkillToolName(String name) {
		if (name == null || name.isBlank()) {
			return false;
		}
		return SKILL_ACTIVATE_TOOL.equals(name) || SKILL_DEACTIVATE_TOOL.equals(name);
	}

	private boolean isAutoApprovedCanvasTool(McpServerTool tool, ToolCall call) {
		if (tool == null || call == null || call.fullName == null || call.fullName.isBlank()) {
			return false;
		}
		if (!autoApprovedToolNames.contains(call.fullName.trim())) {
			return false;
		}
		if (tool.mcpServer == null) {
			LOGGER.warnf("Skipping canvas auto-approval for tool %s because the server is missing", call.fullName);
			return false;
		}
		return Objects.equals(tool.toolName, call.toolName)
			&& Objects.equals(tool.mcpServer.id, call.serverId)
			&& "polymr_canvas".equals(tool.mcpServer.virtualType);
	}

	private Map<String, Skill> buildSkillNameMap(List<Skill> skills) {
		if (skills == null || skills.isEmpty()) {
			return Map.of();
		}
		Map<String, Skill> map = new HashMap<>();
		for (Skill skill : skills) {
			if (!isSkillRenderable(skill)) {
				continue;
			}
			String key = skillKey(skill);
			map.putIfAbsent(key, skill);
		}
		return map;
	}

	private JsonNode parseArgumentsNode(String argumentsText) {
		if (argumentsText == null || argumentsText.isBlank()) {
			return null;
		}
		try {
			return objectMapper.readTree(argumentsText);
		}
		catch (Exception error) {
			return null;
		}
	}

	private ObjectNode prepareInternalUpdate(ObjectNode internalUpdate, ConversationGraphState state) {
		if (internalUpdate != null) {
			return internalUpdate;
		}
		ObjectNode internal = readInternalObject(state);
		return internal == null ? objectMapper.createObjectNode() : internal.deepCopy();
	}

	private void updateActiveSkill(ObjectNode internal, String logicalNodeId, UUID skillId) {
		if (internal == null || logicalNodeId == null || logicalNodeId.isBlank()) {
			return;
		}
		ObjectNode active = internal.get(INTERNAL_ACTIVE_SKILLS) instanceof ObjectNode node
			? node
			: internal.putObject(INTERNAL_ACTIVE_SKILLS);
		if (skillId == null) {
			active.remove(logicalNodeId);
			return;
		}
		active.put(logicalNodeId, skillId.toString());
	}

	private Map<String, Object> internalToolResultEvent(String id, String toolName, String text) {
		ObjectNode payload = objectMapper.createObjectNode();
		if (id != null && !id.isBlank()) {
			payload.put("tool_call_id", id);
		}
		if (toolName != null && !toolName.isBlank()) {
			payload.put("tool_name", toolName);
		}
		if (text != null && !text.isBlank()) {
			payload.put("text", text);
		}
		return pendingEvent(SessionEventType.TOOL_RESULT, payload);
	}

	private Map<String, Object> toolResultEvent(Session session, ToolCall call, JsonNode result, List<UUID> tagIds) {
		ObjectNode payload = objectMapper.createObjectNode();
		if (call != null) {
			if (call.id != null) {
				payload.put("tool_call_id", call.id);
			}
			if (call.fullName != null) {
				payload.put("tool_name", call.fullName);
			}
		}
		if (result != null) {
			payload.set("result", result);
			McpServerTool tool = call == null ? null : loadTool(call.serverId, call.toolName);
			applyToolResultMeta(session, call, tool, result, payload, tagIds);
			if (tool != null && tool.outputTemplate != null && !tool.outputTemplate.isBlank()) {
				String outputTemplate = renderToolTemplate(tool.outputTemplate, call.argumentsNode, result);
				if (outputTemplate != null && !outputTemplate.isBlank()) {
					payload.put("output_template", outputTemplate);
				}
			}
		}
		return pendingEvent(SessionEventType.TOOL_RESULT, payload);
	}

	private String renderToolTemplate(String template, JsonNode inputArgs, JsonNode output) {
		if (template == null || template.isBlank()) {
			return "";
		}
		ObjectNode merged = objectMapper.createObjectNode();
		if (inputArgs != null && inputArgs.isObject()) {
			merged.setAll((ObjectNode) inputArgs);
		}
		else if (inputArgs != null) {
			merged.set("input", inputArgs);
		}
		JsonNode templateOutput = output;
		if (output != null && output.isObject()) {
			JsonNode structuredContent = output.get("structuredContent");
			if (structuredContent != null && structuredContent.isObject()) {
				templateOutput = structuredContent;
			}
		}
		if (templateOutput != null && templateOutput.isObject()) {
			merged.setAll((ObjectNode) templateOutput);
		}
		else if (templateOutput != null) {
			merged.set("output", templateOutput);
		}
		String rendered = renderIntentTemplate(template, merged);
		return rendered == null ? "" : rendered;
	}

	private void applyToolResultMeta(
			Session session,
			ToolCall call,
			McpServerTool tool,
			JsonNode result,
			ObjectNode payload,
			List<UUID> tagIds) {
		if (session == null || call == null || result == null || payload == null || !result.isObject()) {
			return;
		}
		JsonNode meta = result.get("_meta");
		JsonNode effectiveMeta = meta;
		String uiUri = readText(meta == null ? null : meta.path("ui").path("resourceUri"));
		if ((uiUri == null || uiUri.isBlank()) && tool != null && tool.meta != null && tool.meta.isObject()) {
			effectiveMeta = tool.meta;
		}
		String reviewUri = primaryMetaResourceUri(effectiveMeta, "reviewUri", "previewUri");
		String diffUri = readMetaUri(meta, "diffUri", "diff_uri");
		StoredMetaResource review = storeMetaResource(session, call, reviewUri, null, tagIds);
		if (review == null) {
			review = storeMetaFromContent(session, call, result, "review", tagIds);
		}
		if (review != null) {
			payload.put("review_uri", review.uri());
			if (review.mimeType() != null && !review.mimeType().isBlank()) {
				payload.put("review_mime_type", review.mimeType());
			}
		}
		StoredMetaResource diff = storeMetaResource(session, call, diffUri, null, tagIds);
		if (diff == null) {
			diff = storeMetaFromContent(session, call, result, "diff", tagIds);
		}
		if (diff != null) {
			payload.put("diff_uri", diff.uri());
			if (diff.mimeType() != null && !diff.mimeType().isBlank()) {
				payload.put("diff_mime_type", diff.mimeType());
			}
		}
	}

	private StoredMetaResource storeMetaResource(Session session, ToolCall call, String uri) {
		return storeMetaResource(session, call, uri, null, null);
	}

	private StoredMetaResource storeMetaResource(Session session, ToolCall call, String uri, JsonNode csp) {
		return storeMetaResource(session, call, uri, csp, null);
	}

	private StoredMetaResource storeMetaResource(
			Session session,
			ToolCall call,
			String uri,
			JsonNode csp,
			List<UUID> tagIds) {
		if (session == null || call == null || uri == null || uri.isBlank()) {
			return null;
		}
		if (call.serverId == null) {
			return null;
		}
		if (uri.startsWith(UI_BLOB_PREFIX)) {
			return new StoredMetaResource(uri, null);
		}
		ResourcePayload resource = readResourcePayload(session, call, uri, tagIds);
		if (resource == null || resource.bytes() == null || resource.bytes().length == 0) {
			return null;
		}
		String mimeType = resource.mimeType();
		byte[] bytes = resource.bytes();
		Map<String, StoredAssetInfo> storedAssets = null;
		if (uiRewriteEnabled && mimeType != null && mimeType.toLowerCase(Locale.ROOT).contains("html")) {
			String html = new String(bytes, StandardCharsets.UTF_8);
			RewrittenHtml rewritten = rewriteHtmlResourceLinks(session, call, html, uri, tagIds);
			bytes = rewritten.html().getBytes(StandardCharsets.UTF_8);
			storedAssets = rewritten.storedAssets();
		}
		StoredBlob stored = blobStore.store(session.workspace.id, bytes, mimeType);
		String storedUri = UI_BLOB_PREFIX + stored.hash() + assetPathSuffix(uri);
		self.get().registerAssetBundleTransactional(session, call, stored, storedUri, uri, mimeType, csp);
		if (uiRewriteEnabled) {
			registerStoredAssetEntries(session, stored, storedAssets);
		}
		return new StoredMetaResource(storedUri, stored.mimeType());
	}

	private StoredMetaResource storeMetaFromContent(
			Session session,
			ToolCall call,
			JsonNode payload,
			String desiredType,
			List<UUID> tagIds) {
		if (session == null || call == null || payload == null) {
			return null;
		}
		String desired = desiredType == null ? null : desiredType.toLowerCase(Locale.ROOT);
		JsonNode content = payload.get("content");
		if (content == null || content.isNull()) {
			content = payload.get("contents");
		}
		if (content == null || content.isNull()) {
			return null;
		}
		JsonNode firstCandidate = null;
		if (content.isArray()) {
			for (JsonNode entry : content) {
				if (entry == null || entry.isNull()) {
					continue;
				}
				if (desired != null && !desired.isBlank() && !entryMatchesType(entry, desired)) {
					continue;
				}
				if (firstCandidate == null) {
					firstCandidate = entry;
				}
				if (isHtmlEntry(entry)) {
					StoredMetaResource stored = storeMetaFromEntry(session, call, entry, tagIds);
					if (stored != null) {
						return stored;
					}
				}
			}
		}
		else if (content.isObject()) {
			if (desired == null || desired.isBlank() || entryMatchesType(content, desired)) {
				firstCandidate = content;
			}
		}
		if (firstCandidate != null) {
			return storeMetaFromEntry(session, call, firstCandidate, tagIds);
		}
		return null;
	}

	private StoredMetaResource storeMetaFromEntry(Session session, ToolCall call, JsonNode entry, List<UUID> tagIds) {
		if (entry == null || entry.isNull()) {
			return null;
		}
		String uri = readText(entry.get("uri"));
		byte[] bytes = decodeResourceBytes(entry);
		String mimeType = readResourceMimeType(entry);
		if ((bytes == null || bytes.length == 0) && uri != null && !uri.isBlank()) {
			return storeMetaResource(session, call, uri, null, tagIds);
		}
		if (bytes == null || bytes.length == 0) {
			return null;
		}
		if (mimeType == null || mimeType.isBlank()) {
			mimeType = "application/octet-stream";
		}
		Map<String, StoredAssetInfo> storedAssets = null;
		if (uiRewriteEnabled && mimeType.toLowerCase(Locale.ROOT).contains("html")) {
			String html = new String(bytes, StandardCharsets.UTF_8);
			RewrittenHtml rewritten = rewriteHtmlResourceLinks(session, call, html, uri, tagIds);
			bytes = rewritten.html().getBytes(StandardCharsets.UTF_8);
			storedAssets = rewritten.storedAssets();
		}
		StoredBlob stored = blobStore.store(session.workspace.id, bytes, mimeType);
		String storedUri = UI_BLOB_PREFIX + stored.hash() + assetPathSuffix(uri);
		self.get().registerAssetBundleTransactional(session, call, stored, storedUri, uri, mimeType, null);
		if (uiRewriteEnabled) {
			registerStoredAssetEntries(session, stored, storedAssets);
		}
		return new StoredMetaResource(storedUri, stored.mimeType());
	}

	private boolean isHtmlEntry(JsonNode entry) {
		if (entry == null || entry.isNull()) {
			return false;
		}
		String mimeType = readResourceMimeType(entry);
		if (mimeType != null && mimeType.toLowerCase(Locale.ROOT).contains("html")) {
			return true;
		}
		String type = entry.path("type").asText(null);
		return type != null && type.toLowerCase(Locale.ROOT).contains("html");
	}

	private boolean entryMatchesType(JsonNode entry, String desiredType) {
		if (entry == null || entry.isNull() || desiredType == null || desiredType.isBlank()) {
			return false;
		}
		String type = entry.path("type").asText(null);
		if (type == null || type.isBlank()) {
			return false;
		}
		return type.trim().equalsIgnoreCase(desiredType);
	}

	private ResourcePayload readResourcePayload(Session session, ToolCall call, String uri, List<UUID> tagIds) {
		if (session == null || call == null || uri == null || uri.isBlank()) {
			return null;
		}
		JsonNode response;
		try {
			if (tagIds == null || tagIds.isEmpty()) {
				response = mcpRegistry.readResource(session.workspace.id, call.serverId, uri);
			}
			else {
				response = mcpRegistry.readResource(session.workspace.id, call.serverId, uri, tagIds);
			}
		}
		catch (RuntimeException ex) {
			if (isResourceNotFound(ex)) {
				return null;
			}
			throw ex;
		}
		if (response == null || response.isNull()) {
			return null;
		}
		if (response.isTextual()) {
			return new ResourcePayload(response.asText().getBytes(StandardCharsets.UTF_8), "text/plain");
		}
		JsonNode contents = response.has("contents") ? response.get("contents") : response.get("content");
		if (contents == null || contents.isNull()) {
			contents = response;
		}
		JsonNode entry = null;
		if (contents.isArray() && contents.size() > 0) {
			entry = contents.get(0);
		}
		else if (contents.isObject()) {
			entry = contents;
		}
		if (entry == null || entry.isNull()) {
			return null;
		}
		byte[] bytes = decodeResourceBytes(entry);
		if (bytes == null) {
			return null;
		}
		String mimeType = readResourceMimeType(entry);
		return new ResourcePayload(bytes, mimeType);
	}

	private boolean isResourceNotFound(Throwable throwable) {
		if (throwable == null) {
			return false;
		}
		String message = throwable.getMessage();
		if (message != null) {
			String normalized = message.toLowerCase(Locale.ROOT);
			if (normalized.contains("resource not found") || normalized.contains("\"code\":-32000")) {
				return true;
			}
		}
		return isResourceNotFound(throwable.getCause());
	}

	private RewrittenHtml rewriteHtmlResourceLinks(
			Session session,
			ToolCall call,
			String html,
			String rootUri,
			List<UUID> tagIds) {
		if (html == null || html.isBlank()) {
			return new RewrittenHtml(html == null ? "" : html, Map.of());
		}
		List<HtmlResourceMatch> matches = new ArrayList<>();
		matches.addAll(findHtmlResourceMatches(html, "link", "href"));
		matches.addAll(findHtmlResourceMatches(html, "script", "src"));
		if (matches.isEmpty()) {
			return new RewrittenHtml(html, Map.of());
		}
		Map<String, StoredAssetInfo> storedAssets = new HashMap<>();
		List<HtmlReplacement> replacements = new ArrayList<>();
		for (HtmlResourceMatch match : matches) {
			ResolvedUrl resolved = resolveHtmlUrl(match.value(), rootUri);
			if (resolved == null) {
				continue;
			}
			String target = resolved.url();
			if (!"ui".equals(resolved.scheme())) {
				continue;
			}
			if (target.startsWith(UI_BLOB_PREFIX)) {
				replacements.add(new HtmlReplacement(match.start(), match.end(), target));
				continue;
			}
			String storedUri = storedAssetUri(session, call, target, storedAssets, tagIds);
			if (storedUri == null || storedUri.isBlank()) {
				continue;
			}
			replacements.add(new HtmlReplacement(match.start(), match.end(), storedUri));
		}
		if (replacements.isEmpty()) {
			return new RewrittenHtml(html, storedAssets);
		}
		replacements.sort((a, b) -> Integer.compare(b.start(), a.start()));
		StringBuilder builder = new StringBuilder(html);
		for (HtmlReplacement replacement : replacements) {
			if (replacement.start() < 0
					|| replacement.end() > builder.length()
					|| replacement.start() >= replacement.end()) {
				continue;
			}
			builder.replace(replacement.start(), replacement.end(), replacement.value());
		}
		return new RewrittenHtml(builder.toString(), storedAssets);
	}

	private String rewriteJavaScriptResourceLinks(
			Session session,
			ToolCall call,
			String script,
			String rootUri,
			Map<String, StoredAssetInfo> storedAssets,
			List<UUID> tagIds) {
		if (script == null || script.isBlank()) {
			return script == null ? "" : script;
		}
		List<HtmlReplacement> replacements = new ArrayList<>();
		collectJavaScriptImportReplacements(
			session,
			call,
			script,
			rootUri,
			storedAssets,
			replacements,
			Pattern.compile("(?m)(?:^|\\n|;)\\s*import\\s+(?:[^\"'\\n;]*?\\s+from\\s+)?([\"'])([^\"'\\n]+)\\1"),
			tagIds
		);
		collectJavaScriptImportReplacements(
			session,
			call,
			script,
			rootUri,
			storedAssets,
			replacements,
			Pattern.compile("(?m)(?:^|\\n|;)\\s*export\\s+[^\"'\\n;]*?\\s+from\\s+([\"'])([^\"'\\n]+)\\1"),
			tagIds
		);
		collectJavaScriptImportReplacements(
			session,
			call,
			script,
			rootUri,
			storedAssets,
			replacements,
			Pattern.compile("(?i)\\bimport\\s*\\(\\s*([\"'])([^\"']+)\\1\\s*\\)"),
			tagIds
		);
		if (replacements.isEmpty()) {
			return script;
		}
		replacements.sort((a, b) -> Integer.compare(b.start(), a.start()));
		StringBuilder builder = new StringBuilder(script);
		for (HtmlReplacement replacement : replacements) {
			if (replacement.start() < 0
					|| replacement.end() > builder.length()
					|| replacement.start() >= replacement.end()) {
				continue;
			}
			builder.replace(replacement.start(), replacement.end(), replacement.value());
		}
		return builder.toString();
	}

	private void collectJavaScriptImportReplacements(
			Session session,
			ToolCall call,
			String script,
			String rootUri,
			Map<String, StoredAssetInfo> storedAssets,
			List<HtmlReplacement> replacements,
			Pattern pattern,
			List<UUID> tagIds) {
		Matcher matcher = pattern.matcher(script);
		while (matcher.find()) {
			String raw = matcher.group(2);
			if (raw == null || raw.isBlank()) {
				continue;
			}
			ResolvedUrl resolved = resolveHtmlUrl(raw, rootUri);
			if (resolved == null) {
				continue;
			}
			String target = resolved.url();
			if (!"ui".equals(resolved.scheme())) {
				continue;
			}
			if (target.startsWith(UI_BLOB_PREFIX)) {
				replacements.add(new HtmlReplacement(matcher.start(2), matcher.end(2), target));
				continue;
			}
			String storedUri = storedAssetUri(session, call, target, storedAssets, tagIds);
			if (storedUri == null || storedUri.isBlank()) {
				continue;
			}
			replacements.add(new HtmlReplacement(matcher.start(2), matcher.end(2), storedUri));
		}
	}

	private String storedAssetUri(
			Session session,
			ToolCall call,
			String target,
			Map<String, StoredAssetInfo> storedAssets,
			List<UUID> tagIds) {
		StoredAssetInfo storedInfo = storedAssets.get(target);
		if (storedInfo != null) {
			return storedInfo.storedUri();
		}
		ResourcePayload payload = readResourcePayload(session, call, target, tagIds);
		if (payload == null || payload.bytes() == null || payload.bytes().length == 0) {
			return null;
		}
		byte[] bytes = payload.bytes();
		String mimeType = payload.mimeType();
		if (mimeType != null) {
			String normalizedMimeType = mimeType.toLowerCase(Locale.ROOT);
			if (normalizedMimeType.contains("javascript")
					|| normalizedMimeType.contains("ecmascript")
					|| normalizedMimeType.equals("module")) {
				String rewritten = rewriteJavaScriptResourceLinks(session, call, new String(bytes, StandardCharsets.UTF_8), target, storedAssets, tagIds);
				bytes = rewritten.getBytes(StandardCharsets.UTF_8);
			}
		}
		StoredBlob stored = blobStore.store(session.workspace.id, bytes, mimeType);
		String storedUri = UI_BLOB_PREFIX + stored.hash() + assetPathSuffix(target);
		storedAssets.put(target, new StoredAssetInfo(storedUri, mimeType));
		return storedUri;
	}

	private List<HtmlResourceMatch> findHtmlResourceMatches(String html, String tag, String attr) {
		List<HtmlResourceMatch> matches = new ArrayList<>();
		Pattern pattern = Pattern.compile("(?i)<" + tag + "\\b[^>]*\\b" + attr + "\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");
		Matcher matcher = pattern.matcher(html);
		while (matcher.find()) {
			int start;
			int end;
			String value;
			if (matcher.group(2) != null) {
				start = matcher.start(2);
				end = matcher.end(2);
				value = matcher.group(2);
			}
			else if (matcher.group(3) != null) {
				start = matcher.start(3);
				end = matcher.end(3);
				value = matcher.group(3);
			}
			else {
				start = matcher.start(4);
				end = matcher.end(4);
				value = matcher.group(4);
			}
			if (value == null || value.isBlank()) {
				continue;
			}
			matches.add(new HtmlResourceMatch(start, end, value));
		}
		return matches;
	}

	private ResolvedUrl resolveHtmlUrl(String raw, String base) {
		if (raw == null) {
			return null;
		}
		String trimmed = raw.trim();
		if (trimmed.isBlank() || trimmed.startsWith("#")) {
			return null;
		}
		String scheme = readScheme(trimmed);
		if (scheme != null) {
			String normalized = scheme.toLowerCase(Locale.ROOT);
			if (HTML_IGNORED_SCHEMES.contains(normalized)) {
				return null;
			}
			if (!normalized.equals("ui")) {
				return null;
			}
			return new ResolvedUrl(trimmed, normalized);
		}
		if (base == null || base.isBlank()) {
			return null;
		}
		try {
			URI baseUri = URI.create(base);
			URI resolved = baseUri.resolve(trimmed);
			String resolvedScheme = resolved.getScheme();
			if (resolvedScheme == null) {
				return null;
			}
			String normalized = resolvedScheme.toLowerCase(Locale.ROOT);
			if (HTML_IGNORED_SCHEMES.contains(normalized)) {
				return null;
			}
			if (!normalized.equals("ui")) {
				return null;
			}
			return new ResolvedUrl(resolved.toString(), normalized);
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private void registerStoredAssetEntries(
			Session session,
			StoredBlob rootStored,
			Map<String, StoredAssetInfo> storedAssets) {
		if (session == null
				|| session.workspace == null
				|| rootStored == null
				|| storedAssets == null
				|| storedAssets.isEmpty()) {
			return;
		}
		WorkspaceAssetBundle bundle = entityManager.createQuery(
				"select b from WorkspaceAssetBundle b where b.workspace.id = :workspaceId "
					+ "and b.rootBlobHash = :hash",
				WorkspaceAssetBundle.class
			)
			.setParameter("workspaceId", session.workspace.id)
			.setParameter("hash", rootStored.hash())
			.getResultStream()
			.findFirst()
			.orElse(null);
		if (bundle == null) {
			return;
		}
		for (Map.Entry<String, StoredAssetInfo> entry : storedAssets.entrySet()) {
			String sourceUri = entry.getKey();
			StoredAssetInfo storedInfo = entry.getValue();
			if (storedInfo == null) {
				continue;
			}
			String storedUri = storedInfo.storedUri();
			if (storedUri == null || storedUri.isBlank()) {
				continue;
			}
			String resourcePath = bundleResourcePath(storedUri);
			if (resourcePath == null || resourcePath.isBlank()) {
				continue;
			}
			String blobHash = extractBlobHash(storedUri);
			if (blobHash == null || blobHash.isBlank()) {
				continue;
			}
			registerAssetBundleEntry(bundle, resourcePath, blobHash, sourceUri, storedInfo.mimeType());
		}
	}

	private String extractBlobHash(String storedUri) {
		if (storedUri == null || storedUri.isBlank() || !storedUri.startsWith(UI_BLOB_PREFIX)) {
			return null;
		}
		String path = storedUri.substring(UI_BLOB_PREFIX.length());
		int slashIndex = path.indexOf('/');
		if (slashIndex < 0) {
			return path.isBlank() ? null : path;
		}
		if (slashIndex == 0) {
			return null;
		}
		return path.substring(0, slashIndex);
	}

	private String readScheme(String value) {
		if (value == null) {
			return null;
		}
		int index = value.indexOf(':');
		if (index <= 0) {
			return null;
		}
		String scheme = value.substring(0, index);
		return scheme.isBlank() ? null : scheme;
	}

	private String assetPathSuffix(String target) {
		if (target == null || target.isBlank()) {
			return "";
		}
		if (target.startsWith(UI_BLOB_PREFIX)) {
			String path = target.substring(UI_BLOB_PREFIX.length());
			int slashIndex = path.indexOf('/');
			if (slashIndex < 0 || slashIndex == path.length() - 1) {
				return "";
			}
			return path.substring(slashIndex);
		}
		try {
			URI uri = URI.create(target);
			String path = uri.getPath();
			if (path == null || path.isBlank() || "/".equals(path)) {
				return "";
			}
			return path.startsWith("/") ? path : "/" + path;
		}
		catch (IllegalArgumentException ex) {
			return "";
		}
	}

	private byte[] decodeResourceBytes(JsonNode entry) {
		if (entry == null || entry.isNull()) {
			return null;
		}
		if (entry.isTextual()) {
			return entry.asText().getBytes(StandardCharsets.UTF_8);
		}
		JsonNode textNode = entry.get("text");
		if (textNode != null && textNode.isTextual()) {
			return textNode.asText().getBytes(StandardCharsets.UTF_8);
		}
		JsonNode base64Node = entry.get("base64");
		if (base64Node != null && base64Node.isTextual()) {
			byte[] decoded = decodeBase64(base64Node.asText());
			return decoded != null ? decoded : base64Node.asText().getBytes(StandardCharsets.UTF_8);
		}
		JsonNode bytesNode = entry.get("bytes");
		if (bytesNode != null) {
			if (bytesNode.isArray()) {
				byte[] bytes = new byte[bytesNode.size()];
				for (int i = 0; i < bytesNode.size(); i++) {
					bytes[i] = (byte) bytesNode.get(i).asInt();
				}
				return bytes;
			}
			if (bytesNode.isTextual()) {
				byte[] decoded = decodeBase64(bytesNode.asText());
				return decoded != null ? decoded : bytesNode.asText().getBytes(StandardCharsets.UTF_8);
			}
		}
		JsonNode dataNode = entry.get("data");
		if (dataNode != null && dataNode.isTextual()) {
			String data = dataNode.asText();
			int commaIndex = data.indexOf(',');
			if (data.startsWith("data:") && commaIndex > 0) {
				byte[] decoded = decodeBase64(data.substring(commaIndex + 1));
				return decoded != null ? decoded : data.substring(commaIndex + 1).getBytes(StandardCharsets.UTF_8);
			}
			byte[] decoded = decodeBase64(data);
			return decoded != null ? decoded : data.getBytes(StandardCharsets.UTF_8);
		}
		return null;
	}

	private String readResourceMimeType(JsonNode entry) {
		if (entry == null || entry.isNull()) {
			return null;
		}
		JsonNode mimeNode = entry.get("mimeType");
		if (mimeNode == null) {
			mimeNode = entry.get("mime_type");
		}
		if (mimeNode != null && mimeNode.isTextual()) {
			return mimeNode.asText();
		}
		JsonNode typeNode = entry.get("type");
		if (typeNode != null && typeNode.isTextual() && typeNode.asText().contains("/")) {
			return typeNode.asText();
		}
		return null;
	}

	private void registerAssetBundle(
			Session session,
			ToolCall call,
			StoredBlob stored,
			String storedUri,
			String sourceUri,
			String mimeType,
			JsonNode csp) {
		if (session == null
				|| session.workspace == null
				|| stored == null
				|| stored.hash() == null
				|| stored.hash()
					.isBlank()) {
			return;
		}
		String rootPath = bundleResourcePath(storedUri);
		if (rootPath == null || rootPath.isBlank()) {
			rootPath = "index.html";
		}
		WorkspaceAssetBundle bundle = entityManager.createQuery(
				"select b from WorkspaceAssetBundle b where b.workspace.id = :workspaceId "
					+ "and b.rootBlobHash = :hash",
				WorkspaceAssetBundle.class
			)
			.setParameter("workspaceId", session.workspace.id)
			.setParameter("hash", stored.hash())
			.getResultStream()
			.findFirst()
			.orElse(null);
		if (bundle == null) {
			bundle = new WorkspaceAssetBundle();
			bundle.workspace = session.workspace;
			bundle.rootBlobHash = stored.hash();
			bundle.rootPath = rootPath;
			entityManager.persist(bundle);
		}
		bundle.type = "ui";
		if (call != null && call.serverId != null) {
			bundle.mcpServer = entityManager.find(be.celerex.polymr.model.McpServer.class, call.serverId);
		}
		bundle.rootSourceUri = sourceUri;
		bundle.rootMimeType = mimeType;
		if (csp != null && !csp.isNull() && !csp.isMissingNode()) {
			bundle.cspJson = csp.toString();
		}
		registerAssetBundleEntry(bundle, rootPath, stored.hash(), sourceUri, mimeType);
	}

	@Transactional
	void registerAssetBundleTransactional(
			Session session,
			ToolCall call,
			StoredBlob stored,
			String storedUri,
			String sourceUri,
			String mimeType,
			JsonNode csp) {
		registerAssetBundle(session, call, stored, storedUri, sourceUri, mimeType, csp);
	}

	private void registerAssetBundleEntry(
			WorkspaceAssetBundle bundle,
			String resourcePath,
			String blobHash,
			String sourceUri,
			String mimeType) {
		if (bundle == null
				|| resourcePath == null
				|| resourcePath.isBlank()
				|| blobHash == null
				|| blobHash.isBlank()) {
			return;
		}
		WorkspaceAssetBundleEntry entry = entityManager.createQuery(
				"select e from WorkspaceAssetBundleEntry e where e.bundle.id = :bundleId "
					+ "and e.resourcePath = :resourcePath",
				WorkspaceAssetBundleEntry.class
			)
			.setParameter("bundleId", bundle.id)
			.setParameter("resourcePath", resourcePath)
			.getResultStream()
			.findFirst()
			.orElse(null);
		if (entry == null) {
			entry = new WorkspaceAssetBundleEntry();
			entry.bundle = bundle;
			entry.resourcePath = resourcePath;
		}
		entry.blobHash = blobHash;
		entry.sourceUri = sourceUri;
		entry.mimeType = mimeType;
		if (entry.id == null) {
			entityManager.persist(entry);
		}
	}

	private String bundleResourcePath(String uri) {
		if (uri == null || uri.isBlank() || !uri.startsWith(UI_BLOB_PREFIX)) {
			return null;
		}
		String path = uri.substring(UI_BLOB_PREFIX.length());
		int slashIndex = path.indexOf('/');
		if (slashIndex < 0 || slashIndex == path.length() - 1) {
			return "index.html";
		}
		return path.substring(slashIndex + 1);
	}

	private String primaryMetaResourceUri(JsonNode meta, String preferredField, String fallbackField) {
		String uiUri = readText(meta == null ? null : meta.path("ui").path("resourceUri"));
		if (uiUri != null && !uiUri.isBlank()) {
			return uiUri;
		}
		return readMetaUri(meta, preferredField, fallbackField);
	}

	private String readMetaUri(JsonNode meta, String primaryField, String alternateField) {
		String uri = readText(meta == null ? null : meta.get(primaryField));
		if (uri == null || uri.isBlank()) {
			uri = readText(meta == null ? null : meta.get(alternateField));
		}
		return uri;
	}

	private String readText(JsonNode node) {
		if (node == null || node.isNull()) {
			return null;
		}
		if (node.isTextual()) {
			return node.asText();
		}
		return node.toString();
	}

	private record ResourcePayload(byte[] bytes, String mimeType) {}

	private record StoredMetaResource(String uri, String mimeType) {}

	private record StoredAssetInfo(String storedUri, String mimeType) {}

	private record HtmlResourceMatch(int start, int end, String value) {}

	private record HtmlReplacement(int start, int end, String value) {}

	private record ResolvedUrl(String url, String scheme) {}

	private record RewrittenHtml(String html, Map<String, StoredAssetInfo> storedAssets) {}

	private String extractToolContentText(JsonNode result) {
		if (result == null || result.isNull()) {
			return null;
		}
		JsonNode content = result.get("content");
		if (content == null || !content.isArray()) {
			return null;
		}
		for (JsonNode entry : content) {
			if (entry == null || entry.isNull()) {
				continue;
			}
			String type = entry.path("type").asText(null);
			if (!"text".equals(type)) {
				continue;
			}
			String text = entry.path("text").asText(null);
			if (text != null && !text.isBlank()) {
				return text;
			}
		}
		return null;
	}

	private String extractToolText(JsonNode result) {
		if (result == null || result.isNull()) {
			return null;
		}
		if (result.isTextual()) {
			return result.asText();
		}
		JsonNode meta = result.get("_meta");
		if (meta != null && meta.isObject()) {
			JsonNode displayMessage = meta.get("displayMessage");
			if (displayMessage != null && displayMessage.isTextual()) {
				String text = displayMessage.asText();
				if (!text.isBlank()) {
					return text;
				}
			}
		}
		JsonNode message = result.get("message");
		if (message != null && message.isTextual()) {
			String text = message.asText();
			if (!text.isBlank()) {
				return text;
			}
		}
		String contentText = extractToolContentText(result);
		if (contentText != null) {
			return contentText;
		}
		return result.toString();
	}

	private boolean shouldFailPreview(JsonNode preview) {
		if (preview == null || preview.isNull()) {
			return false;
		}
		boolean isError = preview.path("isError").asBoolean(false) || preview.path("error").asBoolean(false);
		if (!isError) {
			return false;
		}
		JsonNode meta = preview.get("_meta");
		if (meta == null || meta.isNull() || !meta.isObject()) {
			return true;
		}
		String reviewUri = readText(meta.get("reviewUri"));
		if (reviewUri == null || reviewUri.isBlank()) {
			reviewUri = readText(meta.get("review_uri"));
		}
		return reviewUri == null || reviewUri.isBlank();
	}

	private String previewErrorMessage(JsonNode preview) {
		if (preview == null || preview.isNull()) {
			return null;
		}
		JsonNode message = preview.get("message");
		if (message != null && message.isTextual()) {
			String text = message.asText();
			if (!text.isBlank()) {
				return text;
			}
		}
		return extractToolText(preview);
	}

	private String resolveToolCallSummary(McpServerTool tool, JsonNode arguments) {
		if (tool == null) {
			return "";
		}
		String template = tool.intentTemplate;
		if (template == null || template.isBlank()) {
			template = tool.description;
		}
		if (template == null || template.isBlank()) {
			template = tool.toolName;
		}
		if (template == null || template.isBlank()) {
			return "";
		}
		String rendered = renderIntentTemplate(template, arguments);
		return rendered == null ? "" : rendered;
	}

	private String renderIntentTemplate(String template, JsonNode arguments) {
		if (template == null || template.isBlank()) {
			return "";
		}
		int[] index = new int[] { 0 };
		List<TemplateNode> nodes = parseTemplate(template, index, '\0');
		RenderResult rendered = renderGroup(nodes, arguments, java.util.Map.of(), false);
		if (rendered == null) {
			return "";
		}
		return rendered.text;
	}

	private List<TemplateNode> parseTemplate(String template, int[] index, char terminator) {
		List<TemplateNode> nodes = new ArrayList<>();
		StringBuilder text = new StringBuilder();
		int length = template.length();
		while (index[0] < length) {
			char ch = template.charAt(index[0]);
			if (terminator != '\0' && ch == terminator) {
				index[0]++;
				break;
			}
			if (ch == '[') {
				flushTextNode(nodes, text);
				index[0]++;
				List<TemplateNode> child = parseTemplate(template, index, ']');
				nodes.add(TemplateNode.group(child));
				continue;
			}
			if (ch == '{') {
				flushTextNode(nodes, text);
				int start = ++index[0];
				int end = template.indexOf('}', start);
				if (end == -1) {
					text.append('{');
					index[0] = start;
					continue;
				}
				String placeholder = template.substring(start, end);
				index[0] = end + 1;
				nodes.add(TemplateNode.placeholder(placeholder));
				continue;
			}
			text.append(ch);
			index[0]++;
		}
		flushTextNode(nodes, text);
		return nodes;
	}

	private void flushTextNode(List<TemplateNode> nodes, StringBuilder text) {
		if (text.length() == 0) {
			return;
		}
		nodes.add(TemplateNode.text(text.toString()));
		text.setLength(0);
	}

	private RenderResult renderGroup(
			List<TemplateNode> nodes,
			JsonNode arguments,
			java.util.Map<String, Integer> indexByRoot,
			boolean optionalContext) {
		StringBuilder output = new StringBuilder();
		boolean missingRequired = false;
		for (TemplateNode node : nodes) {
			if (node == null) {
				continue;
			}
			if (node.type == TemplateNode.Type.TEXT) {
				output.append(node.text);
				continue;
			}
			if (node.type == TemplateNode.Type.PLACEHOLDER) {
				String value = resolveIntentValue(arguments, node.key, indexByRoot);
				if ((value == null || value.isBlank()) && node.defaultValue != null) {
					value = node.defaultValue;
				}
				if (value == null || value.isBlank()) {
					missingRequired = true;
					output.append("{").append(node.raw).append("}");
				}
				else {
					output.append(value);
				}
				continue;
			}
			if (node.type == TemplateNode.Type.GROUP) {
				RenderResult rendered = renderOptionalGroup(node.children, arguments, indexByRoot);
				output.append(rendered.text);
			}
		}
		if (optionalContext && missingRequired) {
			return new RenderResult("", true);
		}
		return new RenderResult(output.toString(), missingRequired);
	}

	private RenderResult renderOptionalGroup(
			List<TemplateNode> nodes,
			JsonNode arguments,
			java.util.Map<String, Integer> indexByRoot) {
		List<String> arrayRoots = collectArrayRoots(nodes, arguments, new java.util.LinkedHashSet<>());
		if (arrayRoots.isEmpty()) {
			return renderGroup(nodes, arguments, indexByRoot, true);
		}
		boolean hasIndexContext = false;
		if (indexByRoot != null && !indexByRoot.isEmpty()) {
			for (String root : arrayRoots) {
				if (indexByRoot.containsKey(root)) {
					hasIndexContext = true;
					break;
				}
			}
		}
		if (hasIndexContext) {
			return renderGroup(nodes, arguments, indexByRoot, true);
		}
		List<String> parts = new ArrayList<>();
		boolean anyRendered = false;
		for (String root : arrayRoots) {
			JsonNode list = arguments == null ? null : arguments.get(root);
			if (list == null || !list.isArray()) {
				continue;
			}
			for (int i = 0; i < list.size(); i++) {
				RenderResult group = renderGroup(nodes, arguments, java.util.Map.of(root, i), true);
				if (group.ok) {
					continue;
				}
				String text = group.text.trim();
				if (!text.isEmpty()) {
					parts.add(text);
					anyRendered = true;
				}
			}
		}
		if (!anyRendered) {
			return new RenderResult("", false);
		}
		return new RenderResult(String.join(", ", parts), false);
	}

	private List<String> collectArrayRoots(List<TemplateNode> nodes, JsonNode arguments, java.util.Set<String> roots) {
		if (nodes == null || nodes.isEmpty()) {
			return new ArrayList<>(roots);
		}
		for (TemplateNode node : nodes) {
			if (node == null) {
				continue;
			}
			if (node.type == TemplateNode.Type.PLACEHOLDER) {
				String key = node.key == null ? "" : node.key;
				String root = key.split("\\.", 2)[0];
				if (!root.isBlank() && arguments != null && arguments.has(root) && arguments.get(root).isArray()) {
					roots.add(root);
				}
				continue;
			}
			if (node.type == TemplateNode.Type.GROUP) {
				collectArrayRoots(node.children, arguments, roots);
			}
		}
		return new ArrayList<>(roots);
	}

	private String resolveIntentValue(JsonNode payload, String path, java.util.Map<String, Integer> indexByRoot) {
		if (payload == null || path == null || path.isBlank()) {
			return null;
		}
		String[] parts = java.util.Arrays
			.stream(path.split("\\."))
			.map(String::trim)
			.filter(part -> !part.isBlank())
			.toArray(String[]::new);
		if (parts.length == 0) {
			return null;
		}
		JsonNode current = payload;
		int startIndex = 0;
		String root = parts[0];
		if (indexByRoot != null
				&& indexByRoot.containsKey(root)
				&& payload.has(root)
				&& payload.get(root)
					.isArray()) {
			int index = indexByRoot.get(root);
			JsonNode list = payload.get(root);
			if (index >= list.size()) {
				return null;
			}
			current = list.get(index);
			startIndex = 1;
		}
		for (int i = startIndex; i < parts.length; i++) {
			String part = parts[i];
			if (current == null || current.isNull()) {
				return null;
			}
			if (current.isArray()) {
				ArrayNode values = objectMapper.createArrayNode();
				for (JsonNode item : current) {
					if (item != null && item.isObject()) {
						JsonNode next = item.get(part);
						if (next != null && !next.isNull() && !(next.isTextual() && next.asText().isBlank())) {
							values.add(next);
						}
					}
				}
				if (values.isEmpty()) {
					return null;
				}
				current = values;
				continue;
			}
			if (!current.isObject()) {
				return null;
			}
			current = current.get(part);
		}
		if (current == null || current.isNull()) {
			return null;
		}
		if (current.isArray()) {
			boolean hasContainerValue = false;
			for (JsonNode value : current) {
				if (value != null && !value.isNull() && (value.isArray() || value.isObject())) {
					hasContainerValue = true;
					break;
				}
			}
			if (hasContainerValue) {
				return prettyPrintJson(current);
			}
			List<String> values = new ArrayList<>();
			for (JsonNode value : current) {
				if (value == null || value.isNull()) {
					continue;
				}
				if (value.isTextual()) {
					String text = value.asText();
					if (!text.isBlank()) {
						values.add(text);
					}
					continue;
				}
				if (value.isNumber() || value.isBoolean()) {
					values.add(String.valueOf(value));
					continue;
				}
				values.add(value.toString());
			}
			return values.isEmpty() ? null : String.join(", ", values);
		}
		if (current.isTextual()) {
			String text = current.asText();
			return text.isBlank() ? null : text;
		}
		if (current.isNumber() || current.isBoolean()) {
			return String.valueOf(current);
		}
		if (current.isObject()) {
			return prettyPrintJson(current);
		}
		return null;
	}

	private String prettyPrintJson(JsonNode value) {
		if (value == null || value.isNull()) {
			return null;
		}
		try {
			com.fasterxml.jackson.core.util.DefaultPrettyPrinter printer = new com.fasterxml.jackson.core.util.DefaultPrettyPrinter();
			com.fasterxml.jackson.core.util.DefaultIndenter indenter = new com.fasterxml.jackson.core.util.DefaultIndenter("\t", com.fasterxml.jackson.core.util.DefaultIndenter.SYS_LF);
			printer.indentObjectsWith(indenter);
			printer.indentArraysWith(indenter);
			return objectMapper.writer(printer).writeValueAsString(value);
		}
		catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
			return value.toString();
		}
	}

	private static class TemplateNode {
		enum Type {
			TEXT,
			PLACEHOLDER,
			GROUP
		}

		final Type type;
		final String text;
		final String raw;
		final String key;
		final String defaultValue;
		final List<TemplateNode> children;

		private TemplateNode(
				Type type,
				String text,
				String raw,
				String key,
				String defaultValue,
				List<TemplateNode> children) {
			this.type = type;
			this.text = text;
			this.raw = raw;
			this.key = key;
			this.defaultValue = defaultValue;
			this.children = children;
		}

		static TemplateNode text(String value) {
			return new TemplateNode(Type.TEXT, value, null, null, null, List.of());
		}

		static TemplateNode group(List<TemplateNode> children) {
			return new TemplateNode(Type.GROUP, null, null, null, null, children);
		}

		static TemplateNode placeholder(String raw) {
			String trimmed = raw == null ? null : raw.trim();
			String key = trimmed;
			String defaultValue = null;
			if (key != null) {
				int pipe = key.indexOf('|');
				if (pipe >= 0) {
					defaultValue = key.substring(pipe + 1).trim();
					key = key.substring(0, pipe).trim();
				}
			}
			return new TemplateNode(Type.PLACEHOLDER, null, raw, key, defaultValue, List.of());
		}
	}

	private record RenderResult(String text, boolean ok) {}

	private JsonNode resolveToolResultPayload(JsonNode result) {
		if (result == null || result.isNull()) {
			return result;
		}
		boolean fullMode = toolResultMode != null && toolResultMode.equalsIgnoreCase("full");
		if (!result.isObject()) {
			return result;
		}
		ObjectNode copy = result.deepCopy();
		copy.remove("_meta");
		if (fullMode) {
			return copy;
		}
		JsonNode content = copy.get("content");
		if (content != null && !content.isNull()) {
			String text = extractToolContentText(copy);
			if (text != null && !text.isBlank()) {
				return objectMapper.getNodeFactory().textNode(text);
			}
			ObjectNode contentOnly = objectMapper.createObjectNode();
			contentOnly.set("content", content);
			return contentOnly;
		}
		JsonNode structured = copy.get("structuredContent");
		if (structured != null && !structured.isNull()) {
			ArrayNode contentArray = objectMapper.createArrayNode();
			ObjectNode textEntry = objectMapper.createObjectNode();
			textEntry.put("type", "text");
			textEntry.put("text", structured.toString());
			contentArray.add(textEntry);
			ObjectNode contentOnly = objectMapper.createObjectNode();
			contentOnly.set("content", contentArray);
			return contentOnly;
		}
		return copy;
	}

	private ToolFailure parseToolFailure(RuntimeException exception) {
		if (exception == null) {
			return null;
		}
		try {
			JsonNode node = null;
			if (exception instanceof JsonRpcException jsonRpcException) {
				node = jsonRpcException.error();
			}
			if (node == null) {
				String message = exception.getMessage();
				if (message == null) {
					return null;
				}
				String trimmed = message.trim();
				if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
					return null;
				}
				node = objectMapper.readTree(trimmed);
			}
			if (!node.isObject()) {
				return null;
			}
			String code = node.has("code") ? node.get("code").asText(null) : null;
			String detail = node.has("message") ? node.get("message").asText(null) : null;
			if (detail == null || detail.isBlank()) {
				detail = node.toString();
			}
			JsonNode meta = node.get("_meta");
			if (meta == null || meta.isNull()) {
				JsonNode data = node.get("data");
				if (data != null && data.isObject()) {
					meta = data.get("_meta");
				}
			}
			JsonNode structuredContent = node.get("structuredContent");
			if (structuredContent == null || structuredContent.isNull()) {
				JsonNode data = node.get("data");
				if (data != null && data.isObject()) {
					structuredContent = data.get("structuredContent");
				}
			}
			List<String> requestedScopes = meta != null && meta.isObject() ? readScopeList(meta.get("requested_scopes")) : List.of();
			boolean preview = meta != null && meta.isObject() && meta.path("preview").asBoolean(false);
			String uiResourceUri = null;
			String diffUri = null;
			if (meta != null && meta.isObject()) {
				uiResourceUri = readText(meta.path("ui").path("resourceUri"));
				diffUri = readText(meta.get("diffUri"));
				if (diffUri == null || diffUri.isBlank()) {
					diffUri = readText(meta.get("diff_uri"));
				}
			}
			String formatted = code == null || code.isBlank() ? "Tool error: " + detail : "Tool error (" + code + "): " + detail;
			return new ToolFailure(code, formatted, requestedScopes, preview, uiResourceUri, diffUri, structuredContent);
		}
		catch (Exception ex) {
			LOGGER.debugf(ex, "Failed to parse JSON payload");
			return null;
		}
	}

	private boolean isPreviewFailureToolFailure() {
		return previewFailureMode == null || !previewFailureMode.equalsIgnoreCase("permission_request");
	}

	private boolean shouldPauseAfterToolFailure(Session session) {
		if (session == null) {
			return true;
		}
		List<SessionEvent> events = entityManager.createQuery(
				"select e from SessionEvent e where e.session.id = :sessionId order by e.createdAt desc",
				SessionEvent.class
			)
			.setParameter("sessionId", session.id)
			.setMaxResults(1)
			.getResultList();
		if (events.isEmpty()) {
			return true;
		}
		SessionEventType type = events.get(0).eventType;
		return type != SessionEventType.TOOL_CALL && type != SessionEventType.TOOL_RESULT;
	}

	private boolean shouldReturnHardToolErrorsToUser() {
		return "user".equalsIgnoreCase(hardErrorTurn);
	}

	private Map<String, Object> systemEvent(String message) {
		ObjectNode payload = objectMapper.createObjectNode();
		payload.put("text", message == null ? "" : message);
		return pendingEvent(SessionEventType.SYSTEM, payload);
	}

	private ApprovalDecision approvalDecision(JsonNode payload) {
		if (payload == null) {
			return new ApprovalDecision(null, List.of(), false, null, Map.of(), Map.of());
		}
		String direct = payload.path("decision").asText(null);
		String text = payload.has("text") ? payload.get("text").asText("") : payload.asText("");
		String normalized = direct != null && !direct.isBlank() ? direct.trim().toLowerCase() : text.trim().toLowerCase();
		String decision = null;
		if (normalized.startsWith("approve") || normalized.equals("yes") || normalized.equals("allow")) {
			decision = "allow";
		}
		else if (normalized.startsWith("deny") || normalized.equals("no")) {
			decision = "deny";
		}
		List<String> allowScopes = readStringArray(payload.get("allow_scopes"));
		if (allowScopes.isEmpty() && payload.has("allow_scope") && payload.get("allow_scope").isTextual()) {
			allowScopes = List.of(payload.get("allow_scope").asText());
		}
		boolean remember = payload.path("remember").asBoolean(false);
		String requestId = payload.path("request_id").asText(null);
		Map<String, String> scopeDecisions = readDecisionMap(payload.get("scope_decisions"));
		Map<String, Boolean> scopeRemember = readBooleanMap(payload.get("scope_remember"));
		return new ApprovalDecision(decision, allowScopes, remember, requestId, scopeDecisions, scopeRemember);
	}

	private ApprovalScopePolicy resolveApprovalPolicy(
			ApprovalDecision approval,
			List<Map<String, Object>> pendingRequests,
			List<UUID> serverIds) {
		if (approval == null) {
			return new ApprovalScopePolicy(List.of(), List.of(), List.of(), List.of());
		}
		Map<String, String> scopeDecisions = approval.scopeDecisions == null ? Map.of() : approval.scopeDecisions;
		Map<String, Boolean> scopeRemember = approval.scopeRemember == null ? Map.of() : approval.scopeRemember;
		List<String> allowScopes = approval.allowScopes == null ? List.of() : approval.allowScopes;
		boolean hasScopeDecisions = !scopeDecisions.isEmpty();
		boolean hasAllowScopes = !allowScopes.isEmpty();
		String decision = approval.decision;
		java.util.Set<String> allow = new java.util.LinkedHashSet<>();
		java.util.Set<String> deny = new java.util.LinkedHashSet<>();
		java.util.Set<String> rememberAllow = new java.util.LinkedHashSet<>();
		java.util.Set<String> rememberDeny = new java.util.LinkedHashSet<>();
		if (hasScopeDecisions) {
			for (Map.Entry<String, String> entry : scopeDecisions.entrySet()) {
				String scope = entry.getKey();
				if (scope == null || scope.isBlank()) {
					continue;
				}
				String scopeDecision = entry.getValue();
				boolean allowed = "allow".equalsIgnoreCase(scopeDecision);
				if (allowed) {
					allow.add(scope);
					boolean rememberScope = scopeRemember.containsKey(scope) ? Boolean.TRUE.equals(scopeRemember.get(scope)) : approval.remember;
					if (rememberScope) {
						rememberAllow.add(scope);
					}
				}
				else if (scopeDecision != null) {
					deny.add(scope);
					boolean rememberScope = scopeRemember.containsKey(scope) ? Boolean.TRUE.equals(scopeRemember.get(scope)) : approval.remember;
					if (rememberScope) {
						rememberDeny.add(scope);
					}
				}
			}
		}
		else if (hasAllowScopes) {
			for (String scope : allowScopes) {
				if (scope == null || scope.isBlank()) {
					continue;
				}
				allow.add(scope);
				if (approval.remember) {
					rememberAllow.add(scope);
				}
			}
		}
		return new ApprovalScopePolicy(
			new ArrayList<>(allow),
			new ArrayList<>(deny),
			new ArrayList<>(rememberAllow),
			new ArrayList<>(rememberDeny)
		);
	}

	private ObjectNode applyApprovalScopes(ObjectNode snapshot, List<String> allowScopes, List<String> logicalNodeIds) {
		if (snapshot == null || allowScopes == null || allowScopes.isEmpty()) {
			return snapshot;
		}
		if (logicalNodeIds == null || logicalNodeIds.isEmpty()) {
			return snapshot;
		}
		ObjectNode nodes = snapshot.get("nodes") instanceof ObjectNode node ? node : snapshot.putObject("nodes");
		for (String logicalNodeId : logicalNodeIds) {
			if (logicalNodeId == null || logicalNodeId.isBlank()) {
				continue;
			}
			ObjectNode logicalNode = nodes.get(logicalNodeId) instanceof ObjectNode logicalNodeObj ? logicalNodeObj : null;
			if (logicalNode == null) {
				continue;
			}
			ObjectNode scopes = logicalNode.get("scopes") instanceof ObjectNode scopesNode
				? scopesNode
				: logicalNode.putObject("scopes");
			ArrayNode allowArray = scopes.get("allow_scopes") instanceof ArrayNode arr ? arr : scopes.putArray("allow_scopes");
			java.util.Set<String> existing = new java.util.LinkedHashSet<>();
			allowArray.forEach(entry -> {
				if (entry.isTextual()) {
					existing.add(entry.asText());
				}
			});
			for (String scope : allowScopes) {
				if (scope != null && !scope.isBlank()) {
					existing.add(scope);
				}
			}
			allowArray.removeAll();
			existing.forEach(allowArray::add);
		}
		return snapshot;
	}

	private ObjectNode applyApprovalDenyScopes(
			ObjectNode snapshot,
			List<String> denyScopes,
			List<String> logicalNodeIds) {
		if (snapshot == null || denyScopes == null || denyScopes.isEmpty()) {
			return snapshot;
		}
		if (logicalNodeIds == null || logicalNodeIds.isEmpty()) {
			return snapshot;
		}
		ObjectNode nodes = snapshot.get("nodes") instanceof ObjectNode node ? node : snapshot.putObject("nodes");
		for (String logicalNodeId : logicalNodeIds) {
			if (logicalNodeId == null || logicalNodeId.isBlank()) {
				continue;
			}
			ObjectNode logicalNode = nodes.get(logicalNodeId) instanceof ObjectNode logicalNodeObj ? logicalNodeObj : null;
			if (logicalNode == null) {
				continue;
			}
			ObjectNode scopes = logicalNode.get("scopes") instanceof ObjectNode scopesNode
				? scopesNode
				: logicalNode.putObject("scopes");
			ArrayNode denyArray = scopes.get("deny_scopes") instanceof ArrayNode arr ? arr : scopes.putArray("deny_scopes");
			java.util.Set<String> existing = new java.util.LinkedHashSet<>();
			denyArray.forEach(entry -> {
				if (entry.isTextual()) {
					existing.add(entry.asText());
				}
			});
			for (String scope : denyScopes) {
				if (scope != null && !scope.isBlank()) {
					existing.add(scope);
				}
			}
			denyArray.removeAll();
			existing.forEach(denyArray::add);
		}
		return snapshot;
	}

	private List<String> resolveLogicalNodeIds(List<Map<String, Object>> pendingRequests, String fallback) {
		java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
		if (pendingRequests != null) {
			for (Map<String, Object> request : pendingRequests) {
				String logicalNodeId = readRequestText(request, "logical_node_id");
				if (logicalNodeId != null && !logicalNodeId.isBlank()) {
					ids.add(logicalNodeId);
				}
			}
		}
		if (ids.isEmpty() && fallback != null && !fallback.isBlank()) {
			ids.add(fallback);
		}
		return new ArrayList<>(ids);
	}

	private Map<String, Object> decisionResultEvent(JsonNode payload, String decision, String approvedByUserId) {
		ObjectNode result = objectMapper.createObjectNode();
		String requestId = payload == null ? null : payload.path("request_id").asText(null);
		if (requestId != null) {
			result.put("request_id", requestId);
		}
		if (decision != null) {
			result.put("decision", decision);
		}
		if (approvedByUserId != null && !approvedByUserId.isBlank()) {
			result.put("approved_by_user_id", approvedByUserId);
		}
		if (payload != null && payload.has("remember")) {
			result.put("remember", payload.path("remember").asBoolean(false));
		}
		if (payload != null) {
			JsonNode allowScopes = payload.get("allow_scopes");
			if (allowScopes != null && allowScopes.isArray()) {
				result.set("allow_scopes", allowScopes);
			}
			else if (payload.has("allow_scope")) {
				result.put("allow_scope", payload.path("allow_scope").asText());
			}
			JsonNode scopeDecisions = payload.get("scope_decisions");
			if (scopeDecisions != null && scopeDecisions.isObject()) {
				result.set("scope_decisions", scopeDecisions);
			}
			JsonNode scopeRemember = payload.get("scope_remember");
			if (scopeRemember != null && scopeRemember.isObject()) {
				result.set("scope_remember", scopeRemember);
			}
		}
		return pendingEvent(SessionEventType.DECISION_RESULT, result);
	}

	private List<String> scopeOptions(List<String> scopes) {
		if (scopes == null || scopes.isEmpty()) {
			return List.of();
		}
		java.util.LinkedHashSet<String> options = new java.util.LinkedHashSet<>();
		for (String scope : scopes) {
			if (scope == null || scope.isBlank()) {
				continue;
			}
			String[] parts = scope.split(":");
			StringBuilder builder = new StringBuilder();
			for (int i = 0; i < parts.length; i++) {
				if (i > 0) {
					builder.append(":");
				}
				builder.append(parts[i]);
				options.add(builder.toString());
			}
		}
		return new ArrayList<>(options);
	}

	private record ApprovalDecision(
			String decision,
			List<String> allowScopes,
			boolean remember,
			String requestId,
			Map<String, String> scopeDecisions,
			Map<String, Boolean> scopeRemember) {}

	private record ApprovalScopePolicy(
			List<String> allowScopes,
			List<String> denyScopes,
			List<String> rememberAllowScopes,
			List<String> rememberDenyScopes) {}

	private record ToolFailure(
			String code,
			String message,
			List<String> requestedScopes,
			boolean preview,
			String uiResourceUri,
			String diffUri,
			JsonNode structuredContent) {}

	private record ToolCall(
			String id,
			String fullName,
			UUID serverId,
			String toolName,
			String arguments,
			JsonNode argumentsNode,
			String errorCode,
			String errorMessage) {}

	private record LocationInfo(double lat, double lng) {}

	private enum ToolDecision {
		ALLOW,
		DENY,
		DYNAMIC
	}

	private record ToolScopeDecision(ToolDecision decision, List<String> dynamicScopes) {
		static ToolScopeDecision allow() {
			return new ToolScopeDecision(ToolDecision.ALLOW, List.of());
		}

		static ToolScopeDecision deny() {
			return new ToolScopeDecision(ToolDecision.DENY, List.of());
		}

		static ToolScopeDecision dynamic(List<String> scopes) {
			return new ToolScopeDecision(ToolDecision.DYNAMIC, scopes == null ? List.of() : scopes);
		}
	}

	private List<ToolSpecification> toolSpecificationsForNode(
			ConversationGraphState state,
			Session session,
			String nodeId) {
		if (session == null || nodeId == null) {
			return List.of();
		}
		ObjectNode snapshot = readSnapshot(state);
		ObjectNode node = snapshot == null ? null : readNodeSnapshot(snapshot, nodeId);
		List<UUID> serverIds = resolveServerIds(session.workspace.id, snapshot, node);
		if (serverIds.isEmpty()) {
			return List.of();
		}
		boolean isWorkerSession = isWorker(session, state);
		boolean limitWorkerTools = isWorkerSession && isWorkerToolListLeading();
		if (!hideDeniedTools(node)) {
			List<ToolSpecification> specs = toolSpecificationService.toolSpecifications(serverIds);
			if (limitWorkerTools) {
				return filterWorkerToolSpecifications(specs, readWorkerAllowedTools(state));
			}
			return specs;
		}
		List<McpServerTool> tools = entityManager.createQuery(
				"select t from McpServerTool t join fetch t.mcpServer "
					+ "where t.mcpServer.id in :serverIds and t.deleted = false and t.disabled = false",
				McpServerTool.class
			)
			.setParameter("serverIds", serverIds)
			.getResultList();
		if (tools.isEmpty()) {
			return List.of();
		}
		List<String> allowScopes = readScopeList(node, "allow_scopes");
		List<String> denyScopes = readScopeList(node, "deny_scopes");
		List<String> stickyAllowed = readMcpStringArray(node, "allowed_tools");
		List<McpServerTool> filtered = new ArrayList<>();
		for (McpServerTool tool : tools) {
			if (tool == null) {
				continue;
			}
			List<String> toolScopes = resolveToolScopes(tool);
			boolean denied = isDeniedByScope(toolScopes, denyScopes);
			String fullName = toolSpecificationService.toolName(tool);
			if (!denied || stickyAllowed.contains(fullName)) {
				filtered.add(tool);
			}
		}
		List<ToolSpecification> specs = toolSpecificationService.toolSpecificationsForTools(filtered);
		if (limitWorkerTools) {
			return filterWorkerToolSpecifications(specs, readWorkerAllowedTools(state));
		}
		return specs;
	}

	private McpActivationContext buildMcpActivationContext(
			Session session,
			ConversationGraphState state,
			String nodeId) {
		if (session == null || state == null || nodeId == null || nodeId.isBlank()) {
			return McpActivationContext.empty();
		}
		ObjectNode snapshot = readSnapshot(state);
		ObjectNode nodeSnapshot = snapshot == null ? null : readNodeSnapshot(snapshot, nodeId);
		List<UUID> serverIds = resolveServerIds(session.workspace.id, snapshot, nodeSnapshot);
		if (serverIds.isEmpty()) {
			return McpActivationContext.empty();
		}
		McpServer polymrServer = entityManager.createQuery(
				"select s from McpServer s where s.id in :serverIds and s.protocol = :protocol "
					+ "and lower(s.virtualType) = :virtualType",
				McpServer.class
			)
			.setParameter("serverIds", serverIds)
			.setParameter("protocol", be.celerex.polymr.model.McpProtocol.VIRTUAL)
			.setParameter("virtualType", "polymr")
			.getResultStream()
			.findFirst()
			.orElse(null);
		if (polymrServer == null) {
			return McpActivationContext.empty();
		}
		McpServerTool activationTool = entityManager.createQuery(
				"select t from McpServerTool t where t.mcpServer.id = :serverId "
					+ "and t.toolName = :toolName and t.deleted = false",
				McpServerTool.class
			)
			.setParameter("serverId", polymrServer.id)
			.setParameter("toolName", be.celerex.polymr.mcp.VirtualMcpService.TOOL_ACTIVATE_MCP_SERVER)
			.getResultStream()
			.findFirst()
			.orElse(null);
		if (activationTool == null || activationTool.disabled) {
			return McpActivationContext.empty();
		}
		String activationToolName = toolSpecificationService.toolName(activationTool);
		if (activationToolName == null || activationToolName.isBlank()) {
			return McpActivationContext.empty();
		}
		List<String> denyScopes = readScopeList(nodeSnapshot, "deny_scopes");
		if (hideDeniedTools(nodeSnapshot) && isDeniedByScope(resolveToolScopes(activationTool), denyScopes)) {
			return McpActivationContext.empty();
		}
		List<UUID> activeServerIds = new ArrayList<>(serverIds);
		List<McpServer> candidates;
		if (session.channel != null) {
			List<UUID> availableIds = entityManager.createQuery(
					"select c.mcpServer.id from ChannelMcpServer c where c.channel.id = "
						+ ":channelId and c.availableOnRequest = true",
					UUID.class
				)
				.setParameter("channelId", session.channel.id)
				.getResultList();
			if (availableIds.isEmpty()) {
				candidates = List.of();
			}
			else if (activeServerIds.isEmpty()) {
				candidates = entityManager.createQuery("select s from McpServer s where s.id in :serverIds order by lower(s.name)", McpServer.class)
					.setParameter("serverIds", availableIds)
					.getResultList();
			}
			else {
				candidates = entityManager.createQuery(
						"select s from McpServer s where s.id in :serverIds and "
							+ "s.id not in :activeIds order by lower(s.name)",
						McpServer.class
					)
					.setParameter("serverIds", availableIds)
					.setParameter("activeIds", activeServerIds)
					.getResultList();
			}
		}
		else {
			candidates = activeServerIds.isEmpty()
				? entityManager.createQuery(
						"select s from McpServer s where s.workspace.id = :workspaceId "
							+ "and s.visibility = :visibility order by lower(s.name)",
						McpServer.class
					)
					.setParameter("workspaceId", session.workspace.id)
					.setParameter("visibility", be.celerex.polymr.model.McpServerVisibility.AVAILABLE)
					.getResultList()
				: entityManager.createQuery(
						"select s from McpServer s where s.workspace.id = :workspaceId "
							+ "and s.visibility = :visibility and s.id not in :activeIds order by lower(s.name)",
						McpServer.class
					)
					.setParameter("workspaceId", session.workspace.id)
					.setParameter("visibility", be.celerex.polymr.model.McpServerVisibility.AVAILABLE)
					.setParameter("activeIds", activeServerIds)
					.getResultList();
		}
		if (candidates.isEmpty()) {
			return new McpActivationContext(activationToolName, List.of());
		}
		List<Map<String, Object>> entries = new ArrayList<>();
		for (McpServer server : candidates) {
			if (server == null || server.name == null || server.name.isBlank()) {
				continue;
			}
			Map<String, Object> entry = new HashMap<>();
			entry.put("name", server.name);
			entry.put("instructions", server.instructions == null ? "" : server.instructions);
			entries.add(entry);
		}
		return new McpActivationContext(activationToolName, entries);
	}

	private record McpActivationContext(String toolName, List<Map<String, Object>> servers) {
		static McpActivationContext empty() {
			return new McpActivationContext(null, List.of());
		}
	}

	private List<Map<String, Object>> buildActiveMcpPromptEntries(
			Session session,
			ConversationGraphState state,
			String promptNodeId) {
		if (session == null || session.workspace == null || state == null) {
			return List.of();
		}
		ObjectNode snapshot = readSnapshot(state);
		ObjectNode nodeSnapshot = resolveLogicalNodeSnapshot(snapshot, promptNodeId);
		if (nodeSnapshot == null) {
			nodeSnapshot = resolvePrimaryNodeSnapshot(snapshot);
		}
		List<UUID> activeServerIds = resolveServerIds(session.workspace.id, snapshot, nodeSnapshot);
		if (activeServerIds.isEmpty()) {
			return List.of();
		}
		List<McpServer> servers = entityManager.createQuery(
				"select s from McpServer s where s.workspace.id = :workspaceId and s.id in "
					+ ":serverIds order by lower(s.name), s.name, s.id",
				McpServer.class
			)
			.setParameter("workspaceId", session.workspace.id)
			.setParameter("serverIds", activeServerIds)
			.getResultList();
		List<Map<String, Object>> entries = new ArrayList<>();
		for (McpServer server : servers) {
			if (server == null || server.name == null || server.name.isBlank()) {
				continue;
			}
			String explicitPrompt = normalizePrompt(server.prompt);
			String virtualPrompt = normalizePrompt(virtualMcpService.resolvePrompt(server, session.id));
			String prompt = explicitPrompt;
			if (virtualPrompt != null) {
				prompt = prompt == null ? virtualPrompt : normalizeSpacing(prompt + "\n\n" + virtualPrompt);
			}
			if (prompt == null) {
				continue;
			}
			Map<String, Object> entry = new HashMap<>();
			entry.put("name", server.name);
			entry.put("prompt", prompt);
			entries.add(entry);
		}
		return entries;
	}

	private String loadDesignRules() {
		try (var stream = getClass().getResourceAsStream("/prompt-templates/design_rules.md")) {
			if (stream == null) {
				return "";
			}
			return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
		}
		catch (Exception ex) {
			LOGGER.debugf(ex, "Failed to load design rules");
			return "";
		}
	}

	private String loadScriptRules() {
		try (var stream = getClass().getResourceAsStream("/prompt-templates/script_rules.md")) {
			if (stream == null) {
				return "";
			}
			return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
		}
		catch (Exception ex) {
			LOGGER.debugf(ex, "Failed to load script rules");
			return "";
		}
	}

	private boolean isDesignSession(Session session) {
		if (session == null) {
			return false;
		}
		if (session.visibility == SessionVisibility.HIDDEN || session.visibility == SessionVisibility.FLEXIBLE) {
			return session.title != null && session.title.startsWith("Design:");
		}
		return false;
	}

	private boolean isScriptSession(Session session) {
		if (session == null) {
			return false;
		}
		if (session.visibility == SessionVisibility.HIDDEN || session.visibility == SessionVisibility.FLEXIBLE) {
			return session.title != null && session.title.startsWith("Script:");
		}
		return false;
	}

	private ObjectNode buildScriptSessionCatalog(Session session) {
		if (session == null) {
			return scriptCatalogService.buildCatalog(null);
		}
		Script script = entityManager.createQuery(
				"select v.script from ScriptVersion v where v.designSessionId = :sessionId and v.releasedAt is null",
				Script.class
			)
			.setParameter("sessionId", session.id)
			.setMaxResults(1)
			.getResultStream()
			.findFirst()
			.orElse(null);
		if (script == null) {
			return scriptCatalogService.buildCatalog(session.workspace == null ? null : session.workspace.id);
		}
		return scriptCatalogService.buildCatalogForScript(script);
	}

	private List<ToolSpecification> skillToolSpecifications(
			Session session,
			WorkflowDefinition definition,
			ObjectNode logicalNode) {
		if (session == null) {
			return List.of();
		}
		if (!shouldAllowSkillTools(definition, logicalNode)) {
			return List.of();
		}
		Assistant assistant = resolveAssistantForNode(session, logicalNode, false);
		List<Skill> skills = loadAssistantSkills(session, assistant).stream().filter(this::isSkillRenderable).toList();
		if (skills.isEmpty()) {
			return List.of();
		}
		return List.of(skillActivateSpecification(), skillDeactivateSpecification());
	}

	private boolean shouldAllowSkillTools(WorkflowDefinition definition, ObjectNode logicalNode) {
		if (logicalNode != null) {
			String type = logicalNode.path("type").asText(null);
			return type == null || !"ai".equalsIgnoreCase(type);
		}
		return definition != null && workflowDefinitionService.isConversationDefinition(definition);
	}

	private JsonNode resolveDefinitionJson(WorkflowRun run, WorkflowDefinition definition) {
		if (run != null && run.workflowDefinitionVersion != null) {
			UUID versionId = run.workflowDefinitionVersion.id;
			if (versionId != null) {
				JsonNode versionJson = entityManager.createQuery("select v.definitionJson from WorkflowDefinitionVersion v where v.id = :versionId", JsonNode.class)
					.setParameter("versionId", versionId)
					.getResultStream()
					.findFirst()
					.orElse(null);
				if (versionJson != null) {
					return versionJson;
				}
			}
		}
		return definition == null ? null : loadReleasedDefinition(definition.id);
	}

	private JsonNode loadReleasedDefinition(UUID workflowId) {
		if (workflowId == null) {
			return null;
		}
		return entityManager.createQuery(
				"select v.definitionJson from WorkflowDefinitionVersion v where v.workflowDefinition.id = "
					+ ":workflowId "
					+ "and v.releasedAt is not null and v.deprecatedAt is null",
				JsonNode.class
			)
			.setParameter("workflowId", workflowId)
			.setMaxResults(1)
			.getResultStream()
			.findFirst()
			.orElse(null);
	}

	private ToolSpecification skillActivateSpecification() {
		JsonObjectSchema schema = JsonObjectSchema.builder()
			.addProperty("skill_name", JsonStringSchema.builder().description("Snake case name of the skill.").build())
			.required(List.of("skill_name"))
			.build();
		return ToolSpecification.builder()
			.name(SKILL_ACTIVATE_TOOL)
			.description("Activate a skill for this session.")
			.parameters(schema)
			.build();
	}

	private ToolSpecification skillDeactivateSpecification() {
		JsonObjectSchema schema = JsonObjectSchema.builder().build();
		return ToolSpecification.builder()
			.name(SKILL_DEACTIVATE_TOOL)
			.description("Deactivate the currently active skill.")
			.parameters(schema)
			.build();
	}

	private boolean isDeniedByScope(List<String> toolScopes, List<String> denyScopes) {
		if (toolScopes == null || toolScopes.isEmpty()) {
			return false;
		}
		if (denyScopes == null || denyScopes.isEmpty()) {
			return false;
		}
		for (String toolScope : toolScopes) {
			if (toolScope == null || toolScope.isBlank()) {
				continue;
			}
			int denySpec = bestSpecificity(toolScope, denyScopes);
			if (denySpec > 0) {
				return true;
			}
		}
		return false;
	}

	private ObjectNode readSnapshot(ConversationGraphState state) {
		Object snapshot = state.value(ConversationGraphState.MCP_SNAPSHOT).orElse(null);
		if (snapshot instanceof ObjectNode node) {
			return node;
		}
		if (snapshot instanceof JsonNode json && json.isObject()) {
			return (ObjectNode) json;
		}
		if (snapshot == null) {
			return null;
		}
		return objectMapper.convertValue(snapshot, ObjectNode.class);
	}

	private ObjectNode resolveLogicalNodeSnapshot(ObjectNode snapshot, String logicalNodeId) {
		if (snapshot == null || logicalNodeId == null || logicalNodeId.isBlank()) {
			return null;
		}
		return readNodeSnapshot(snapshot, logicalNodeId);
	}

	private ObjectNode resolvePrimaryNodeSnapshot(ObjectNode snapshot) {
		if (snapshot == null) {
			return null;
		}
		JsonNode nodesNode = snapshot.get("nodes");
		if (nodesNode == null || !nodesNode.isObject()) {
			return null;
		}
		JsonNode llmNode = nodesNode.get("llm");
		if (llmNode instanceof ObjectNode objectNode) {
			return objectNode;
		}
		return nodesNode.fields().hasNext() ? (ObjectNode) nodesNode.fields().next().getValue() : null;
	}

	private String readRequestText(Map<String, Object> request, String key) {
		if (request == null || key == null || key.isBlank()) {
			return null;
		}
		Object value = request.get(key);
		if (value == null) {
			return null;
		}
		String text = value.toString();
		return text == null || text.isBlank() ? null : text;
	}

	private List<String> readRequestScopes(Map<String, Object> request, String key) {
		if (request == null || key == null || key.isBlank()) {
			return List.of();
		}
		Object value = request.get(key);
		if (value instanceof JsonNode node) {
			return readScopeList(node);
		}
		if (value instanceof List<?> list) {
			List<String> scopes = new ArrayList<>();
			for (Object entry : list) {
				if (entry == null) {
					continue;
				}
				String text = entry.toString();
				if (text != null && !text.isBlank()) {
					scopes.add(text);
				}
			}
			return scopes;
		}
		return List.of();
	}

	private JsonNode readRequestStructuredContent(Map<String, Object> request, String key) {
		if (request == null || key == null || key.isBlank()) {
			return null;
		}
		Object value = request.get(key);
		if (value == null) {
			return null;
		}
		if (value instanceof JsonNode node) {
			return node;
		}
		try {
			return objectMapper.valueToTree(value);
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private List<Content> readAttachmentContents(JsonNode payload, UUID workspaceId) {
		if (payload == null || payload.isNull()) {
			return List.of();
		}
		JsonNode attachments = payload.get("attachments");
		if (attachments == null || !attachments.isArray()) {
			return List.of();
		}
		List<Content> contents = new ArrayList<>();
		for (JsonNode entry : attachments) {
			if (entry == null || !entry.isObject()) {
				continue;
			}
			String type = entry.path("type").asText("");
			String name = entry.path("name").asText("attachment");
			String data = entry.path("data").asText(null);
			String hash = entry.path("blob_hash").asText(null);
			String blobUri = buildBlobUri(workspaceId, hash);
			if (blobUri != null) {
				contents.add(TextContent.from("Attachment reference: " + blobUri));
			}
			if (type.startsWith("image/")) {
				Optional<PublicBlobLink> link = resolvePublicLink(workspaceId, hash);
				if (link.isPresent()) {
					contents.add(ImageContent.from(link.get().uri()));
					continue;
				}
			}
			else if ("application/pdf".equals(type)) {
				Optional<PublicBlobLink> link = resolvePublicLink(workspaceId, hash);
				if (link.isPresent()) {
					contents.add(PdfFileContent.from(link.get().uri()));
					continue;
				}
			}
			else if (type.startsWith("audio/")) {
				Optional<PublicBlobLink> link = resolvePublicLink(workspaceId, hash);
				if (link.isPresent()) {
					contents.add(AudioContent.from(link.get().uri()));
					continue;
				}
			}
			else if (type.startsWith("video/")) {
				Optional<PublicBlobLink> link = resolvePublicLink(workspaceId, hash);
				if (link.isPresent()) {
					contents.add(VideoContent.from(link.get().uri()));
					continue;
				}
			}
			AttachmentBytes bytes = resolveAttachmentBytes(data, hash, workspaceId);
			if (bytes == null || bytes.base64 == null || bytes.base64.isBlank()) {
				continue;
			}
			String base64 = bytes.base64;
			if (base64 == null || base64.isBlank()) {
				continue;
			}
			if (type.startsWith("image/")) {
				Image image = Image.builder().base64Data(base64).mimeType(type).build();
				contents.add(ImageContent.from(image));
			}
			else if ("application/pdf".equals(type)) {
				PdfFile pdf = PdfFile.builder().base64Data(base64).mimeType(type).build();
				contents.add(PdfFileContent.from(pdf));
			}
			else if (type.startsWith("audio/")) {
				Audio audio = Audio.builder().base64Data(base64).mimeType(type).build();
				contents.add(AudioContent.from(audio));
			}
			else if (type.startsWith("video/")) {
				Video video = Video.builder().base64Data(base64).mimeType(type).build();
				contents.add(VideoContent.from(video));
			}
		}
		return contents;
	}

	public static String buildBlobUri(UUID workspaceId, String hash) {
		if (workspaceId == null || hash == null || hash.isBlank()) {
			return null;
		}
		return "blob:/" + workspaceId + "/" + hash;
	}

	private List<ChatMessage> readAttachmentMessages(JsonNode payload, UUID workspaceId) {
		if (payload == null || payload.isNull()) {
			return List.of();
		}
		JsonNode attachments = payload.get("attachments");
		if (attachments == null || !attachments.isArray()) {
			return List.of();
		}
		List<ChatMessage> messages = new ArrayList<>();
		for (JsonNode entry : attachments) {
			if (entry == null || !entry.isObject()) {
				continue;
			}
			String type = entry.path("type").asText("");
			if (isSupportedAttachmentType(type)) {
				continue;
			}
			String name = entry.path("name").asText("attachment");
			String data = entry.path("data").asText(null);
			String hash = entry.path("blob_hash").asText(null);
			AttachmentBytes bytes = resolveAttachmentBytes(data, hash, workspaceId);
			String base64 = bytes == null ? null : bytes.base64;
			if (base64 == null || base64.isBlank()) {
				continue;
			}
			AttachmentPayload payloadData = new AttachmentPayload(name, type, base64);
			AttachmentHandlerResult result = attachmentHandlerRegistry.handle(payloadData).orElse(null);
			if (result != null && result.messages() != null && !result.messages().isEmpty()) {
				messages.addAll(result.messages());
			}
		}
		return messages;
	}

	private boolean isSupportedAttachmentType(String type) {
		if (type == null || type.isBlank()) {
			return false;
		}
		return type.startsWith("image/")
			|| type.startsWith("audio/")
			|| type.startsWith("video/")
			|| "application/pdf".equals(type);
	}

	private String extractBase64(String data) {
		if (data == null || data.isBlank()) {
			return null;
		}
		int commaIndex = data.indexOf(',');
		if (data.startsWith("data:") && commaIndex > 0) {
			return data.substring(commaIndex + 1);
		}
		return data;
	}

	private AttachmentBytes resolveAttachmentBytes(String data, String hash, UUID workspaceId) {
		String base64 = extractBase64(data);
		if (base64 != null && !base64.isBlank()) {
			return new AttachmentBytes(base64, null);
		}
		if (workspaceId == null || hash == null || hash.isBlank()) {
			return null;
		}
		Optional<StoredBlob> blob = blobStore.load(workspaceId, hash);
		if (blob.isEmpty()) {
			return null;
		}
		String encoded = Base64.getEncoder().encodeToString(blob.get().bytes());
		return new AttachmentBytes(encoded, blob.get().mimeType());
	}

	public PublicWorkspaceBlobStore publicBlobStore() {
		if (blobStore instanceof PublicWorkspaceBlobStore publicStore) {
			return publicStore;
		}
		return null;
	}

	private Optional<PublicBlobLink> resolvePublicLink(UUID workspaceId, String hash) {
		if (workspaceId == null || hash == null || hash.isBlank()) {
			return Optional.empty();
		}
		PublicWorkspaceBlobStore publicStore = publicBlobStore();
		if (publicStore == null) {
			return Optional.empty();
		}
		return attachmentLinkService.resolvePublicLink(workspaceId, hash, publicStore);
	}

	private record AttachmentBytes(String base64, String mimeType) {}

	private ToolScopeDecision evaluateToolDecision(
			List<String> toolScopes,
			List<String> allowScopes,
			List<String> denyScopes) {
		if (toolScopes == null || toolScopes.isEmpty()) {
			return ToolScopeDecision.allow();
		}
		List<String> dynamicScopes = new ArrayList<>();
		for (String scope : toolScopes) {
			if (scope == null || scope.isBlank()) {
				continue;
			}
			int allowSpec = bestSpecificity(scope, allowScopes);
			int denySpec = bestSpecificity(scope, denyScopes);
			if (denySpec > 0 && denySpec >= allowSpec) {
				return ToolScopeDecision.deny();
			}
			if (allowSpec == 0) {
				dynamicScopes.add(scope);
			}
		}
		if (!dynamicScopes.isEmpty()) {
			return ToolScopeDecision.dynamic(dynamicScopes);
		}
		return ToolScopeDecision.allow();
	}

	private List<String> relevantScopes(List<String> toolScopes, List<String> candidates) {
		if (toolScopes == null || toolScopes.isEmpty() || candidates == null || candidates.isEmpty()) {
			return List.of();
		}
		java.util.LinkedHashSet<String> relevant = new java.util.LinkedHashSet<>();
		for (String candidate : candidates) {
			if (candidate == null || candidate.isBlank()) {
				continue;
			}
			for (String scope : toolScopes) {
				if (scope == null || scope.isBlank()) {
					continue;
				}
				if (scope.equals(candidate) || scope.startsWith(candidate + ":")) {
					relevant.add(candidate);
					break;
				}
			}
		}
		return new ArrayList<>(relevant);
	}

	private java.util.Set<String> declaredScopesForServers(List<UUID> serverIds) {
		if (serverIds == null || serverIds.isEmpty()) {
			return java.util.Set.of();
		}
		List<McpServerTool> tools = entityManager.createQuery(
				"select t from McpServerTool t "
					+ "where t.mcpServer.id in :serverIds and t.deleted = false and t.disabled = false",
				McpServerTool.class
			)
			.setParameter("serverIds", serverIds)
			.getResultList();
		java.util.LinkedHashSet<String> declared = new java.util.LinkedHashSet<>();
		for (McpServerTool tool : tools) {
			if (tool == null) {
				continue;
			}
			List<String> scopes = resolveToolScopes(tool);
			for (String scope : scopes) {
				if (scope != null && !scope.isBlank()) {
					declared.add(scope);
				}
			}
		}
		return declared;
	}

	private List<String> dynamicChildScopes(
			List<String> toolScopes,
			List<String> candidates,
			java.util.Set<String> declaredScopes) {
		if (toolScopes == null || toolScopes.isEmpty() || candidates == null || candidates.isEmpty()) {
			return List.of();
		}
		java.util.LinkedHashSet<String> dynamic = new java.util.LinkedHashSet<>();
		for (String candidate : candidates) {
			if (candidate == null || candidate.isBlank()) {
				continue;
			}
			if (declaredScopes != null && declaredScopes.contains(candidate)) {
				continue;
			}
			for (String scope : toolScopes) {
				if (scope == null || scope.isBlank()) {
					continue;
				}
				if (candidate.startsWith(scope + ":")) {
					dynamic.add(candidate);
					break;
				}
			}
		}
		return new ArrayList<>(dynamic);
	}

	private List<String> mergeScopes(List<String> first, List<String> second) {
		if ((first == null || first.isEmpty()) && (second == null || second.isEmpty())) {
			return List.of();
		}
		java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>();
		if (first != null) {
			for (String scope : first) {
				if (scope != null && !scope.isBlank()) {
					merged.add(scope);
				}
			}
		}
		if (second != null) {
			for (String scope : second) {
				if (scope != null && !scope.isBlank()) {
					merged.add(scope);
				}
			}
		}
		return new ArrayList<>(merged);
	}

	private boolean isPreviewUnsupported(JsonRpcException ex) {
		if (ex == null) {
			return false;
		}
		int code = ex.code();
		if (code == -32050) {
			return true;
		}
		String message = ex.getMessage();
		if (message == null) {
			return false;
		}
		String normalized = message.toLowerCase();
		return normalized.contains("preview") && normalized.contains("unsupported");
	}

	private int bestSpecificity(String scope, List<String> candidates) {
		if (scope == null || scope.isBlank() || candidates == null || candidates.isEmpty()) {
			return 0;
		}
		int best = 0;
		for (String candidate : candidates) {
			if (candidate == null || candidate.isBlank()) {
				continue;
			}
			if (scope.equals(candidate) || scope.startsWith(candidate + ":")) {
				int specificity = candidate.split(":").length;
				if (specificity > best) {
					best = specificity;
				}
			}
		}
		return best;
	}

	private List<String> validateAttachments(JsonNode payload) {
		if (payload == null || payload.isNull()) {
			return List.of();
		}
		JsonNode attachments = payload.get("attachments");
		if (attachments == null || !attachments.isArray()) {
			return List.of();
		}
		List<String> errors = new ArrayList<>();
		for (JsonNode entry : attachments) {
			if (entry == null || !entry.isObject()) {
				continue;
			}
			String type = entry.path("type").asText("");
			String name = entry.path("name").asText("attachment");
			if (type.isBlank()) {
				errors.add("Attachment type missing for " + name + ".");
				continue;
			}
			if (isSupportedAttachmentType(type)) {
				continue;
			}
			String data = entry.path("data").asText(null);
			String base64 = extractBase64(data);
			if (base64 == null || base64.isBlank()) {
				errors.add("Attachment data missing for " + name + ".");
				continue;
			}
			AttachmentPayload payloadData = new AttachmentPayload(name, type, base64);
			AttachmentHandlerResult result = attachmentHandlerRegistry.handle(payloadData).orElse(null);
			if (result == null || result.messages() == null || result.messages().isEmpty()) {
				errors.add("No handler available for attachment type " + type + " (" + name + ").");
			}
		}
		return errors;
	}

	private void normalizeAttachments(ObjectNode payload, UUID workspaceId) {
		if (payload == null || workspaceId == null) {
			return;
		}
		JsonNode attachments = payload.get("attachments");
		if (!(attachments instanceof ArrayNode arrayNode)) {
			return;
		}
		for (JsonNode entry : arrayNode) {
			if (!(entry instanceof ObjectNode entryNode)) {
				continue;
			}
			String data = entryNode.path("data").asText(null);
			if (data == null || data.isBlank()) {
				continue;
			}
			String base64 = extractBase64(data);
			if (base64 == null || base64.isBlank()) {
				continue;
			}
			byte[] bytes = decodeBase64(base64);
			if (bytes == null || bytes.length == 0) {
				continue;
			}
			String mimeType = entryNode.path("type").asText(null);
			StoredBlob stored = blobStore.store(workspaceId, bytes, mimeType);
			entryNode.remove("data");
			entryNode.put("blob_hash", stored.hash());
		}
	}

	private byte[] decodeBase64(String base64) {
		try {
			return java.util.Base64.getDecoder().decode(base64);
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private ObjectNode readNodeSnapshot(ObjectNode snapshot, String nodeId) {
		if (snapshot == null || nodeId == null) {
			return null;
		}
		JsonNode nodes = snapshot.get("nodes");
		if (nodes != null && nodes.isObject()) {
			JsonNode node = nodes.get(nodeId);
			if (node != null && node.isObject()) {
				return (ObjectNode) node;
			}
		}
		return null;
	}

	private boolean hideDeniedTools(ObjectNode nodeSnapshot) {
		if (nodeSnapshot == null) {
			return false;
		}
		ObjectNode mcp = nodeSnapshot.get("mcp") instanceof ObjectNode mcpNode ? mcpNode : null;
		if (mcp == null) {
			return false;
		}
		return mcp.path("hide_denied_tools").asBoolean(false);
	}

	private List<UUID> resolveServerIds(UUID workspaceId, ObjectNode snapshot, ObjectNode nodeSnapshot) {
		if (workspaceId == null) {
			return List.of();
		}
		List<UUID> allServers = readSnapshotServers(snapshot);
		if (allServers.isEmpty()) {
			if (workspaceId == null) {
				return List.of();
			}
			allServers = entityManager.createQuery(
					"select s.id from McpServer s where s.workspace.id = :workspaceId and s.visibility = :visibility",
					UUID.class
				)
				.setParameter("workspaceId", workspaceId)
				.setParameter("visibility", be.celerex.polymr.model.McpServerVisibility.VISIBLE)
				.getResultList();
		}
		if (nodeSnapshot == null) {
			return allServers;
		}
		ObjectNode mcp = nodeSnapshot.get("mcp") instanceof ObjectNode mcpNode ? mcpNode : null;
		if (mcp == null) {
			return allServers;
		}
		List<UUID> allow = readUuidArray(mcp.get("allow"));
		List<UUID> deny = readUuidArray(mcp.get("deny"));
		List<UUID> filtered = new ArrayList<>();
		if (allow.isEmpty()) {
			filtered.addAll(allServers);
		}
		else {
			for (UUID serverId : allServers) {
				if (allow.contains(serverId)) {
					filtered.add(serverId);
				}
			}
		}
		if (!deny.isEmpty()) {
			filtered.removeIf(deny::contains);
		}
		return filtered;
	}

	private List<UUID> readSnapshotServers(ObjectNode snapshot) {
		if (snapshot == null) {
			return List.of();
		}
		ObjectNode mcp = snapshot.get("mcp") instanceof ObjectNode mcpNode ? mcpNode : null;
		if (mcp == null) {
			return List.of();
		}
		return readUuidArray(mcp.get("servers"));
	}

	private List<UUID> readUuidArray(JsonNode node) {
		List<UUID> list = new ArrayList<>();
		if (node == null || !node.isArray()) {
			return list;
		}
		node.forEach(
			entry -> {
				if (entry.isTextual()) {
					try {
						list.add(UUID.fromString(entry.asText()));
					}
					catch (IllegalArgumentException ignored) {}
				}
			}
		);
		return list;
	}

	private List<String> readMcpStringArray(ObjectNode nodeSnapshot, String field) {
		if (nodeSnapshot == null) {
			return List.of();
		}
		ObjectNode mcp = nodeSnapshot.get("mcp") instanceof ObjectNode mcpNode ? mcpNode : null;
		if (mcp == null) {
			return List.of();
		}
		return readStringArray(mcp.get(field));
	}

	private List<String> readStringArray(JsonNode node) {
		if (node == null || node.isNull() || !node.isArray()) {
			return List.of();
		}
		List<String> list = new ArrayList<>();
		node.forEach(entry -> {
			if (entry.isTextual()) {
				list.add(entry.asText());
			}
		});
		return list;
	}

	private Map<String, String> readDecisionMap(JsonNode node) {
		if (node == null || node.isNull() || !node.isObject()) {
			return Map.of();
		}
		Map<String, String> map = new HashMap<>();
		node.fields()
			.forEachRemaining(
				entry -> {
					String key = entry.getKey();
					JsonNode value = entry.getValue();
					if (key == null || key.isBlank() || value == null || !value.isTextual()) {
						return;
					}
					String decision = value.asText(null);
					if (decision != null && !decision.isBlank()) {
						map.put(key, decision.trim().toLowerCase());
					}
				}
			);
		return map;
	}

	private Map<String, Boolean> readBooleanMap(JsonNode node) {
		if (node == null || node.isNull() || !node.isObject()) {
			return Map.of();
		}
		Map<String, Boolean> map = new HashMap<>();
		node.fields()
			.forEachRemaining(
				entry -> {
					String key = entry.getKey();
					JsonNode value = entry.getValue();
					if (key == null || key.isBlank() || value == null || value.isNull()) {
						return;
					}
					map.put(key, value.asBoolean(false));
				}
			);
		return map;
	}

	private List<Map<String, Object>> toPendingToolRequests(
			List<ToolExecutionRequest> requests,
			String logicalNodeId,
			String logicalNodeInstanceId) {
		if (requests == null || requests.isEmpty()) {
			return List.of();
		}
		List<Map<String, Object>> list = new ArrayList<>();
		for (ToolExecutionRequest request : requests) {
			if (request == null) {
				continue;
			}
			Map<String, Object> entry = new HashMap<>();
			String requestId = request.id();
			if (requestId == null || requestId.isBlank()) {
				requestId = UUID.randomUUID().toString();
			}
			entry.put("id", requestId);
			entry.put("name", request.name());
			entry.put("arguments", request.arguments());
			if (logicalNodeId != null && !logicalNodeId.isBlank()) {
				entry.put("logical_node_id", logicalNodeId);
			}
			if (logicalNodeInstanceId != null && !logicalNodeInstanceId.isBlank()) {
				entry.put("logical_node_instance_id", logicalNodeInstanceId);
			}
			list.add(entry);
		}
		return list;
	}

	private String safeText(AiMessage message) {
		if (message == null) {
			return "";
		}
		String text = message.text();
		return text == null ? "" : text;
	}

	private UsageAccounting applySessionPricing(Session session, ChatResponse response) {
		UsageAccounting accounting = resolveUsageAccounting(session, response);
		if (session == null || accounting.priceResult() == null) {
			return accounting;
		}
		sessionCostService.applyPriceResult(session.id, accounting.modelId(), accounting.priceResult());
		return accounting;
	}

	private UsageAccounting resolveUsageAccounting(Session session, ChatResponse response) {
		if (session == null
				|| response == null
				|| session.defaultAssistant == null
				|| session.defaultAssistant.model == null) {
			return new UsageAccounting(null, null, null);
		}
		AiModel model = session.defaultAssistant.model;
		if (!model.enabled) {
			return new UsageAccounting(null, null, null);
		}
		AiChatModelProvider provider = providerRegistry.find(model.provider).orElse(null);
		if (provider == null) {
			return new UsageAccounting(null, null, null);
		}
		Map<String, Object> config = modelConfigService.resolveConfig(model);
		String modelId = resolveModelId(config);
		AiChatModelDefinition definition = provider.resolveChatModel(modelId).orElse(null);
		if (definition == null) {
			return new UsageAccounting(modelId, null, null);
		}
		ResponseUsage usage = null;
		AiModelResponseUsageExtractor usageExtractor = definition.responseUsageExtractor(config).orElse(null);
		if (usageExtractor != null) {
			usage = usageExtractor.extract(response, modelId);
		}
		PriceResult result = null;
		AiModelResponseCostEstimator estimator = definition.responseCostEstimator(config).orElse(null);
		if (estimator != null) {
			result = estimator.estimate(response, modelId);
		}
		return new UsageAccounting(modelId, usage, result);
	}

	private Map<String, Object> pendingAssistantEvent(ObjectNode payload, UsageAccounting accounting) {
		Map<String, Object> event = pendingEvent(SessionEventType.ASSISTANT_MESSAGE, payload);
		applyUsageToEvent(event, accounting);
		return event;
	}

	private boolean hasUsageAccounting(UsageAccounting accounting) {
		if (accounting == null) {
			return false;
		}
		ResponseUsage usage = accounting.usage();
		if (usage != null) {
			if (usage.input_tokens() != null) {
				return true;
			}
			if (usage.output_tokens() != null) {
				return true;
			}
			if (usage.reasoning_tokens() != null) {
				return true;
			}
			if (usage.cached_input_tokens() != null) {
				return true;
			}
		}
		PriceResult result = accounting.priceResult();
		if (result != null) {
			if (result.total_cost() != null) {
				return true;
			}
			if (result.currency() != null && !result.currency().isBlank()) {
				return true;
			}
		}
		return accounting.modelId() != null && !accounting.modelId().isBlank();
	}

	private void applyUsageToEvent(Map<String, Object> event, UsageAccounting accounting) {
		if (event == null || accounting == null) {
			return;
		}
		ResponseUsage usage = accounting.usage();
		if (usage != null) {
			if (usage.input_tokens() != null) {
				event.put("input_tokens", usage.input_tokens());
			}
			if (usage.output_tokens() != null) {
				event.put("output_tokens", usage.output_tokens());
			}
			if (usage.reasoning_tokens() != null) {
				event.put("reasoning_tokens", usage.reasoning_tokens());
			}
			if (usage.cached_input_tokens() != null) {
				event.put("cached_input_tokens", usage.cached_input_tokens());
			}
		}
		PriceResult result = accounting.priceResult();
		if (result != null) {
			if (result.total_cost() != null) {
				event.put("price_snapshot", result.total_cost());
			}
			if (result.currency() != null && !result.currency().isBlank()) {
				event.put("price_currency", result.currency());
			}
		}
		if (accounting.modelId() != null && !accounting.modelId().isBlank()) {
			event.put("tokenizer_model_id", accounting.modelId());
		}
	}

	private ObjectNode pendingToolUsagePayload(UsageAccounting accounting) {
		if (!hasUsageAccounting(accounting)) {
			return null;
		}
		ObjectNode payload = objectMapper.createObjectNode();
		applyUsageToPayload(payload, accounting);
		return payload;
	}

	private UsageAccounting readPendingToolUsage(ConversationGraphState state) {
		if (state == null) {
			return null;
		}
		ObjectNode pendingState = PendingState.pendingFromState(state, objectMapper);
		JsonNode payload = pendingState.get(PendingState.TOOL_USAGE);
		if (!(payload instanceof ObjectNode objectNode)) {
			return null;
		}
		String modelId = objectNode.path("tokenizer_model_id").asText(null);
		Long inputTokens = objectNode.hasNonNull("input_tokens") ? objectNode.path("input_tokens").asLong() : null;
		Long outputTokens = objectNode.hasNonNull("output_tokens") ? objectNode.path("output_tokens").asLong() : null;
		Long reasoningTokens = objectNode.hasNonNull("reasoning_tokens") ? objectNode.path("reasoning_tokens").asLong() : null;
		Long cachedInputTokens = objectNode.hasNonNull("cached_input_tokens")
			? objectNode.path("cached_input_tokens").asLong()
			: null;
		ResponseUsage usage = null;
		if (inputTokens != null
				|| outputTokens != null
				|| reasoningTokens != null
				|| cachedInputTokens != null) {
			usage = new ResponseUsage(inputTokens, outputTokens, reasoningTokens, cachedInputTokens, null, false);
		}
		java.math.BigDecimal totalCost = objectNode.hasNonNull("price_snapshot") ? objectNode.path("price_snapshot").decimalValue() : null;
		String currency = objectNode.path("price_currency").asText(null);
		PriceResult result = null;
		if (totalCost != null || (currency != null && !currency.isBlank())) {
			result = new PriceResult(
				totalCost,
				currency,
				inputTokens,
				outputTokens,
				reasoningTokens,
				cachedInputTokens,
				null,
				List.of()
			);
		}
		UsageAccounting accounting = new UsageAccounting(modelId, usage, result);
		return hasUsageAccounting(accounting) ? accounting : null;
	}

	private void applyUsageToPayload(ObjectNode payload, UsageAccounting accounting) {
		if (payload == null || accounting == null) {
			return;
		}
		ResponseUsage usage = accounting.usage();
		if (usage != null) {
			if (usage.input_tokens() != null) {
				payload.put("input_tokens", usage.input_tokens());
			}
			if (usage.output_tokens() != null) {
				payload.put("output_tokens", usage.output_tokens());
			}
			if (usage.reasoning_tokens() != null) {
				payload.put("reasoning_tokens", usage.reasoning_tokens());
			}
			if (usage.cached_input_tokens() != null) {
				payload.put("cached_input_tokens", usage.cached_input_tokens());
			}
		}
		PriceResult result = accounting.priceResult();
		if (result == null) {
			return;
		}
		if (result.total_cost() != null) {
			payload.put("price_snapshot", result.total_cost());
		}
		if (result.currency() != null && !result.currency().isBlank()) {
			payload.put("price_currency", result.currency());
		}
		if (accounting.modelId() != null && !accounting.modelId().isBlank()) {
			payload.put("tokenizer_model_id", accounting.modelId());
		}
	}

	private Tracer resolveTracer() {
		if (tracer == null || tracer.isUnsatisfied()) {
			return GlobalOpenTelemetry.getTracer("polymr");
		}
		return tracer.get();
	}

	private record UsageAccounting(String modelId, ResponseUsage usage, PriceResult priceResult) {}
}
