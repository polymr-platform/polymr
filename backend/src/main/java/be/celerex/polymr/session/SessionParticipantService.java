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
import be.celerex.polymr.model.SessionParticipant;
import be.celerex.polymr.model.SessionParticipantConnection;
import be.celerex.polymr.model.SessionParticipantRole;
import be.celerex.polymr.model.SessionVisibility;
import be.celerex.polymr.session.dto.SessionParticipantResponse;
import be.celerex.polymr.ws.WorkspaceSocketEvent;
import be.celerex.polymr.ws.WorkspaceSocketManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

@ApplicationScoped
public class SessionParticipantService {
	private static final Logger LOGGER = Logger.getLogger(SessionParticipantService.class);
	@Inject
	EntityManager entityManager;

	@Inject
	WorkspaceSocketManager socketManager;

	@Inject
	be.celerex.polymr.infra.ServerIdentity serverIdentity;

	@Inject
	PresenceSettings presenceSettings;

	@Inject
	be.celerex.polymr.ws.ClusterEventBus clusterEventBus;

	@Transactional
	public List<SessionParticipantResponse> listParticipants(Session session) {
		return listParticipantsBySessionIds(List.of(session.id)).getOrDefault(session.id, List.of());
	}

	@Transactional
	public Map<UUID, List<SessionParticipantResponse>> listParticipantsBySessionIds(List<UUID> sessionIds) {
		if (sessionIds == null || sessionIds.isEmpty()) {
			return Map.of();
		}
		Map<UUID, java.util.Set<UUID>> activeUsersBySession = activeUsersBySession(sessionIds);
		Map<UUID, List<SessionParticipantResponse>> participantsBySession = new LinkedHashMap<>();
		for (UUID sessionId : sessionIds) {
			participantsBySession.put(sessionId, new java.util.ArrayList<>());
		}
		List<SessionParticipant> participants = entityManager.createQuery(
				"select p from SessionParticipant p join fetch p.user where p.session.id in :sessionIds",
				SessionParticipant.class
			)
			.setParameter("sessionIds", sessionIds)
			.getResultList();
		for (SessionParticipant participant : participants) {
			UUID sessionId = participant.session.id;
			java.util.Set<UUID> activeUsers = activeUsersBySession.getOrDefault(sessionId, Set.of());
			participantsBySession.computeIfAbsent(sessionId, key -> new java.util.ArrayList<>())
				.add(toParticipantResponse(participant, activeUsers.contains(participant.user.id)));
		}
		return participantsBySession;
	}

	@Transactional
	public List<SessionParticipantResponse> join(Session session, UUID userId) {
		if (session.visibility == SessionVisibility.PRIVATE && !session.createdBy.id.equals(userId)) {
			throw new WebApplicationException("Session is private", Response.Status.FORBIDDEN);
		}
		List<SessionParticipant> existing = entityManager.createQuery(
				"select p from SessionParticipant p where p.session.id = :sessionId and p.user.id = :userId",
				SessionParticipant.class
			)
			.setParameter("sessionId", session.id)
			.setParameter("userId", userId)
			.getResultList();
		if (existing.isEmpty()) {
			SessionParticipant participant = new SessionParticipant();
			participant.session = session;
			participant.user = entityManager.getReference(be.celerex.polymr.model.User.class, userId);
			participant.role = SessionParticipantRole.PARTICIPANT;
			entityManager.persist(participant);
		}
		return listParticipants(session);
	}

	@Transactional
	public List<SessionParticipantResponse> addParticipant(Session session, UUID userId) {
		if (session == null || userId == null) {
			return List.of();
		}
		List<SessionParticipant> existing = entityManager.createQuery(
				"select p from SessionParticipant p where p.session.id = :sessionId and p.user.id = :userId",
				SessionParticipant.class
			)
			.setParameter("sessionId", session.id)
			.setParameter("userId", userId)
			.getResultList();
		if (existing.isEmpty()) {
			SessionParticipant participant = new SessionParticipant();
			participant.session = session;
			participant.user = entityManager.getReference(be.celerex.polymr.model.User.class, userId);
			participant.role = SessionParticipantRole.PARTICIPANT;
			entityManager.persist(participant);
		}
		List<SessionParticipantResponse> responses = listParticipants(session);
		broadcastParticipants(session, responses);
		return responses;
	}

