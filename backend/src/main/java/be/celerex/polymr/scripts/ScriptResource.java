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

package be.celerex.polymr.scripts;

import be.celerex.polymr.model.Assistant;
import be.celerex.polymr.model.McpProtocol;
import be.celerex.polymr.model.McpServer;
import be.celerex.polymr.model.McpServerVisibility;
import be.celerex.polymr.model.Script;
import be.celerex.polymr.model.ScriptType;
import be.celerex.polymr.model.ScriptToolHookPhase;
import be.celerex.polymr.model.ScriptVersion;
import be.celerex.polymr.model.Session;
import be.celerex.polymr.model.SessionParticipant;
import be.celerex.polymr.model.SessionParticipantRole;
import be.celerex.polymr.model.SessionStatus;
import be.celerex.polymr.model.SessionTagSelection;
import be.celerex.polymr.model.SessionVisibility;
import be.celerex.polymr.model.TenantMembership;
import be.celerex.polymr.model.User;
import be.celerex.polymr.model.Workspace;
import be.celerex.polymr.model.WorkflowDefinition;
import be.celerex.polymr.model.WorkflowDefinitionVersion;
import be.celerex.polymr.model.WorkflowRun;
import be.celerex.polymr.model.WorkflowRunStatus;
import be.celerex.polymr.mcp.McpToolCatalogService;
import be.celerex.polymr.mcp.VirtualMcpService;
import be.celerex.polymr.mcp.WorkflowMcpSnapshotService;
import be.celerex.polymr.mcp.WorkflowStateStoreContext;
import be.celerex.polymr.mcp.WorkflowStateStoreMemory;
import be.celerex.polymr.scheduling.ScriptScheduleService;
import be.celerex.polymr.scripts.dto.ScriptDraftRequest;
import be.celerex.polymr.scripts.dto.ScriptMetadataRequest;
import be.celerex.polymr.scripts.dto.ScriptNamespaceChildrenResponse;
import be.celerex.polymr.scripts.dto.ScriptNamespaceEntryResponse;
import be.celerex.polymr.scripts.dto.ScriptRequest;
import be.celerex.polymr.scripts.dto.ScriptResponse;
import be.celerex.polymr.scripts.dto.ScriptRunRequest;
import be.celerex.polymr.scripts.dto.ScriptRunResponse;
import be.celerex.polymr.scripts.dto.ScriptVersionResponse;
import be.celerex.polymr.tenant.TenantAccessService;
import be.celerex.polymr.util.SlugSupport;
import be.celerex.polymr.workflow.ConversationWorkflowStartup;
import be.celerex.polymr.workflow.WorkflowDefinitionService;
import be.celerex.polymr.workflow.runtime.ConversationGraphState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Path("/api/tenants/{tenantId}/workspaces/{workspaceId}/scripts")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ScriptResource {
	@Inject
	EntityManager entityManager;

	@Inject
	TenantAccessService tenantAccessService;

	@Inject
	ScriptCatalogService catalogService;

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
	ScriptRuntimeService runtimeService;

	@Inject
	ScriptScheduleService scheduleService;

	@Inject
	ObjectMapper objectMapper;

	@Context
	SecurityContext securityContext;

	@GET
	@Path("/catalog")
	public Object catalog(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@jakarta.ws.rs.QueryParam("script_id") UUID scriptId) {
		requireMembership(tenantId);
		if (scriptId == null) {
			return catalogService.buildCatalog(workspaceId);
		}
		Script script = requireActiveScript(workspaceId, scriptId);
		return catalogService.buildCatalogForScript(script);
	}

	@GET
	public Object list(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@jakarta.ws.rs.QueryParam("include_workflow") Boolean includeWorkflow,
			@jakarta.ws.rs.QueryParam("namespace") String namespace,
			@jakarta.ws.rs.QueryParam("non_recursive") Boolean nonRecursive,
			@jakarta.ws.rs.QueryParam("search") String search) {
		requireMembership(tenantId);
		boolean includeWorkflowScripts = includeWorkflow != null && includeWorkflow;
		boolean namespaceChildrenOnly = nonRecursive != null && nonRecursive;
		String searchTerm = search == null ? null : search.trim();
		if (searchTerm != null && !searchTerm.isBlank()) {
			return searchScripts(workspaceId, includeWorkflowScripts, normalizeNamespace(namespace), searchTerm);
		}
		if (!namespaceChildrenOnly) {
			return listAllScripts(workspaceId, includeWorkflowScripts);
		}
		return listNamespaceChildren(workspaceId, includeWorkflowScripts, normalizeNamespace(namespace));
	}

	@GET
	@Path("/{scriptId}")
	public ScriptResponse get(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("scriptId") UUID scriptId) {
		requireMembership(tenantId);
		Script script = requireActiveScript(workspaceId, scriptId);
		return toResponse(script, hasDraftVersion(scriptId), isReleased(scriptId));
	}

	@POST
	@Transactional
	public ScriptResponse create(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			ScriptRequest request) {
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
		Script script = new Script();
		script.workspace = workspace;
		script.createdBy = entityManager.find(User.class, membership.user.id);
		script.name = request.name().trim();
		script.description = request.description();
		script.namespace = namespace;
		script.slug = slug;
		entityManager.persist(script);
		ScriptVersion version = new ScriptVersion();
		version.script = script;
		version.createdBy = script.createdBy;
		version.version = nextScriptVersionNumber(script.id);
		version.inputSchema = defaultSchema(null);
		version.outputSchema = defaultSchema(null);
		entityManager.persist(version);
		return toResponse(script, false, false);
	}

	@PUT
	@Path("/{scriptId}")
	@Transactional
	public ScriptResponse update(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("scriptId") UUID scriptId,
			ScriptMetadataRequest request) {
		requireMembership(tenantId);
		Script script = requireActiveScript(workspaceId, scriptId);
		if (request == null) {
			throw new WebApplicationException("Request is required", Response.Status.BAD_REQUEST);
		}
		if (request.name() != null && !request.name().isBlank()) {
			script.name = request.name().trim();
		}
		if (request.description() != null) {
			script.description = request.description();
		}
		String namespace = script.namespace;
		if (request.namespace() != null) {
			namespace = normalizeNamespace(request.namespace());
		}
		String slug = script.slug;
		if (request.name() != null || request.namespace() != null) {
			slug = SlugSupport.buildSlug(namespace, request.name() != null && !request.name().isBlank() ? request.name() : script.name);
		}
		if (!sameNamespace(namespace, script.namespace) || !slug.equalsIgnoreCase(script.slug)) {
			if (slugExists(workspaceId, namespace, slug, script.id)) {
				throw new WebApplicationException("Slug is already in use in this namespace", Response.Status.CONFLICT);
			}
		}
		script.namespace = namespace;
		script.slug = slug;
		ScriptType requestedType = parseScriptType(request.type());
		if (requestedType != null) {
			script.type = requestedType;
		}
		applyScheduleConfig(script, request);
		applyToolHookConfig(script, request);
		if (request.workflow_definition_id() != null) {
			WorkflowDefinition definition = entityManager.find(WorkflowDefinition.class, request.workflow_definition_id());
			if (definition != null && definition.workspace.id.equals(workspaceId)) {
				script.workflowDefinition = definition;
			}
		}
		return toResponse(script, hasDraftVersion(script.id), isReleased(script.id));
	}

	@GET
	@Path("/{scriptId}/versions")
	public List<ScriptVersionResponse> listVersions(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("scriptId") UUID scriptId) {
		requireMembership(tenantId);
		requireActiveScript(workspaceId, scriptId);
		List<ScriptVersion> versions = entityManager.createQuery(
				"select v from ScriptVersion v where v.script.id = :scriptId order by v.updatedAt desc",
				ScriptVersion.class
			)
			.setParameter("scriptId", scriptId)
			.getResultList();
		return versions.stream().map(this::toVersionResponse).toList();
	}

	@GET
	@Path("/{scriptId}/draft")
	@Transactional
	public ScriptVersionResponse getDraft(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("scriptId") UUID scriptId,
			@jakarta.ws.rs.QueryParam("assistant_id") UUID assistantId) {
		TenantMembership membership = requireMembership(tenantId);
		Script script = requireActiveScript(workspaceId, scriptId);
		ScriptVersion draft = findDraft(scriptId);
		if (draft == null) {
			draft = new ScriptVersion();
			draft.script = script;
			draft.createdBy = entityManager.find(User.class, membership.user.id);
			draft.version = nextScriptVersionNumber(script.id);
			if (script.activeVersion != null) {
				if (script.activeVersion.sourceGroovy != null) {
					draft.sourceGroovy = script.activeVersion.sourceGroovy;
				}
				draft.inputSchema = defaultSchema(script.activeVersion.inputSchema);
				draft.outputSchema = defaultSchema(script.activeVersion.outputSchema);
			}
			else {
				draft.inputSchema = defaultSchema(null);
				draft.outputSchema = defaultSchema(null);
			}
			draft.designSessionId = createScriptSession(membership, script, assistantId).id;
			entityManager.persist(draft);
		}
		else if (draft.designSessionId == null) {
			draft.designSessionId = createScriptSession(membership, script, assistantId).id;
		}
		return toVersionResponse(draft);
	}

	@PUT
	@Path("/{scriptId}/draft")
	@Transactional
	public ScriptVersionResponse updateDraft(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("scriptId") UUID scriptId,
			ScriptDraftRequest request) {
		TenantMembership membership = requireMembership(tenantId);
		Script script = requireActiveScript(workspaceId, scriptId);
		ScriptVersion draft = findDraft(scriptId);
		if (draft == null) {
			draft = new ScriptVersion();
			draft.script = script;
			draft.createdBy = entityManager.find(User.class, membership.user.id);
			draft.version = nextScriptVersionNumber(script.id);
			draft.designSessionId = createScriptSession(membership, script, null).id;
			entityManager.persist(draft);
		}
		else if (draft.designSessionId == null) {
			draft.designSessionId = createScriptSession(membership, script, null).id;
		}
		if (request != null) {
			if (request.source_groovy() != null) {
				draft.sourceGroovy = request.source_groovy();
			}
			if (request.input_schema() != null) {
				draft.inputSchema = defaultSchema(request.input_schema());
			}
			if (request.output_schema() != null) {
				draft.outputSchema = defaultSchema(request.output_schema());
			}
		}
		draft.inputSchema = defaultSchema(draft.inputSchema);
		draft.outputSchema = defaultSchema(draft.outputSchema);
		return toVersionResponse(draft);
	}

	@POST
	@Path("/{scriptId}/approve")
	@Transactional
	public ScriptVersionResponse approve(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("scriptId") UUID scriptId) {
		TenantMembership membership = requireMembership(tenantId);
		Script script = requireActiveScript(workspaceId, scriptId);
		ScriptVersion draft = findDraft(scriptId);
		if (draft == null || draft.sourceGroovy == null || draft.sourceGroovy.isBlank()) {
			throw new WebApplicationException("Draft has no source", Response.Status.CONFLICT);
		}
		ScriptVersion previous = script.activeVersion;
		if (previous != null && previous != draft && previous.deprecatedAt == null) {
			previous.deprecatedAt = Instant.now();
		}
		draft.releasedBy = entityManager.find(User.class, membership.user.id);
		draft.releasedAt = Instant.now();
		script.activeVersion = draft;
		archiveScriptSession(draft.designSessionId);
		return toVersionResponse(draft);
	}

	@POST
	@Path("/{scriptId}/run")
	@Transactional
	public ScriptRunResponse run(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("scriptId") UUID scriptId,
			ScriptRunRequest request) {
		TenantMembership membership = requireMembership(tenantId);
		Script script = requireActiveScript(workspaceId, scriptId);
		ScriptVersion version = findDraft(scriptId);
		if (version == null || version.sourceGroovy == null || version.sourceGroovy.isBlank()) {
			version = script.activeVersion;
		}
		if (version == null || version.sourceGroovy == null || version.sourceGroovy.isBlank()) {
			throw new WebApplicationException("Script source not available", Response.Status.CONFLICT);
		}
		try {
			if (script.type == ScriptType.WORKFLOW) {
				snapshotService.ensureWorkflowStateServerForWorkspace(script.workspace);
				JsonNode initialState = request == null ? null : request.state();
				WorkflowStateStoreMemory store = new WorkflowStateStoreMemory(objectMapper, initialState, resolveWorkflowStateSchema(script));
				WorkflowStateStoreContext.set(store);
				try {
					runtimeService.runScript(script, membership.user.id, null, objectMapper.createObjectNode());
				}
				finally {
					WorkflowStateStoreContext.clear();
				}
				return new ScriptRunResponse(store.getState());
			}
			JsonNode output = runtimeService.runScriptWithSource(
				script,
				membership.user.id,
				null,
				version.sourceGroovy,
				request == null ? null : request.input(),
				defaultSchema(version.inputSchema),
				defaultSchema(version.outputSchema)
			);
			return new ScriptRunResponse(output);
		}
		catch (WebApplicationException ex) {
			throw ex;
		}
		catch (RuntimeException ex) {
			String message = ex.getMessage();
			if (message == null || message.isBlank()) {
				Throwable cause = ex.getCause();
				message = cause == null ? null : cause.getMessage();
			}
			if (message == null || message.isBlank()) {
				message = "Script execution failed";
			}
			throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST).type(MediaType.TEXT_PLAIN).entity(message).build());
		}
	}

	@DELETE
	@Path("/{scriptId}/draft")
	@Transactional
	public Response deleteDraft(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("scriptId") UUID scriptId) {
		requireMembership(tenantId);
		requireActiveScript(workspaceId, scriptId);
		ScriptVersion draft = findDraft(scriptId);
		if (draft != null) {
			archiveScriptSession(draft.designSessionId);
			entityManager.remove(draft);
		}
		return Response.noContent().build();
	}

	@DELETE
	@Path("/{scriptId}")
	@Transactional
	public Response deleteScript(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("scriptId") UUID scriptId) {
		requireMembership(tenantId);
		Script script = requireScriptIncludingDisabled(workspaceId, scriptId);
		script.disabled = true;
		return Response.noContent().build();
	}

	private ScriptVersion findDraft(UUID scriptId) {
		return entityManager.createQuery(
				"select v from ScriptVersion v where v.script.id = :scriptId and v.releasedAt is null",
				ScriptVersion.class
			)
			.setParameter("scriptId", scriptId)
			.setMaxResults(1)
			.getResultStream()
			.findFirst()
			.orElse(null);
	}

	private boolean hasDraftVersion(UUID scriptId) {
		if (scriptId == null) {
			return false;
		}
		return !entityManager.createQuery(
				"select v.id from ScriptVersion v where v.script.id = :scriptId and v.releasedAt is null",
				UUID.class
			)
			.setParameter("scriptId", scriptId)
			.setMaxResults(1)
			.getResultList()
			.isEmpty();
	}

	private boolean isReleased(UUID scriptId) {
		if (scriptId == null) {
			return false;
		}
		return !entityManager.createQuery(
				"select v.id from ScriptVersion v where v.script.id = :scriptId and v.releasedAt is not null "
					+ "and v.deprecatedAt is null",
				UUID.class
			)
			.setParameter("scriptId", scriptId)
			.setMaxResults(1)
			.getResultList()
			.isEmpty();
	}

	private Script requireActiveScript(UUID workspaceId, UUID scriptId) {
		Script script = requireScriptIncludingDisabled(workspaceId, scriptId);
		if (script.disabled) {
			throw new WebApplicationException("Script not found", Response.Status.NOT_FOUND);
		}
		return script;
	}

	private Script requireScriptIncludingDisabled(UUID workspaceId, UUID scriptId) {
		Script script = entityManager.find(Script.class, scriptId);
		if (script == null || !script.workspace.id.equals(workspaceId)) {
			throw new WebApplicationException("Script not found", Response.Status.NOT_FOUND);
		}
		return script;
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

	private boolean slugExists(UUID workspaceId, String namespace, String slug, UUID excludedId) {
		if (slug == null || slug.isBlank()) {
			return false;
		}
		StringBuilder query = new StringBuilder("select s.id from Script s where s.workspace.id = :workspaceId and lower(s.slug) = :slug");
		if (namespace == null) {
			query.append(" and (s.namespace is null or trim(s.namespace) = '')");
		}
		else {
			query.append(" and lower(s.namespace) = :namespace");
		}
		if (excludedId != null) {
			query.append(" and s.id <> :excludedId");
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

	private List<ScriptResponse> listAllScripts(UUID workspaceId, boolean includeWorkflowScripts) {
		String queryText = "select s from Script s where s.workspace.id = :workspaceId and s.disabled = false "
			+ (includeWorkflowScripts ? "" : "and s.type <> :workflowType ")
			+ "order by lower(s.name)";
		var query = entityManager.createQuery(queryText, Script.class)
			.setParameter("workspaceId", workspaceId);
		if (!includeWorkflowScripts) {
			query.setParameter("workflowType", ScriptType.WORKFLOW);
		}
		return toResponses(workspaceId, query.getResultList());
	}

	private List<ScriptResponse> searchScripts(UUID workspaceId, boolean includeWorkflowScripts, String namespace, String search) {
		String pattern = "%" + search.toLowerCase() + "%";
		String queryText = "select s from Script s where s.workspace.id = :workspaceId and s.disabled = false "
			+ (includeWorkflowScripts ? "" : "and s.type <> :workflowType ")
			+ (namespace == null ? "" : "and lower(coalesce(s.namespace, '')) = :namespace ")
			+ "and (lower(s.name) like :pattern or lower(coalesce(s.slug, '')) like :pattern "
			+ "or lower(coalesce(s.namespace, '')) like :pattern or lower(coalesce(s.description, '')) "
			+ "like :pattern) "
			+ "order by lower(s.name)";
		var query = entityManager.createQuery(queryText, Script.class)
			.setParameter("workspaceId", workspaceId)
			.setParameter("pattern", pattern);
		if (!includeWorkflowScripts) {
			query.setParameter("workflowType", ScriptType.WORKFLOW);
		}
		if (namespace != null) {
			query.setParameter("namespace", namespace.toLowerCase());
		}
		query.setMaxResults(50);
		return toResponses(workspaceId, query.getResultList());
	}

	private ScriptNamespaceChildrenResponse listNamespaceChildren(UUID workspaceId, boolean includeWorkflowScripts, String namespace) {
		String scriptQueryText = "select s from Script s where s.workspace.id = :workspaceId and s.disabled = false "
			+ (includeWorkflowScripts ? "" : "and s.type <> :workflowType ")
			+ (namespace == null
				? "and (s.namespace is null or trim(s.namespace) = '') "
				: "and lower(s.namespace) = :namespace ")
			+ "order by lower(s.name)";
		var scriptQuery = entityManager.createQuery(scriptQueryText, Script.class)
			.setParameter("workspaceId", workspaceId);
		if (!includeWorkflowScripts) {
			scriptQuery.setParameter("workflowType", ScriptType.WORKFLOW);
		}
		if (namespace != null) {
			scriptQuery.setParameter("namespace", namespace.toLowerCase());
		}
		List<Script> scripts = scriptQuery.getResultList();

		String childQueryText = "select distinct coalesce(s.namespace, '') from Script s where s.workspace.id = "
			+ ":workspaceId and s.disabled = false "
			+ (includeWorkflowScripts ? "" : "and s.type <> :workflowType ")
			+ (namespace == null
				? "and s.namespace is not null and trim(s.namespace) <> '' "
				: "and lower(s.namespace) like :childPrefix ");
		var childQuery = entityManager.createQuery(childQueryText, String.class)
			.setParameter("workspaceId", workspaceId);
		if (!includeWorkflowScripts) {
			childQuery.setParameter("workflowType", ScriptType.WORKFLOW);
		}
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

		List<ScriptNamespaceEntryResponse> namespaces = childCounts.entrySet()
			.stream()
			.map(
				entry -> new ScriptNamespaceEntryResponse(entry.getKey(), lastSegment(entry.getKey()), entry.getValue())
			)
			.sorted((left, right) -> left.name().compareToIgnoreCase(right.name()))
			.toList();
		return new ScriptNamespaceChildrenResponse(namespace, namespaces, toResponses(workspaceId, scripts));
	}

	private List<ScriptResponse> toResponses(UUID workspaceId, List<Script> scripts) {
		if (scripts.isEmpty()) {
			return List.of();
		}
		List<UUID> scriptIds = scripts.stream()
			.map(script -> script.id)
			.toList();
		List<UUID> draftIds = entityManager.createQuery(
				"select distinct v.script.id from ScriptVersion v where v.script.id in "
					+ ":scriptIds and v.releasedAt is null",
				UUID.class
			)
			.setParameter("scriptIds", scriptIds)
			.getResultList();
		List<UUID> releasedIds = entityManager.createQuery(
				"select distinct v.script.id from ScriptVersion v where v.script.id in :scriptIds "
					+ "and v.releasedAt is not null and v.deprecatedAt is null",
				UUID.class
			)
			.setParameter("scriptIds", scriptIds)
			.getResultList();
		return scripts.stream()
			.map(script -> toResponse(script, draftIds.contains(script.id), releasedIds.contains(script.id)))
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

	private JsonNode activeInputSchema(Script script) {
		if (script == null || script.activeVersion == null) {
			return defaultSchema(null);
		}
		return defaultSchema(script.activeVersion.inputSchema);
	}

	private JsonNode activeOutputSchema(Script script) {
		if (script == null || script.activeVersion == null) {
			return defaultSchema(null);
		}
		return defaultSchema(script.activeVersion.outputSchema);
	}

	private ScriptResponse toResponse(Script script, boolean hasDraft, boolean released) {
		return new ScriptResponse(
			script.id,
			script.name,
			script.description,
			script.namespace,
			script.slug,
			activeInputSchema(script),
			activeOutputSchema(script),
			resolveWorkflowStateSchema(script),
			script.type == null ? ScriptType.STANDALONE.name() : script.type.name(),
			script.workflowDefinition == null ? null : script.workflowDefinition.id,
			hasDraft,
			released,
			script.activeVersion == null ? null : script.activeVersion.id,
			script.createdBy == null ? null : script.createdBy.id,
			script.updatedAt,
			script.scheduled,
			script.scheduleRrule,
			script.scheduleTimezone,
			script.scheduleStartAt,
			script.scheduleEndAt,
			script.nextRunAt,
			script.lastRunAt,
			script.toolHookEnabled,
			script.toolHookPhase == null ? null : script.toolHookPhase.name(),
			script.toolHookToolNames == null ? List.of() : script.toolHookToolNames
		);
	}

	private ScriptType parseScriptType(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return ScriptType.valueOf(raw.trim().toUpperCase());
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private void applyScheduleConfig(Script script, ScriptMetadataRequest request) {
		if (script == null || request == null) {
			return;
		}
		if (script.type != ScriptType.STANDALONE) {
			script.scheduled = false;
			script.scheduleRrule = null;
			script.scheduleTimezone = null;
			script.scheduleStartAt = null;
			script.scheduleEndAt = null;
			script.nextRunAt = null;
			script.lastRunAt = null;
			return;
		}
		if (!request.scheduled()) {
			script.scheduled = false;
			script.scheduleRrule = null;
			script.scheduleTimezone = null;
			script.scheduleStartAt = null;
			script.scheduleEndAt = null;
			script.nextRunAt = null;
			return;
		}
		String rrule = request.schedule_rrule();
		String timezone = request.schedule_timezone();
		if (rrule == null || rrule.isBlank()) {
			throw new WebApplicationException("Schedule rule is required", Response.Status.BAD_REQUEST);
		}
		if (timezone == null || timezone.isBlank()) {
			throw new WebApplicationException("Schedule timezone is required", Response.Status.BAD_REQUEST);
		}
		script.scheduled = true;
		script.scheduleRrule = rrule.trim();
		script.scheduleTimezone = timezone.trim();
		script.scheduleStartAt = request.schedule_start_at();
		script.scheduleEndAt = request.schedule_end_at();
		Instant nextRun = scheduleService.resolveNextRun(script, Instant.now());
		if (nextRun == null) {
			throw new WebApplicationException("Unable to compute next scheduled run", Response.Status.BAD_REQUEST);
		}
		script.nextRunAt = nextRun;
	}

	private void applyToolHookConfig(Script script, ScriptMetadataRequest request) {
		if (script == null || request == null) {
			return;
		}
		if (script.type != ScriptType.STANDALONE) {
			script.toolHookEnabled = false;
			script.toolHookPhase = null;
			script.toolHookToolNames = null;
			return;
		}
		if (!request.tool_hook_enabled()) {
			script.toolHookEnabled = false;
			script.toolHookPhase = null;
			script.toolHookToolNames = null;
			return;
		}
		ScriptToolHookPhase phase = parseToolHookPhase(request.tool_hook_phase());
		if (phase == null) {
			throw new WebApplicationException("Tool hook phase is required", Response.Status.BAD_REQUEST);
		}
		List<String> tools = request.tool_hook_tool_names();
		if (tools == null || tools.isEmpty()) {
			throw new WebApplicationException("Tool hook tools are required", Response.Status.BAD_REQUEST);
		}
		List<String> normalized = tools.stream()
			.filter(name -> name != null && !name.isBlank())
			.map(name -> name.trim())
			.distinct()
			.toList();
		if (normalized.isEmpty()) {
			throw new WebApplicationException("Tool hook tools are required", Response.Status.BAD_REQUEST);
		}
		script.toolHookEnabled = true;
		script.toolHookPhase = phase;
		script.toolHookToolNames = normalized;
	}

	private ScriptToolHookPhase parseToolHookPhase(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return ScriptToolHookPhase.valueOf(raw.trim().toUpperCase());
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private ScriptVersionResponse toVersionResponse(ScriptVersion version) {
		return new ScriptVersionResponse(
			version.id,
			version.script == null ? null : version.script.id,
			version.designSessionId,
			version.version,
			version.sourceGroovy,
			defaultSchema(version.inputSchema),
			defaultSchema(version.outputSchema),
			version.createdBy == null ? null : version.createdBy.id,
			version.releasedBy == null ? null : version.releasedBy.id,
			version.releasedAt,
			version.deprecatedAt,
			version.updatedAt
		);
	}

	private int nextScriptVersionNumber(UUID scriptId) {
		Integer currentMax = entityManager.createQuery("select max(v.version) from ScriptVersion v where v.script.id = :scriptId", Integer.class)
			.setParameter("scriptId", scriptId)
			.getSingleResult();
		return currentMax == null ? 1 : currentMax + 1;
	}

	private JsonNode defaultSchema(JsonNode schema) {
		if (schema != null && !schema.isNull()) {
			return schema;
		}
		ObjectNode node = objectMapper.createObjectNode();
		node.put("type", "object");
		return node;
	}

	private Session createScriptSession(TenantMembership membership, Script script, UUID assistantId) {
		Workspace workspace = script.workspace;
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
		session.title = "Script: " + script.name;
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
		overwriteScriptSnapshot(snapshot, workspace, session);
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

	private void overwriteScriptSnapshot(ObjectNode snapshot, Workspace workspace, Session session) {
		if (snapshot == null || workspace == null) {
			return;
		}
		McpServer scriptServer = ensureScriptServer(workspace);
		ObjectNode mcp = snapshot.get("mcp") instanceof ObjectNode node ? node : snapshot.putObject("mcp");
		ArrayNode servers = objectMapper.createArrayNode();
		if (scriptServer != null) {
			servers.add(scriptServer.id.toString());
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

	private McpServer ensureScriptServer(Workspace workspace) {
		List<McpServer> existing = entityManager.createQuery(
				"select s from McpServer s where s.workspace.id = :workspaceId and s.internal = true "
					+ "and s.protocol = :protocol and lower(s.virtualType) = :virtualType",
				McpServer.class
			)
			.setParameter("workspaceId", workspace.id)
			.setParameter("protocol", McpProtocol.VIRTUAL)
			.setParameter("virtualType", "polymr_script")
			.getResultList();
		if (!existing.isEmpty()) {
			McpServer server = existing.get(0);
			toolCatalogService.refreshTools(server, virtualMcpService.listTools(server));
			return server;
		}
		McpServer server = new McpServer();
		server.workspace = workspace;
		server.name = "Polymr Script";
		server.description = "Internal script tools";
		server.protocol = McpProtocol.VIRTUAL;
		server.virtualType = "polymr_script";
		server.visibility = McpServerVisibility.HIDDEN;
		server.internal = true;
		entityManager.persist(server);
		toolCatalogService.refreshTools(server, virtualMcpService.listTools(server));
		return server;
	}

	private JsonNode resolveWorkflowStateSchema(Script script) {
		if (script == null || script.workflowDefinition == null) {
			return null;
		}
		WorkflowDefinitionVersion version = loadReleasedVersion(script.workflowDefinition);
		if (version == null || version.definitionJson == null) {
			return null;
		}
		return version.definitionJson.get("state_schema");
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

	private void archiveScriptSession(UUID sessionId) {
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
