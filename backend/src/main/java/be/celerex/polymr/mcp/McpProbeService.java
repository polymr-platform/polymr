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

import be.celerex.polymr.model.McpServer;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class McpProbeService {
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
	@Inject
	McpClientFactory clientFactory;

	public ProbeResult probe(McpServer server) throws Exception {
		McpClientFactory.McpClientSession session = clientFactory.connect(server, java.util.Map.of(), null);
		try {
			JsonNode initialize = session.client()
				.initialize(server.configurationJson)
				.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
			JsonNode tools = session.client()
				.listTools()
				.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
			JsonNode resources = null;
			try {
				resources = session.client()
					.listResources()
					.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
			}
			catch (Exception ignored) {}
			return parseInitialize(initialize, tools, resources);
		}
		finally {
			session.shutdown();
		}
	}

	private ProbeResult parseInitialize(JsonNode initialize, JsonNode tools, JsonNode resources) {
		JsonNode configSchema = initialize == null ? null : initialize.get("configSchema");
		boolean policySupported = false;
		if (initialize != null) {
			JsonNode capabilities = initialize.get("capabilities");
			if (capabilities != null) {
				JsonNode experimental = capabilities.get("experimental");
				if (experimental != null && experimental.has("policy")) {
					JsonNode policy = experimental.get("policy");
					if (policy.isBoolean()) {
						// Legacy support for boolean policy; should be removed soon.
						policySupported = policy.asBoolean(false);
					}
					else if (policy.isObject()) {
						JsonNode enabled = policy.get("enabled");
						policySupported = enabled != null && enabled.asBoolean(false);
					}
				}
			}
		}
		return new ProbeResult(configSchema, policySupported, initialize, tools, resources);
	}

	public record ProbeResult(
			JsonNode configSchema,
			boolean policySupported,
			JsonNode initialize,
			JsonNode tools,
			JsonNode resources) {}
}
