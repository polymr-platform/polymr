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

import be.celerex.polymr.model.McpProtocol;
import be.celerex.polymr.model.McpServer;
import be.celerex.polymr.model.Workspace;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.List;

@ApplicationScoped
public class ToolHookMcpService {
	private static final String VIRTUAL_TYPE_TOOL_HOOK = "polymr_tool_hook";
	@Inject
	EntityManager entityManager;

	@Inject
	VirtualMcpService virtualMcpService;

	@Inject
	McpToolCatalogService toolCatalogService;

	public McpServer ensureToolHookServer(Workspace workspace) {
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
			.setParameter("virtualType", VIRTUAL_TYPE_TOOL_HOOK)
			.getResultList();
		if (!existing.isEmpty()) {
			McpServer server = existing.get(0);
			toolCatalogService.refreshTools(server, virtualMcpService.listTools(server));
			return server;
		}
		McpServer server = new McpServer();
		server.workspace = workspace;
		server.name = "Tool Hook";
		server.description = "Internal tool hook inspection tools";
		server.protocol = McpProtocol.VIRTUAL;
		server.virtualType = VIRTUAL_TYPE_TOOL_HOOK;
		server.visibility = be.celerex.polymr.model.McpServerVisibility.HIDDEN;
		server.internal = true;
		entityManager.persist(server);
		toolCatalogService.refreshTools(server, virtualMcpService.listTools(server));
		return server;
	}
}
