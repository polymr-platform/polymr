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

package be.celerex.polymr.mcp;

import be.celerex.polymr.model.ChannelMcpServer;
import be.celerex.polymr.model.ChannelScope;
import be.celerex.polymr.model.ChannelTagSelection;
import be.celerex.polymr.model.Session;
import be.celerex.polymr.model.TagValue;
import be.celerex.polymr.model.TagCategory;
import be.celerex.polymr.model.WorkflowDefinition;
import be.celerex.polymr.model.WorkspaceTagSelection;
import be.celerex.polymr.model.McpServerTool;
import be.celerex.polymr.model.McpProtocol;
import be.celerex.polymr.model.McpServer;
import be.celerex.polymr.model.McpServerVisibility;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import be.celerex.polymr.model.Workspace;

@ApplicationScoped
public class WorkflowMcpSnapshotService {
	@Inject
	EntityManager entityManager;

	@Inject
	ObjectMapper objectMapper;

	@Inject
	WorkspaceScopeService workspaceScopeService;

	@Inject
	ScopePermissionEvaluator scopePermissionEvaluator;

	@Inject
	VirtualMcpService virtualMcpService;

	@Inject
	McpToolCatalogService toolCatalogService;

	public ObjectNode buildSnapshot(WorkflowDefinition definition, Session session) {
		JsonNode definitionJson = loadReleasedDefinition(definition);
		return buildSnapshot(definitionJson, session);
	}

	private JsonNode loadReleasedDefinition(WorkflowDefinition definition) {
		if (definition == null) {
			return null;
		}
		return entityManager.createQuery(
				"select v.definitionJson from WorkflowDefinitionVersion v where v.workflowDefinition.id = "
					+ ":workflowId "
					+ "and v.releasedAt is not null and v.deprecatedAt is null",
				JsonNode.class
			)
			.setParameter("workflowId", definition.id)
			.setMaxResults(1)
			.getResultStream()
			.findFirst()
			.orElse(null);
	}

