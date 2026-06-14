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

import be.celerex.polymr.model.McpOauthClient;
import be.celerex.polymr.model.McpOauthProvider;
import be.celerex.polymr.model.McpOauthSession;
import be.celerex.polymr.model.McpOauthToken;
import be.celerex.polymr.model.McpServer;
import be.celerex.polymr.model.TagCategory;
import be.celerex.polymr.model.TagValue;
import be.celerex.polymr.model.User;
import be.celerex.polymr.model.Workspace;
import be.celerex.polymr.model.WorkspaceTagSelection;
import be.celerex.polymr.security.SecretCipher;
import be.celerex.polymr.ws.WorkspaceSocketEvent;
import be.celerex.polymr.ws.WorkspaceSocketManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.jboss.logging.Logger;

@ApplicationScoped
public class McpOAuthService {
	private static final Logger LOGGER = Logger.getLogger(McpOAuthService.class);
	private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(8);
	private static final Duration ACCESS_TOKEN_SAFETY_WINDOW = Duration.ofSeconds(60);
	@Inject
	EntityManager entityManager;

	@Inject
	ObjectMapper objectMapper;

	@Inject
	SecretCipher secretCipher;

	@Inject
	WorkspaceSocketManager socketManager;

	private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();

	public UUID resolveActiveAuthScopeValue(McpServer server) {
		if (server == null || server.workspace == null) {
			return null;
		}
		McpOauthProvider provider = resolveProviderForServer(server);
		if (provider == null) {
			return null;
		}
		List<UUID> categoryIds = loadScopeCategoryIds(provider);
		if (categoryIds.isEmpty()) {
			return null;
		}
		List<WorkspaceTagSelection> states = entityManager.createQuery(
				"select s from WorkspaceTagSelection s join fetch s.category c "
					+ "where s.workspace.id = :workspaceId and c.id in :categoryIds and c.deletedAt is null "
					+ "order by c.priority asc, lower(c.name) asc",
				WorkspaceTagSelection.class
			)
			.setParameter("workspaceId", server.workspace.id)
			.setParameter("categoryIds", categoryIds)
			.getResultList();
		for (WorkspaceTagSelection state : states) {
			if (state == null || state.value == null) {
				continue;
			}
			if (state.value.deletedAt != null) {
				continue;
			}
			return state.value.id;
		}
		return null;
	}

	@Transactional
	public void recordAuthRequired(McpServer server, String resourceMetadataUrl) {
		if (server == null || resourceMetadataUrl == null || resourceMetadataUrl.isBlank()) {
			return;
		}
		if (!server.oauthEnabled) {
			return;
		}
		try {
			ensureProviderDiscovery(server, resourceMetadataUrl, null, null, false);
		}
		catch (Exception ex) {
			LOGGER.debugf("Failed to discover provider for %s: %s", resourceMetadataUrl, ex.getMessage());
		}
		broadcastAuthUpdate(server);
	}

	@Transactional
	public void clearRefreshFailure(McpServer server, UUID authScopeValueId, UUID userId) {
		if (server == null || !server.oauthEnabled) {
			return;
		}
		McpOauthProvider provider = resolveProviderForServer(server);
		if (provider == null) {
			return;
		}
		UUID effectiveUserId = resolveAuthUserId(provider, userId);
		User authUser = effectiveUserId == null ? null : entityManager.getReference(User.class, effectiveUserId);
		TagValue authScopeValue = resolveAuthScopeValue(server.workspace, provider, authScopeValueId);
		clearRefreshFailure(loadToken(provider, server.workspace, authScopeValue, authUser));
		clearRefreshFailure(loadToken(provider, server.workspace, null, authUser));
	}

	public ObjectNode enrichAuthView(McpServer server, UUID authScopeValueId, UUID userId, ObjectNode view) {
		if (view == null) {
			view = objectMapper.createObjectNode();
		}
		if (server == null) {
			return view;
		}
		McpOauthProvider provider = resolveProviderForServer(server);
		McpOauthClient client = provider == null ? null : loadClient(provider);
		TagValue authScopeValue = resolveAuthScopeValue(server.workspace, provider, authScopeValueId);
		UUID effectiveUserId = resolveAuthUserId(provider, userId);
		User authUser = effectiveUserId == null ? null : entityManager.getReference(User.class, effectiveUserId);
		McpOauthToken authScopeToken = provider == null ? null : loadToken(provider, server.workspace, authScopeValue, authUser);
		McpOauthToken fallbackToken = provider == null ? null : loadToken(provider, server.workspace, null, authUser);

		boolean hasAuthScopeToken = authScopeToken != null;
		boolean hasFallbackToken = fallbackToken != null;
		boolean canReuseFallback = !hasAuthScopeToken && hasFallbackToken && authScopeValue != null;
		McpOauthToken recentToken = authScopeToken;
		if (recentToken == null
				|| (recentToken.refreshFailedAt == null
						&& fallbackToken != null
						&& fallbackToken.refreshFailedAt != null)
				|| (recentToken.updatedAt != null
						&& fallbackToken != null
						&& fallbackToken.updatedAt != null
						&& fallbackToken.updatedAt.isAfter(recentToken.updatedAt))) {
			recentToken = fallbackToken;
		}
		boolean refreshFailed = recentToken != null && recentToken.refreshFailedAt != null;

		String status = "";
		boolean enabled = server.oauthEnabled;
		if (enabled) {
			if (provider == null) {
				status = "auth_required";
			}
			else if (client == null && provider.registrationEndpoint == null) {
				status = "manual_client_required";
			}
			else if (refreshFailed) {
				status = "refresh_failed";
			}
			else if (hasAuthScopeToken) {
				status = "connected";
			}
			else if (hasFallbackToken) {
				status = "fallback_available";
			}
			else {
				status = "auth_required";
			}
		}

		view.put("status", status);
		view.put("global", provider == null || provider.globalAuth);
		view.put("auth_scope_value_id", authScopeValue == null ? null : authScopeValue.id.toString());
		view.put("auth_scope_token", hasAuthScopeToken);
		view.put("fallback_token", hasFallbackToken);
		view.put("can_reuse_fallback", canReuseFallback);
		view.put("manual_client_required", status.equals("manual_client_required"));

		if (recentToken != null) {
			view.put("last_auth_at", recentToken.updatedAt == null ? null : recentToken.updatedAt.toString());
			view.put("refreshable", recentToken.refreshTokenCiphertext != null);
			view.put(
				"access_expires_at",
				recentToken.accessExpiresAt == null ? null : recentToken.accessExpiresAt.toString()
			);
			view.put(
				"refresh_failed_at",
				recentToken.refreshFailedAt == null ? null : recentToken.refreshFailedAt.toString()
			);
			if (recentToken.refreshErrorMessage != null && !recentToken.refreshErrorMessage.isBlank()) {
				view.put("error_message", recentToken.refreshErrorMessage);
			}
		}
		if (provider != null) {
			view.put("issuer", provider.issuer);
			if (provider.wellKnownUrl != null) {
				view.put("well_known_url", provider.wellKnownUrl);
			}
			if (provider.resourceMetadataUrl != null) {
				view.put("resource_metadata_url", provider.resourceMetadataUrl);
			}
			if (provider.authorizationEndpoint != null) {
				view.put("authorization_endpoint", provider.authorizationEndpoint);
			}
			if (provider.tokenEndpoint != null) {
				view.put("token_endpoint", provider.tokenEndpoint);
			}
			if (provider.registrationEndpoint != null) {
				view.put("registration_endpoint", provider.registrationEndpoint);
			}
			if (provider.scopes != null) {
				view.put("scopes", provider.scopes);
			}
			ArrayNode supportedScopes = view.putArray("supported_scopes");
			readScopes(provider.scopes).forEach(supportedScopes::add);
			ArrayNode scopeCategories = view.putArray("scope_category_ids");
			loadScopeCategoryIds(provider)
				.forEach(id -> scopeCategories.add(id.toString()));
		}
		if (client != null) {
			view.put("client_id", client.clientId);
			if (client.clientSecretHint != null) {
				view.put("client_secret_hint", client.clientSecretHint);
			}
		}
		return view;
	}

