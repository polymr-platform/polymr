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

import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class SessionConnectionReaper {
	private static final Logger LOGGER = Logger.getLogger(SessionConnectionReaper.class);
	@ConfigProperty(name = "polymr.presence.reaper-retention-seconds", defaultValue = "86400")
	long retentionSeconds;

	@ConfigProperty(name = "polymr.presence.active-retention-seconds", defaultValue = "3600")
	long activeRetentionSeconds;

	@Inject
	EntityManager entityManager;

	@Inject
	be.celerex.polymr.infra.ServerIdentity serverIdentity;

	@Inject
	be.celerex.polymr.ws.WorkspaceSocketManager socketManager;

	@PostConstruct
	void runOnStartup() {
		runOnce();
	}

	@Scheduled(every = "{polymr.presence.reaper-interval}")
	void runOnce() {
		int deletedActive = deleteStaleActive();
		int deleted = reapInactive();
		int deactivated = deactivateClosedConnections();
		if (deletedActive > 0) {
			LOGGER.debugf("Presence reaper deleted %d stale active connections", deletedActive);
		}
		if (deleted > 0) {
			LOGGER.debugf("Presence reaper deleted %d stale connections", deleted);
		}
		if (deactivated > 0) {
			LOGGER.debugf("Presence reaper deactivated %d closed connections", deactivated);
		}
	}

	@Transactional
	int reapInactive() {
		Instant cutoff = Instant.now().minusSeconds(retentionSeconds);
		return entityManager.createQuery(
				"delete from SessionParticipantConnection c "
					+ "where c.active = false and (c.lastSeenAt is null or c.lastSeenAt < :cutoff)"
			)
			.setParameter("cutoff", cutoff)
			.executeUpdate();
	}

	@Transactional
	int deleteStaleActive() {
		Instant cutoff = Instant.now().minusSeconds(activeRetentionSeconds);
		return entityManager.createQuery(
				"delete from SessionParticipantConnection c "
					+ "where c.active = true and (c.lastSeenAt is null or c.lastSeenAt < :cutoff)"
			)
			.setParameter("cutoff", cutoff)
			.executeUpdate();
	}

	@Transactional
	int deactivateClosedConnections() {
		Set<String> activeIds = socketManager.activeConnectionIds();
		Instant now = Instant.now();
		if (activeIds.isEmpty()) {
			return entityManager.createQuery(
					"update SessionParticipantConnection c set c.active = false, c.lastActiveAt = :now, "
						+ "c.lastSeenAt = :now "
						+ "where c.active = true and c.serverId = :serverId"
				)
				.setParameter("now", now)
				.setParameter("serverId", serverIdentity.id())
				.executeUpdate();
		}
		return entityManager.createQuery(
				"update SessionParticipantConnection c set c.active = false, c.lastActiveAt = :now, "
					+ "c.lastSeenAt = :now "
					+ "where c.active = true and c.serverId = :serverId and c.connectionId not in :activeIds"
			)
			.setParameter("now", now)
			.setParameter("serverId", serverIdentity.id())
			.setParameter("activeIds", activeIds)
			.executeUpdate();
	}
}
