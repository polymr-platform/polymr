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

import be.celerex.polymr.mcp.dto.McpServerRequest;
import be.celerex.polymr.mcp.dto.McpServerResponse;
import be.celerex.polymr.mcp.dto.McpServerPolicyResponse;
import be.celerex.polymr.mcp.dto.McpServerApplicationResponse;
import be.celerex.polymr.mcp.dto.McpServerApplicationUpdateRequest;
import be.celerex.polymr.mcp.dto.McpServerImportToolRequest;
import be.celerex.polymr.mcp.dto.McpServerResourceReadRequest;
import be.celerex.polymr.mcp.dto.McpServerToolCallRequest;
import be.celerex.polymr.mcp.dto.McpServerToolResponse;
import be.celerex.polymr.mcp.dto.McpServerToolBulkUpdateRequest;
import be.celerex.polymr.mcp.dto.McpServerToolUpdateRequest;
import be.celerex.polymr.model.McpProtocol;
import be.celerex.polymr.model.McpServer;
import be.celerex.polymr.model.McpServerApplication;
import be.celerex.polymr.model.McpServerPolicy;
import be.celerex.polymr.model.McpServerVisibility;
import be.celerex.polymr.model.McpServerTool;
import be.celerex.polymr.model.TenantMembership;
import be.celerex.polymr.model.TenantRole;
import be.celerex.polymr.model.Workspace;
import be.celerex.polymr.security.SecretCipher;
import be.celerex.polymr.ws.WorkspaceSocketEvent;
import be.celerex.polymr.ws.WorkspaceSocketManager;
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
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import org.jboss.logging.Logger;

