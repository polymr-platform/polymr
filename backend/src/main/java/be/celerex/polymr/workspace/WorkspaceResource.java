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

package be.celerex.polymr.workspace;

import be.celerex.polymr.model.TenantMembership;
import be.celerex.polymr.model.TenantRole;
import be.celerex.polymr.model.Workspace;
import be.celerex.polymr.tenant.TenantAccessService;
import be.celerex.polymr.mcp.WorkspaceScopeService;
import be.celerex.polymr.workspace.dto.WorkspaceRequest;
import be.celerex.polymr.workspace.dto.WorkspaceResponse;
import be.celerex.polymr.workspace.dto.WorkspaceScopeRequest;
import be.celerex.polymr.workspace.dto.WorkspaceScopeResponse;
import be.celerex.polymr.workflow.ConversationWorkflowStartup;
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

@Path("/api/tenants/{tenantId}/workspaces")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class WorkspaceResource {
	@Inject
	EntityManager entityManager;

	@Inject
	TenantAccessService tenantAccessService;

	@Inject
	WorkspaceScopeService workspaceScopeService;

	@Inject
	ConversationWorkflowStartup conversationWorkflowStartup;

	@Context
	SecurityContext securityContext;

	@GET
	public List<WorkspaceResponse> list(@PathParam("tenantId") UUID tenantId) {
		requireMembership(tenantId);
		return entityManager.createQuery(
				"select new be.celerex.polymr.workspace.dto.WorkspaceResponse(w.id, w.name, w.description, "
					+ "w.externalFrontendImports) "
					+ "from Workspace w where w.tenant.id = :tenantId",
				WorkspaceResponse.class
			)
			.setParameter("tenantId", tenantId)
			.getResultList();
	}

	@POST
	@Transactional
	public WorkspaceResponse create(@PathParam("tenantId") UUID tenantId, WorkspaceRequest request) {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		if (request == null || request.name() == null || request.name().isBlank()) {
			throw new WebApplicationException("Name is required", Response.Status.BAD_REQUEST);
		}
		Workspace workspace = new Workspace();
		workspace.tenant = membership.tenant;
		workspace.name = request.name().trim();
		workspace.description = request.description();
		workspace.externalFrontendImports = request.external_frontend_imports();
		entityManager.persist(workspace);
		conversationWorkflowStartup.ensureForWorkspace(workspace);
		return new WorkspaceResponse(workspace.id, workspace.name, workspace.description, workspace.externalFrontendImports);
	}

	@PUT
	@Path("/{workspaceId}")
	@Transactional
	public WorkspaceResponse update(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			WorkspaceRequest request) {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		Workspace workspace = entityManager.find(Workspace.class, workspaceId);
		if (workspace == null || !workspace.tenant.id.equals(tenantId)) {
			throw new WebApplicationException("Workspace not found", Response.Status.NOT_FOUND);
		}
		if (request == null || request.name() == null || request.name().isBlank()) {
			throw new WebApplicationException("Name is required", Response.Status.BAD_REQUEST);
		}
		workspace.name = request.name().trim();
		workspace.description = request.description();
		workspace.externalFrontendImports = request.external_frontend_imports();
		return new WorkspaceResponse(workspace.id, workspace.name, workspace.description, workspace.externalFrontendImports);
	}

	@DELETE
	@Path("/{workspaceId}")
	@Transactional
	public Response delete(@PathParam("tenantId") UUID tenantId, @PathParam("workspaceId") UUID workspaceId) {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		Workspace workspace = entityManager.find(Workspace.class, workspaceId);
		if (workspace == null || !workspace.tenant.id.equals(tenantId)) {
			throw new WebApplicationException("Workspace not found", Response.Status.NOT_FOUND);
		}
		entityManager.remove(workspace);
		return Response.noContent().build();
	}

	private TenantMembership requireMembership(UUID tenantId) {
		return tenantAccessService.requireMembership(tenantId, requireUserId());
	}

	@GET
	@Path("/{workspaceId}/scopes")
	public WorkspaceScopeResponse getScopes(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId) {
		requireMembership(tenantId);
		Workspace workspace = entityManager.find(Workspace.class, workspaceId);
		if (workspace == null || !workspace.tenant.id.equals(tenantId)) {
			throw new WebApplicationException("Workspace not found", Response.Status.NOT_FOUND);
		}
		WorkspaceScopeService.ScopePermissions scopes = workspaceScopeService.load(workspaceId);
		return new WorkspaceScopeResponse(scopes.allow(), scopes.deny());
	}

	@PUT
	@Path("/{workspaceId}/scopes")
	@Transactional
	public WorkspaceScopeResponse updateScopes(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			WorkspaceScopeRequest request) {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		Workspace workspace = entityManager.find(Workspace.class, workspaceId);
		if (workspace == null || !workspace.tenant.id.equals(tenantId)) {
			throw new WebApplicationException("Workspace not found", Response.Status.NOT_FOUND);
		}
		List<String> allow = request == null ? List.of() : request.allow_scopes();
		List<String> deny = request == null ? List.of() : request.deny_scopes();
		WorkspaceScopeService.ScopePermissions scopes = workspaceScopeService.save(workspaceId, allow, deny);
		return new WorkspaceScopeResponse(scopes.allow(), scopes.deny());
	}

	private UUID requireUserId() {
		if (securityContext == null || securityContext.getUserPrincipal() == null) {
			throw new WebApplicationException("Unauthorized", Response.Status.UNAUTHORIZED);
		}
		return UUID.fromString(securityContext.getUserPrincipal().getName());
	}
}
