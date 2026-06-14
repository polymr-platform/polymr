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

import be.celerex.polymr.mcp.dto.WorkspaceToolCallRequest;
import be.celerex.polymr.scripts.ScriptToolService;
import be.celerex.polymr.model.McpServerTool;
import be.celerex.polymr.model.TenantMembership;
import be.celerex.polymr.tenant.TenantAccessService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.util.List;
import java.util.UUID;

@Path("/api/tenants/{tenantId}/workspaces/{workspaceId}/tools")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class WorkspaceToolResource {
	@Inject
	EntityManager entityManager;

	@Inject
	WorkspaceMcpRegistry mcpRegistry;

	@Inject
	TenantAccessService tenantAccessService;

	@Inject
	ObjectMapper objectMapper;

	@Context
	SecurityContext securityContext;

	@POST
	@Path("/call")
	public JsonNode callTool(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@QueryParam("tag") List<String> tags,
			WorkspaceToolCallRequest request) {
		TenantMembership membership = requireMembership(tenantId);
		String requestedName = request == null ? null : request.service();
		if (requestedName == null || requestedName.isBlank()) {
			requestedName = request == null ? null : request.tool();
		}
		if (requestedName == null || requestedName.isBlank()) {
			throw new WebApplicationException("Tool is required", Response.Status.BAD_REQUEST);
		}
		String name = requestedName.trim();
		List<McpServerTool> tools = entityManager.createQuery(
				"select t from McpServerTool t join fetch t.mcpServer "
					+ "where t.mcpServer.workspace.id = :workspaceId and t.mcpServer.internal = false "
					+ "and t.deleted = false and t.disabled = false "
					+ "and lower(coalesce(t.mcpServer.toolNamePrefix, '') || coalesce(t.toolAlias, t.toolName)) = "
					+ "lower(:name)",
				McpServerTool.class
			)
			.setParameter("workspaceId", workspaceId)
			.setParameter("name", name)
			.getResultList();
		if (tools.isEmpty()) {
			throw new WebApplicationException("Tool not found", Response.Status.NOT_FOUND);
		}
		if (tools.size() > 1) {
			throw new WebApplicationException("Multiple tools share that alias", Response.Status.BAD_REQUEST);
		}
		McpServerTool tool = tools.get(0);
		if (tool.mcpServer == null) {
			throw new WebApplicationException("Tool not available", Response.Status.NOT_FOUND);
		}
		JsonNode arguments = request.arguments() == null ? objectMapper.createObjectNode() : request.arguments();
		ObjectNode meta = objectMapper.createObjectNode();
		if (tags != null && !tags.isEmpty()) {
			ArrayNode tagArray = meta.putArray("tags");
			for (String tag : tags) {
				String normalized = tag == null ? null : tag.trim();
				if (normalized == null || normalized.isBlank()) {
					continue;
				}
				if (!isValidTag(normalized)) {
					throw new WebApplicationException("Invalid tag format. Expected type:value", Response.Status.BAD_REQUEST);
				}
				tagArray.add(normalized);
			}
		}
		ArrayNode allowedScopes = ScriptToolService.readAllowedScopes(objectMapper, tool);
		if (allowedScopes != null && allowedScopes.size() > 0) {
			meta.set("allowed_scopes", allowedScopes);
		}
		return mcpRegistry.call(workspaceId, tool.mcpServer.id, tool.toolName, arguments, meta, null, membership.user.id);
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

	private boolean isValidTag(String value) {
		int index = value.indexOf(':');
		if (index <= 0 || index >= value.length() - 1) {
			return false;
		}
		return !value.contains(" ");
	}
}
