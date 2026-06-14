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

package be.celerex.polymr.scripts;

import be.celerex.polymr.model.Script;
import be.celerex.polymr.model.ScriptCallLog;
import be.celerex.polymr.model.Session;
import be.celerex.polymr.model.Tenant;
import be.celerex.polymr.model.User;
import be.celerex.polymr.model.Workspace;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import java.util.UUID;

@ApplicationScoped
public class ScriptCallLogService {
	@Inject
	EntityManager entityManager;

	@Transactional(TxType.REQUIRES_NEW)
	public ScriptCallLog recordStart(UUID tenantId, UUID workspaceId, UUID scriptId, UUID userId, UUID sessionId, String input) {
		ScriptCallLog log = new ScriptCallLog();
		log.tenant = entityManager.getReference(Tenant.class, tenantId);
		log.workspace = entityManager.getReference(Workspace.class, workspaceId);
		log.script = entityManager.getReference(Script.class, scriptId);
		if (userId != null) {
			log.user = entityManager.getReference(User.class, userId);
		}
		if (sessionId != null) {
			log.session = entityManager.getReference(Session.class, sessionId);
		}
		log.input = input;
		log.status = "started";
		entityManager.persist(log);
		return log;
	}

	@Transactional(TxType.REQUIRES_NEW)
	public void recordSuccess(UUID logId, String output) {
		if (logId == null) {
			return;
		}
		ScriptCallLog log = entityManager.find(ScriptCallLog.class, logId);
		if (log == null) {
			return;
		}
		log.output = output;
		log.status = "success";
	}

	@Transactional(TxType.REQUIRES_NEW)
	public void recordFailure(UUID logId, String output) {
		if (logId == null) {
			return;
		}
		ScriptCallLog log = entityManager.find(ScriptCallLog.class, logId);
		if (log == null) {
			return;
		}
		log.output = output;
		log.status = "failed";
	}
}