	public ObjectNode buildSnapshot(JsonNode definitionJson, Session session) {
		ObjectNode snapshot = objectMapper.createObjectNode();
		if (definitionJson == null || session == null) {
			return snapshot;
		}
		ObjectNode nodes = snapshot.putObject("nodes");
		ObjectNode snapshotMcp = snapshot.putObject("mcp");
		List<UUID> workspaceServers = loadSessionServers(session);
		ArrayNode serversArray = snapshotMcp.putArray("servers");
		workspaceServers.forEach(serverId -> serversArray.add(serverId.toString()));
		boolean allowWorkflowTools = definitionJson.path("allow_workflow_tools").asBoolean(true);
		if (allowWorkflowTools) {
			McpServer workflowServer = ensureWorkflowStateServer(session.workspace);
			if (workflowServer != null) {
				addServerId(serversArray, workflowServer.id);
			}
		}
		ObjectNode definitionNodes = definitionJson.path("nodes") instanceof ObjectNode node ? node : objectMapper.createObjectNode();
		ObjectNode workflowMcp = readObject(definitionJson.get("mcp"));
		ObjectNode workflowScopes = readObject(definitionJson.get("scopes"));
		String logicalStart = definitionJson.path("logical_start").asText(null);
		if (logicalStart == null || logicalStart.isBlank()) {
			logicalStart = definitionJson.path("start").asText(null);
		}
		final String logicalStartNode = logicalStart;

		Map<UUID, UUID> workspaceTags = loadSessionTagDefaults(session);
		Map<UUID, TagValue> tagsById = loadTagsById(workspaceTags.values());

		List<McpServerTool> workspaceTools = loadWorkspaceTools(workspaceServers);

		WorkspaceScopeService.ScopePermissions workspaceScopes = loadSessionScopes(session);

		definitionNodes.fieldNames()
			.forEachRemaining(
				nodeId -> {
					ObjectNode node = readObject(definitionNodes.get(nodeId));
					ObjectNode nodeMcp = readObject(node.get("mcp"));
					ObjectNode nodeScopes = readObject(node.get("scopes"));
					ObjectNode nodeSnapshot = nodes.putObject(nodeId);

					ObjectNode mcpSnapshot = nodeSnapshot.putObject("mcp");
					boolean inheritTags = nodeMcp.path("inherit_workspace_tags")
						.asBoolean(workflowMcp.path("inherit_workspace_tags").asBoolean(false));
					boolean hideDeniedTools = nodeMcp.has("hide_denied_tools")
						? nodeMcp.path("hide_denied_tools").asBoolean(true)
						: workflowMcp.path("hide_denied_tools").asBoolean(true);
					List<UUID> tags = resolveTags(inheritTags, workflowMcp, nodeMcp, workspaceTags, tagsById);
					ArrayNode tagsArray = mcpSnapshot.putArray("tags");
					tags.forEach(tagId -> tagsArray.add(tagId.toString()));
					copyArray(mcpSnapshot, "allow", workflowMcp, nodeMcp);
					copyArray(mcpSnapshot, "deny", workflowMcp, nodeMcp);
					mcpSnapshot.put("hide_denied_tools", hideDeniedTools);

					ObjectNode scopesSnapshot = nodeSnapshot.putObject("scopes");
					boolean inheritScopes = nodeScopes.path("inherit_workspace_scopes")
						.asBoolean(workflowScopes.path("inherit_workspace_scopes").asBoolean(false));
					List<String> allowScopes = new ArrayList<>();
					List<String> denyScopes = new ArrayList<>();
					if (inheritScopes && logicalStartNode != null && logicalStartNode.equals(nodeId)) {
						allowScopes.addAll(workspaceScopes.allow());
						denyScopes.addAll(workspaceScopes.deny());
					}
					allowScopes.addAll(readStringArray(workflowScopes.get("allow_scopes")));
					denyScopes.addAll(readStringArray(workflowScopes.get("deny_scopes")));
					allowScopes.addAll(readStringArray(nodeScopes.get("allow_scopes")));
					denyScopes.addAll(readStringArray(nodeScopes.get("deny_scopes")));
					ArrayNode allowArray = scopesSnapshot.putArray("allow_scopes");
					allowScopes.forEach(allowArray::add);
					ArrayNode denyArray = scopesSnapshot.putArray("deny_scopes");
					denyScopes.forEach(denyArray::add);
				}
			);

		return snapshot;
	}

	private List<UUID> resolveTags(
			boolean inheritWorkspace,
			ObjectNode workflowMcp,
			ObjectNode nodeMcp,
			Map<UUID, UUID> workspaceTags,
			Map<UUID, TagValue> tagsById) {
		Map<UUID, UUID> result = new HashMap<>();
		if (inheritWorkspace) {
			result.putAll(workspaceTags);
		}
		for (UUID tagId : readUuidArray(workflowMcp.get("tags"))) {
			TagValue tag = tagsById.get(tagId);
			if (tag != null && tag.category != null) {
				result.put(tag.category.id, tagId);
			}
		}
		for (UUID tagId : readUuidArray(nodeMcp.get("tags"))) {
			TagValue tag = tagsById.get(tagId);
			if (tag != null && tag.category != null) {
				result.put(tag.category.id, tagId);
			}
		}
		return new ArrayList<>(result.values());
	}

	private Map<UUID, UUID> loadSessionTagDefaults(Session session) {
		if (session == null) {
			return Map.of();
		}
		if (session.channel != null) {
			List<ChannelTagSelection> states = entityManager.createQuery(
					"select s from ChannelTagSelection s left join fetch s.value left "
						+ "join fetch s.category where s.channel.id = :channelId",
					ChannelTagSelection.class
				)
				.setParameter("channelId", session.channel.id)
				.getResultList();
			Map<UUID, UUID> map = new HashMap<>();
			for (ChannelTagSelection state : states) {
				if (state.category != null && state.value != null) {
					map.put(state.category.id, state.value.id);
				}
			}
			return map;
		}
		List<WorkspaceTagSelection> states = entityManager.createQuery(
				"select s from WorkspaceTagSelection s left join fetch s.value left join "
					+ "fetch s.category where s.workspace.id = :workspaceId",
				WorkspaceTagSelection.class
			)
			.setParameter("workspaceId", session.workspace.id)
			.getResultList();
		Map<UUID, UUID> map = new HashMap<>();
		for (WorkspaceTagSelection state : states) {
			if (state.category != null && state.value != null) {
				map.put(state.category.id, state.value.id);
			}
		}
		return map;
	}

