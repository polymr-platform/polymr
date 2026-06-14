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

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashSet;

@ApplicationScoped
public class WorkspaceSocketManager {
	private static final org.jboss.logging.Logger LOGGER = org.jboss.logging.Logger.getLogger(WorkspaceSocketManager.class);
	@Inject
	ObjectMapper mapper;

	private final Map<UUID, Set<WorkspaceSocketClient>> sessionsByWorkspace = new ConcurrentHashMap<>();
	private final Map<UUID, Set<WorkspaceSocketClient>> sessionsBySession = new ConcurrentHashMap<>();

	public void register(UUID workspaceId, UUID userId, jakarta.websocket.Session session) {
		WorkspaceSocketClient client = new WorkspaceSocketClient(session, userId, workspaceId);
		session.getUserProperties().putIfAbsent("workspaceId", workspaceId);
		session.getUserProperties().putIfAbsent("userId", userId);
		session.getUserProperties().putIfAbsent("connectionId", java.util.UUID.randomUUID().toString());
		session.getUserProperties().put("wsClient", client);
		session.getUserProperties().putIfAbsent("activeSessions", ConcurrentHashMap.newKeySet());
		sessionsByWorkspace.computeIfAbsent(workspaceId, key -> ConcurrentHashMap.newKeySet())
			.add(client);
		LOGGER.debugf(
			"WS register user=%s workspace=%s props=%s",
			userId,
			workspaceId,
			session.getUserProperties().keySet()
		);
	}

	public void unregister(UUID workspaceId, jakarta.websocket.Session session) {
		Object stored = session.getUserProperties().get("wsClient");
		if (stored instanceof WorkspaceSocketClient client) {
			removeClient(workspaceId, client);
			removeSessionMemberships(session, client);
		}
	}

	public void activateSession(UUID sessionId, jakarta.websocket.Session session) {
		Object stored = session.getUserProperties().get("wsClient");
		if (!(stored instanceof WorkspaceSocketClient client)) {
			return;
		}
		sessionsBySession.computeIfAbsent(sessionId, key -> ConcurrentHashMap.newKeySet())
			.add(client);
		Object active = session.getUserProperties().get("activeSessions");
		if (active instanceof Set<?> set) {
			@SuppressWarnings("unchecked")
			Set<UUID> ids = (Set<UUID>) set;
			ids.add(sessionId);
		}
	}

	public void deactivateSession(UUID sessionId, jakarta.websocket.Session session) {
		Object stored = session.getUserProperties().get("wsClient");
		if (!(stored instanceof WorkspaceSocketClient client)) {
			return;
		}
		removeSessionClient(sessionId, client);
		Object active = session.getUserProperties().get("activeSessions");
		if (active instanceof Set<?> set) {
			@SuppressWarnings("unchecked")
			Set<UUID> ids = (Set<UUID>) set;
			ids.remove(sessionId);
		}
	}

	public void broadcast(UUID workspaceId, WorkspaceSocketEvent event) {
		Set<WorkspaceSocketClient> sessions = sessionsByWorkspace.get(workspaceId);
		if (sessions == null || sessions.isEmpty()) {
			return;
		}
		try {
			String payload = mapper.writeValueAsString(event);
			sessions.removeIf(client -> !client.session().isOpen());
			for (WorkspaceSocketClient client : sessions) {
				client.session().getAsyncRemote().sendText(payload);
			}
		}
		catch (Exception ex) {
			LOGGER.warnf(ex, "Failed to broadcast workspace event");
		}
	}

	public void broadcastToUsers(UUID workspaceId, Set<UUID> userIds, WorkspaceSocketEvent event) {
		Set<WorkspaceSocketClient> sessions = sessionsByWorkspace.get(workspaceId);
		if (sessions == null || sessions.isEmpty()) {
			return;
		}
		try {
			String payload = mapper.writeValueAsString(event);
			sessions.removeIf(client -> !client.session().isOpen());
			for (WorkspaceSocketClient client : sessions) {
				if (userIds.contains(client.userId())) {
					client.session().getAsyncRemote().sendText(payload);
				}
			}
		}
		catch (Exception ex) {
			LOGGER.warnf(ex, "Failed to broadcast workspace event to users");
		}
	}

