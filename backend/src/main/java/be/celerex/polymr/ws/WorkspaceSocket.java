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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.websocket.CloseReason;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnOpen;
import jakarta.websocket.OnMessage;
import jakarta.websocket.Session;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import io.vertx.core.Vertx;
import java.util.List;
import java.util.UUID;

@ServerEndpoint("/api/tenants/{tenantId}/workspaces/{workspaceId}/ws")
@ApplicationScoped
public class WorkspaceSocket {
	private static final org.jboss.logging.Logger LOGGER = org.jboss.logging.Logger.getLogger(WorkspaceSocket.class);
	private static final ClassLoader APPLICATION_CLASS_LOADER = WorkspaceSocket.class.getClassLoader();
	@Inject
	WorkspaceSocketManager socketManager;

	@Inject
	be.celerex.polymr.session.SessionParticipantService participantService;

	@Inject
	com.fasterxml.jackson.databind.ObjectMapper mapper;

	@Inject
	Vertx vertx;

	@Inject
	WorkspaceSocketHandler socketHandler;

	@OnOpen
	public void onOpen(
			Session session,
			@PathParam("tenantId") String tenantIdRaw,
			@PathParam("workspaceId") String workspaceIdRaw) {
		UUID tenantId;
		UUID workspaceId;
		try {
			tenantId = UUID.fromString(tenantIdRaw);
			workspaceId = UUID.fromString(workspaceIdRaw);
		}
		catch (IllegalArgumentException ex) {
			close(session, CloseReason.CloseCodes.CANNOT_ACCEPT, "Invalid workspace or tenant");
			return;
		}
		String token = firstQueryParam(session, "token");
		if (token == null) {
			close(session, CloseReason.CloseCodes.CANNOT_ACCEPT, "Missing token");
			return;
		}
		io.vertx.core.Context presenceContext = vertx.getOrCreateContext();
		session.getUserProperties().put("presenceContext", presenceContext);
		vertx.executeBlocking(
			promise -> {
				withApplicationClassLoader(() -> {
					socketHandler.handleOpen(session, tenantId, workspaceId, token);
					promise.complete();
				});
			},
			result -> {
				if (result.failed()) {
					LOGGER.errorf(result.cause(), "WS open failed for tenant=%s workspace=%s", tenantId, workspaceId);
					close(session, CloseReason.CloseCodes.CANNOT_ACCEPT, "Unauthorized");
					return;
				}
				drainPendingMessages(session);
			}
		);
	}

	@OnMessage
	public void onMessage(String message, Session session) {
		WorkspaceSocketMessage payload = parse(message);
		if (payload == null || payload.session_id() == null || payload.type() == null) {
			LOGGER.debugf("WS message ignored: %s", message);
			return;
		}
		LOGGER.debugf("WS message type=%s session=%s", payload.type(), payload.session_id());
		if (!hasContext(session)) {
			queuePendingMessage(session, message);
			return;
		}
		vertx.executeBlocking(
			promise -> {
				withApplicationClassLoader(() -> {
					socketHandler.handleMessage(session, payload);
					promise.complete();
				});
			},
			result -> {
				if (result.failed()) {
					LOGGER.errorf(result.cause(), "WS onMessage failed: %s", message);
				}
			}
		);
	}

	@OnClose
	public void onClose(Session session) {
		deactivateParticipants(session);
		unregister(session);
	}

	@OnError
	public void onError(Session session, Throwable throwable) {
		deactivateParticipants(session);
		unregister(session);
	}

	private void unregister(Session session) {
		Object workspace = session.getUserProperties().get("workspaceId");
		if (workspace instanceof UUID workspaceId) {
			socketManager.unregister(workspaceId, session);
		}
	}

	private void deactivateParticipants(Session session) {
		Object workspace = session.getUserProperties().get("workspaceId");
		if (workspace instanceof UUID workspaceId) {
			String connection = connectionId(session);
			if (connection == null || connection.isBlank()) {
				return;
			}
			vertx.executeBlocking(
				promise -> {
					withApplicationClassLoader(() -> {
						participantService.deactivateConnection(workspaceId, connection);
						promise.complete();
					});
				},
				result -> {
					if (result.failed()) {
						LOGGER.debugf(result.cause(), "WS deactivate failed workspace=%s", workspaceId);
					}
				}
			);
		}
	}

