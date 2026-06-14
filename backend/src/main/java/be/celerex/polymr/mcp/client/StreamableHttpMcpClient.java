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

import be.celerex.polymr.mcp.McpAuthHeaderParser;
import be.celerex.polymr.mcp.McpAuthRequiredException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.jboss.logging.Logger;

public class StreamableHttpMcpClient implements McpClient {
	private static final Logger LOGGER = Logger.getLogger(StreamableHttpMcpClient.class);
	private static final String MCP_SESSION_HEADER = "mcp-session-id";
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
	private final Duration requestTimeout;
	private final ObjectMapper mapper;
	private final HttpClient client;
	private final URI endpoint;
	private final Map<String, String> headers;
	private final McpLogSink logSink;
	private final AtomicInteger nextId = new AtomicInteger(1);
	private volatile HttpResponse<InputStream> activeResponse;
	private volatile JsonNode initializeConfiguration;

	public StreamableHttpMcpClient(
			ObjectMapper mapper,
			String url,
			Map<String, String> headers,
			McpLogSink logSink,
			Duration requestTimeout) {
		this.mapper = mapper;
		this.client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
		this.endpoint = URI.create(url);
		this.headers = headers;
		this.logSink = logSink;
		this.requestTimeout = requestTimeout == null ? Duration.ofSeconds(15) : requestTimeout;
	}

	@Override
	public CompletableFuture<JsonNode> initialize(JsonNode configuration) {
		initializeConfiguration = configuration == null ? null : configuration.deepCopy();
		return performInitialize();
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
		return request("tools/call", params);
	}

	@Override
	public CompletableFuture<JsonNode> listTools() {
		return request("tools/list", null);
	}

	@Override
	public CompletableFuture<JsonNode> listResources() {
		return request("resources/list", null);
	}

	@Override
	public CompletableFuture<JsonNode> readResource(String uri) {
		ObjectNode params = mapper.createObjectNode();
		params.put("uri", uri);
		return request("resources/read", params);
	}

	@Override
	public void cancel() {
		HttpResponse<InputStream> response = activeResponse;
		if (response != null && response.body() != null) {
			try {
				response.body().close();
			}
			catch (IOException e) {
				logSink.logLine("!! failed to close streamable HTTP body on cancel: " + e.getMessage());
			}
		}
	}

	private CompletableFuture<JsonNode> request(String method, JsonNode params) {
		int id = nextId.getAndIncrement();
		ObjectNode payload = mapper.createObjectNode();
		payload.put("jsonrpc", "2.0");
		payload.put("id", id);
		payload.put("method", method);
		if (params != null) {
			payload.set("params", params);
		}
		return send(payload, id, false)
			.thenApply(
				message -> {
					if (message.has("result")) {
						return message.get("result");
					}
					if (message.has("error")) {
						throw new IllegalStateException(message.get("error").toString());
					}
					return message;
				}
			);
	}

	private void notify(String method, JsonNode params) {
		ObjectNode payload = mapper.createObjectNode();
		payload.put("jsonrpc", "2.0");
		payload.put("method", method);
		if (params != null) {
			payload.set("params", params);
		}
		send(payload, null, false);
	}

	private CompletableFuture<JsonNode> performInitialize() {
		ObjectNode params = mapper.createObjectNode();
		params.put("protocolVersion", "2024-11-05");
		ObjectNode capabilities = params.putObject("capabilities");
		if (initializeConfiguration != null && !initializeConfiguration.isNull()) {
			ObjectNode experimental = capabilities.putObject("experimental");
			experimental.set("configuration", initializeConfiguration.deepCopy());
		}
		ObjectNode clientInfo = params.putObject("clientInfo");
		clientInfo.put("name", "polymr");
		clientInfo.put("version", "0.1.0");
		return request("initialize", params)
			.thenApply(result -> {
				notify("initialized", null);
				return result;
			});
	}

	private CompletableFuture<JsonNode> send(ObjectNode payload, Integer id, boolean retryingAfterSession404) {
		return send(payload, id, retryingAfterSession404, false);
	}

	private CompletableFuture<JsonNode> send(
			ObjectNode payload,
			Integer id,
			boolean retryingAfterSession404,
			boolean retryingAfterTransportFailure) {
		try {
			String json = mapper.writeValueAsString(payload);
			logSink.logOutbound(json);
			HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(endpoint)
				.timeout(requestTimeout)
				.POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
				.header("Content-Type", "application/json")
				.header("Accept", "application/x-ndjson, application/json, text/event-stream");
			if (headers != null) {
				for (Map.Entry<String, String> entry : headers.entrySet()) {
					builder.header(entry.getKey(), entry.getValue());
				}
			}
			return client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
				.handle(
					(response, error) -> {
						if (error == null) {
							activeResponse = response;
							return parseResponse(response, payload, id, retryingAfterSession404);
						}
						Throwable cause = unwrap(error);
						if (shouldRetryAfterTransportFailure(cause, retryingAfterTransportFailure)) {
							return send(payload.deepCopy(), id, retryingAfterSession404, true);
						}
						CompletableFuture<JsonNode> failed = new CompletableFuture<>();
						failed.completeExceptionally(cause == null ? error : cause);
						return failed;
					}
				)
				.thenCompose(future -> future);
		}
		catch (Exception e) {
			CompletableFuture<JsonNode> failed = new CompletableFuture<>();
			failed.completeExceptionally(e);
			return failed;
		}
	}

