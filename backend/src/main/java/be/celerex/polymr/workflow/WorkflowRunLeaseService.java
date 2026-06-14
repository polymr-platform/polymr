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

package be.celerex.polymr.workflow;

import be.celerex.polymr.infra.ServerIdentity;
import be.celerex.polymr.model.WorkflowRunStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.UUID;
import org.jboss.logging.Logger;

@ApplicationScoped
public class WorkflowRunLeaseService {
	private static final Logger LOGGER = Logger.getLogger(WorkflowRunLeaseService.class);
	@Inject
	EntityManager entityManager;

	@Inject
	ServerIdentity serverIdentity;

	@Transactional
	public UUID claimNext() {
		List<UUID> candidates = entityManager.createQuery("select r.id from WorkflowRun r where r.status = :status order by r.createdAt", UUID.class)
			.setParameter("status", WorkflowRunStatus.QUEUED)
			.setMaxResults(1)
			.getResultList();
		if (candidates.isEmpty()) {
			return null;
		}
		UUID runId = candidates.get(0);
		int updated = entityManager.createQuery(
				"update WorkflowRun r set r.status = :leased, r.runtimeServerId = :serverId "
					+ "where r.id = :id and r.status = :queued"
			)
			.setParameter("leased", WorkflowRunStatus.LEASED)
			.setParameter("serverId", serverIdentity.id())
			.setParameter("queued", WorkflowRunStatus.QUEUED)
			.setParameter("id", runId)
			.executeUpdate();
		if (updated != 1) {
			LOGGER.debugf("Failed to lease workflow run %s", runId);
			return null;
		}
		LOGGER.debugf("Leased workflow run %s", runId);
		return runId;
	}
}
