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

import be.celerex.polymr.model.McpServer;
import be.celerex.polymr.model.McpServerTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jboss.logging.Logger;

@ApplicationScoped
public class McpToolCatalogService {
	private static final Logger LOGGER = Logger.getLogger(McpToolCatalogService.class);
	@Inject
	EntityManager entityManager;

	@Inject
	ObjectMapper objectMapper;

	@Transactional
	public void refreshTools(McpServer server, JsonNode toolsPayload) {
		if (server == null || toolsPayload == null) {
			return;
		}
		JsonNode toolsNode = toolsPayload.isArray() ? toolsPayload : toolsPayload.get("tools");
		if (toolsNode == null || !toolsNode.isArray()) {
			return;
		}
		String nextHash = hashNode(toolsNode);
		if (nextHash != null && nextHash.equals(server.toolsHash)) {
			return;
		}
		Map<String, McpServerTool> existing = loadExisting(server.id);
		Map<String, ToolEntry> incoming = new HashMap<>();
		toolsNode.forEach(
			node -> {
				ToolEntry entry = parseTool(node);
				if (entry != null && entry.name != null && !entry.name.isBlank()) {
					incoming.put(entry.name, entry);
				}
			}
		);

		for (ToolEntry entry : incoming.values()) {
			McpServerTool tool = existing.get(entry.name);
			if (tool == null) {
				tool = new McpServerTool();
				tool.mcpServer = server;
				tool.toolName = entry.name;
				tool.disabled = false;
				tool.deleted = false;
				entityManager.persist(tool);
			}
			tool.description = entry.description;
			tool.intentTemplate = entry.intentTemplate;
			tool.inputTemplate = entry.inputTemplate;
			tool.outputTemplate = entry.outputTemplate;
			tool.scopes = entry.scopes;
			tool.dynamicScopes = entry.dynamicScopes;
			tool.inputSchema = entry.inputSchema;
			tool.outputSchema = entry.outputSchema;
			tool.meta = entry.meta;
			tool.previewSupported = entry.previewSupported;
			tool.deleted = false;
		}

		for (McpServerTool tool : existing.values()) {
			if (!incoming.containsKey(tool.toolName)) {
				tool.deleted = true;
			}
		}

		server.toolsHash = nextHash;
		LOGGER.debugf("Updated MCP tool catalog for server %s", server.id);
	}

	@Transactional
	public void updateInstructions(McpServer server, JsonNode initializePayload) {
		if (server == null || server.customInstructions) {
			return;
		}
		String instructions = null;
		if (initializePayload != null && initializePayload.has("instructions")) {
			JsonNode node = initializePayload.get("instructions");
			if (node != null && node.isTextual()) {
				String value = node.asText();
				instructions = value == null || value.isBlank() ? null : value;
			}
		}
		server.instructions = instructions;
	}

	private Map<String, McpServerTool> loadExisting(UUID serverId) {
		List<McpServerTool> tools = entityManager.createQuery("select t from McpServerTool t where t.mcpServer.id = :serverId", McpServerTool.class)
			.setParameter("serverId", serverId)
			.getResultList();
		Map<String, McpServerTool> map = new HashMap<>();
		for (McpServerTool tool : tools) {
			map.put(tool.toolName, tool);
		}
		return map;
	}

