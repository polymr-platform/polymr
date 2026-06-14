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

package be.celerex.polymr.mcp;

import be.celerex.polymr.model.McpCallLog;
import be.celerex.polymr.model.McpServer;
import be.celerex.polymr.model.Script;
import be.celerex.polymr.model.Session;
import be.celerex.polymr.model.Tenant;
import be.celerex.polymr.model.User;
import be.celerex.polymr.model.Workspace;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class McpCallLogService {
	@Inject
	EntityManager entityManager;

	@Transactional
	@ActivateRequestContext
	public void recordInput(McpCallLogEntry entry) {
		if (entry == null
				|| entry.tenantId() == null
				|| entry.workspaceId() == null
				|| entry.serverId() == null) {
			return;
		}
		McpServer server = entityManager.find(McpServer.class, entry.serverId());
		if (server == null) {
			return;
		}
		McpCallLog log = new McpCallLog();
		log.tenant = entityManager.getReference(Tenant.class, entry.tenantId());
		log.workspace = entityManager.getReference(Workspace.class, entry.workspaceId());
		applyServerSnapshot(log, server);
		if (entry.sessionId() != null) {
			log.session = entityManager.getReference(Session.class, entry.sessionId());
		}
		if (entry.userId() != null) {
			log.user = entityManager.getReference(User.class, entry.userId());
		}
		log.connectionId = entry.connectionId();
		log.requestId = entry.requestId();
		log.method = entry.method();
		log.protocol = entry.protocol();
		log.input = entry.payload();
		log.mcpServerOverrideName = entry.overrideName();
		log.mcpServerOverrideTagName = entry.overrideTagName();
		log.scriptCallId = entry.scriptCallId();
		if (entry.scriptId() != null) {
			log.script = entityManager.getReference(Script.class, entry.scriptId());
		}
		entityManager.persist(log);
	}

	@Transactional
	@ActivateRequestContext
	public void recordOutput(McpCallLogEntry entry) {
		if (entry == null
				|| entry.tenantId() == null
				|| entry.workspaceId() == null
				|| entry.serverId() == null) {
			return;
		}
		McpServer server = entityManager.find(McpServer.class, entry.serverId());
		if (server == null) {
			return;
		}
		McpCallLog log = findByRequest(entry.connectionId(), entry.requestId());
		if (log == null) {
			log = new McpCallLog();
			log.tenant = entityManager.getReference(Tenant.class, entry.tenantId());
			log.workspace = entityManager.getReference(Workspace.class, entry.workspaceId());
			applyServerSnapshot(log, server);
			log.connectionId = entry.connectionId();
			log.requestId = entry.requestId();
			log.method = entry.method();
			log.protocol = entry.protocol();
		}
		else if (log.mcpServer == null) {
			applyServerSnapshot(log, server);
		}
		if (log.session == null && entry.sessionId() != null) {
			log.session = entityManager.getReference(Session.class, entry.sessionId());
		}
		if (log.user == null && entry.userId() != null) {
			log.user = entityManager.getReference(User.class, entry.userId());
		}
		log.output = entry.payload();
		log.mcpServerOverrideName = entry.overrideName();
		log.mcpServerOverrideTagName = entry.overrideTagName();
		if (entry.scriptCallId() != null) {
			log.scriptCallId = entry.scriptCallId();
		}
		if (entry.scriptId() != null) {
			log.script = entityManager.getReference(Script.class, entry.scriptId());
		}
		if (entry.status() != null) {
			log.status = entry.status();
		}
		if (log.id == null) {
			entityManager.persist(log);
		}
	}

	@Transactional
	@ActivateRequestContext
	public void recordIntercepted(McpCallLogEntry entry) {
		if (entry == null
				|| entry.tenantId() == null
				|| entry.workspaceId() == null
				|| entry.serverId() == null) {
			return;
		}
		McpServer server = entityManager.find(McpServer.class, entry.serverId());
		if (server == null) {
			return;
		}
		McpCallLog log = new McpCallLog();
		log.tenant = entityManager.getReference(Tenant.class, entry.tenantId());
		log.workspace = entityManager.getReference(Workspace.class, entry.workspaceId());
		applyServerSnapshot(log, server);
		if (entry.sessionId() != null) {
			log.session = entityManager.getReference(Session.class, entry.sessionId());
		}
		if (entry.userId() != null) {
			log.user = entityManager.getReference(User.class, entry.userId());
		}
		log.connectionId = entry.connectionId();
		log.requestId = entry.requestId();
		log.method = entry.method();
		log.protocol = entry.protocol();
		log.input = entry.payload();
		log.output = entry.payload();
		log.status = entry.status();
		log.mcpServerOverrideName = entry.overrideName();
		log.mcpServerOverrideTagName = entry.overrideTagName();
		log.scriptCallId = entry.scriptCallId();
		if (entry.scriptId() != null) {
			log.script = entityManager.getReference(Script.class, entry.scriptId());
		}
		entityManager.persist(log);
	}

	private void applyServerSnapshot(McpCallLog log, McpServer server) {
		log.mcpServer = server;
		log.mcpServerName = server.name;
		log.mcpServerProtocol = server.protocol == null ? null : server.protocol.name();
	}

	private McpCallLog findByRequest(UUID connectionId, Integer requestId) {
		if (connectionId == null || requestId == null) {
			return null;
		}
		List<McpCallLog> results = entityManager.createQuery(
				"select l from McpCallLog l where l.connectionId = :connectionId and "
					+ "l.requestId = :requestId order by l.createdAt desc",
				McpCallLog.class
			)
			.setParameter("connectionId", connectionId)
			.setParameter("requestId", requestId)
			.setMaxResults(1)
			.getResultList();
		return results.isEmpty() ? null : results.get(0);
	}

	public record McpCallLogEntry(
			UUID tenantId,
			UUID workspaceId,
			UUID serverId,
			UUID sessionId,
			UUID userId,
			UUID connectionId,
			Integer requestId,
			String method,
			String protocol,
			String payload,
			String status,
			UUID scriptCallId,
			UUID scriptId,
			String overrideName,
			String overrideTagName) {}
}