	public void broadcastToSession(UUID sessionId, WorkspaceSocketEvent event) {
		Set<WorkspaceSocketClient> sessions = sessionsBySession.get(sessionId);
		if (sessions == null || sessions.isEmpty()) {
			return;
		}
		try {
			String payload = mapper.writeValueAsString(event);
			sessions.removeIf(client -> !client.session().isOpen());
			for (WorkspaceSocketClient client : sessions) {
				client.session().getAsyncRemote().sendText(payload);
			}
		}
		catch (Exception ex) {
			LOGGER.warnf(ex, "Failed to broadcast session event");
		}
	}

	public void broadcastToConnection(
			UUID workspaceId,
			UUID userId,
			String connectionId,
			WorkspaceSocketEvent event) {
		if (workspaceId == null || userId == null || connectionId == null || connectionId.isBlank()) {
			return;
		}
		Set<WorkspaceSocketClient> sessions = sessionsByWorkspace.get(workspaceId);
		if (sessions == null || sessions.isEmpty()) {
			return;
		}
		try {
			String payload = mapper.writeValueAsString(event);
			sessions.removeIf(client -> !client.session().isOpen());
			for (WorkspaceSocketClient client : sessions) {
				if (!userId.equals(client.userId())) {
					continue;
				}
				jakarta.websocket.Session session = client.session();
				Object stored = session.getUserProperties().get("connectionId");
				if (connectionId.equals(stored)) {
					session.getAsyncRemote().sendText(payload);
				}
			}
		}
		catch (Exception ex) {
			LOGGER.warnf(ex, "Failed to broadcast connection event");
		}
	}

	public void broadcastTrace(UUID workspaceId, UUID sessionId, WorkspaceSocketEvent event) {
		Set<WorkspaceSocketClient> sessions = sessionsByWorkspace.get(workspaceId);
		if (sessions == null || sessions.isEmpty()) {
			return;
		}
		try {
			String payload = mapper.writeValueAsString(event);
			sessions.removeIf(client -> !client.session().isOpen());
			for (WorkspaceSocketClient client : sessions) {
				if (isTracing(client.session(), sessionId)) {
					client.session().getAsyncRemote().sendText(payload);
				}
			}
		}
		catch (Exception ex) {
			LOGGER.warnf(ex, "Failed to broadcast trace event");
		}
	}

	public Set<String> activeConnectionIds() {
		Set<String> connectionIds = new HashSet<>();
		for (Set<WorkspaceSocketClient> clients : sessionsByWorkspace.values()) {
			for (WorkspaceSocketClient client : clients) {
				jakarta.websocket.Session session = client.session();
				if (session == null || !session.isOpen()) {
					continue;
				}
				Object connection = session.getUserProperties().get("connectionId");
				if (connection instanceof String id && !id.isBlank()) {
					connectionIds.add(id);
				}
			}
		}
		return connectionIds;
	}

	private boolean isTracing(jakarta.websocket.Session session, UUID sessionId) {
		if (session == null || sessionId == null) {
			return false;
		}
		Object stored = session.getUserProperties().get("traceSessions");
		if (!(stored instanceof Set<?> set)) {
			return false;
		}
		@SuppressWarnings("unchecked")
		Set<UUID> ids = (Set<UUID>) set;
		return ids.contains(sessionId);
	}

	private void removeClient(UUID workspaceId, WorkspaceSocketClient client) {
		Set<WorkspaceSocketClient> sessions = sessionsByWorkspace.get(workspaceId);
		if (sessions != null) {
			sessions.remove(client);
			if (sessions.isEmpty()) {
				sessionsByWorkspace.remove(workspaceId);
			}
		}
	}

	private void removeSessionMemberships(jakarta.websocket.Session session, WorkspaceSocketClient client) {
		Object active = session.getUserProperties().get("activeSessions");
		if (!(active instanceof Set<?> set)) {
			return;
		}
		@SuppressWarnings("unchecked")
		Set<UUID> sessionIds = (Set<UUID>) set;
		for (UUID sessionId : sessionIds) {
			removeSessionClient(sessionId, client);
		}
	}

	private void removeSessionClient(UUID sessionId, WorkspaceSocketClient client) {
		Set<WorkspaceSocketClient> sessions = sessionsBySession.get(sessionId);
		if (sessions != null) {
			sessions.remove(client);
			if (sessions.isEmpty()) {
				sessionsBySession.remove(sessionId);
			}
		}
	}

	public record WorkspaceSocketClient(jakarta.websocket.Session session, UUID userId, UUID workspaceId) {}
}