	public ObjectNode buildAuthView(McpServer server, UUID authScopeValueId) {
		ObjectNode view = objectMapper.createObjectNode();
		return enrichAuthView(server, authScopeValueId, null, view);
	}

	public ObjectNode buildAuthView(McpServer server, UUID authScopeValueId, UUID userId) {
		ObjectNode view = objectMapper.createObjectNode();
		return enrichAuthView(server, authScopeValueId, userId, view);
	}

	public ObjectNode buildAuthConfig(McpServer server) {
		ObjectNode config = objectMapper.createObjectNode();
		if (server == null) {
			return config;
		}
		McpOauthProvider provider = resolveProviderForServer(server);
		if (provider == null) {
			return config;
		}
		config.put("global", provider.globalAuth);
		if (provider.scopes != null) {
			config.put("scopes", provider.scopes);
		}
		if (provider.issuer != null) {
			config.put("issuer", provider.issuer);
		}
		if (provider.resourceMetadataUrl != null) {
			config.put("resource_metadata_url", provider.resourceMetadataUrl);
		}
		if (provider.wellKnownUrl != null) {
			config.put("well_known_url", provider.wellKnownUrl);
		}
		if (provider.authorizationEndpoint != null) {
			config.put("authorization_endpoint", provider.authorizationEndpoint);
		}
		if (provider.tokenEndpoint != null) {
			config.put("token_endpoint", provider.tokenEndpoint);
		}
		if (provider.registrationEndpoint != null) {
			config.put("registration_endpoint", provider.registrationEndpoint);
		}
		if (provider.scopeCategories != null && !provider.scopeCategories.isEmpty()) {
			var array = config.putArray("scope_category_ids");
			provider.scopeCategories.forEach(
				category -> {
					if (category != null && category.id != null) {
						array.add(category.id.toString());
					}
				}
			);
		}
		McpOauthClient client = loadClient(provider);
		if (client != null && client.clientId != null) {
			config.put("client_id", client.clientId);
			if (client.clientSecretCiphertext != null && client.clientSecretNonce != null) {
				ObjectNode secret = config.putObject("client_secret");
				secret.put("ciphertext", client.clientSecretCiphertext);
				secret.put("nonce", client.clientSecretNonce);
			}
		}
		return config;
	}

	@Transactional
	public String startAuthorization(McpServer server, UUID authScopeValueId, UUID userId, UriInfo uriInfo) {
		if (server == null) {
			throw new WebApplicationException("MCP server not found", Response.Status.NOT_FOUND);
		}
		if (!server.oauthEnabled) {
			throw new WebApplicationException("OAuth is disabled", Response.Status.CONFLICT);
		}
		McpOauthProvider provider = ensureProviderDiscovery(server, null, null, null, true);
		if (provider == null) {
			throw new WebApplicationException("OAuth configuration missing", Response.Status.CONFLICT);
		}
		UUID effectiveUserId = resolveAuthUserId(provider, userId);
		String redirectUri = uriInfo.getBaseUriBuilder()
			.path("api")
			.path("tenants")
			.path(server.workspace.tenant.id.toString())
			.path("workspaces")
			.path(server.workspace.id.toString())
			.path("mcp-servers")
			.path(server.id.toString())
			.path("oauth")
			.path("callback")
			.build()
			.toString();

		McpOauthClient client = ensureClient(provider, redirectUri);
		if (client == null) {
			LOGGER.warnf(
				"OAuth start failed for server %s: issuer=%s wellKnown=%s auth=%s token=%s registration=%s",
				server.id,
				provider.issuer,
				provider.wellKnownUrl,
				provider.authorizationEndpoint,
				provider.tokenEndpoint,
				provider.registrationEndpoint
			);
			throw new WebApplicationException("Manual client configuration required", Response.Status.CONFLICT);
		}
		String verifier = generateVerifier();
		String challenge = codeChallenge(verifier);
		String state = randomString(24);
		TagValue authScopeValue = resolveAuthScopeValue(server.workspace, provider, authScopeValueId);
		if (authScopeValueId != null && authScopeValue == null) {
			throw new WebApplicationException("Auth scope tag value not found", Response.Status.NOT_FOUND);
		}

		McpOauthSession session = new McpOauthSession();
		session.workspace = server.workspace;
		session.mcpServer = server;
		session.provider = provider;
		session.authScopeValue = authScopeValue;
		if (effectiveUserId != null) {
			session.user = entityManager.getReference(User.class, effectiveUserId);
		}
		session.state = state;
		session.codeVerifier = verifier;
		session.redirectUri = redirectUri;
		entityManager.persist(session);

		String scope = trim(provider.scopes);
		String authorizationEndpoint = provider.authorizationEndpoint;
		if (authorizationEndpoint == null || authorizationEndpoint.isBlank()) {
			throw new WebApplicationException("Authorization endpoint missing", Response.Status.CONFLICT);
		}
		String url = authorizationEndpoint
			+ "?response_type=code"
			+ "&client_id="
			+ encode(client.clientId)
			+ "&redirect_uri="
			+ encode(redirectUri);
		if (scope != null && !scope.isBlank()) {
			url += "&scope=" + encode(scope);
		}
		url += "&state=" + encode(state)
			+ "&code_challenge="
			+ encode(challenge)
			+ "&code_challenge_method=S256";
		return url;
	}

