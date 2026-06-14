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

package be.celerex.polymr.session;

import be.celerex.polymr.model.Session;
import be.celerex.polymr.model.SessionEvent;
import be.celerex.polymr.model.SessionEventResource;
import be.celerex.polymr.model.SessionEventType;
import be.celerex.polymr.model.User;
import be.celerex.polymr.model.Workspace;
import be.celerex.polymr.storage.StoredBlob;
import be.celerex.polymr.storage.WorkspaceBlobStore;
import be.celerex.polymr.storage.AttachmentLinkService;
import be.celerex.polymr.storage.PublicWorkspaceBlobStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.util.Optional;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class SessionEventService {
	private static final String UI_BLOB_PREFIX = "ui://polymr-blob/";
	private static final org.jboss.logging.Logger LOGGER = org.jboss.logging.Logger.getLogger(SessionEventService.class);
	@Inject
	EntityManager entityManager;

	@Inject
	ObjectMapper objectMapper;

	@Inject
	WorkspaceBlobStore blobStore;

	@Inject
	AttachmentLinkService attachmentLinkService;

	@Transactional
	public SessionEvent createEvent(Session session, SessionEventType type, JsonNode payload) {
		Integer epochId = resolveCurrentEpochId(session);
		return createEvent(session, null, null, type, payload, epochId);
	}

	@Transactional
	public SessionEvent createEvent(Session session, User user, SessionEventType type, JsonNode payload) {
		Integer epochId = resolveCurrentEpochId(session);
		return createEvent(session, user, null, type, payload, epochId);
	}

	@Transactional
	public SessionEvent createEvent(
			Session session,
			User user,
			SessionEventType type,
			JsonNode payload,
			Double locationLat,
			Double locationLng) {
		Integer epochId = resolveCurrentEpochId(session);
		return createEvent(session, user, new LocationInfo(locationLat, locationLng), type, payload, epochId);
	}

	@Transactional
	public SessionEvent createEvent(Session session, SessionEventType type, JsonNode payload, Integer epochId) {
		return createEvent(session, null, null, type, payload, epochId);
	}

	@Transactional
	public SessionEvent createEvent(Session session, User user, SessionEventType type, JsonNode payload, Integer epochId) {
		return createEvent(session, user, null, type, payload, epochId);
	}

	@Transactional
	public SessionEvent createEvent(
			Session session,
			User user,
			LocationInfo location,
			SessionEventType type,
			JsonNode payload,
			Integer epochId) {
		try {
			PayloadRewrite rewrite = rewritePayload(session.workspace, payload);
			SessionEvent event = new SessionEvent();
			event.session = session;
			event.user = user;
			event.eventType = type;
			event.payloadJson = rewrite.payload;
			event.epochId = epochId == null || epochId < 1 ? 1 : epochId;
			event.locationLat = location == null ? null : location.lat();
			event.locationLng = location == null ? null : location.lng();
			entityManager.persist(event);
			for (ResourceReference resource : rewrite.resources) {
				SessionEventResource link = new SessionEventResource();
				link.sessionEvent = event;
				link.workspace = session.workspace;
				link.uri = resource.uri;
				link.mimeType = resource.mimeType;
				link.blobHash = resource.hash;
				entityManager.persist(link);
			}
			return event;
		}
		catch (RuntimeException exception) {
			UUID sessionId = session == null ? null : session.id;
			UUID workspaceId = session == null || session.workspace == null ? null : session.workspace.id;
			LOGGER.errorf(
				exception,
				"Failed to create session event session=%s workspace=%s type=%s",
				sessionId,
				workspaceId,
				type
			);
			throw exception;
		}
	}

	@Transactional
	public void applyResponseTelemetry(
			UUID eventId,
			String modelId,
			Long inputTokens,
			Long outputTokens,
			Long reasoningTokens,
			Long cachedInputTokens,
			java.math.BigDecimal priceSnapshot,
			String priceCurrency) {
		if (eventId == null) {
			return;
		}
		SessionEvent event = entityManager.find(SessionEvent.class, eventId);
		if (event == null) {
			return;
		}
		event.tokenizerModelId = modelId;
		event.inputTokens = toInteger(inputTokens);
		event.outputTokens = toInteger(outputTokens);
		event.reasoningTokens = toInteger(reasoningTokens);
		event.cachedInputTokens = toInteger(cachedInputTokens);
		event.priceSnapshot = priceSnapshot;
		event.priceCurrency = priceCurrency;
	}

	private Integer resolveCurrentEpochId(Session session) {
		if (session == null) {
			return 1;
		}
		Integer value = entityManager.createQuery("select max(e.epochId) from SessionEvent e where e.session.id = :sessionId", Integer.class)
			.setParameter("sessionId", session.id)
			.getSingleResult();
		return value == null || value < 1 ? 1 : value;
	}

	private Integer toInteger(Long value) {
		if (value == null) {
			return null;
		}
		long normalized = Math.max(0L, value);
		if (normalized > Integer.MAX_VALUE) {
			return Integer.MAX_VALUE;
		}
		return (int) normalized;
	}

	public JsonNode enrichPayload(SessionEvent event) {
		if (event == null || event.payloadJson == null) {
			return event == null ? objectMapper.createObjectNode() : event.payloadJson;
		}
		JsonNode payload = event.payloadJson.deepCopy();
		if (!(payload instanceof ObjectNode objectNode)) {
			return payload;
		}
		JsonNode attachments = objectNode.get("attachments");
		if (!(attachments instanceof ArrayNode arrayNode)) {
			return payload;
		}
		UUID workspaceId = event.session != null && event.session.workspace != null ? event.session.workspace.id : null;
		UUID tenantId = event.session != null && event.session.workspace != null && event.session.workspace.tenant != null
			? event.session.workspace.tenant.id
			: null;
		PublicWorkspaceBlobStore publicStore = blobStore instanceof PublicWorkspaceBlobStore store ? store : null;
		logClassLoaderState("session.enrichPayload.before");
		for (JsonNode entry : arrayNode) {
			if (!(entry instanceof ObjectNode entryNode)) {
				continue;
			}
			String hash = entryNode.path("blob_hash").asText(null);
			if (hash == null || hash.isBlank() || workspaceId == null || tenantId == null) {
				continue;
			}
			attachmentLinkService.resolveUserLink(tenantId, workspaceId, hash, publicStore)
				.ifPresent(link -> entryNode.put("user_url", link.uri().toString()));
			if (publicStore != null) {
				attachmentLinkService.resolvePublicLink(workspaceId, hash, publicStore)
					.ifPresent(link -> entryNode.put("public_url", link.uri().toString()));
			}
		}
		return objectNode;
	}

	@Transactional
	public SessionEvent upsertWorkerProgress(Session session, ObjectNode payload) {
		if (session == null) {
			return null;
		}
		String toolCallId = null;
		if (payload != null && payload.has("tool_call_id")) {
			toolCallId = payload.path("tool_call_id").asText(null);
		}
		SessionEvent existing = findWorkerProgressEvent(session, toolCallId);
		if (existing == null) {
			return createEvent(session, SessionEventType.SYSTEM, payload);
		}
		ObjectNode merged = mergeSubassistantProgress(existing.payloadJson, payload);
		PayloadRewrite rewrite = rewritePayload(session.workspace, merged);
		existing.payloadJson = rewrite.payload;
		return existing;
	}

	private ObjectNode mergeSubassistantProgress(JsonNode existingPayload, ObjectNode nextPayload) {
		if (nextPayload == null) {
			return existingPayload instanceof ObjectNode node ? node.deepCopy() : objectMapper.createObjectNode();
		}
		ObjectNode merged = existingPayload instanceof ObjectNode node ? node.deepCopy() : objectMapper.createObjectNode();
		nextPayload.fields()
			.forEachRemaining(entry -> merged.set(entry.getKey(), entry.getValue()));
		if (!nextPayload.has("children") && nextPayload.has("child")) {
			ArrayNode children = merged.get("children") instanceof ArrayNode array ? array : merged.putArray("children");
			ObjectNode child = nextPayload.get("child") instanceof ObjectNode obj ? obj : null;
			if (child != null && child.hasNonNull("session_id")) {
				String sessionId = child.get("session_id").asText();
				boolean updated = false;
				for (JsonNode entry : children) {
					if (entry != null && entry.isObject()) {
						JsonNode entrySession = entry.get("session_id");
						if (entrySession != null && sessionId.equals(entrySession.asText())) {
							ObjectNode entryObj = (ObjectNode) entry;
							child.fields()
								.forEachRemaining(field -> entryObj.set(field.getKey(), field.getValue()));
							updated = true;
							break;
						}
					}
				}
				if (!updated) {
					children.add(child);
				}
			}
		}
		return merged;
	}

	private SessionEvent findWorkerProgressEvent(Session session, String toolCallId) {
		if (session == null || session.id == null) {
			return null;
		}
		List<SessionEvent> events = entityManager.createQuery(
				"select e from SessionEvent e where e.session.id = :sessionId and "
					+ "e.eventType = :type order by e.createdAt desc",
				SessionEvent.class
			)
			.setParameter("sessionId", session.id)
			.setParameter("type", SessionEventType.SYSTEM)
			.setMaxResults(25)
			.getResultList();
		for (SessionEvent event : events) {
			if (event == null || event.payloadJson == null || !event.payloadJson.isObject()) {
				continue;
			}
			JsonNode kind = event.payloadJson.get("kind");
			if (kind != null && kind.isTextual() && "worker_progress".equals(kind.asText())) {
				if (toolCallId == null || toolCallId.isBlank()) {
					return event;
				}
				JsonNode storedToolCallId = event.payloadJson.get("tool_call_id");
				if (storedToolCallId != null
						&& storedToolCallId.isTextual()
						&& toolCallId.equals(storedToolCallId.asText())) {
					return event;
				}
			}
		}
		return null;
	}

	private void logClassLoaderState(String location) {
		Thread thread = Thread.currentThread();
		ClassLoader contextLoader = thread.getContextClassLoader();
		ClassLoader serviceLoader = SessionEventService.class.getClassLoader();
		ClassLoader quarkusConfigLoader = null;
		try {
			quarkusConfigLoader = io.quarkus.runtime.configuration.QuarkusConfigFactory.class.getClassLoader();
		}
		catch (RuntimeException ignored) {}
		LOGGER.debugf(
			"%s contextLoader=%s serviceLoader=%s quarkusConfigLoader=%s thread=%s",
			location,
			loaderId(contextLoader),
			loaderId(serviceLoader),
			loaderId(quarkusConfigLoader),
			thread.getName()
		);
	}

	private String loaderId(ClassLoader loader) {
		if (loader == null) {
			return "null";
		}
		return loader.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(loader));
	}

	private PayloadRewrite rewritePayload(Workspace workspace, JsonNode payload) {
		if (payload == null || payload.isNull()) {
			return new PayloadRewrite(objectMapper.createObjectNode(), List.of());
		}
		JsonNode copy = payload.deepCopy();
		if (!(copy instanceof ObjectNode objectNode)) {
			return new PayloadRewrite(copy, List.of());
		}
		List<ResourceCandidate> candidates = new ArrayList<>();
		collectResources(objectNode, "content", candidates);
		collectResources(objectNode, "contents", candidates);

		if (candidates.isEmpty()) {
			return new PayloadRewrite(objectNode, List.of());
		}

		Map<String, String> uriRewrite = new HashMap<>();
		List<ResourceReference> stored = new ArrayList<>();

		for (ResourceCandidate candidate : candidates) {
			if (candidate.isHtml) {
				continue;
			}
			StoredBlob blob = blobStore.store(workspace.id, candidate.bytes, candidate.mimeType);
			String newUri = UI_BLOB_PREFIX + blob.hash();
			candidate.node.put("uri", newUri);
			removeInlineData(candidate.node);
			uriRewrite.put(candidate.originalUri, newUri);
			stored.add(new ResourceReference(newUri, blob.hash(), blob.mimeType()));
		}

		for (ResourceCandidate candidate : candidates) {
			if (!candidate.isHtml) {
				continue;
			}
			byte[] rewritten = rewriteHtml(candidate.bytes, uriRewrite);
			StoredBlob blob = blobStore.store(workspace.id, rewritten, candidate.mimeType);
			String newUri = UI_BLOB_PREFIX + blob.hash();
			candidate.node.put("uri", newUri);
			removeInlineData(candidate.node);
			stored.add(new ResourceReference(newUri, blob.hash(), blob.mimeType()));
		}

		addMetaResourceReferences(objectNode, stored);

		return new PayloadRewrite(objectNode, stored);
	}

	private void addMetaResourceReferences(ObjectNode payload, List<ResourceReference> stored) {
		if (payload == null || stored == null) {
			return;
		}
		addMetaResourceReference(payload.get("review_uri"), stored);
		addMetaResourceReference(payload.get("diff_uri"), stored);
		addMetaResourceReference(payload.get("reviewUri"), stored);
		addMetaResourceReference(payload.get("diffUri"), stored);
	}

	private void addMetaResourceReference(JsonNode node, List<ResourceReference> stored) {
		if (node == null || !node.isTextual()) {
			return;
		}
		String uri = node.asText();
		if (uri == null || uri.isBlank() || !uri.startsWith(UI_BLOB_PREFIX)) {
			return;
		}
		String path = uri.substring(UI_BLOB_PREFIX.length());
		int slashIndex = path.indexOf('/');
		String hash = slashIndex >= 0 ? path.substring(0, slashIndex) : path;
		if (hash.isBlank()) {
			return;
		}
		stored.add(new ResourceReference(uri, hash, null));
	}

	private void collectResources(ObjectNode payload, String field, List<ResourceCandidate> candidates) {
		JsonNode node = payload.get(field);
		if (!(node instanceof ArrayNode arrayNode)) {
			return;
		}
		for (JsonNode entry : arrayNode) {
			if (!(entry instanceof ObjectNode entryNode)) {
				continue;
			}
			JsonNode uriNode = entryNode.get("uri");
			if (uriNode == null || !uriNode.isTextual()) {
				continue;
			}
			String uri = uriNode.asText();
			if (!uri.startsWith("ui://")) {
				continue;
			}
			byte[] bytes = extractBytes(entryNode);
			if (bytes == null) {
				continue;
			}
			String mimeType = readMimeType(entryNode);
			boolean isHtml = mimeType != null && mimeType.contains("html");
			candidates.add(new ResourceCandidate(uri, entryNode, mimeType, bytes, isHtml));
		}
	}

	private byte[] extractBytes(ObjectNode entryNode) {
		JsonNode textNode = entryNode.get("text");
		if (textNode != null && textNode.isTextual()) {
			return textNode.asText().getBytes(StandardCharsets.UTF_8);
		}
		JsonNode base64Node = entryNode.get("base64");
		if (base64Node != null && base64Node.isTextual()) {
			String encoded = base64Node.asText();
			try {
				return Base64.getDecoder().decode(encoded);
			}
			catch (IllegalArgumentException ex) {
				return encoded.getBytes(StandardCharsets.UTF_8);
			}
		}
		JsonNode dataNode = entryNode.get("data");
		if (dataNode != null && dataNode.isTextual()) {
			String data = dataNode.asText();
			int commaIndex = data.indexOf(',');
			if (data.startsWith("data:") && commaIndex > 0) {
				String encoded = data.substring(commaIndex + 1);
				try {
					return Base64.getDecoder().decode(encoded);
				}
				catch (IllegalArgumentException ex) {
					return encoded.getBytes(StandardCharsets.UTF_8);
				}
			}
			try {
				return Base64.getDecoder().decode(data);
			}
			catch (IllegalArgumentException ex) {
				return data.getBytes(StandardCharsets.UTF_8);
			}
		}
		JsonNode bytesNode = entryNode.get("bytes");
		if (bytesNode != null && bytesNode.isArray()) {
			byte[] bytes = new byte[bytesNode.size()];
			for (int i = 0; i < bytesNode.size(); i++) {
				bytes[i] = (byte) bytesNode.get(i).asInt();
			}
			return bytes;
		}
		return null;
	}

	private String readMimeType(ObjectNode entryNode) {
		JsonNode mimeNode = entryNode.get("mime_type");
		if (mimeNode == null) {
			mimeNode = entryNode.get("mimeType");
		}
		if (mimeNode != null && mimeNode.isTextual()) {
			return mimeNode.asText();
		}
		JsonNode typeNode = entryNode.get("type");
		if (typeNode != null && typeNode.isTextual() && typeNode.asText().contains("/")) {
			return typeNode.asText();
		}
		return "text/plain";
	}

	private void removeInlineData(ObjectNode entryNode) {
		entryNode.remove(List.of("text", "base64", "bytes", "data"));
	}

	private byte[] rewriteHtml(byte[] bytes, Map<String, String> uriRewrite) {
		if (uriRewrite.isEmpty()) {
			return bytes;
		}
		String html = new String(bytes, StandardCharsets.UTF_8);
		for (Map.Entry<String, String> entry : uriRewrite.entrySet()) {
			html = html.replace(entry.getKey(), entry.getValue());
		}
		return html.getBytes(StandardCharsets.UTF_8);
	}

	private record ResourceCandidate(
			String originalUri,
			ObjectNode node,
			String mimeType,
			byte[] bytes,
			boolean isHtml) {}

	private record ResourceReference(String uri, String hash, String mimeType) {}

	private record PayloadRewrite(JsonNode payload, List<ResourceReference> resources) {}

	private record LocationInfo(Double lat, Double lng) {}
}
