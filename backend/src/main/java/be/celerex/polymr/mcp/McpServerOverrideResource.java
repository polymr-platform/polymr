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

import be.celerex.polymr.mcp.dto.McpServerOverrideRequest;
import be.celerex.polymr.mcp.dto.McpServerOverrideResponse;
import be.celerex.polymr.model.McpProtocol;
import be.celerex.polymr.model.McpServer;
import be.celerex.polymr.model.McpServerOverride;
import be.celerex.polymr.model.TagCategory;
import be.celerex.polymr.model.TagValue;
import be.celerex.polymr.model.TenantMembership;
import be.celerex.polymr.model.TenantRole;
import be.celerex.polymr.model.Workspace;
import be.celerex.polymr.tenant.TenantAccessService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

@Path("/api/tenants/{tenantId}/workspaces/{workspaceId}/mcp-servers/{serverId}/overrides")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class McpServerOverrideResource {
	@Inject
	EntityManager entityManager;

	@Inject
	TenantAccessService tenantAccessService;

	@Inject
	McpOAuthService oauthService;

	@Inject
	ObjectMapper objectMapper;

	@Context
	SecurityContext securityContext;

	@GET
	public List<McpServerOverrideResponse> list(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("serverId") UUID serverId) {
		requireMembership(tenantId);
		requireWorkspace(tenantId, workspaceId);
		McpServer server = requireServer(workspaceId, serverId);
		List<McpServerOverride> overrides = entityManager.createQuery(
				"select o from McpServerOverride o join fetch o.tag t join fetch t.category "
					+ "where o.mcpServer.id = :serverId",
				McpServerOverride.class
			)
			.setParameter("serverId", server.id)
			.getResultList();
		UUID authScopeValueId = oauthService.resolveActiveAuthScopeValue(server);
		return overrides.stream()
			.map(override -> toResponse(override, server, authScopeValueId))
			.collect(Collectors.toList());
	}

	@POST
	@Transactional
	public McpServerOverrideResponse create(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("serverId") UUID serverId,
			McpServerOverrideRequest request) {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		requireWorkspace(tenantId, workspaceId);
		McpServer server = requireServer(workspaceId, serverId);
		requireHttpServer(server);
		validateRequest(request, server);
		TagValue tag = requireTag(workspaceId, request.tag_id());
		McpServerOverride override = new McpServerOverride();
		override.mcpServer = server;
		override.tag = tag;
		applyRequest(override, server, request);
		entityManager.persist(override);
		UUID authScopeValueId = oauthService.resolveActiveAuthScopeValue(server);
		return toResponse(override, server, authScopeValueId);
	}

	@PUT
	@Path("/{overrideId}")
	@Transactional
	public McpServerOverrideResponse update(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("serverId") UUID serverId,
			@PathParam("overrideId") UUID overrideId,
			McpServerOverrideRequest request) {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		requireWorkspace(tenantId, workspaceId);
		McpServer server = requireServer(workspaceId, serverId);
		requireHttpServer(server);
		McpServerOverride override = entityManager.find(McpServerOverride.class, overrideId);
		if (override == null || !override.mcpServer.id.equals(serverId)) {
			throw new WebApplicationException("Override not found", Response.Status.NOT_FOUND);
		}
		validateRequest(request, server);
		TagValue tag = requireTag(workspaceId, request.tag_id());
		override.tag = tag;
		applyRequest(override, server, request);
		UUID authScopeValueId = oauthService.resolveActiveAuthScopeValue(server);
		return toResponse(override, server, authScopeValueId);
	}

	@DELETE
	@Path("/{overrideId}")
	@Transactional
	public void delete(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("serverId") UUID serverId,
			@PathParam("overrideId") UUID overrideId) {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		requireWorkspace(tenantId, workspaceId);
		requireServer(workspaceId, serverId);
		McpServerOverride override = entityManager.find(McpServerOverride.class, overrideId);
		if (override == null || !override.mcpServer.id.equals(serverId)) {
			throw new WebApplicationException("Override not found", Response.Status.NOT_FOUND);
		}
		entityManager.remove(override);
	}

	private void validateRequest(McpServerOverrideRequest request, McpServer server) {
		if (request == null || request.tag_id() == null) {
			throw new WebApplicationException("Tag is required", Response.Status.BAD_REQUEST);
		}
		if (request.logical_name() == null || request.logical_name().isBlank()) {
			throw new WebApplicationException("Logical name is required", Response.Status.BAD_REQUEST);
		}
		if (request.auth() != null && !request.auth().isNull() && !server.oauthEnabled) {
			throw new WebApplicationException("OAuth is disabled", Response.Status.BAD_REQUEST);
		}
	}

	private void applyRequest(McpServerOverride override, McpServer server, McpServerOverrideRequest request) {
		override.logicalName = request.logical_name().trim();
		override.httpUrl = request.http_url();
		override.headers = request.headers();
		if (request.ssh_enabled() != null) {
			override.sshEnabled = request.ssh_enabled();
		}
		if (request.ssh_tunnel() != null) {
			override.sshTunnel = request.ssh_tunnel();
		}
		if (request.auth() != null && !request.auth().isNull()) {
			McpServer authServer = buildOverrideAuthServer(server, override);
			ObjectNode baseAuth = oauthService.buildAuthConfig(server);
			JsonNode mergedAuth = deepMergeJson(baseAuth, request.auth());
			oauthService.updateServerAuthConfig(authServer, mergedAuth);
			override.oauthProvider = authServer.oauthProvider;
		}
	}

	private McpServerOverrideResponse toResponse(McpServerOverride override, McpServer server, UUID authScopeValueId) {
		TagValue tag = override.tag;
		TagCategory category = tag == null ? null : tag.category;
		McpServer authServer = buildAuthServer(server, override);
		return new McpServerOverrideResponse(
			override.id,
			tag == null ? null : tag.id,
			category == null ? null : category.id,
			category == null ? null : category.name,
			category == null ? null : category.slug,
			tag == null ? null : tag.name,
			tag == null ? null : tag.slug,
			category == null ? 0 : category.priority,
			tag == null ? 0 : tag.priority,
			override.logicalName,
			override.httpUrl,
			override.headers,
			override.sshEnabled,
			override.sshTunnel,
			oauthService.buildAuthView(authServer, authScopeValueId)
		);
	}

	private McpServer buildAuthServer(McpServer server, McpServerOverride override) {
		McpServer authServer = new McpServer();
		authServer.workspace = server.workspace;
		authServer.oauthEnabled = server.oauthEnabled;
		authServer.oauthProvider = override.oauthProvider == null ? server.oauthProvider : override.oauthProvider;
		return authServer;
	}

	private McpServer buildOverrideAuthServer(McpServer server, McpServerOverride override) {
		McpServer authServer = new McpServer();
		authServer.workspace = server.workspace;
		authServer.oauthEnabled = server.oauthEnabled;
		authServer.oauthProvider = override.oauthProvider;
		return authServer;
	}

	private JsonNode deepMergeJson(JsonNode base, JsonNode override) {
		if (override == null || override.isNull()) {
			return base;
		}
		if (base == null || base.isNull()) {
			return override.deepCopy();
		}
		if (!override.isObject() || !base.isObject()) {
			return override.deepCopy();
		}
		ObjectNode merged = objectMapper.createObjectNode();
		base.fields()
			.forEachRemaining(entry -> merged.set(entry.getKey(), entry.getValue().deepCopy()));
		override.fields()
			.forEachRemaining(
				entry -> {
					String key = entry.getKey();
					JsonNode value = entry.getValue();
					if (value == null || value.isNull()) {
						return;
					}
					JsonNode existing = merged.get(key);
					merged.set(key, deepMergeJson(existing, value));
				}
			);
		return merged;
	}

	private void requireHttpServer(McpServer server) {
		if (server.protocol != McpProtocol.SSE && server.protocol != McpProtocol.STREAMABLE_HTTP) {
			throw new WebApplicationException("Overrides are only supported for HTTP MCP servers", Response.Status.BAD_REQUEST);
		}
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