	private CompletableFuture<JsonNode> parseResponse(
			HttpResponse<InputStream> response,
			ObjectNode payload,
			Integer id,
			boolean retryingAfterSession404) {
		return CompletableFuture.supplyAsync(
			() -> {
				String contentType = response.headers().firstValue("Content-Type").orElse("");
				updateSessionHeader(response);
				try {
					if (response.statusCode() >= 400) {
						String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
						logSink.logLine("HTTP " + response.statusCode() + " " + body);
						LOGGER.warnf("MCP HTTP %d response: %s", response.statusCode(), body);
						if (shouldRetryAfterSession404(response.statusCode(), retryingAfterSession404)) {
							return retryAfterSession404(payload, id);
						}
						if (response.statusCode() == 401) {
							String header = response.headers().firstValue("WWW-Authenticate").orElse(null);
							String resourceMetadata = McpAuthHeaderParser.extractResourceMetadata(header);
							if (resourceMetadata == null || resourceMetadata.isBlank()) {
								resourceMetadata = deriveWellKnownUrl(endpoint);
							}
							if (resourceMetadata != null) {
								throw new McpAuthRequiredException(resourceMetadata);
							}
						}
						String detail = body == null || body.isBlank() ? "" : ": " + body.trim();
						throw new IllegalStateException("HTTP " + response.statusCode() + " from server" + detail);
					}
					if (contentType.contains("ndjson") || contentType.contains("jsonlines")) {
						return parseNdjson(response.body(), id);
					}
					if (contentType.contains("text/event-stream")) {
						return parseEventStream(response.body(), id);
					}
					String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8).trim();
					if (body.isEmpty()) {
						return mapper.createObjectNode();
					}
					logSink.logInbound(body);
					return mapper.readTree(body);
				}
				catch (CompletionException e) {
					throw e;
				}
				catch (RuntimeException e) {
					throw e;
				}
				catch (Exception e) {
					String detail = response == null ? "" : " (status=" + response.statusCode() + ", content-type=" + contentType + ")";
					throw new IllegalStateException("Failed to parse response" + detail, e);
				}
				finally {
					if (activeResponse == response) {
						activeResponse = null;
					}
				}
			}
		);
	}

	private JsonNode parseNdjson(InputStream input, Integer id) throws Exception {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				String payload = line.trim();
				if (payload.isEmpty()) {
					continue;
				}
				logSink.logInbound(payload);
				JsonNode node = mapper.readTree(payload);
				if (id == null) {
					return node;
				}
				if (node.has("id") && node.get("id").asInt() == id) {
					return node;
				}
			}
		}
		throw new IllegalStateException("Stream ended without response");
	}

	private JsonNode parseEventStream(InputStream input, Integer id) throws Exception {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
			StringBuilder data = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.startsWith("data:")) {
					data.append(line.substring(5).stripLeading());
					data.append('\n');
					continue;
				}
				if (!line.isBlank()) {
					continue;
				}
				String payload = data.toString().trim();
				data.setLength(0);
				if (payload.isEmpty()) {
					continue;
				}
				logSink.logInbound(payload);
				JsonNode node = mapper.readTree(payload);
				if (id == null) {
					return node;
				}
				if (node.has("id") && node.get("id").asInt() == id) {
					return node;
				}
			}
		}
		throw new IllegalStateException("Stream ended without response");
	}

	private boolean shouldRetryAfterSession404(int statusCode, boolean retryingAfterSession404) {
		return statusCode == 404
			&& !retryingAfterSession404
			&& headers != null
			&& headers.containsKey(MCP_SESSION_HEADER);
	}

	private boolean shouldRetryAfterTransportFailure(Throwable error, boolean retryingAfterTransportFailure) {
		if (retryingAfterTransportFailure || error == null) {
			return false;
		}
		if (error instanceof HttpConnectTimeoutException || error instanceof java.net.ConnectException) {
			return true;
		}
		String message = error.getMessage();
		return message != null && message.contains("HTTP/1.1 header parser received no bytes");
	}

	private Throwable unwrap(Throwable error) {
		Throwable current = error;
		while (current instanceof CompletionException && current.getCause() != null) {
			current = current.getCause();
		}
		return current;
	}

	private JsonNode retryAfterSession404(ObjectNode payload, Integer id) {
		if (headers != null) {
			headers.remove(MCP_SESSION_HEADER);
		}
		return performInitialize()
			.thenCompose(result -> send(payload.deepCopy(), id, true))
			.join();
	}

	private void updateSessionHeader(HttpResponse<?> response) {
		if (response == null || headers == null) {
			return;
		}
		response.headers()
			.firstValue(MCP_SESSION_HEADER)
			.ifPresent(value -> headers.put(MCP_SESSION_HEADER, value));
	}

	private String deriveWellKnownUrl(URI endpoint) {
		if (endpoint == null || endpoint.getScheme() == null || endpoint.getHost() == null) {
			return null;
		}
		try {
			return new URI(
				endpoint.getScheme(),
				endpoint.getUserInfo(),
				endpoint.getHost(),
				endpoint.getPort(),
				"/.well-known/openid-configuration",
				null,
				null
			)
				.toString();
		}
		catch (URISyntaxException ex) {
			return null;
		}
	}
}
