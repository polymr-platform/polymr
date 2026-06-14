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

package be.celerex.polymr.session;

import be.celerex.polymr.mcp.WorkspaceMcpRegistry;
import be.celerex.polymr.model.McpServer;
import be.celerex.polymr.model.TenantMembership;
import be.celerex.polymr.model.Workspace;
import be.celerex.polymr.model.WorkspaceAssetBundle;
import be.celerex.polymr.model.WorkspaceAssetBundleEntry;
import be.celerex.polymr.storage.StoredBlob;
import be.celerex.polymr.storage.WorkspaceBlobStore;
import be.celerex.polymr.tenant.TenantAccessService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Path("/api/tenants/{tenantId}/workspaces/{workspaceId}/ui-assets")
public class WorkspaceAssetResource {
	private static final String UI_BLOB_PREFIX = "ui://polymr-blob/";
	@Inject
	WorkspaceBlobStore blobStore;

	@Inject
	EntityManager entityManager;

	@Inject
	TenantAccessService tenantAccessService;

	@Inject
	WorkspaceMcpRegistry mcpRegistry;

	@Inject
	ObjectMapper objectMapper;

	@Context
	SecurityContext securityContext;

	@Context
	Request request;

	@GET
	@Path("/{hash}")
	@Produces("*/*")
	public Response load(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("hash") String hash) {
		return loadAsset(tenantId, workspaceId, hash);
	}

	@GET
	@Path("/{hash}/{rest: .*}")
	@Produces("*/*")
	public Response loadWithSuffix(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("hash") String hash,
			@PathParam("rest") String rest) {
		return loadAsset(tenantId, workspaceId, hash, rest);
	}

	@GET
	@Path("/{authority}/{hash}")
	@Produces("*/*")
	public Response loadWithAuthority(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("authority") String authority,
			@PathParam("hash") String hash) {
		if (authority == null || authority.isBlank()) {
			throw new WebApplicationException("Asset not found", Response.Status.NOT_FOUND);
		}
		return loadAsset(tenantId, workspaceId, hash);
	}

	@GET
	@Path("/{authority}/{hash}/{rest: .*}")
	@Produces("*/*")
	public Response loadWithAuthorityAndSuffix(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("authority") String authority,
			@PathParam("hash") String hash,
			@PathParam("rest") String rest) {
		if (authority == null || authority.isBlank()) {
			throw new WebApplicationException("Asset not found", Response.Status.NOT_FOUND);
		}
		return loadAsset(tenantId, workspaceId, hash, rest);
	}

	private Response loadAsset(UUID tenantId, UUID workspaceId, String hash) {
		return loadAsset(tenantId, workspaceId, hash, null);
	}

	@Transactional
	Response loadAsset(UUID tenantId, UUID workspaceId, String hash, String rest) {
		// currently we will make it public, the hash protects it
		// check AuthFilter as well, we made an exception there
		// requireMembership(tenantId);
		Workspace workspace = requireWorkspace(tenantId, workspaceId);
		String resourcePath = normalizeResourcePath(rest);
		WorkspaceAssetBundle bundle = findUiBundle(workspace.id, hash);
		if (bundle == null) {
			throw new WebApplicationException("Asset not found", Response.Status.NOT_FOUND);
		}
		if (bundle != null) {
			String normalizedPath = resourcePath == null || resourcePath.isBlank() ? bundle.rootPath : resourcePath;
			WorkspaceAssetBundleEntry entry = resolveBundleEntry(workspace, bundle, normalizedPath);
			if (entry != null && entry.blobHash != null && !entry.blobHash.isBlank()) {
				if (!entry.blobHash.equals(hash)) {
					return redirectToCanonicalBlob(tenantId, workspaceId, entry.blobHash, normalizedPath);
				}
				resourcePath = normalizedPath;
			}
		}
		StoredBlob blob = blobStore.load(workspace.id, hash)
			.orElseThrow(() -> new WebApplicationException("Asset not found", Response.Status.NOT_FOUND));
		String mimeType = blob.mimeType() == null || blob.mimeType().isBlank() ? "application/octet-stream" : blob.mimeType();
		Response.ResponseBuilder builder = cachedResponseBuilder(blob, mimeType);
		if ((resourcePath == null || resourcePath.isBlank() || "index.html".equals(resourcePath))) {
			String contentSecurityPolicy = buildContentSecurityPolicy(bundle);
			if (contentSecurityPolicy != null && !contentSecurityPolicy.isBlank()) {
				builder.header("Content-Security-Policy", contentSecurityPolicy);
			}
		}
		return builder.build();
	}