	private List<McpServerTool> loadWorkspaceTools(List<UUID> serverIds) {
		if (serverIds == null || serverIds.isEmpty()) {
			return List.of();
		}
		return entityManager.createQuery(
				"select t from McpServerTool t join fetch t.mcpServer "
					+ "where t.mcpServer.id in :serverIds and t.deleted = false and t.disabled = false",
				McpServerTool.class
			)
			.setParameter("serverIds", serverIds)
			.getResultList();
	}

	private List<UUID> loadSessionServers(Session session) {
		if (session == null || session.workspace == null) {
			return List.of();
		}
		if (session.channel != null) {
			return entityManager.createQuery(
					"select c.mcpServer.id from ChannelMcpServer c where c.channel.id = "
						+ ":channelId and c.enabledByDefault = true",
					UUID.class
				)
				.setParameter("channelId", session.channel.id)
				.getResultList();
		}
		return entityManager.createQuery(
				"select s.id from McpServer s where s.workspace.id = :workspaceId and s.visibility = :visibility",
				UUID.class
			)
			.setParameter("workspaceId", session.workspace.id)
			.setParameter("visibility", be.celerex.polymr.model.McpServerVisibility.VISIBLE)
			.getResultList();
	}

	private WorkspaceScopeService.ScopePermissions loadSessionScopes(Session session) {
		if (session == null || session.workspace == null) {
			return new WorkspaceScopeService.ScopePermissions(List.of(), List.of());
		}
		if (session.channel != null) {
			ChannelScope scope = entityManager.createQuery("select c from ChannelScope c where c.channel.id = :channelId", ChannelScope.class)
				.setParameter("channelId", session.channel.id)
				.getResultStream()
				.findFirst()
				.orElse(null);
			List<String> allow = normalizeScopes(
				scope == null || scope.allowScopes == null
					? List.of()
					: objectMapper.convertValue(scope.allowScopes, List.class)
			);
			List<String> deny = normalizeScopes(
				scope == null || scope.denyScopes == null
					? List.of()
					: objectMapper.convertValue(scope.denyScopes, List.class)
			);
			return new WorkspaceScopeService.ScopePermissions(allow, deny);
		}
		return workspaceScopeService.load(session.workspace.id);
	}

	private Map<UUID, TagValue> loadTagsById(Iterable<UUID> tagIds) {
		List<UUID> ids = new ArrayList<>();
		tagIds.forEach(ids::add);
		if (ids.isEmpty()) {
			return Map.of();
		}
		List<TagValue> tags = entityManager.createQuery("select t from TagValue t join fetch t.category where t.id in :ids", TagValue.class)
			.setParameter("ids", ids)
			.getResultList();
		Map<UUID, TagValue> map = new HashMap<>();
		for (TagValue tag : tags) {
			map.put(tag.id, tag);
		}
		return map;
	}

	private List<String> normalizeScopes(List<String> scopes) {
		if (scopes == null || scopes.isEmpty()) {
			return List.of();
		}
		List<String> normalized = new ArrayList<>();
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (String scope : scopes) {
			if (scope == null) {
				continue;
			}
			String trimmed = scope.trim();
			if (trimmed.isEmpty() || !seen.add(trimmed)) {
				continue;
			}
			normalized.add(trimmed);
		}
		return normalized;
	}

	private ObjectNode readObject(JsonNode node) {
		return node instanceof ObjectNode objectNode ? objectNode : objectMapper.createObjectNode();
	}

