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
import be.celerex.polymr.model.TenantAutomationTask;
import be.celerex.polymr.modelregistry.ModelConfigService;
import be.celerex.polymr.modelregistry.provider.AiChatModelDefinition;
import be.celerex.polymr.modelregistry.provider.AiChatModelProvider;
import be.celerex.polymr.modelregistry.provider.AiChatModelProviderRegistry;
import be.celerex.polymr.ws.WorkspaceSocketEvent;
import be.celerex.polymr.ws.WorkspaceSocketManager;
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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.jboss.logging.Logger;

@ApplicationScoped
public class VoiceTranscriptionWorker {
	private static final Logger LOGGER = Logger.getLogger(VoiceTranscriptionWorker.class);
	@Inject
	EntityManager entityManager;

	@Inject
	PromptService promptService;

	@Inject
	AiChatModelProviderRegistry providerRegistry;

	@Inject
	ModelConfigService modelConfigService;

	@Inject
	WorkspaceSocketManager socketManager;

	@ActivateRequestContext
	@Transactional
	public void transcribe(UUID sessionId, UUID userId, String connectionId, float[] audio, int sampleRate) {
		try {
			Session session = entityManager.find(Session.class, sessionId);
			if (session == null || session.workspace == null || session.workspace.tenant == null) {
				LOGGER.debugf("Voice transcription skipped (session missing) %s", sessionId);
				return;
			}
			TenantAutomationTask task = resolveTask(session);
			if (task == null || !task.enabled) {
				LOGGER.debugf("Voice transcription skipped (task disabled) session=%s", session.id);
				return;
			}
			AiModel model = task.model;
			if (model == null || !model.enabled) {
				LOGGER.debugf("Voice transcription skipped (model disabled) session=%s", session.id);
				return;
			}
			AiChatModelProvider provider = providerRegistry.find(model.provider).orElse(null);
			if (provider == null) {
				LOGGER.debugf("Voice transcription skipped (provider missing) session=%s", session.id);
				return;
			}
			String prompt = task.promptText == null || task.promptText.isBlank()
				? promptService.loadPrompt("transcribe-clean")
				: task.promptText;
			if (prompt == null || prompt.isBlank()) {
				LOGGER.debugf("Voice transcription skipped (prompt blank) session=%s", session.id);
				return;
			}
			Map<String, Object> config = modelConfigService.resolveConfig(model);
			String modelId = resolveModelId(config);
			AiChatModelDefinition definition = provider.resolveChatModel(modelId).orElse(null);
			if (definition == null) {
				LOGGER.debugf("Voice transcription skipped (model id missing) session=%s", session.id);
				return;
			}
			ChatModel chatModel = definition.createChatModel(config);
			byte[] wav = encodeWav(audio, sampleRate <= 0 ? 16000 : sampleRate);
			String base64 = Base64.getEncoder().encodeToString(wav);
			Audio audioContent = Audio.builder().base64Data(base64).mimeType("audio/wav").build();
			ChatResponse response = chatModel.chat(SystemMessage.from(prompt), UserMessage.from(AudioContent.from(audioContent)));
			String text = response == null || response.aiMessage() == null ? null : response.aiMessage().text();
			if (text == null || text.isBlank()) {
				LOGGER.debugf("Voice transcription empty response session=%s", session.id);
				return;
			}
			Map<String, Object> payload = Map.of("text", text.trim());
			WorkspaceSocketEvent event = new WorkspaceSocketEvent("voice.transcription", session.workspace.id, session.id, payload);
			socketManager.broadcastToConnection(session.workspace.id, userId, connectionId, event);
			LOGGER.debugf("Transcription generated for session %s", session.id);
		}
		catch (Exception ex) {
			LOGGER.warnf("Voice transcription failed for session %s: %s", sessionId, ex.getMessage());
			Session session = entityManager.find(Session.class, sessionId);
			if (session != null && session.workspace != null) {
				Map<String, Object> payload = Map.of("error", "Transcription failed");
				WorkspaceSocketEvent event = new WorkspaceSocketEvent("voice.transcription.error", session.workspace.id, session.id, payload);
				socketManager.broadcastToConnection(session.workspace.id, userId, connectionId, event);
			}
		}
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

	private byte[] encodeWav(float[] audio, int sampleRate) {
		int numSamples = audio.length;
		int dataSize = numSamples * 2;
		int fileSize = 36 + dataSize;
		ByteBuffer buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
		buffer.put("RIFF".getBytes());
		buffer.putInt(fileSize);
		buffer.put("WAVE".getBytes());
		buffer.put("fmt ".getBytes());
		buffer.putInt(16);
		buffer.putShort((short) 1);
		buffer.putShort((short) 1);
		buffer.putInt(sampleRate);
		buffer.putInt(sampleRate * 2);
		buffer.putShort((short) 2);
		buffer.putShort((short) 16);
		buffer.put("data".getBytes());
		buffer.putInt(dataSize);
		for (float value : audio) {
			float clamped = Math.max(-1f, Math.min(1f, value));
			short pcm = (short) Math.round(clamped * 32767f);
			buffer.putShort(pcm);
		}
		return buffer.array();
	}
}
