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

import be.celerex.polymr.model.Session;
import be.celerex.polymr.model.WorkflowRun;
import be.celerex.polymr.model.WorkflowRunCheckpoint;
import be.celerex.polymr.session.SessionActivityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.util.UUID;

@ApplicationScoped
public class WorkflowCheckpointService {
	@Inject
	EntityManager entityManager;

	@Inject
	SessionActivityService sessionActivityService;

	@Inject
	ObjectMapper objectMapper;

	@Transactional
	public WorkflowRun requireRun(Session session) {
		return entityManager.createQuery(
				"select r from WorkflowRun r join fetch r.workflowDefinition where r.session.id = :sessionId",
				WorkflowRun.class
			)
			.setParameter("sessionId", session.id)
			.getResultStream()
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("Workflow run not found"));
	}

	@Transactional
	public void saveCheckpoint(WorkflowRun run, String nodeId, ObjectNode state) {
		sessionActivityService.clearForNodeTransition(run, nodeId);
		WorkflowRunCheckpoint checkpoint = new WorkflowRunCheckpoint();
		checkpoint.workflowRun = run;
		checkpoint.stepIndex = nextStepIndex(run.id);
		checkpoint.nodeId = nodeId;
		checkpoint.stateJson = state;
		entityManager.persist(checkpoint);
		run.currentNode = nodeId;
		run.checkpointJson = state;
	}

	@Transactional
	public void updateProjectionFromCheckpoint(WorkflowRun run, java.util.function.UnaryOperator<ObjectNode> updater) {
		if (run == null || updater == null) {
			return;
		}
		ObjectNode base = run.checkpointJson instanceof ObjectNode node ? node.deepCopy() : objectMapper.createObjectNode();
		ObjectNode updated = updater.apply(base);
		if (updated == null) {
			updated = base;
		}
		String nodeId = run.currentNode;
		if (nodeId == null || nodeId.isBlank()) {
			nodeId = updated.path("next").asText(null);
		}
		if (nodeId == null || nodeId.isBlank()) {
			nodeId = "user_input";
		}
		saveCheckpoint(run, nodeId, updated);
	}

	private int nextStepIndex(UUID runId) {
		Integer current = entityManager.createQuery(
				"select max(c.stepIndex) from WorkflowRunCheckpoint c where c.workflowRun.id = :runId",
				Integer.class
			)
			.setParameter("runId", runId)
			.getSingleResult();
		return current == null ? 1 : current + 1;
	}
}
