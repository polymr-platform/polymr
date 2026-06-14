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

package be.celerex.polymr.skill;

import be.celerex.polymr.model.AssistantSkill;
import be.celerex.polymr.model.Skill;
import be.celerex.polymr.model.TenantMembership;
import be.celerex.polymr.model.TenantRole;
import be.celerex.polymr.model.Workspace;
import be.celerex.polymr.skill.dto.SkillRequest;
import be.celerex.polymr.skill.dto.SkillResponse;
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

@Path("/api/tenants/{tenantId}/skills")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class SkillResource {
	@Inject
	EntityManager entityManager;

	@Inject
	TenantAccessService tenantAccessService;

	@Context
	SecurityContext securityContext;

	@GET
	public List<SkillResponse> list(@PathParam("tenantId") UUID tenantId) {
		requireMembership(tenantId);
		return entityManager.createQuery(
				"select new be.celerex.polymr.skill.dto.SkillResponse(s.id, null, s.name, s.trigger, s.description,"
					+ " s.alwaysIncluded, s.promptText) from Skill s where s.tenant.id = :tenantId and "
					+ "s.workspace is null",
				SkillResponse.class
			)
			.setParameter("tenantId", tenantId)
			.getResultList();
	}

	@POST
	@Transactional
	public SkillResponse create(@PathParam("tenantId") UUID tenantId, SkillRequest request) {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		validate(request);
		if (request.workspace_id() != null) {
			throw new WebApplicationException("Workspace skills must be created through the workspace endpoint", Response.Status.BAD_REQUEST);
		}
		Skill skill = new Skill();
		skill.tenant = membership.tenant;
		skill.workspace = null;
		skill.name = request.name().trim();
		skill.trigger = request.trigger().trim();
		skill.description = request.description().trim();
		skill.alwaysIncluded = request.always_included();
		skill.promptText = normalizePrompt(request.prompt_text());
		entityManager.persist(skill);
		return toResponse(skill);
	}

	@PUT
	@Path("/{skillId}")
	@Transactional
	public SkillResponse update(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("skillId") UUID skillId,
			SkillRequest request) {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		Skill skill = entityManager.find(Skill.class, skillId);
		if (skill == null || !skill.tenant.id.equals(tenantId) || skill.workspace != null) {
			throw new WebApplicationException("Skill not found", Response.Status.NOT_FOUND);
		}
		validate(request);
		if (request.workspace_id() != null) {
			throw new WebApplicationException("Workspace skills must be updated through the workspace endpoint", Response.Status.BAD_REQUEST);
		}
		skill.name = request.name().trim();
		skill.trigger = request.trigger().trim();
		skill.description = request.description().trim();
		skill.alwaysIncluded = request.always_included();
		skill.promptText = normalizePrompt(request.prompt_text());
		return toResponse(skill);
	}

	@DELETE
	@Path("/{skillId}")
	@Transactional
	public Response delete(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("skillId") UUID skillId) {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		Skill skill = entityManager.find(Skill.class, skillId);
		if (skill == null || !skill.tenant.id.equals(tenantId) || skill.workspace != null) {
			throw new WebApplicationException("Skill not found", Response.Status.NOT_FOUND);
		}
		entityManager.createQuery("delete from AssistantSkill s where s.skill.id = :skillId")
			.setParameter("skillId", skill.id)
			.executeUpdate();
		entityManager.remove(skill);
		return Response.noContent().build();
	}

	private String normalizePrompt(String prompt) {
		if (prompt == null) {
			return null;
		}
		String trimmed = prompt.trim();
		return trimmed.isBlank() ? null : trimmed;
	}

	private SkillResponse toResponse(Skill skill) {
		return new SkillResponse(
			skill.id,
			skill.workspace == null ? null : skill.workspace.id,
			skill.name,
			skill.trigger,
			skill.description,
			skill.alwaysIncluded,
			skill.promptText
		);
	}

	private Workspace requireWorkspace(UUID tenantId, UUID workspaceId) {
		Workspace workspace = entityManager.find(Workspace.class, workspaceId);
		if (workspace == null || !workspace.tenant.id.equals(tenantId)) {
			throw new WebApplicationException("Workspace not found", Response.Status.NOT_FOUND);
		}
		return workspace;
	}

	private void validate(SkillRequest request) {
		if (request == null || request.name() == null || request.name().isBlank()) {
			throw new WebApplicationException("Name is required", Response.Status.BAD_REQUEST);
		}
		if (request.trigger() == null || request.trigger().isBlank()) {
			throw new WebApplicationException("Trigger is required", Response.Status.BAD_REQUEST);
		}
		if (request.description() == null || request.description().isBlank()) {
			throw new WebApplicationException("Description is required", Response.Status.BAD_REQUEST);
		}
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
