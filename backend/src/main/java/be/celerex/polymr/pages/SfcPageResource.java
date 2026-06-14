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

package be.celerex.polymr.pages;

import be.celerex.polymr.model.SfcPage;
import be.celerex.polymr.model.SfcPageDependency;
import be.celerex.polymr.model.SfcPageInstallation;
import be.celerex.polymr.model.SfcPageType;
import be.celerex.polymr.model.SfcPageVersion;
import be.celerex.polymr.model.Session;
import be.celerex.polymr.model.SessionParticipant;
import be.celerex.polymr.model.SessionParticipantRole;
import be.celerex.polymr.model.SessionStatus;
import be.celerex.polymr.model.SessionTagSelection;
import be.celerex.polymr.model.SessionVisibility;
import be.celerex.polymr.model.McpServer;
import be.celerex.polymr.model.McpProtocol;
import be.celerex.polymr.model.McpServerVisibility;
import be.celerex.polymr.model.Assistant;
import be.celerex.polymr.model.TenantMembership;
import be.celerex.polymr.model.User;
import be.celerex.polymr.model.Workspace;
import be.celerex.polymr.pages.dto.SfcPageAttachmentUploadResponse;
import be.celerex.polymr.pages.dto.SfcPageCompileRequest;
import be.celerex.polymr.pages.dto.SfcPageDraftRequest;
import be.celerex.polymr.pages.dto.SfcPageRequest;
import be.celerex.polymr.pages.dto.SfcPageNamespaceChildrenResponse;
import be.celerex.polymr.pages.dto.SfcPageNamespaceEntryResponse;
import be.celerex.polymr.pages.dto.SfcPageResponse;
import be.celerex.polymr.pages.dto.SfcPageRuntimeResponse;
import be.celerex.polymr.pages.dto.SfcPageScriptCallRequest;
import be.celerex.polymr.pages.dto.SfcPageSlugValidationResponse;
import be.celerex.polymr.pages.dto.SfcPageVersionResponse;
import be.celerex.polymr.pages.dto.SfcPageExportEntry;
import be.celerex.polymr.pages.dto.SfcPageExportResponse;
import be.celerex.polymr.pages.dto.SfcPageImportRequest;
import be.celerex.polymr.pages.dto.SfcPageImportResponse;
import be.celerex.polymr.pages.compiler.PageCompilationRequest;
import be.celerex.polymr.pages.compiler.PageCompilationResult;
import be.celerex.polymr.pages.compiler.PageCompilerService;
import be.celerex.polymr.profile.UserExecutionModeService;
import be.celerex.polymr.scripts.ScriptRuntimeService;
import be.celerex.polymr.model.Script;
import be.celerex.polymr.tenant.TenantAccessService;
import be.celerex.polymr.util.SlugSupport;
import be.celerex.polymr.workflow.ConversationWorkflowStartup;
import be.celerex.polymr.workflow.WorkflowDefinitionService;
import be.celerex.polymr.mcp.WorkflowMcpSnapshotService;
import be.celerex.polymr.mcp.McpToolCatalogService;
import be.celerex.polymr.mcp.VirtualMcpService;
import be.celerex.polymr.model.WorkflowDefinition;
import be.celerex.polymr.model.WorkflowDefinitionVersion;
import be.celerex.polymr.model.WorkflowRun;
import be.celerex.polymr.model.WorkflowRunStatus;
import be.celerex.polymr.storage.AttachmentLinkService;
import be.celerex.polymr.storage.PublicBlobLink;
import be.celerex.polymr.storage.PublicWorkspaceBlobStore;
import be.celerex.polymr.storage.StoredBlob;
import be.celerex.polymr.storage.WorkspaceBlobStore;
import be.celerex.polymr.workflow.runtime.ConversationGraphState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Path("/api/tenants/{tenantId}/workspaces/{workspaceId}/pages")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class SfcPageResource {
	@Inject
	EntityManager entityManager;

	@Inject
	TenantAccessService tenantAccessService;

	@Inject
	SfcPageCatalogService catalogService;

	@Inject
	WorkflowDefinitionService workflowDefinitionService;

	@Inject
	WorkflowMcpSnapshotService snapshotService;

	@Inject
	ConversationWorkflowStartup conversationWorkflowStartup;

	@Inject
	McpToolCatalogService toolCatalogService;

	@Inject
	VirtualMcpService virtualMcpService;

	@Inject
	ObjectMapper objectMapper;

	@Inject
	ScriptRuntimeService scriptRuntimeService;

	@Inject
	UserExecutionModeService userExecutionModeService;

	@Inject
	WorkspaceBlobStore blobStore;

	@Inject
	AttachmentLinkService attachmentLinkService;

	@Inject
	PageCompilerService pageCompilerService;

	@Context
	SecurityContext securityContext;

	@GET
	@Path("/catalog")
	public Object catalog(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId) {
		requireMembership(tenantId);
		return catalogService.buildCatalog(workspaceId);
	}

	@GET
	@Path("/installed")
	public List<UUID> listInstalled(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId) {
		TenantMembership membership = requireMembership(tenantId);
		return entityManager.createQuery(
				"select i.page.id from SfcPageInstallation i where i.user.id = :userId and i.page.workspace.id "
					+ "= :workspaceId "
					+ "and i.page.disabled = false",
				UUID.class
			)
			.setParameter("userId", membership.user.id)
			.setParameter("workspaceId", workspaceId)
			.getResultList();
	}

	@GET
	@Path("/installed/details")
	public List<SfcPageResponse> listInstalledDetails(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId) {
		TenantMembership membership = requireMembership(tenantId);
		List<SfcPage> pages = entityManager.createQuery(
				"select p from SfcPage p join SfcPageInstallation i on i.page.id = p.id where i.user.id = :userId "
					+ "and p.workspace.id = :workspaceId and p.disabled = false order by lower(p.name)",
				SfcPage.class
			)
			.setParameter("userId", membership.user.id)
			.setParameter("workspaceId", workspaceId)
			.getResultList();
		return toInstalledResponses(pages, membership.user.id);
	}

	@GET
	public Object list(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@jakarta.ws.rs.QueryParam("namespace") String namespace,
			@jakarta.ws.rs.QueryParam("non_recursive") Boolean nonRecursive) {
		requireMembership(tenantId);
		boolean namespaceChildrenOnly = nonRecursive != null && nonRecursive;
		if (!namespaceChildrenOnly) {
			return listAllPages(workspaceId);
		}
		return listNamespaceChildren(workspaceId, normalizeNamespace(namespace));
	}

	@GET
	@Path("/slug-validation")
	public SfcPageSlugValidationResponse validateSlug(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@jakarta.ws.rs.QueryParam("namespace") String namespace,
			@jakarta.ws.rs.QueryParam("slug") String slug,
			@jakarta.ws.rs.QueryParam("exclude_page_id") UUID excludePageId) {
		requireMembership(tenantId);
		String normalizedNamespace = normalizeNamespace(namespace);
		String normalizedSlug = slug == null ? "" : SlugSupport.normalizeSlugValue(slug);
		if (normalizedSlug.isBlank()) {
			return new SfcPageSlugValidationResponse(false, normalizedSlug, normalizedNamespace, "Slug is required.");
		}
		boolean exists = slugExists(workspaceId, normalizedNamespace, normalizedSlug, excludePageId);
		if (exists) {
			return new SfcPageSlugValidationResponse(false, normalizedSlug, normalizedNamespace, "Slug must be unique within the namespace.");
		}
		return new SfcPageSlugValidationResponse(true, normalizedSlug, normalizedNamespace, "");
	}

	@GET
	@Path("/{pageId}")
	public SfcPageResponse get(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("pageId") UUID pageId) {
		requireMembership(tenantId);
		SfcPage page = requireActivePage(workspaceId, pageId);
		boolean hasDraft = hasDraftVersion(pageId);
		boolean released = isReleased(pageId);
		return toResponse(page, hasDraft, released);
	}

	@GET
	@Path("/{pageId}/bundle")
	@Transactional
	public SfcPageRuntimeResponse getBundle(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("pageId") UUID pageId) {
		TenantMembership membership = requireMembership(tenantId);
		SfcPage page = requireActivePage(workspaceId, pageId);
		SfcPageVersion version = requireRuntimePageVersion(page, membership.user.id);
		return new SfcPageRuntimeResponse(
			page.id,
			page.name,
			page.label,
			page.namespace,
			page.slug,
			page.routeSuffix,
			page.inputParams,
			page.queryParams,
			page.importAllowlist,
			page.workspace == null ? null : page.workspace.externalFrontendImports,
			version.compiledBundle,
			version.compileErrors
		);
	}

	@GET
	@Path("/{pageId}/export")
	public SfcPageExportResponse exportPage(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("pageId") UUID pageId,
			@jakarta.ws.rs.QueryParam("include_dependencies") boolean includeDependencies) {
		requireMembership(tenantId);
		SfcPage page = requireActivePage(workspaceId, pageId);
		List<SfcPage> pages = includeDependencies ? collectDependencies(page) : List.of(page);
		Map<UUID, List<String>> dependencySlugs = loadDependencySlugs(pages);
		List<SfcPageExportEntry> entries = pages.stream()
			.map(entry -> toExportEntry(entry, dependencySlugs.getOrDefault(entry.id, List.of())))
			.toList();
		return new SfcPageExportResponse(entries);
	}

	@GET
	@Path("/slug/{slug}")
	@Transactional
	public SfcPageRuntimeResponse getBySlug(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("slug") String slug) {
		TenantMembership membership = requireMembership(tenantId);
		if (slug == null || slug.isBlank()) {
			throw new WebApplicationException("Slug is required", Response.Status.BAD_REQUEST);
		}
		String normalizedSlug = slug.trim().toLowerCase();
		SfcPage page = entityManager.createQuery(
				"select p from SfcPage p where p.workspace.id = :workspaceId and "
					+ "lower(p.slug) = :slug and p.disabled = false",
				SfcPage.class
			)
			.setParameter("workspaceId", workspaceId)
			.setParameter("slug", normalizedSlug)
			.getResultStream()
			.findFirst()
			.orElse(null);
		if (page == null) {
			throw new WebApplicationException("Page not found", Response.Status.NOT_FOUND);
		}
		SfcPageVersion version = requireRuntimePageVersion(page, membership.user.id);
		return new SfcPageRuntimeResponse(
			page.id,
			page.name,
			page.label,
			page.namespace,
			page.slug,
			page.routeSuffix,
			page.inputParams,
			page.queryParams,
			page.importAllowlist,
			page.workspace == null ? null : page.workspace.externalFrontendImports,
			version.compiledBundle,
			version.compileErrors
		);
	}

	@POST
	@Path("/scripts/call")
	@Transactional
	public Object callScript(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			SfcPageScriptCallRequest request) {
		TenantMembership membership = requireMembership(tenantId);
		if (request == null || request.path() == null || request.path().isBlank()) {
			throw new WebApplicationException("Script path is required", Response.Status.BAD_REQUEST);
		}
		Script script = findCallableScript(workspaceId, request.path());
		if (script == null
				|| userExecutionModeService.resolveScriptVersion(script, membership.user.id) == null) {
			throw new WebApplicationException("Script not available", Response.Status.NOT_FOUND);
		}
		JsonNode input = request.input() == null ? objectMapper.createObjectNode() : request.input();
		JsonNode output = scriptRuntimeService.runScript(script, membership.user.id, null, input);
		return output == null || output.isNull() ? null : objectMapper.convertValue(output, Object.class);
	}

	@POST
	@Transactional
	public SfcPageResponse create(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			SfcPageRequest request) {
		TenantMembership membership = requireMembership(tenantId);
		Workspace workspace = requireWorkspace(tenantId, workspaceId);
		if (request == null || request.name() == null || request.name().isBlank()) {
			throw new WebApplicationException("Name is required", Response.Status.BAD_REQUEST);
		}
		String namespace = normalizeNamespace(request.namespace());
		String slug = SlugSupport.buildSlug(namespace, request.name());
		if (slugExists(workspaceId, namespace, slug, null)) {
			throw new WebApplicationException("Slug is already in use in this namespace", Response.Status.CONFLICT);
		}
		SfcPage page = new SfcPage();
		page.workspace = workspace;
		page.createdBy = entityManager.find(User.class, membership.user.id);
		page.name = request.name().trim();
		page.label = sanitizeLabel(request.label());
		page.description = request.description();
		page.namespace = namespace;
		page.slug = slug;
		page.type = parseType(request.type());
		page.menuVisible = resolveMenuVisible(page.type, request.menu_visible());
		page.iconSvg = request.icon_svg();
		page.routeSuffix = sanitizeRouteSuffix(request.route_suffix());
		page.usageGuide = request.usage_guide();
		page.importAllowlist = request.import_allowlist();
		page.queryParams = request.query_params();
		page.inputParams = request.input_params();
		entityManager.persist(page);
		updateDependencies(page, request.dependency_ids());
		boolean hasDraft = hasDraftVersion(page.id);
		boolean released = isReleased(page.id);
		return toResponse(page, hasDraft, released);
	}

	@POST
	@Path("/import")
	@Transactional
	public SfcPageImportResponse importPages(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			SfcPageImportRequest request) {
		TenantMembership membership = requireMembership(tenantId);
		Workspace workspace = requireWorkspace(tenantId, workspaceId);
		if (request == null || request.pages() == null || request.pages().isEmpty()) {
			throw new WebApplicationException("Pages are required", Response.Status.BAD_REQUEST);
		}
		boolean updateSlugs = request.update_slugs();
		Map<String, SfcPage> createdBySlug = new HashMap<>();
		Map<String, String> slugMapping = new HashMap<>();
		for (SfcPageExportEntry entry : request.pages()) {
			if (entry == null || entry.name() == null || entry.name().isBlank()) {
				continue;
			}
			String namespace = null;
			String originalSlug = entry.slug() == null || entry.slug().isBlank()
				? SlugSupport.buildSlug(namespace, entry.name())
				: SlugSupport.normalizeSlugValue(entry.slug());
			String desiredSlug = originalSlug;
			String finalSlug = updateSlugs ? ensureUniqueSlug(workspaceId, namespace, desiredSlug) : desiredSlug;
			if (!updateSlugs && slugExists(workspaceId, namespace, finalSlug, null)) {
				throw new WebApplicationException("Slug is already in use: " + finalSlug, Response.Status.CONFLICT);
			}
			SfcPage page = new SfcPage();
			page.workspace = workspace;
			page.createdBy = entityManager.find(User.class, membership.user.id);
			page.name = entry.name().trim();
			page.label = sanitizeLabel(entry.label());
			page.description = entry.description();
			page.namespace = namespace;
			page.slug = finalSlug;
			page.type = parseType(entry.type());
			page.menuVisible = resolveMenuVisible(page.type, entry.menu_visible());
			page.iconSvg = entry.icon_svg();
			page.routeSuffix = sanitizeRouteSuffix(entry.route_suffix());
			page.usageGuide = entry.usage_guide();
			page.importAllowlist = entry.import_allowlist();
			page.queryParams = entry.query_params();
			page.inputParams = entry.input_params();
			entityManager.persist(page);
			createdBySlug.put(originalSlug, page);
			slugMapping.put(originalSlug, finalSlug);
		}
		for (SfcPageExportEntry entry : request.pages()) {
			if (entry == null) {
				continue;
			}
			String originalSlug = entry.slug() == null || entry.slug().isBlank()
				? SlugSupport.buildSlug(null, entry.name())
				: SlugSupport.normalizeSlugValue(entry.slug());
			SfcPage page = createdBySlug.get(originalSlug);
			if (page == null) {
				continue;
			}
			List<String> dependencies = entry.dependency_slugs() == null ? List.of() : entry.dependency_slugs();
			List<UUID> dependencyIds = new ArrayList<>();
			for (String slug : dependencies) {
				String mapped = slugMapping.getOrDefault(slug, slug);
				SfcPage dependency = findPageBySlug(workspaceId, mapped);
				if (dependency != null && !dependency.disabled) {
					dependencyIds.add(dependency.id);
				}
			}
			updateDependencies(page, dependencyIds);
			if (entry.source_sfc() != null && !entry.source_sfc().isBlank()) {
				SfcPageVersion draft = new SfcPageVersion();
				draft.page = page;
				draft.createdBy = entityManager.find(User.class, membership.user.id);
				draft.version = nextPageVersionNumber(page.id);
				draft.sourceSfc = entry.source_sfc();
				draft.designSessionId = createDesignSession(membership, page, null).id;
				entityManager.persist(draft);
			}
		}
		List<SfcPageResponse> responses = createdBySlug.values()
			.stream()
			.map(page -> toResponse(page, hasDraftVersion(page.id), isReleased(page.id)))
			.toList();
		return new SfcPageImportResponse(responses, slugMapping);
	}

	@PUT
	@Path("/{pageId}")
	@Transactional
	public SfcPageResponse update(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("pageId") UUID pageId,
			SfcPageRequest request) {
		requireMembership(tenantId);
		SfcPage page = requireActivePage(workspaceId, pageId);
		if (request == null) {
			throw new WebApplicationException("Request is required", Response.Status.BAD_REQUEST);
		}
		if (request.name() != null && !request.name().isBlank()) {
			page.name = request.name().trim();
		}
		if (request.label() != null) {
			page.label = sanitizeLabel(request.label());
		}
		if (request.description() != null) {
			page.description = request.description();
		}
		String namespace = page.namespace;
		if (request.namespace() != null) {
			namespace = normalizeNamespace(request.namespace());
		}
		String slug = page.slug;
		if (request.name() != null || request.namespace() != null) {
			slug = SlugSupport.buildSlug(namespace, request.name() != null && !request.name().isBlank() ? request.name() : page.name);
		}
		if (!sameNamespace(namespace, page.namespace) || !slug.equalsIgnoreCase(page.slug)) {
			if (slugExists(workspaceId, namespace, slug, page.id)) {
				throw new WebApplicationException("Slug is already in use in this namespace", Response.Status.CONFLICT);
			}
		}
		page.namespace = namespace;
		page.slug = slug;
		if (request.type() != null && !request.type().isBlank()) {
			page.type = parseType(request.type());
		}
		if (request.menu_visible() != null) {
			page.menuVisible = request.menu_visible();
		}
		if (request.icon_svg() != null) {
			page.iconSvg = request.icon_svg();
		}
		page.routeSuffix = sanitizeRouteSuffix(request.route_suffix());
		if (request.usage_guide() != null) {
			page.usageGuide = request.usage_guide();
		}
		if (request.import_allowlist() != null) {
			page.importAllowlist = request.import_allowlist();
		}
		if (request.query_params() != null) {
			page.queryParams = request.query_params();
		}
		if (request.input_params() != null) {
			page.inputParams = request.input_params();
		}
		if (request.dependency_ids() != null) {
			updateDependencies(page, request.dependency_ids());
		}
		boolean hasDraft = hasDraftVersion(page.id);
		boolean released = isReleased(page.id);
		return toResponse(page, hasDraft, released);
	}

	@GET
	@Path("/{pageId}/versions")
	public List<SfcPageVersionResponse> listVersions(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("pageId") UUID pageId) {
		requireMembership(tenantId);
		requireActivePage(workspaceId, pageId);
		List<SfcPageVersion> versions = entityManager.createQuery(
				"select v from SfcPageVersion v where v.page.id = :pageId order by v.updatedAt desc",
				SfcPageVersion.class
			)
			.setParameter("pageId", pageId)
			.getResultList();
		return versions.stream().map(this::toVersionResponse).toList();
	}

	@GET
	@Path("/{pageId}/draft")
	@Transactional
	public SfcPageVersionResponse getDraft(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("pageId") UUID pageId,
			@jakarta.ws.rs.QueryParam("assistant_id") UUID assistantId) {
		TenantMembership membership = requireMembership(tenantId);
		SfcPage page = requireActivePage(workspaceId, pageId);
		SfcPageVersion draft = findDraft(pageId);
		if (draft == null) {
			draft = new SfcPageVersion();
			draft.page = page;
			draft.createdBy = entityManager.find(User.class, membership.user.id);
			draft.version = nextPageVersionNumber(page.id);
			if (page.activeVersion != null && page.activeVersion.sourceSfc != null) {
				draft.sourceSfc = page.activeVersion.sourceSfc;
			}
			draft.designSessionId = createDesignSession(membership, page, assistantId).id;
			entityManager.persist(draft);
		}
		else if (draft.designSessionId == null) {
			draft.designSessionId = createDesignSession(membership, page, assistantId).id;
		}
		return toVersionResponse(draft);
	}

	@PUT
	@Path("/{pageId}/draft")
	@Transactional
	public SfcPageVersionResponse updateDraft(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("pageId") UUID pageId,
			SfcPageDraftRequest request) {
		TenantMembership membership = requireMembership(tenantId);
		SfcPage page = requireActivePage(workspaceId, pageId);
		SfcPageVersion draft = findDraft(pageId);
		if (draft == null) {
			draft = new SfcPageVersion();
			draft.page = page;
			draft.createdBy = entityManager.find(User.class, membership.user.id);
			draft.version = nextPageVersionNumber(page.id);
			draft.designSessionId = createDesignSession(membership, page, null).id;
			entityManager.persist(draft);
		}
		else if (draft.designSessionId == null) {
			draft.designSessionId = createDesignSession(membership, page, null).id;
		}
		if (request != null && request.source_sfc() != null) {
			draft.sourceSfc = request.source_sfc();
		}
		return toVersionResponse(draft);
	}

	@POST
	@Path("/{pageId}/compile")
	@Transactional
	public SfcPageVersionResponse compileDraft(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("pageId") UUID pageId,
			SfcPageCompileRequest request) {
		TenantMembership membership = requireMembership(tenantId);
		SfcPage page = requireActivePage(workspaceId, pageId);
		SfcPageVersion draft = findDraft(pageId);
		if (draft == null) {
			draft = new SfcPageVersion();
			draft.page = page;
			draft.createdBy = entityManager.find(User.class, membership.user.id);
			draft.version = nextPageVersionNumber(page.id);
			draft.designSessionId = createDesignSession(membership, page, null).id;
			entityManager.persist(draft);
		}
		else if (draft.designSessionId == null) {
			draft.designSessionId = createDesignSession(membership, page, null).id;
		}
		if (request != null) {
			if (request.source_sfc() != null) {
				draft.sourceSfc = request.source_sfc();
			}
			draft.compiledBundle = request.compiled_bundle();
			draft.compileErrors = request.compile_errors();
		}
		boolean hasErrors = draft.compileErrors != null && !draft.compileErrors.isBlank();
		if (hasErrors) {
			draft.compiledBundle = null;
		}
		return toVersionResponse(draft);
	}

	@POST
	@Path("/{pageId}/attachments")
	@Consumes("*/*")
	@Transactional
	public SfcPageAttachmentUploadResponse uploadAttachment(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("pageId") UUID pageId,
			@jakarta.ws.rs.QueryParam("filename") String filename,
			InputStream body,
			@Context HttpHeaders headers) {
		requireMembership(tenantId);
		requireActivePage(workspaceId, pageId);
		Workspace workspace = requireWorkspace(tenantId, workspaceId);
		if (body == null) {
			throw new WebApplicationException("Attachment file is required", Response.Status.BAD_REQUEST);
		}
		byte[] bytes = readBytes(body);
		if (bytes.length == 0) {
			throw new WebApplicationException("Attachment file is empty", Response.Status.BAD_REQUEST);
		}
		String mimeType = headers == null ? null : headers.getHeaderString("Content-Type");
		if (mimeType == null || mimeType.isBlank()) {
			mimeType = MediaType.APPLICATION_OCTET_STREAM;
		}
		StoredBlob stored = blobStore.store(workspace.id, bytes, mimeType);
		return toAttachmentUploadResponse(tenantId, workspace.id, stored, filename);
	}

	@GET
	@Path("/{pageId}/attachments/public-link")
	@Transactional
	public SfcPageAttachmentUploadResponse createAttachmentPublicLink(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("pageId") UUID pageId,
			@jakarta.ws.rs.QueryParam("blob_uri") String blobUri,
			@jakarta.ws.rs.QueryParam("filename") String filename) {
		requireMembership(tenantId);
		requireActivePage(workspaceId, pageId);
		BlobReference reference = parseBlobUri(blobUri);
		if (reference == null || !workspaceId.equals(reference.workspaceId())) {
			throw new WebApplicationException("blob_uri must be in the form blob:/workspaceId/hash", Response.Status.BAD_REQUEST);
		}
		StoredBlob stored = blobStore.load(workspaceId, reference.hash())
			.orElseThrow(() -> new WebApplicationException("Attachment not found", Response.Status.NOT_FOUND));
		return toAttachmentUploadResponse(tenantId, workspaceId, stored, filename);
	}

	@POST
	@Path("/{pageId}/approve")
	@Transactional
	public SfcPageVersionResponse approve(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("pageId") UUID pageId) {
		TenantMembership membership = requireMembership(tenantId);
		SfcPage page = requireActivePage(workspaceId, pageId);
		SfcPageVersion draft = findDraft(pageId);
		if (draft == null || !isCompiled(draft)) {
			throw new WebApplicationException("Draft has not been compiled", Response.Status.CONFLICT);
		}
		SfcPageVersion previous = page.activeVersion;
		if (previous != null && previous != draft && previous.deprecatedAt == null) {
			previous.deprecatedAt = Instant.now();
		}
		draft.releasedBy = entityManager.find(User.class, membership.user.id);
		draft.releasedAt = Instant.now();
		page.activeVersion = draft;
		archiveDesignSession(draft.designSessionId);
		return toVersionResponse(draft);
	}

	@DELETE
	@Path("/{pageId}/draft")
	@Transactional
	public Response deleteDraft(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("pageId") UUID pageId) {
		requireMembership(tenantId);
		requireActivePage(workspaceId, pageId);
		SfcPageVersion draft = findDraft(pageId);
		if (draft != null) {
			archiveDesignSession(draft.designSessionId);
			entityManager.remove(draft);
		}
		return Response.noContent().build();
	}

	private SfcPageAttachmentUploadResponse toAttachmentUploadResponse(
			UUID tenantId,
			UUID workspaceId,
			StoredBlob stored,
			String filename) {
		PublicWorkspaceBlobStore publicStore = blobStore instanceof PublicWorkspaceBlobStore candidate ? candidate : null;
		PublicBlobLink link = attachmentLinkService.resolveUserLink(tenantId, workspaceId, stored.hash(), publicStore)
			.orElseThrow(
				() -> new WebApplicationException("Unable to create attachment link", Response.Status.INTERNAL_SERVER_ERROR)
			);
		String normalizedFilename = filename == null ? "" : filename.trim();
		return new SfcPageAttachmentUploadResponse(
			"blob:/" + workspaceId + "/" + stored.hash(),
			stored.hash(),
			normalizedFilename,
			stored.mimeType(),
			stored.byteSize(),
			link.uri().toString(),
			link.expiresAt() == null ? null : link.expiresAt().toString()
		);
	}

	private BlobReference parseBlobUri(String blobUri) {
		if (blobUri == null || blobUri.isBlank() || !blobUri.startsWith("blob:/")) {
			return null;
		}
		String value = blobUri.substring("blob:/".length());
		int slash = value.indexOf('/');
		if (slash <= 0 || slash == value.length() - 1) {
			return null;
		}
		try {
			return new BlobReference(UUID.fromString(value.substring(0, slash)), value.substring(slash + 1));
		}
		catch (IllegalArgumentException exception) {
			return null;
		}
	}

	private byte[] readBytes(InputStream stream) {
		try {
			return stream.readAllBytes();
		}
		catch (IOException exception) {
			throw new WebApplicationException("Unable to read attachment", Response.Status.BAD_REQUEST);
		}
	}

	private void updateDependencies(SfcPage page, List<UUID> dependencyIds) {
		if (page == null) {
			return;
		}
		entityManager.createQuery("delete from SfcPageDependency d where d.page.id = :pageId")
			.setParameter("pageId", page.id)
			.executeUpdate();
		if (dependencyIds == null) {
			return;
		}
		for (UUID dependencyId : dependencyIds) {
			if (dependencyId == null) {
				continue;
			}
			SfcPage dependency = entityManager.find(SfcPage.class, dependencyId);
			if (dependency == null || !dependency.workspace.id.equals(page.workspace.id)) {
				continue;
			}
			SfcPageDependency entry = new SfcPageDependency();
			entry.page = page;
			entry.dependsOn = dependency;
			entityManager.persist(entry);
		}
	}

	@POST
	@Path("/{pageId}/install")
	@Transactional
	public Response install(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("pageId") UUID pageId) {
		TenantMembership membership = requireMembership(tenantId);
		SfcPage page = requireActivePage(workspaceId, pageId);
		requireRuntimePageVersion(page, membership.user.id);
		installRecursive(membership.user.id, workspaceId, pageId);
		return Response.noContent().build();
	}

	@POST
	@Path("/{pageId}/uninstall")
	@Transactional
	public Response uninstall(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("pageId") UUID pageId) {
		TenantMembership membership = requireMembership(tenantId);
		requireActivePage(workspaceId, pageId);
		entityManager.createQuery("delete from SfcPageInstallation i where i.user.id = :userId and i.page.id = :pageId")
			.setParameter("userId", membership.user.id)
			.setParameter("pageId", pageId)
			.executeUpdate();
		return Response.noContent().build();
	}

	@DELETE
	@Path("/{pageId}")
	@Transactional
	public Response deletePage(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("pageId") UUID pageId) {
		requireMembership(tenantId);
		SfcPage page = requirePageIncludingDisabled(workspaceId, pageId);
		page.disabled = true;
		entityManager.createQuery("delete from SfcPageInstallation i where i.page.id = :pageId")
			.setParameter("pageId", page.id)
			.executeUpdate();
		return Response.noContent().build();
	}

	private void installRecursive(UUID userId, UUID workspaceId, UUID pageId) {
		Set<UUID> visited = new HashSet<>();
		ArrayDeque<UUID> queue = new ArrayDeque<>();
		queue.add(pageId);
		while (!queue.isEmpty()) {
			UUID current = queue.pop();
			if (!visited.add(current)) {
				continue;
			}
			SfcPage page = entityManager.find(SfcPage.class, current);
			if (page == null || !page.workspace.id.equals(workspaceId) || page.disabled) {
				continue;
			}
			if (userExecutionModeService.resolvePageVersion(page, userId) == null) {
				continue;
			}
			boolean installed = !entityManager.createQuery(
					"select i.id from SfcPageInstallation i where i.user.id = :userId and i.page.id = :pageId",
					UUID.class
				)
				.setParameter("userId", userId)
				.setParameter("pageId", current)
				.setMaxResults(1)
				.getResultList()
				.isEmpty();
			if (!installed) {
				SfcPageInstallation installation = new SfcPageInstallation();
				installation.page = page;
				installation.user = entityManager.find(User.class, userId);
				entityManager.persist(installation);
			}
			List<UUID> dependencies = entityManager.createQuery("select d.dependsOn.id from SfcPageDependency d where d.page.id = :pageId", UUID.class)
				.setParameter("pageId", current)
				.getResultList();
			dependencies.forEach(queue::add);
		}
	}

	private SfcPageVersion findDraft(UUID pageId) {
		return entityManager.createQuery(
				"select v from SfcPageVersion v where v.page.id = :pageId and v.releasedAt is null",
				SfcPageVersion.class
			)
			.setParameter("pageId", pageId)
			.setMaxResults(1)
			.getResultStream()
			.findFirst()
			.orElse(null);
	}

	private boolean hasDraftVersion(UUID pageId) {
		if (pageId == null) {
			return false;
		}
		return !entityManager.createQuery("select v.id from SfcPageVersion v where v.page.id = :pageId and v.releasedAt is null", UUID.class)
			.setParameter("pageId", pageId)
			.setMaxResults(1)
			.getResultList()
			.isEmpty();
	}

	private boolean isReleased(UUID pageId) {
		if (pageId == null) {
			return false;
		}
		return !entityManager.createQuery(
				"select v.id from SfcPageVersion v where v.page.id = :pageId "
					+ "and v.releasedAt is not null and v.deprecatedAt is null",
				UUID.class
			)
			.setParameter("pageId", pageId)
			.setMaxResults(1)
			.getResultList()
			.isEmpty();
	}

	private SfcPage requireActivePage(UUID workspaceId, UUID pageId) {
		SfcPage page = requirePageIncludingDisabled(workspaceId, pageId);
		if (page.disabled) {
			throw new WebApplicationException("Page not found", Response.Status.NOT_FOUND);
		}
		return page;
	}

	private SfcPage requirePageIncludingDisabled(UUID workspaceId, UUID pageId) {
		SfcPage page = entityManager.find(SfcPage.class, pageId);
		if (page == null || !page.workspace.id.equals(workspaceId)) {
			throw new WebApplicationException("Page not found", Response.Status.NOT_FOUND);
		}
		return page;
	}

	private List<SfcPage> collectDependencies(SfcPage root) {
		List<SfcPage> result = new ArrayList<>();
		if (root == null) {
			return result;
		}
		Set<UUID> visited = new HashSet<>();
		ArrayDeque<UUID> queue = new ArrayDeque<>();
		queue.add(root.id);
		while (!queue.isEmpty()) {
			UUID current = queue.pop();
			if (!visited.add(current)) {
				continue;
			}
			SfcPage page = entityManager.find(SfcPage.class, current);
			if (page == null || page.disabled) {
				continue;
			}
			result.add(page);
			List<UUID> dependencies = entityManager.createQuery("select d.dependsOn.id from SfcPageDependency d where d.page.id = :pageId", UUID.class)
				.setParameter("pageId", current)
				.getResultList();
			dependencies.forEach(queue::add);
		}
		return result;
	}

	private Map<UUID, List<String>> loadDependencySlugs(List<SfcPage> pages) {
		Map<UUID, List<String>> result = new HashMap<>();
		if (pages == null || pages.isEmpty()) {
			return result;
		}
		List<UUID> pageIds = pages.stream()
			.map(page -> page.id)
			.toList();
		List<Object[]> rows = entityManager.createQuery(
				"select d.page.id, d.dependsOn.slug from SfcPageDependency d where d.page.id in :pageIds",
				Object[].class
			)
			.setParameter("pageIds", pageIds)
			.getResultList();
		for (Object[] row : rows) {
			UUID pageId = (UUID) row[0];
			String slug = (String) row[1];
			result.computeIfAbsent(pageId, key -> new ArrayList<>())
				.add(slug);
		}
		return result;
	}

	private SfcPageExportEntry toExportEntry(SfcPage page, List<String> dependencySlugs) {
		String source = page.activeVersion == null ? null : page.activeVersion.sourceSfc;
		return new SfcPageExportEntry(
			page.name,
			page.label,
			page.description,
			page.slug,
			page.type == null ? null : page.type.name(),
			page.menuVisible,
			page.iconSvg,
			page.routeSuffix,
			page.usageGuide,
			page.importAllowlist,
			page.queryParams,
			page.inputParams,
			source,
			dependencySlugs
		);
	}

	private boolean slugExists(UUID workspaceId, String namespace, String slug, UUID excludedId) {
		if (slug == null || slug.isBlank()) {
			return false;
		}
		StringBuilder query = new StringBuilder("select p.id from SfcPage p where p.workspace.id = :workspaceId and lower(p.slug) = :slug");
		if (namespace == null) {
			query.append(" and (p.namespace is null or trim(p.namespace) = '')");
		}
		else {
			query.append(" and lower(p.namespace) = :namespace");
		}
		if (excludedId != null) {
			query.append(" and p.id <> :excludedId");
		}
		var typedQuery = entityManager.createQuery(query.toString(), UUID.class)
			.setParameter("workspaceId", workspaceId)
			.setParameter("slug", slug.toLowerCase());
		if (namespace != null) {
			typedQuery.setParameter("namespace", namespace.toLowerCase());
		}
		if (excludedId != null) {
			typedQuery.setParameter("excludedId", excludedId);
		}
		return !typedQuery.setMaxResults(1).getResultList().isEmpty();
	}

	private String ensureUniqueSlug(UUID workspaceId, String namespace, String slug) {
		if (slug == null || slug.isBlank()) {
			return slug;
		}
		String base = slug.trim();
		String candidate = base;
		int index = 1;
		while (slugExists(workspaceId, namespace, candidate, null)) {
			candidate = base + "-copy" + (index > 1 ? "-" + index : "");
			index += 1;
		}
		return candidate;
	}

	private List<SfcPageResponse> listAllPages(UUID workspaceId) {
		List<SfcPage> pages = entityManager.createQuery(
				"select p from SfcPage p where p.workspace.id = :workspaceId and p.disabled "
					+ "= false order by lower(p.name)",
				SfcPage.class
			)
			.setParameter("workspaceId", workspaceId)
			.getResultList();
		return toResponses(pages);
	}

	private SfcPageNamespaceChildrenResponse listNamespaceChildren(UUID workspaceId, String namespace) {
		String pageQueryText = namespace == null
			? "select p from SfcPage p where p.workspace.id = :workspaceId and p.disabled = "
				+ "false and (p.namespace is null or trim(p.namespace) = '') order by lower(p.name)"
			: "select p from SfcPage p where p.workspace.id = :workspaceId and p.disabled = "
				+ "false and lower(p.namespace) = :namespace order by lower(p.name)";
		var pageQuery = entityManager.createQuery(pageQueryText, SfcPage.class)
			.setParameter("workspaceId", workspaceId);
		if (namespace != null) {
			pageQuery.setParameter("namespace", namespace.toLowerCase());
		}
		List<SfcPage> pages = pageQuery.getResultList();

		String childQueryText = namespace == null
			? "select distinct coalesce(p.namespace, '') from SfcPage p where p.workspace.id "
				+ "= :workspaceId and p.disabled = false and p.namespace is not null and trim(p.namespace) "
				+ "<> ''"
			: "select distinct coalesce(p.namespace, '') from SfcPage p where p.workspace.id "
				+ "= :workspaceId and p.disabled = false and lower(p.namespace) like :childPrefix";
		var childQuery = entityManager.createQuery(childQueryText, String.class)
			.setParameter("workspaceId", workspaceId);
		if (namespace != null) {
			childQuery.setParameter("childPrefix", namespace.toLowerCase() + "/%");
		}

		Map<String, Long> childCounts = new LinkedHashMap<>();
		for (String candidate : childQuery.getResultList()) {
			String normalizedCandidate = normalizeNamespace(candidate);
			if (normalizedCandidate == null) {
				continue;
			}
			String childPath = directChildNamespace(namespace, normalizedCandidate);
			if (childPath == null) {
				continue;
			}
			childCounts.putIfAbsent(childPath, 0L);
			childCounts.put(childPath, childCounts.get(childPath) + 1L);
		}

		List<SfcPageNamespaceEntryResponse> namespaces = childCounts.entrySet()
			.stream()
			.map(
				entry -> new SfcPageNamespaceEntryResponse(entry.getKey(), lastSegment(entry.getKey()), entry.getValue())
			)
			.sorted((left, right) -> left.name().compareToIgnoreCase(right.name()))
			.toList();
		return new SfcPageNamespaceChildrenResponse(namespace, namespaces, toResponses(pages));
	}

	private List<SfcPageResponse> toResponses(List<SfcPage> pages) {
		if (pages.isEmpty()) {
			return List.of();
		}
		List<UUID> pageIds = pages.stream()
			.map(page -> page.id)
			.toList();
		List<UUID> draftPageIds = entityManager.createQuery(
				"select distinct v.page.id from SfcPageVersion v where v.page.id in "
					+ ":pageIds and v.releasedAt is null",
				UUID.class
			)
			.setParameter("pageIds", pageIds)
			.getResultList();
		List<UUID> releasedPageIds = entityManager.createQuery(
				"select distinct v.page.id from SfcPageVersion v where v.page.id in "
					+ ":pageIds and v.releasedAt is not null and v.deprecatedAt is null",
				UUID.class
			)
			.setParameter("pageIds", pageIds)
			.getResultList();
		return pages.stream()
			.map(page -> toResponse(page, draftPageIds.contains(page.id), releasedPageIds.contains(page.id)))
			.toList();
	}

	private List<SfcPageResponse> toInstalledResponses(List<SfcPage> pages, UUID userId) {
		if (pages.isEmpty()) {
			return List.of();
		}
		return pages.stream()
			.map(
				page -> toResponse(page, hasDraftVersion(page.id), userExecutionModeService.resolvePageVersion(page, userId) != null)
			)
			.toList();
	}

	private String directChildNamespace(String parentNamespace, String candidateNamespace) {
		List<String> parentParts = splitNamespace(parentNamespace);
		List<String> candidateParts = splitNamespace(candidateNamespace);
		if (candidateParts.size() <= parentParts.size()) {
			return null;
		}
		for (int i = 0; i < parentParts.size(); i++) {
			if (!parentParts.get(i).equalsIgnoreCase(candidateParts.get(i))) {
				return null;
			}
		}
		List<String> childParts = new ArrayList<>(candidateParts.subList(0, parentParts.size() + 1));
		return String.join("/", childParts);
	}

	private String lastSegment(String namespace) {
		List<String> parts = splitNamespace(namespace);
		return parts.isEmpty() ? "root" : parts.get(parts.size() - 1);
	}

	private List<String> splitNamespace(String namespace) {
		if (namespace == null || namespace.isBlank()) {
			return List.of();
		}
		return Arrays.stream(namespace.trim().split("/+"))
			.map(String::trim)
			.filter(segment -> !segment.isBlank())
			.toList();
	}

	private String normalizeNamespace(String value) {
		return SlugSupport.normalizeNamespace(value);
	}

	private boolean sameNamespace(String left, String right) {
		if (left == null || left.isBlank()) {
			return right == null || right.isBlank();
		}
		return left.equalsIgnoreCase(right == null ? "" : right);
	}

	private Script findCallableScript(UUID workspaceId, String identifier) {
		String normalizedIdentifier = normalizeScriptIdentifier(identifier);
		if (normalizedIdentifier == null) {
			return null;
		}
		return entityManager.createQuery(
				"select s from Script s where s.workspace.id = :workspaceId and "
					+ "lower(s.slug) = :slug and s.disabled = false",
				Script.class
			)
			.setParameter("workspaceId", workspaceId)
			.setParameter("slug", normalizedIdentifier)
			.setMaxResults(1)
			.getResultStream()
			.findFirst()
			.orElse(null);
	}

	private String normalizeScriptIdentifier(Object identifier) {
		if (identifier == null) {
			return null;
		}
		String value = identifier.toString().trim();
		if (value.isEmpty()) {
			return null;
		}
		if (value.indexOf('/') >= 0 || value.toLowerCase().endsWith(".groovy")) {
			return SlugSupport.slugFromPath(value).toLowerCase();
		}
		return value.toLowerCase();
	}

	private SfcPage findPageBySlug(UUID workspaceId, String slug) {
		if (slug == null || slug.isBlank()) {
			return null;
		}
		return entityManager.createQuery(
				"select p from SfcPage p where p.workspace.id = :workspaceId and "
					+ "lower(p.slug) = :slug and (p.namespace is null or trim(p.namespace) = '')",
				SfcPage.class
			)
			.setParameter("workspaceId", workspaceId)
			.setParameter("slug", slug.trim().toLowerCase())
			.getResultStream()
			.findFirst()
			.orElse(null);
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

	private record BlobReference(UUID workspaceId, String hash) {}

	private UUID requireUserId() {
		if (securityContext == null || securityContext.getUserPrincipal() == null) {
			throw new WebApplicationException("Unauthorized", Response.Status.UNAUTHORIZED);
		}
		return UUID.fromString(securityContext.getUserPrincipal().getName());
	}

	private SfcPageType parseType(String value) {
		if (value == null || value.isBlank()) {
			return SfcPageType.PAGE;
		}
		try {
			return SfcPageType.valueOf(value.trim().toUpperCase());
		}
		catch (IllegalArgumentException ex) {
			return SfcPageType.PAGE;
		}
	}

	private boolean resolveMenuVisible(SfcPageType type, Boolean value) {
		if (type == SfcPageType.COMPONENT) {
			return false;
		}
		return value == null || value;
	}

	private String sanitizeLabel(String label) {
		if (label == null) {
			return null;
		}
		String trimmed = label.trim();
		return trimmed.isBlank() ? null : trimmed;
	}

	private String sanitizeRouteSuffix(String routeSuffix) {
		if (routeSuffix == null) {
			return null;
		}
		String trimmed = routeSuffix.trim();
		if (trimmed.isBlank()) {
			return null;
		}
		return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
	}

	private SfcPageResponse toResponse(SfcPage page) {
		return toResponse(page, false, false);
	}

	private SfcPageResponse toResponse(SfcPage page, boolean hasDraft, boolean released) {
		return new SfcPageResponse(
			page.id,
			page.name,
			page.label,
			page.description,
			page.namespace,
			page.slug,
			page.type == null ? null : page.type.name(),
			page.menuVisible,
			page.iconSvg,
			page.routeSuffix,
			page.usageGuide,
			page.importAllowlist,
			page.workspace == null ? null : page.workspace.externalFrontendImports,
			page.queryParams,
			page.inputParams,
			hasDraft,
			released,
			page.activeVersion == null ? null : page.activeVersion.id,
			page.createdBy == null ? null : page.createdBy.id,
			page.updatedAt
		);
	}

	private SfcPageVersion requireRuntimePageVersion(SfcPage page, UUID userId) {
		SfcPageVersion version = userExecutionModeService.resolvePageVersion(page, userId);
		if (version == null) {
			throw new WebApplicationException("Page not released", Response.Status.NOT_FOUND);
		}
		if (version.releasedAt == null && (version.compiledBundle == null || version.compiledBundle.isBlank())) {
			lazyCompileDraftVersion(version);
		}
		if (version.compiledBundle == null || version.compiledBundle.isBlank()) {
			if (version.releasedAt != null || version.compileErrors == null || version.compileErrors.isBlank()) {
				throw new WebApplicationException("Page not released", Response.Status.NOT_FOUND);
			}
		}
		return version;
	}

	private void lazyCompileDraftVersion(SfcPageVersion version) {
		if (version == null || version.releasedAt != null) {
			return;
		}
		Map<String, String> workspaceImports = workspaceExternalFrontendImports(version.page);
		List<String> allowlist = new ArrayList<>(pageImportAllowlist(version.page));
		for (String specifier : workspaceImports.keySet()) {
			if (!allowlist.contains(specifier)) {
				allowlist.add(specifier);
			}
		}
		PageCompilationResult compilation = pageCompilerService.compile(
			new PageCompilationRequest(
				version.sourceSfc,
				allowlist,
				workspaceImports,
				version.page == null || version.page.id == null ? "page" : version.page.id.toString(),
				Collections.emptyMap()
			)
		);
		String compileErrors = compilation.compileErrors() == null || compilation.compileErrors().isBlank()
			? null
			: compilation.compileErrors();
		if (compileErrors != null) {
			version.compileErrors = null;
			version.compiledBundle = null;
			return;
		}
		version.compiledBundle = compilation.compiledBundle() == null || compilation.compiledBundle().isBlank()
			? null
			: compilation.compiledBundle();
		version.compileErrors = null;
	}

	private List<String> pageImportAllowlist(SfcPage page) {
		if (page == null || page.importAllowlist == null || !page.importAllowlist.isArray()) {
			return List.of();
		}
		List<String> allowlist = new ArrayList<>();
		for (JsonNode entry : page.importAllowlist) {
			if (entry != null && entry.isTextual() && !entry.asText().isBlank()) {
				allowlist.add(entry.asText());
			}
		}
		return allowlist;
	}

	private Map<String, String> workspaceExternalFrontendImports(SfcPage page) {
		if (page == null
				|| page.workspace == null
				|| page.workspace.externalFrontendImports == null
				|| !page.workspace.externalFrontendImports.isArray()) {
			return Map.of();
		}
		Map<String, String> imports = new HashMap<>();
		for (JsonNode entry : page.workspace.externalFrontendImports) {
			if (entry == null || !entry.isObject()) {
				continue;
			}
			JsonNode specifierNode = entry.get("specifier");
			JsonNode globalNode = entry.get("global_name");
			JsonNode urlNode = entry.get("source_url");
			if (specifierNode == null || !specifierNode.isTextual() || specifierNode.asText().isBlank()) {
				continue;
			}
			if (globalNode == null || !globalNode.isTextual() || globalNode.asText().isBlank()) {
				continue;
			}
			if (urlNode == null || !urlNode.isTextual() || urlNode.asText().isBlank()) {
				continue;
			}
			imports.put(specifierNode.asText(), globalNode.asText());
		}
		return imports;
	}

	private SfcPageVersionResponse toVersionResponse(SfcPageVersion version) {
		return new SfcPageVersionResponse(
			version.id,
			version.page == null ? null : version.page.id,
			version.designSessionId,
			version.version,
			version.sourceSfc,
			version.compiledBundle,
			version.compileErrors,
			version.createdBy == null ? null : version.createdBy.id,
			version.releasedBy == null ? null : version.releasedBy.id,
			version.releasedAt,
			version.deprecatedAt,
			version.updatedAt
		);
	}

	private int nextPageVersionNumber(UUID pageId) {
		Integer currentMax = entityManager.createQuery("select max(v.version) from SfcPageVersion v where v.page.id = :pageId", Integer.class)
			.setParameter("pageId", pageId)
			.getSingleResult();
		return currentMax == null ? 1 : currentMax + 1;
	}

	private boolean isCompiled(SfcPageVersion version) {
		if (version == null) {
			return false;
		}
		boolean hasBundle = version.compiledBundle != null && !version.compiledBundle.isBlank();
		boolean hasErrors = version.compileErrors != null && !version.compileErrors.isBlank();
		return hasBundle && !hasErrors;
	}

	private Session createDesignSession(TenantMembership membership, SfcPage page, UUID assistantId) {
		Workspace workspace = page.workspace;
		conversationWorkflowStartup.ensureForWorkspace(workspace);
		WorkflowDefinition definition = entityManager.createQuery(
				"select w from WorkflowDefinition w where w.workspace.id = :workspaceId and w.deletedAt is null",
				WorkflowDefinition.class
			)
			.setParameter("workspaceId", workspace.id)
			.getResultList()
			.stream()
			.filter(workflowDefinitionService::isConversationDefinition)
			.findFirst()
			.orElseThrow(() -> new WebApplicationException("Conversation workflow not found", Response.Status.CONFLICT));
		Session session = new Session();
		session.tenant = membership.tenant;
		session.workspace = workspace;
		session.createdBy = membership.user;
		if (assistantId != null) {
			session.defaultAssistant = entityManager.find(Assistant.class, assistantId);
		}
		session.title = "Design: " + page.name;
		session.titleLocked = true;
		session.status = SessionStatus.PAUSED;
		session.visibility = SessionVisibility.HIDDEN;
		entityManager.persist(session);
		WorkflowRun run = new WorkflowRun();
		run.session = session;
		run.workflowDefinition = definition;
		run.workflowDefinitionVersion = loadReleasedVersion(definition);
		run.status = WorkflowRunStatus.PAUSED;
		ObjectNode checkpoint = objectMapper.createObjectNode();
		ObjectNode snapshot = snapshotService.buildSnapshot(definition, session);
		overwriteDesignSnapshot(snapshot, workspace, session);
		checkpoint.set(ConversationGraphState.MCP_SNAPSHOT, snapshot);
		run.checkpointJson = checkpoint;
		entityManager.persist(run);
		SessionParticipant owner = new SessionParticipant();
		owner.session = session;
		owner.user = membership.user;
		owner.role = SessionParticipantRole.OWNER;
		entityManager.persist(owner);
		return session;
	}

	private void overwriteDesignSnapshot(ObjectNode snapshot, Workspace workspace, Session session) {
		if (snapshot == null || workspace == null) {
			return;
		}
		McpServer designServer = ensureDesignServer(workspace);
		ObjectNode mcp = snapshot.get("mcp") instanceof ObjectNode node ? node : snapshot.putObject("mcp");
		ArrayNode servers = objectMapper.createArrayNode();
		if (designServer != null) {
			servers.add(designServer.id.toString());
		}
		mcp.set("servers", servers);
		ObjectNode nodes = snapshot.get("nodes") instanceof ObjectNode node ? node : snapshot.putObject("nodes");
		nodes.fieldNames()
			.forEachRemaining(
				nodeId -> {
					ObjectNode node = nodes.get(nodeId) instanceof ObjectNode entry ? entry : nodes.putObject(nodeId);
					ObjectNode scopes = node.get("scopes") instanceof ObjectNode scopeNode ? scopeNode : node.putObject("scopes");
					ArrayNode allowScopes = scopes.get("allow_scopes") instanceof ArrayNode arr ? arr : scopes.putArray("allow_scopes");
					ensureScope(allowScopes, "read:polymr");
					ensureScope(allowScopes, "write:polymr");
				}
			);
	}

	private void ensureScope(ArrayNode array, String value) {
		if (array == null || value == null || value.isBlank()) {
			return;
		}
		for (JsonNode node : array) {
			if (node != null && value.equalsIgnoreCase(node.asText())) {
				return;
			}
		}
		array.add(value);
	}

	private McpServer ensureDesignServer(Workspace workspace) {
		List<McpServer> existing = entityManager.createQuery(
				"select s from McpServer s where s.workspace.id = :workspaceId and s.internal = true "
					+ "and s.protocol = :protocol and lower(s.virtualType) = :virtualType",
				McpServer.class
			)
			.setParameter("workspaceId", workspace.id)
			.setParameter("protocol", McpProtocol.VIRTUAL)
			.setParameter("virtualType", "polymr_design")
			.getResultList();
		if (!existing.isEmpty()) {
			McpServer server = existing.get(0);
			toolCatalogService.refreshTools(server, virtualMcpService.listTools(server));
			return server;
		}
		McpServer server = new McpServer();
		server.workspace = workspace;
		server.name = "Polymr Design";
		server.description = "Internal design tools";
		server.protocol = McpProtocol.VIRTUAL;
		server.virtualType = "polymr_design";
		server.visibility = McpServerVisibility.HIDDEN;
		server.internal = true;
		entityManager.persist(server);
		toolCatalogService.refreshTools(server, virtualMcpService.listTools(server));
		return server;
	}

	private WorkflowDefinitionVersion loadReleasedVersion(WorkflowDefinition definition) {
		if (definition == null) {
			return null;
		}
		return entityManager.createQuery(
				"select v from WorkflowDefinitionVersion v where v.workflowDefinition.id = :workflowId "
					+ "and v.releasedAt is not null and v.deprecatedAt is null",
				WorkflowDefinitionVersion.class
			)
			.setParameter("workflowId", definition.id)
			.setMaxResults(1)
			.getResultStream()
			.findFirst()
			.orElse(null);
	}

	private void archiveDesignSession(UUID sessionId) {
		if (sessionId == null) {
			return;
		}
		Session session = entityManager.find(Session.class, sessionId);
		if (session != null) {
			if (isSessionEmpty(sessionId)) {
				deleteSession(sessionId);
			}
			else {
				session.status = SessionStatus.COMPLETED;
			}
		}
	}

	private boolean isSessionEmpty(UUID sessionId) {
		Long count = entityManager.createQuery("select count(e) from SessionEvent e where e.session.id = :sessionId", Long.class)
			.setParameter("sessionId", sessionId)
			.getSingleResult();
		return count == null || count == 0;
	}

	private void deleteSession(UUID sessionId) {
		entityManager.createNativeQuery("delete from session_participant_connections where session_id = :sessionId")
			.setParameter("sessionId", sessionId)
			.executeUpdate();
		entityManager.createNativeQuery("delete from session_participants where session_id = :sessionId")
			.setParameter("sessionId", sessionId)
			.executeUpdate();
		entityManager.createQuery("delete from SessionEventResource r where r.sessionEvent.session.id = :sessionId")
			.setParameter("sessionId", sessionId)
			.executeUpdate();
		entityManager.createQuery("delete from SessionEvent e where e.session.id = :sessionId")
			.setParameter("sessionId", sessionId)
			.executeUpdate();
		entityManager.createQuery("delete from SessionCostEntry e where e.session.id = :sessionId")
			.setParameter("sessionId", sessionId)
			.executeUpdate();
		entityManager.createQuery("delete from SessionCanvas c where c.session.id = :sessionId")
			.setParameter("sessionId", sessionId)
			.executeUpdate();
		entityManager.createQuery("delete from SessionTagSelection s where s.session.id = :sessionId")
			.setParameter("sessionId", sessionId)
			.executeUpdate();
		entityManager.createQuery("update McpCallLog l set l.session = null where l.session.id = :sessionId")
			.setParameter("sessionId", sessionId)
			.executeUpdate();
		entityManager.createQuery("delete from WorkflowRunCheckpoint c where c.workflowRun.session.id = :sessionId")
			.setParameter("sessionId", sessionId)
			.executeUpdate();
		entityManager.createQuery("delete from WorkflowRun r where r.session.id = :sessionId")
			.setParameter("sessionId", sessionId)
			.executeUpdate();
		entityManager.createQuery("delete from Session s where s.id = :sessionId")
			.setParameter("sessionId", sessionId)
			.executeUpdate();
	}
}
