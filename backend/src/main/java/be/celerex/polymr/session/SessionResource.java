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

import be.celerex.polymr.model.Assistant;
import be.celerex.polymr.model.Channel;
import be.celerex.polymr.model.ChannelTagSelection;
import be.celerex.polymr.model.Session;
import be.celerex.polymr.model.SessionCanvas;
import be.celerex.polymr.model.SessionEvent;
import be.celerex.polymr.model.SessionParticipant;
import be.celerex.polymr.model.SessionParticipantRole;
import be.celerex.polymr.model.SessionStatus;
import be.celerex.polymr.model.SessionVisibility;
import be.celerex.polymr.model.SessionTagSelection;
import be.celerex.polymr.model.TenantMembership;
import be.celerex.polymr.model.TenantRole;
import be.celerex.polymr.model.User;
import be.celerex.polymr.model.Workspace;
import be.celerex.polymr.model.WorkspaceTagSelection;
import be.celerex.polymr.model.WorkflowDefinition;
import be.celerex.polymr.model.WorkflowDefinitionVersion;
import be.celerex.polymr.model.WorkflowRun;
import be.celerex.polymr.model.WorkflowRunStatus;
import be.celerex.polymr.session.dto.SessionCanvasResponse;
import be.celerex.polymr.session.dto.SessionEventResponse;
import be.celerex.polymr.session.dto.SessionParticipantInviteRequest;
import be.celerex.polymr.session.dto.SessionParticipantResponse;
import be.celerex.polymr.session.dto.SessionRequest;
import be.celerex.polymr.session.dto.SessionResponse;
import be.celerex.polymr.session.dto.SessionSummary;
import be.celerex.polymr.session.dto.SessionPromptResponse;
import be.celerex.polymr.session.dto.SessionUpdateRequest;
import be.celerex.polymr.session.dto.SessionTechnicalDetailsResponse;
import be.celerex.polymr.session.dto.SessionHistoryPageResponse;
import be.celerex.polymr.session.dto.TagSummary;
import be.celerex.polymr.session.dto.McpServerSummary;
import be.celerex.polymr.session.dto.SessionToolServerSummary;
import be.celerex.polymr.session.dto.SessionToolSummary;
import be.celerex.polymr.session.SessionEventService;
import be.celerex.polymr.tenant.TenantAccessService;
import be.celerex.polymr.workflow.WorkflowDefinitionService;
import be.celerex.polymr.workflow.WorkflowCheckpointService;
import be.celerex.polymr.workflow.WorkflowRunDispatcher;
import be.celerex.polymr.mcp.WorkflowMcpSnapshotService;
import be.celerex.polymr.model.TagCategory;
import be.celerex.polymr.model.TagValue;
import be.celerex.polymr.model.McpServer;
import be.celerex.polymr.model.McpServerPolicy;
import be.celerex.polymr.model.McpServerTool;
import be.celerex.polymr.model.SessionEventType;
import be.celerex.polymr.workflow.runtime.ConversationGraphState;
import be.celerex.polymr.workflow.runtime.PendingState;
import be.celerex.polymr.ws.WorkspaceSocketEvent;
import be.celerex.polymr.ws.WorkspaceSocketManager;
import be.celerex.polymr.mcp.McpToolSpecificationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.transaction.TransactionSynchronizationRegistry;
import jakarta.transaction.Synchronization;
import jakarta.transaction.Status;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.jboss.logging.Logger;

