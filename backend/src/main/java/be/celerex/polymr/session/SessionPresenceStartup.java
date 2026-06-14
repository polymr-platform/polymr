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

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

@ApplicationScoped
public class SessionPresenceStartup {
	private static final Logger LOGGER = Logger.getLogger(SessionPresenceStartup.class);
	@Inject
	EntityManager entityManager;

	@Inject
	be.celerex.polymr.infra.ServerIdentity serverIdentity;

	@PostConstruct
	@Transactional
	void resetPresence() {
		int updated = entityManager.createQuery("update SessionParticipantConnection c set c.active = false where c.serverId = :serverId")
			.setParameter("serverId", serverIdentity.id())
			.executeUpdate();
		LOGGER.infof("Reset session participant presence for server %s: %d", serverIdentity.id(), updated);
	}
}
