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

package be.celerex.polymr.infra;

import be.celerex.polymr.model.RuntimeHeartbeat;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

@ApplicationScoped
public class RuntimeHeartbeatService {
	private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
	@Inject
	EntityManager entityManager;

	@Inject
	ServerIdentity serverIdentity;

	@PostConstruct
	void init() {
		updateHeartbeat();
	}

	void onShutdown(@Observes ShutdownEvent event) {
		shuttingDown.set(true);
	}

	@Scheduled(every = "{polymr.runtime.heartbeat-interval:30s}")
	void scheduledHeartbeat() {
		if (shuttingDown.get()) {
			return;
		}
		updateHeartbeat();
	}

	@Transactional
	void updateHeartbeat() {
		if (shuttingDown.get()) {
			return;
		}
		String serverId = serverIdentity.id();
		Instant now = Instant.now();
		RuntimeHeartbeat heartbeat = entityManager.find(RuntimeHeartbeat.class, serverId);
		if (heartbeat == null) {
			heartbeat = new RuntimeHeartbeat();
			heartbeat.serverId = serverId;
			heartbeat.startedAt = now;
			heartbeat.lastSeenAt = now;
			entityManager.persist(heartbeat);
			return;
		}
		heartbeat.lastSeenAt = now;
	}
}
