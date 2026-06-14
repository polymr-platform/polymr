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
import java.io.IOException;
import be.celerex.polymr.mcp.McpAuthRequiredException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.jboss.logging.Logger;

public class SseMcpClient implements McpClient {
	private static final Logger LOGGER = Logger.getLogger(SseMcpClient.class);
	private static final String MCP_SESSION_HEADER = "mcp-session-id";
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
	private final Duration requestTimeout;
	private final ObjectMapper mapper;
	private final HttpClient client;
	private final URI sseEndpoint;
	private final Map<String, String> headers;
	private final McpLogSink logSink;
	private final AtomicInteger nextId = new AtomicInteger(1);
	private final ConcurrentHashMap<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
	private final AtomicBoolean streamStarted = new AtomicBoolean(false);
	private final CompletableFuture<URI> messageEndpoint = new CompletableFuture<>();
	private final URI fallbackEndpoint;
	private volatile InputStream activeStream;
	private volatile JsonNode initializeConfiguration;

	public SseMcpClient(
			ObjectMapper mapper,
			String sseUrl,
			String messageUrl,
			Map<String, String> headers,
			McpLogSink logSink,
			Duration requestTimeout) {
		this.mapper = mapper;
		this.client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
		this.sseEndpoint = URI.create(sseUrl);
		this.headers = headers;
		this.logSink = logSink;
		this.requestTimeout = requestTimeout == null ? Duration.ofSeconds(15) : requestTimeout;
		this.fallbackEndpoint = deriveMessageEndpoint(sseUrl, messageUrl);
		if (messageUrl != null && !messageUrl.isBlank()) {
			messageEndpoint.complete(URI.create(messageUrl));
		}
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
		IllegalStateException exception = new IllegalStateException("MCP request canceled");
		pending.values()
			.forEach(future -> future.completeExceptionally(exception));
		pending.clear();
		InputStream stream = activeStream;
		if (stream != null) {
			try {
				stream.close();
			}
			catch (IOException e) {
				logSink.logLine("!! failed to close SSE stream on cancel: " + e.getMessage());
			}
		}
	}

