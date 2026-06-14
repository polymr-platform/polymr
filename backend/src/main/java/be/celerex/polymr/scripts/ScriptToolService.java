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

package be.celerex.polymr.scripts;

import be.celerex.polymr.mcp.ToolHookContextHolder;
import be.celerex.polymr.mcp.WorkspaceMcpRegistry;
import be.celerex.polymr.model.McpServerTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class ScriptToolService {
	@Inject
	EntityManager entityManager;

	@Inject
	WorkspaceMcpRegistry mcpRegistry;

	@Inject
	ObjectMapper objectMapper;

	public JsonNode callTool(UUID workspaceId, UUID userId, String serviceName, JsonNode arguments, JsonNode options) {
		if (workspaceId == null) {
			throw new IllegalArgumentException("Workspace is required");
		}
		if (serviceName == null || serviceName.isBlank()) {
			throw new IllegalArgumentException("Service is required");
		}
		String name = serviceName.trim();
		List<McpServerTool> tools = findWorkspaceTool(workspaceId, name);
		if (tools.isEmpty() && isWorkflowStateTool(name)) {
			tools = findWorkflowStateTool(workspaceId, name);
		}
		if (tools.isEmpty() && ToolHookContextHolder.isActive() && isToolHookTool(name)) {
			tools = findToolHookTool(workspaceId, name);
		}
		if (tools.isEmpty()) {
			throw new IllegalArgumentException("Tool not found");
		}
		if (tools.size() > 1) {
			throw new IllegalArgumentException("Multiple tools share that alias");
		}
		McpServerTool tool = tools.get(0);
		if (tool.mcpServer == null) {
			throw new IllegalArgumentException("Tool not available");
		}
		JsonNode args = arguments == null ? objectMapper.createObjectNode() : arguments;
		ObjectNode meta = objectMapper.createObjectNode();
		ArrayNode tags = readTags(options);
		if (tags != null && tags.size() > 0) {
			meta.set("tags", tags);
		}
		ArrayNode allowedScopes = readAllowedScopes(objectMapper, tool);
		if (allowedScopes != null && allowedScopes.size() > 0) {
			meta.set("allowed_scopes", allowedScopes);
		}
		return mcpRegistry.call(workspaceId, tool.mcpServer.id, tool.toolName, args, meta, null, userId);
	}

	private List<McpServerTool> findWorkspaceTool(UUID workspaceId, String name) {
		return entityManager.createQuery(
				"select t from McpServerTool t join fetch t.mcpServer "
					+ "where t.mcpServer.workspace.id = :workspaceId and t.mcpServer.internal = false "
					+ "and t.deleted = false and t.disabled = false "
					+ "and lower(coalesce(t.mcpServer.toolNamePrefix, '') || coalesce(t.toolAlias, t.toolName)) = "
					+ "lower(:name)",
				McpServerTool.class
			)
			.setParameter("workspaceId", workspaceId)
			.setParameter("name", name)
			.getResultList();
	}

	private List<McpServerTool> findWorkflowStateTool(UUID workspaceId, String name) {
		return entityManager.createQuery(
				"select t from McpServerTool t join fetch t.mcpServer "
					+ "where t.mcpServer.workspace.id = :workspaceId and t.mcpServer.internal = true "
					+ "and lower(t.mcpServer.virtualType) = :virtualType "
					+ "and t.deleted = false and t.disabled = false "
					+ "and lower(coalesce(t.mcpServer.toolNamePrefix, '') || coalesce(t.toolAlias, t.toolName)) = "
					+ "lower(:name)",
				McpServerTool.class
			)
			.setParameter("workspaceId", workspaceId)
			.setParameter("virtualType", "polymr_workflow")
			.setParameter("name", name)
			.getResultList();
	}

	private List<McpServerTool> findToolHookTool(UUID workspaceId, String name) {
		return entityManager.createQuery(
				"select t from McpServerTool t join fetch t.mcpServer "
					+ "where t.mcpServer.workspace.id = :workspaceId and t.mcpServer.internal = true "
					+ "and lower(t.mcpServer.virtualType) = :virtualType "
					+ "and t.deleted = false and t.disabled = false "
					+ "and lower(coalesce(t.mcpServer.toolNamePrefix, '') || coalesce(t.toolAlias, t.toolName)) = "
					+ "lower(:name)",
				McpServerTool.class
			)
			.setParameter("workspaceId", workspaceId)
			.setParameter("virtualType", "polymr_tool_hook")
			.setParameter("name", name)
			.getResultList();
	}

	private boolean isWorkflowStateTool(String name) {
		if (name == null) {
			return false;
		}
		return name.equalsIgnoreCase("workflow_state_get")
			|| name.equalsIgnoreCase("workflow_state_set")
			|| name.equalsIgnoreCase("workflow_state_patch")
			|| name.equalsIgnoreCase("workflow_state_schema");
	}

	private boolean isToolHookTool(String name) {
		if (name == null) {
			return false;
		}
		return name.equalsIgnoreCase("tool_hook_get_name")
			|| name.equalsIgnoreCase("tool_hook_get_input")
			|| name.equalsIgnoreCase("tool_hook_set_input")
			|| name.equalsIgnoreCase("tool_hook_patch_input")
			|| name.equalsIgnoreCase("tool_hook_get_output")
			|| name.equalsIgnoreCase("tool_hook_set_output")
			|| name.equalsIgnoreCase("tool_hook_patch_output")
			|| name.equalsIgnoreCase("tool_hook_cancel");
	}

	public static ArrayNode readAllowedScopes(ObjectMapper objectMapper, McpServerTool tool) {
		if (tool == null || !tool.dynamicScopes || tool.scopes == null || !tool.scopes.isArray()) {
			return null;
		}
		LinkedHashSet<String> scopes = new LinkedHashSet<>();
		tool.scopes.forEach(
			(node) -> {
				if (node == null || !node.isTextual()) {
					return;
				}
				String value = node.asText();
				if (value == null) {
					return;
				}
				String trimmed = value.trim();
				if (trimmed.isBlank()) {
					return;
				}
				int wildcard = trimmed.indexOf('*');
				if (wildcard >= 0) {
					trimmed = trimmed.substring(0, wildcard);
					while (trimmed.endsWith(":")) {
						trimmed = trimmed.substring(0, trimmed.length() - 1);
					}
				}
				if (!trimmed.isBlank()) {
					scopes.add(trimmed);
				}
			}
		);
		if (scopes.isEmpty()) {
			return null;
		}
		ArrayNode allowedScopes = objectMapper.createArrayNode();
		scopes.forEach(allowedScopes::add);
		return allowedScopes;
	}

	private ArrayNode readTags(JsonNode options) {
		if (options == null) {
			return null;
		}
		JsonNode tags = options.get("tags");
		if (tags == null) {
			return null;
		}
		ArrayNode result = objectMapper.createArrayNode();
		if (tags.isTextual()) {
			String value = tags.asText();
			if (value != null && !value.isBlank()) {
				result.add(value.trim());
			}
			return result;
		}
		if (tags.isArray()) {
			tags.forEach(
				(node) -> {
					if (node != null && node.isTextual()) {
						String value = node.asText();
						if (value != null && !value.isBlank()) {
							result.add(value.trim());
						}
					}
				}
			);
			return result;
		}
		return null;
	}
}
