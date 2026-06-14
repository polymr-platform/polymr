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

package be.celerex.polymr.rule;

import be.celerex.polymr.model.Rule;
import be.celerex.polymr.model.RuleScope;
import be.celerex.polymr.model.TenantMembership;
import be.celerex.polymr.model.TenantRole;
import be.celerex.polymr.rule.dto.RuleRequest;
import be.celerex.polymr.rule.dto.RuleResponse;
import be.celerex.polymr.tenant.TenantAccessService;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
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
import java.util.List;
import java.util.UUID;

@Path("/api/tenants/{tenantId}/rules")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class RuleResource {
	@Inject
	EntityManager entityManager;

	@Inject
	TenantAccessService tenantAccessService;

	@Context
	SecurityContext securityContext;

	@GET
	public List<RuleResponse> list(@PathParam("tenantId") UUID tenantId) {
		requireMembership(tenantId);
		return entityManager.createQuery(
				"select new be.celerex.polymr.rule.dto.RuleResponse(r.id, r.name, r.content, "
					+ "r.alwaysIncluded, r.enabled, r.order) from Rule r "
					+ "where r.scope = :scope and r.tenant.id = :tenantId "
					+ "order by coalesce(r.order, 0) desc, r.name",
				RuleResponse.class
			)
			.setParameter("scope", RuleScope.TENANT)
			.setParameter("tenantId", tenantId)
			.getResultList();
	}

	@POST
	@Transactional
	public RuleResponse create(@PathParam("tenantId") UUID tenantId, RuleRequest request) {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		if (request == null || request.name() == null || request.name().isBlank()) {
			throw new WebApplicationException("Name is required", Response.Status.BAD_REQUEST);
		}
		if (request.content() == null || request.content().isBlank()) {
			throw new WebApplicationException("Content is required", Response.Status.BAD_REQUEST);
		}
		Rule rule = new Rule();
		rule.scope = RuleScope.TENANT;
		rule.tenant = membership.tenant;
		rule.name = request.name().trim();
		rule.content = request.content();
		rule.alwaysIncluded = request.always_included();
		rule.enabled = request.enabled();
		rule.order = request.order();
		entityManager.persist(rule);
		return new RuleResponse(rule.id, rule.name, rule.content, rule.alwaysIncluded, rule.enabled, rule.order);
	}

	@PUT
	@Path("/{ruleId}")
	@Transactional
	public RuleResponse update(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("ruleId") UUID ruleId,
			RuleRequest request) {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		Rule rule = requireTenantRule(tenantId, ruleId);
		if (request == null || request.name() == null || request.name().isBlank()) {
			throw new WebApplicationException("Name is required", Response.Status.BAD_REQUEST);
		}
		if (request.content() == null || request.content().isBlank()) {
			throw new WebApplicationException("Content is required", Response.Status.BAD_REQUEST);
		}
		rule.name = request.name().trim();
		rule.content = request.content();
		rule.alwaysIncluded = request.always_included();
		rule.enabled = request.enabled();
		rule.order = request.order();
		return new RuleResponse(rule.id, rule.name, rule.content, rule.alwaysIncluded, rule.enabled, rule.order);
	}

	@DELETE
	@Path("/{ruleId}")
	@Transactional
	public void delete(@PathParam("tenantId") UUID tenantId, @PathParam("ruleId") UUID ruleId) {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		Rule rule = requireTenantRule(tenantId, ruleId);
		entityManager.createQuery("delete from AssistantRule r where r.rule.id = :ruleId")
			.setParameter("ruleId", rule.id)
			.executeUpdate();
		entityManager.createQuery("delete from Rule r where r.id = :ruleId")
			.setParameter("ruleId", rule.id)
			.executeUpdate();
	}

	private Rule requireTenantRule(UUID tenantId, UUID ruleId) {
		Rule rule = entityManager.find(Rule.class, ruleId);
		if (rule == null
				|| rule.scope != RuleScope.TENANT
				|| rule.tenant == null
				|| !rule.tenant.id.equals(tenantId)) {
			throw new WebApplicationException("Rule not found", Response.Status.NOT_FOUND);
		}
		return rule;
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