	private WorkspaceAssetBundleEntry resolveBundleEntry(Workspace workspace, WorkspaceAssetBundle bundle, String resourcePath) {
		if (bundle == null) {
			return null;
		}
		WorkspaceAssetBundleEntry entry = findBundleEntry(bundle.id, resourcePath);
		if (entry == null) {
			entry = fetchBundleEntry(workspace, bundle, resourcePath);
		}
		return entry;
	}

	private WorkspaceAssetBundle findUiBundle(UUID workspaceId, String rootHash) {
		WorkspaceAssetBundle bundle = entityManager.createQuery(
				"select b from WorkspaceAssetBundle b where b.workspace.id = :workspaceId and "
					+ "b.rootBlobHash = :rootHash",
				WorkspaceAssetBundle.class
			)
			.setParameter("workspaceId", workspaceId)
			.setParameter("rootHash", rootHash)
			.getResultStream()
			.findFirst()
			.orElse(null);
		if (bundle == null || bundle.type == null || bundle.type.isBlank() || !"ui".equals(bundle.type)) {
			return null;
		}
		return bundle;
	}

	private WorkspaceAssetBundleEntry findBundleEntry(UUID bundleId, String resourcePath) {
		return entityManager.createQuery(
				"select e from WorkspaceAssetBundleEntry e where e.bundle.id = :bundleId and "
					+ "e.resourcePath = :resourcePath",
				WorkspaceAssetBundleEntry.class
			)
			.setParameter("bundleId", bundleId)
			.setParameter("resourcePath", resourcePath)
			.getResultStream()
			.findFirst()
			.orElse(null);
	}

	private WorkspaceAssetBundleEntry fetchBundleEntry(Workspace workspace, WorkspaceAssetBundle bundle, String resourcePath) {
		String sourceUri = resolveBundleEntrySourceUri(bundle, resourcePath);
		if (sourceUri == null || sourceUri.isBlank()) {
			return null;
		}
		byte[] bytes = readResourceBytes(bundle, workspace.id, sourceUri);
		if (bytes == null || bytes.length == 0) {
			return null;
		}
		String mimeType = inferMimeType(resourcePath);
		StoredBlob stored = blobStore.store(workspace.id, bytes, mimeType);
		WorkspaceAssetBundleEntry entry = new WorkspaceAssetBundleEntry();
		entry.bundle = bundle;
		entry.resourcePath = resourcePath;
		entry.blobHash = stored.hash();
		entry.sourceUri = sourceUri;
		entry.mimeType = stored.mimeType();
		entityManager.persist(entry);
		return entry;
	}

	private String resolveBundleEntrySourceUri(WorkspaceAssetBundle bundle, String resourcePath) {
		if (bundle == null || resourcePath == null || resourcePath.isBlank()) {
			return null;
		}
		String normalized = normalizeResourcePath(resourcePath);
		if (normalized == null || normalized.isBlank()) {
			return null;
		}
		WorkspaceAssetBundleEntry existing = findBundleEntry(bundle.id, normalized);
		if (existing != null && existing.sourceUri != null && !existing.sourceUri.isBlank()) {
			return existing.sourceUri;
		}
		int slashIndex = normalized.lastIndexOf('/');
		while (slashIndex > 0) {
			String parentPath = normalized.substring(0, slashIndex);
			WorkspaceAssetBundleEntry parent = findBundleEntry(bundle.id, parentPath);
			if (parent != null && parent.sourceUri != null && !parent.sourceUri.isBlank()) {
				String suffix = normalized.substring(slashIndex + 1);
				String resolved = resolveSourceUri(parent.sourceUri, suffix);
				if (resolved != null && !resolved.isBlank()) {
					return resolved;
				}
			}
			slashIndex = normalized.lastIndexOf('/', slashIndex - 1);
		}
		if (bundle.rootSourceUri == null || bundle.rootSourceUri.isBlank()) {
			return null;
		}
		return resolveSourceUri(bundle.rootSourceUri, normalized);
	}