@Path("/api/tenants/{tenantId}/workspaces/{workspaceId}/mcp-servers")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class McpServerResource {
	private static final Logger LOGGER = Logger.getLogger(McpServerResource.class);
	@Inject
	EntityManager entityManager;

	@Inject
	TenantAccessService tenantAccessService;

	@Inject
	WorkspaceSocketManager socketManager;

	@Inject
	ObjectMapper objectMapper;

	@Inject
	SecretCipher secretCipher;

	@Context
	SecurityContext securityContext;

	@Inject
	McpProbeService probeService;

	@Inject
	McpToolCatalogService toolCatalogService;

	@Inject
	McpApplicationCatalogService applicationCatalogService;

	@Inject
	WorkspaceMcpRegistry mcpRegistry;

	@Inject
	McpOAuthService oauthService;

	@Inject
	VirtualMcpService virtualMcpService;

	@GET
	public List<McpServerResponse> list(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId) {
		requireMembership(tenantId);
		UUID userId = requireUserId();
		Workspace workspace = requireWorkspace(tenantId, workspaceId);
		List<McpServer> servers = entityManager.createQuery(
				"select s from McpServer s where s.workspace.id = :workspaceId and s.internal = false",
				McpServer.class
			)
			.setParameter("workspaceId", workspace.id)
			.getResultList();
		Map<UUID, List<McpServerPolicyResponse>> policiesByServer = loadPoliciesByServer(workspaceId);
		return servers.stream()
			.map(
				server -> toResponse(
					server,
					policiesByServer.getOrDefault(server.id, List.of()),
					oauthService.resolveActiveAuthScopeValue(server),
					userId
				)
			)
			.collect(Collectors.toList());
	}

	@POST
	@Transactional
	public McpServerResponse create(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			McpServerRequest request) {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		Workspace workspace = requireWorkspace(tenantId, workspaceId);
		if (request != null && "polymr_design".equalsIgnoreCase(request.virtual_type())) {
			throw new WebApplicationException("Virtual design server is internal", Response.Status.BAD_REQUEST);
		}
		validateRequest(request);
		McpServer server = new McpServer();
		server.workspace = workspace;
		applyRequest(server, request);
		entityManager.persist(server);
		applyImportedTools(server, request == null ? null : request.tools());
		if (server.protocol == McpProtocol.VIRTUAL) {
			toolCatalogService.refreshTools(server, virtualMcpService.listTools(server));
		}
		UUID authScopeValueId = oauthService.resolveActiveAuthScopeValue(server);
		UUID userId = requireUserId();
		McpServerResponse response = toResponse(server, List.of(), authScopeValueId, userId);
		socketManager.broadcast(workspace.id, new WorkspaceSocketEvent("mcp.update", workspace.id, null, response));
		return response;
	}

	@PUT
	@Path("/{serverId}")
	@Transactional
	public McpServerResponse update(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("serverId") UUID serverId,
			McpServerRequest request) {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		requireWorkspace(tenantId, workspaceId);
		McpServer server = entityManager.find(McpServer.class, serverId);
		if (server == null || !server.workspace.id.equals(workspaceId)) {
			throw new WebApplicationException("MCP server not found", Response.Status.NOT_FOUND);
		}
		if (request != null && "polymr_design".equalsIgnoreCase(request.virtual_type())) {
			throw new WebApplicationException("Virtual design server is internal", Response.Status.BAD_REQUEST);
		}
		validateRequest(request);
		applyRequest(server, request);
		applyImportedTools(server, request == null ? null : request.tools());
		if (server.protocol == McpProtocol.VIRTUAL) {
			toolCatalogService.refreshTools(server, virtualMcpService.listTools(server));
		}
		UUID authScopeValueId = oauthService.resolveActiveAuthScopeValue(server);
		UUID userId = requireUserId();
		McpServerResponse response = toResponse(server, loadPoliciesForServer(server.id), authScopeValueId, userId);
		mcpRegistry.publishServerUpdated(workspaceId, server.id);
		socketManager.broadcast(workspaceId, new WorkspaceSocketEvent("mcp.update", workspaceId, null, response));
		return response;
	}

	@DELETE
	@Path("/{serverId}")
	@Transactional
	public void delete(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("serverId") UUID serverId) {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		requireWorkspace(tenantId, workspaceId);
		McpServer server = entityManager.find(McpServer.class, serverId);
		if (server == null || !server.workspace.id.equals(workspaceId)) {
			throw new WebApplicationException("MCP server not found", Response.Status.NOT_FOUND);
		}
		entityManager.createQuery("update McpCallLog l set l.mcpServer = null where l.mcpServer.id = :serverId")
			.setParameter("serverId", serverId)
			.executeUpdate();
		entityManager.createQuery("delete from ChannelMcpServer c where c.mcpServer.id = :serverId")
			.setParameter("serverId", serverId)
			.executeUpdate();
		entityManager.createQuery("delete from McpServerTool t where t.mcpServer.id = :serverId")
			.setParameter("serverId", serverId)
			.executeUpdate();
		entityManager.createQuery("delete from McpServerApplication a where a.mcpServer.id = :serverId")
			.setParameter("serverId", serverId)
			.executeUpdate();
		entityManager.createQuery("update WorkspaceAssetBundle b set b.mcpServer = null where b.mcpServer.id = :serverId")
			.setParameter("serverId", serverId)
			.executeUpdate();
		entityManager.remove(server);
		socketManager.broadcast(workspaceId, new WorkspaceSocketEvent("mcp.update", workspaceId, null, null));
	}

	@POST
	@Path("/{serverId}/probe")
	@Transactional
	public McpServerResponse probe(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("serverId") UUID serverId) throws Exception {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		requireWorkspace(tenantId, workspaceId);
		McpServer server = entityManager.find(McpServer.class, serverId);
		if (server == null || !server.workspace.id.equals(workspaceId)) {
			throw new WebApplicationException("MCP server not found", Response.Status.NOT_FOUND);
		}
		McpProbeService.ProbeResult result = probeService.probe(server);
		server.configSchema = result.configSchema();
		server.supportsDynamicConfig = server.configSchema != null && !server.configSchema.isNull();
		server.allowPolicy = result.policySupported();
		toolCatalogService.refreshTools(server, result.tools());
		applicationCatalogService.refreshApplications(server, result.resources());
		UUID authScopeValueId = oauthService.resolveActiveAuthScopeValue(server);
		UUID userId = requireUserId();
		McpServerResponse response = toResponse(server, loadPoliciesForServer(server.id), authScopeValueId, userId);
		mcpRegistry.publishServerUpdated(workspaceId, server.id);
		socketManager.broadcast(workspaceId, new WorkspaceSocketEvent("mcp.update", workspaceId, null, response));
		return response;
	}

	@GET
	@Path("/{serverId}/applications")
	public List<McpServerApplicationResponse> listApplications(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("serverId") UUID serverId) {
		requireMembership(tenantId);
		requireWorkspace(tenantId, workspaceId);
		McpServer server = entityManager.find(McpServer.class, serverId);
		if (server == null || !server.workspace.id.equals(workspaceId)) {
			throw new WebApplicationException("MCP server not found", Response.Status.NOT_FOUND);
		}
		List<McpServerApplication> apps = entityManager.createQuery(
				"select a from McpServerApplication a where a.mcpServer.id = :serverId",
				McpServerApplication.class
			)
			.setParameter("serverId", serverId)
			.getResultList();
		return apps.stream().map(this::toApplicationResponse).collect(Collectors.toList());
	}

	@GET
	@Path("/{serverId}/definition")
	public JsonNode getDefinition(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("serverId") UUID serverId) throws Exception {
		requireMembership(tenantId);
		requireWorkspace(tenantId, workspaceId);
		McpServer server = entityManager.find(McpServer.class, serverId);
		if (server == null || !server.workspace.id.equals(workspaceId)) {
			throw new WebApplicationException("MCP server not found", Response.Status.NOT_FOUND);
		}
		McpProbeService.ProbeResult result = probeService.probe(server);
		var payload = objectMapper.createObjectNode();
		payload.set("initialize", result.initialize());
		payload.set("tools", result.tools());
		payload.set("resources", result.resources());
		payload.set("config_schema", result.configSchema());
		payload.put("policy_supported", result.policySupported());
		return payload;
	}

	@PUT
	@Path("/{serverId}/applications/{applicationId}")
	@Transactional
	public McpServerApplicationResponse updateApplication(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("serverId") UUID serverId,
			@PathParam("applicationId") UUID applicationId,
			McpServerApplicationUpdateRequest request) {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		requireWorkspace(tenantId, workspaceId);
		McpServer server = entityManager.find(McpServer.class, serverId);
		if (server == null || !server.workspace.id.equals(workspaceId)) {
			throw new WebApplicationException("MCP server not found", Response.Status.NOT_FOUND);
		}
		McpServerApplication app = entityManager.find(McpServerApplication.class, applicationId);
		if (app == null || app.mcpServer == null || !app.mcpServer.id.equals(serverId)) {
			throw new WebApplicationException("Application not found", Response.Status.NOT_FOUND);
		}
		if (request != null) {
			if (request.display_name() != null) {
				String name = request.display_name().trim();
				app.displayName = name.isBlank() ? null : name;
			}
			if (request.icon_svg() != null) {
				String icon = request.icon_svg().trim();
				app.iconSvg = icon.isBlank() ? null : icon;
			}
			if (request.disabled() != null) {
				app.disabled = request.disabled();
			}
		}
		McpServerApplicationResponse response = toApplicationResponse(app);
		socketManager.broadcast(workspaceId, new WorkspaceSocketEvent("mcp.update", workspaceId, null, null));
		return response;
	}

	@GET
	@Path("/{serverId}/tools")
	public List<McpServerToolResponse> listTools(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("serverId") UUID serverId) {
		requireMembership(tenantId);
		requireWorkspace(tenantId, workspaceId);
		McpServer server = entityManager.find(McpServer.class, serverId);
		if (server == null || !server.workspace.id.equals(workspaceId)) {
			throw new WebApplicationException("MCP server not found", Response.Status.NOT_FOUND);
		}
		if (server.protocol == McpProtocol.VIRTUAL) {
			toolCatalogService.refreshTools(server, virtualMcpService.listTools(server));
		}
		List<McpServerTool> tools = entityManager.createQuery(
				"select t from McpServerTool t where t.mcpServer.id = :serverId and t.deleted = false",
				McpServerTool.class
			)
			.setParameter("serverId", serverId)
			.getResultList();
		return tools.stream()
			.map(
				tool -> new McpServerToolResponse(
					tool.id,
					tool.toolName,
					tool.toolAlias,
					tool.description,
					readStringArray(tool.customScopes),
					readStringArray(tool.scopes),
					tool.dynamicScopes,
					tool.disabled
				)
			)
			.collect(Collectors.toList());
	}

	@PUT
	@Path("/{serverId}/tools/{toolId}")
	@Transactional
	public McpServerToolResponse updateToolAlias(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("serverId") UUID serverId,
			@PathParam("toolId") UUID toolId,
			McpServerToolUpdateRequest request) {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		requireWorkspace(tenantId, workspaceId);
		McpServer server = entityManager.find(McpServer.class, serverId);
		if (server == null || !server.workspace.id.equals(workspaceId)) {
			throw new WebApplicationException("MCP server not found", Response.Status.NOT_FOUND);
		}
		McpServerTool tool = entityManager.find(McpServerTool.class, toolId);
		if (tool == null || tool.mcpServer == null || !tool.mcpServer.id.equals(serverId)) {
			throw new WebApplicationException("Tool not found", Response.Status.NOT_FOUND);
		}
		String alias = request == null ? null : request.tool_alias();
		tool.toolAlias = alias == null || alias.isBlank() ? null : alias.trim();
		if (request != null && request.custom_scopes() != null) {
			tool.customScopes = objectMapper.valueToTree(request.custom_scopes());
		}
		if (request != null && request.disabled() != null) {
			tool.disabled = request.disabled();
		}
		return new McpServerToolResponse(
			tool.id,
			tool.toolName,
			tool.toolAlias,
			tool.description,
			readStringArray(tool.customScopes),
			readStringArray(tool.scopes),
			tool.dynamicScopes,
			tool.disabled
		);
	}

	@PUT
	@Path("/{serverId}/tools")
	@Transactional
	public List<McpServerToolResponse> updateTools(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("serverId") UUID serverId,
			List<McpServerToolBulkUpdateRequest> requests) {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		requireWorkspace(tenantId, workspaceId);
		McpServer server = entityManager.find(McpServer.class, serverId);
		if (server == null || !server.workspace.id.equals(workspaceId)) {
			throw new WebApplicationException("MCP server not found", Response.Status.NOT_FOUND);
		}
		if (requests == null || requests.isEmpty()) {
			return List.of();
		}
		Set<UUID> toolIds = requests.stream()
			.map(McpServerToolBulkUpdateRequest::tool_id)
			.filter(id -> id != null)
			.collect(Collectors.toSet());
		if (toolIds.isEmpty()) {
			return List.of();
		}
		List<McpServerTool> tools = entityManager.createQuery(
				"select t from McpServerTool t where t.mcpServer.id = :serverId and t.id in "
					+ ":toolIds and t.deleted = false",
				McpServerTool.class
			)
			.setParameter("serverId", serverId)
			.setParameter("toolIds", toolIds)
			.getResultList();
		Map<UUID, McpServerTool> toolById = tools.stream()
			.collect(Collectors.toMap(tool -> tool.id, tool -> tool));
		for (McpServerToolBulkUpdateRequest request : requests) {
			if (request == null || request.tool_id() == null) {
				continue;
			}
			McpServerTool tool = toolById.get(request.tool_id());
			if (tool == null) {
				continue;
			}
			String alias = request.tool_alias();
			tool.toolAlias = alias == null || alias.isBlank() ? null : alias.trim();
			if (request.custom_scopes() != null) {
				tool.customScopes = objectMapper.valueToTree(request.custom_scopes());
			}
			if (request.disabled() != null) {
				tool.disabled = request.disabled();
			}
		}
		return tools.stream()
			.map(
				tool -> new McpServerToolResponse(
					tool.id,
					tool.toolName,
					tool.toolAlias,
					tool.description,
					readStringArray(tool.customScopes),
					readStringArray(tool.scopes),
					tool.dynamicScopes,
					tool.disabled
				)
			)
			.collect(Collectors.toList());
	}

	@POST
	@Path("/{serverId}/tools/refresh")
	@Transactional
	public List<McpServerToolResponse> refreshTools(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("serverId") UUID serverId) {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		requireWorkspace(tenantId, workspaceId);
		McpServer server = entityManager.find(McpServer.class, serverId);
		if (server == null || !server.workspace.id.equals(workspaceId)) {
			throw new WebApplicationException("MCP server not found", Response.Status.NOT_FOUND);
		}
		try {
			mcpRegistry.refreshTools(workspaceId, serverId);
		}
		catch (RuntimeException ex) {
			Throwable cause = ex.getCause() == null ? ex : ex.getCause();
			if (cause instanceof McpAuthRequiredException) {
				throw new WebApplicationException("Authentication required for MCP server", Response.Status.CONFLICT);
			}
			String message = cause.getMessage();
			LOGGER.warnf(cause, "Failed to refresh MCP tools for server %s", serverId);
			throw new WebApplicationException(
				message == null || message.isBlank() ? "Failed to refresh MCP tools" : message,
				Response.Status.INTERNAL_SERVER_ERROR
			);
		}
		List<McpServerTool> tools = entityManager.createQuery(
				"select t from McpServerTool t where t.mcpServer.id = :serverId and t.deleted = false",
				McpServerTool.class
			)
			.setParameter("serverId", serverId)
			.getResultList();
		return tools.stream()
			.map(
				tool -> new McpServerToolResponse(
					tool.id,
					tool.toolName,
					tool.toolAlias,
					tool.description,
					readStringArray(tool.customScopes),
					readStringArray(tool.scopes),
					tool.dynamicScopes,
					tool.disabled
				)
			)
			.collect(Collectors.toList());
	}

	@POST
	@Path("/{serverId}/tools/call")
	public JsonNode callTool(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("serverId") UUID serverId,
			McpServerToolCallRequest request) {
		requireMembership(tenantId);
		UUID userId = requireUserId();
		requireWorkspace(tenantId, workspaceId);
		McpServer server = entityManager.find(McpServer.class, serverId);
		if (server == null || !server.workspace.id.equals(workspaceId)) {
			throw new WebApplicationException("MCP server not found", Response.Status.NOT_FOUND);
		}
		if (request == null || request.tool_name() == null || request.tool_name().isBlank()) {
			throw new WebApplicationException("Tool name is required", Response.Status.BAD_REQUEST);
		}
		return mcpRegistry.call(workspaceId, serverId, request.tool_name(), request.arguments(), request.meta(), null, userId);
	}

	@POST
	@Path("/{serverId}/resources/read")
	public JsonNode readResource(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("serverId") UUID serverId,
			McpServerResourceReadRequest request) {
		requireMembership(tenantId);
		requireWorkspace(tenantId, workspaceId);
		McpServer server = entityManager.find(McpServer.class, serverId);
		if (server == null || !server.workspace.id.equals(workspaceId)) {
			throw new WebApplicationException("MCP server not found", Response.Status.NOT_FOUND);
		}
		if (request == null || request.uri() == null || request.uri().isBlank()) {
			throw new WebApplicationException("Resource URI is required", Response.Status.BAD_REQUEST);
		}
		return mcpRegistry.readResource(workspaceId, serverId, request.uri());
	}

	private List<String> readStringArray(JsonNode node) {
		if (node == null || node.isNull() || !node.isArray()) {
			return List.of();
		}
		List<String> list = new java.util.ArrayList<>();
		node.forEach(entry -> {
			if (entry.isTextual()) {
				list.add(entry.asText());
			}
		});
		return list;
	}

	private void validateRequest(McpServerRequest request) {
		if (request == null || request.name() == null || request.name().isBlank()) {
			throw new WebApplicationException("Name is required", Response.Status.BAD_REQUEST);
		}
		if (request.protocol() == null) {
			throw new WebApplicationException("Protocol is required", Response.Status.BAD_REQUEST);
		}
		if (request.protocol() == McpProtocol.STDIO) {
			if (request.command() == null || request.command().isBlank()) {
				throw new WebApplicationException("Command is required", Response.Status.BAD_REQUEST);
			}
		}
		if (request.protocol() == McpProtocol.VIRTUAL) {
			if (request.virtual_type() == null || request.virtual_type().isBlank()) {
				throw new WebApplicationException("Virtual server type is required", Response.Status.BAD_REQUEST);
			}
		}
		if (request.protocol() == McpProtocol.SSE || request.protocol() == McpProtocol.STREAMABLE_HTTP) {
			if (request.http_url() == null || request.http_url().isBlank()) {
				throw new WebApplicationException("HTTP URL is required", Response.Status.BAD_REQUEST);
			}
			if (Boolean.TRUE.equals(request.ssh_enabled())) {
				JsonNode sshTunnel = request.ssh_tunnel();
				if (sshTunnel == null || !sshTunnel.isObject()) {
					throw new WebApplicationException("SSH tunnel configuration is required", Response.Status.BAD_REQUEST);
				}
				JsonNode host = sshTunnel.get("server");
				JsonNode port = sshTunnel.get("port");
				if (host == null || host.asText().isBlank()) {
					throw new WebApplicationException("SSH server is required", Response.Status.BAD_REQUEST);
				}
				if (port == null || !port.canConvertToInt() || port.asInt() <= 0) {
					throw new WebApplicationException("SSH port is required", Response.Status.BAD_REQUEST);
				}
			}
		}
	}

	private void applyRequest(McpServer server, McpServerRequest request) {
		server.name = request.name().trim();
		server.description = request.description();
		server.protocol = request.protocol();
		server.framing = request.framing();
		server.command = request.command();
		server.cwd = request.cwd();
		server.httpUrl = request.http_url();
		server.virtualType = request.virtual_type();
		server.headers = request.headers();
		server.environment = request.environment();
		if (request.ssh_enabled() != null) {
			server.sshEnabled = request.ssh_enabled();
		}
		if (request.ssh_tunnel() != null) {
			server.sshTunnel = request.ssh_tunnel();
		}
		if (request.allow_policy() != null) {
			server.allowPolicy = request.allow_policy();
		}
		if (request.visibility() != null) {
			McpServerVisibility visibility = parseVisibility(request.visibility());
			if (visibility != null) {
				server.visibility = visibility;
			}
		}
		if (request.custom_instructions() != null) {
			server.customInstructions = request.custom_instructions();
		}
		if (request.instructions() != null) {
			if (server.customInstructions) {
				server.instructions = request.instructions();
			}
			else if (request.instructions().isBlank()) {
				server.instructions = null;
			}
		}
		if (request.prompt() != null) {
			server.prompt = request.prompt().isBlank() ? null : request.prompt();
		}
		if (request.tool_name_prefix() != null) {
			server.toolNamePrefix = request.tool_name_prefix().isBlank() ? null : request.tool_name_prefix().trim();
		}
		if (request.config_schema() != null) {
			server.configSchema = request.config_schema();
		}
		if (request.configuration_json() != null) {
			server.configurationJson = request.configuration_json();
		}
		if (request.oauth_enabled() != null) {
			server.oauthEnabled = request.oauth_enabled();
		}
		if (request.auth() != null) {
			oauthService.updateServerAuthConfig(server, request.auth());
		}
		server.supportsDynamicConfig = server.configSchema != null && !server.configSchema.isNull();
	}

	private void applyImportedTools(McpServer server, List<McpServerImportToolRequest> requests) {
		if (server == null || requests == null || requests.isEmpty()) {
			return;
		}
		Map<String, McpServerTool> existing = entityManager.createQuery("select t from McpServerTool t where t.mcpServer.id = :serverId", McpServerTool.class)
			.setParameter("serverId", server.id)
			.getResultList()
			.stream()
			.collect(Collectors.toMap(tool -> tool.toolName, tool -> tool, (left, right) -> left));
		for (McpServerImportToolRequest request : requests) {
			if (request == null) {
				continue;
			}
			String toolName = request.tool_name();
			if (toolName == null || toolName.isBlank()) {
				continue;
			}
			McpServerTool tool = existing.get(toolName);
			if (tool == null) {
				tool = new McpServerTool();
				tool.mcpServer = server;
				tool.toolName = toolName.trim();
				tool.deleted = false;
				entityManager.persist(tool);
				existing.put(tool.toolName, tool);
			}
			if (request.description() != null) {
				tool.description = request.description();
			}
			if (request.scopes() != null) {
				tool.scopes = objectMapper.valueToTree(request.scopes());
			}
			if (request.dynamic_scopes() != null) {
				tool.dynamicScopes = request.dynamic_scopes();
			}
			String alias = request.tool_alias();
			tool.toolAlias = alias == null || alias.isBlank() ? null : alias.trim();
			if (request.custom_scopes() != null) {
				tool.customScopes = objectMapper.valueToTree(request.custom_scopes());
			}
			if (request.disabled() != null) {
				tool.disabled = request.disabled();
			}
			tool.deleted = false;
		}
	}

	private McpServerResponse toResponse(
			McpServer server,
			List<McpServerPolicyResponse> policies,
			UUID authScopeValueId,
			UUID userId) {
		return new McpServerResponse(
			server.id,
			server.name,
			server.description,
			server.protocol,
			server.framing,
			server.command,
			server.cwd,
			server.httpUrl,
			server.virtualType,
			server.headers,
			server.environment,
			server.sshEnabled,
			server.sshTunnel,
			server.supportsDynamicConfig,
			server.allowPolicy,
			server.oauthEnabled,
			server.visibility == null ? McpServerVisibility.VISIBLE.name() : server.visibility.name(),
			server.instructions,
			server.prompt,
			server.toolNamePrefix,
			server.customInstructions,
			server.configSchema,
			server.configurationJson,
			oauthService.buildAuthView(server, authScopeValueId, userId),
			policies
		);
	}

	private McpServerVisibility parseVisibility(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return McpServerVisibility.valueOf(value.trim().toUpperCase());
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private McpServerApplicationResponse toApplicationResponse(McpServerApplication app) {
		return new McpServerApplicationResponse(app.id, app.appUri, app.appName, app.displayName, app.iconSvg, app.disabled);
	}

	private Map<UUID, List<McpServerPolicyResponse>> loadPoliciesByServer(UUID workspaceId) {
		List<McpServerPolicy> policies = entityManager.createQuery(
				"select p from McpServerPolicy p join fetch p.tag where p.mcpServer.workspace.id = :workspaceId",
				McpServerPolicy.class
			)
			.setParameter("workspaceId", workspaceId)
			.getResultList();
		Map<UUID, List<McpServerPolicyResponse>> result = new HashMap<>();
		for (McpServerPolicy policy : policies) {
			result.computeIfAbsent(policy.mcpServer.id, key -> new java.util.ArrayList<>())
				.add(toPolicyResponse(policy));
		}
		return result;
	}

	private List<McpServerPolicyResponse> loadPoliciesForServer(UUID serverId) {
		return entityManager.createQuery(
				"select p from McpServerPolicy p join fetch p.tag where p.mcpServer.id = :serverId",
				McpServerPolicy.class
			)
			.setParameter("serverId", serverId)
			.getResultList()
			.stream()
			.map(this::toPolicyResponse)
			.collect(Collectors.toList());
	}

	private McpServerPolicyResponse toPolicyResponse(McpServerPolicy policy) {
		return new McpServerPolicyResponse(
			policy.id,
			policy.tag == null ? null : policy.tag.id,
			policy.tag == null || policy.tag.category == null ? null : policy.tag.category.id,
			policy.tag == null || policy.tag.category == null ? null : policy.tag.category.name,
			policy.tag == null || policy.tag.category == null ? null : policy.tag.category.slug,
			policy.tag == null ? null : policy.tag.name,
			policy.tag == null ? null : policy.tag.slug,
			policy.policyJson
		);
	}

	private JsonNode normalizeAuthConfig(JsonNode input, JsonNode existing) {
		if (input == null || !input.isObject()) {
			return existing;
		}
		ObjectNode incoming = (ObjectNode) input;
		ObjectNode prior = existing != null && existing.isObject() ? (ObjectNode) existing : objectMapper.createObjectNode();
		ObjectNode result = objectMapper.createObjectNode();
		incoming.fields()
			.forEachRemaining(
				entry -> {
					if ("client_secret".equals(entry.getKey())) {
						return;
					}
					result.set(entry.getKey(), entry.getValue());
				}
			);
		JsonNode secretNode = incoming.get("client_secret");
		boolean clearSecret = secretNode != null && secretNode.isTextual() && secretNode.asText().isBlank();
		boolean hasSecretValue = secretNode != null && secretNode.isTextual() && !secretNode.asText().isBlank();
		if (hasSecretValue) {
			SecretCipher.EncryptedSecret encrypted = secretCipher.encrypt(secretNode.asText());
			ObjectNode secretJson = objectMapper.createObjectNode();
			secretJson.put("ciphertext", encrypted.ciphertext());
			secretJson.put("nonce", encrypted.nonce());
			secretJson.put("hint", maskSecret(secretNode.asText()));
			result.set("client_secret", secretJson);
		}
		else if (!clearSecret && prior.has("client_secret")) {
			result.set("client_secret", prior.get("client_secret"));
		}
		return result;
	}

	private String maskSecret(String value) {
		if (value == null || value.length() <= 8) {
			return "***";
		}
		return value.substring(0, 4) + "..." + value.substring(value.length() - 4);
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
