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

package be.celerex.polymr.tag;

import be.celerex.polymr.model.TagCategory;
import be.celerex.polymr.model.TagValue;
import be.celerex.polymr.model.TenantMembership;
import be.celerex.polymr.model.TenantRole;
import be.celerex.polymr.model.Workspace;
import be.celerex.polymr.model.WorkspaceTagSelection;
import be.celerex.polymr.tag.dto.TagSelectionRequest;
import be.celerex.polymr.tag.dto.TagSelectionResponse;
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
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Path("/api/tenants/{tenantId}/workspaces/{workspaceId}/tag-states")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class TagStateResource {
	@Inject
	EntityManager entityManager;

	@Inject
	TenantAccessService tenantAccessService;

	@Context
	SecurityContext securityContext;

	@GET
	public List<TagSelectionResponse> list(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId) {
		requireMembership(tenantId);
		Workspace workspace = requireWorkspace(tenantId, workspaceId);
		List<WorkspaceTagSelection> states = entityManager.createQuery(
				"select s from WorkspaceTagSelection s where s.workspace.id = :workspaceId",
				WorkspaceTagSelection.class
			)
			.setParameter("workspaceId", workspace.id)
			.getResultList();
		return states.stream().map(this::toResponse).collect(Collectors.toList());
	}

	@PUT
	@Path("/{categoryId}")
	@Transactional
	public TagSelectionResponse update(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("categoryId") UUID categoryId,
			TagSelectionRequest request) {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		Workspace workspace = requireWorkspace(tenantId, workspaceId);
		TagCategory category = entityManager.find(TagCategory.class, categoryId);
		if (category == null || category.workspace == null || !category.workspace.id.equals(workspaceId)) {
			throw new WebApplicationException("Tag category not found", Response.Status.NOT_FOUND);
		}
		TagValue value = null;
		if (request != null && request.value_id() != null) {
			value = entityManager.find(TagValue.class, request.value_id());
			if (value == null
					|| value.category == null
					|| !value.category.id.equals(categoryId)
					|| value.deletedAt != null) {
				throw new WebApplicationException("Tag value not found", Response.Status.NOT_FOUND);
			}
		}
		WorkspaceTagSelection state = entityManager.createQuery(
				"select s from WorkspaceTagSelection s where s.workspace.id = :workspaceId "
					+ "and s.category.id = :categoryId",
				WorkspaceTagSelection.class
			)
			.setParameter("workspaceId", workspace.id)
			.setParameter("categoryId", categoryId)
			.getResultStream()
			.findFirst()
			.orElse(null);
		if (state == null) {
			state = new WorkspaceTagSelection();
			state.workspace = workspace;
			state.category = category;
			state.value = value;
			entityManager.persist(state);
		}
		else {
			state.value = value;
		}
		return toResponse(state);
	}

	private TagSelectionResponse toResponse(WorkspaceTagSelection state) {
		return new TagSelectionResponse(state.category == null ? null : state.category.id, state.value == null ? null : state.value.id);
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