	private byte[] readResourceBytes(WorkspaceAssetBundle bundle, UUID workspaceId, String uri) {
		if (bundle == null || bundle.mcpServer == null) {
			return null;
		}
		JsonNode response = tryReadResource(workspaceId, bundle.mcpServer.id, uri);
		if (response == null) {
			return null;
		}
		return decodeResponseBytes(response);
	}

	private JsonNode tryReadResource(UUID workspaceId, UUID serverId, String uri) {
		try {
			JsonNode response = mcpRegistry.readResource(workspaceId, serverId, uri);
			return response == null || response.isNull() ? null : response;
		}
		catch (RuntimeException ignored) {
			return null;
		}
	}

	private byte[] decodeResponseBytes(JsonNode response) {
		byte[] bytes = decodeResourceBytes(response);
		if (bytes != null && bytes.length > 0) {
			return bytes;
		}
		JsonNode contents = response.has("contents") ? response.get("contents") : response.get("content");
		if (contents != null) {
			byte[] nested = decodeResourceBytes(contents);
			if (nested != null && nested.length > 0) {
				return nested;
			}
			if (contents.isArray() && !contents.isEmpty()) {
				byte[] first = decodeResourceBytes(contents.get(0));
				if (first != null && first.length > 0) {
					return first;
				}
			}
		}
		return null;
	}

	private byte[] decodeResourceBytes(JsonNode entry) {
		if (entry == null || entry.isNull()) {
			return null;
		}
		if (entry.isTextual()) {
			return entry.asText().getBytes(StandardCharsets.UTF_8);
		}
		JsonNode textNode = entry.get("text");
		if (textNode != null && textNode.isTextual()) {
			return textNode.asText().getBytes(StandardCharsets.UTF_8);
		}
		JsonNode base64Node = entry.get("base64");
		if (base64Node != null && base64Node.isTextual()) {
			try {
				return Base64.getDecoder().decode(base64Node.asText());
			}
			catch (IllegalArgumentException ignored) {
				return base64Node.asText().getBytes(StandardCharsets.UTF_8);
			}
		}
		JsonNode bytesNode = entry.get("bytes");
		if (bytesNode != null) {
			if (bytesNode.isArray()) {
				byte[] bytes = new byte[bytesNode.size()];
				for (int i = 0; i < bytesNode.size(); i++) {
					bytes[i] = (byte) bytesNode.get(i).asInt();
				}
				return bytes;
			}
			if (bytesNode.isTextual()) {
				try {
					return Base64.getDecoder().decode(bytesNode.asText());
				}
				catch (IllegalArgumentException ignored) {
					return bytesNode.asText().getBytes(StandardCharsets.UTF_8);
				}
			}
		}
		return null;
	}

