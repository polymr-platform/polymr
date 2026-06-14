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

import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

@ApplicationScoped
public class McpCallRegistry {
	private static final Logger LOGGER = Logger.getLogger(McpCallRegistry.class);

	public interface McpCancelable {
		void cancel();
	}

	private final ConcurrentHashMap<UUID, McpCancelable> calls = new ConcurrentHashMap<>();

	public void register(UUID sessionId, McpCancelable call) {
		if (sessionId == null || call == null) {
			return;
		}
		McpCancelable previous = calls.put(sessionId, call);
		if (previous != null && previous != call) {
			LOGGER.warnf("MCP call registry overwrite session=%s", sessionId);
		}
	}

	public void clear(UUID sessionId, McpCancelable call) {
		if (sessionId == null || call == null) {
			return;
		}
		calls.remove(sessionId, call);
	}

	public boolean cancel(UUID sessionId) {
		if (sessionId == null) {
			return false;
		}
		McpCancelable call = calls.remove(sessionId);
		if (call == null) {
			LOGGER.warnf("MCP call cancel missed (no active call) session=%s", sessionId);
			return false;
		}
		LOGGER.infof("MCP call cancel requested session=%s", sessionId);
		call.cancel();
		return true;
	}
}
