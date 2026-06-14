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

package be.celerex.polymr.ws;

import be.celerex.polymr.auth.TokenService;
import be.celerex.polymr.model.TenantMembership;
import be.celerex.polymr.session.SessionChatService;
import be.celerex.polymr.session.SessionParticipantService;
import be.celerex.polymr.tenant.TenantAccessService;
import be.celerex.polymr.automation.VoiceTranscriptionService;
import be.celerex.polymr.model.User;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.websocket.CloseReason;
import jakarta.websocket.Session;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import java.util.Map;
import org.jboss.logging.Logger;

@ApplicationScoped
public class WorkspaceSocketHandler {
	private static final Logger LOGGER = Logger.getLogger(WorkspaceSocketHandler.class);
	@Inject
	TokenService tokenService;

	@Inject
	TenantAccessService tenantAccessService;

	@Inject
	EntityManager entityManager;

	@Inject
	SessionParticipantService participantService;

	@Inject
	SessionChatService chatService;

	@Inject
	WorkspaceSocketManager socketManager;

	@Inject
	VoiceTranscriptionService transcriptionService;

	@Inject
	be.celerex.polymr.session.SessionSummaryService summaryService;

	@ActivateRequestContext
	@Transactional
	public void handleOpen(Session session, UUID tenantId, UUID workspaceId, String token) {
		Optional<be.celerex.polymr.model.Token> verified = tokenService.verifyAccessToken(token);
		if (verified.isEmpty()) {
			throw new IllegalStateException("Invalid token");
		}
		UUID userId = verified.get().user.id;
		TenantMembership membership = tenantAccessService.requireMembership(tenantId, userId);
		if (!membership.tenant.id.equals(tenantId)) {
			throw new IllegalStateException("Invalid tenant");
		}
		storeContext(session, workspaceId, userId);
		socketManager.register(workspaceId, userId, session);
		session.getUserProperties()
			.putIfAbsent("traceSessions", java.util.concurrent.ConcurrentHashMap.newKeySet());
		java.util.List<be.celerex.polymr.model.Session> sessions = summaryService.listVisibleActiveSessions(workspaceId, userId);
		java.util.List<be.celerex.polymr.session.dto.SessionSummary> summaries = summaryService.buildSummaries(sessions, userId);
		socketManager.broadcastToUsers(
			workspaceId,
			java.util.Set.of(userId),
			new WorkspaceSocketEvent("session.list", workspaceId, null, summaries)
		);
		LOGGER.debugf("WS open user=%s workspace=%s", userId, workspaceId);
	}