	public boolean isParticipant(Session session, UUID userId) {
		if (session == null || userId == null) {
			return false;
		}
		Long count = entityManager.createQuery(
				"select count(p.id) from SessionParticipant p where p.session.id = "
					+ ":sessionId and p.user.id = :userId",
				Long.class
			)
			.setParameter("sessionId", session.id)
			.setParameter("userId", userId)
			.getSingleResult();
		return count != null && count > 0;
	}

	@Transactional
	public void setActive(Session session, UUID userId, String connectionId, boolean active) {
		if (connectionId == null || connectionId.isBlank()) {
			return;
		}
		Instant now = Instant.now();
		int updated = entityManager.createQuery(
				"update SessionParticipantConnection c "
					+ "set c.active = :active, c.lastActiveAt = :now, c.lastSeenAt = :now, c.serverId = :serverId "
					+ "where c.session.id = :sessionId and c.user.id = :userId and c.connectionId = :connectionId"
			)
			.setParameter("active", active)
			.setParameter("now", now)
			.setParameter("serverId", serverIdentity.id())
			.setParameter("sessionId", session.id)
			.setParameter("userId", userId)
			.setParameter("connectionId", connectionId)
			.executeUpdate();
		if (updated == 0) {
			SessionParticipantConnection connection = new SessionParticipantConnection();
			connection.session = session;
			connection.user = entityManager.getReference(be.celerex.polymr.model.User.class, userId);
			connection.connectionId = connectionId;
			connection.active = active;
			connection.lastActiveAt = now;
			connection.lastSeenAt = now;
			connection.serverId = serverIdentity.id();
			try {
				entityManager.persist(connection);
				entityManager.flush();
			}
			catch (RuntimeException ex) {
				LOGGER.warnf(
					ex,
					"Duplicate session.active detected for session=%s user=%s connection=%s; updating",
					session.id,
					userId,
					connectionId
				);
			}
		}
		List<SessionParticipantResponse> responses = listParticipants(session);
		broadcastParticipants(session, responses);
	}

	@Transactional
	public void deactivateConnection(UUID workspaceId, String connectionId) {
		if (connectionId == null || connectionId.isBlank()) {
			return;
		}
		List<UUID> sessionIds = entityManager.createQuery(
				"select c.session.id from SessionParticipantConnection c "
					+ "where c.session.workspace.id = :workspaceId and c.connectionId = :connectionId",
				UUID.class
			)
			.setParameter("workspaceId", workspaceId)
			.setParameter("connectionId", connectionId)
			.getResultList();
		if (sessionIds.isEmpty()) {
			return;
		}
		entityManager.createQuery(
				"update SessionParticipantConnection c set c.active = false, c.lastActiveAt = :now, "
					+ "c.lastSeenAt = :now "
					+ "where c.connectionId = :connectionId"
			)
			.setParameter("connectionId", connectionId)
			.setParameter("now", java.time.Instant.now())
			.executeUpdate();
		List<Session> sessions = entityManager.createQuery("select s from Session s where s.id in :sessionIds", Session.class)
			.setParameter("sessionIds", sessionIds)
			.getResultList();
		for (Session session : sessions) {
			List<SessionParticipantResponse> responses = listParticipants(session);
			broadcastParticipants(session, responses);
		}
	}

	private void broadcastParticipants(Session session, List<SessionParticipantResponse> participants) {
		WorkspaceSocketEvent event = new WorkspaceSocketEvent("session.participants", session.workspace.id, session.id, participants);
		if (session.visibility == SessionVisibility.WORKSPACE) {
			socketManager.broadcast(session.workspace.id, event);
			clusterEventBus.publish(event, null);
			return;
		}
		java.util.Set<UUID> participantIds = participantUserIds(session);
		if (!participantIds.isEmpty()) {
			socketManager.broadcastToUsers(session.workspace.id, participantIds, event);
			clusterEventBus.publish(event, participantIds);
		}
	}

	public void broadcastToSession(Session session, String type, Object payload) {
		WorkspaceSocketEvent event = new WorkspaceSocketEvent(type, session.workspace.id, session.id, payload);
		socketManager.broadcastToSession(session.id, event);
	}

