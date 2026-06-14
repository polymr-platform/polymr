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
import be.celerex.polymr.model.User;
import be.celerex.polymr.notifications.dto.PushSubscriptionRequest;
import be.celerex.polymr.tenant.TenantAccessService;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.time.Instant;
import java.util.UUID;

@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class PushResource {
	@Inject
	EntityManager entityManager;

	@Inject
	TenantAccessService tenantAccessService;

	@Inject
	PushNotificationService pushService;

	@Context
	SecurityContext securityContext;

	@POST
	@Path("/push/subscriptions")
	@Transactional
	public void registerSubscription(PushSubscriptionRequest request) {
		UUID userId = requireUserId();
		if (request == null || request.endpoint() == null || request.endpoint().isBlank()) {
			throw new WebApplicationException("Endpoint is required", Response.Status.BAD_REQUEST);
		}
		if (request.p256dh() == null || request.p256dh().isBlank()) {
			throw new WebApplicationException("Key is required", Response.Status.BAD_REQUEST);
		}
		if (request.auth() == null || request.auth().isBlank()) {
			throw new WebApplicationException("Auth secret is required", Response.Status.BAD_REQUEST);
		}
		User user = entityManager.find(User.class, userId);
		PushSubscription existing = entityManager.createQuery(
				"select s from PushSubscription s where s.user.id = :userId and s.endpoint = :endpoint",
				PushSubscription.class
			)
			.setParameter("userId", userId)
			.setParameter("endpoint", request.endpoint())
			.getResultStream()
			.findFirst()
			.orElse(null);
		if (existing == null) {
			existing = new PushSubscription();
			existing.user = user;
			existing.endpoint = request.endpoint();
			existing.p256dh = request.p256dh();
			existing.auth = request.auth();
			existing.userAgent = request.user_agent();
			existing.active = true;
			existing.lastSeenAt = Instant.now();
			entityManager.persist(existing);
			return;
		}
		existing.p256dh = request.p256dh();
		existing.auth = request.auth();
		existing.userAgent = request.user_agent();
		existing.active = true;
		existing.lastSeenAt = Instant.now();
	}

	@GET
	@Path("/push/vapid")
	public java.util.Map<String, Object> getVapidKey() {
		if (!pushService.isConfigured()) {
			throw new WebApplicationException("Push notifications are not configured", Response.Status.CONFLICT);
		}
		return java.util.Map.of("public_key", pushService.publicKey());
	}

	private UUID requireUserId() {
		if (securityContext == null || securityContext.getUserPrincipal() == null) {
			throw new WebApplicationException("Unauthorized", Response.Status.UNAUTHORIZED);
		}
		return UUID.fromString(securityContext.getUserPrincipal().getName());
	}
}
