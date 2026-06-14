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

import be.celerex.polymr.mcp.client.McpLogSink;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class McpCallLogSink implements McpLogSink {
	private final ObjectMapper mapper;
	private final McpCallLogService logService;
	private final UUID tenantId;
	private final UUID workspaceId;
	private final UUID serverId;
	private final String protocol;
	private final UUID connectionId;
	private final Map<Integer, McpLogContext> contextByRequest = new ConcurrentHashMap<>();
	private final Map<Integer, String> methodByRequest = new ConcurrentHashMap<>();

	public McpCallLogSink(
			ObjectMapper mapper,
			McpCallLogService logService,
			UUID tenantId,
			UUID workspaceId,
			UUID serverId,
			String protocol,
			UUID connectionId) {
		this.mapper = mapper;
		this.logService = logService;
		this.tenantId = tenantId;
		this.workspaceId = workspaceId;
		this.serverId = serverId;
		this.protocol = protocol;
		this.connectionId = connectionId;
	}

	@Override
	public void logOutbound(String json) {
		log(json, true);
	}

	@Override
	public void logInbound(String json) {
		log(json, false);
	}

	private void log(String json, boolean outbound) {
		if (json == null || json.isBlank()) {
			return;
		}
		JsonNode node;
		try {
			node = mapper.readTree(json);
		}
		catch (Exception e) {
			return;
		}
		Integer requestId = node.has("id") && node.get("id").canConvertToInt() ? node.get("id").asInt() : null;
		String method = node.has("method") ? node.get("method").asText(null) : null;
		McpLogContext context = null;
		if (outbound) {
			context = McpLogContextHolder.get();
			if (requestId != null && context != null) {
				contextByRequest.put(requestId, context);
			}
			if (requestId != null && method != null) {
				methodByRequest.put(requestId, method);
			}
		}
		else if (requestId != null) {
			context = contextByRequest.remove(requestId);
		}
		if (requestId != null && method == null) {
			method = methodByRequest.get(requestId);
		}
		McpCallLogService.McpCallLogEntry entry = new McpCallLogService.McpCallLogEntry(
			tenantId,
			workspaceId,
			serverId,
			context == null ? null : context.sessionId(),
			context == null ? null : context.userId(),
			connectionId,
			requestId,
			method,
			protocol,
			json,
			null,
			context == null ? null : context.scriptCallId(),
			context == null ? null : context.scriptId(),
			context == null ? null : context.overrideName(),
			context == null ? null : context.overrideTagName()
		);
		if (outbound) {
			logService.recordInput(entry);
		}
		else {
			logService.recordOutput(entry);
		}
	}
}
