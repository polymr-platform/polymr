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
import be.celerex.polymr.model.RuntimeHeartbeat;
import be.celerex.polymr.model.SessionStatus;
import be.celerex.polymr.model.WorkflowRun;
import be.celerex.polymr.model.WorkflowRunStatus;
import be.celerex.polymr.session.SessionActivityService;
import be.celerex.polymr.session.SessionChatService;
import be.celerex.polymr.session.SessionEventService;
import be.celerex.polymr.session.dto.SessionEventResponse;
import be.celerex.polymr.ws.WorkspaceSocketEvent;
import be.celerex.polymr.ws.WorkspaceSocketManager;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class WorkflowRunReaper {
	private static final Logger LOGGER = Logger.getLogger(WorkflowRunReaper.class);
	@ConfigProperty(name = "polymr.runtime.reaper-stale-seconds", defaultValue = "600")
	long staleSeconds;

	@Inject
	EntityManager entityManager;

	@Inject
	SessionChatService sessionChatService;

	@Inject
	SessionActivityService sessionActivityService;

	@Inject
	SessionEventService eventService;

	@Inject
	WorkspaceSocketManager socketManager;

	@Inject
	ObjectMapper objectMapper;

	@Inject
	ServerIdentity serverIdentity;

	@PostConstruct
	void runOnStartup() {
		reapOnce();
	}

	@Scheduled(every = "{polymr.runtime.reaper-interval:5m}")
	void scheduled() {
		reapOnce();
	}

	@Transactional
	void reapOnce() {
		Instant cutoff = Instant.now().minusSeconds(staleSeconds);
		List<RuntimeHeartbeat> stale = entityManager.createQuery(
				"select h from RuntimeHeartbeat h where h.lastSeenAt < :cutoff and h.serverId <> :serverId",
				RuntimeHeartbeat.class
			)
			.setParameter("cutoff", cutoff)
			.setParameter("serverId", serverIdentity.id())
			.getResultList();
		if (stale.isEmpty()) {
			return;
		}
		for (RuntimeHeartbeat heartbeat : stale) {
			recoverRunsForServer(heartbeat.serverId);
		}
	}

	private void recoverRunsForServer(String serverId) {
		if (serverId == null || serverId.isBlank()) {
			return;
		}
		List<WorkflowRun> runs = entityManager.createQuery(
				"select r from WorkflowRun r join fetch r.session "
					+ "where r.status in (:running, :leased) and r.runtimeServerId = :serverId",
				WorkflowRun.class
			)
			.setParameter("running", WorkflowRunStatus.RUNNING)
			.setParameter("leased", WorkflowRunStatus.LEASED)
			.setParameter("serverId", serverId)
			.getResultList();
		if (runs.isEmpty()) {
			return;
		}
		List<WorkflowRun> recovered = new ArrayList<>();
		for (WorkflowRun run : runs) {
			run.status = WorkflowRunStatus.QUEUED;
			run.runtimeServerId = null;
			if (run.session != null) {
				run.session.status = SessionStatus.ACTIVE;
				run.session.locked = true;
				sessionActivityService.clear(run.session);
			}
			recovered.add(run);
		}
		for (WorkflowRun run : recovered) {
			recordRecoveryEvent(run, serverId, "stale_heartbeat");
			sessionChatService.resumeRecovery(run);
		}
		LOGGER.warnf("Recovered %d workflow runs from server %s", recovered.size(), serverId);
	}

	private void recordRecoveryEvent(WorkflowRun run, String sourceServerId, String reason) {
		if (run == null || run.session == null) {
			return;
		}
		ObjectNode payload = objectMapper.createObjectNode();
		payload.put("text", "Workflow recovered after a server interruption.");
		payload.put("system_type", "workflow_recovery");
		if (sourceServerId != null) {
			payload.put("source_server_id", sourceServerId);
		}
		if (reason != null) {
			payload.put("reason", reason);
		}
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
