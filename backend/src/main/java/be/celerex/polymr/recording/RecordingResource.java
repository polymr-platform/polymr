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

import be.celerex.polymr.model.Recording;
import be.celerex.polymr.model.RecordingRule;
import be.celerex.polymr.model.RecordingStatus;
import be.celerex.polymr.model.Rule;
import be.celerex.polymr.model.TenantMembership;
import be.celerex.polymr.model.User;
import be.celerex.polymr.model.Workspace;
import be.celerex.polymr.storage.StoredBlob;
import be.celerex.polymr.storage.WorkspaceBlobStore;
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
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import jakarta.transaction.Status;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Path("/api/tenants/{tenantId}/workspaces/{workspaceId}/recordings")
@Produces(MediaType.APPLICATION_JSON)
public class RecordingResource {
	@Inject
	WorkspaceBlobStore blobStore;

	@Inject
	EntityManager entityManager;

	@Inject
	TenantAccessService tenantAccessService;

	@Inject
	RecordingProcessingService processingService;

	@Inject
	ObjectMapper objectMapper;

	@Inject
	TransactionSynchronizationRegistry txRegistry;

	@Context
	SecurityContext securityContext;

	@GET
	public List<RecordingResponse> list(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId) {
		requireMembership(tenantId);
		Workspace workspace = requireWorkspace(tenantId, workspaceId);
		return entityManager.createQuery(
				"select r from Recording r where r.workspace.id = :workspaceId order by r.createdAt desc",
				Recording.class
			)
			.setParameter("workspaceId", workspace.id)
			.getResultList()
			.stream()
			.map(this::toResponse)
			.toList();
	}

	@GET
	@Path("/{recordingId}")
	public RecordingDetailResponse get(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("recordingId") UUID recordingId) {
		requireMembership(tenantId);
		Recording recording = requireRecording(tenantId, workspaceId, recordingId);
		return toDetailResponse(recording);
	}

	@GET
	@Path("/{recordingId}/audio")
	@Produces("*/*")
	public Response downloadAudio(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("recordingId") UUID recordingId) {
		requireMembership(tenantId);
		Recording recording = requireRecording(tenantId, workspaceId, recordingId);
		StoredBlob blob = blobStore.load(recording.workspace.id, recording.audioHash)
			.orElseThrow(() -> new WebApplicationException("Audio not found", Response.Status.NOT_FOUND));
		String mimeType = recording.audioMimeType == null || recording.audioMimeType.isBlank()
			? "application/octet-stream"
			: recording.audioMimeType;
		String filename = sanitizeFilename(recording.title) + "." + resolveExtension(mimeType);
		return Response.ok(blob.bytes())
			.type(mimeType)
			.header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
			.build();
	}

	@DELETE
	@Path("/{recordingId}")
	@Transactional
	public Response delete(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("recordingId") UUID recordingId) {
		requireMembership(tenantId);
		Recording recording = requireRecording(tenantId, workspaceId, recordingId);
		entityManager.createQuery("delete from RecordingRule rr where rr.recording.id = :recordingId")
			.setParameter("recordingId", recordingId)
			.executeUpdate();
		if (recording.audioHash != null && !recording.audioHash.isBlank()) {
			blobStore.delete(recording.workspace.id, recording.audioHash);
		}
		entityManager.remove(recording);
		return Response.noContent().build();
	}

	@PUT
	@Path("/{recordingId}")
	@Consumes(MediaType.APPLICATION_JSON)
	@Transactional
	public RecordingResponse update(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("recordingId") UUID recordingId,
			RecordingUpdateRequest request) {
		requireMembership(tenantId);
		Recording recording = requireRecording(tenantId, workspaceId, recordingId);
		if (request == null) {
			throw new WebApplicationException("Update payload is required", Response.Status.BAD_REQUEST);
		}
		if (request.title() != null) {
			String title = request.title().trim();
			if (title.isBlank()) {
				throw new WebApplicationException("Title cannot be empty", Response.Status.BAD_REQUEST);
			}
			recording.title = title;
		}
		if (request.summary_text() != null) {
			String summary = request.summary_text().trim();
			if (summary.isBlank()) {
				throw new WebApplicationException("Summary cannot be empty", Response.Status.BAD_REQUEST);
			}
			recording.summaryText = summary;
		}
		if (request.started_at() != null) {
			if (request.started_at().isBlank()) {
				recording.startedAt = null;
			}
			else {
				recording.startedAt = parseStartedAt(request.started_at());
			}
		}
		return toResponse(recording);
	}

	@POST
	@Consumes("audio/*")
	@Transactional
	public RecordingResponse upload(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@QueryParam("title") String title,
			@QueryParam("duration_seconds") Integer durationSeconds,
			@QueryParam("started_at") String startedAt,
			@QueryParam("rule_ids") String ruleIds,
			InputStream body,
			@Context HttpHeaders headers) {
		requireMembership(tenantId);
		Workspace workspace = requireWorkspace(tenantId, workspaceId);
		if (body == null) {
			throw new WebApplicationException("Recording file is required", Response.Status.BAD_REQUEST);
		}
		String normalizedTitle = title == null ? "" : title.trim();
		if (normalizedTitle.isBlank()) {
			throw new WebApplicationException("Title is required", Response.Status.BAD_REQUEST);
		}
		byte[] bytes = readBytes(body);
		if (bytes == null || bytes.length == 0) {
			throw new WebApplicationException("Recording file is empty", Response.Status.BAD_REQUEST);
		}
		String mimeType = headers == null ? null : headers.getHeaderString("Content-Type");
		if (mimeType == null || mimeType.isBlank()) {
			mimeType = "application/octet-stream";
		}
		StoredBlob stored = blobStore.store(workspace.id, bytes, mimeType);
		Recording recording = new Recording();
		recording.tenant = workspace.tenant;
		recording.workspace = workspace;
		recording.createdBy = entityManager.getReference(User.class, requireUserId());
		recording.title = normalizedTitle;
		recording.audioHash = stored.hash();
		recording.audioMimeType = stored.mimeType();
		recording.audioSizeBytes = stored.byteSize();
		recording.durationSeconds = durationSeconds;
		recording.startedAt = startedAt == null || startedAt.isBlank() ? null : parseStartedAt(startedAt);
		recording.status = RecordingStatus.UPLOADED;
		entityManager.persist(recording);
		persistRecordingRules(recording, ruleIds, tenantId, workspaceId);
		registerProcessing(recording.id);
		return toResponse(recording);
	}

