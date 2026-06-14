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

package be.celerex.polymr.channel;

import be.celerex.polymr.channel.dto.ChannelMcpServerRequest;
import be.celerex.polymr.channel.dto.ChannelMcpServerResponse;
import be.celerex.polymr.channel.dto.ChannelRequest;
import be.celerex.polymr.channel.dto.ChannelResponse;
import be.celerex.polymr.model.Assistant;
import be.celerex.polymr.model.Channel;
import be.celerex.polymr.model.ChannelMcpServer;
import be.celerex.polymr.model.ChannelScope;
import be.celerex.polymr.model.ChannelTagSelection;
import be.celerex.polymr.model.McpServer;
import be.celerex.polymr.model.McpServerVisibility;
import be.celerex.polymr.model.TagValue;
import be.celerex.polymr.model.TenantMembership;
import be.celerex.polymr.model.TenantRole;
import be.celerex.polymr.model.Workspace;
import be.celerex.polymr.mcp.WorkspaceScopeService;
import be.celerex.polymr.tenant.TenantAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Path("/api/tenants/{tenantId}/workspaces/{workspaceId}/channels")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ChannelResource {
	@Inject
	EntityManager entityManager;

	@Inject
	TenantAccessService tenantAccessService;

	@Inject
	WorkspaceScopeService workspaceScopeService;

	@Inject
	ObjectMapper objectMapper;

	@Context
	SecurityContext securityContext;

	@GET
	public List<ChannelResponse> list(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId) {
		requireMembership(tenantId);
		requireWorkspace(tenantId, workspaceId);
		List<Channel> channels = entityManager.createQuery(
				"select c from Channel c where c.workspace.id = :workspaceId order by lower(c.name) asc",
				Channel.class
			)
			.setParameter("workspaceId", workspaceId)
			.getResultList();
		return channels.stream().map(this::toResponse).toList();
	}

	@POST
	@Transactional
	public ChannelResponse create(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			ChannelRequest request) {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		Workspace workspace = requireWorkspace(tenantId, workspaceId);
		validateRequest(request);
		Channel channel = new Channel();
		channel.workspace = workspace;
		applyRequest(tenantId, workspace, channel, request);
		entityManager.persist(channel);
		entityManager.flush();
		saveScopes(channel, request);
		saveTagSelections(workspace, channel, request);
		saveMcpServers(workspace, channel, request == null ? null : request.mcp_servers());
		return toResponse(channel);
	}

	@PUT
	@Path("/{channelId}")
	@Transactional
	public ChannelResponse update(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("channelId") UUID channelId,
			ChannelRequest request) {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		Workspace workspace = requireWorkspace(tenantId, workspaceId);
		Channel channel = requireChannel(workspaceId, channelId);
		validateRequest(request);
		applyRequest(tenantId, workspace, channel, request);
		saveScopes(channel, request);
		saveTagSelections(workspace, channel, request);
		saveMcpServers(workspace, channel, request == null ? null : request.mcp_servers());
		return toResponse(channel);
	}

	@DELETE
	@Path("/{channelId}")
	@Transactional
	public Response delete(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("channelId") UUID channelId) {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		requireWorkspace(tenantId, workspaceId);
		Channel channel = requireChannel(workspaceId, channelId);
		entityManager.createQuery("delete from ChannelMcpServer c where c.channel.id = :channelId")
			.setParameter("channelId", channelId)
			.executeUpdate();
		entityManager.createQuery("delete from ChannelTagSelection c where c.channel.id = :channelId")
			.setParameter("channelId", channelId)
			.executeUpdate();
		entityManager.createQuery("delete from ChannelScope c where c.channel.id = :channelId")
			.setParameter("channelId", channelId)
			.executeUpdate();
		entityManager.createQuery("update Session s set s.channel = null where s.channel.id = :channelId")
			.setParameter("channelId", channelId)
			.executeUpdate();
		entityManager.remove(channel);
		return Response.noContent().build();
	}

	private void validateRequest(ChannelRequest request) {
		if (request == null || request.name() == null || request.name().isBlank()) {
			throw new WebApplicationException("Name is required", Response.Status.BAD_REQUEST);
		}
	}

	private void applyRequest(UUID tenantId, Workspace workspace, Channel channel, ChannelRequest request) {
		channel.name = request.name().trim();
		channel.description = request.description();
		channel.prompt = request.prompt();
		channel.assistant = resolveAssistant(tenantId, request == null ? null : request.assistant_id());
		if (channel.assistant != null && !channel.assistant.tenant.id.equals(tenantId)) {
			throw new WebApplicationException("Assistant not found", Response.Status.NOT_FOUND);
		}
		if (!channel.workspace.id.equals(workspace.id)) {
			channel.workspace = workspace;
		}
	}

	private Assistant resolveAssistant(UUID tenantId, UUID assistantId) {
		if (assistantId == null) {
			return null;
		}
		Assistant assistant = entityManager.find(Assistant.class, assistantId);
		if (assistant == null || !assistant.tenant.id.equals(tenantId)) {
			throw new WebApplicationException("Assistant not found", Response.Status.NOT_FOUND);
		}
		return assistant;
	}

	private void saveScopes(Channel channel, ChannelRequest request) {
		ChannelScope scope = entityManager.createQuery("select c from ChannelScope c where c.channel.id = :channelId", ChannelScope.class)
			.setParameter("channelId", channel.id)
			.getResultStream()
			.findFirst()
			.orElse(null);
		if (scope == null) {
			scope = new ChannelScope();
			scope.channel = channel;
			entityManager.persist(scope);
		}
		ArrayNode allow = objectMapper.createArrayNode();
		for (String value : normalizeScopes(request == null ? null : request.allow_scopes())) {
			allow.add(value);
		}
		ArrayNode deny = objectMapper.createArrayNode();
		for (String value : normalizeScopes(request == null ? null : request.deny_scopes())) {
			deny.add(value);
		}
		scope.allowScopes = allow;
		scope.denyScopes = deny;
	}

	private void saveTagSelections(Workspace workspace, Channel channel, ChannelRequest request) {
		entityManager.createQuery("delete from ChannelTagSelection c where c.channel.id = :channelId")
			.setParameter("channelId", channel.id)
			.executeUpdate();
		Map<UUID, TagValue> tagsById = new HashMap<>();
		Set<UUID> categoryIds = new HashSet<>();
		List<UUID> tagIds = request == null || request.tag_ids() == null ? List.of() : request.tag_ids();
		if (!tagIds.isEmpty()) {
			List<TagValue> tags = entityManager.createQuery("select t from TagValue t join fetch t.category where t.id in :tagIds", TagValue.class)
				.setParameter("tagIds", tagIds)
				.getResultList();
			for (TagValue tag : tags) {
				if (tag.category == null
						|| tag.category.workspace == null
						|| !tag.category.workspace.id.equals(workspace.id)
						|| tag.deletedAt != null) {
					throw new WebApplicationException("Tag not found", Response.Status.NOT_FOUND);
				}
				if (!tagsById.containsKey(tag.id)) {
					tagsById.put(tag.id, tag);
				}
			}
			if (tagsById.size() != new HashSet<>(tagIds).size()) {
				throw new WebApplicationException("Tag not found", Response.Status.NOT_FOUND);
			}
		}
		for (UUID tagId : tagIds) {
			TagValue tag = tagsById.get(tagId);
			if (tag == null || tag.category == null || !categoryIds.add(tag.category.id)) {
				continue;
			}
			ChannelTagSelection selection = new ChannelTagSelection();
			selection.channel = channel;
			selection.category = tag.category;
			selection.value = tag;
			entityManager.persist(selection);
		}
	}

	private void saveMcpServers(Workspace workspace, Channel channel, List<ChannelMcpServerRequest> requests) {
		entityManager.createQuery("delete from ChannelMcpServer c where c.channel.id = :channelId")
			.setParameter("channelId", channel.id)
			.executeUpdate();
		if (requests == null || requests.isEmpty()) {
			return;
		}
		Set<UUID> seen = new HashSet<>();
		for (ChannelMcpServerRequest request : requests) {
			if (request == null || request.mcp_server_id() == null || !seen.add(request.mcp_server_id())) {
				continue;
			}
			McpServer server = entityManager.find(McpServer.class, request.mcp_server_id());
			if (server == null || server.workspace == null || !server.workspace.id.equals(workspace.id)) {
				throw new WebApplicationException("MCP server not found", Response.Status.NOT_FOUND);
			}
			McpServerVisibility visibility = request.visibility();
			if (visibility == null) {
				continue;
			}
			ChannelMcpServer item = new ChannelMcpServer();
			item.channel = channel;
			item.mcpServer = server;
			item.enabledByDefault = visibility == McpServerVisibility.VISIBLE;
			item.availableOnRequest = visibility == McpServerVisibility.AVAILABLE;
			entityManager.persist(item);
		}
	}

	private ChannelResponse toResponse(Channel channel) {
		UUID assistantId = channel.assistant == null ? null : channel.assistant.id;
		ChannelScope scope = entityManager.createQuery("select c from ChannelScope c where c.channel.id = :channelId", ChannelScope.class)
			.setParameter("channelId", channel.id)
			.getResultStream()
			.findFirst()
			.orElse(null);
		List<String> allowScopes = normalizeScopes(
			scope == null || scope.allowScopes == null
				? List.of()
				: objectMapper.convertValue(scope.allowScopes, List.class)
		);
		List<String> denyScopes = normalizeScopes(
			scope == null || scope.denyScopes == null
				? List.of()
				: objectMapper.convertValue(scope.denyScopes, List.class)
		);
		List<UUID> tagIds = entityManager.createQuery(
				"select c.value.id from ChannelTagSelection c where c.channel.id = "
					+ ":channelId and c.value is not null",
				UUID.class
			)
			.setParameter("channelId", channel.id)
			.getResultList();
		List<ChannelMcpServerResponse> mcpServers = entityManager.createQuery("select c from ChannelMcpServer c where c.channel.id = :channelId", ChannelMcpServer.class)
			.setParameter("channelId", channel.id)
			.getResultList()
			.stream()
			.map(
				(item) -> new ChannelMcpServerResponse(
					item.mcpServer.id,
					item.enabledByDefault
						? McpServerVisibility.VISIBLE
						: item.availableOnRequest ? McpServerVisibility.AVAILABLE : McpServerVisibility.HIDDEN
				)
			)
			.toList();
		return new ChannelResponse(
			channel.id,
			channel.name,
			channel.description,
			assistantId,
			channel.prompt,
			allowScopes,
			denyScopes,
			tagIds,
			mcpServers
		);
	}

	private List<String> normalizeScopes(List<String> scopes) {
		if (scopes == null || scopes.isEmpty()) {
			return List.of();
		}
		List<String> normalized = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (String scope : scopes) {
			if (scope == null) {
				continue;
			}
			String trimmed = scope.trim();
			if (trimmed.isEmpty() || !seen.add(trimmed)) {
				continue;
			}
			normalized.add(trimmed);
		}
		return normalized;
	}

	private Workspace requireWorkspace(UUID tenantId, UUID workspaceId) {
		Workspace workspace = entityManager.find(Workspace.class, workspaceId);
		if (workspace == null || !workspace.tenant.id.equals(tenantId)) {
			throw new WebApplicationException("Workspace not found", Response.Status.NOT_FOUND);
		}
		return workspace;
	}

	private Channel requireChannel(UUID workspaceId, UUID channelId) {
		Channel channel = entityManager.find(Channel.class, channelId);
		if (channel == null || channel.workspace == null || !channel.workspace.id.equals(workspaceId)) {
			throw new WebApplicationException("Channel not found", Response.Status.NOT_FOUND);
		}
		return channel;
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
