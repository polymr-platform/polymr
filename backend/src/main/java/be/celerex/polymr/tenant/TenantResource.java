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

package be.celerex.polymr.tenant;

import be.celerex.polymr.model.Tenant;
import be.celerex.polymr.model.TenantMembership;
import be.celerex.polymr.model.TenantRole;
import be.celerex.polymr.model.User;
import be.celerex.polymr.tenant.dto.TenantInviteRequest;
import be.celerex.polymr.tenant.dto.TenantMemberResponse;
import be.celerex.polymr.tenant.dto.TenantRequest;
import be.celerex.polymr.tenant.dto.TenantResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.util.Locale;
import java.util.List;
import java.util.UUID;

@Path("/api/tenants")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class TenantResource {
	@Inject
	EntityManager entityManager;

	@Inject
	TenantAccessService tenantAccessService;

	@Context
	SecurityContext securityContext;

	@GET
	public List<TenantResponse> listTenants() {
		UUID userId = requireUserId();
		return entityManager.createQuery(
				"select new be.celerex.polymr.tenant.dto.TenantResponse(t.id, t.name, tm.role, t.maxOutputTokens) "
					+ "from TenantMembership tm join tm.tenant t where tm.user.id = :userId",
				TenantResponse.class
			)
			.setParameter("userId", userId)
			.getResultList();
	}

	@POST
	@Transactional
	public TenantResponse createTenant(TenantRequest request) {
		UUID userId = requireUserId();
		if (request == null || request.name() == null || request.name().isBlank()) {
			throw new WebApplicationException("Tenant name is required", Response.Status.BAD_REQUEST);
		}
		User user = entityManager.find(User.class, userId);
		if (user == null) {
			throw new WebApplicationException("User not found", Response.Status.NOT_FOUND);
		}
		Tenant tenant = new Tenant();
		tenant.name = request.name().trim();
		tenant.maxOutputTokens = request.max_output_tokens();
		tenant.owner = user;
		entityManager.persist(tenant);

		TenantMembership membership = new TenantMembership();
		membership.tenant = tenant;
		membership.user = user;
		membership.role = TenantRole.OWNER;
		entityManager.persist(membership);

		return new TenantResponse(tenant.id, tenant.name, membership.role, tenant.maxOutputTokens);
	}

	@PUT
	@Path("/{tenantId}")
	@Transactional
	public TenantResponse updateTenant(@PathParam("tenantId") UUID tenantId, TenantRequest request) {
		UUID userId = requireUserId();
		if (request == null || request.name() == null || request.name().isBlank()) {
			throw new WebApplicationException("Tenant name is required", Response.Status.BAD_REQUEST);
		}
		TenantMembership membership = entityManager.createQuery(
				"select tm from TenantMembership tm where tm.user.id = :userId and tm.tenant.id = :tenantId",
				TenantMembership.class
			)
			.setParameter("userId", userId)
			.setParameter("tenantId", tenantId)
			.getResultStream()
			.findFirst()
			.orElseThrow(() -> new WebApplicationException("Tenant not found", Response.Status.NOT_FOUND));

		if (membership.role != TenantRole.OWNER && membership.role != TenantRole.ADMIN) {
			throw new WebApplicationException("Forbidden", Response.Status.FORBIDDEN);
		}

		Tenant tenant = membership.tenant;
		tenant.name = request.name().trim();
		tenant.maxOutputTokens = request.max_output_tokens();
		return new TenantResponse(tenant.id, tenant.name, membership.role, tenant.maxOutputTokens);
	}

	@GET
	@Path("/{tenantId}/members")
	public List<TenantMemberResponse> listMembers(@PathParam("tenantId") UUID tenantId) {
		UUID userId = requireUserId();
		tenantAccessService.requireMembership(tenantId, userId);
		List<Object[]> rows = entityManager.createQuery(
				"select tm, u from TenantMembership tm join tm.user u "
					+ "where tm.tenant.id = :tenantId order by lower(coalesce(u.nickname, u.email))",
				Object[].class
			)
			.setParameter("tenantId", tenantId)
			.getResultList();
		return rows.stream()
			.map(
				(row) -> {
					TenantMembership membership = (TenantMembership) row[0];
					User user = (User) row[1];
					return new TenantMemberResponse(user.id, user.email, resolveDisplayName(user), user.avatarUrl, membership.role);
				}
			)
			.toList();
	}

	@POST
	@Path("/{tenantId}/members")
	@Transactional
	public TenantMemberResponse inviteMember(@PathParam("tenantId") UUID tenantId, TenantInviteRequest request) {
		UUID userId = requireUserId();
		TenantMembership requester = tenantAccessService.requireMembership(tenantId, userId);
		tenantAccessService.requireRole(requester, TenantRole.OWNER, TenantRole.ADMIN);
		if (request == null || request.email() == null || request.email().isBlank()) {
			throw new WebApplicationException("Email is required", Response.Status.BAD_REQUEST);
		}
		String normalized = request.email().trim().toLowerCase(Locale.ROOT);
		User user = entityManager.createQuery("select u from User u where lower(u.email) = :email", User.class)
			.setParameter("email", normalized)
			.getResultStream()
			.findFirst()
			.orElseThrow(() -> new WebApplicationException("User not found", Response.Status.NOT_FOUND));

		TenantMembership existing = entityManager.createQuery(
				"select tm from TenantMembership tm where tm.tenant.id = :tenantId and tm.user.id = :userId",
				TenantMembership.class
			)
			.setParameter("tenantId", tenantId)
			.setParameter("userId", user.id)
			.getResultStream()
			.findFirst()
			.orElse(null);

		if (existing == null) {
			TenantMembership membership = new TenantMembership();
			membership.tenant = requester.tenant;
			membership.user = user;
			membership.role = TenantRole.MEMBER;
			entityManager.persist(membership);
			existing = membership;
		}

		return new TenantMemberResponse(user.id, user.email, resolveDisplayName(user), user.avatarUrl, existing.role);
	}

	private String resolveDisplayName(User user) {
		if (user == null) {
			return null;
		}
		if (user.nickname != null && !user.nickname.isBlank()) {
			return user.nickname;
		}
		return user.email;
	}

	private UUID requireUserId() {
		if (securityContext == null || securityContext.getUserPrincipal() == null) {
			throw new WebApplicationException("Unauthorized", Response.Status.UNAUTHORIZED);
		}
		return UUID.fromString(securityContext.getUserPrincipal().getName());
	}
}
