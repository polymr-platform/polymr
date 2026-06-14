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

package be.celerex.polymr.prompt;

import be.celerex.polymr.model.PromptTemplate;
import be.celerex.polymr.model.PromptTemplateSection;
import be.celerex.polymr.model.TenantMembership;
import be.celerex.polymr.model.TenantRole;
import be.celerex.polymr.model.Workspace;
import be.celerex.polymr.prompt.dto.PromptTemplateRequest;
import be.celerex.polymr.prompt.dto.PromptTemplateResponse;
import be.celerex.polymr.tenant.TenantAccessService;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Path("/api/tenants/{tenantId}/prompt-templates")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class PromptTemplateResource {
	@Inject
	EntityManager entityManager;

	@Inject
	TenantAccessService tenantAccessService;

	@Inject
	PromptTemplateService templateService;

	@Context
	SecurityContext securityContext;

	@GET
	public List<PromptTemplateResponse> listTemplates(
			@PathParam("tenantId") UUID tenantId,
			@QueryParam("workspaceId") UUID workspaceId) {
		tenantAccessService.requireMembership(tenantId, requireUserId());
		List<PromptTemplate> templates = new ArrayList<>();
		templates.addAll(loadStandardTemplates());
		templates.addAll(loadTenantTemplates(tenantId));
		if (workspaceId != null) {
			requireWorkspace(tenantId, workspaceId);
			templates.addAll(loadWorkspaceTemplates(workspaceId));
		}
		return templates.stream().map(this::toResponse).toList();
	}

	@PUT
	@Transactional
	public PromptTemplateResponse upsertTenantTemplate(
			@PathParam("tenantId") UUID tenantId,
			PromptTemplateRequest request) {
		TenantMembership membership = tenantAccessService.requireMembership(tenantId, requireUserId());
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		PromptTemplateSection section = request == null ? null : request.section();
		String content = request == null ? null : request.content();
		if (section == null || content == null) {
			throw new WebApplicationException("Section and content are required", Response.Status.BAD_REQUEST);
		}
		PromptTemplate template = templateService.findTemplate(tenantId, null, section);
		if (template == null) {
			template = new PromptTemplate();
			template.section = section;
			template.tenant = membership.tenant;
			template.workspace = null;
			entityManager.persist(template);
		}
		template.content = content;
		template.enabled = request.enabled();
		return toResponse(template);
	}

	@PUT
	@Path("/workspaces/{workspaceId}")
	@Transactional
	public PromptTemplateResponse upsertWorkspaceTemplate(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			PromptTemplateRequest request) {
		TenantMembership membership = tenantAccessService.requireMembership(tenantId, requireUserId());
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		Workspace workspace = requireWorkspace(tenantId, workspaceId);
		PromptTemplateSection section = request == null ? null : request.section();
		String content = request == null ? null : request.content();
		if (section == null || content == null) {
			throw new WebApplicationException("Section and content are required", Response.Status.BAD_REQUEST);
		}
		PromptTemplate template = templateService.findTemplate(tenantId, workspaceId, section);
		if (template == null) {
			template = new PromptTemplate();
			template.section = section;
			template.tenant = membership.tenant;
			template.workspace = workspace;
			entityManager.persist(template);
		}
		template.content = content;
		template.enabled = request.enabled();
		return toResponse(template);
	}

	private PromptTemplateResponse toResponse(PromptTemplate template) {
		if (template == null) {
			return null;
		}
		UUID tenantId = template.tenant == null ? null : template.tenant.id;
		UUID workspaceId = template.workspace == null ? null : template.workspace.id;
		boolean standard = tenantId == null && workspaceId == null;
		return new PromptTemplateResponse(template.id, tenantId, workspaceId, template.section, template.content, template.enabled, standard);
	}

	private List<PromptTemplate> loadStandardTemplates() {
		return entityManager.createQuery(
				"select p from PromptTemplate p where p.tenant is null and p.workspace is null",
				PromptTemplate.class
			)
			.getResultList();
	}

	private List<PromptTemplate> loadTenantTemplates(UUID tenantId) {
		return entityManager.createQuery(
				"select p from PromptTemplate p where p.tenant.id = :tenantId and p.workspace is null",
				PromptTemplate.class
			)
			.setParameter("tenantId", tenantId)
			.getResultList();
	}

	private List<PromptTemplate> loadWorkspaceTemplates(UUID workspaceId) {
		return entityManager.createQuery("select p from PromptTemplate p where p.workspace.id = :workspaceId", PromptTemplate.class)
			.setParameter("workspaceId", workspaceId)
			.getResultList();
	}

	private Workspace requireWorkspace(UUID tenantId, UUID workspaceId) {
		if (workspaceId == null) {
			throw new WebApplicationException("Workspace is required", Response.Status.BAD_REQUEST);
		}
		Workspace workspace = entityManager.createQuery("select w from Workspace w where w.id = :workspaceId and w.tenant.id = :tenantId", Workspace.class)
			.setParameter("workspaceId", workspaceId)
			.setParameter("tenantId", tenantId)
			.getResultStream()
			.findFirst()
			.orElse(null);
		if (workspace == null) {
			throw new WebApplicationException("Workspace not found", Response.Status.NOT_FOUND);
		}
		return workspace;
	}

	private UUID requireUserId() {
		if (securityContext == null || securityContext.getUserPrincipal() == null) {
			throw new WebApplicationException(Response.Status.UNAUTHORIZED);
		}
		try {
			return UUID.fromString(securityContext.getUserPrincipal().getName());
		}
		catch (IllegalArgumentException error) {
			throw new WebApplicationException(Response.Status.UNAUTHORIZED);
		}
	}
}
