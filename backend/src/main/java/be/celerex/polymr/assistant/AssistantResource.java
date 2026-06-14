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

package be.celerex.polymr.assistant;

import be.celerex.polymr.assistant.dto.AssistantRequest;
import be.celerex.polymr.assistant.dto.AssistantResponse;
import be.celerex.polymr.model.AiModel;
import be.celerex.polymr.model.Assistant;
import be.celerex.polymr.model.AssistantRule;
import be.celerex.polymr.model.AssistantSkill;
import be.celerex.polymr.model.Persona;
import be.celerex.polymr.model.Rule;
import be.celerex.polymr.model.SessionStatus;
import be.celerex.polymr.model.Skill;
import be.celerex.polymr.model.TenantMembership;
import be.celerex.polymr.model.TenantRole;
import be.celerex.polymr.model.Workspace;
import be.celerex.polymr.tenant.TenantAccessService;
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
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Path("/api/tenants/{tenantId}/assistants")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class AssistantResource {
	private static final String WORKER_TOOL_SCOPE = "execute:polymr:worker";
	@Inject
	EntityManager entityManager;

	@Inject
	TenantAccessService tenantAccessService;

	@Context
	SecurityContext securityContext;

	@GET
	public List<AssistantResponse> list(@PathParam("tenantId") UUID tenantId) {
		requireMembership(tenantId);
		return listAssistants(tenantId, null);
	}

	@GET
	@Path("/workspaces/{workspaceId}")
	public List<AssistantResponse> listWorkspaceAssistants(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId) {
		requireMembership(tenantId);
		Workspace workspace = entityManager.find(Workspace.class, workspaceId);
		if (workspace == null || !workspace.tenant.id.equals(tenantId)) {
			throw new WebApplicationException("Workspace not found", Response.Status.NOT_FOUND);
		}
		return listAssistants(tenantId, workspaceId);
	}

	@GET
	@Path("/{assistantId}")
	public AssistantResponse get(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("assistantId") UUID assistantId) {
		requireMembership(tenantId);
		List<Assistant> assistants = entityManager.createQuery(
				"select a from Assistant a "
					+ "left join fetch a.persona "
					+ "left join fetch a.model "
					+ "left join fetch a.workspace "
					+ "where a.id = :assistantId and a.tenant.id = :tenantId",
				Assistant.class
			)
			.setParameter("assistantId", assistantId)
			.setParameter("tenantId", tenantId)
			.getResultList();
		if (assistants.isEmpty()) {
			throw new WebApplicationException("Assistant not found", Response.Status.NOT_FOUND);
		}
		return buildResponse(assistants.get(0));
	}

	@POST
	@Transactional
	public AssistantResponse create(@PathParam("tenantId") UUID tenantId, AssistantRequest request) {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		if (request == null || request.name() == null || request.name().isBlank()) {
			throw new WebApplicationException("Name is required", Response.Status.BAD_REQUEST);
		}
		if (request.model_id() == null) {
			throw new WebApplicationException("Model is required", Response.Status.BAD_REQUEST);
		}
		Persona persona = null;
		if (request.persona_id() != null) {
			persona = entityManager.find(Persona.class, request.persona_id());
			if (persona == null || !persona.tenant.id.equals(tenantId)) {
				throw new WebApplicationException("Persona not found", Response.Status.NOT_FOUND);
			}
		}
		AiModel model = null;
		model = entityManager.find(AiModel.class, request.model_id());
		if (model == null || !model.tenant.id.equals(tenantId)) {
			throw new WebApplicationException("Model not found", Response.Status.NOT_FOUND);
		}
		Workspace workspace = null;
		if (request.workspace_id() != null) {
			workspace = entityManager.find(Workspace.class, request.workspace_id());
			if (workspace == null || !workspace.tenant.id.equals(tenantId)) {
				throw new WebApplicationException("Workspace not found", Response.Status.NOT_FOUND);
			}
		}
		Assistant assistant = new Assistant();
		assistant.tenant = membership.tenant;
		assistant.persona = persona;
		assistant.model = model;
		assistant.workspace = workspace;
		assistant.name = request.name().trim();
		assistant.description = request.description();
		assistant.promptText = normalizePrompt(request.prompt_text());
		assistant.maxOutputTokens = normalizePositiveInteger(request.max_output_tokens());
		assistant.maxTurns = normalizePositiveInteger(request.max_turns());
		applySubassistantSettings(assistant, request);
		entityManager.persist(assistant);
		syncSkills(assistant, tenantId, request.skill_ids());
		syncRules(assistant, tenantId, request.rule_ids());
		return buildResponse(assistant);
	}

	@PUT
	@Path("/{assistantId}")
	@Transactional
	public AssistantResponse update(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("assistantId") UUID assistantId,
			AssistantRequest request) {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		Assistant assistant = entityManager.find(Assistant.class, assistantId);
		if (assistant == null || !assistant.tenant.id.equals(tenantId)) {
			throw new WebApplicationException("Assistant not found", Response.Status.NOT_FOUND);
		}
		if (request == null || request.name() == null || request.name().isBlank()) {
			throw new WebApplicationException("Name is required", Response.Status.BAD_REQUEST);
		}
		if (request.model_id() == null) {
			throw new WebApplicationException("Model is required", Response.Status.BAD_REQUEST);
		}
		Persona persona = null;
		if (request.persona_id() != null) {
			persona = entityManager.find(Persona.class, request.persona_id());
			if (persona == null || !persona.tenant.id.equals(tenantId)) {
				throw new WebApplicationException("Persona not found", Response.Status.NOT_FOUND);
			}
		}
		AiModel model = null;
		model = entityManager.find(AiModel.class, request.model_id());
		if (model == null || !model.tenant.id.equals(tenantId)) {
			throw new WebApplicationException("Model not found", Response.Status.NOT_FOUND);
		}
		Workspace workspace = null;
		if (request.workspace_id() != null) {
			workspace = entityManager.find(Workspace.class, request.workspace_id());
			if (workspace == null || !workspace.tenant.id.equals(tenantId)) {
				throw new WebApplicationException("Workspace not found", Response.Status.NOT_FOUND);
			}
		}
		assistant.name = request.name().trim();
		assistant.description = request.description();
		assistant.promptText = normalizePrompt(request.prompt_text());
		assistant.persona = persona;
		assistant.model = model;
		assistant.workspace = workspace;
		assistant.maxOutputTokens = normalizePositiveInteger(request.max_output_tokens());
		assistant.maxTurns = normalizePositiveInteger(request.max_turns());
		applySubassistantSettings(assistant, request);
		syncSkills(assistant, tenantId, request.skill_ids());
		syncRules(assistant, tenantId, request.rule_ids());
		return buildResponse(assistant);
	}

	@DELETE
	@Path("/{assistantId}")
	@Transactional
	public Response delete(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("assistantId") UUID assistantId) {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		Assistant assistant = entityManager.find(Assistant.class, assistantId);
		if (assistant == null || !assistant.tenant.id.equals(tenantId)) {
			throw new WebApplicationException("Assistant not found", Response.Status.NOT_FOUND);
		}
		entityManager.createQuery(
				"update Session s set s.defaultAssistant = null "
					+ "where s.defaultAssistant.id = :assistantId and s.status in :statuses"
			)
			.setParameter("assistantId", assistant.id)
			.setParameter("statuses", List.of(SessionStatus.PAUSED, SessionStatus.COMPLETED))
			.executeUpdate();
		Long remainingSessions = entityManager.createQuery("select count(s) from Session s where s.defaultAssistant.id = :assistantId", Long.class)
			.setParameter("assistantId", assistant.id)
			.getSingleResult();
		if (remainingSessions != null && remainingSessions > 0) {
			throw new WebApplicationException("Assistant is still used by active sessions", Response.Status.CONFLICT);
		}
		entityManager.createQuery("delete from AssistantSkill s where s.assistant.id = :assistantId")
			.setParameter("assistantId", assistant.id)
			.executeUpdate();
		entityManager.createQuery("delete from AssistantRule r where r.assistant.id = :assistantId")
			.setParameter("assistantId", assistant.id)
			.executeUpdate();
		entityManager.remove(assistant);
		return Response.noContent().build();
	}

	private List<AssistantResponse> listAssistants(UUID tenantId, UUID workspaceId) {
		String query = workspaceId == null
			? "select a from Assistant a "
				+ "left join fetch a.persona "
				+ "left join fetch a.model "
				+ "left join fetch a.workspace "
				+ "where a.tenant.id = :tenantId and a.workspace is null"
			: "select a from Assistant a "
				+ "left join fetch a.persona "
				+ "left join fetch a.model "
				+ "left join fetch a.workspace "
				+ "where a.tenant.id = :tenantId and a.workspace.id = :workspaceId";
		var typedQuery = entityManager.createQuery(query, Assistant.class)
			.setParameter("tenantId", tenantId);
		if (workspaceId != null) {
			typedQuery.setParameter("workspaceId", workspaceId);
		}
		return typedQuery.getResultList().stream().map(this::buildResponse).collect(Collectors.toList());
	}

	private AssistantResponse buildResponse(Assistant assistant) {
		return new AssistantResponse(
			assistant.id,
			assistant.name,
			assistant.description,
			assistant.promptText,
			assistant.persona == null ? null : assistant.persona.id,
			assistant.persona == null ? null : assistant.persona.name,
			assistant.model == null ? null : assistant.model.id,
			assistant.model == null ? null : assistant.model.name,
			assistant.workspace == null ? null : assistant.workspace.id,
			assistant.maxOutputTokens,
			assistant.maxTurns,
			getAssistantSkillIds(assistant.id),
			getAssistantRuleIds(assistant.id),
			assistant.workerEnabled,
			assistant.workerTrigger,
			assistant.workerAllowScopes,
			assistant.workerDenyScopes
		);
	}

	private void applySubassistantSettings(Assistant assistant, AssistantRequest request) {
		if (assistant == null || request == null) {
			return;
		}
		boolean enabled = Boolean.TRUE.equals(request.worker_enabled());
		assistant.workerEnabled = enabled;
		assistant.workerTrigger = request.worker_trigger();
		assistant.workerAllowScopes = new java.util.ArrayList<>(normalizeScopes(request.worker_allow_scopes()));
		assistant.workerDenyScopes = new java.util.ArrayList<>(normalizeScopes(request.worker_deny_scopes()));
		if (!enabled) {
			return;
		}
		if (assistant.workerAllowScopes != null
				&& assistant.workerAllowScopes
					.stream()
					.anyMatch(scope -> WORKER_TOOL_SCOPE.equals(scope))) {
			return;
		}
		if (assistant.workerDenyScopes == null) {
			assistant.workerDenyScopes = new java.util.ArrayList<>();
		}
		if (assistant.workerDenyScopes
			.stream()
			.noneMatch(scope -> WORKER_TOOL_SCOPE.equals(scope))) {
			assistant.workerDenyScopes.add(WORKER_TOOL_SCOPE);
		}
	}

	private List<String> normalizeScopes(List<String> scopes) {
		if (scopes == null || scopes.isEmpty()) {
			return List.of();
		}
		java.util.Set<String> normalized = new java.util.LinkedHashSet<>();
		for (String scope : scopes) {
			if (scope == null) {
				continue;
			}
			String trimmed = scope.trim();
			if (!trimmed.isBlank()) {
				normalized.add(trimmed);
			}
		}
		return new java.util.ArrayList<>(normalized);
	}

	private Integer normalizePositiveInteger(Integer value) {
		if (value == null || value <= 0) {
			return null;
		}
		return value;
	}

	private String normalizePrompt(String prompt) {
		if (prompt == null) {
			return null;
		}
		String trimmed = prompt.trim();
		return trimmed.isBlank() ? null : trimmed;
	}

	private void syncSkills(Assistant assistant, UUID tenantId, List<UUID> skillIds) {
		List<UUID> ids = normalizeIds(skillIds);
		entityManager.createQuery("delete from AssistantSkill s where s.assistant.id = :assistantId")
			.setParameter("assistantId", assistant.id)
			.executeUpdate();
		if (ids.isEmpty()) {
			return;
		}
		List<Skill> skills = entityManager.createQuery(
				"select s from Skill s where s.id in :ids and s.tenant.id = :tenantId "
					+ "and (s.workspace is null or s.workspace.id = :workspaceId)",
				Skill.class
			)
			.setParameter("ids", ids)
			.setParameter("tenantId", tenantId)
			.setParameter("workspaceId", assistant.workspace == null ? null : assistant.workspace.id)
			.getResultList();
		if (skills.size() != ids.size()) {
			throw new WebApplicationException("Skill not found", Response.Status.NOT_FOUND);
		}
		for (Skill skill : skills) {
			AssistantSkill link = new AssistantSkill();
			link.assistant = assistant;
			link.skill = skill;
			entityManager.persist(link);
		}
	}

	private void syncRules(Assistant assistant, UUID tenantId, List<UUID> ruleIds) {
		List<UUID> ids = normalizeIds(ruleIds);
		entityManager.createQuery("delete from AssistantRule r where r.assistant.id = :assistantId")
			.setParameter("assistantId", assistant.id)
			.executeUpdate();
		if (ids.isEmpty()) {
			return;
		}
		List<Rule> rules = entityManager.createQuery("select r from Rule r where r.id in :ids and r.tenant.id = :tenantId", Rule.class)
			.setParameter("ids", ids)
			.setParameter("tenantId", tenantId)
			.getResultList();
		if (rules.size() != ids.size()) {
			throw new WebApplicationException("Rule not found", Response.Status.NOT_FOUND);
		}
		for (Rule rule : rules) {
			if (rule.alwaysIncluded) {
				throw new WebApplicationException("Rule is always included", Response.Status.BAD_REQUEST);
			}
			AssistantRule link = new AssistantRule();
			link.assistant = assistant;
			link.rule = rule;
			entityManager.persist(link);
		}
	}

	private List<UUID> getAssistantSkillIds(UUID assistantId) {
		return entityManager.createQuery("select s.skill.id from AssistantSkill s where s.assistant.id = :assistantId", UUID.class)
			.setParameter("assistantId", assistantId)
			.getResultList();
	}

	private List<UUID> getAssistantRuleIds(UUID assistantId) {
		return entityManager.createQuery("select r.rule.id from AssistantRule r where r.assistant.id = :assistantId", UUID.class)
			.setParameter("assistantId", assistantId)
			.getResultList();
	}

	private List<UUID> normalizeIds(List<UUID> ids) {
		if (ids == null || ids.isEmpty()) {
			return List.of();
		}
		return new ArrayList<>(ids.stream()
			.filter(id -> id != null)
			.distinct()
			.toList());
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
