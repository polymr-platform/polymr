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

package be.celerex.polymr.mcp.client;

import be.celerex.polymr.mcp.McpAuthRequiredException;
import be.celerex.polymr.mcp.McpStdioAuthError;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CompletableFuture;

public class StdioMcpClient implements McpClient {
	private final ObjectMapper mapper;
	private final JsonRpcConnection connection;

	public StdioMcpClient(
			ObjectMapper mapper,
			InputStream input,
			OutputStream output,
			McpLogSink logSink,
			JsonRpcConnection.Framing framing) {
		this.mapper = mapper;
		this.connection = new JsonRpcConnection(mapper, input, output, logSink, framing);
		this.connection.start();
	}

	@Override
	public CompletableFuture<JsonNode> initialize(JsonNode configuration) {
		ObjectNode params = mapper.createObjectNode();
		params.put("protocolVersion", "2024-11-05");
		ObjectNode capabilities = params.putObject("capabilities");
		if (configuration != null && !configuration.isNull()) {
			ObjectNode experimental = capabilities.putObject("experimental");
			experimental.set("configuration", configuration);
		}
		ObjectNode clientInfo = params.putObject("clientInfo");
		clientInfo.put("name", "polymr");
		clientInfo.put("version", "0.1.0");
		return connection.request("initialize", params)
			.exceptionallyCompose(this::mapAuthRequired)
			.thenApply(result -> {
				connection.notify("initialized", null);
				return result;
			});
	}

	@Override
	public CompletableFuture<JsonNode> callTool(String name, JsonNode arguments, JsonNode meta) {
		ObjectNode params = mapper.createObjectNode();
		params.put("name", name);
		if (arguments != null) {
			params.set("arguments", arguments);
		}
		if (meta != null) {
			params.set("_meta", meta);
		}
		return connection.request("tools/call", params);
	}

	@Override
	public CompletableFuture<JsonNode> listTools() {
		return connection.request("tools/list", null);
	}

	@Override
	public CompletableFuture<JsonNode> listResources() {
		return connection.request("resources/list", null);
	}

	@Override
	public CompletableFuture<JsonNode> readResource(String uri) {
		ObjectNode params = mapper.createObjectNode();
		params.put("uri", uri);
		return connection.request("resources/read", params);
	}

	@Override
	public void cancel() {
		connection.cancel();
	}

	private CompletableFuture<JsonNode> mapAuthRequired(Throwable throwable) {
		Throwable cause = throwable instanceof java.util.concurrent.CompletionException && throwable.getCause() != null
			? throwable.getCause()
			: throwable;
		if (cause instanceof JsonRpcException jsonRpcException
				&& jsonRpcException.code() == McpStdioAuthError.AUTH_REQUIRED) {
			String resourceMetadataUrl = extractResourceMetadataUrl(jsonRpcException.error());
			if (resourceMetadataUrl != null && !resourceMetadataUrl.isBlank()) {
				CompletableFuture<JsonNode> failed = new CompletableFuture<>();
				failed.completeExceptionally(new McpAuthRequiredException(resourceMetadataUrl));
				return failed;
			}
		}
		CompletableFuture<JsonNode> failed = new CompletableFuture<>();
		failed.completeExceptionally(cause);
		return failed;
	}

	private String extractResourceMetadataUrl(JsonNode error) {
		if (error == null || error.isNull()) {
			return null;
		}
		JsonNode meta = error.get("_meta");
		if (meta == null || meta.isNull()) {
			return null;
		}
		JsonNode auth = meta.get("auth");
		if (auth != null && !auth.isNull()) {
			JsonNode resourceUrl = auth.get("resourceUrl");
			String text = resourceUrl == null || resourceUrl.isNull() ? null : resourceUrl.asText(null);
			if (text != null && !text.isBlank()) {
				return text;
			}
		}
		return null;
	}
}
