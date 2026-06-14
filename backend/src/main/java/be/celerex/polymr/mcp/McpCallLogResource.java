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

import be.celerex.polymr.mcp.dto.McpCallLogResponse;
import be.celerex.polymr.model.McpCallLog;
import be.celerex.polymr.model.TenantMembership;
import be.celerex.polymr.model.Workspace;
import be.celerex.polymr.tenant.TenantAccessService;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Path("/api/tenants/{tenantId}/workspaces/{workspaceId}/mcp-call-logs")
@Produces(MediaType.APPLICATION_JSON)
public class McpCallLogResource {
	@Inject
	EntityManager entityManager;

	@Inject
	TenantAccessService tenantAccessService;

	@Context
	SecurityContext securityContext;

	@GET
	public List<McpCallLogResponse> list(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@QueryParam("server_id") UUID serverId,
			@QueryParam("session_id") UUID sessionId,
			@QueryParam("script_call_id") UUID scriptCallId,
			@QueryParam("limit") Integer limit,
			@QueryParam("before") String before) {
		requireMembership(tenantId);
		Workspace workspace = requireWorkspace(tenantId, workspaceId);
		int max = limit == null || limit <= 0 ? 200 : Math.min(limit, 1000);
		Instant beforeInstant = null;
		if (before != null && !before.isBlank()) {
			try {
				beforeInstant = Instant.parse(before);
			}
			catch (Exception ignored) {
				beforeInstant = null;
			}
		}
		StringBuilder query = new StringBuilder("select l from McpCallLog l where l.workspace.id = :workspaceId");
		if (serverId != null) {
			query.append(" and (l.mcpServer.id = :serverId)");
		}
		if (sessionId != null) {
			query.append(" and l.session.id = :sessionId");
		}
		if (scriptCallId != null) {
			query.append(" and l.scriptCallId = :scriptCallId");
		}
		if (beforeInstant != null) {
			query.append(" and l.createdAt < :before");
		}
		query.append(" order by l.createdAt desc");
		var typed = entityManager.createQuery(query.toString(), McpCallLog.class)
			.setParameter("workspaceId", workspace.id)
			.setMaxResults(max);
		if (serverId != null) {
			typed.setParameter("serverId", serverId);
		}
		if (sessionId != null) {
			typed.setParameter("sessionId", sessionId);
		}
		if (scriptCallId != null) {
			typed.setParameter("scriptCallId", scriptCallId);
		}
		if (beforeInstant != null) {
			typed.setParameter("before", beforeInstant);
		}
		return typed.getResultList().stream().map(this::toResponse).collect(Collectors.toList());
	}

	private McpCallLogResponse toResponse(McpCallLog log) {
		return new McpCallLogResponse(
			log.id,
			log.tenant == null ? null : log.tenant.id,
			log.workspace == null ? null : log.workspace.id,
			log.mcpServer == null ? null : log.mcpServer.id,
			log.mcpServerName,
			log.mcpServerProtocol,
			log.mcpServerOverrideName,
			log.mcpServerOverrideTagName,
			log.session == null ? null : log.session.id,
			log.user == null ? null : log.user.id,
			log.connectionId,
			log.requestId,
			log.method,
			log.protocol,
			log.input,
			log.output,
			log.status,
			log.scriptCallId,
			log.script == null ? null : log.script.id,
			log.createdAt
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
