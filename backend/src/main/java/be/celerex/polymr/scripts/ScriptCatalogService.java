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

import be.celerex.polymr.mcp.McpToolSpecificationService;
import be.celerex.polymr.mcp.WorkflowMcpSnapshotService;
import be.celerex.polymr.model.McpProtocol;
import be.celerex.polymr.model.McpServer;
import be.celerex.polymr.model.McpServerPolicy;
import be.celerex.polymr.model.Script;
import be.celerex.polymr.model.ScriptType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class ScriptCatalogService {
	private static final String CATALOG_RESOURCE = "/script-catalog.json";
	@Inject
	ObjectMapper objectMapper;

	@Inject
	EntityManager entityManager;

	@Inject
	WorkflowMcpSnapshotService snapshotService;

	@Inject
	McpToolSpecificationService toolSpecificationService;

	public interface CatalogFilter {
        default boolean includeServer(be.celerex.polymr.model.McpServer server) {
            return server != null && !server.internal;
        }
    }

	private static final CatalogFilter DEFAULT_FILTER = new CatalogFilter() {};

	public ObjectNode buildCatalog(UUID workspaceId) {
		return buildCatalog(workspaceId, DEFAULT_FILTER);
	}

	public ObjectNode buildCatalog(UUID workspaceId, CatalogFilter filter, boolean utilityOnlyScripts) {
		ObjectNode base = loadBaseCatalog();
		ObjectNode dynamic = buildDynamicCatalog(workspaceId, utilityOnlyScripts);
		base.set("dynamic", dynamic);
		ObjectNode tools = buildToolsCatalog(workspaceId, filter == null ? DEFAULT_FILTER : filter);
		base.set("tools", tools);
		return base;
	}

	public ObjectNode buildCatalogForScript(Script script) {
		if (script == null || script.workspace == null) {
			return buildCatalog(null);
		}
		if (script.type != ScriptType.WORKFLOW) {
			return buildCatalog(script.workspace.id, new CatalogFilter() {}, true);
		}
		return buildCatalogForWorkflowScript(script);
	}

	public ObjectNode buildCatalogForWorkflowScript(Script script) {
		if (script == null || script.workspace == null) {
			return buildCatalog(null);
		}
		snapshotService.ensureWorkflowStateServerForWorkspace(script.workspace);
		return buildCatalog(
			script.workspace.id,
			new CatalogFilter() {
				@Override
				public boolean includeServer(McpServer server) {
					if (server == null) {
						return false;
					}
					if (!server.internal) {
						return true;
					}
					return server.protocol == McpProtocol.VIRTUAL
						&& "polymr_workflow".equalsIgnoreCase(server.virtualType);
				}
			},
			true
		);
	}

	public ObjectNode buildCatalog(UUID workspaceId, CatalogFilter filter) {
		return buildCatalog(workspaceId, filter, false);
	}

	private ObjectNode loadBaseCatalog() {
		try (InputStream stream = getClass().getResourceAsStream(CATALOG_RESOURCE)) {
			if (stream == null) {
				return objectMapper.createObjectNode();
			}
			JsonNode node = objectMapper.readTree(stream);
			if (node instanceof ObjectNode objectNode) {
				return objectNode;
			}
		}
		catch (Exception ignored) {}
		return objectMapper.createObjectNode();
	}

	private ObjectNode buildDynamicCatalog(UUID workspaceId, boolean utilityOnlyScripts) {
		ObjectNode dynamic = objectMapper.createObjectNode();
		ArrayNode scriptsNode = dynamic.putArray("scripts");
		if (workspaceId == null) {
			return dynamic;
		}
		String queryText = utilityOnlyScripts
			? "select s from Script s where s.workspace.id = :workspaceId and s.disabled = "
				+ "false and s.type = :scriptType order by lower(s.name)"
			: "select s from Script s where s.workspace.id = :workspaceId and s.disabled = "
				+ "false and s.type <> :workflowType order by lower(s.name)";
		List<Script> scripts = entityManager.createQuery(queryText, Script.class)
			.setParameter("workspaceId", workspaceId)
			.setParameter(
				utilityOnlyScripts ? "scriptType" : "workflowType",
				utilityOnlyScripts ? ScriptType.UTILITY : ScriptType.WORKFLOW
			)
			.getResultList();
		for (Script script : scripts) {
			ObjectNode entry = objectMapper.createObjectNode();
			entry.put("id", script.id.toString());
			entry.put("name", script.name);
			entry.put("description", script.description == null ? "" : script.description);
			entry.put("namespace", script.namespace == null ? "" : script.namespace);
			entry.put("slug", script.slug);
			entry.put("type", script.type == null ? ScriptType.STANDALONE.name() : script.type.name());
			if (script.activeVersion != null && script.activeVersion.inputSchema != null) {
				entry.set("input_schema", script.activeVersion.inputSchema);
			}
			if (script.activeVersion != null && script.activeVersion.outputSchema != null) {
				entry.set("output_schema", script.activeVersion.outputSchema);
			}
			scriptsNode.add(entry);
		}
		return dynamic;
	}

	private ObjectNode buildToolsCatalog(UUID workspaceId, CatalogFilter filter) {
		ObjectNode tools = objectMapper.createObjectNode();
		ArrayNode serversNode = tools.putArray("servers");
		if (workspaceId == null) {
			return tools;
		}
		Map<UUID, List<String>> tagsByServer = loadPolicyTags(workspaceId);
		List<be.celerex.polymr.model.McpServer> servers = entityManager.createQuery(
				"select s from McpServer s where s.workspace.id = :workspaceId",
				be.celerex.polymr.model.McpServer.class
			)
			.setParameter("workspaceId", workspaceId)
			.getResultList()
			.stream()
			.filter(filter::includeServer)
			.toList();
		for (be.celerex.polymr.model.McpServer server : servers) {
			ObjectNode serverNode = objectMapper.createObjectNode();
			serverNode.put("name", server.name);
			serverNode.put("description", server.description == null ? "" : server.description);
			List<String> supportedTags = tagsByServer.get(server.id);
			if (supportedTags != null && !supportedTags.isEmpty()) {
				ArrayNode tagNode = serverNode.putArray("supported_tags");
				supportedTags.forEach(tagNode::add);
			}
			ArrayNode toolsNode = serverNode.putArray("tools");
			List<be.celerex.polymr.model.McpServerTool> toolList = entityManager.createQuery(
					"select t from McpServerTool t where t.mcpServer.id = :serverId and t.deleted = false",
					be.celerex.polymr.model.McpServerTool.class
				)
				.setParameter("serverId", server.id)
				.getResultList();
			for (be.celerex.polymr.model.McpServerTool tool : toolList) {
				ObjectNode toolNode = objectMapper.createObjectNode();
				toolNode.put("name", toolSpecificationService.toolName(tool));
				toolNode.put("description", tool.description == null ? "" : tool.description);
				toolsNode.add(toolNode);
			}
			serversNode.add(serverNode);
		}
		return tools;
	}

	private Map<UUID, List<String>> loadPolicyTags(UUID workspaceId) {
		List<McpServerPolicy> policies = entityManager.createQuery(
				"select p from McpServerPolicy p join fetch p.tag where p.mcpServer.workspace.id = :workspaceId",
				McpServerPolicy.class
			)
			.setParameter("workspaceId", workspaceId)
			.getResultList();
		Map<UUID, List<String>> byServer = new HashMap<>();
		for (McpServerPolicy policy : policies) {
			if (policy.mcpServer == null || policy.tag == null) {
				continue;
			}
			String tagValue = policy.tag.category.slug + ":" + policy.tag.slug;
			byServer.computeIfAbsent(policy.mcpServer.id, key -> new ArrayList<>())
				.add(tagValue);
		}
		return byServer.entrySet()
			.stream()
			.collect(
				Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().stream().distinct().sorted().toList())
			);
	}
}
