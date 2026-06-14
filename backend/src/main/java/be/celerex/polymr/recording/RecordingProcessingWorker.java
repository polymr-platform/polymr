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

package be.celerex.polymr.recording;

import be.celerex.polymr.automation.PromptService;
import be.celerex.polymr.model.AiModel;
import be.celerex.polymr.model.Recording;
import be.celerex.polymr.model.RecordingStatus;
import be.celerex.polymr.model.Rule;
import be.celerex.polymr.model.TenantAutomationTask;
import be.celerex.polymr.modelregistry.ModelConfigService;
import be.celerex.polymr.modelregistry.provider.AiChatModelDefinition;
import be.celerex.polymr.modelregistry.provider.AiChatModelProvider;
import be.celerex.polymr.modelregistry.provider.AiChatModelProviderRegistry;
import be.celerex.polymr.storage.StoredBlob;
import be.celerex.polymr.storage.WorkspaceBlobStore;
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
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import org.jboss.logging.Logger;

@ApplicationScoped
public class RecordingProcessingWorker {
	private static final Logger LOGGER = Logger.getLogger(RecordingProcessingWorker.class);
	@Inject
	EntityManager entityManager;

	@Inject
	PromptService promptService;

	@Inject
	AiChatModelProviderRegistry providerRegistry;

	@Inject
	ModelConfigService modelConfigService;

	@Inject
	WorkspaceBlobStore blobStore;

	@ActivateRequestContext
	public void process(UUID recordingId) {
		try {
			RecordingSnapshot snapshot = loadSnapshot(recordingId);
			if (snapshot == null) {
				return;
			}
			if (!prepareTask(snapshot, "TRANSCRIPTION", "Transcription model is not configured.")) {
				return;
			}
			updateStatus(recordingId, RecordingStatus.TRANSCRIBING, null);
			String transcript = transcribe(snapshot);
			if (transcript == null || transcript.isBlank()) {
				updateStatus(recordingId, RecordingStatus.ERROR, "Transcription failed to produce output.");
				return;
			}
			saveTranscript(recordingId, transcript.trim());
			updateStatus(recordingId, RecordingStatus.TRANSCRIBED, null);

			if (!prepareTask(snapshot, "SUMMARIZE", "Summarize task is not configured.")) {
				return;
			}
			updateStatus(recordingId, RecordingStatus.SUMMARIZING, null);
			String summary = runTextTask(snapshot, "SUMMARIZE", transcript.trim());
			if (summary == null || summary.isBlank()) {
				updateStatus(recordingId, RecordingStatus.ERROR, "Summarize task failed to produce output.");
				return;
			}
			saveSummary(recordingId, summary.trim());
			updateStatus(recordingId, RecordingStatus.SUMMARIZED, null);
		}
		catch (Exception ex) {
			LOGGER.warnf("Recording processing failed %s: %s", recordingId, ex.getMessage());
			updateStatus(recordingId, RecordingStatus.ERROR, "Recording processing failed.");
		}
	}

	private boolean prepareTask(RecordingSnapshot snapshot, String taskType, String errorMessage) {
		TaskConfig task = resolveTask(snapshot, taskType);
		if (task == null || !task.enabled) {
			updateStatus(snapshot.id, RecordingStatus.ERROR, errorMessage);
			return false;
		}
		if (!task.modelEnabled) {
			updateStatus(snapshot.id, RecordingStatus.ERROR, errorMessage);
			return false;
		}
		AiChatModelProvider provider = providerRegistry.find(task.providerId).orElse(null);
		if (provider == null) {
			updateStatus(snapshot.id, RecordingStatus.ERROR, errorMessage);
			return false;
		}
		String modelId = resolveModelId(task.modelConfig);
		AiChatModelDefinition definition = provider.resolveChatModel(modelId).orElse(null);
		if (definition == null) {
			updateStatus(snapshot.id, RecordingStatus.ERROR, errorMessage);
			return false;
		}
		return true;
	}

	private String transcribe(RecordingSnapshot snapshot) {
		TaskConfig task = resolveTask(snapshot, "TRANSCRIPTION");
		if (task == null) {
			return null;
		}
		String prompt = task.promptText == null || task.promptText.isBlank()
			? promptService.loadPrompt("transcribe-clean")
			: task.promptText;
		if (prompt == null || prompt.isBlank()) {
			return null;
		}
		String glossary = buildRuleBlock(snapshot.rules);
		if (!glossary.isBlank()) {
			prompt = glossary + "\n\n" + prompt;
		}
		ChatModel chatModel = resolveChatModel(task);
		if (chatModel == null) {
			return null;
		}
		if (snapshot.audioBytes == null || snapshot.audioBytes.length == 0) {
			return null;
		}
		String base64 = Base64.getEncoder().encodeToString(snapshot.audioBytes);
		String mimeType = snapshot.audioMimeType == null || snapshot.audioMimeType.isBlank()
			? "audio/webm"
			: snapshot.audioMimeType;
		Audio audioContent = Audio.builder().base64Data(base64).mimeType(mimeType).build();
		ChatResponse response = chatModel.chat(SystemMessage.from(prompt), UserMessage.from(AudioContent.from(audioContent)));
		return response == null || response.aiMessage() == null ? null : response.aiMessage().text();
	}

