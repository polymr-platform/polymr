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

import be.celerex.polymr.model.PushSubscription;
import be.celerex.polymr.model.PushWorkspacePreference;
import be.celerex.polymr.model.TenantMembership;
import be.celerex.polymr.model.Workspace;
import be.celerex.polymr.notifications.dto.PushPreferenceRequest;
import be.celerex.polymr.notifications.dto.PushStatusResponse;
import be.celerex.polymr.tenant.TenantAccessService;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.time.Instant;
import java.util.UUID;

@Path("/api/tenants/{tenantId}/workspaces/{workspaceId}/push")
@Produces(MediaType.APPLICATION_JSON)
public class WorkspacePushResource {
	@Inject
	EntityManager entityManager;

	@Inject
	TenantAccessService tenantAccessService;

	@Inject
	PushNotificationService pushService;

	@Context
	SecurityContext securityContext;

	@GET
	@Transactional
	public PushStatusResponse status(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId) {
		TenantMembership membership = requireMembership(tenantId);
		Workspace workspace = requireWorkspace(tenantId, workspaceId);
		UUID userId = membership.user.id;
		boolean hasSubscription = entityManager.createQuery(
				"select s from PushSubscription s where s.user.id = :userId and s.active = true",
				PushSubscription.class
			)
			.setParameter("userId", userId)
			.setMaxResults(1)
			.getResultStream()
			.findFirst()
			.isPresent();
		PushWorkspacePreference pref = loadPreference(workspaceId, userId);
		String snoozedUntil = membership.user.notificationsSnoozedUntil == null
			? null
			: membership.user.notificationsSnoozedUntil.toString();
		return new PushStatusResponse(pushService.isConfigured(), hasSubscription, pref != null && pref.enabled, snoozedUntil);
	}

	@PUT
	@Transactional
	public PushStatusResponse updatePreference(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			PushPreferenceRequest request) {
		TenantMembership membership = requireMembership(tenantId);
		Workspace workspace = requireWorkspace(tenantId, workspaceId);
		boolean enabled = request != null && request.enabled() != null && request.enabled();
		PushWorkspacePreference pref = loadPreference(workspaceId, membership.user.id);
		if (pref == null) {
			pref = new PushWorkspacePreference();
			pref.user = membership.user;
			pref.workspace = workspace;
			entityManager.persist(pref);
		}
		pref.enabled = enabled;
		pref.updatedAt = Instant.now();
		boolean hasSubscription = entityManager.createQuery(
				"select s from PushSubscription s where s.user.id = :userId and s.active = true",
				PushSubscription.class
			)
			.setParameter("userId", membership.user.id)
			.setMaxResults(1)
			.getResultStream()
			.findFirst()
			.isPresent();
		String snoozedUntil = membership.user.notificationsSnoozedUntil == null
			? null
			: membership.user.notificationsSnoozedUntil.toString();
		return new PushStatusResponse(pushService.isConfigured(), hasSubscription, pref.enabled, snoozedUntil);
	}

	private PushWorkspacePreference loadPreference(UUID workspaceId, UUID userId) {
		return entityManager.createQuery(
				"select p from PushWorkspacePreference p where p.workspace.id = "
					+ ":workspaceId and p.user.id = :userId",
				PushWorkspacePreference.class
			)
			.setParameter("workspaceId", workspaceId)
			.setParameter("userId", userId)
			.getResultStream()
			.findFirst()
			.orElse(null);
	}

	private TenantMembership requireMembership(UUID tenantId) {
		return tenantAccessService.requireMembership(tenantId, requireUserId());
	}

	private Workspace requireWorkspace(UUID tenantId, UUID workspaceId) {
		Workspace workspace = entityManager.find(Workspace.class, workspaceId);
		if (workspace == null || workspace.tenant == null || !workspace.tenant.id.equals(tenantId)) {
			throw new WebApplicationException("Workspace not found", Response.Status.NOT_FOUND);
		}
		return workspace;
	}

	private UUID requireUserId() {
		if (securityContext == null || securityContext.getUserPrincipal() == null) {
			throw new WebApplicationException("Unauthorized", Response.Status.UNAUTHORIZED);
		}
		return UUID.fromString(securityContext.getUserPrincipal().getName());
	}
}
