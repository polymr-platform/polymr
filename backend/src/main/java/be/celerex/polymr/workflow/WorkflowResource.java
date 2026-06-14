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

package be.celerex.polymr.workflow;

import be.celerex.polymr.model.TenantMembership;
import be.celerex.polymr.model.TenantRole;
import be.celerex.polymr.model.WorkflowDefinition;
import be.celerex.polymr.model.WorkflowDefinitionVersion;
import be.celerex.polymr.model.WorkflowStartTrigger;
import be.celerex.polymr.model.User;
import be.celerex.polymr.model.Script;
import be.celerex.polymr.model.ScriptVersion;
import be.celerex.polymr.model.Workspace;
import be.celerex.polymr.tenant.TenantAccessService;
import be.celerex.polymr.workflow.dto.WorkflowDefinitionRequest;
import be.celerex.polymr.workflow.dto.WorkflowDefinitionResponse;
import be.celerex.polymr.workflow.dto.WorkflowDraftRequest;
import be.celerex.polymr.workflow.dto.WorkflowVersionResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.time.Instant;
import java.util.Objects;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Path("/api/tenants/{tenantId}/workspaces/{workspaceId}/workflows")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class WorkflowResource {
	@Inject
	EntityManager entityManager;

	@Inject
	TenantAccessService tenantAccessService;

	@Inject
	ObjectMapper objectMapper;

	@Inject
	WorkflowDefinitionService workflowDefinitionService;

	@Context
	SecurityContext securityContext;

	@GET
	public List<WorkflowDefinitionResponse> list(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId) {
		requireMembership(tenantId);
		Workspace workspace = requireWorkspace(tenantId, workspaceId);
		List<WorkflowDefinition> definitions = entityManager.createQuery(
				"select w from WorkflowDefinition w where w.workspace.id = :workspaceId and w.deletedAt is null",
				WorkflowDefinition.class
			)
			.setParameter("workspaceId", workspace.id)
			.getResultList();
		List<UUID> draftIds = entityManager.createQuery(
				"select v.workflowDefinition.id from WorkflowDefinitionVersion v where "
					+ "v.workflowDefinition.workspace.id = :workspaceId "
					+ "and v.releasedAt is null",
				UUID.class
			)
			.setParameter("workspaceId", workspace.id)
			.getResultList();
		List<UUID> releasedIds = entityManager.createQuery(
				"select v.workflowDefinition.id from WorkflowDefinitionVersion v where "
					+ "v.workflowDefinition.workspace.id = :workspaceId "
					+ "and v.releasedAt is not null and v.deprecatedAt is null",
				UUID.class
			)
			.setParameter("workspaceId", workspace.id)
			.getResultList();
		return definitions.stream()
			.map(
				definition -> toResponse(definition, draftIds.contains(definition.id), releasedIds.contains(definition.id))
			)
			.collect(Collectors.toList());
	}

	@POST
	@Transactional
	public WorkflowDefinitionResponse create(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			WorkflowDefinitionRequest request) {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		Workspace workspace = requireWorkspace(tenantId, workspaceId);
		if (request == null || request.name() == null || request.name().isBlank()) {
			throw new WebApplicationException("Name is required", Response.Status.BAD_REQUEST);
		}
		WorkflowDefinition definition = new WorkflowDefinition();
		definition.tenant = membership.tenant;
		definition.workspace = workspace;
		definition.name = request.name().trim();
		definition.description = request.description();
		definition.startTrigger = request.start_trigger() == null ? WorkflowStartTrigger.USER_PROMPT : request.start_trigger();
		if (request.disabled() != null) {
			definition.disabled = request.disabled();
		}
		entityManager.persist(definition);
		boolean createdDraft = request.definition_json() != null && !request.definition_json().isNull();
		if (createdDraft) {
			workflowDefinitionService.validateDefinition(request.definition_json());
			WorkflowDefinitionVersion draft = new WorkflowDefinitionVersion();
			draft.workflowDefinition = definition;
			draft.createdBy = entityManager.find(User.class, membership.user.id);
			draft.definitionJson = request.definition_json();
			entityManager.persist(draft);
		}
		return toResponse(definition, createdDraft, false);
	}

	@PUT
	@Path("/{workflowId}")
	@Transactional
	public WorkflowDefinitionResponse update(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("workflowId") UUID workflowId,
			WorkflowDefinitionRequest request) {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		Workspace workspace = requireWorkspace(tenantId, workspaceId);
		WorkflowDefinition definition = entityManager.find(WorkflowDefinition.class, workflowId);
		if (definition == null
				|| definition.deletedAt != null
				|| !Objects.equals(definition.workspace.id, workspace.id)) {
			throw new WebApplicationException("Workflow not found", Response.Status.NOT_FOUND);
		}
		if (request != null && request.name() != null) {
			if (request.name().isBlank()) {
				throw new WebApplicationException("Name is required", Response.Status.BAD_REQUEST);
			}
			definition.name = request.name().trim();
		}
		if (request != null) {
			if (request.description() != null) {
				definition.description = request.description();
			}
			if (request.start_trigger() != null) {
				definition.startTrigger = request.start_trigger();
			}
			if (request.disabled() != null) {
				definition.disabled = request.disabled();
			}
		}
		return toResponse(definition, hasDraft(definition.id), isReleased(definition.id));
	}

	@GET
	@Path("/{workflowId}/versions")
	public List<WorkflowVersionResponse> listVersions(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("workflowId") UUID workflowId) {
		requireMembership(tenantId);
		WorkflowDefinition definition = requireDefinition(workspaceId, workflowId);
		List<WorkflowDefinitionVersion> versions = entityManager.createQuery(
				"select v from WorkflowDefinitionVersion v where v.workflowDefinition.id = "
					+ ":workflowId order by v.updatedAt desc",
				WorkflowDefinitionVersion.class
			)
			.setParameter("workflowId", definition.id)
			.getResultList();
		return versions.stream().map(this::toVersionResponse).collect(Collectors.toList());
	}

	@GET
	@Path("/{workflowId}/draft")
	@Transactional
	public WorkflowVersionResponse getDraft(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("workflowId") UUID workflowId) {
		TenantMembership membership = requireMembership(tenantId);
		WorkflowDefinition definition = requireDefinition(workspaceId, workflowId);
		WorkflowDefinitionVersion draft = findDraft(definition.id);
		if (draft == null) {
			draft = new WorkflowDefinitionVersion();
			draft.workflowDefinition = definition;
			draft.createdBy = entityManager.find(User.class, membership.user.id);
			WorkflowDefinitionVersion active = findActiveReleased(definition.id);
			if (active != null && active.definitionJson != null) {
				draft.definitionJson = active.definitionJson.deepCopy();
			}
			else {
				draft.definitionJson = workflowDefinitionService.defaultDefinition();
			}
			entityManager.persist(draft);
		}
		return toVersionResponse(draft);
	}

	@PUT
	@Path("/{workflowId}/draft")
	@Transactional
	public WorkflowVersionResponse updateDraft(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("workflowId") UUID workflowId,
			WorkflowDraftRequest request) {
		TenantMembership membership = requireMembership(tenantId);
		WorkflowDefinition definition = requireDefinition(workspaceId, workflowId);
		WorkflowDefinitionVersion draft = findDraft(definition.id);
		if (draft == null) {
			draft = new WorkflowDefinitionVersion();
			draft.workflowDefinition = definition;
			draft.createdBy = entityManager.find(User.class, membership.user.id);
			entityManager.persist(draft);
		}
		if (request != null && request.definition_json() != null) {
			workflowDefinitionService.validateDefinition(request.definition_json());
			draft.definitionJson = request.definition_json();
		}
		return toVersionResponse(draft);
	}

	@POST
	@Path("/{workflowId}/approve")
	@Transactional
	public WorkflowVersionResponse approve(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("workflowId") UUID workflowId) {
		TenantMembership membership = requireMembership(tenantId);
		WorkflowDefinition definition = requireDefinition(workspaceId, workflowId);
		WorkflowDefinitionVersion draft = findDraft(definition.id);
		if (draft == null || draft.definitionJson == null) {
			throw new WebApplicationException("Draft has no definition", Response.Status.CONFLICT);
		}
		ObjectNode updatedDefinition = applyScriptVersions(definition, draft.definitionJson, membership.user.id);
		workflowDefinitionService.validateDefinition(updatedDefinition);
		draft.definitionJson = updatedDefinition;
		WorkflowDefinitionVersion previous = findActiveReleased(definition.id);
		if (previous != null && previous != draft && previous.deprecatedAt == null) {
			previous.deprecatedAt = Instant.now();
		}
		draft.releasedBy = entityManager.find(User.class, membership.user.id);
		draft.releasedAt = Instant.now();
		return toVersionResponse(draft);
	}

	@DELETE
	@Path("/{workflowId}/draft")
	@Transactional
	public Response deleteDraft(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("workflowId") UUID workflowId) {
		requireMembership(tenantId);
		WorkflowDefinition definition = requireDefinition(workspaceId, workflowId);
		WorkflowDefinitionVersion draft = findDraft(definition.id);
		if (draft != null) {
			entityManager.remove(draft);
		}
		return Response.noContent().build();
	}

	@DELETE
	@Path("/{workflowId}")
	@Transactional
	public Response delete(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("workflowId") UUID workflowId) {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		Workspace workspace = requireWorkspace(tenantId, workspaceId);
		WorkflowDefinition definition = entityManager.find(WorkflowDefinition.class, workflowId);
		if (definition == null
				|| definition.deletedAt != null
				|| !Objects.equals(definition.workspace.id, workspace.id)) {
			throw new WebApplicationException("Workflow not found", Response.Status.NOT_FOUND);
		}
		Long runCount = entityManager.createQuery("select count(r) from WorkflowRun r where r.workflowDefinition.id = :workflowId", Long.class)
			.setParameter("workflowId", definition.id)
			.getSingleResult();
		if (runCount != null && runCount > 0) {
			definition.deletedAt = Instant.now();
			definition.disabled = true;
		}
		else {
			entityManager.createQuery(
					"delete from ScriptVersion v where v.script.id in "
						+ "(select s.id from Script s where s.workflowDefinition.id = :workflowId)"
				)
				.setParameter("workflowId", definition.id)
				.executeUpdate();
			entityManager.createQuery("delete from Script s where s.workflowDefinition.id = :workflowId")
				.setParameter("workflowId", definition.id)
				.executeUpdate();
			entityManager.createQuery("delete from WorkflowDefinitionVersion v where v.workflowDefinition.id = :workflowId")
				.setParameter("workflowId", definition.id)
				.executeUpdate();
			entityManager.remove(definition);
		}
		return Response.noContent().build();
	}

	private WorkflowDefinitionResponse toResponse(WorkflowDefinition definition, boolean hasDraft, boolean released) {
		WorkflowDefinitionVersion active = findActiveReleased(definition.id);
		return new WorkflowDefinitionResponse(
			definition.id,
			definition.name,
			definition.description,
			definition.startTrigger,
			definition.disabled,
			hasDraft,
			released,
			active == null ? null : active.id
		);
	}

	private WorkflowVersionResponse toVersionResponse(WorkflowDefinitionVersion version) {
		return new WorkflowVersionResponse(
			version.id,
			version.workflowDefinition == null ? null : version.workflowDefinition.id,
			version.definitionJson,
			version.createdBy == null ? null : version.createdBy.id,
			version.releasedBy == null ? null : version.releasedBy.id,
			version.releasedAt,
			version.deprecatedAt,
			version.updatedAt
		);
	}

	private WorkflowDefinitionVersion findDraft(UUID workflowId) {
		return entityManager.createQuery(
				"select v from WorkflowDefinitionVersion v where v.workflowDefinition.id = "
					+ ":workflowId and v.releasedAt is null",
				WorkflowDefinitionVersion.class
			)
			.setParameter("workflowId", workflowId)
			.setMaxResults(1)
			.getResultStream()
			.findFirst()
			.orElse(null);
	}

	private WorkflowDefinitionVersion findActiveReleased(UUID workflowId) {
		return entityManager.createQuery(
				"select v from WorkflowDefinitionVersion v where v.workflowDefinition.id = :workflowId "
					+ "and v.releasedAt is not null and v.deprecatedAt is null",
				WorkflowDefinitionVersion.class
			)
			.setParameter("workflowId", workflowId)
			.setMaxResults(1)
			.getResultStream()
			.findFirst()
			.orElse(null);
	}

	private ObjectNode applyScriptVersions(WorkflowDefinition definition, JsonNode definitionJson, UUID userId) {
		ObjectNode normalized = definitionJson instanceof ObjectNode node
			? node.deepCopy()
			: objectMapper.convertValue(definitionJson, ObjectNode.class);
		ObjectNode nodes = normalized.get("nodes") instanceof ObjectNode node ? node : normalized.putObject("nodes");
		nodes.fieldNames()
			.forEachRemaining(
				nodeId -> {
					ObjectNode node = nodes.get(nodeId) instanceof ObjectNode entry ? entry : nodes.putObject(nodeId);
					if (!"script".equalsIgnoreCase(node.path("type").asText(null))) {
						return;
					}
					String scriptId = node.path("script_id").asText(null);
					if (scriptId == null || scriptId.isBlank()) {
						return;
					}
					Script script;
					try {
						script = entityManager.find(Script.class, UUID.fromString(scriptId));
					}
					catch (IllegalArgumentException ex) {
						return;
					}
					if (script == null
							|| script.disabled
							|| script.workspace == null
							|| !script.workspace.id.equals(definition.workspace.id)) {
						return;
					}
					ScriptVersion released = releaseScriptVersion(script, userId);
					if (released == null) {
						throw new WebApplicationException("Script has no released version", Response.Status.CONFLICT);
					}
					node.put("script_version_id", released.id.toString());
				}
			);
		return normalized;
	}

	private ScriptVersion releaseScriptVersion(Script script, UUID userId) {
		ScriptVersion draft = entityManager.createQuery(
				"select v from ScriptVersion v where v.script.id = :scriptId and v.releasedAt is null",
				ScriptVersion.class
			)
			.setParameter("scriptId", script.id)
			.setMaxResults(1)
			.getResultStream()
			.findFirst()
			.orElse(null);
		if (draft != null) {
			ScriptVersion previous = script.activeVersion;
			if (previous != null && previous != draft && previous.deprecatedAt == null) {
				previous.deprecatedAt = Instant.now();
			}
			draft.releasedBy = userId == null ? null : entityManager.find(User.class, userId);
			draft.releasedAt = Instant.now();
			script.activeVersion = draft;
			return draft;
		}
		return script.activeVersion;
	}

	private boolean hasDraft(UUID workflowId) {
		return findDraft(workflowId) != null;
	}

	private boolean isReleased(UUID workflowId) {
		return !entityManager.createQuery(
				"select v.id from WorkflowDefinitionVersion v where v.workflowDefinition.id = :workflowId "
					+ "and v.releasedAt is not null and v.deprecatedAt is null",
				UUID.class
			)
			.setParameter("workflowId", workflowId)
			.setMaxResults(1)
			.getResultList()
			.isEmpty();
	}

	private WorkflowDefinition requireDefinition(UUID workspaceId, UUID workflowId) {
		WorkflowDefinition definition = entityManager.find(WorkflowDefinition.class, workflowId);
		if (definition == null
				|| definition.deletedAt != null
				|| !Objects.equals(definition.workspace.id, workspaceId)) {
			throw new WebApplicationException("Workflow not found", Response.Status.NOT_FOUND);
		}
		return definition;
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
