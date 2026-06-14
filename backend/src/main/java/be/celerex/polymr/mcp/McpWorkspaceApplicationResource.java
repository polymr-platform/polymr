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

import be.celerex.polymr.mcp.dto.McpWorkspaceApplicationResponse;
import be.celerex.polymr.model.McpServerApplication;
import be.celerex.polymr.model.TenantMembership;
import be.celerex.polymr.model.Workspace;
import be.celerex.polymr.tenant.TenantAccessService;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Path("/api/tenants/{tenantId}/workspaces/{workspaceId}/mcp-applications")
@Produces(MediaType.APPLICATION_JSON)
public class McpWorkspaceApplicationResource {
	@Inject
	EntityManager entityManager;

	@Inject
	TenantAccessService tenantAccessService;

	@Context
	SecurityContext securityContext;

	@GET
	public List<McpWorkspaceApplicationResponse> list(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId) {
		requireMembership(tenantId);
		Workspace workspace = requireWorkspace(tenantId, workspaceId);
		List<McpServerApplication> apps = entityManager.createQuery(
				"select a from McpServerApplication a join fetch a.mcpServer s "
					+ "where s.workspace.id = :workspaceId and a.disabled = false",
				McpServerApplication.class
			)
			.setParameter("workspaceId", workspace.id)
			.getResultList();
		return apps.stream()
			.map(this::toResponse)
			.collect(Collectors.toList());
	}

	private McpWorkspaceApplicationResponse toResponse(McpServerApplication app) {
		return new McpWorkspaceApplicationResponse(
			app.id,
			app.mcpServer != null ? app.mcpServer.id : null,
			app.mcpServer != null ? app.mcpServer.name : null,
			app.appUri,
			app.appName,
			app.displayName,
			app.iconSvg
		);
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
