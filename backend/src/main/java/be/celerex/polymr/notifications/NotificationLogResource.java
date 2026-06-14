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

package be.celerex.polymr.notifications;

import be.celerex.polymr.model.NotificationLog;
import be.celerex.polymr.model.NotificationRecipient;
import be.celerex.polymr.model.TenantMembership;
import be.celerex.polymr.model.Workspace;
import be.celerex.polymr.model.Workspace;
import be.celerex.polymr.notifications.dto.NotificationLogResponse;
import be.celerex.polymr.notifications.dto.NotificationRecipientResponse;
import be.celerex.polymr.tenant.TenantAccessService;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.Context;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Path("/api/tenants/{tenantId}/workspaces/{workspaceId}/notification-logs")
@Produces(MediaType.APPLICATION_JSON)
public class NotificationLogResource {
	@Inject
	EntityManager entityManager;

	@Inject
	TenantAccessService tenantAccessService;

	@Context
	SecurityContext securityContext;

	@GET
	public List<NotificationLogResponse> list(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@QueryParam("before") String before,
			@QueryParam("limit") Integer limit) {
		requireMembership(tenantId);
		Workspace workspace = requireWorkspace(tenantId, workspaceId);
		StringBuilder query = new StringBuilder("select l from NotificationLog l where l.workspace.id = :workspaceId");
		if (before != null && !before.isBlank()) {
			query.append(" and l.createdAt < :before");
		}
		query.append(" order by l.createdAt desc");
		var typed = entityManager.createQuery(query.toString(), NotificationLog.class)
			.setParameter("workspaceId", workspace.id);
		if (before != null && !before.isBlank()) {
			typed.setParameter("before", java.time.Instant.parse(before));
		}
		int resolvedLimit = limit == null || limit <= 0 ? 200 : Math.min(limit, 500);
		List<NotificationLog> logs = typed.setMaxResults(resolvedLimit).getResultList();
		if (logs.isEmpty()) {
			return List.of();
		}
		List<UUID> logIds = logs.stream()
			.map(log -> log.id)
			.toList();
		List<NotificationRecipient> recipients = entityManager.createQuery("select r from NotificationRecipient r where r.log.id in :logIds", NotificationRecipient.class)
			.setParameter("logIds", logIds)
			.getResultList();
		var recipientsByLog = recipients.stream()
			.collect(Collectors.groupingBy(r -> r.log.id));
		return logs.stream()
			.map(log -> toResponse(log, recipientsByLog.getOrDefault(log.id, List.of())))
			.collect(Collectors.toList());
	}

	private NotificationLogResponse toResponse(NotificationLog log, List<NotificationRecipient> recipients) {
		return new NotificationLogResponse(
			log.id,
			log.session == null ? null : log.session.id,
			log.initiator == null ? null : log.initiator.id,
			log.target == null ? null : log.target.name().toLowerCase(),
			log.title,
			log.body,
			log.destination,
			log.eligibleCount,
			log.sentCount,
			log.createdAt == null ? null : log.createdAt.toString(),
			recipients.stream()
				.map(
					recipient -> new NotificationRecipientResponse(recipient.user.id, recipient.status, recipient.detail)
				)
				.collect(Collectors.toList())
		);
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

	private Workspace requireWorkspace(UUID tenantId, UUID workspaceId) {
		Workspace workspace = entityManager.find(Workspace.class, workspaceId);
		if (workspace == null || workspace.tenant == null || !workspace.tenant.id.equals(tenantId)) {
			throw new WebApplicationException("Workspace not found", Response.Status.NOT_FOUND);
		}
		return workspace;
	}
}
