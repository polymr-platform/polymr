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

import be.celerex.polymr.mcp.dto.McpOauthProviderRequest;
import be.celerex.polymr.mcp.dto.McpOauthProviderResponse;
import be.celerex.polymr.model.McpOauthClient;
import be.celerex.polymr.model.McpOauthProvider;
import be.celerex.polymr.model.McpOauthToken;
import be.celerex.polymr.model.TagCategory;
import be.celerex.polymr.model.TenantMembership;
import be.celerex.polymr.model.TenantRole;
import be.celerex.polymr.model.Workspace;
import be.celerex.polymr.tenant.TenantAccessService;
import be.celerex.polymr.security.SecretCipher;
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

@Path("/api/tenants/{tenantId}/workspaces/{workspaceId}/mcp-oauth-providers")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class McpOauthProviderResource {
	@Inject
	EntityManager entityManager;

	@Inject
	TenantAccessService tenantAccessService;

	@Context
	SecurityContext securityContext;

	@Inject
	SecretCipher secretCipher;

	@GET
	public List<McpOauthProviderResponse> list(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId) {
		requireMembership(tenantId);
		Workspace workspace = requireWorkspace(tenantId, workspaceId);
		List<McpOauthProvider> providers = entityManager.createQuery("select p from McpOauthProvider p where p.workspace.id = :workspaceId", McpOauthProvider.class)
			.setParameter("workspaceId", workspace.id)
			.getResultList();
		return providers.stream().map(this::toResponse).collect(Collectors.toList());
	}

	@POST
	@Transactional
	public McpOauthProviderResponse create(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			McpOauthProviderRequest request) {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		Workspace workspace = requireWorkspace(tenantId, workspaceId);
		if (request == null || request.issuer() == null || request.issuer().isBlank()) {
			throw new WebApplicationException("Issuer is required", Response.Status.BAD_REQUEST);
		}
		McpOauthProvider provider = new McpOauthProvider();
		provider.workspace = workspace;
		applyRequest(provider, request);
		entityManager.persist(provider);
		return toResponse(provider);
	}

	@PUT
	@Path("/{providerId}")
	@Transactional
	public McpOauthProviderResponse update(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("providerId") UUID providerId,
			McpOauthProviderRequest request) {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		requireWorkspace(tenantId, workspaceId);
		McpOauthProvider provider = entityManager.find(McpOauthProvider.class, providerId);
		if (provider == null || !provider.workspace.id.equals(workspaceId)) {
			throw new WebApplicationException("Provider not found", Response.Status.NOT_FOUND);
		}
		applyRequest(provider, request);
		return toResponse(provider);
	}

	@DELETE
	@Path("/{providerId}")
	@Transactional
	public void delete(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("providerId") UUID providerId) {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		requireWorkspace(tenantId, workspaceId);
		McpOauthProvider provider = entityManager.find(McpOauthProvider.class, providerId);
		if (provider == null || !provider.workspace.id.equals(workspaceId)) {
			throw new WebApplicationException("Provider not found", Response.Status.NOT_FOUND);
		}
		List<McpOauthToken> tokens = entityManager.createQuery("select t from McpOauthToken t where t.provider.id = :providerId", McpOauthToken.class)
			.setParameter("providerId", provider.id)
			.getResultList();
		tokens.forEach(entityManager::remove);
		McpOauthClient client = entityManager.createQuery("select c from McpOauthClient c where c.provider.id = :providerId", McpOauthClient.class)
			.setParameter("providerId", provider.id)
			.getResultStream()
			.findFirst()
			.orElse(null);
		if (client != null) {
			entityManager.remove(client);
		}
		entityManager.remove(provider);
	}

	private void applyRequest(McpOauthProvider provider, McpOauthProviderRequest request) {
		if (request == null) {
			return;
		}
		if (request.issuer() != null && !request.issuer().isBlank()) {
			provider.issuer = request.issuer().trim();
		}
		provider.resourceMetadataUrl = trim(request.resource_metadata_url());
		provider.wellKnownUrl = trim(request.well_known_url());
		provider.authorizationEndpoint = trim(request.authorization_endpoint());
		provider.tokenEndpoint = trim(request.token_endpoint());
		provider.registrationEndpoint = trim(request.registration_endpoint());
		applyScopeCategories(provider, request.scope_category_ids());
		applyClient(provider, request);
	}

	private void applyScopeCategories(McpOauthProvider provider, List<UUID> categoryIds) {
		if (provider == null || provider.workspace == null) {
			return;
		}
		if (categoryIds == null) {
			return;
		}
		List<TagCategory> categories = categoryIds.isEmpty()
			? List.of()
			: entityManager.createQuery(
					"select c from TagCategory c where c.workspace.id = :workspaceId and c.id in :categoryIds "
						+ "and c.deletedAt is null",
					TagCategory.class
				)
				.setParameter("workspaceId", provider.workspace.id)
				.setParameter("categoryIds", categoryIds)
				.getResultList();
		provider.scopeCategories.clear();
		provider.scopeCategories.addAll(categories);
	}

	private void applyClient(McpOauthProvider provider, McpOauthProviderRequest request) {
		if (provider == null || request == null) {
			return;
		}
		String clientId = request.client_id();
		String clientSecret = request.client_secret();
		if ((clientId == null || clientId.isBlank()) && (clientSecret == null || clientSecret.isBlank())) {
			return;
		}
		McpOauthClient client = entityManager.createQuery("select c from McpOauthClient c where c.provider.id = :providerId", McpOauthClient.class)
			.setParameter("providerId", provider.id)
			.getResultStream()
			.findFirst()
			.orElse(null);
		if (client == null) {
			client = new McpOauthClient();
			client.provider = provider;
			client.dynamicRegistration = false;
			entityManager.persist(client);
		}
		if (clientId != null && !clientId.isBlank()) {
			client.clientId = clientId.trim();
		}
		if (clientSecret != null) {
			if (clientSecret.isBlank()) {
				client.clientSecretCiphertext = null;
				client.clientSecretNonce = null;
				client.clientSecretHint = null;
			}
			else {
				SecretCipher.EncryptedSecret encrypted = secretCipher.encrypt(clientSecret);
				client.clientSecretCiphertext = encrypted.ciphertext();
				client.clientSecretNonce = encrypted.nonce();
				client.clientSecretHint = maskSecret(clientSecret);
			}
		}
	}

	private McpOauthProviderResponse toResponse(McpOauthProvider provider) {
		McpOauthClient client = entityManager.createQuery("select c from McpOauthClient c where c.provider.id = :providerId", McpOauthClient.class)
			.setParameter("providerId", provider.id)
			.getResultStream()
			.findFirst()
			.orElse(null);
		List<UUID> scopeCategoryIds = entityManager.createQuery(
				"select c.id from McpOauthProvider p join p.scopeCategories c where p.id = :providerId",
				UUID.class
			)
			.setParameter("providerId", provider.id)
			.getResultList();
		return new McpOauthProviderResponse(
			provider.id,
			provider.issuer,
			provider.resourceMetadataUrl,
			provider.wellKnownUrl,
			provider.authorizationEndpoint,
			provider.tokenEndpoint,
			provider.registrationEndpoint,
			client == null ? null : client.clientId,
			client == null ? null : client.clientSecretHint,
			client != null && client.dynamicRegistration,
			scopeCategoryIds
		);
	}

	private String maskSecret(String value) {
		if (value == null || value.length() <= 8) {
			return "***";
		}
		return value.substring(0, 4) + "..." + value.substring(value.length() - 4);
	}

	private String trim(String value) {
		return value == null ? null : value.trim();
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
