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

import be.celerex.polymr.model.ScriptCallLog;
import be.celerex.polymr.scripts.dto.ScriptCallLogResponse;
import be.celerex.polymr.tenant.TenantAccessService;
import be.celerex.polymr.model.TenantMembership;
import be.celerex.polymr.model.TenantRole;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Path("/api/tenants/{tenantId}/workspaces/{workspaceId}/script-logs")
public class ScriptCallLogResource {
	@Inject
	EntityManager entityManager;

	@Inject
	TenantAccessService tenantAccessService;

	@Context
	SecurityContext securityContext;

	@GET
	public List<ScriptCallLogResponse> list(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@QueryParam("before") Instant before,
			@QueryParam("limit") Integer limit) {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN, TenantRole.MEMBER);
		int resolvedLimit = limit == null || limit <= 0 ? 200 : Math.min(limit, 500);
		String base = "select l from ScriptCallLog l join fetch l.script where l.workspace.id = :workspaceId";
		if (before != null) {
			base += " and l.createdAt < :before";
		}
		base += " order by l.createdAt desc";
		var query = entityManager.createQuery(base, ScriptCallLog.class)
			.setParameter("workspaceId", workspaceId)
			.setMaxResults(resolvedLimit);
		if (before != null) {
			query.setParameter("before", before);
		}
		return query.getResultList().stream().map(this::toResponse).toList();
	}

	private ScriptCallLogResponse toResponse(ScriptCallLog log) {
		return new ScriptCallLogResponse(
			log.id,
			log.script == null ? null : log.script.id,
			log.script == null ? null : log.script.name,
			log.script == null ? null : log.script.slug,
			buildScriptPath(log.script),
			log.workspace == null ? null : log.workspace.id,
			log.tenant == null ? null : log.tenant.id,
			log.user == null ? null : log.user.id,
			log.session == null ? null : log.session.id,
			log.input,
			log.output,
			log.status,
			log.createdAt
		);
	}

	private String buildScriptPath(be.celerex.polymr.model.Script script) {
		if (script == null || script.name == null || script.name.isBlank()) {
			return null;
		}
		if (script.namespace == null || script.namespace.isBlank()) {
			return script.name + ".groovy";
		}
		return script.namespace + "/" + script.name + ".groovy";
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