	private String resolveSourceUri(String rootSourceUri, String resourcePath) {
		try {
			URI baseUri = URI.create(rootSourceUri);
			return baseUri.resolve(resourcePath).toString();
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private String normalizeResourcePath(String rest) {
		if (rest == null || rest.isBlank()) {
			return null;
		}
		String normalized = rest.startsWith("/") ? rest.substring(1) : rest;
		return normalized.isBlank() ? null : normalized;
	}

	private String inferMimeType(String resourcePath) {
		if (resourcePath == null || resourcePath.isBlank()) {
			return "application/octet-stream";
		}
		String lower = resourcePath.toLowerCase(Locale.ROOT);
		if (lower.endsWith(".html") || lower.endsWith(".htm")) {
			return "text/html";
		}
		if (lower.endsWith(".js") || lower.endsWith(".mjs") || lower.endsWith(".cjs")) {
			return "text/javascript";
		}
		if (lower.endsWith(".css")) {
			return "text/css";
		}
		if (lower.endsWith(".svg")) {
			return "image/svg+xml";
		}
		if (lower.endsWith(".json")) {
			return "application/json";
		}
		return "application/octet-stream";
	}

	private String buildContentSecurityPolicy(WorkspaceAssetBundle bundle) {
		if (bundle == null || bundle.cspJson == null || bundle.cspJson.isBlank()) {
			return null;
		}
		try {
			JsonNode csp = objectMapper.readTree(bundle.cspJson);
			if (csp == null || csp.isNull() || !csp.isObject()) {
				return null;
			}
			List<String> directives = new ArrayList<>();
			directives.add("default-src 'none'");
			directives.add(buildDomainDirective("connect-src", csp.get("connectDomains")));
			LinkedHashSet<String> resourceSources = readDomains(csp.get("resourceDomains"));
			if (!resourceSources.isEmpty()) {
				directives.add(joinDirective("script-src", resourceSources));
				directives.add(joinDirective("style-src", resourceSources));
				directives.add(joinDirective("img-src", resourceSources));
				directives.add(joinDirective("font-src", resourceSources));
				directives.add(joinDirective("media-src", resourceSources));
			}
			directives.add(buildDomainDirective("frame-src", csp.get("frameDomains")));
			directives.add(buildDomainDirective("base-uri", csp.get("baseUriDomains")));
			directives.removeIf(directive -> directive == null || directive.isBlank());
			return directives.isEmpty() ? null : String.join("; ", directives);
		}
		catch (Exception ex) {
			return null;
		}
	}

	private String buildDomainDirective(String name, JsonNode domainsNode) {
		LinkedHashSet<String> domains = readDomains(domainsNode);
		if (domains.isEmpty()) {
			return null;
		}
		return joinDirective(name, domains);
	}

	private String joinDirective(String name, LinkedHashSet<String> domains) {
		if (name == null || name.isBlank() || domains == null || domains.isEmpty()) {
			return null;
		}
		return name + " " + String.join(" ", domains);
	}

	private LinkedHashSet<String> readDomains(JsonNode domainsNode) {
		LinkedHashSet<String> domains = new LinkedHashSet<>();
		if (domainsNode == null || domainsNode.isNull()) {
			return domains;
		}
		if (domainsNode.isArray()) {
			for (JsonNode entry : domainsNode) {
				String domain = normalizeCspSource(entry == null ? null : entry.asText(null));
				if (domain != null) {
					domains.add(domain);
				}
			}
		}
		else {
			String domain = normalizeCspSource(domainsNode.asText(null));
			if (domain != null) {
				domains.add(domain);
			}
		}
		return domains;
	}

	private String normalizeCspSource(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		if (trimmed.isBlank()) {
			return null;
		}
		if ("'self'".equals(trimmed)
				|| "'none'".equals(trimmed)
				|| "'unsafe-inline'".equals(trimmed)
				|| "'unsafe-eval'".equals(trimmed)
				|| "data:".equals(trimmed)
				|| "blob:".equals(trimmed)) {
			return trimmed;
		}
		return trimmed;
	}

	private Response redirectToCanonicalBlob(UUID tenantId, UUID workspaceId, String blobHash, String resourcePath) {
		String suffix = resourcePath == null || resourcePath.isBlank() ? "" : "/" + resourcePath;
		URI location = URI.create("/api/tenants/" + tenantId + "/workspaces/" + workspaceId + "/ui-assets/" + blobHash + suffix);
		return Response.status(Response.Status.FOUND).location(location).build();
	}

	private Response.ResponseBuilder cachedResponseBuilder(StoredBlob blob, String mimeType) {
		EntityTag entityTag = new EntityTag(blob.hash());
		if (request != null) {
			Response.ResponseBuilder notModified = request.evaluatePreconditions(entityTag);
			if (notModified != null) {
				return applyCacheHeaders(notModified.tag(entityTag));
			}
		}
		return applyCacheHeaders(Response.ok(blob.bytes()).type(mimeType).tag(entityTag));
	}

	private Response.ResponseBuilder applyCacheHeaders(Response.ResponseBuilder builder) {
		CacheControl cacheControl = new CacheControl();
		cacheControl.setMaxAge(31536000);
		cacheControl.setPrivate(true);
		cacheControl.setNoTransform(false);
		builder.cacheControl(cacheControl);
		builder.header("Cache-Control", "private, max-age=31536000, immutable");
		return builder;
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