	@ActivateRequestContext
	@Transactional
	public void handleMessage(Session session, WorkspaceSocketMessage payload) {
		UUID workspaceId = contextValue(session, "workspaceId");
		UUID userId = contextValue(session, "userId");
		if (workspaceId == null || userId == null) {
			Object stored = session.getUserProperties().get("wsClient");
			if (stored instanceof WorkspaceSocketManager.WorkspaceSocketClient client) {
				workspaceId = client.workspaceId();
				userId = client.userId();
			}
			if (workspaceId == null || userId == null) {
				LOGGER.warn("WS missing workspace/user context; closing socket");
				close(session, CloseReason.CloseCodes.CANNOT_ACCEPT, "Missing context");
				return;
			}
		}
		if (payload.type().equals("ws.ping")) {
			participantService.touchConnection(workspaceId, connectionId(session));
			return;
		}
		be.celerex.polymr.model.Session target = entityManager.find(be.celerex.polymr.model.Session.class, payload.session_id());
		if (target == null || !target.workspace.id.equals(workspaceId)) {
			LOGGER.debugf(
				"WS session not found or workspace mismatch. session=%s workspace=%s",
				payload.session_id(),
				workspaceId
			);
			return;
		}
		participantService.touchConnection(workspaceId, connectionId(session));
		if (payload.type().equals("session.active")) {
			participantService.join(target, userId);
			socketManager.activateSession(target.id, session);
			participantService.setActive(target, userId, connectionId(session), true);
		}
		if (payload.type().equals("session.inactive")) {
			participantService.setActive(target, userId, connectionId(session), false);
			socketManager.deactivateSession(target.id, session);
			traceSessions(session).remove(target.id);
		}
		if (payload.type().equals("session.trace.start")) {
			traceSessions(session).add(target.id);
		}
		if (payload.type().equals("session.trace.stop")) {
			traceSessions(session).remove(target.id);
		}
		if (payload.type().equals("voice.recording")) {
			float[] samples = readAudioSamples(payload.payload());
			int sampleRate = readSampleRate(payload.payload());
			if (samples != null && samples.length > 0) {
				LOGGER.debugf("WS voice.recording received session=%s samples=%s", target.id, samples.length);
				transcriptionService.queueTranscription(target.id, userId, connectionId(session), samples, sampleRate);
			}
		}
		if (payload.type().equals("session.typing")) {
			User user = entityManager.find(User.class, userId);
			String displayName = user == null
				? "User"
				: user.nickname != null && !user.nickname.isBlank() ? user.nickname : user.email;
			boolean typing = payload.payload() == null || payload.payload().path("typing").asBoolean(true);
			Map<String, Object> typingPayload = Map.of(
				"user_id",
				userId,
				"display_name",
				displayName,
				"typed_at",
				Instant.now().toString(),
				"typing",
				typing
			);
			participantService.broadcastSessionState(target, "session.typing", typingPayload);
		}
		if (payload.type().equals("chat.send")) {
			if (target.locked) {
				LOGGER.warnf("WS chat.send rejected (session locked). session=%s user=%s", target.id, userId);
				Map<String, Object> rejectPayload = Map.of("message", "Session is busy. Message could not be processed.", "reason", "session_locked");
				socketManager.broadcastToUsers(
					workspaceId,
					Set.of(userId),
					new WorkspaceSocketEvent("chat.rejected", workspaceId, target.id, rejectPayload)
				);
				return;
			}
			LOGGER.infof("WS chat.send dispatch session=%s user=%s", target.id, userId);
			chatService.handleChatSend(target, userId, payload.payload());
			LOGGER.infof("WS chat.send complete session=%s", target.id);
		}
		if (payload.type().equals("session.abort")) {
			chatService.handleAbort(target, userId);
		}
	}

	private UUID contextValue(Session session, String key) {
		Object value = session.getUserProperties().get(key);
		return value instanceof UUID ? (UUID) value : null;
	}

	private String connectionId(Session session) {
		Object connection = session.getUserProperties().get("connectionId");
		return connection == null ? "" : connection.toString();
	}

	private void storeContext(Session session, UUID workspaceId, UUID userId) {
		session.getUserProperties().put("workspaceId", workspaceId);
		session.getUserProperties().put("userId", userId);
		if (session.getUserProperties().get("connectionId") == null) {
			session.getUserProperties().put("connectionId", UUID.randomUUID().toString());
		}
	}

	private float[] readAudioSamples(com.fasterxml.jackson.databind.JsonNode payload) {
		if (payload == null) {
			return null;
		}
		var node = payload.get("data");
		if (node == null || !node.isArray()) {
			return null;
		}
		float[] samples = new float[node.size()];
		for (int i = 0; i < node.size(); i++) {
			samples[i] = (float) node.get(i).asDouble();
		}
		return samples;
	}

	private int readSampleRate(com.fasterxml.jackson.databind.JsonNode payload) {
		if (payload == null) {
			return 16000;
		}
		return payload.path("sample_rate").asInt(16000);
	}

	private java.util.Set<UUID> traceSessions(Session session) {
		Object stored = session.getUserProperties().get("traceSessions");
		if (stored instanceof java.util.Set<?> set) {
			@SuppressWarnings("unchecked")
			java.util.Set<UUID> ids = (java.util.Set<UUID>) set;
			return ids;
		}
		java.util.Set<UUID> ids = java.util.concurrent.ConcurrentHashMap.newKeySet();
		session.getUserProperties().put("traceSessions", ids);
		return ids;
	}

	private void close(Session session, CloseReason.CloseCode code, String reason) {
		try {
			session.close(new CloseReason(code, reason));
		}
		catch (Exception ex) {
			LOGGER.debugf(ex, "Failed to close websocket session");
		}
	}
}
