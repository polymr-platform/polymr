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

import be.celerex.polymr.infra.ServerIdentity;
import be.celerex.polymr.lock.LockService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ClusterEventBus {
	private static final Logger LOGGER = Logger.getLogger(ClusterEventBus.class);
	private static final String CHANNEL = "workspace.events";
	@Inject
	LockService lockService;

	@Inject
	ObjectMapper mapper;

	@Inject
	WorkspaceSocketManager socketManager;

	@Inject
	ServerIdentity serverIdentity;

	private AutoCloseable subscription;

	@PostConstruct
	void subscribe() {
		subscription = lockService.subscribe(CHANNEL, this::handleMessage);
	}

	public void publish(WorkspaceSocketEvent event, Set<java.util.UUID> userIds) {
		try {
			ClusterEvent envelope = new ClusterEvent(serverIdentity.id(), event, userIds);
			lockService.publish(CHANNEL, mapper.writeValueAsString(envelope));
		}
		catch (Exception ex) {
			LOGGER.debugf(ex, "Cluster publish failed for %s", event.type());
		}
	}

	private void handleMessage(String payload) {
		if (payload == null || payload.isBlank()) {
			return;
		}
		try {
			ClusterEvent envelope = mapper.readValue(payload, ClusterEvent.class);
			if (envelope == null || envelope.event() == null) {
				return;
			}
			if (serverIdentity.id().equals(envelope.source_server_id())) {
				return;
			}
			if (envelope.user_ids() != null && !envelope.user_ids().isEmpty()) {
				socketManager.broadcastToUsers(envelope.event().workspace_id(), envelope.user_ids(), envelope.event());
				return;
			}
			socketManager.broadcast(envelope.event().workspace_id(), envelope.event());
		}
		catch (Exception ex) {
			LOGGER.debugf(ex, "Cluster message parse failed");
		}
	}
}
