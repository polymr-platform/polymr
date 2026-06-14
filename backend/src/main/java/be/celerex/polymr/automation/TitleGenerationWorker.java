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

package be.celerex.polymr.automation;

import be.celerex.polymr.model.AiModel;
import be.celerex.polymr.model.Session;
import be.celerex.polymr.model.SessionVisibility;
import be.celerex.polymr.model.TenantAutomationTask;
import be.celerex.polymr.modelregistry.ModelConfigService;
import be.celerex.polymr.modelregistry.provider.AiChatModelDefinition;
import be.celerex.polymr.modelregistry.provider.AiChatModelProvider;
import be.celerex.polymr.modelregistry.provider.AiChatModelProviderRegistry;
import be.celerex.polymr.session.SessionParticipantService;
import be.celerex.polymr.session.SessionSummaryService;
import be.celerex.polymr.session.dto.SessionParticipantResponse;
import be.celerex.polymr.session.dto.SessionSummary;
import be.celerex.polymr.ws.WorkspaceSocketEvent;
import be.celerex.polymr.ws.WorkspaceSocketManager;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import jakarta.transaction.Transactional;
import jakarta.transaction.Status;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jboss.logging.Logger;

@ApplicationScoped
public class TitleGenerationWorker {
	private static final Logger LOGGER = Logger.getLogger(TitleGenerationWorker.class);
	@Inject
	EntityManager entityManager;

	@Inject
	PromptService promptService;

	@Inject
	AiChatModelProviderRegistry providerRegistry;

	@Inject
	ModelConfigService modelConfigService;

	@Inject
	SessionSummaryService summaryService;

	@Inject
	SessionParticipantService participantService;

	@Inject
	WorkspaceSocketManager socketManager;

	@Inject
	TransactionSynchronizationRegistry txRegistry;

	@ActivateRequestContext
	public void generateTitle(UUID sessionId, String userText) {
		TitleGenerationContext context = loadContext(sessionId, userText);
		if (context == null) {
			return;
		}
		AiChatModelProvider provider = providerRegistry.find(context.providerId).orElse(null);
		if (provider == null) {
			return;
		}
		AiChatModelDefinition definition = provider.resolveChatModel(context.modelId).orElse(null);
		if (definition == null) {
			return;
		}
		ChatModel chatModel = definition.createChatModel(context.modelConfig);
		List<ChatMessage> messages = List.of(SystemMessage.from(context.prompt), UserMessage.from(context.userText));
		ChatResponse response = chatModel.chat(messages);
		String title = response == null || response.aiMessage() == null ? null : response.aiMessage().text();
		title = normalizeTitle(title);
		if (title == null) {
			return;
		}
		saveTitle(sessionId, title);
	}

	@Transactional
	TitleGenerationContext loadContext(UUID sessionId, String userText) {
		Session session = entityManager.find(Session.class, sessionId);
		if (session == null) {
			return null;
		}
		if (session.titleLocked) {
			return null;
		}
		if (session.title != null && !session.title.isBlank()) {
			return null;
		}
		if (userText == null || userText.isBlank()) {
			return null;
		}
		TenantAutomationTask task = resolveTask(session);
		if (task == null || !task.enabled) {
			return null;
		}
		AiModel model = task.model;
		if (model == null || !model.enabled) {
			return null;
		}
		String prompt = task.promptText == null || task.promptText.isBlank()
			? promptService.loadPrompt("title")
			: task.promptText;
		if (prompt == null || prompt.isBlank()) {
			return null;
		}
		Map<String, Object> config = modelConfigService.resolveConfig(model);
		String modelId = resolveModelId(config);
		if (modelId == null) {
			return null;
		}
		return new TitleGenerationContext(session.id, userText, prompt, model.provider, modelId, config);
	}

	@Transactional
	void saveTitle(UUID sessionId, String title) {
		if (sessionId == null || title == null || title.isBlank()) {
			return;
		}
		int updated = entityManager.createQuery(
				"update Session s set s.title = :title where s.id = :sessionId "
					+ "and (s.title is null or trim(s.title) = '')"
			)
			.setParameter("title", title)
			.setParameter("sessionId", sessionId)
			.executeUpdate();
		if (updated < 1) {
			return;
		}
		Session session = entityManager.find(Session.class, sessionId);
		if (session == null) {
			return;
		}
		if (session.titleLocked) {
			return;
		}
		SessionSummary summary = summaryService.buildSummaries(List.of(session), session.createdBy.id)
			.stream()
			.findFirst()
			.orElse(null);
		java.util.Set<UUID> participantIds = session.visibility == SessionVisibility.PRIVATE
			? participantService.listParticipants(session)
				.stream()
				.map(SessionParticipantResponse::user_id)
				.collect(java.util.stream.Collectors.toSet())
			: java.util.Set.of();
		registerBroadcast(session, summary, participantIds);
		LOGGER.debugf("Generated title for session %s", session.id);
	}

	private record TitleGenerationContext(
			UUID sessionId,
			String userText,
			String prompt,
			String providerId,
			String modelId,
			Map<String, Object> modelConfig) {}

	private String resolveModelId(Map<String, Object> config) {
		if (config == null) {
			return null;
		}
		Object value = config.get("model_id");
		if (value == null) {
			return null;
		}
		String modelId = value.toString();
		return modelId == null || modelId.isBlank() ? null : modelId;
	}

	private void registerBroadcast(
			Session session,
			SessionSummary summary,
			java.util.Set<UUID> participantIds) {
		txRegistry.registerInterposedSynchronization(
			new Synchronization() {
				@Override
				public void beforeCompletion() {}

				@Override
				public void afterCompletion(int status) {
					if (status != Status.STATUS_COMMITTED) {
						return;
					}
					WorkspaceSocketEvent event = new WorkspaceSocketEvent("session.updated", session.workspace.id, session.id, summary);
					if (session.visibility == SessionVisibility.WORKSPACE) {
						socketManager.broadcast(session.workspace.id, event);
					}
					else if (!participantIds.isEmpty()) {
						socketManager.broadcastToUsers(session.workspace.id, participantIds, event);
					}
				}
			}
		);
	}

	private String normalizeTitle(String title) {
		if (title == null) {
			return null;
		}
		String trimmed = title.trim();
		if (trimmed.isBlank()) {
			return null;
		}
		if (trimmed.startsWith("Title:")) {
			trimmed = trimmed.substring(6).trim();
		}
		if (trimmed.length() >= 2
				&& ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
						|| (trimmed.startsWith("'") && trimmed.endsWith("'")))) {
			trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
		}
		return trimmed.isBlank() ? null : trimmed;
	}

	private TenantAutomationTask resolveTask(Session session) {
		TenantAutomationTask task = entityManager.createQuery(
				"select t from TenantAutomationTask t join fetch t.model "
					+ "where t.tenant.id = :tenantId and t.taskType = :taskType",
				TenantAutomationTask.class
			)
			.setParameter("tenantId", session.workspace.tenant.id)
			.setParameter("taskType", "TITLE")
			.getResultStream()
			.findFirst()
			.orElse(null);
		if (task != null) {
			return task;
		}
		return entityManager.createQuery(
				"select t from TenantAutomationTask t join fetch t.model "
					+ "where t.tenant.id = :tenantId and t.taskType = :taskType",
				TenantAutomationTask.class
			)
			.setParameter("tenantId", session.workspace.tenant.id)
			.setParameter("taskType", "ALL")
			.getResultStream()
			.findFirst()
			.orElse(null);
	}
}