	private void copyArray(ObjectNode target, String name, ObjectNode workflow, ObjectNode node) {
		ArrayNode array = target.putArray(name);
		for (String value : readStringArray(workflow.get(name))) {
			array.add(value);
		}
		for (String value : readStringArray(node.get(name))) {
			array.add(value);
		}
	}

	private List<String> readStringArray(JsonNode node) {
		if (node == null || node.isNull()) {
			return List.of();
		}
		List<String> list = new ArrayList<>();
		if (node.isArray()) {
			node.forEach(entry -> {
				if (entry.isTextual()) {
					list.add(entry.asText());
				}
			});
		}
		return list;
	}

	private List<String> resolveToolScopes(McpServerTool tool) {
		if (tool == null) {
			return List.of();
		}
		List<String> scopes = readStringArray(tool.customScopes);
		if (scopes.isEmpty()) {
			scopes = readStringArray(tool.scopes);
		}
		if (scopes.isEmpty()) {
			String toolName = tool.toolName;
			if (toolName != null && !toolName.isBlank()) {
				return List.of(toolName);
			}
		}
		return scopes;
	}

	private McpServer ensureWorkflowStateServer(Workspace workspace) {
		if (workspace == null) {
			return null;
		}
		List<McpServer> existing = entityManager.createQuery(
				"select s from McpServer s where s.workspace.id = :workspaceId and s.internal = true "
					+ "and s.protocol = :protocol and lower(s.virtualType) = :virtualType",
				McpServer.class
			)
			.setParameter("workspaceId", workspace.id)
			.setParameter("protocol", McpProtocol.VIRTUAL)
			.setParameter("virtualType", "polymr_workflow")
			.getResultList();
		if (!existing.isEmpty()) {
			McpServer server = existing.get(0);
			toolCatalogService.refreshTools(server, virtualMcpService.listTools(server));
			return server;
		}
		McpServer server = new McpServer();
		server.workspace = workspace;
		server.name = "Workflow State";
		server.description = "Internal workflow state tools";
		server.protocol = McpProtocol.VIRTUAL;
		server.virtualType = "polymr_workflow";
		server.visibility = McpServerVisibility.HIDDEN;
		server.internal = true;
		entityManager.persist(server);
		toolCatalogService.refreshTools(server, virtualMcpService.listTools(server));
		return server;
	}

	public McpServer ensureWorkerFeedbackServer(Workspace workspace) {
		if (workspace == null) {
			return null;
		}
		List<McpServer> existing = entityManager.createQuery(
				"select s from McpServer s where s.workspace.id = :workspaceId and s.internal = true "
					+ "and s.protocol = :protocol and lower(s.virtualType) = :virtualType",
				McpServer.class
			)
			.setParameter("workspaceId", workspace.id)
			.setParameter("protocol", McpProtocol.VIRTUAL)
			.setParameter("virtualType", "polymr_worker")
			.getResultList();
		if (!existing.isEmpty()) {
			McpServer server = existing.get(0);
			toolCatalogService.refreshTools(server, virtualMcpService.listTools(server));
			return server;
		}
		McpServer server = new McpServer();
		server.workspace = workspace;
		server.name = "Worker Feedback";
		server.description = "Internal worker feedback tools";
		server.protocol = McpProtocol.VIRTUAL;
		server.virtualType = "polymr_worker";
		server.visibility = McpServerVisibility.HIDDEN;
		server.internal = true;
		entityManager.persist(server);
		toolCatalogService.refreshTools(server, virtualMcpService.listTools(server));
		return server;
	}

	public McpServer ensureWorkflowStateServerForWorkspace(Workspace workspace) {
		return ensureWorkflowStateServer(workspace);
	}

	private void addServerId(ArrayNode array, UUID serverId) {
		if (array == null || serverId == null) {
			return;
		}
		String value = serverId.toString();
		for (JsonNode node : array) {
			if (node != null && value.equals(node.asText())) {
				return;
			}
		}
		array.add(value);
	}

	private List<UUID> readUuidArray(JsonNode node) {
		List<UUID> list = new ArrayList<>();
		if (node == null || node.isNull() || !node.isArray()) {
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
}
