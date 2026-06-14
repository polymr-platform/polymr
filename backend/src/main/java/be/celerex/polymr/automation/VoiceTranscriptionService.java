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

import java.util.Base64;
import java.util.Map;
import org.eclipse.microprofile.context.ManagedExecutor;
import io.smallrye.context.api.ManagedExecutorConfig;
import org.eclipse.microprofile.context.ThreadContext;
import be.celerex.polymr.model.AiModel;
import be.celerex.polymr.model.Session;
import be.celerex.polymr.model.TenantAutomationTask;
import be.celerex.polymr.modelregistry.ModelConfigService;
import be.celerex.polymr.modelregistry.provider.AiChatModelDefinition;
import be.celerex.polymr.modelregistry.provider.AiChatModelProvider;
import be.celerex.polymr.modelregistry.provider.AiChatModelProviderRegistry;
import dev.langchain4j.data.audio.Audio;
import dev.langchain4j.data.message.AudioContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.util.UUID;
import org.jboss.logging.Logger;

@ApplicationScoped
public class VoiceTranscriptionService {
	private static final Logger LOGGER = Logger.getLogger(VoiceTranscriptionService.class);
	@Inject
	VoiceTranscriptionWorker worker;

	@Inject
	EntityManager entityManager;

	@Inject
	PromptService promptService;

	@Inject
	AiChatModelProviderRegistry providerRegistry;

	@Inject
	ModelConfigService modelConfigService;

	@Inject
	@ManagedExecutorConfig(cleared = ThreadContext.TRANSACTION)
	ManagedExecutor transcriptionExecutor;

	public void queueTranscription(UUID sessionId, UUID userId, String connectionId, float[] audio, int sampleRate) {
		if (sessionId == null || userId == null || connectionId == null || connectionId.isBlank()) {
			return;
		}
		if (audio == null || audio.length == 0) {
			return;
		}
		transcriptionExecutor.runAsync(
			() -> {
				try {
					worker.transcribe(sessionId, userId, connectionId, audio, sampleRate);
				}
				catch (Exception ex) {
					LOGGER.errorf(ex, "Async voice transcription failed for session %s", sessionId);
					throw ex;
				}
			}
		);
	}

	@ActivateRequestContext
	@Transactional
	public String transcribeAudio(UUID sessionId, UUID userId, byte[] audioBytes, String mimeType) {
		Session session = entityManager.find(Session.class, sessionId);
		if (session == null || session.workspace == null || session.workspace.tenant == null) {
			LOGGER.debugf("Voice audio transcription skipped (session missing) %s", sessionId);
			return null;
		}
		TenantAutomationTask task = resolveTask(session);
		if (task == null || !task.enabled) {
			LOGGER.debugf("Voice audio transcription skipped (task disabled) session=%s", session.id);
			return null;
		}
		AiModel model = task.model;
		if (model == null || !model.enabled) {
			LOGGER.debugf("Voice audio transcription skipped (model disabled) session=%s", session.id);
			return null;
		}
		AiChatModelProvider provider = providerRegistry.find(model.provider).orElse(null);
		if (provider == null) {
			LOGGER.debugf("Voice audio transcription skipped (provider missing) session=%s", session.id);
			return null;
		}
		String prompt = task.promptText == null || task.promptText.isBlank()
			? promptService.loadPrompt("transcribe-clean")
			: task.promptText;
		if (prompt == null || prompt.isBlank()) {
			LOGGER.debugf("Voice audio transcription skipped (prompt blank) session=%s", session.id);
			return null;
		}
		Map<String, Object> config = modelConfigService.resolveConfig(model);
		String modelId = resolveModelId(config);
		AiChatModelDefinition definition = provider.resolveChatModel(modelId).orElse(null);
		if (definition == null) {
			LOGGER.debugf("Voice audio transcription skipped (model id missing) session=%s", session.id);
			return null;
		}
		ChatModel chatModel = definition.createChatModel(config);
		Audio audioContent = Audio.builder()
			.base64Data(Base64.getEncoder().encodeToString(audioBytes))
			.mimeType(mimeType == null || mimeType.isBlank() ? "audio/wav" : mimeType)
			.build();
		ChatResponse response = chatModel.chat(SystemMessage.from(prompt), UserMessage.from(AudioContent.from(audioContent)));
		String text = response == null || response.aiMessage() == null ? null : response.aiMessage().text();
		if (text == null || text.isBlank()) {
			LOGGER.debugf("Voice audio transcription empty response session=%s", session.id);
			return null;
		}
		return text.trim();
	}

	private TenantAutomationTask resolveTask(Session session) {
		return entityManager.createQuery(
				"select t from TenantAutomationTask t join fetch t.model "
					+ "where t.tenant.id = :tenantId and t.taskType = :taskType",
				TenantAutomationTask.class
			)
			.setParameter("tenantId", session.workspace.tenant.id)
			.setParameter("taskType", "TRANSCRIPTION")
			.getResultStream()
			.findFirst()
			.orElse(null);
	}

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
}
