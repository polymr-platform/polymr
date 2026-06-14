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

package be.celerex.polymr.tenant;

import be.celerex.polymr.automation.PromptService;
import be.celerex.polymr.model.AiModel;
import be.celerex.polymr.model.AiModelType;
import be.celerex.polymr.model.TenantAutomationTask;
import be.celerex.polymr.tenant.dto.TenantAutomationTaskRequest;
import be.celerex.polymr.tenant.dto.TenantAutomationPromptResponse;
import be.celerex.polymr.tenant.dto.TenantAutomationTaskResponse;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
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

@Path("/api/tenants/{tenantId}/automation-tasks")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class TenantAutomationResource {
	@Inject
	EntityManager entityManager;

	@Inject
	TenantAccessService tenantAccessService;

	@Inject
	PromptService promptService;

	@Context
	SecurityContext securityContext;

	@GET
	public List<TenantAutomationTaskResponse> list(@PathParam("tenantId") UUID tenantId) {
		requireMembership(tenantId);
		return entityManager.createQuery(
				"select t from TenantAutomationTask t join fetch t.model where t.tenant.id = :tenantId",
				TenantAutomationTask.class
			)
			.setParameter("tenantId", tenantId)
			.getResultList()
			.stream()
			.map(this::toResponse)
			.toList();
	}

	@GET
	@Path("/defaults")
	public List<TenantAutomationPromptResponse> defaults(@PathParam("tenantId") UUID tenantId) {
		requireMembership(tenantId);
		return List.of(
			new TenantAutomationPromptResponse("TITLE", promptService.loadPrompt("title")),
			new TenantAutomationPromptResponse("TRANSCRIPTION", promptService.loadPrompt("transcribe-clean")),
			new TenantAutomationPromptResponse("SUMMARIZE", promptService.loadPrompt("summarize")),
			new TenantAutomationPromptResponse("EMBEDDING", null)
		);
	}

	@PUT
	@Path("/{taskType}")
	@Transactional
	public TenantAutomationTaskResponse upsert(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("taskType") String taskType,
			TenantAutomationTaskRequest request) {
		requireMembership(tenantId);
		if (request == null || request.model_id() == null) {
			throw new WebApplicationException("Model is required", Response.Status.BAD_REQUEST);
		}
		AiModel model = entityManager.find(AiModel.class, request.model_id());
		if (model == null || !model.tenant.id.equals(tenantId)) {
			throw new WebApplicationException("Model not found", Response.Status.NOT_FOUND);
		}
		String normalizedTask = normalizeTask(taskType);
		if (normalizedTask.isBlank()) {
			throw new WebApplicationException("Task type is required", Response.Status.BAD_REQUEST);
		}
		if (requiredModelType(normalizedTask) != model.type) {
			throw new WebApplicationException("Model type is not supported for this task", Response.Status.BAD_REQUEST);
		}
		TenantAutomationTask entry = entityManager.createQuery(
				"select t from TenantAutomationTask t where t.tenant.id = :tenantId and t.taskType = :taskType",
				TenantAutomationTask.class
			)
			.setParameter("tenantId", tenantId)
			.setParameter("taskType", normalizedTask)
			.getResultStream()
			.findFirst()
			.orElse(null);
		if (entry == null) {
			entry = new TenantAutomationTask();
			entry.tenant = tenantAccessService.requireMembership(tenantId, requireUserId()).tenant;
			entry.taskType = normalizedTask;
		}
		entry.model = model;
		entry.promptText = normalizePrompt(request.prompt_text());
		entry.enabled = request.enabled() == null || request.enabled();
		if (entry.id == null) {
			entityManager.persist(entry);
		}
		return toResponse(entry);
	}

	private TenantAutomationTaskResponse toResponse(TenantAutomationTask entry) {
		return new TenantAutomationTaskResponse(entry.id, entry.taskType, entry.model.id, entry.model.name, entry.promptText, entry.enabled);
	}

	private String normalizeTask(String task) {
		if (task == null) {
			return "";
		}
		return task.trim().toUpperCase();
	}

	private String normalizePrompt(String prompt) {
		if (prompt == null) {
			return null;
		}
		String trimmed = prompt.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	private AiModelType requiredModelType(String taskType) {
		return switch (taskType) {
			case "TITLE", "TRANSCRIPTION", "SUMMARIZE", "ALL" -> AiModelType.CHAT;
			case "EMBEDDING" -> AiModelType.EMBEDDING;
			default -> throw new WebApplicationException("Task type is not supported", Response.Status.BAD_REQUEST);
		};
	}

	private void requireMembership(UUID tenantId) {
		tenantAccessService.requireMembership(tenantId, requireUserId());
	}

	private UUID requireUserId() {
		if (securityContext == null || securityContext.getUserPrincipal() == null) {
			throw new WebApplicationException("Unauthorized", Response.Status.UNAUTHORIZED);
		}
		return UUID.fromString(securityContext.getUserPrincipal().getName());
	}
}
