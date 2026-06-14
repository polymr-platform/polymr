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
import be.celerex.polymr.model.WorkflowRun;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class SessionActivityService {
	@Inject
	EntityManager entityManager;

	@Inject
	SessionParticipantService participantService;

	@Transactional
	public void update(Session session, ObjectNode activity) {
		if (session == null) {
			return;
		}
		Session managed = entityManager.find(Session.class, session.id);
		if (managed == null) {
			return;
		}
		managed.currentActivity = activity == null ? null : activity.deepCopy();
		participantService.broadcastSessionActivity(managed);
	}

	@Transactional
	public void clear(Session session) {
		update(session, null);
	}

	@Transactional
	public void clearForNodeTransition(WorkflowRun run, String nextNodeId) {
		if (run == null || run.session == null) {
			return;
		}
		String previousNodeId = run.currentNode;
		if (previousNodeId == null || nextNodeId == null || previousNodeId.equals(nextNodeId)) {
			return;
		}
		JsonNode current = run.session.currentActivity;
		if (current == null || current.isNull()) {
			return;
		}
		clear(run.session);
	}
}