	@Transactional
	public void handleCallback(McpServer server, String state, String code) {
		if (state == null || code == null) {
			throw new WebApplicationException("Missing OAuth state or code", Response.Status.BAD_REQUEST);
		}
		McpOauthSession session = entityManager.createQuery("select s from McpOauthSession s where s.state = :state", McpOauthSession.class)
			.setParameter("state", state)
			.getResultStream()
			.findFirst()
			.orElse(null);
		if (session == null || server == null || !session.mcpServer.id.equals(server.id)) {
			throw new WebApplicationException("OAuth session not found", Response.Status.NOT_FOUND);
		}
		McpOauthProvider provider = session.provider;
		McpOauthClient client = loadClient(provider);
		if (client == null) {
			throw new WebApplicationException("OAuth client not configured", Response.Status.CONFLICT);
		}
		TokenResponse response = exchangeCode(provider, client, code, session.codeVerifier, session.redirectUri);
		if (response == null || response.accessToken == null) {
			throw new WebApplicationException("OAuth token exchange failed", Response.Status.BAD_REQUEST);
		}
		deactivateActiveTokens(provider, session.workspace, session.authScopeValue, session.user);
		McpOauthToken token = createTokenVersion(provider, session.workspace, session.authScopeValue, session.user, null);
		applyToken(token, response);
		entityManager.remove(session);
		broadcastAuthUpdate(server);
	}

	@Transactional
	public void cloneFallbackToken(McpServer server, UUID authScopeValueId, UUID userId) {
		if (server == null) {
			throw new WebApplicationException("MCP server not found", Response.Status.NOT_FOUND);
		}
		if (!server.oauthEnabled) {
			throw new WebApplicationException("OAuth is disabled", Response.Status.CONFLICT);
		}
		McpOauthProvider provider = resolveProviderForServer(server);
		if (provider == null) {
			throw new WebApplicationException("OAuth configuration missing", Response.Status.CONFLICT);
		}
		UUID effectiveUserId = resolveAuthUserId(provider, userId);
		User authUser = effectiveUserId == null ? null : entityManager.getReference(User.class, effectiveUserId);
		McpOauthToken fallback = loadToken(provider, server.workspace, null, authUser);
		if (fallback == null) {
			throw new WebApplicationException("Fallback token not available", Response.Status.CONFLICT);
		}
		TagValue authScopeValue = resolveAuthScopeValue(server.workspace, provider, authScopeValueId);
		if (authScopeValue == null) {
			throw new WebApplicationException("Auth scope tag value not found", Response.Status.NOT_FOUND);
		}
		deactivateActiveTokens(provider, server.workspace, authScopeValue, authUser);
		McpOauthToken token = createTokenVersion(provider, server.workspace, authScopeValue, authUser, fallback);
		copyToken(token, fallback);
	}

	@Transactional
	public String resolveAccessToken(McpServer server, UUID authScopeValueId, UUID userId) {
		if (server == null) {
			return null;
		}
		if (!server.oauthEnabled) {
			return null;
		}
		McpOauthProvider provider = resolveProviderForServer(server);
		if (provider == null) {
			return null;
		}
		UUID effectiveUserId = resolveAuthUserId(provider, userId);
		User authUser = effectiveUserId == null ? null : entityManager.getReference(User.class, effectiveUserId);
		McpOauthClient client = loadClient(provider);
		TagValue authScopeValue = resolveAuthScopeValue(server.workspace, provider, authScopeValueId);
		McpOauthToken token = loadToken(provider, server.workspace, authScopeValue, authUser);
		if (token == null) {
			token = loadToken(provider, server.workspace, null, authUser);
		}
		if (token == null) {
			return null;
		}
		McpOauthToken root = token.sourceToken == null ? token : token.sourceToken;
		if (needsRefresh(root)) {
			if (client == null || !refreshToken(root, client, provider)) {
				String authResourceMetadataUrl = provider.resourceMetadataUrl;
				recordAuthRequired(server, authResourceMetadataUrl);
				throw new McpAuthRequiredException(authResourceMetadataUrl);
			}
		}
		String accessToken = decrypt(root.accessTokenCiphertext, root.accessTokenNonce);
		if (accessToken == null || accessToken.isBlank()) {
			String authResourceMetadataUrl = provider.resourceMetadataUrl;
			recordAuthRequired(server, authResourceMetadataUrl);
			throw new McpAuthRequiredException(authResourceMetadataUrl);
		}
		return accessToken;
	}

	@Transactional
	public boolean forceRefreshAccessToken(McpServer server, UUID authScopeValueId, UUID userId) {
		if (server == null || !server.oauthEnabled) {
			return false;
		}
		McpOauthProvider provider = resolveProviderForServer(server);
		if (provider == null) {
			return false;
		}
		McpOauthClient client = loadClient(provider);
		if (client == null) {
			return false;
		}
		UUID effectiveUserId = resolveAuthUserId(provider, userId);
		User authUser = effectiveUserId == null ? null : entityManager.getReference(User.class, effectiveUserId);
		TagValue authScopeValue = resolveAuthScopeValue(server.workspace, provider, authScopeValueId);
		McpOauthToken token = loadToken(provider, server.workspace, authScopeValue, authUser);
		if (token == null) {
			token = loadToken(provider, server.workspace, null, authUser);
		}
		if (token == null) {
			return false;
		}
		McpOauthToken root = token.sourceToken == null ? token : token.sourceToken;
		return refreshToken(root, client, provider);
	}

	private boolean needsRefresh(McpOauthToken token) {
		if (token == null || token.accessExpiresAt == null) {
			return false;
		}
		Instant threshold = Instant.now().plus(ACCESS_TOKEN_SAFETY_WINDOW);
		return token.accessExpiresAt.isBefore(threshold);
	}

	@Transactional
	boolean refreshToken(McpOauthToken token, McpOauthClient client, McpOauthProvider provider) {
		if (token == null || client == null || provider == null) {
			return false;
		}
		String refreshToken = decrypt(token.refreshTokenCiphertext, token.refreshTokenNonce);
		if (refreshToken == null || refreshToken.isBlank()) {
			markRefreshFailed(token, "Stored OAuth refresh token is missing. Please authenticate again.");
			return false;
		}
		TokenResponse response;
		try {
			response = exchangeRefresh(provider, client, refreshToken);
		}
		catch (WebApplicationException ex) {
			markRefreshFailed(token, ex.getMessage());
			return false;
		}
		if (response == null || response.accessToken == null) {
			markRefreshFailed(token, "OAuth token refresh failed. Please authenticate again.");
			return false;
		}
		McpOauthToken root = token.sourceToken == null ? token : token.sourceToken;
		deactivateActiveTokens(provider, root.workspace, root.authScopeValue, root.user);
		McpOauthToken refreshed = createTokenVersion(provider, root.workspace, root.authScopeValue, root.user, null);
		copyToken(refreshed, root);
		refreshed.refreshFailedAt = null;
		refreshed.refreshErrorMessage = null;
		applyToken(refreshed, response);
		List<McpOauthToken> linked = entityManager.createQuery(
				"select t from McpOauthToken t where t.sourceToken.id = :sourceId and t.active = true",
				McpOauthToken.class
			)
			.setParameter("sourceId", root.id)
			.getResultList();
		for (McpOauthToken child : linked) {
			child.active = false;
			McpOauthToken updatedChild = createTokenVersion(provider, child.workspace, child.authScopeValue, child.user, refreshed);
			copyToken(updatedChild, refreshed);
		}
		return true;
	}

