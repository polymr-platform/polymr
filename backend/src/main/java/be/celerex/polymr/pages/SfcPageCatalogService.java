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

package be.celerex.polymr.pages;

import be.celerex.polymr.model.SfcPage;
import be.celerex.polymr.model.ScriptType;
import be.celerex.polymr.model.SfcPageType;
import be.celerex.polymr.model.McpServerPolicy;
import be.celerex.polymr.mcp.McpToolSpecificationService;
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
import java.util.Comparator;

@ApplicationScoped
public class SfcPageCatalogService {
	private static final String CATALOG_RESOURCE = "/design-catalog.json";
	@Inject
	ObjectMapper objectMapper;

	@Inject
	EntityManager entityManager;

	@Inject
	McpToolSpecificationService toolSpecificationService;

	public ObjectNode buildCatalog(UUID workspaceId) {
		ObjectNode base = loadBaseCatalog();
		ObjectNode dynamic = buildDynamicCatalog(workspaceId);
		base.set("dynamic", dynamic);
		ObjectNode tools = buildToolsCatalog(workspaceId);
		base.set("tools", tools);
		return base;
	}

	public String buildComponentUsageGuide(UUID workspaceId) {
		List<ObjectNode> components = new ArrayList<>();
		ObjectNode catalog = buildCatalog(workspaceId);
		JsonNode dynamicComponents = catalog.path("dynamic").path("components");
		if (dynamicComponents.isArray()) {
			for (JsonNode entry : dynamicComponents) {
				if (entry instanceof ObjectNode objectNode) {
					components.add(objectNode);
				}
			}
		}
		JsonNode builtInComponents = catalog.path("components");
		if (builtInComponents.isArray()) {
			for (JsonNode entry : builtInComponents) {
				if (!(entry instanceof ObjectNode objectNode)) {
					continue;
				}
				ObjectNode normalized = objectMapper.createObjectNode();
				normalized.put("name", objectNode.path("name").asText(""));
				normalized.put("description", objectNode.path("description").asText(""));
				String usage = objectNode.path("usage").asText("");
				String importStatement = objectNode.path("import").asText("");
				if (!importStatement.isBlank() && !usage.isBlank()) {
					normalized.put("usage_guide", importStatement + "\n\n" + usage);
				}
				else if (!importStatement.isBlank()) {
					normalized.put("usage_guide", importStatement);
				}
				else {
					normalized.put("usage_guide", usage);
				}
				components.add(normalized);
			}
		}
		components.sort(Comparator.comparing(entry -> entry.path("name").asText(""), String.CASE_INSENSITIVE_ORDER));
		if (components.isEmpty()) {
			return "No reusable components are currently available.";
		}
		StringBuilder builder = new StringBuilder();
		for (ObjectNode component : components) {
			String name = component.path("name").asText("").trim();
			if (name.isBlank()) {
				continue;
			}
			if (!builder.isEmpty()) {
				builder.append("\n\n");
			}
			builder.append("#### `").append(name).append("`\n");
			String description = component.path("description").asText("").trim();
			if (!description.isBlank()) {
				builder.append(description).append("\n\n");
			}
			String usageGuide = component.path("usage_guide").asText("").trim();
			if (!usageGuide.isBlank()) {
				builder.append(usageGuide);
			}
			else {
				builder.append("No usage guide provided.");
			}
		}
		if (builder.isEmpty()) {
			return "No reusable components are currently available.";
		}
		return builder.toString();
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

	private ObjectNode buildDynamicCatalog(UUID workspaceId) {
		ObjectNode dynamic = objectMapper.createObjectNode();
		ArrayNode pagesNode = dynamic.putArray("pages");
		ArrayNode componentsNode = dynamic.putArray("components");
		ArrayNode utilityScriptsNode = dynamic.putArray("utility_scripts");
		if (workspaceId == null) {
			return dynamic;
		}
		List<SfcPage> pages = entityManager.createQuery(
				"select p from SfcPage p where p.workspace.id = :workspaceId and p.disabled "
					+ "= false order by lower(p.name)",
				SfcPage.class
			)
			.setParameter("workspaceId", workspaceId)
			.getResultList();
		for (SfcPage page : pages) {
			ObjectNode entry = objectMapper.createObjectNode();
			entry.put("id", page.id.toString());
			entry.put("name", page.name);
			entry.put("label", page.label == null ? "" : page.label);
			entry.put("description", page.description == null ? "" : page.description);
			entry.put("namespace", page.namespace == null ? "" : page.namespace);
			entry.put("slug", page.slug);
			entry.put("type", page.type == null ? null : page.type.name());
			entry.put("menu_visible", page.menuVisible);
			entry.put("route_path", buildRoutePath(page));
			entry.put("usage_guide", page.usageGuide == null ? "" : page.usageGuide);
			if (page.importAllowlist != null) {
				entry.set("import_allowlist", page.importAllowlist);
			}
			if (page.queryParams != null) {
				entry.set("query_params", page.queryParams);
			}
			if (page.inputParams != null) {
				entry.set("input_params", page.inputParams);
			}
			if (page.type == SfcPageType.COMPONENT) {
				componentsNode.add(entry);
			}
			else {
				pagesNode.add(entry);
			}
		}
		List<be.celerex.polymr.model.Script> scripts = entityManager.createQuery(
				"select s from Script s where s.workspace.id = :workspaceId and s.disabled "
					+ "= false and s.type = :type order by lower(s.name)",
				be.celerex.polymr.model.Script.class
			)
			.setParameter("workspaceId", workspaceId)
			.setParameter("type", be.celerex.polymr.model.ScriptType.UTILITY)
			.getResultList();
		for (be.celerex.polymr.model.Script script : scripts) {
			ObjectNode entry = objectMapper.createObjectNode();
			entry.put("name", script.name);
			entry.put("description", script.description == null ? "" : script.description);
			utilityScriptsNode.add(entry);
		}
		return dynamic;
	}

	private ObjectNode buildToolsCatalog(UUID workspaceId) {
		ObjectNode tools = objectMapper.createObjectNode();
		ArrayNode serversNode = tools.putArray("servers");
		if (workspaceId == null) {
			return tools;
		}
		Map<UUID, List<String>> tagsByServer = loadPolicyTags(workspaceId);
		List<be.celerex.polymr.model.McpServer> servers = entityManager.createQuery(
				"select s from McpServer s where s.workspace.id = :workspaceId and s.internal = false",
				be.celerex.polymr.model.McpServer.class
			)
			.setParameter("workspaceId", workspaceId)
			.getResultList();
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

	private String buildRoutePath(SfcPage page) {
		if (page == null) {
			return null;
		}
		String base = page.slug == null ? "" : page.slug.trim();
		String suffix = page.routeSuffix == null ? "" : page.routeSuffix.trim();
		if (!suffix.isEmpty() && !suffix.startsWith("/")) {
			suffix = "/" + suffix;
		}
		return "pages/" + base + suffix;
	}
}