@Path("/api/tenants/{tenantId}/workspaces/{workspaceId}/sessions")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class SessionResource {
	private static final Logger LOGGER = Logger.getLogger(SessionResource.class);
	@Inject
	EntityManager entityManager;

	@Inject
	TenantAccessService tenantAccessService;

	@Inject
	WorkspaceSocketManager socketManager;

	@Inject
	SessionEventService eventService;

	@Inject
	TransactionSynchronizationRegistry txRegistry;

	@Inject
	SessionParticipantService participantService;

	@Inject
	SessionSummaryService summaryService;

	@Inject
	SessionTelemetryService telemetryService;

	@Inject
	SessionChatService sessionChatService;

	@Inject
	SessionPruningService pruningService;

	@Inject
	ObjectMapper objectMapper;

	@Inject
	WorkflowDefinitionService workflowDefinitionService;

	@Inject
	WorkflowRunDispatcher runDispatcher;

	@Inject
	WorkflowCheckpointService checkpointService;

	@Inject
	WorkflowMcpSnapshotService snapshotService;

	@Inject
	McpToolSpecificationService toolSpecificationService;

	@Inject
	be.celerex.polymr.automation.VoiceTranscriptionService voiceTranscriptionService;

	@Context
	SecurityContext securityContext;

	public record VoiceTranscriptionResponse(String text) {}

	@POST
	@Path("/{sessionId}/voice-transcription")
	@Consumes("audio/*")
	public VoiceTranscriptionResponse uploadVoiceTranscription(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("sessionId") UUID sessionId,
			InputStream body,
			@Context HttpHeaders headers) {
		requireMembership(tenantId);
		Session session = requireSession(workspaceId, sessionId);
		if (body == null) {
			throw new WebApplicationException("Voice payload is required", Response.Status.BAD_REQUEST);
		}
		byte[] audioBytes = readBytes(body);
		if (audioBytes == null || audioBytes.length == 0) {
			throw new WebApplicationException("Voice payload is empty", Response.Status.BAD_REQUEST);
		}
		String mimeType = headers == null ? null : headers.getHeaderString("Content-Type");
		String text = voiceTranscriptionService.transcribeAudio(session.id, requireUserId(), audioBytes, mimeType);
		if (text == null || text.isBlank()) {
			throw new WebApplicationException("Transcription failed", Response.Status.BAD_GATEWAY);
		}
		return new VoiceTranscriptionResponse(text);
	}

	@GET
	@Path("/{sessionId}/technical")
	public SessionTechnicalDetailsResponse technicalDetails(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("sessionId") UUID sessionId) {
		requireMembership(tenantId);
		Session session = requireSession(workspaceId, sessionId);
		WorkflowRun run = findRun(session);
		WorkflowDefinition definition = run == null ? null : run.workflowDefinition;
		ObjectNode snapshot = resolveSnapshot(run, definition, session);
		String logicalNodeId = resolveLogicalNodeId(run == null ? null : run.checkpointJson, snapshot, run == null ? null : run.workflowDefinition);
		ObjectNode nodeSnapshot = resolveNodeSnapshot(snapshot, logicalNodeId);
		if (nodeSnapshot == null) {
			nodeSnapshot = resolveScopeNodeSnapshot(snapshot);
		}
		ObjectNode mcp = nodeSnapshot == null ? null : readObject(nodeSnapshot.get("mcp"));
		ObjectNode scopes = nodeSnapshot == null ? null : readObject(nodeSnapshot.get("scopes"));

		List<UUID> tagIds = readUuidArray(mcp == null ? null : mcp.get("tags"));
		List<TagSummary> tags = loadTags(tagIds);
		List<String> allowScopes = readStringArray(scopes == null ? null : scopes.get("allow_scopes"));
		List<String> denyScopes = readStringArray(scopes == null ? null : scopes.get("deny_scopes"));
		boolean hideDeniedTools = mcp != null && mcp.path("hide_denied_tools").asBoolean(false);

		ObjectNode rootMcp = snapshot == null ? null : readObject(snapshot.get("mcp"));
		List<UUID> serverIds = readUuidArray(rootMcp == null ? null : rootMcp.get("servers"));
		List<McpServerSummary> servers = loadServers(serverIds);
		List<String> availableScopes = resolveAvailableScopes(serverIds);
		List<String> stickyAllowed = readStringArray(mcp == null ? null : mcp.get("allowed_tools"));
		List<SessionToolServerSummary> serverTools = buildServerTools(serverIds, allowScopes, denyScopes, stickyAllowed);

		return new SessionTechnicalDetailsResponse(
			session.id,
			run == null ? null : run.id,
			definition == null ? null : definition.name,
			hideDeniedTools,
			tags,
			allowScopes,
			denyScopes,
			availableScopes,
			servers,
			serverTools
		);
	}

	@POST
	@Path("/{sessionId}/prune")
	@Transactional
	public Response pruneSession(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("sessionId") UUID sessionId) {
		requireMembership(tenantId);
		Session session = requireSession(workspaceId, sessionId);
		SessionPruningService.PruneResult result = pruningService.pruneSession(sessionId);
		if (result.prunedEvents() > 0) {
			ObjectNode payload = objectMapper.createObjectNode();
			payload.put("pruned_events", result.prunedEvents());
			payload.put("pruned_tokens", result.prunedTokens());
			payload.put("estimated_tokens", result.estimatedTokens());
			if (result.pruningLimit() != null) {
				payload.put("pruning_limit", result.pruningLimit());
			}
			socketManager.broadcastToSession(session.id, new WorkspaceSocketEvent("session.pruned", session.workspace.id, session.id, payload));
		}
		return Response.ok(result).build();
	}

	@GET
	@Path("/{sessionId}/prompt")
	public SessionPromptResponse promptPreview(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("sessionId") UUID sessionId) {
		requireMembership(tenantId);
		Session session = requireSession(workspaceId, sessionId);
		WorkflowRun run = findRun(session);
		if (run == null) {
			throw new WebApplicationException("Session run not found", Response.Status.CONFLICT);
		}
		return sessionChatService.buildCompiledPrompt(session, run);
	}

	@PUT
	@Path("/{sessionId}/scopes")
	@Transactional
	public SessionTechnicalDetailsResponse updateSessionScopes(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("sessionId") UUID sessionId,
			be.celerex.polymr.session.dto.SessionScopeRequest request) {
		requireMembership(tenantId);
		Session session = requireSession(workspaceId, sessionId);
		WorkflowRun run = findRun(session);
		if (run == null) {
			throw new WebApplicationException("Session run not found", Response.Status.CONFLICT);
		}
		List<String> allowScopes = sanitizeScopes(request == null ? null : request.allow_scopes());
		List<String> denyScopes = sanitizeScopes(request == null ? null : request.deny_scopes());

		ObjectNode checkpoint = run.checkpointJson instanceof ObjectNode node ? node : objectMapper.createObjectNode();
		ObjectNode snapshot = resolveSnapshot(run, run.workflowDefinition, session);
		String logicalNodeId = resolveLogicalNodeId(checkpoint, snapshot, run.workflowDefinition);
		if (logicalNodeId != null
				&& !logicalNodeId.isBlank()
				&& !checkpoint.has(ConversationGraphState.LOGICAL_NODE_ID)) {
			checkpoint.put(ConversationGraphState.LOGICAL_NODE_ID, logicalNodeId);
		}
		if (logicalNodeId != null
				&& !logicalNodeId.isBlank()
				&& !checkpoint.has(ConversationGraphState.LOGICAL_NODE_INSTANCE_ID)) {
			checkpoint.put(ConversationGraphState.LOGICAL_NODE_INSTANCE_ID, UUID.randomUUID().toString());
		}
		ObjectNode scopeNode = resolveNodeSnapshot(snapshot, logicalNodeId);
		if (scopeNode == null) {
			scopeNode = resolveScopeNodeSnapshot(snapshot);
		}
		if (scopeNode == null) {
			scopeNode = ensureDefaultScopeNode(snapshot);
		}
		applyScopeUpdate(scopeNode, allowScopes, denyScopes);

		checkpoint.set(ConversationGraphState.MCP_SNAPSHOT, snapshot);
		checkpointService.updateProjectionFromCheckpoint(run, state -> checkpoint);

		return technicalDetails(tenantId, workspaceId, sessionId);
	}

	@PUT
	@Path("/{sessionId}/tags")
	@Transactional
	public SessionTechnicalDetailsResponse updateSessionTags(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("sessionId") UUID sessionId,
			be.celerex.polymr.session.dto.SessionTagRequest request) {
		requireMembership(tenantId);
		Session session = requireSession(workspaceId, sessionId);
		WorkflowRun run = findRun(session);
		if (run == null) {
			throw new WebApplicationException("Session run not found", Response.Status.CONFLICT);
		}
		List<UUID> nextTagIds = request == null ? List.of() : sanitizeTagIds(request.valueIds(), workspaceId);

		ObjectNode checkpoint = run.checkpointJson instanceof ObjectNode node ? node : objectMapper.createObjectNode();
		ObjectNode snapshot = resolveSnapshot(run, run.workflowDefinition, session);
		String logicalNodeId = resolveLogicalNodeId(checkpoint, snapshot, run.workflowDefinition);
		if (logicalNodeId != null
				&& !logicalNodeId.isBlank()
				&& !checkpoint.has(ConversationGraphState.LOGICAL_NODE_ID)) {
			checkpoint.put(ConversationGraphState.LOGICAL_NODE_ID, logicalNodeId);
		}
		if (logicalNodeId != null
				&& !logicalNodeId.isBlank()
				&& !checkpoint.has(ConversationGraphState.LOGICAL_NODE_INSTANCE_ID)) {
			checkpoint.put(ConversationGraphState.LOGICAL_NODE_INSTANCE_ID, UUID.randomUUID().toString());
		}
		ObjectNode nodeSnapshot = resolveNodeSnapshot(snapshot, logicalNodeId);
		if (nodeSnapshot == null) {
			nodeSnapshot = resolveScopeNodeSnapshot(snapshot);
		}
		if (nodeSnapshot == null) {
			nodeSnapshot = ensureDefaultScopeNode(snapshot);
		}
		ObjectNode mcpNode = nodeSnapshot.get("mcp") instanceof ObjectNode obj ? obj : nodeSnapshot.putObject("mcp");
		List<UUID> previousTagIds = readUuidArray(mcpNode.get("tags"));
		ArrayNode tagsArray = mcpNode.putArray("tags");
		nextTagIds.forEach(tagId -> tagsArray.add(tagId.toString()));

		checkpoint.set(ConversationGraphState.MCP_SNAPSHOT, snapshot);
		checkpointService.updateProjectionFromCheckpoint(run, state -> checkpoint);

		List<SessionEvent> tagChangeEvents = emitTagChangeEvents(session, previousTagIds, nextTagIds, requireUserId(), workspaceId);
		broadcastSessionEvents(session, tagChangeEvents);

		return technicalDetails(tenantId, workspaceId, sessionId);
	}

	@PUT
	@Path("/{sessionId}/mcp-servers")
	@Transactional
	public SessionTechnicalDetailsResponse addMcpServer(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("sessionId") UUID sessionId,
			be.celerex.polymr.session.dto.SessionMcpServerRequest request) {
		requireMembership(tenantId);
		Session session = requireSession(workspaceId, sessionId);
		WorkflowRun run = findRun(session);
		if (run == null) {
			throw new WebApplicationException("Session run not found", Response.Status.CONFLICT);
		}
		UUID serverId = request == null ? null : request.server_id();
		if (serverId == null) {
			throw new WebApplicationException("Server id is required", Response.Status.BAD_REQUEST);
		}
		McpServer server = entityManager.find(McpServer.class, serverId);
		if (server == null || server.workspace == null || !server.workspace.id.equals(workspaceId)) {
			throw new WebApplicationException("MCP server not found", Response.Status.NOT_FOUND);
		}
		ObjectNode checkpoint = run.checkpointJson instanceof ObjectNode node ? node : objectMapper.createObjectNode();
		ObjectNode snapshot = resolveSnapshot(run, run.workflowDefinition, session);
		ObjectNode rootMcp = snapshot.get("mcp") instanceof ObjectNode node ? node : snapshot.putObject("mcp");
		ArrayNode serversArray = rootMcp.get("servers") instanceof ArrayNode arr ? arr : rootMcp.putArray("servers");
		boolean exists = false;
		for (JsonNode entry : serversArray) {
			if (entry.isTextual() && entry.asText().equals(serverId.toString())) {
				exists = true;
				break;
			}
		}
		if (!exists) {
			serversArray.add(serverId.toString());
		}
		checkpoint.set(ConversationGraphState.MCP_SNAPSHOT, snapshot);
		checkpointService.updateProjectionFromCheckpoint(run, state -> checkpoint);
		return technicalDetails(tenantId, workspaceId, sessionId);
	}

	private ObjectNode resolveScopeNodeSnapshot(ObjectNode snapshot) {
		ObjectNode toolNode = resolveNodeSnapshot(snapshot, "tool_exec");
		if (toolNode != null) {
			return toolNode;
		}
		return resolvePrimaryNodeSnapshot(snapshot);
	}

	private ObjectNode resolveNodeSnapshot(ObjectNode snapshot, String nodeId) {
		if (snapshot == null || nodeId == null || nodeId.isBlank()) {
			return null;
		}
		JsonNode nodesNode = snapshot.get("nodes");
		if (nodesNode == null || !nodesNode.isObject()) {
			return null;
		}
		JsonNode node = nodesNode.get(nodeId);
		return node instanceof ObjectNode objectNode ? objectNode : null;
	}

	private ObjectNode ensureDefaultScopeNode(ObjectNode snapshot) {
		ObjectNode nodes = snapshot.get("nodes") instanceof ObjectNode obj ? obj : snapshot.putObject("nodes");
		return nodes.get("tool_exec") instanceof ObjectNode existing ? existing : nodes.putObject("tool_exec");
	}

	private void applyScopeUpdate(ObjectNode node, List<String> allowScopes, List<String> denyScopes) {
		if (node == null) {
			return;
		}
		ObjectNode scopesNode = node.get("scopes") instanceof ObjectNode obj ? obj : node.putObject("scopes");
		var allowArray = scopesNode.putArray("allow_scopes");
		allowScopes.forEach(allowArray::add);
		var denyArray = scopesNode.putArray("deny_scopes");
		denyScopes.forEach(denyArray::add);
	}

	private List<String> resolveAvailableScopes(List<UUID> serverIds) {
		if (serverIds == null || serverIds.isEmpty()) {
			return List.of();
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
		Set<String> scopes = new java.util.LinkedHashSet<>();
		for (McpServerTool tool : tools) {
			if (tool == null) {
				continue;
			}
			resolveToolScopes(tool).forEach(scopes::add);
		}
		return scopes.stream().sorted().collect(Collectors.toList());
	}

	private List<String> resolveToolScopes(McpServerTool tool) {
		if (tool == null) {
			return List.of();
		}
		List<String> custom = readStringArray(tool.customScopes);
		if (!custom.isEmpty()) {
			return custom;
		}
		return readStringArray(tool.scopes);
	}

	private List<SessionToolServerSummary> buildServerTools(
			List<UUID> serverIds,
			List<String> allowScopes,
			List<String> denyScopes,
			List<String> stickyAllowed) {
		if (serverIds == null || serverIds.isEmpty()) {
			return List.of();
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
		boolean dynamicOnly = (allowScopes == null || allowScopes.isEmpty())
			&& (denyScopes == null || denyScopes.isEmpty());
		Set<String> stickySet = new java.util.LinkedHashSet<>(stickyAllowed == null ? List.of() : stickyAllowed);
		Map<UUID, List<SessionToolSummary>> byServer = new java.util.LinkedHashMap<>();
		for (McpServerTool tool : tools) {
			if (tool == null || tool.mcpServer == null) {
				continue;
			}
			List<String> toolScopes = resolveToolScopes(tool);
			String decision = resolveDecision(toolScopes, allowScopes, denyScopes, dynamicOnly);
			String fullName = toolSpecificationService.toolName(tool);
			boolean sticky = stickySet.contains(fullName);
			SessionToolSummary summary = new SessionToolSummary(tool.id, tool.toolName, tool.toolAlias, toolScopes, decision, sticky);
			byServer.computeIfAbsent(tool.mcpServer.id, key -> new ArrayList<>())
				.add(summary);
		}
		List<McpServer> servers = entityManager.createQuery("select s from McpServer s where s.id in :serverIds", McpServer.class)
			.setParameter("serverIds", serverIds)
			.getResultList();
		Map<UUID, McpServer> serverMap = servers.stream()
			.collect(Collectors.toMap(server -> server.id, server -> server));
		List<SessionToolServerSummary> result = new ArrayList<>();
		for (UUID serverId : serverIds) {
			List<SessionToolSummary> items = byServer.get(serverId);
			if (items == null || items.isEmpty()) {
				continue;
			}
			items.sort(
				java.util.Comparator.comparing(
					item -> item.tool_alias() != null && !item.tool_alias().isBlank() ? item.tool_alias() : item.tool_name()
				)
			);
			McpServer server = serverMap.get(serverId);
			result.add(new SessionToolServerSummary(serverId, server == null ? null : server.name, items));
		}
		return result;
	}

	private String resolveDecision(
			List<String> toolScopes,
			List<String> allowScopes,
			List<String> denyScopes,
			boolean dynamicOnly) {
		if (dynamicOnly) {
			return "dynamic";
		}
		if (toolScopes == null || toolScopes.isEmpty()) {
			return "allow";
		}
		boolean hasDynamic = false;
		for (String scope : toolScopes) {
			if (scope == null || scope.isBlank()) {
				continue;
			}
			int allowSpec = bestSpecificity(scope, allowScopes);
			int denySpec = bestSpecificity(scope, denyScopes);
			if (denySpec > 0 && denySpec >= allowSpec) {
				return "deny";
			}
			if (allowSpec == 0) {
				hasDynamic = true;
			}
		}
		if (hasDynamic) {
			return "dynamic";
		}
		return "allow";
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

	private List<String> sanitizeScopes(List<String> scopes) {
		if (scopes == null || scopes.isEmpty()) {
			return List.of();
		}
		return scopes.stream()
			.filter(entry -> entry != null && !entry.isBlank())
			.map(String::trim)
			.distinct()
			.sorted()
			.collect(Collectors.toList());
	}

	@GET
	@Path("/{sessionId}/canvases")
	public List<SessionCanvasResponse> canvases(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("sessionId") UUID sessionId) {
		requireMembership(tenantId);
		Session session = requireSession(workspaceId, sessionId);
		return listCanvases(session.id);
	}

	@GET
	@Path("/{sessionId}/summary")
	public SessionSummary summary(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("sessionId") UUID sessionId) {
		TenantMembership membership = requireMembership(tenantId);
		Session session = requireSession(workspaceId, sessionId);
		SessionSummary summary = summaryService.buildSummaries(List.of(session), membership.user.id)
			.stream()
			.findFirst()
			.orElse(null);
		if (summary == null) {
			return null;
		}
		WorkflowRun run = entityManager.createQuery("select r from WorkflowRun r where r.session.id = :sessionId", WorkflowRun.class)
			.setParameter("sessionId", session.id)
			.getResultStream()
			.findFirst()
			.orElse(null);
		return new SessionSummary(
			summary.id(),
			summary.title(),
			summary.status(),
			summary.needs_input(),
			summary.locked(),
			summary.participants(),
			summary.workflow_definition_id(),
			summary.workflow_definition_name(),
			summary.workflow_run_status(),
			summary.workflow_current_node(),
			summary.workflow_checkpoint_status(),
			summary.default_assistant_id(),
			summary.channel_id(),
			summary.channel_name(),
			summary.visibility(),
			summary.updated_at(),
			telemetryService.buildTelemetry(session, run),
			summary.current_activity()
		);
	}

	@GET
	public List<SessionSummary> list(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId) {
		TenantMembership membership = requireMembership(tenantId);
		requireWorkspace(tenantId, workspaceId);
		List<Session> sessions = summaryService.listVisibleActiveSessions(workspaceId, membership.user.id);
		return summaryService.buildSummaries(sessions, membership.user.id);
	}

	@GET
	@Path("/history")
	public SessionHistoryPageResponse history(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@QueryParam("offset") Integer offset,
			@QueryParam("limit") Integer limit) {
		TenantMembership membership = requireMembership(tenantId);
		requireWorkspace(tenantId, workspaceId);
		int pageOffset = offset == null || offset < 0 ? 0 : offset;
		int pageLimit = limit == null || limit < 1 ? 20 : Math.min(limit, 100);
		List<Session> sessions = summaryService.listVisibleHistorizedSessions(workspaceId, membership.user.id, pageOffset, pageLimit);
		long total = summaryService.countVisibleHistorizedSessions(workspaceId, membership.user.id);
		return new SessionHistoryPageResponse(summaryService.buildSummaries(sessions, membership.user.id), pageOffset, pageLimit, total);
	}

	@POST
	@Transactional
	public SessionResponse create(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			SessionRequest request) {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN, TenantRole.MEMBER);
		Workspace workspace = requireWorkspace(tenantId, workspaceId);
		WorkflowDefinition definition = resolveWorkflowDefinition(workspace, request);
		WorkflowDefinitionVersion definitionVersion = resolveReleasedVersion(definition);
		if (definitionVersion == null) {
			throw new WebApplicationException("Workflow has no released version", Response.Status.CONFLICT);
		}
		Channel channel = resolveChannel(workspace, request);
		Assistant assistant = resolveAssistant(tenantId, request, channel);
		SessionVisibility visibility = resolveWorkflowVisibility(definition);
		List<UUID> participantIds = resolveWorkflowParticipants(definition);
		boolean participantOverride = request != null && request.participant_ids() != null;
		if (participantOverride) {
			participantIds = request.participant_ids();
			visibility = participantIds == null || participantIds.isEmpty()
				? SessionVisibility.WORKSPACE
				: SessionVisibility.PRIVATE;
		}
		Session session = new Session();
		session.tenant = membership.tenant;
		session.workspace = workspace;
		session.defaultAssistant = assistant;
		session.channel = channel;
		session.createdBy = membership.user;
		session.title = request == null ? null : request.title();
		session.titleLocked = request != null && request.title() != null && !request.title().isBlank();
		session.status = definition.startTrigger == be.celerex.polymr.model.WorkflowStartTrigger.USER_PROMPT
			? SessionStatus.PAUSED
			: SessionStatus.ACTIVE;
		session.visibility = visibility;
		entityManager.persist(session);
		WorkflowRun run = new WorkflowRun();
		run.session = session;
		run.workflowDefinition = definition;
		run.workflowDefinitionVersion = definitionVersion;
		run.status = definition.startTrigger == be.celerex.polymr.model.WorkflowStartTrigger.USER_PROMPT
			? WorkflowRunStatus.PAUSED
			: WorkflowRunStatus.QUEUED;
		ObjectNode checkpoint = objectMapper.createObjectNode();
		ObjectNode snapshot = snapshotService.buildSnapshot(resolveDefinitionJson(run, definition), session);
		checkpoint.set(ConversationGraphState.MCP_SNAPSHOT, snapshot);
		run.checkpointJson = checkpoint;
		entityManager.persist(run);
		SessionParticipant owner = new SessionParticipant();
		owner.session = session;
		owner.user = membership.user;
		owner.role = SessionParticipantRole.OWNER;
		entityManager.persist(owner);
		if (visibility == SessionVisibility.PRIVATE) {
			if (participantIds == null || participantIds.isEmpty()) {
				throw new WebApplicationException("Private workflows require at least one participant", Response.Status.BAD_REQUEST);
			}
			for (UUID participantId : participantIds) {
				if (participantId == null || participantId.equals(membership.user.id)) {
					continue;
				}
				tenantAccessService.requireMembership(tenantId, participantId);
				SessionParticipant participant = new SessionParticipant();
				participant.session = session;
				participant.user = entityManager.getReference(User.class, participantId);
				participant.role = SessionParticipantRole.PARTICIPANT;
				entityManager.persist(participant);
			}
		}
		snapshotTagStates(session, workspace, channel);
		if (request != null && request.context() != null && !request.context().isBlank()) {
			ObjectNode payload = objectMapper.createObjectNode();
			payload.put("text", request.context().trim());
			payload.put("user_id", membership.user.id.toString());
			eventService.createEvent(session, SessionEventType.CONTEXT_MESSAGE, payload);
		}
		SessionSummary summary = summaryService.buildSummaries(List.of(session), membership.user.id)
			.stream()
			.findFirst()
			.orElse(null);
		registerCreateBroadcast(summary, session, membership.user.id);
		if (run.status == WorkflowRunStatus.QUEUED) {
			runDispatcher.notifyWorkAvailable();
		}
		return toResponse(session, run, definition);
	}

	@GET
	@Path("/{sessionId}/events")
	public List<SessionEventResponse> events(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("sessionId") UUID sessionId,
			@QueryParam("epoch") Integer epoch,
			@QueryParam("limit") Integer limit,
			@QueryParam("offset") Integer offset,
			@QueryParam("reverse") Boolean reverse) {
		requireMembership(tenantId);
		Session session = entityManager.find(Session.class, sessionId);
		if (session == null || !session.workspace.id.equals(workspaceId)) {
			throw new WebApplicationException("Session not found", Response.Status.NOT_FOUND);
		}
		Integer targetEpoch = epoch;
		if (targetEpoch == null || targetEpoch < 1) {
			targetEpoch = entityManager.createQuery("select max(e.epochId) from SessionEvent e where e.session.id = :sessionId", Integer.class)
				.setParameter("sessionId", sessionId)
				.getSingleResult();
		}
		if (targetEpoch == null || targetEpoch < 1) {
			return List.of();
		}
		boolean reverseOrder = Boolean.TRUE.equals(reverse) && limit != null && limit > 0;
		int resolvedOffset = offset == null || offset < 0 ? 0 : offset;
		var query = entityManager.createQuery(
				"select e from SessionEvent e where e.session.id = :sessionId and e.epochId = :epoch order "
					+ "by e.createdAt "
					+ (reverseOrder ? "desc" : "asc"),
				SessionEvent.class
			)
			.setParameter("sessionId", sessionId)
			.setParameter("epoch", targetEpoch);
		if (resolvedOffset > 0) {
			query.setFirstResult(resolvedOffset);
		}
		if (limit != null && limit > 0) {
			query.setMaxResults(Math.min(limit, 500));
		}
		List<SessionEvent> events = query.getResultList();
		if (reverseOrder) {
			java.util.Collections.reverse(events);
		}
		return events.stream()
			.map(this::toEventResponse)
			.collect(Collectors.toList());
	}

	@POST
	@Path("/{sessionId}/participants")
	@Transactional
	public List<SessionParticipantResponse> addParticipant(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("sessionId") UUID sessionId,
			SessionParticipantInviteRequest request) {
		TenantMembership membership = requireMembership(tenantId);
		Session session = requireSession(workspaceId, sessionId);
		if (request == null || request.user_id() == null) {
			throw new WebApplicationException("User is required", Response.Status.BAD_REQUEST);
		}
		tenantAccessService.requireMembership(tenantId, request.user_id());
		if (session.visibility == SessionVisibility.PRIVATE
				&& !participantService.isParticipant(session, membership.user.id)) {
			throw new WebApplicationException("Session is private", Response.Status.FORBIDDEN);
		}
		return participantService.addParticipant(session, request.user_id());
	}

	@PUT
	@Path("/{sessionId}")
	@Transactional
	public SessionResponse update(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("sessionId") UUID sessionId,
			SessionUpdateRequest request) {
		TenantMembership membership = requireMembership(tenantId);
		Session session = requireSession(workspaceId, sessionId);
		if (request != null) {
			if (request.title() != null) {
				String trimmed = request.title().trim();
				session.title = trimmed.isBlank() ? null : trimmed;
				session.titleLocked = true;
			}
			if (request.assistant_id() != null) {
				Assistant assistant = entityManager.find(Assistant.class, request.assistant_id());
				if (assistant == null || !assistant.tenant.id.equals(tenantId)) {
					throw new WebApplicationException("Assistant not found", Response.Status.NOT_FOUND);
				}
				session.defaultAssistant = assistant;
			}
		}
		WorkflowRun run = findRun(session);
		broadcastSessionUpdate(session, run, membership.user.id);
		return toResponse(session, run, run == null ? null : run.workflowDefinition);
	}

	@POST
	@Path("/{sessionId}/archive")
	@Transactional
	public Response archive(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("sessionId") UUID sessionId) {
		requireMembership(tenantId);
		Session session = requireSession(workspaceId, sessionId);
		if (session.locked) {
			throw new WebApplicationException("Session is locked", Response.Status.CONFLICT);
		}
		WorkflowRun run = findRun(session);
		rejectPendingToolApproval(session, run);
		session.status = SessionStatus.COMPLETED;
		if (run != null) {
			run.status = WorkflowRunStatus.PAUSED;
			run.runtimeServerId = null;
		}
		broadcastSessionUpdate(session, run, requireUserId());
		return Response.noContent().build();
	}

	@POST
	@Path("/{sessionId}/reactivate")
	@Transactional
	public Response reactivate(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("sessionId") UUID sessionId) {
		requireMembership(tenantId);
		Session session = requireSession(workspaceId, sessionId);
		if (session.locked) {
			throw new WebApplicationException("Session is locked", Response.Status.CONFLICT);
		}
		if (session.status != SessionStatus.COMPLETED) {
			throw new WebApplicationException("Session is not completed", Response.Status.CONFLICT);
		}
		WorkflowRun run = findRun(session);
		if (run == null) {
			throw new WebApplicationException("Session has no workflow run", Response.Status.CONFLICT);
		}
		if (run.status != WorkflowRunStatus.PAUSED) {
			throw new WebApplicationException("Session workflow is already finished", Response.Status.CONFLICT);
		}
		session.status = SessionStatus.PAUSED;
		session.locked = false;
		run.status = WorkflowRunStatus.PAUSED;
		run.runtimeServerId = null;
		ObjectNode payload = objectMapper.createObjectNode();
		payload.put("text", "Session reactivated.");
		payload.put("action", "session_reactivated");
		payload.put("user_id", requireUserId().toString());
		eventService.createEvent(session, SessionEventType.AUDIT, payload);
		broadcastSessionUpdate(session, run, requireUserId());
		return Response.noContent().build();
	}

	@DELETE
	@Path("/{sessionId}")
	@Transactional
	public Response delete(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("sessionId") UUID sessionId) {
		requireMembership(tenantId);
		Session session = requireSession(workspaceId, sessionId);
		if (session.locked) {
			throw new WebApplicationException("Session is locked", Response.Status.CONFLICT);
		}
		List<UUID> childSessionIds = entityManager.createQuery("select s.id from Session s where s.parentSession.id = :parentSessionId", UUID.class)
			.setParameter("parentSessionId", session.id)
			.getResultList();
		java.util.Set<UUID> participantIds = session.visibility == SessionVisibility.PRIVATE
			? participantService.listParticipants(session)
				.stream()
				.map(SessionParticipantResponse::user_id)
				.collect(Collectors.toSet())
			: java.util.Set.of();
		UUID deletedSessionId = session.id;
		UUID deletedWorkspaceId = session.workspace.id;
		SessionVisibility visibility = session.visibility;
		deleteSessionsByIds(childSessionIds);
		entityManager.createNativeQuery("delete from session_participant_connections where session_id = :sessionId")
			.setParameter("sessionId", deletedSessionId)
			.executeUpdate();
		entityManager.createNativeQuery("delete from session_participants where session_id = :sessionId")
			.setParameter("sessionId", deletedSessionId)
			.executeUpdate();
		entityManager.createQuery("delete from SessionEventResource r where r.sessionEvent.session.id = :sessionId")
			.setParameter("sessionId", deletedSessionId)
			.executeUpdate();
		entityManager.createQuery("delete from SessionEvent e where e.session.id = :sessionId")
			.setParameter("sessionId", deletedSessionId)
			.executeUpdate();
		entityManager.createQuery("delete from SessionCostEntry e where e.session.id = :sessionId")
			.setParameter("sessionId", deletedSessionId)
			.executeUpdate();
		entityManager.createQuery("delete from SessionCanvas c where c.session.id = :sessionId")
			.setParameter("sessionId", deletedSessionId)
			.executeUpdate();
		entityManager.createQuery("delete from SessionTagSelection s where s.session.id = :sessionId")
			.setParameter("sessionId", deletedSessionId)
			.executeUpdate();
		entityManager.createQuery("update McpCallLog l set l.session = null where l.session.id = :sessionId")
			.setParameter("sessionId", deletedSessionId)
			.executeUpdate();
		entityManager.createQuery("delete from WorkflowRunCheckpoint c where c.workflowRun.session.id = :sessionId")
			.setParameter("sessionId", deletedSessionId)
			.executeUpdate();
		entityManager.createQuery("delete from WorkflowRun r where r.session.id = :sessionId")
			.setParameter("sessionId", deletedSessionId)
			.executeUpdate();
		entityManager.createQuery("delete from Session s where s.id = :sessionId")
			.setParameter("sessionId", deletedSessionId)
			.executeUpdate();
		entityManager.flush();
		registerDeleteBroadcast(deletedSessionId, deletedWorkspaceId, visibility, participantIds);
		return Response.noContent().build();
	}

	private void deleteSessionsByIds(List<UUID> sessionIds) {
		if (sessionIds == null || sessionIds.isEmpty()) {
			return;
		}
		entityManager.createNativeQuery("delete from session_participant_connections where session_id in :sessionIds")
			.setParameter("sessionIds", sessionIds)
			.executeUpdate();
		entityManager.createNativeQuery("delete from session_participants where session_id in :sessionIds")
			.setParameter("sessionIds", sessionIds)
			.executeUpdate();
		entityManager.createQuery("delete from SessionEventResource r where r.sessionEvent.session.id in :sessionIds")
			.setParameter("sessionIds", sessionIds)
			.executeUpdate();
		entityManager.createQuery("delete from SessionEvent e where e.session.id in :sessionIds")
			.setParameter("sessionIds", sessionIds)
			.executeUpdate();
		entityManager.createQuery("delete from SessionCostEntry e where e.session.id in :sessionIds")
			.setParameter("sessionIds", sessionIds)
			.executeUpdate();
		entityManager.createQuery("delete from SessionCanvas c where c.session.id in :sessionIds")
			.setParameter("sessionIds", sessionIds)
			.executeUpdate();
		entityManager.createQuery("delete from SessionTagSelection s where s.session.id in :sessionIds")
			.setParameter("sessionIds", sessionIds)
			.executeUpdate();
		entityManager.createQuery("update McpCallLog l set l.session = null where l.session.id in :sessionIds")
			.setParameter("sessionIds", sessionIds)
			.executeUpdate();
		entityManager.createQuery("delete from WorkflowRunCheckpoint c where c.workflowRun.session.id in :sessionIds")
			.setParameter("sessionIds", sessionIds)
			.executeUpdate();
		entityManager.createQuery("delete from WorkflowRun r where r.session.id in :sessionIds")
			.setParameter("sessionIds", sessionIds)
			.executeUpdate();
		entityManager.createQuery("delete from Session s where s.id in :sessionIds")
			.setParameter("sessionIds", sessionIds)
			.executeUpdate();
	}

	private void registerDeleteBroadcast(
			UUID sessionId,
			UUID workspaceId,
			SessionVisibility visibility,
			java.util.Set<UUID> participantIds) {
		txRegistry.registerInterposedSynchronization(
			new Synchronization() {
				@Override
				public void beforeCompletion() {}

				@Override
				public void afterCompletion(int status) {
					if (status != Status.STATUS_COMMITTED) {
						return;
					}
					WorkspaceSocketEvent event = new WorkspaceSocketEvent("session.deleted", workspaceId, sessionId, java.util.Map.of("id", sessionId));
					if (visibility == SessionVisibility.WORKSPACE) {
						socketManager.broadcast(workspaceId, event);
					}
					else if (!participantIds.isEmpty()) {
						socketManager.broadcastToUsers(workspaceId, participantIds, event);
					}
				}
			}
		);
	}

	private void registerCreateBroadcast(SessionSummary summary, Session session, UUID userId) {
		if (session == null) {
			return;
		}
		UUID sessionId = session.id;
		UUID workspaceId = session.workspace.id;
		SessionVisibility visibility = session.visibility;
		java.util.Set<UUID> participantIds = visibility == SessionVisibility.PRIVATE ? java.util.Set.of(userId) : java.util.Set.of();
		txRegistry.registerInterposedSynchronization(
			new Synchronization() {
				@Override
				public void beforeCompletion() {}

				@Override
				public void afterCompletion(int status) {
					if (status != Status.STATUS_COMMITTED) {
						return;
					}
					WorkspaceSocketEvent event = new WorkspaceSocketEvent("session.created", workspaceId, sessionId, summary);
					if (visibility == SessionVisibility.WORKSPACE) {
						socketManager.broadcast(workspaceId, event);
					}
					else if (!participantIds.isEmpty()) {
						socketManager.broadcastToUsers(workspaceId, participantIds, event);
					}
				}
			}
		);
	}

	private WorkflowDefinition resolveWorkflowDefinition(Workspace workspace, SessionRequest request) {
		if (request != null && request.workflow_definition_id() != null) {
			WorkflowDefinition definition = entityManager.find(WorkflowDefinition.class, request.workflow_definition_id());
			if (definition == null
					|| definition.deletedAt != null
					|| !definition.workspace.id.equals(workspace.id)) {
				throw new WebApplicationException("Workflow not found", Response.Status.NOT_FOUND);
			}
			if (definition.disabled) {
				throw new WebApplicationException("Workflow is disabled", Response.Status.CONFLICT);
			}
			return definition;
		}
		List<WorkflowDefinition> definitions = entityManager.createQuery(
				"select w from WorkflowDefinition w where w.workspace.id = :workspaceId and w.deletedAt is null",
				WorkflowDefinition.class
			)
			.setParameter("workspaceId", workspace.id)
			.getResultList();
		WorkflowDefinition definition = definitions.stream()
			.filter((item) -> !item.disabled)
			.filter(workflowDefinitionService::isConversationDefinition)
			.findFirst()
			.orElse(null);
		if (definition != null) {
			return definition;
		}
		LOGGER.warnf("Conversation workflow not configured for workspace %s", workspace.id);
		throw new WebApplicationException("Conversation workflow not configured", Response.Status.BAD_REQUEST);
	}

	private SessionVisibility resolveWorkflowVisibility(WorkflowDefinition definition) {
		JsonNode definitionJson = resolveDefinitionJson(null, definition);
		if (definitionJson == null) {
			return SessionVisibility.WORKSPACE;
		}
		JsonNode visibility = definitionJson.get("session_visibility");
		if (visibility == null || visibility.isNull()) {
			return SessionVisibility.WORKSPACE;
		}
		String value = visibility.asText("").trim().toUpperCase();
		if ("PRIVATE".equals(value)) {
			return SessionVisibility.PRIVATE;
		}
		return SessionVisibility.WORKSPACE;
	}

	private List<UUID> resolveWorkflowParticipants(WorkflowDefinition definition) {
		JsonNode definitionJson = resolveDefinitionJson(null, definition);
		if (definitionJson == null) {
			return List.of();
		}
		JsonNode list = definitionJson.get("participant_ids");
		if (list == null || !list.isArray()) {
			return List.of();
		}
		List<UUID> ids = new ArrayList<>();
		for (JsonNode entry : list) {
			if (entry == null || entry.isNull()) {
				continue;
			}
			String value = entry.asText(null);
			if (value == null || value.isBlank()) {
				continue;
			}
			try {
				ids.add(UUID.fromString(value));
			}
			catch (IllegalArgumentException ignored) {}
		}
		return ids;
	}

	private Channel resolveChannel(Workspace workspace, SessionRequest request) {
		if (request == null || request.channel_id() == null) {
			return null;
		}
		Channel channel = entityManager.find(Channel.class, request.channel_id());
		if (channel == null || channel.workspace == null || !channel.workspace.id.equals(workspace.id)) {
			throw new WebApplicationException("Channel not found", Response.Status.NOT_FOUND);
		}
		return channel;
	}

	private Assistant resolveAssistant(UUID tenantId, SessionRequest request, Channel channel) {
		if (request != null && request.assistant_id() != null) {
			Assistant assistant = entityManager.find(Assistant.class, request.assistant_id());
			if (assistant != null && assistant.tenant.id.equals(tenantId)) {
				return assistant;
			}
		}
		Assistant fallback = channel == null ? null : channel.assistant;
		if (fallback != null) {
			return fallback;
		}
		if (channel != null && channel.workspace != null) {
			return entityManager.createQuery(
					"select a from Assistant a where a.tenant.id = :tenantId and "
						+ "a.workspace.id = :workspaceId order by a.createdAt asc",
					Assistant.class
				)
				.setParameter("tenantId", tenantId)
				.setParameter("workspaceId", channel.workspace.id)
				.setMaxResults(1)
				.getResultStream()
				.findFirst()
				.orElse(null);
		}
		return entityManager.createQuery(
				"select a from Assistant a where a.tenant.id = :tenantId order by a.createdAt asc",
				Assistant.class
			)
			.setParameter("tenantId", tenantId)
			.setMaxResults(1)
			.getResultStream()
			.findFirst()
			.orElse(null);
	}

	private void snapshotTagStates(Session session, Workspace workspace, Channel channel) {
		if (channel != null) {
			List<ChannelTagSelection> states = entityManager.createQuery("select s from ChannelTagSelection s where s.channel.id = :channelId", ChannelTagSelection.class)
				.setParameter("channelId", channel.id)
				.getResultList();
			for (ChannelTagSelection state : states) {
				SessionTagSelection snapshot = new SessionTagSelection();
				snapshot.session = session;
				snapshot.category = state.category;
				snapshot.value = state.value;
				entityManager.persist(snapshot);
			}
			return;
		}
		List<WorkspaceTagSelection> states = entityManager.createQuery(
				"select s from WorkspaceTagSelection s where s.workspace.id = :workspaceId",
				WorkspaceTagSelection.class
			)
			.setParameter("workspaceId", workspace.id)
			.getResultList();
		for (WorkspaceTagSelection state : states) {
			SessionTagSelection snapshot = new SessionTagSelection();
			snapshot.session = session;
			snapshot.category = state.category;
			snapshot.value = state.value;
			entityManager.persist(snapshot);
		}
	}

	private ObjectNode resolveSnapshot(WorkflowRun run, WorkflowDefinition definition, Session session) {
		ObjectNode snapshot = null;
		if (run != null
				&& run.checkpointJson != null
				&& run.checkpointJson.has(ConversationGraphState.MCP_SNAPSHOT)) {
			JsonNode node = run.checkpointJson.get(ConversationGraphState.MCP_SNAPSHOT);
			snapshot = node instanceof ObjectNode objectNode
				? objectNode
				: objectMapper.convertValue(node, ObjectNode.class);
		}
		if (snapshot != null) {
			return snapshot;
		}
		if (definition != null && session != null) {
			return snapshotService.buildSnapshot(resolveDefinitionJson(run, definition), session);
		}
		return objectMapper.createObjectNode();
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

	private String resolveLogicalNodeId(JsonNode checkpoint, ObjectNode snapshot, WorkflowDefinition definition) {
		String logicalNodeId = checkpoint == null ? null : checkpoint.path(ConversationGraphState.LOGICAL_NODE_ID).asText(null);
		if (logicalNodeId != null && !logicalNodeId.isBlank()) {
			return logicalNodeId;
		}
		JsonNode definitionJson = resolveDefinitionJson(null, definition);
		if (definitionJson != null) {
			JsonNode logicalStart = definitionJson.get("logical_start");
			if (logicalStart != null && logicalStart.isTextual() && !logicalStart.asText().isBlank()) {
				return logicalStart.asText();
			}
			JsonNode start = definitionJson.get("start");
			if (start != null && start.isTextual() && !start.asText().isBlank()) {
				return start.asText();
			}
		}
		if (snapshot == null) {
			return null;
		}
		JsonNode nodesNode = snapshot.get("nodes");
		if (nodesNode != null && nodesNode.isObject()) {
			if (nodesNode.has("llm")) {
				return "llm";
			}
			if (nodesNode.fields().hasNext()) {
				return nodesNode.fields().next().getKey();
			}
		}
		return null;
	}

	private ObjectNode readObject(JsonNode node) {
		return node instanceof ObjectNode objectNode ? objectNode : null;
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
		WorkflowDefinitionVersion released = resolveReleasedVersion(definition);
		return released == null ? null : released.definitionJson;
	}

	private WorkflowDefinitionVersion resolveReleasedVersion(WorkflowDefinition definition) {
		if (definition == null) {
			return null;
		}
		return entityManager.createQuery(
				"select v from WorkflowDefinitionVersion v where v.workflowDefinition.id = :workflowId "
					+ "and v.releasedAt is not null and v.deprecatedAt is null",
				WorkflowDefinitionVersion.class
			)
			.setParameter("workflowId", definition.id)
			.setMaxResults(1)
			.getResultStream()
			.findFirst()
			.orElse(null);
	}

	private List<UUID> readUuidArray(JsonNode node) {
		if (node == null || !node.isArray()) {
			return List.of();
		}
		List<UUID> list = new ArrayList<>();
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

	private List<String> readStringArray(JsonNode node) {
		if (node == null || !node.isArray()) {
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

	private List<TagSummary> loadTags(List<UUID> tagIds) {
		if (tagIds == null || tagIds.isEmpty()) {
			return List.of();
		}
		List<TagValue> tags = entityManager.createQuery("select t from TagValue t join fetch t.category where t.id in :tagIds", TagValue.class)
			.setParameter("tagIds", tagIds)
			.getResultList();
		Map<UUID, TagValue> byId = tags.stream()
			.collect(Collectors.toMap(tag -> tag.id, tag -> tag));
		List<TagSummary> summaries = new ArrayList<>();
		for (UUID tagId : tagIds) {
			TagValue tag = byId.get(tagId);
			if (tag != null && tag.category != null) {
				summaries.add(new TagSummary(tag.id, tag.category.id, tag.category.name, tag.category.slug, tag.name, tag.slug));
			}
		}
		return summaries;
	}

	private List<UUID> sanitizeTagIds(List<UUID> tagIds, UUID workspaceId) {
		if (tagIds == null || tagIds.isEmpty() || workspaceId == null) {
			return List.of();
		}
		List<UUID> filtered = tagIds.stream()
			.filter(id -> id != null)
			.distinct()
			.toList();
		if (filtered.isEmpty()) {
			return List.of();
		}
		List<TagValue> tags = entityManager.createQuery(
				"select t from TagValue t join fetch t.category where t.id in :tagIds and "
					+ "t.category.workspace.id = :workspaceId and t.deletedAt is null and t.category.deletedAt "
					+ "is null",
				TagValue.class
			)
			.setParameter("tagIds", filtered)
			.setParameter("workspaceId", workspaceId)
			.getResultList();
		Set<UUID> allowed = tags.stream()
			.map(tag -> tag.id)
			.collect(Collectors.toSet());
		return filtered.stream().filter(allowed::contains).toList();
	}

	private List<SessionEvent> emitTagChangeEvents(
			Session session,
			List<UUID> previousTagIds,
			List<UUID> nextTagIds,
			UUID userId,
			UUID workspaceId) {
		if (session == null) {
			return List.of();
		}
		Set<UUID> combined = new HashSet<>();
		if (previousTagIds != null) {
			combined.addAll(previousTagIds);
		}
		if (nextTagIds != null) {
			combined.addAll(nextTagIds);
		}
		if (combined.isEmpty()) {
			return List.of();
		}
		Map<UUID, TagValue> tagById = loadTagsById(combined, workspaceId);
		Map<UUID, TagValue> previousByCategory = mapTagsByCategory(previousTagIds, tagById);
		Map<UUID, TagValue> nextByCategory = mapTagsByCategory(nextTagIds, tagById);

		Set<UUID> categoryIds = new HashSet<>();
		categoryIds.addAll(previousByCategory.keySet());
		categoryIds.addAll(nextByCategory.keySet());
		if (categoryIds.isEmpty()) {
			return List.of();
		}

		List<SessionEvent> createdEvents = new ArrayList<>();
		Set<String> usedTools = loadUsedToolNames(session.id);
		Map<UUID, Set<UUID>> serversByTag = loadServersByTagId(combined);
		for (UUID categoryId : categoryIds) {
			TagValue previous = previousByCategory.get(categoryId);
			TagValue next = nextByCategory.get(categoryId);
			if (previous == null && next == null) {
				continue;
			}
			if (previous != null && next != null && previous.id.equals(next.id)) {
				continue;
			}
			Set<UUID> impactedServers = new HashSet<>();
			if (previous != null) {
				impactedServers.addAll(serversByTag.getOrDefault(previous.id, Set.of()));
			}
			if (next != null) {
				impactedServers.addAll(serversByTag.getOrDefault(next.id, Set.of()));
			}
			List<String> impactedTools = resolveImpactedTools(impactedServers, usedTools);
			ObjectNode payload = objectMapper.createObjectNode();
			String categoryLabel = next != null && next.category != null
				? next.category.name
				: previous != null && previous.category != null ? previous.category.name : "Tag";
			if (previous != null && previous.category != null) {
				payload.put("tag_category_id", previous.category.id.toString());
				payload.put("tag_category_slug", previous.category.slug);
				payload.put("tag_category_name", previous.category.name);
			}
			else if (next != null && next.category != null) {
				payload.put("tag_category_id", next.category.id.toString());
				payload.put("tag_category_slug", next.category.slug);
				payload.put("tag_category_name", next.category.name);
			}
			if (previous != null) {
				payload.put("from_tag_id", previous.id.toString());
				payload.put("from_tag_value", previous.name);
				payload.put("from_tag_slug", previous.slug);
			}
			if (next != null) {
				payload.put("to_tag_id", next.id.toString());
				payload.put("to_tag_value", next.name);
				payload.put("to_tag_slug", next.slug);
			}
			if (userId != null) {
				payload.put("user_id", userId.toString());
			}
			ArrayNode impactedArray = payload.putArray("impacted_tools");
			impactedTools.forEach(impactedArray::add);
			payload.put("text", buildTagAuditText(categoryLabel, previous, next, impactedTools));
			String llmMessage = buildTagChangeUserMessage(categoryLabel, previous, next, impactedTools);
			if (llmMessage != null && !llmMessage.isBlank()) {
				payload.put("llm_message", llmMessage);
			}
			createdEvents.add(eventService.createEvent(session, SessionEventType.SESSION_TAG_CHANGE, payload));
		}
		return createdEvents;
	}

	private Map<UUID, TagValue> loadTagsById(Set<UUID> tagIds, UUID workspaceId) {
		if (tagIds == null || tagIds.isEmpty() || workspaceId == null) {
			return Map.of();
		}
		List<TagValue> tags = entityManager.createQuery(
				"select t from TagValue t join fetch t.category where t.id in :tagIds and "
					+ "t.category.workspace.id = :workspaceId",
				TagValue.class
			)
			.setParameter("tagIds", tagIds)
			.setParameter("workspaceId", workspaceId)
			.getResultList();
		return tags.stream()
			.collect(Collectors.toMap(tag -> tag.id, tag -> tag));
	}

	private Map<UUID, TagValue> mapTagsByCategory(List<UUID> tagIds, Map<UUID, TagValue> tagById) {
		if (tagIds == null || tagIds.isEmpty()) {
			return Map.of();
		}
		Map<UUID, TagValue> result = new HashMap<>();
		for (UUID tagId : tagIds) {
			TagValue tag = tagById.get(tagId);
			if (tag == null || tag.category == null || tag.category.id == null) {
				continue;
			}
			result.put(tag.category.id, tag);
		}
		return result;
	}

	private Set<String> loadUsedToolNames(UUID sessionId) {
		if (sessionId == null) {
			return Set.of();
		}
		List<SessionEvent> events = entityManager.createQuery(
				"select e from SessionEvent e where e.session.id = :sessionId and e.eventType in :types",
				SessionEvent.class
			)
			.setParameter("sessionId", sessionId)
			.setParameter("types", EnumSet.of(SessionEventType.TOOL_CALL, SessionEventType.TOOL_RESULT))
			.getResultList();
		Set<String> names = new HashSet<>();
		for (SessionEvent event : events) {
			JsonNode payload = event.payloadJson;
			if (payload == null || payload.isNull()) {
				continue;
			}
			JsonNode nameNode = payload.get("tool_name");
			if (nameNode != null && nameNode.isTextual()) {
				String name = nameNode.asText();
				if (name != null && !name.isBlank()) {
					names.add(name);
				}
			}
		}
		return names;
	}

	private Map<UUID, Set<UUID>> loadServersByTagId(Set<UUID> tagIds) {
		if (tagIds == null || tagIds.isEmpty()) {
			return Map.of();
		}
		List<McpServerPolicy> policies = entityManager.createQuery(
				"select p from McpServerPolicy p join fetch p.mcpServer where p.tag.id in :tagIds",
				McpServerPolicy.class
			)
			.setParameter("tagIds", tagIds)
			.getResultList();
		Map<UUID, Set<UUID>> result = new HashMap<>();
		for (McpServerPolicy policy : policies) {
			if (policy.tag == null || policy.mcpServer == null) {
				continue;
			}
			result.computeIfAbsent(policy.tag.id, key -> new HashSet<>())
				.add(policy.mcpServer.id);
		}
		return result;
	}

	private List<String> resolveImpactedTools(Set<UUID> serverIds, Set<String> usedTools) {
		if (serverIds == null || serverIds.isEmpty() || usedTools == null || usedTools.isEmpty()) {
			return List.of();
		}
		List<McpServerTool> tools = entityManager.createQuery(
				"select t from McpServerTool t where t.mcpServer.id in :serverIds and "
					+ "t.deleted = false and t.disabled = false",
				McpServerTool.class
			)
			.setParameter("serverIds", serverIds)
			.getResultList();
		Set<String> impacted = new HashSet<>();
		for (McpServerTool tool : tools) {
			if (tool == null || tool.toolName == null) {
				continue;
			}
			if (usedTools.contains(tool.toolName)) {
				impacted.add(tool.toolName);
			}
		}
		return impacted.stream().sorted().toList();
	}

	private String buildTagAuditText(String type, TagValue previous, TagValue next, List<String> impactedTools) {
		String tagType = type == null || type.isBlank() ? "tag" : type;
		String impacted = impactedTools == null || impactedTools.isEmpty() ? "none" : String.join(", ", impactedTools);
		if (previous == null && next != null) {
			return String.format("Tag updated: %s set to %s. Impacted tools: %s", tagType, next.name, impacted);
		}
		if (previous != null && next == null) {
			return String.format("Tag updated: %s removed (was %s). Impacted tools: %s", tagType, previous.name, impacted);
		}
		if (previous != null && next != null) {
			return String.format("Tag updated: %s %s → %s. Impacted tools: %s", tagType, previous.name, next.name, impacted);
		}
		return String.format("Tag updated: %s. Impacted tools: %s", tagType, impacted);
	}

	private String buildTagChangeUserMessage(String type, TagValue previous, TagValue next, List<String> impactedTools) {
		if (impactedTools == null || impactedTools.isEmpty()) {
			return null;
		}
		String tagType = type == null || type.isBlank() ? "tag" : type;
		String tools = String.join(", ", impactedTools);
		if (previous == null && next != null) {
			return String.format(
				"I set the %s to %s, this may impact results in previous turns from tools %s",
				tagType,
				next.name,
				tools
			);
		}
		if (previous != null && next == null) {
			return String.format(
				"I removed the %s, now it is no longer %s, this may impact results in previous turns from tools %s",
				tagType,
				previous.name,
				tools
			);
		}
		if (previous != null && next != null) {
			return String.format(
				"I changed the %s from %s to %s, this may impact results in previous turns from tools %s",
				tagType,
				previous.name,
				next.name,
				tools
			);
		}
		return String.format("I changed the %s, this may impact results in previous turns from tools %s", tagType, tools);
	}

	private List<McpServerSummary> loadServers(List<UUID> serverIds) {
		if (serverIds == null || serverIds.isEmpty()) {
			return List.of();
		}
		List<McpServer> servers = entityManager.createQuery("select s from McpServer s where s.id in :serverIds", McpServer.class)
			.setParameter("serverIds", serverIds)
			.getResultList();
		Map<UUID, McpServer> byId = servers.stream()
			.collect(Collectors.toMap(server -> server.id, server -> server));
		List<McpServerSummary> summaries = new ArrayList<>();
		for (UUID serverId : serverIds) {
			McpServer server = byId.get(serverId);
			if (server != null) {
				summaries.add(new McpServerSummary(server.id, server.name));
			}
		}
		return summaries;
	}

	private SessionResponse toResponse(Session session, WorkflowRun run, WorkflowDefinition definition) {
		return new SessionResponse(
			session.id,
			session.title,
			session.status,
			run == null ? null : run.id,
			definition == null ? null : definition.id,
			definition == null ? null : definition.name,
			session.defaultAssistant == null ? null : session.defaultAssistant.id,
			session.channel == null ? null : session.channel.id,
			session.channel == null ? null : session.channel.name,
			listCanvases(session.id)
		);
	}

	private List<SessionCanvasResponse> listCanvases(UUID sessionId) {
		if (sessionId == null) {
			return List.of();
		}
		return entityManager.createQuery(
				"select c from SessionCanvas c where c.session.id = :sessionId order by lower(c.title), c.createdAt",
				SessionCanvas.class
			)
			.setParameter("sessionId", sessionId)
			.getResultList()
			.stream()
			.map(this::toCanvasResponse)
			.toList();
	}

	private SessionCanvasResponse toCanvasResponse(SessionCanvas canvas) {
		return new SessionCanvasResponse(
			canvas.id,
			canvas.logicalId,
			canvas.title,
			canvas.sourceSfc,
			canvas.compiledBundle,
			canvas.session == null
					|| canvas.session.workspace == null
				? null
				: canvas.session.workspace.externalFrontendImports,
			canvas.updatedAt
		);
	}

	private WorkflowRun findRun(Session session) {
		if (session == null) {
			return null;
		}
		List<WorkflowRun> runs = entityManager.createQuery(
				"select r from WorkflowRun r join fetch r.workflowDefinition where r.session.id = :sessionId",
				WorkflowRun.class
			)
			.setParameter("sessionId", session.id)
			.setMaxResults(1)
			.getResultList();
		return runs.isEmpty() ? null : runs.get(0);
	}

	private void rejectPendingToolApproval(Session session, WorkflowRun run) {
		if (session == null || run == null || run.checkpointJson == null || !run.checkpointJson.isObject()) {
			return;
		}
		ObjectNode checkpoint = (ObjectNode) run.checkpointJson;
		ObjectNode pending = PendingState.pendingFromCheckpoint(checkpoint, objectMapper);
		boolean approvalPending = PendingState.readBoolean(pending, PendingState.TOOL_APPROVAL, PendingState.TOOL_APPROVAL_PENDING);
		if (!approvalPending) {
			return;
		}
		ObjectNode payload = objectMapper.createObjectNode();
		String requestId = PendingState.readText(pending, PendingState.TOOL_APPROVAL, PendingState.TOOL_APPROVAL_REQUEST_ID);
		if (requestId != null && !requestId.isBlank()) {
			payload.put("request_id", requestId);
		}
		payload.put("decision", "deny");
		SessionEvent event = eventService.createEvent(session, SessionEventType.DECISION_RESULT, payload);
		broadcastSessionEvents(session, List.of(event));
		pending.remove(PendingState.TOOL_REQUESTS);
		pending.remove(PendingState.TOOL_USAGE);
		pending.remove(PendingState.TOOL_FAILURE);
		ObjectNode toolApproval = pending.with(PendingState.TOOL_APPROVAL);
		toolApproval.put(PendingState.TOOL_APPROVAL_PENDING, false);
		toolApproval.put(PendingState.TOOL_APPROVAL_DECISION, "deny");
		toolApproval.remove(PendingState.TOOL_APPROVAL_REQUEST_ID);
		toolApproval.remove(PendingState.TOOL_APPROVAL_BYPASS);
		toolApproval.remove(PendingState.TOOL_APPROVAL_ALLOW_SCOPES);
		toolApproval.remove(PendingState.TOOL_APPROVAL_DENY_SCOPES);
		pending.put(PendingState.SESSION_STATUS, SessionStatus.PAUSED.name());
		pending.put(PendingState.RUN_STATUS, WorkflowRunStatus.PAUSED.name());
		pending.put(PendingState.LOCKED, false);
		checkpoint.set(PendingState.PENDING, pending);
	}

	private void broadcastSessionUpdate(Session session, WorkflowRun run, UUID userId) {
		if (session == null) {
			return;
		}
		SessionSummary summary = summaryService.buildSummaries(List.of(session), userId)
			.stream()
			.findFirst()
			.orElse(null);
		WorkspaceSocketEvent event = new WorkspaceSocketEvent("session.updated", session.workspace.id, session.id, summary);
		if (session.visibility == SessionVisibility.WORKSPACE) {
			socketManager.broadcast(session.workspace.id, event);
			return;
		}
		java.util.Set<UUID> participantIds = participantService.listParticipants(session)
			.stream()
			.map(SessionParticipantResponse::user_id)
			.collect(Collectors.toSet());
		if (!participantIds.isEmpty()) {
			socketManager.broadcastToUsers(session.workspace.id, participantIds, event);
		}
	}

	private void broadcastSessionEvents(Session session, List<SessionEvent> events) {
		if (session == null || events == null || events.isEmpty()) {
			return;
		}
		List<SessionEventResponse> payload = events.stream()
			.map(this::toEventResponse)
			.collect(Collectors.toList());
		txRegistry.registerInterposedSynchronization(
			new Synchronization() {
				@Override
				public void beforeCompletion() {}

				@Override
				public void afterCompletion(int status) {
					if (status != Status.STATUS_COMMITTED) {
						return;
					}
					for (SessionEventResponse event : payload) {
						socketManager.broadcastToSession(session.id, new WorkspaceSocketEvent("session.event", session.workspace.id, session.id, event));
					}
				}
			}
		);
	}

	private SessionEventResponse toEventResponse(SessionEvent event) {
		return new SessionEventResponse(
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
	}

	private byte[] readBytes(InputStream input) {
		try {
			return input.readAllBytes();
		}
		catch (IOException ex) {
			throw new WebApplicationException("Unable to read voice payload", Response.Status.BAD_REQUEST);
		}
	}

	private Session requireSession(UUID workspaceId, UUID sessionId) {
		Session session = entityManager.find(Session.class, sessionId);
		if (session == null || !session.workspace.id.equals(workspaceId)) {
			throw new WebApplicationException("Session not found", Response.Status.NOT_FOUND);
		}
		return session;
	}

	private Workspace requireWorkspace(UUID tenantId, UUID workspaceId) {
		Workspace workspace = entityManager.find(Workspace.class, workspaceId);
		if (workspace == null || !workspace.tenant.id.equals(tenantId)) {
			throw new WebApplicationException("Workspace not found", Response.Status.NOT_FOUND);
		}
		return workspace;
	}

	private TenantMembership requireMembership(UUID tenantId) {
		return tenantAccessService.requireMembership(tenantId, requireUserId());
	}

	private UUID requireUserId() {
		if (securityContext == null || securityContext.getUserPrincipal() == null) {
			throw new WebApplicationException("Unauthorized", Response.Status.UNAUTHORIZED);
		}
		return UUID.fromString(securityContext.getUserPrincipal().getName());
	}
}