	public void broadcastSessionState(Session session, String type, Object payload) {
		if (session == null) {
			return;
		}
		WorkspaceSocketEvent event = new WorkspaceSocketEvent(type, session.workspace.id, session.id, payload);
		if (session.visibility == SessionVisibility.WORKSPACE) {
			socketManager.broadcast(session.workspace.id, event);
			clusterEventBus.publish(event, null);
			return;
		}
		Set<UUID> participantIds = participantUserIds(session);
		if (!participantIds.isEmpty()) {
			socketManager.broadcastToUsers(session.workspace.id, participantIds, event);
			clusterEventBus.publish(event, participantIds);
		}
	}

	public void broadcastSessionActivity(Session session) {
		if (session == null) {
			return;
		}
		broadcastSessionState(session, "session.activity", session.currentActivity);
	}

	public void sendParticipantsToUser(Session session, UUID userId) {
		List<SessionParticipantResponse> responses = listParticipants(session);
		WorkspaceSocketEvent event = new WorkspaceSocketEvent("session.participants", session.workspace.id, session.id, responses);
		socketManager.broadcastToUsers(session.workspace.id, java.util.Set.of(userId), event);
	}

	private java.util.Set<UUID> participantUserIds(Session session) {
		return entityManager.createQuery("select distinct p.user.id from SessionParticipant p where p.session.id = :sessionId", UUID.class)
			.setParameter("sessionId", session.id)
			.getResultStream()
			.collect(java.util.stream.Collectors.toSet());
	}

	private SessionParticipantResponse toParticipantResponse(SessionParticipant participant, boolean active) {
		return new SessionParticipantResponse(
			participant.user.id,
			displayName(participant.user),
			participant.role,
			active,
			avatarUrl(participant.user)
		);
	}

	private String avatarUrl(be.celerex.polymr.model.User user) {
		if (user == null || user.avatarBytes == null || user.avatarBytes.length == 0) {
			return null;
		}
		String suffix = user.avatarUpdatedAt == null ? "" : "?v=" + user.avatarUpdatedAt.toEpochMilli();
		return "/api/users/" + user.id + "/avatar" + suffix;
	}

	@Transactional
	public java.util.Set<UUID> activeUsers(UUID sessionId) {
		return activeUsersBySession(List.of(sessionId)).getOrDefault(sessionId, Set.of());
	}

	@Transactional
	public Map<UUID, java.util.Set<UUID>> activeUsersBySession(List<UUID> sessionIds) {
		if (sessionIds == null || sessionIds.isEmpty()) {
			return Map.of();
		}
		java.time.Instant cutoff = presenceSettings.cutoff();
		Map<UUID, java.util.Set<UUID>> activeUsersBySession = new LinkedHashMap<>();
		for (UUID sessionId : sessionIds) {
			activeUsersBySession.put(sessionId, new java.util.LinkedHashSet<>());
		}
		List<Object[]> rows = entityManager.createQuery(
				"select c.session.id, c.user.id from SessionParticipantConnection c "
					+ "where c.session.id in :sessionIds and c.active = true and c.lastSeenAt >= :cutoff",
				Object[].class
			)
			.setParameter("sessionIds", sessionIds)
			.setParameter("cutoff", cutoff)
			.getResultList();
		for (Object[] row : rows) {
			UUID sessionId = (UUID) row[0];
			UUID userId = (UUID) row[1];
			activeUsersBySession.computeIfAbsent(sessionId, key -> new java.util.LinkedHashSet<>())
				.add(userId);
		}
		return activeUsersBySession;
	}

	@Transactional
	public void touchConnection(UUID workspaceId, String connectionId) {
		if (connectionId == null || connectionId.isBlank()) {
			return;
		}
		entityManager.createQuery(
				"update SessionParticipantConnection c set c.lastSeenAt = :now "
					+ "where c.connectionId = :connectionId"
			)
			.setParameter("connectionId", connectionId)
			.setParameter("now", java.time.Instant.now())
			.executeUpdate();
	}

	private String displayName(be.celerex.polymr.model.User user) {
		if (user.nickname != null && !user.nickname.isBlank()) {
			return user.nickname;
		}
		return user.email;
	}
}
