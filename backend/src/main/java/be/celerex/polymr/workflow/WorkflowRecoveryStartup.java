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

import be.celerex.polymr.model.SessionStatus;
import be.celerex.polymr.model.WorkflowRun;
import be.celerex.polymr.model.WorkflowRunStatus;
import be.celerex.polymr.infra.ServerIdentity;
import be.celerex.polymr.session.SessionActivityService;
import be.celerex.polymr.session.SessionChatService;
import be.celerex.polymr.session.SessionEventService;
import be.celerex.polymr.session.dto.SessionEventResponse;
import be.celerex.polymr.ws.WorkspaceSocketEvent;
import be.celerex.polymr.ws.WorkspaceSocketManager;
import be.celerex.polymr.workflow.WorkflowRunDispatcher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import java.time.Instant;
import java.util.List;
import org.jboss.logging.Logger;

@ApplicationScoped
public class WorkflowRecoveryStartup {
	private static final Logger LOGGER = Logger.getLogger(WorkflowRecoveryStartup.class);
	@Inject
	EntityManager entityManager;

	@Inject
	ObjectMapper objectMapper;

	@Inject
	ServerIdentity serverIdentity;

	@Inject
	SessionEventService eventService;

	@Inject
	WorkspaceSocketManager socketManager;

	@Inject
	WorkflowRunDispatcher runDispatcher;

	@Inject
	SessionChatService sessionChatService;

	@Inject
	SessionActivityService sessionActivityService;

	@Transactional
	void onStartup(@Observes StartupEvent event) {
		markRecoverableRuns();
	}

	void markRecoverableRuns() {
		List<WorkflowRun> runs = entityManager.createQuery(
				"select r from WorkflowRun r join fetch r.session "
					+ "where r.status in (:running, :leased) "
					+ "and (r.runtimeServerId = :serverId or r.runtimeServerId is null)",
				WorkflowRun.class
			)
			.setParameter("running", WorkflowRunStatus.RUNNING)
			.setParameter("leased", WorkflowRunStatus.LEASED)
			.setParameter("serverId", serverIdentity.id())
			.getResultList();
		int updated = 0;
		List<WorkflowRun> recovered = new java.util.ArrayList<>();
		for (WorkflowRun run : runs) {
			run.status = WorkflowRunStatus.QUEUED;
			run.runtimeServerId = null;
			if (run.session != null) {
				run.session.status = SessionStatus.ACTIVE;
				run.session.locked = true;
				sessionActivityService.clear(run.session);
			}
			recordRecoveryEvent(run, "server_restart");
			recovered.add(run);
			updated++;
		}
		for (WorkflowRun run : recovered) {
			sessionChatService.resumeRecovery(run);
		}
		if (updated > 0) {
			LOGGER.warnf("Marked %d workflow runs for recovery", updated);
		}
	}

	public static ObjectNode ensureObjectNode(JsonNode node) {
		if (node instanceof ObjectNode objectNode) {
			return objectNode;
		}
		return new ObjectMapper().createObjectNode();
	}

	private void recordRecoveryEvent(WorkflowRun run, String reason) {
		if (run == null || run.session == null) {
			return;
		}
		ObjectNode payload = objectMapper.createObjectNode();
		payload.put("text", "Workflow recovered after a server restart.");
		payload.put("system_type", "workflow_recovery");
		payload.put("reason", reason == null ? "server_restart" : reason);
		payload.put("recovered_by", serverIdentity.id());
		var event = eventService.createEvent(run.session, be.celerex.polymr.model.SessionEventType.SYSTEM, payload);
		SessionEventResponse response = new SessionEventResponse(
			event.id,
			event.eventType,
			event.payloadJson,
			event.createdAt,
			event.epochId,
			event.inputTokens,
			event.outputTokens,
			event.reasoningTokens,
			event.cachedInputTokens,
			event.tokenizerModelId,
			event.priceSnapshot,
			event.priceCurrency
		);
		socketManager.broadcastToSession(
			run.session.id,
			new WorkspaceSocketEvent("session.event", run.session.workspace.id, run.session.id, response)
		);
	}
}