	private McpOauthProvider ensureProviderDiscovery(
			McpServer server,
			String resourceMetadataUrl,
			String issuer,
			String wellKnownUrl,
			boolean fallbackToServerUrl) {
		if (server == null || !server.oauthEnabled) {
			return null;
		}
		McpOauthProvider provider = resolveProviderForServer(server);
		String effectiveResourceMetadataUrl = trim(resourceMetadataUrl);
		String effectiveIssuer = trim(issuer);
		String effectiveWellKnownUrl = trim(wellKnownUrl);
		if ((effectiveResourceMetadataUrl == null || effectiveResourceMetadataUrl.isBlank())
				&& (effectiveIssuer == null || effectiveIssuer.isBlank())
				&& (effectiveWellKnownUrl == null || effectiveWellKnownUrl.isBlank())
				&& fallbackToServerUrl) {
			effectiveWellKnownUrl = deriveWellKnownUrl(server.httpUrl);
			LOGGER.infof("Bootstrapping OAuth discovery for server %s from %s", server.id, effectiveWellKnownUrl);
		}
		if ((effectiveResourceMetadataUrl == null || effectiveResourceMetadataUrl.isBlank())
				&& (effectiveIssuer == null || effectiveIssuer.isBlank())
				&& (effectiveWellKnownUrl == null || effectiveWellKnownUrl.isBlank())) {
			return provider;
		}
		ProviderDiscovery discovery = discoverProvider(effectiveResourceMetadataUrl, effectiveIssuer, effectiveWellKnownUrl);
		LOGGER.infof(
			"Discovered OAuth provider for server %s: issuer=%s wellKnown=%s auth=%s token=%s registration=%s",
			server.id,
			discovery == null ? null : discovery.issuer,
			discovery == null ? null : discovery.wellKnownUrl,
			discovery == null ? null : discovery.authorizationEndpoint,
			discovery == null ? null : discovery.tokenEndpoint,
			discovery == null ? null : discovery.registrationEndpoint
		);
		if (provider == null) {
			provider = new McpOauthProvider();
			provider.workspace = server.workspace;
			provider.globalAuth = true;
			entityManager.persist(provider);
			server.oauthProvider = provider;
		}
		if (discovery != null) {
			provider.issuer = trim(discovery.issuer);
			provider.resourceMetadataUrl = trim(discovery.resourceMetadataUrl);
			provider.wellKnownUrl = trim(discovery.wellKnownUrl);
			provider.authorizationEndpoint = trim(discovery.authorizationEndpoint);
			provider.tokenEndpoint = trim(discovery.tokenEndpoint);
			provider.registrationEndpoint = trim(discovery.registrationEndpoint);
		}
		return provider;
	}

	private McpOauthProvider resolveProvider(Workspace workspace, String issuer, String resourceMetadataUrl) {
		if (workspace == null) {
			return null;
		}
		if (issuer != null && !issuer.isBlank()) {
			return entityManager.createQuery(
					"select p from McpOauthProvider p where p.workspace.id = :workspaceId and p.issuer = :issuer",
					McpOauthProvider.class
				)
				.setParameter("workspaceId", workspace.id)
				.setParameter("issuer", issuer)
				.getResultStream()
				.findFirst()
				.orElse(null);
		}
		if (resourceMetadataUrl != null && !resourceMetadataUrl.isBlank()) {
			return entityManager.createQuery(
					"select p from McpOauthProvider p where p.workspace.id = "
						+ ":workspaceId and p.resourceMetadataUrl = :url",
					McpOauthProvider.class
				)
				.setParameter("workspaceId", workspace.id)
				.setParameter("url", resourceMetadataUrl)
				.getResultStream()
				.findFirst()
				.orElse(null);
		}
		return null;
	}

	private McpOauthProvider upsertProvider(Workspace workspace, ProviderDiscovery discovery) {
		if (workspace == null || discovery == null || discovery.issuer == null) {
			return null;
		}
		McpOauthProvider provider = resolveProvider(workspace, discovery.issuer, null);
		if (provider == null) {
			provider = new McpOauthProvider();
			provider.workspace = workspace;
			provider.issuer = discovery.issuer;
			provider.resourceMetadataUrl = discovery.resourceMetadataUrl;
			provider.wellKnownUrl = discovery.wellKnownUrl;
			provider.authorizationEndpoint = discovery.authorizationEndpoint;
			provider.tokenEndpoint = discovery.tokenEndpoint;
			provider.registrationEndpoint = discovery.registrationEndpoint;
			entityManager.persist(provider);
		}
		else {
			provider.resourceMetadataUrl = discovery.resourceMetadataUrl;
			provider.wellKnownUrl = discovery.wellKnownUrl;
			provider.authorizationEndpoint = discovery.authorizationEndpoint;
			provider.tokenEndpoint = discovery.tokenEndpoint;
			provider.registrationEndpoint = discovery.registrationEndpoint;
		}
		return provider;
	}