	private ToolEntry parseTool(JsonNode node) {
		if (node == null || !node.isObject()) {
			return null;
		}
		String name = node.path("name").asText(null);
		String description = node.path("description").asText(null);
		JsonNode annotations = node.get("annotations");
		JsonNode inputSchema = node.get("inputSchema");
		JsonNode outputSchema = node.get("outputSchema");
		JsonNode meta = node.get("_meta");
		JsonNode scopes = null;
		String intentTemplate = null;
		String inputTemplate = null;
		String outputTemplate = null;
		boolean preview = false;
		boolean dynamicScopes = false;
		List<String> dynamicScopeParents = List.of();
		if (annotations != null && annotations.isObject()) {
			scopes = annotations.get("scopes");
			JsonNode dynamicNode = annotations.get("dynamic_scopes");
			if (dynamicNode != null) {
				if (dynamicNode.isBoolean()) {
					dynamicScopes = dynamicNode.asBoolean(false);
				}
				else if (dynamicNode.isArray()) {
					dynamicScopes = dynamicNode.size() > 0;
					dynamicScopeParents = extractDynamicScopeParents(dynamicNode);
				}
			}
			JsonNode previewNode = annotations.get("preview");
			if (previewNode != null && previewNode.isBoolean()) {
				preview = previewNode.asBoolean(false);
			}
			JsonNode intentNode = annotations.get("intentTemplate");
			if (intentNode != null && intentNode.isTextual()) {
				intentTemplate = intentNode.asText();
			}
			JsonNode inputNode = annotations.get("inputTemplate");
			if (inputNode != null && inputNode.isTextual()) {
				inputTemplate = inputNode.asText();
			}
			JsonNode outputNode = annotations.get("outputTemplate");
			if (outputNode != null && outputNode.isTextual()) {
				outputTemplate = outputNode.asText();
			}
		}
		JsonNode mergedScopes = mergeScopes(scopes, dynamicScopeParents);
		return new ToolEntry(
			name,
			description,
			intentTemplate,
			inputTemplate,
			outputTemplate,
			mergedScopes,
			dynamicScopes,
			inputSchema,
			outputSchema,
			meta,
			preview
		);
	}

	private List<String> extractDynamicScopeParents(JsonNode node) {
		if (node == null || !node.isArray()) {
			return List.of();
		}
		List<String> parents = new java.util.ArrayList<>();
		node.forEach(
			entry -> {
				if (!entry.isTextual()) {
					return;
				}
				String value = entry.asText();
				if (value == null) {
					return;
				}
				String trimmed = value.trim();
				if (trimmed.isBlank() || "*".equals(trimmed)) {
					return;
				}
				int wildcard = trimmed.indexOf('*');
				if (wildcard < 0) {
					return;
				}
				String prefix = trimmed.substring(0, wildcard);
				while (prefix.endsWith(":")) {
					prefix = prefix.substring(0, prefix.length() - 1);
				}
				if (!prefix.isBlank() && !parents.contains(prefix)) {
					parents.add(prefix);
				}
			}
		);
		return parents;
	}

	private JsonNode mergeScopes(JsonNode scopes, List<String> dynamicParents) {
		if ((dynamicParents == null || dynamicParents.isEmpty()) && (scopes == null || scopes.isNull())) {
			return scopes;
		}
		var array = objectMapper.createArrayNode();
		java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>();
		if (scopes != null && scopes.isArray()) {
			scopes.forEach(
				entry -> {
					if (entry.isTextual()) {
						String value = entry.asText();
						if (value != null && !value.isBlank()) {
							merged.add(value);
						}
					}
				}
			);
		}
		if (dynamicParents != null) {
			for (String parent : dynamicParents) {
				if (parent != null && !parent.isBlank()) {
					merged.add(parent);
				}
			}
		}
		merged.forEach(array::add);
		return array;
	}

	private String hashNode(JsonNode node) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] bytes = objectMapper.writeValueAsBytes(node);
			byte[] hash = digest.digest(bytes);
			StringBuilder builder = new StringBuilder();
			for (byte b : hash) {
				builder.append(String.format("%02x", b));
			}
			return builder.toString();
		}
		catch (Exception ex) {
			return null;
		}
	}

	private record ToolEntry(
			String name,
			String description,
			String intentTemplate,
			String inputTemplate,
			String outputTemplate,
			JsonNode scopes,
			boolean dynamicScopes,
			JsonNode inputSchema,
			JsonNode outputSchema,
			JsonNode meta,
			boolean previewSupported) {}
}