	@Override
	public void close() {
		cancel();
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
		return send(payload, false)
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
		send(payload, false);
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

	private CompletableFuture<JsonNode> send(ObjectNode payload, boolean retryingAfterSession404) {
		return send(payload, retryingAfterSession404, false);
	}

	private CompletableFuture<JsonNode> send(ObjectNode payload, boolean retryingAfterSession404, boolean retryingAfterTransportFailure) {
		ensureStream();
		Integer id = payload.has("id") ? payload.get("id").asInt() : null;
		CompletableFuture<JsonNode> response = new CompletableFuture<>();
		if (id != null) {
			pending.put(id, response);
		}
		try {
			return resolveEndpoint()
				.thenCompose(
					endpoint -> postMessage(endpoint, payload, id, response, retryingAfterSession404, retryingAfterTransportFailure)
				);
		}
		catch (Exception e) {
			if (id != null) {
				pending.remove(id);
			}
			response.completeExceptionally(e);
			return response;
		}
	}

	private CompletableFuture<JsonNode> postMessage(
			URI endpoint,
			ObjectNode payload,
			Integer id,
			CompletableFuture<JsonNode> response,
			boolean retryingAfterSession404,
			boolean retryingAfterTransportFailure) {
		try {
			String json = mapper.writeValueAsString(payload);
			logSink.logOutbound(json);
			HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(endpoint)
				.timeout(requestTimeout)
				.POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
				.header("Content-Type", "application/json");
			if (headers != null) {
				for (Map.Entry<String, String> entry : headers.entrySet()) {
					builder.header(entry.getKey(), entry.getValue());
				}
			}
			return client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
				.handle(
					(httpResponse, error) -> {
						if (error != null) {
							Throwable cause = unwrap(error);
							if (shouldRetryAfterTransportFailure(cause, retryingAfterTransportFailure)) {
								if (id != null) {
									pending.remove(id);
								}
								return send(payload.deepCopy(), retryingAfterSession404, true);
							}
							if (id != null) {
								pending.remove(id);
							}
							response.completeExceptionally(cause == null ? error : cause);
							return response;
						}
						String responseBody = httpResponse.body();
						if (httpResponse.statusCode() >= 400) {
							if (shouldRetryAfterSession404(httpResponse.statusCode(), retryingAfterSession404)) {
								retryAfterSession404(payload, id, response);
								return response;
							}
							if (id != null) {
								pending.remove(id);
							}
							if (httpResponse.statusCode() == 401) {
								String header = httpResponse.headers().firstValue("WWW-Authenticate").orElse(null);
								String resourceMetadata = McpAuthHeaderParser.extractResourceMetadata(header);
								if (resourceMetadata == null || resourceMetadata.isBlank()) {
									resourceMetadata = deriveWellKnownUrl(endpoint);
								}
								if (resourceMetadata != null) {
									response.completeExceptionally(new McpAuthRequiredException(resourceMetadata));
									return response;
								}
							}
							String detail = responseBody == null || responseBody.isBlank() ? "" : ": " + responseBody.trim();
							response.completeExceptionally(new IllegalStateException("HTTP " + httpResponse.statusCode() + " from server" + detail));
						}
						else {
							updateSessionHeader(httpResponse);
						}
						return response;
					}
				)
				.thenCompose(future -> future);
		}
		catch (Exception e) {
			if (id != null) {
				pending.remove(id);
			}
			response.completeExceptionally(e);
			return response;
		}
	}

	private void ensureStream() {
		if (!streamStarted.compareAndSet(false, true)) {
			return;
		}
		HttpRequest.Builder builder = HttpRequest.newBuilder()
			.uri(sseEndpoint)
			.GET()
			.header("Accept", "text/event-stream");
		if (headers != null) {
			for (Map.Entry<String, String> entry : headers.entrySet()) {
				builder.header(entry.getKey(), entry.getValue());
			}
		}
		client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
			.thenAccept(
				response -> {
					if (response.statusCode() >= 400) {
						if (response.statusCode() == 401) {
							String header = response.headers().firstValue("WWW-Authenticate").orElse(null);
							String resourceMetadata = McpAuthHeaderParser.extractResourceMetadata(header);
							if (resourceMetadata == null || resourceMetadata.isBlank()) {
								resourceMetadata = deriveWellKnownUrl(sseEndpoint);
							}
							if (resourceMetadata != null) {
								failAll(new McpAuthRequiredException(resourceMetadata));
								return;
							}
						}
						failAll(new IllegalStateException("HTTP " + response.statusCode() + " from SSE server"));
						return;
					}
					updateSessionHeader(response);
					activeStream = response.body();
					parseSseStream(response.body());
				}
			)
			.exceptionally(error -> {
				failAll(error);
				return null;
			});
	}

	private void parseSseStream(InputStream input) {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
			StringBuilder data = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.startsWith("data:")) {
					data.append(line.substring(5).stripLeading());
					data.append('\n');
				}
				else if (line.isBlank()) {
					String payload = data.toString().trim();
					data.setLength(0);
					if (!payload.isEmpty()) {
						handleEvent(payload);
					}
				}
			}
		}
		catch (Exception e) {
			failAll(e);
		}
		failAll(new IllegalStateException("SSE stream ended"));
	}

	private void handleEvent(String payload) throws Exception {
		String trimmed = payload.trim();
		if (looksLikeUrl(trimmed)) {
			setMessageEndpoint(trimmed);
			return;
		}
		logSink.logInbound(payload);
		JsonNode node = mapper.readTree(payload);
		if (node.has("endpoint") && !messageEndpoint.isDone()) {
			String endpoint = node.get("endpoint").asText(null);
			setMessageEndpoint(endpoint);
			return;
		}
		if (node.has("id")) {
			int id = node.get("id").asInt();
			CompletableFuture<JsonNode> future = pending.remove(id);
			if (future != null) {
				future.complete(node);
			}
		}
	}

	private void failAll(Throwable error) {
		LOGGER.error("SSE MCP client failed", error);
		RuntimeException failure = error instanceof RuntimeException
			? (RuntimeException) error
			: new IllegalStateException("SSE failure", error);
		pending.forEach((id, future) -> future.completeExceptionally(failure));
		pending.clear();
	}

	private CompletableFuture<URI> resolveEndpoint() {
		if (messageEndpoint.isDone() || fallbackEndpoint == null) {
			return messageEndpoint;
		}
		return messageEndpoint.orTimeout(1, TimeUnit.SECONDS)
			.exceptionally(error -> fallbackEndpoint);
	}

	private void setMessageEndpoint(String endpoint) {
		if (endpoint == null || endpoint.isBlank() || messageEndpoint.isDone()) {
			return;
		}
		messageEndpoint.complete(URI.create(endpoint));
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

	private void retryAfterSession404(ObjectNode payload, Integer id, CompletableFuture<JsonNode> response) {
		if (headers != null) {
			headers.remove(MCP_SESSION_HEADER);
		}
		performInitialize()
			.thenCompose(result -> send(payload.deepCopy(), true))
			.whenComplete(
				(result, error) -> {
					if (error != null) {
						if (id != null) {
							pending.remove(id);
						}
						Throwable cause = error instanceof CompletionException ? error.getCause() : error;
						response.completeExceptionally(cause == null ? error : cause);
						return;
					}
					response.complete(result);
				}
			);
	}

	private void updateSessionHeader(HttpResponse<?> response) {
		if (response == null || headers == null) {
			return;
		}
		response.headers()
			.firstValue(MCP_SESSION_HEADER)
			.ifPresent(value -> headers.put(MCP_SESSION_HEADER, value));
	}

	private boolean looksLikeUrl(String value) {
		if (value == null || value.isBlank()) {
			return false;
		}
		String lower = value.toLowerCase();
		return lower.startsWith("http://") || lower.startsWith("https://");
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

	private static URI deriveMessageEndpoint(String sseUrl, String messageUrl) {
		if (messageUrl != null && !messageUrl.isBlank()) {
			return URI.create(messageUrl);
		}
		if (sseUrl == null || sseUrl.isBlank()) {
			return null;
		}
		URI uri = URI.create(sseUrl);
		String path = uri.getPath();
		if (path == null || !path.contains("/sse")) {
			return null;
		}
		String updatedPath = path.replaceFirst("/sse/?$", "/message");
		if (updatedPath.equals(path)) {
			return null;
		}
		String base = uri.getScheme() + "://" + uri.getAuthority() + updatedPath;
		String query = uri.getQuery();
		if (query != null && !query.isBlank()) {
			base = base + "?" + query;
		}
		return URI.create(base);
	}
}