	private void persistRecordingRules(Recording recording, String ruleIds, UUID tenantId, UUID workspaceId) {
		if (recording == null || ruleIds == null || ruleIds.isBlank()) {
			return;
		}
		List<UUID> parsed = parseRuleIds(ruleIds);
		if (parsed.isEmpty()) {
			return;
		}
		List<Rule> rules = entityManager.createQuery(
				"select r from Rule r where r.id in :ids and r.tenant.id = :tenantId "
					+ "and (r.workspace is null or r.workspace.id = :workspaceId)",
				Rule.class
			)
			.setParameter("ids", parsed)
			.setParameter("tenantId", tenantId)
			.setParameter("workspaceId", workspaceId)
			.getResultList();
		for (Rule rule : rules) {
			RecordingRule link = new RecordingRule();
			link.recording = recording;
			link.rule = rule;
			entityManager.persist(link);
		}
	}

	private List<UUID> parseRuleIds(String ruleIds) {
		List<UUID> result = new ArrayList<>();
		if (ruleIds == null || ruleIds.isBlank()) {
			return result;
		}
		String trimmed = ruleIds.trim();
		try {
			if (trimmed.startsWith("[")) {
				JsonNode node = objectMapper.readTree(trimmed);
				if (node.isArray()) {
					node.forEach(entry -> {
						if (entry.isTextual()) {
							addUuid(result, entry.asText());
						}
					});
				}
				return result;
			}
		}
		catch (Exception ignored) {}
		for (String entry : trimmed.split(",")) {
			addUuid(result, entry);
		}
		return result;
	}

	private void addUuid(List<UUID> result, String value) {
		if (value == null) {
			return;
		}
		String trimmed = value.trim();
		if (trimmed.isBlank()) {
			return;
		}
		try {
			result.add(UUID.fromString(trimmed));
		}
		catch (IllegalArgumentException ignored) {}
	}

	private void registerProcessing(UUID recordingId) {
		txRegistry.registerInterposedSynchronization(
			new Synchronization() {
				@Override
				public void beforeCompletion() {}

				@Override
				public void afterCompletion(int status) {
					if (status != Status.STATUS_COMMITTED) {
						return;
					}
					processingService.process(recordingId);
				}
			}
		);
	}

	private RecordingResponse toResponse(Recording recording) {
		return new RecordingResponse(
			recording.id,
			recording.title,
			recording.status,
			recording.summaryText,
			recording.errorMessage,
			recording.audioMimeType,
			recording.audioSizeBytes,
			recording.durationSeconds,
			recording.transcriptText != null && !recording.transcriptText.isBlank(),
			recording.optimizedText != null && !recording.optimizedText.isBlank(),
			recording.startedAt,
			recording.createdAt,
			recording.updatedAt
		);
	}

	private RecordingDetailResponse toDetailResponse(Recording recording) {
		return new RecordingDetailResponse(
			recording.id,
			recording.title,
			recording.status,
			recording.transcriptText,
			recording.optimizedText,
			recording.summaryText,
			recording.errorMessage,
			recording.audioMimeType,
			recording.audioSizeBytes,
			recording.durationSeconds,
			recording.startedAt,
			recording.createdAt,
			recording.updatedAt
		);
	}

	private Instant parseStartedAt(String value) {
		try {
			return Instant.parse(value);
		}
		catch (Exception ex) {
			throw new WebApplicationException("Invalid started_at timestamp", Response.Status.BAD_REQUEST);
		}
	}

	private Recording requireRecording(UUID tenantId, UUID workspaceId, UUID recordingId) {
		Workspace workspace = requireWorkspace(tenantId, workspaceId);
		Recording recording = entityManager.find(Recording.class, recordingId);
		if (recording == null || !recording.workspace.id.equals(workspace.id)) {
			throw new WebApplicationException("Recording not found", Response.Status.NOT_FOUND);
		}
		return recording;
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

	private byte[] readBytes(InputStream input) {
		try {
			return input.readAllBytes();
		}
		catch (IOException ex) {
			throw new WebApplicationException("Unable to read recording", Response.Status.BAD_REQUEST);
		}
	}

	private String sanitizeFilename(String title) {
		if (title == null || title.isBlank()) {
			return "recording";
		}
		return title.trim().replaceAll("[^a-zA-Z0-9-_]+", "-");
	}

	private String resolveExtension(String mimeType) {
		if (mimeType == null) {
			return "webm";
		}
		if (mimeType.contains("ogg")) {
			return "ogg";
		}
		if (mimeType.contains("webm")) {
			return "webm";
		}
		return "dat";
	}
}