	private McpOauthClient ensureClient(McpOauthProvider provider, String redirectUri) {
		McpOauthClient client = loadClient(provider);
		if (client != null && client.clientId != null && !client.clientId.isBlank()) {
			return client;
		}
		if (provider == null || provider.registrationEndpoint == null) {
			return null;
		}
		try {
			ObjectNode payload = objectMapper.createObjectNode();
			payload.put("client_name", "Polymr MCP");
			payload.putArray("redirect_uris").add(redirectUri);
			payload.putArray("grant_types").add("authorization_code").add("refresh_token");
			payload.putArray("response_types").add("code");
			String body = objectMapper.writeValueAsString(payload);
			HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(provider.registrationEndpoint))
				.timeout(HTTP_TIMEOUT)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
				.build();
			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() >= 400) {
				LOGGER.debugf("Dynamic registration failed: %s", response.body());
				return null;
			}
			JsonNode node = objectMapper.readTree(response.body());
			String clientId = node.path("client_id").asText(null);
			if (clientId == null || clientId.isBlank()) {
				return null;
			}
			if (client == null) {
				client = new McpOauthClient();
				client.provider = provider;
				entityManager.persist(client);
			}
			client.clientId = clientId;
			String secret = node.path("client_secret").asText(null);
			if (secret != null && !secret.isBlank()) {
				SecretCipher.EncryptedSecret encrypted = secretCipher.encrypt(secret);
				client.clientSecretCiphertext = encrypted.ciphertext();
				client.clientSecretNonce = encrypted.nonce();
				client.clientSecretHint = maskSecret(secret);
			}
			else {
				client.clientSecretCiphertext = null;
				client.clientSecretNonce = null;
				client.clientSecretHint = null;
			}
			client.dynamicRegistration = true;
			return client;
		}
		catch (Exception ex) {
			LOGGER.debugf("Dynamic registration error: %s", ex.getMessage());
			return null;
		}
	}

	private McpOauthClient loadClient(McpOauthProvider provider) {
		if (provider == null) {
			return null;
		}
		return entityManager.createQuery("select c from McpOauthClient c where c.provider.id = :providerId", McpOauthClient.class)
			.setParameter("providerId", provider.id)
			.getResultStream()
			.findFirst()
			.orElse(null);
	}

	private McpOauthToken loadToken(
			McpOauthProvider provider,
			Workspace workspace,
			TagValue authScopeValue,
			User user) {
		if (provider == null || workspace == null) {
			return null;
		}
		boolean hasUser = user != null;
		if (authScopeValue == null) {
			var query = entityManager.createQuery(
					"select t from McpOauthToken t where t.provider.id = :providerId and t.workspace.id = :workspaceId "
						+ "and t.authScopeValue is null and t.active = true and t.user "
						+ (hasUser ? "= :user" : "is null"),
					McpOauthToken.class
				)
				.setParameter("providerId", provider.id)
				.setParameter("workspaceId", workspace.id);
			if (hasUser) {
				query.setParameter("user", user);
			}
			return query.getResultStream().findFirst().orElse(null);
		}
		var query = entityManager.createQuery(
				"select t from McpOauthToken t where t.provider.id = :providerId and t.workspace.id = :workspaceId "
					+ "and t.authScopeValue = :tag and t.active = true and t.user "
					+ (hasUser ? "= :user" : "is null"),
				McpOauthToken.class
			)
			.setParameter("providerId", provider.id)
			.setParameter("workspaceId", workspace.id)
			.setParameter("tag", authScopeValue);
		if (hasUser) {
			query.setParameter("user", user);
		}
		return query.getResultStream().findFirst().orElse(null);
	}

	private void deactivateActiveTokens(McpOauthProvider provider, Workspace workspace, TagValue authScopeValue, User user) {
		if (provider == null || workspace == null) {
			return;
		}
		boolean hasUser = user != null;
		if (authScopeValue == null) {
			var query = entityManager.createQuery(
					"update McpOauthToken t set t.active = false where t.provider.id = :providerId "
						+ "and t.workspace.id = :workspaceId and t.authScopeValue is null "
						+ "and t.active = true and t.user "
						+ (hasUser ? "= :user" : "is null")
				)
				.setParameter("providerId", provider.id)
				.setParameter("workspaceId", workspace.id);
			if (hasUser) {
				query.setParameter("user", user);
			}
			query.executeUpdate();
			return;
		}
		var query = entityManager.createQuery(
				"update McpOauthToken t set t.active = false where t.provider.id = :providerId "
					+ "and t.workspace.id = :workspaceId and t.authScopeValue = :tag and t.active = true "
					+ "and t.user "
					+ (hasUser ? "= :user" : "is null")
			)
			.setParameter("providerId", provider.id)
			.setParameter("workspaceId", workspace.id)
			.setParameter("tag", authScopeValue);
		if (hasUser) {
			query.setParameter("user", user);
		}
		query.executeUpdate();
	}

	private void clearRefreshFailure(McpOauthToken token) {
		if (token == null) {
			return;
		}
		token.refreshFailedAt = null;
		token.refreshErrorMessage = null;
		McpOauthToken root = token.sourceToken == null ? token : token.sourceToken;
		if (root != token) {
			root.refreshFailedAt = null;
			root.refreshErrorMessage = null;
		}
	}

	private void markRefreshFailed(McpOauthToken token, String message) {
		if (token == null) {
			return;
		}
		McpOauthToken root = token.sourceToken == null ? token : token.sourceToken;
		root.refreshFailedAt = Instant.now();
		root.refreshErrorMessage = message == null || message.isBlank()
			? "OAuth token refresh failed. Please authenticate again."
			: message;
		if (token != root) {
			token.refreshFailedAt = root.refreshFailedAt;
			token.refreshErrorMessage = root.refreshErrorMessage;
		}
	}

	@Transactional
	public void logout(McpServer server, UUID authScopeValueId, UUID userId) {
		if (server == null || !server.oauthEnabled) {
			return;
		}
		McpOauthProvider provider = resolveProviderForServer(server);
		if (provider == null) {
			return;
		}
		UUID effectiveUserId = resolveAuthUserId(provider, userId);
		User authUser = effectiveUserId == null ? null : entityManager.getReference(User.class, effectiveUserId);
		TagValue authScopeValue = resolveAuthScopeValue(server.workspace, provider, authScopeValueId);
		deactivateActiveTokens(provider, server.workspace, authScopeValue, authUser);
		if (authScopeValue != null) {
			deactivateActiveTokens(provider, server.workspace, null, authUser);
		}
		broadcastAuthUpdate(server);
	}

	private McpOauthToken createTokenVersion(
			McpOauthProvider provider,
			Workspace workspace,
			TagValue authScopeValue,
			User user,
			McpOauthToken sourceToken) {
		McpOauthToken token = new McpOauthToken();
		token.provider = provider;
		token.workspace = workspace;
		token.authScopeValue = authScopeValue;
		token.user = user;
		token.sourceToken = sourceToken;
		token.active = true;
		entityManager.persist(token);
		return token;
	}

	private TagValue resolveAuthScopeValue(Workspace workspace, McpOauthProvider provider, UUID authScopeValueId) {
		if (workspace == null || provider == null || authScopeValueId == null) {
			return null;
		}
		TagValue value = entityManager.find(TagValue.class, authScopeValueId);
		if (value == null || value.deletedAt != null) {
			return null;
		}
		if (value.category == null || value.category.deletedAt != null) {
			return null;
		}
		if (value.category.workspace == null || !value.category.workspace.id.equals(workspace.id)) {
			return null;
		}
		List<UUID> categoryIds = loadScopeCategoryIds(provider);
		if (!categoryIds.contains(value.category.id)) {
			return null;
		}
		return value;
	}

	private List<UUID> loadScopeCategoryIds(McpOauthProvider provider) {
		if (provider == null || provider.id == null) {
			return List.of();
		}
		return entityManager.createQuery(
				"select c.id from McpOauthProvider p join p.scopeCategories c where p.id = :providerId",
				UUID.class
			)
			.setParameter("providerId", provider.id)
			.getResultList();
	}

	public boolean isGlobalAuth(McpServer server) {
		McpOauthProvider provider = resolveProviderForServer(server);
		return provider == null || provider.globalAuth;
	}

	private UUID resolveAuthUserId(McpOauthProvider provider, UUID userId) {
		if (provider == null || provider.globalAuth) {
			return null;
		}
		return userId;
	}

	private ProviderDiscovery discoverProvider(String resourceMetadataUrl, String issuerOverride, String wellKnownOverride) {
		try {
			String issuer = issuerOverride;
			String wellKnownUrl = wellKnownOverride;
			String authorizationEndpoint = null;
			String tokenEndpoint = null;
			String registrationEndpoint = null;
			if (resourceMetadataUrl != null && !resourceMetadataUrl.isBlank()) {
				String body = fetch(resourceMetadataUrl);
				JsonNode metadata = objectMapper.readTree(body);
				authorizationEndpoint = readText(metadata, "authorization_endpoint");
				tokenEndpoint = readText(metadata, "token_endpoint");
				registrationEndpoint = readText(metadata, "registration_endpoint");
				if (issuer == null || issuer.isBlank()) {
					issuer = readText(metadata, "issuer");
				}
				if (issuer == null || issuer.isBlank()) {
					issuer = readText(metadata, "authorization_server");
					if (issuer == null || issuer.isBlank()) {
						JsonNode servers = metadata.get("authorization_servers");
						if (servers != null && servers.isArray() && servers.size() > 0) {
							issuer = servers.get(0).asText(null);
						}
					}
					if (issuer == null || issuer.isBlank()) {
						issuer = readText(metadata, "issuer");
					}
				}
				String openidConfig = readText(metadata, "openid_configuration_uri");
				if (openidConfig != null && !openidConfig.isBlank()) {
					wellKnownUrl = openidConfig;
				}
			}
			boolean hasInlineConfig = authorizationEndpoint != null || tokenEndpoint != null || registrationEndpoint != null;
			if (issuer == null || issuer.isBlank()) {
				issuer = deriveIssuer(authorizationEndpoint);
			}
			if ((wellKnownUrl == null || wellKnownUrl.isBlank()) && hasInlineConfig) {
				if (issuer == null || issuer.isBlank()) {
					throw new WebApplicationException("Authorization server not found", Response.Status.BAD_REQUEST);
				}
				ProviderDiscovery discovery = new ProviderDiscovery();
				discovery.issuer = issuer;
				discovery.resourceMetadataUrl = resourceMetadataUrl;
				discovery.wellKnownUrl = wellKnownUrl;
				discovery.authorizationEndpoint = authorizationEndpoint;
				discovery.tokenEndpoint = tokenEndpoint;
				discovery.registrationEndpoint = registrationEndpoint;
				return discovery;
			}
			if (wellKnownUrl == null || wellKnownUrl.isBlank()) {
				if (issuer == null || issuer.isBlank()) {
					throw new WebApplicationException("Authorization server not found", Response.Status.BAD_REQUEST);
				}
				// wellKnownUrl = issuer.endsWith("/") ? issuer + ".well-known/openid-configuration"
				// : issuer + "/.well-known/openid-configuration";
				wellKnownUrl = issuer.endsWith("/")
					? issuer + ".well-known/oauth-authorization-server"
					: issuer + "/.well-known/oauth-authorization-server";
			}
			String configBody = fetch(wellKnownUrl);
			JsonNode config = objectMapper.readTree(configBody);
			if (issuer == null || issuer.isBlank()) {
				issuer = readText(config, "issuer");
			}
			if (issuer == null || issuer.isBlank()) {
				throw new WebApplicationException("Authorization server not found", Response.Status.BAD_REQUEST);
			}
			ProviderDiscovery discovery = new ProviderDiscovery();
			discovery.issuer = issuer;
			discovery.resourceMetadataUrl = resourceMetadataUrl;
			discovery.wellKnownUrl = wellKnownUrl;
			discovery.authorizationEndpoint = readText(config, "authorization_endpoint");
			discovery.tokenEndpoint = readText(config, "token_endpoint");
			discovery.registrationEndpoint = readText(config, "registration_endpoint");
			discovery.scopesSupported = readTextArray(config.get("scopes_supported"));
			return discovery;
		}
		catch (WebApplicationException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new WebApplicationException("OAuth discovery failed", Response.Status.BAD_REQUEST);
		}
	}

	public ObjectNode discoverAuthDetails(String resourceMetadataUrl, String issuer, String wellKnownUrl) {
		ProviderDiscovery discovery = discoverProvider(resourceMetadataUrl, issuer, wellKnownUrl);
		return buildDiscoveryPayload(discovery);
	}

	public ObjectNode discoverAuthDetails(McpServer server) {
		String resourceMetadataUrl = discoverResourceMetadataUrl(server);
		ProviderDiscovery discovery = null;
		if (resourceMetadataUrl != null && !resourceMetadataUrl.isBlank()) {
			discovery = discoverProvider(resourceMetadataUrl, null, null);
		}
		if (discovery == null) {
			String wellKnownUrl = deriveWellKnownUrl(server == null ? null : server.httpUrl);
			if (wellKnownUrl != null && !wellKnownUrl.isBlank()) {
				discovery = discoverProvider(null, null, wellKnownUrl);
			}
		}
		if (discovery == null) {
			throw new WebApplicationException("Discovery failed. Provide a discovery URL.", Response.Status.CONFLICT);
		}
		return buildDiscoveryPayload(discovery);
	}

	private ObjectNode buildDiscoveryPayload(ProviderDiscovery discovery) {
		ObjectNode payload = objectMapper.createObjectNode();
		if (discovery == null) {
			return payload;
		}
		if (discovery.issuer != null) {
			payload.put("issuer", discovery.issuer);
		}
		if (discovery.resourceMetadataUrl != null) {
			payload.put("resource_metadata_url", discovery.resourceMetadataUrl);
		}
		if (discovery.wellKnownUrl != null) {
			payload.put("well_known_url", discovery.wellKnownUrl);
		}
		if (discovery.authorizationEndpoint != null) {
			payload.put("authorization_endpoint", discovery.authorizationEndpoint);
		}
		if (discovery.tokenEndpoint != null) {
			payload.put("token_endpoint", discovery.tokenEndpoint);
		}
		if (discovery.registrationEndpoint != null) {
			payload.put("registration_endpoint", discovery.registrationEndpoint);
		}
		ArrayNode supportedScopes = payload.putArray("supported_scopes");
		if (discovery.scopesSupported != null) {
			discovery.scopesSupported.forEach(supportedScopes::add);
		}
		return payload;
	}

	private String discoverResourceMetadataUrl(McpServer server) {
		if (server == null || server.httpUrl == null || server.httpUrl.isBlank()) {
			return null;
		}
		try {
			HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(server.httpUrl))
				.timeout(HTTP_TIMEOUT)
				.header("Accept", "application/json, text/event-stream")
				.GET()
				.build();
			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 401) {
				return null;
			}
			return McpAuthHeaderParser.extractResourceMetadata(response.headers().firstValue("WWW-Authenticate").orElse(null));
		}
		catch (Exception ex) {
			return null;
		}
	}

	private String fetch(String url) throws Exception {
		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create(url))
			.timeout(HTTP_TIMEOUT)
			.header("Accept", "application/json")
			.GET()
			.build();
		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() >= 400) {
			throw new IllegalStateException("HTTP " + response.statusCode() + " from " + url);
		}
		return response.body();
	}

	private TokenResponse exchangeCode(
			McpOauthProvider provider,
			McpOauthClient client,
			String code,
			String verifier,
			String redirectUri) {
		String tokenEndpoint = provider.tokenEndpoint;
		if (tokenEndpoint == null || tokenEndpoint.isBlank()) {
			throw new WebApplicationException("Token endpoint missing", Response.Status.CONFLICT);
		}
		String body = "grant_type=authorization_code"
			+ "&code="
			+ encode(code)
			+ "&redirect_uri="
			+ encode(redirectUri)
			+ "&client_id="
			+ encode(client.clientId)
			+ "&code_verifier="
			+ encode(verifier);
		String secret = decrypt(client.clientSecretCiphertext, client.clientSecretNonce);
		if (secret != null && !secret.isBlank()) {
			body += "&client_secret=" + encode(secret);
		}
		return postToken(tokenEndpoint, body);
	}

	private TokenResponse exchangeRefresh(McpOauthProvider provider, McpOauthClient client, String refreshToken) {
		String tokenEndpoint = provider.tokenEndpoint;
		if (tokenEndpoint == null || tokenEndpoint.isBlank()) {
			return null;
		}
		String body = "grant_type=refresh_token"
			+ "&refresh_token="
			+ encode(refreshToken)
			+ "&client_id="
			+ encode(client.clientId);
		String secret = decrypt(client.clientSecretCiphertext, client.clientSecretNonce);
		if (secret != null && !secret.isBlank()) {
			body += "&client_secret=" + encode(secret);
		}
		return postToken(tokenEndpoint, body);
	}

	private TokenResponse postToken(String tokenEndpoint, String body) {
		try {
			HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(tokenEndpoint))
				.timeout(HTTP_TIMEOUT)
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
				.build();
			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() >= 400) {
				throw new WebApplicationException("OAuth token request failed", Response.Status.BAD_REQUEST);
			}
			JsonNode node = objectMapper.readTree(response.body());
			TokenResponse token = new TokenResponse();
			token.accessToken = readText(node, "access_token");
			token.refreshToken = readText(node, "refresh_token");
			token.expiresIn = node.path("expires_in").isNumber() ? node.get("expires_in").asLong() : null;
			token.refreshExpiresIn = node.path("refresh_expires_in").isNumber() ? node.get("refresh_expires_in").asLong() : null;
			return token;
		}
		catch (WebApplicationException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new WebApplicationException("OAuth token request failed", Response.Status.BAD_REQUEST);
		}
	}

	private void applyToken(McpOauthToken token, TokenResponse response) {
		if (response.accessToken != null) {
			SecretCipher.EncryptedSecret encrypted = secretCipher.encrypt(response.accessToken);
			token.accessTokenCiphertext = encrypted.ciphertext();
			token.accessTokenNonce = encrypted.nonce();
			token.accessTokenHint = maskSecret(response.accessToken);
		}
		if (response.refreshToken != null) {
			SecretCipher.EncryptedSecret encrypted = secretCipher.encrypt(response.refreshToken);
			token.refreshTokenCiphertext = encrypted.ciphertext();
			token.refreshTokenNonce = encrypted.nonce();
			token.refreshTokenHint = maskSecret(response.refreshToken);
		}
		if (response.expiresIn != null) {
			token.accessExpiresAt = Instant.now().plusSeconds(response.expiresIn);
		}
		if (response.refreshExpiresIn != null) {
			token.refreshExpiresAt = Instant.now().plusSeconds(response.refreshExpiresIn);
		}
	}

	private void copyToken(McpOauthToken target, McpOauthToken source) {
		target.accessTokenCiphertext = source.accessTokenCiphertext;
		target.accessTokenNonce = source.accessTokenNonce;
		target.accessTokenHint = source.accessTokenHint;
		target.refreshTokenCiphertext = source.refreshTokenCiphertext;
		target.refreshTokenNonce = source.refreshTokenNonce;
		target.refreshTokenHint = source.refreshTokenHint;
		target.accessExpiresAt = source.accessExpiresAt;
		target.refreshExpiresAt = source.refreshExpiresAt;
	}

	private String decrypt(String ciphertext, String nonce) {
		if (ciphertext == null || nonce == null) {
			return null;
		}
		try {
			return secretCipher.decrypt(new SecretCipher.EncryptedSecret(ciphertext, nonce));
		}
		catch (Exception ex) {
			return null;
		}
	}

	private String readText(JsonNode node, String field) {
		if (node == null || field == null) {
			return null;
		}
		JsonNode value = node.get(field);
		return value != null && value.isTextual() ? value.asText() : null;
	}

	private List<String> readTextArray(JsonNode node) {
		if (node == null || !node.isArray()) {
			return List.of();
		}
		List<String> values = new ArrayList<>();
		node.forEach(
			entry -> {
				if (entry != null && entry.isTextual()) {
					String value = trim(entry.asText());
					if (value != null && !value.isBlank()) {
						values.add(value);
					}
				}
			}
		);
		return values;
	}

	private List<String> readScopes(String value) {
		if (value == null || value.isBlank()) {
			return List.of();
		}
		List<String> scopes = new ArrayList<>();
		for (String part : value.trim().split("\\s+")) {
			if (!part.isBlank()) {
				scopes.add(part);
			}
		}
		return scopes;
	}

	private String trim(String value) {
		return value == null ? null : value.trim();
	}

	private boolean readBoolean(JsonNode node, String field) {
		if (node == null || field == null) {
			return false;
		}
		JsonNode value = node.get(field);
		if (value == null || value.isNull()) {
			return false;
		}
		if (value.isBoolean()) {
			return value.asBoolean();
		}
		if (value.isTextual()) {
			return Boolean.parseBoolean(value.asText());
		}
		return false;
	}

	private List<UUID> readUuidArray(JsonNode node) {
		if (node == null || !node.isArray()) {
			return List.of();
		}
		List<UUID> ids = new java.util.ArrayList<>();
		node.forEach(
			entry -> {
				if (entry != null && entry.isTextual()) {
					try {
						ids.add(UUID.fromString(entry.asText()));
					}
					catch (IllegalArgumentException ignored) {}
				}
			}
		);
		return ids;
	}

	private String generateVerifier() {
		byte[] bytes = new byte[32];
		new SecureRandom().nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private String codeChallenge(String verifier) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hashed = digest.digest(verifier.getBytes(StandardCharsets.UTF_8));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(hashed);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Failed to generate PKCE challenge", ex);
		}
	}

	private String randomString(int length) {
		byte[] bytes = new byte[length];
		new SecureRandom().nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private String encode(String value) {
		return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
	}

	private String maskSecret(String value) {
		if (value == null || value.length() <= 8) {
			return "***";
		}
		return value.substring(0, 4) + "..." + value.substring(value.length() - 4);
	}

	private void broadcastAuthUpdate(McpServer server) {
		if (server == null || server.workspace == null) {
			return;
		}
		socketManager.broadcast(server.workspace.id, new WorkspaceSocketEvent("mcp.update", server.workspace.id, null, null));
	}

	private McpOauthProvider resolveProviderForServer(McpServer server) {
		if (server == null) {
			return null;
		}
		return server.oauthProvider;
	}

	@Transactional
	public void updateServerAuthConfig(McpServer server, JsonNode authJson) {
		if (server == null || authJson == null || !authJson.isObject()) {
			return;
		}
		ObjectNode node = (ObjectNode) authJson;
		McpOauthProvider provider = resolveProviderForServer(server);
		if (provider == null && server.oauthEnabled) {
			provider = new McpOauthProvider();
			provider.workspace = server.workspace;
			entityManager.persist(provider);
			server.oauthProvider = provider;
		}
		if (provider == null) {
			return;
		}
		provider.globalAuth = !node.has("global") || readBoolean(node, "global");
		provider.scopes = readText(node, "scopes");
		provider.issuer = trim(readText(node, "issuer"));
		provider.resourceMetadataUrl = trim(readText(node, "resource_metadata_url"));
		provider.wellKnownUrl = trim(readText(node, "well_known_url"));
		provider.authorizationEndpoint = trim(readText(node, "authorization_endpoint"));
		provider.tokenEndpoint = trim(readText(node, "token_endpoint"));
		provider.registrationEndpoint = trim(readText(node, "registration_endpoint"));

		if (node.has("scope_category_ids")) {
			List<UUID> categoryIds = readUuidArray(node.get("scope_category_ids"));
			List<TagCategory> categories = categoryIds.isEmpty()
				? List.of()
				: entityManager.createQuery(
						"select c from TagCategory c where c.workspace.id = :workspaceId "
							+ "and c.id in :categoryIds and c.deletedAt is null",
						TagCategory.class
					)
					.setParameter("workspaceId", server.workspace.id)
					.setParameter("categoryIds", categoryIds)
					.getResultList();
			provider.scopeCategories.clear();
			provider.scopeCategories.addAll(categories);
		}

		if ((provider.issuer == null || provider.issuer.isBlank())
				&& provider.authorizationEndpoint != null
				&& !provider.authorizationEndpoint.isBlank()) {
			provider.issuer = deriveIssuer(provider.authorizationEndpoint);
		}

		if (provider.resourceMetadataUrl != null && !provider.resourceMetadataUrl.isBlank()) {
			try {
				ProviderDiscovery discovery = discoverProvider(provider.resourceMetadataUrl, provider.issuer, provider.wellKnownUrl);
				if (discovery != null) {
					if (provider.issuer == null || provider.issuer.isBlank()) {
						provider.issuer = discovery.issuer;
					}
					if (provider.wellKnownUrl == null || provider.wellKnownUrl.isBlank()) {
						provider.wellKnownUrl = discovery.wellKnownUrl;
					}
					if (provider.authorizationEndpoint == null || provider.authorizationEndpoint.isBlank()) {
						provider.authorizationEndpoint = discovery.authorizationEndpoint;
					}
					if (provider.tokenEndpoint == null || provider.tokenEndpoint.isBlank()) {
						provider.tokenEndpoint = discovery.tokenEndpoint;
					}
					if (provider.registrationEndpoint == null || provider.registrationEndpoint.isBlank()) {
						provider.registrationEndpoint = discovery.registrationEndpoint;
					}
				}
			}
			catch (Exception ex) {
				LOGGER.debugf("Failed to discover provider for %s: %s", provider.resourceMetadataUrl, ex.getMessage());
			}
		}

		String clientId = readText(node, "client_id");
		String clientSecret = readText(node, "client_secret");
		if (clientSecret == null) {
			JsonNode secretNode = node.get("client_secret");
			if (secretNode != null && secretNode.isObject()) {
				String cipher = readText(secretNode, "ciphertext");
				String nonce = readText(secretNode, "nonce");
				clientSecret = decrypt(cipher, nonce);
			}
		}
		if (clientId != null || clientSecret != null) {
			McpOauthClient client = loadClient(provider);
			if (client == null
					&& ((clientId != null && !clientId.isBlank())
							|| (clientSecret != null && !clientSecret.isBlank()))) {
				client = new McpOauthClient();
				client.provider = provider;
				entityManager.persist(client);
			}
			if (client != null) {
				if (clientId != null) {
					client.clientId = clientId.isBlank() ? null : clientId.trim();
					client.dynamicRegistration = false;
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
		}
	}

	private String deriveWellKnownUrl(String endpoint) {
		String issuer = deriveIssuer(endpoint);
		if (issuer == null || issuer.isBlank()) {
			return null;
		}
		// return issuer.endsWith("/") ? issuer + ".well-known/openid-configuration"
		// : issuer + "/.well-known/openid-configuration";
		return issuer.endsWith("/")
			? issuer + ".well-known/oauth-authorization-server"
			: issuer + "/.well-known/oauth-authorization-server";
	}

	private String deriveIssuer(String endpoint) {
		if (endpoint == null || endpoint.isBlank()) {
			return null;
		}
		try {
			URI uri = URI.create(endpoint);
			if (uri.getScheme() == null || uri.getHost() == null) {
				return null;
			}
			StringBuilder builder = new StringBuilder();
			builder.append(uri.getScheme()).append("://").append(uri.getHost());
			if (uri.getPort() != -1) {
				builder.append(":").append(uri.getPort());
			}
			return builder.toString();
		}
		catch (Exception ignored) {
			return null;
		}
	}

	private static class ProviderDiscovery {
		String issuer;
		String resourceMetadataUrl;
		String wellKnownUrl;
		String authorizationEndpoint;
		String tokenEndpoint;
		String registrationEndpoint;
		List<String> scopesSupported;
	}

	private static class TokenResponse {
		String accessToken;
		String refreshToken;
		Long expiresIn;
		Long refreshExpiresIn;
	}
}