	private String connectionId(Session session) {
		Object connection = session.getUserProperties().get("connectionId");
		return connection == null ? "" : connection.toString();
	}

	private WorkspaceSocketMessage parse(String message) {
		if (message == null || message.isBlank()) {
			return null;
		}
		try {
			return mapper.readValue(message, WorkspaceSocketMessage.class);
		}
		catch (Exception ignored) {
			return null;
		}
	}

	private String firstQueryParam(Session session, String key) {
		List<String> values = session.getRequestParameterMap().get(key);
		return values == null || values.isEmpty() ? null : values.get(0);
	}

	private void close(Session session, CloseReason.CloseCode code, String reason) {
		try {
			session.close(new CloseReason(code, reason));
		}
		catch (Exception ignored) {}
	}

	private void withApplicationClassLoader(Runnable action) {
		Thread thread = Thread.currentThread();
		ClassLoader previous = thread.getContextClassLoader();
		thread.setContextClassLoader(APPLICATION_CLASS_LOADER);
		try {
			action.run();
		}
		finally {
			thread.setContextClassLoader(previous);
		}
	}

	private void logClassLoaderState(String location) {
		Thread thread = Thread.currentThread();
		ClassLoader contextLoader = thread.getContextClassLoader();
		ClassLoader applicationLoader = WorkspaceSocket.class.getClassLoader();
		ClassLoader quarkusConfigLoader = null;
		try {
			quarkusConfigLoader = io.quarkus.runtime.configuration.QuarkusConfigFactory.class.getClassLoader();
		}
		catch (RuntimeException ignored) {}
		LOGGER.debugf(
			"%s contextLoader=%s applicationLoader=%s quarkusConfigLoader=%s thread=%s",
			location,
			loaderId(contextLoader),
			loaderId(applicationLoader),
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

	private boolean hasContext(Session session) {
		Object workspace = session.getUserProperties().get("workspaceId");
		Object user = session.getUserProperties().get("userId");
		if (workspace instanceof UUID && user instanceof UUID) {
			return true;
		}
		Object stored = session.getUserProperties().get("wsClient");
		return stored instanceof WorkspaceSocketManager.WorkspaceSocketClient;
	}

	@SuppressWarnings("unchecked")
	private void queuePendingMessage(Session session, String message) {
		if (message == null || message.isBlank()) {
			return;
		}
		Object stored = session.getUserProperties().get("pendingMessages");
		java.util.Deque<String> queue = stored instanceof java.util.Deque ? (java.util.Deque<String>) stored : new java.util.ArrayDeque<>();
		if (queue.size() >= 20) {
			queue.pollFirst();
		}
		queue.addLast(message);
		session.getUserProperties().put("pendingMessages", queue);
	}

	@SuppressWarnings("unchecked")
	private void drainPendingMessages(Session session) {
		Object stored = session.getUserProperties().get("pendingMessages");
		if (!(stored instanceof java.util.Deque<?>)) {
			return;
		}
		java.util.Deque<String> queue = (java.util.Deque<String>) stored;
		if (queue.isEmpty()) {
			return;
		}
		vertx.executeBlocking(
			promise -> {
				withApplicationClassLoader(
					() -> {
						logClassLoaderState("ws.pending.drain.before");
						while (!queue.isEmpty()) {
							String message = queue.pollFirst();
							WorkspaceSocketMessage payload = parse(message);
							if (payload == null || payload.session_id() == null || payload.type() == null) {
								continue;
							}
							socketHandler.handleMessage(session, payload);
						}
						promise.complete();
					}
				);
			},
			result -> {
				if (result.failed()) {
					LOGGER.errorf(result.cause(), "WS pending message drain failed");
				}
			}
		);
	}
}
