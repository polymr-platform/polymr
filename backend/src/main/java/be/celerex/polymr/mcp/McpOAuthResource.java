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

import be.celerex.polymr.model.McpServer;
import be.celerex.polymr.model.TenantMembership;
import be.celerex.polymr.model.TenantRole;
import be.celerex.polymr.model.Workspace;
import be.celerex.polymr.tenant.TenantAccessService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;
import java.util.UUID;

@Path("/api/tenants/{tenantId}/workspaces/{workspaceId}/mcp-servers/{serverId}/oauth")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class McpOAuthResource {
	@Inject
	EntityManager entityManager;

	@Inject
	TenantAccessService tenantAccessService;

	@Inject
	McpOAuthService oauthService;

	@Context
	SecurityContext securityContext;

	@Context
	UriInfo uriInfo;

	@POST
	@Path("/start")
	@Transactional
	public ObjectNode start(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("serverId") UUID serverId,
			ObjectNode request) {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		UUID userId = requireUserId();
		Workspace workspace = requireWorkspace(tenantId, workspaceId);
		McpServer server = entityManager.find(McpServer.class, serverId);
		if (server == null || !server.workspace.id.equals(workspace.id)) {
			throw new WebApplicationException("MCP server not found", Response.Status.NOT_FOUND);
		}
		UUID authScopeValueId = request != null && request.hasNonNull("auth_scope_value_id")
			? UUID.fromString(request.get("auth_scope_value_id").asText())
			: oauthService.resolveActiveAuthScopeValue(server);
		try {
			String url = oauthService.startAuthorization(server, authScopeValueId, userId, uriInfo);
			ObjectNode response = oauthService.buildAuthView(server, authScopeValueId, userId);
			response.put("authorization_url", url);
			return response;
		}
		catch (WebApplicationException ex) {
			Response.StatusType status = ex.getResponse() != null ? ex.getResponse().getStatusInfo() : Response.Status.BAD_REQUEST;
			String message = ex.getMessage();
			throw new WebApplicationException(
				Response.status(status)
					.entity(message == null || message.isBlank() ? "Unable to start OAuth flow" : message)
					.type(MediaType.TEXT_PLAIN)
					.build()
			);
		}
	}

	@POST
	@Path("/discover")
	@Consumes(MediaType.APPLICATION_JSON)
	public ObjectNode discover(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("serverId") UUID serverId,
			ObjectNode request) {
		requireMembership(tenantId);
		requireWorkspace(tenantId, workspaceId);
		McpServer server = entityManager.find(McpServer.class, serverId);
		if (server == null || !server.workspace.id.equals(workspaceId)) {
			throw new WebApplicationException("MCP server not found", Response.Status.NOT_FOUND);
		}
		String resourceMetadataUrl = request == null ? null : readText(request.get("resource_metadata_url"));
		String wellKnownUrl = request == null ? null : readText(request.get("well_known_url"));
		String issuer = request == null ? null : readText(request.get("issuer"));
		if ((resourceMetadataUrl == null || resourceMetadataUrl.isBlank())
				&& (wellKnownUrl == null || wellKnownUrl.isBlank())
				&& (issuer == null || issuer.isBlank())) {
			return oauthService.discoverAuthDetails(server);
		}
		return oauthService.discoverAuthDetails(resourceMetadataUrl, issuer, wellKnownUrl);
	}

	private String readText(JsonNode node) {
		return node != null && node.isTextual() ? node.asText() : null;
	}

	@POST
	@Path("/clone-fallback")
	@Transactional
	public ObjectNode cloneFallback(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("serverId") UUID serverId,
			ObjectNode request) {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		UUID userId = requireUserId();
		Workspace workspace = requireWorkspace(tenantId, workspaceId);
		McpServer server = entityManager.find(McpServer.class, serverId);
		if (server == null || !server.workspace.id.equals(workspace.id)) {
			throw new WebApplicationException("MCP server not found", Response.Status.NOT_FOUND);
		}
		UUID authScopeValueId = request != null && request.hasNonNull("auth_scope_value_id")
			? UUID.fromString(request.get("auth_scope_value_id").asText())
			: oauthService.resolveActiveAuthScopeValue(server);
		oauthService.cloneFallbackToken(server, authScopeValueId, userId);
		return oauthService.buildAuthView(server, authScopeValueId, userId);
	}

	@POST
	@Path("/logout")
	@Transactional
	public ObjectNode logout(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("serverId") UUID serverId,
			ObjectNode request) {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		UUID userId = requireUserId();
		Workspace workspace = requireWorkspace(tenantId, workspaceId);
		McpServer server = entityManager.find(McpServer.class, serverId);
		if (server == null || !server.workspace.id.equals(workspace.id)) {
			throw new WebApplicationException("MCP server not found", Response.Status.NOT_FOUND);
		}
		UUID authScopeValueId = request != null && request.hasNonNull("auth_scope_value_id")
			? UUID.fromString(request.get("auth_scope_value_id").asText())
			: oauthService.resolveActiveAuthScopeValue(server);
		oauthService.logout(server, authScopeValueId, userId);
		return oauthService.buildAuthView(server, authScopeValueId, userId);
	}

	@GET
	@Path("/callback")
	@Transactional
	@Produces(MediaType.TEXT_HTML)
	@PermitAll
	public String callback(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("serverId") UUID serverId,
			@jakarta.ws.rs.QueryParam("state") String state,
			@jakarta.ws.rs.QueryParam("code") String code,
			@jakarta.ws.rs.QueryParam("error") String error) {
		requireWorkspace(tenantId, workspaceId);
		McpServer server = entityManager.find(McpServer.class, serverId);
		if (server == null || !server.workspace.id.equals(workspaceId)) {
			throw new WebApplicationException("MCP server not found", Response.Status.NOT_FOUND);
		}
		if (error != null && !error.isBlank()) {
			return "<html><body><h2>Authentication failed</h2><p>" + escape(error) + "</p>"
				+ "<p>You can close this window.</p>"
				+ "</body></html>";
		}
		oauthService.handleCallback(server, state, code);
		return "<html><body>"
			+ "<h2>Authentication complete</h2>"
			+ "<p>You can return to the app.</p>"
			+ "<script>try { if (window.opener) { window.opener.postMessage({ type: 'mcp-oauth-complete', "
			+ "serverId: '"
			+ serverId
			+ "' }, '*'); } setTimeout(function(){ window.close(); }, 500); } catch (e) {} </script>"
			+ "</body></html>";
	}

	private String escape(String value) {
		if (value == null) {
			return "";
		}
		return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
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
