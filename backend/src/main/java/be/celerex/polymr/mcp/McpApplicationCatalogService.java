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
import be.celerex.polymr.model.McpServerApplication;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jboss.logging.Logger;

@ApplicationScoped
public class McpApplicationCatalogService {
	private static final Logger LOGGER = Logger.getLogger(McpApplicationCatalogService.class);
	@Inject
	EntityManager entityManager;

	@Transactional
	public void refreshApplications(McpServer server, JsonNode resourcesPayload) {
		if (server == null || resourcesPayload == null) {
			return;
		}
		JsonNode resourcesNode = resourcesPayload;
		if (resourcesNode != null && resourcesNode.isObject()) {
			if (resourcesNode.has("resources")) {
				resourcesNode = resourcesNode.get("resources");
			}
			else if (resourcesNode.has("result")) {
				JsonNode resultNode = resourcesNode.get("result");
				if (resultNode != null && resultNode.isObject() && resultNode.has("resources")) {
					resourcesNode = resultNode.get("resources");
				}
			}
		}
		if (resourcesNode == null || !resourcesNode.isArray()) {
			return;
		}
		List<ApplicationEntry> incomingList = new ArrayList<>();
		resourcesNode.forEach(
			node -> {
				if (!isApplicationResource(node)) {
					return;
				}
				String uri = textValue(node.get("uri"));
				if (uri == null || uri.isBlank()) {
					return;
				}
				String name = textValue(node.get("name"));
				incomingList.add(new ApplicationEntry(uri, name));
			}
		);
		incomingList.sort(Comparator.comparing(ApplicationEntry::uri));
		Map<String, McpServerApplication> existing = loadExisting(server.id);
		Set<String> seen = new HashSet<>();
		for (ApplicationEntry entry : incomingList) {
			seen.add(entry.uri());
			McpServerApplication app = existing.get(entry.uri());
			if (app == null) {
				app = new McpServerApplication();
				app.mcpServer = server;
				app.appUri = entry.uri();
				app.disabled = false;
				entityManager.persist(app);
			}
			app.appName = entry.name();
		}

		for (McpServerApplication app : existing.values()) {
			if (!seen.contains(app.appUri)) {
				entityManager.remove(app);
			}
		}
		LOGGER.debugf("Updated MCP applications for server %s", server.id);
	}

	private Map<String, McpServerApplication> loadExisting(UUID serverId) {
		List<McpServerApplication> applications = entityManager.createQuery(
				"select a from McpServerApplication a where a.mcpServer.id = :serverId",
				McpServerApplication.class
			)
			.setParameter("serverId", serverId)
			.getResultList();
		Map<String, McpServerApplication> map = new HashMap<>();
		for (McpServerApplication app : applications) {
			map.put(app.appUri, app);
		}
		return map;
	}

	private boolean isApplicationResource(JsonNode entry) {
		if (entry == null || entry.isNull()) {
			return false;
		}
		String mimeType = textValue(entry.get("mimeType"));
		if (mimeType == null || !mimeType.toLowerCase().startsWith("text/html")) {
			return false;
		}
		JsonNode annotations = entry.get("annotations");
		if (annotations == null || !annotations.isObject()) {
			return false;
		}
		String type = textValue(annotations.get("type"));
		return "application".equalsIgnoreCase(type);
	}

	private String textValue(JsonNode node) {
		if (node == null || node.isNull()) {
			return null;
		}
		if (!node.isTextual()) {
			return null;
		}
		return node.asText();
	}

	private record ApplicationEntry(String uri, String name) {}
}