	private String runTextTask(RecordingSnapshot snapshot, String taskType, String input) {
		TaskConfig task = resolveTask(snapshot, taskType);
		if (task == null) {
			return null;
		}
		String prompt = task.promptText == null || task.promptText.isBlank()
			? promptService.loadPrompt("summarize")
			: task.promptText;
		if (prompt == null || prompt.isBlank()) {
			return null;
		}
		ChatModel chatModel = resolveChatModel(task);
		if (chatModel == null) {
			return null;
		}
		ChatResponse response = chatModel.chat(SystemMessage.from(prompt), UserMessage.from(input == null ? "" : input));
		return response == null || response.aiMessage() == null ? null : response.aiMessage().text();
	}

	private ChatModel resolveChatModel(TaskConfig task) {
		if (task == null || task.providerId == null) {
			return null;
		}
		AiChatModelProvider provider = providerRegistry.find(task.providerId).orElse(null);
		if (provider == null) {
			return null;
		}
		String modelId = resolveModelId(task.modelConfig);
		AiChatModelDefinition definition = provider.resolveChatModel(modelId).orElse(null);
		if (definition == null) {
			return null;
		}
		return definition.createChatModel(task.modelConfig);
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

	@Transactional
	void updateStatus(UUID recordingId, RecordingStatus status, String errorMessage) {
		if (recordingId == null) {
			return;
		}
		Recording recording = entityManager.find(Recording.class, recordingId);
		if (recording == null) {
			return;
		}
		recording.status = status;
		recording.errorMessage = errorMessage;
	}

	@Transactional
	void saveTranscript(UUID recordingId, String transcript) {
		Recording recording = entityManager.find(Recording.class, recordingId);
		if (recording != null) {
			recording.transcriptText = transcript;
		}
	}

	@Transactional
	void saveSummary(UUID recordingId, String summary) {
		Recording recording = entityManager.find(Recording.class, recordingId);
		if (recording != null) {
			recording.summaryText = summary;
		}
	}

	@Transactional
	RecordingSnapshot loadSnapshot(UUID recordingId) {
		Recording recording = entityManager.find(Recording.class, recordingId);
		if (recording == null || recording.workspace == null || recording.workspace.tenant == null) {
			return null;
		}
		byte[] bytes = null;
		if (recording.audioHash != null && !recording.audioHash.isBlank()) {
			StoredBlob blob = blobStore.load(recording.workspace.id, recording.audioHash).orElse(null);
			bytes = blob == null ? null : blob.bytes();
		}
		return new RecordingSnapshot(
			recording.id,
			recording.workspace.tenant.id,
			recording.workspace.id,
			recording.audioHash,
			recording.audioMimeType,
			bytes,
			loadRules(recording.id)
		);
	}

	private List<RuleSnippet> loadRules(UUID recordingId) {
		if (recordingId == null) {
			return List.of();
		}
		return entityManager.createQuery(
				"select r from RecordingRule rr join rr.rule r "
					+ "where rr.recording.id = :recordingId and r.enabled = true",
				Rule.class
			)
			.setParameter("recordingId", recordingId)
			.getResultList()
			.stream()
			.map(rule -> new RuleSnippet(rule.name, rule.content))
			.toList();
	}

	private String buildRuleBlock(List<RuleSnippet> rules) {
		if (rules == null || rules.isEmpty()) {
			return "";
		}
		StringBuilder builder = new StringBuilder();
		builder.append("Glossary terms (use exact spelling/casing):\n");
		int count = 0;
		for (RuleSnippet rule : rules) {
			if (rule == null || rule.name == null || rule.name.isBlank()) {
				continue;
			}
			String line = rule.name.trim();
			String description = trimRuleDescription(rule.content);
			if (!description.isBlank()) {
				line = line + " — " + description;
			}
			builder.append("- ").append(line).append("\n");
			count += 1;
			if (count >= 30) {
				break;
			}
		}
		return builder.toString().trim();
	}

	private String trimRuleDescription(String content) {
		if (content == null) {
			return "";
		}
		String trimmed = content.trim();
		if (trimmed.isBlank()) {
			return "";
		}
		int newline = trimmed.indexOf('\n');
		if (newline >= 0) {
			trimmed = trimmed.substring(0, newline);
		}
		if (trimmed.length() > 200) {
			trimmed = trimmed.substring(0, 200).trim();
		}
		return trimmed;
	}

	@Transactional
	TaskConfig resolveTask(RecordingSnapshot snapshot, String taskType) {
		if (snapshot == null || snapshot.tenantId == null) {
			return null;
		}
		TenantAutomationTask task = entityManager.createQuery(
				"select t from TenantAutomationTask t join fetch t.model "
					+ "where t.tenant.id = :tenantId and t.taskType = :taskType",
				TenantAutomationTask.class
			)
			.setParameter("tenantId", snapshot.tenantId)
			.setParameter("taskType", taskType)
			.getResultStream()
			.findFirst()
			.orElse(null);
		if (task == null) {
			return null;
		}
		AiModel model = task.model;
		Map<String, Object> modelConfig = model == null ? null : modelConfigService.resolveConfig(model);
		return new TaskConfig(
			task.enabled,
			model != null && model.enabled,
			model == null ? null : model.provider,
			modelConfig,
			task.promptText
		);
	}

	private record RecordingSnapshot(
			UUID id,
			UUID tenantId,
			UUID workspaceId,
			String audioHash,
			String audioMimeType,
			byte[] audioBytes,
			List<RuleSnippet> rules) {}

	private record RuleSnippet(String name, String content) {}

	private record TaskConfig(
			boolean enabled,
			boolean modelEnabled,
			String providerId,
			Map<String, Object> modelConfig,
			String promptText) {}
}
