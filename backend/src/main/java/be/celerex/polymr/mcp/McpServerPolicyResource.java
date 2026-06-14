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

package be.celerex.polymr.mcp;

import be.celerex.polymr.mcp.dto.McpServerPolicyRequest;
import be.celerex.polymr.mcp.dto.McpServerPolicyResponse;
import be.celerex.polymr.model.McpServer;
import be.celerex.polymr.model.McpServerPolicy;
import be.celerex.polymr.model.TagCategory;
import be.celerex.polymr.model.TagValue;
import be.celerex.polymr.model.TenantMembership;
import be.celerex.polymr.model.TenantRole;
import be.celerex.polymr.model.Workspace;
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
import java.util.stream.Collectors;

@Path("/api/tenants/{tenantId}/workspaces/{workspaceId}/mcp-servers/{serverId}/policies")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class McpServerPolicyResource {
	@Inject
	EntityManager entityManager;

	@Inject
	TenantAccessService tenantAccessService;

	@Context
	SecurityContext securityContext;

	@GET
	public List<McpServerPolicyResponse> list(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("serverId") UUID serverId) {
		requireMembership(tenantId);
		requireWorkspace(tenantId, workspaceId);
		McpServer server = requireServer(workspaceId, serverId);
		List<McpServerPolicy> policies = entityManager.createQuery(
				"select p from McpServerPolicy p join fetch p.tag where p.mcpServer.id = :serverId",
				McpServerPolicy.class
			)
			.setParameter("serverId", server.id)
			.getResultList();
		return policies.stream().map(this::toResponse).collect(Collectors.toList());
	}

	@POST
	@Transactional
	public McpServerPolicyResponse create(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("serverId") UUID serverId,
			McpServerPolicyRequest request) {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		requireWorkspace(tenantId, workspaceId);
		McpServer server = requireServer(workspaceId, serverId);
		if (!server.allowPolicy) {
			throw new WebApplicationException("Policy support not enabled", Response.Status.BAD_REQUEST);
		}
		validateRequest(request);
		TagValue tag = requireTag(workspaceId, request.tag_id());
		McpServerPolicy policy = new McpServerPolicy();
		policy.mcpServer = server;
		policy.tag = tag;
		policy.policyJson = request.policy_json();
		entityManager.persist(policy);
		return toResponse(policy);
	}

	@PUT
	@Path("/{policyId}")
	@Transactional
	public McpServerPolicyResponse update(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("serverId") UUID serverId,
			@PathParam("policyId") UUID policyId,
			McpServerPolicyRequest request) {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		requireWorkspace(tenantId, workspaceId);
		McpServer server = requireServer(workspaceId, serverId);
		if (!server.allowPolicy) {
			throw new WebApplicationException("Policy support not enabled", Response.Status.BAD_REQUEST);
		}
		McpServerPolicy policy = entityManager.find(McpServerPolicy.class, policyId);
		if (policy == null || !policy.mcpServer.id.equals(serverId)) {
			throw new WebApplicationException("Policy not found", Response.Status.NOT_FOUND);
		}
		validateRequest(request);
		TagValue tag = requireTag(workspaceId, request.tag_id());
		policy.tag = tag;
		policy.policyJson = request.policy_json();
		return toResponse(policy);
	}

	@DELETE
	@Path("/{policyId}")
	@Transactional
	public void delete(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("serverId") UUID serverId,
			@PathParam("policyId") UUID policyId) {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		requireWorkspace(tenantId, workspaceId);
		requireServer(workspaceId, serverId);
		McpServerPolicy policy = entityManager.find(McpServerPolicy.class, policyId);
		if (policy == null || !policy.mcpServer.id.equals(serverId)) {
			throw new WebApplicationException("Policy not found", Response.Status.NOT_FOUND);
		}
		entityManager.remove(policy);
	}

	private void validateRequest(McpServerPolicyRequest request) {
		if (request == null || request.tag_id() == null) {
			throw new WebApplicationException("Tag is required", Response.Status.BAD_REQUEST);
		}
		if (request.policy_json() == null || request.policy_json().isNull()) {
			throw new WebApplicationException("Policy JSON is required", Response.Status.BAD_REQUEST);
		}
	}

	private McpServerPolicyResponse toResponse(McpServerPolicy policy) {
		TagCategory category = policy.tag == null ? null : policy.tag.category;
		return new McpServerPolicyResponse(
			policy.id,
			policy.tag == null ? null : policy.tag.id,
			category == null ? null : category.id,
			category == null ? null : category.name,
			category == null ? null : category.slug,
			policy.tag == null ? null : policy.tag.name,
			policy.tag == null ? null : policy.tag.slug,
			policy.policyJson
		);
	}

	private McpServer requireServer(UUID workspaceId, UUID serverId) {
		McpServer server = entityManager.find(McpServer.class, serverId);
		if (server == null || !server.workspace.id.equals(workspaceId)) {
			throw new WebApplicationException("MCP server not found", Response.Status.NOT_FOUND);
		}
		return server;
	}

	private TagValue requireTag(UUID workspaceId, UUID tagId) {
		TagValue tag = entityManager.find(TagValue.class, tagId);
		TagCategory category = tag == null ? null : tag.category;
		if (tag == null
				|| category == null
				|| category.workspace == null
				|| !category.workspace.id.equals(workspaceId)
				|| category.deletedAt != null
				|| tag.deletedAt != null) {
			throw new WebApplicationException("Tag not found", Response.Status.NOT_FOUND);
		}
		return tag;
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
