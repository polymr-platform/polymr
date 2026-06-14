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
import be.celerex.polymr.model.SessionStatus;
import be.celerex.polymr.model.SessionVisibility;
import be.celerex.polymr.model.WorkflowDefinition;
import be.celerex.polymr.model.WorkflowRun;
import be.celerex.polymr.session.dto.SessionParticipantResponse;
import be.celerex.polymr.session.dto.SessionSummary;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class SessionSummaryService {
	@Inject
	EntityManager entityManager;

	@Inject
	SessionParticipantService participantService;

	public List<Session> listVisibleActiveSessions(UUID workspaceId, UUID userId) {
		TypedQuery<Session> query = entityManager.createQuery(baseSessionQuery("and s.status in :statuses ") + "order by s.updatedAt desc", Session.class)
			.setParameter("workspaceId", workspaceId)
			.setParameter("workspaceVisibility", SessionVisibility.WORKSPACE)
			.setParameter("userId", userId)
			.setParameter("statuses", activeStatuses());
		return query.getResultList();
	}

	public List<Session> listVisibleHistorizedSessions(UUID workspaceId, UUID userId, int offset, int limit) {
		TypedQuery<Session> query = entityManager.createQuery(baseSessionQuery("and s.status not in :statuses ") + "order by s.updatedAt desc", Session.class)
			.setParameter("workspaceId", workspaceId)
			.setParameter("workspaceVisibility", SessionVisibility.WORKSPACE)
			.setParameter("userId", userId)
			.setParameter("statuses", activeStatuses())
			.setFirstResult(offset)
			.setMaxResults(limit);
		return query.getResultList();
	}

	public long countVisibleHistorizedSessions(UUID workspaceId, UUID userId) {
		return entityManager.createQuery(baseCountQuery("and s.status not in :statuses "), Long.class)
			.setParameter("workspaceId", workspaceId)
			.setParameter("workspaceVisibility", SessionVisibility.WORKSPACE)
			.setParameter("userId", userId)
			.setParameter("statuses", activeStatuses())
			.getSingleResult();
	}

	public List<SessionSummary> buildSummaries(List<Session> sessions, UUID userId) {
		if (sessions.isEmpty()) {
			return List.of();
		}
		List<UUID> sessionIds = sessions.stream()
			.map(session -> session.id)
			.collect(Collectors.toList());
		List<WorkflowRun> runs = entityManager.createQuery(
				"select r from WorkflowRun r join fetch r.workflowDefinition where r.session.id in :sessionIds",
				WorkflowRun.class
			)
			.setParameter("sessionIds", sessionIds)
			.getResultList();
		Map<UUID, WorkflowRun> runBySession = runs.stream()
			.collect(Collectors.toMap(run -> run.session.id, run -> run));
		Map<UUID, List<SessionParticipantResponse>> participantsBySession = participantService.listParticipantsBySessionIds(sessionIds);
		return sessions.stream()
			.map(
				session -> toSummary(session, runBySession.get(session.id), participantsBySession.getOrDefault(session.id, List.of()))
			)
			.collect(Collectors.toList());
	}

	private String baseSessionQuery(String statusClause) {
		return "select distinct s from Session s left join SessionParticipant p on p.session.id = s.id "
			+ "where s.workspace.id = :workspaceId and "
			+ "(s.visibility = :workspaceVisibility or s.createdBy.id = :userId or p.user.id = :userId) "
			+ statusClause;
	}

	private String baseCountQuery(String statusClause) {
		return "select count(distinct s.id) from Session s left join SessionParticipant p on p.session.id = s.id "
			+ "where s.workspace.id = :workspaceId and "
			+ "(s.visibility = :workspaceVisibility or s.createdBy.id = :userId or p.user.id = :userId) "
			+ statusClause;
	}

	private EnumSet<SessionStatus> activeStatuses() {
		return EnumSet.of(SessionStatus.ACTIVE, SessionStatus.PAUSED);
	}

	private SessionSummary toSummary(Session session, WorkflowRun run, List<SessionParticipantResponse> participants) {
		WorkflowDefinition definition = run == null ? null : run.workflowDefinition;
		String checkpointStatus = run != null && run.checkpointJson != null ? run.checkpointJson.path("status").asText(null) : null;
		return new SessionSummary(
			session.id,
			session.title,
			session.status,
			session.status == SessionStatus.PAUSED,
			session.locked,
			participants,
			definition == null ? null : definition.id,
			definition == null ? null : definition.name,
			run == null ? null : run.status,
			run == null ? null : run.currentNode,
			checkpointStatus,
			session.defaultAssistant == null ? null : session.defaultAssistant.id,
			session.channel == null ? null : session.channel.id,
			session.channel == null ? null : session.channel.name,
			session.visibility,
			session.updatedAt,
			null,
			session.currentActivity
		);
	}
}
