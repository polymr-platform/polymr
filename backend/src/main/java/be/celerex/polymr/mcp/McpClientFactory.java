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

import be.celerex.polymr.mcp.client.JsonRpcConnection;
import be.celerex.polymr.mcp.client.McpClient;
import be.celerex.polymr.mcp.client.McpLogSink;
import be.celerex.polymr.mcp.client.SseMcpClient;
import be.celerex.polymr.mcp.client.StdioMcpClient;
import be.celerex.polymr.mcp.client.StreamableHttpMcpClient;
import be.celerex.polymr.model.McpProtocol;
import be.celerex.polymr.model.McpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import be.celerex.polymr.net.SshTunnelSupport;
import be.celerex.polymr.net.SshTunnelSupport.ManagedSshTunnel;
import be.celerex.polymr.net.SshTunnelSupport.SshTunnelConfig;

@ApplicationScoped
public class McpClientFactory {
	private static final Duration PORT_DETECTION_TIMEOUT = Duration.ofSeconds(5);
	private static final Pattern PORT_PATTERN = Pattern.compile("(?i)\\bPORT\\s*=\\s*(\\d{2,5})\\b");
	private static final String TIMEOUT_SECONDS_KEY = "request_timeout_seconds";
	private static final String TIMEOUT_SECONDS_CAMEL = "requestTimeoutSeconds";
	private static final String TIMEOUT_SECONDS_ALT = "timeout_seconds";
	private static final String TIMEOUT_SECONDS_ALT_CAMEL = "timeoutSeconds";
	private static final long MIN_TIMEOUT_SECONDS = 1;
	@Inject
	ObjectMapper mapper;

	@org.eclipse.microprofile.config.inject.ConfigProperty(
	name = "polymr.mcp.request-timeout-seconds",
	defaultValue = "300"
	)
	long defaultTimeoutSeconds;

	public McpClientSession connect(McpServer server, Map<String, String> extraHeaders, McpLogSink logSink) throws Exception {
		McpLogSink effectiveSink = logSink == null ? McpLogSink.noop() : logSink;
		if (server.protocol == null) {
			throw new IllegalStateException("Protocol is required");
		}
		if (server.protocol == McpProtocol.STDIO) {
			if (server.command == null || server.command.isBlank()) {
				throw new IllegalStateException("Command is required for stdio servers");
			}
			Process process = startProcess(server);
			JsonRpcConnection.Framing framing = toFraming(server.framing);
			McpClient client = new StdioMcpClient(mapper, process.getInputStream(), process.getOutputStream(), effectiveSink, framing);
			Duration timeout = resolveRequestTimeout(server);
			return new McpClientSession(new be.celerex.polymr.mcp.client.TimeoutMcpClient(client, timeout), process::destroy);
		}
		if (server.protocol == McpProtocol.SSE) {
			if (server.httpUrl == null || server.httpUrl.isBlank()) {
				throw new IllegalStateException("HTTP URL is required for SSE servers");
			}
			ManagedHttpProcess managed = startHttpProcessIfNeeded(server);
			String httpUrl = resolveHttpUrl(server.httpUrl, managed);
			ManagedSshTunnel sshTunnel = startSshTunnelIfNeeded(server, httpUrl);
			String effectiveUrl = sshTunnel == null ? httpUrl : sshTunnel.forwardedUrl();
			Map<String, String> headers = parseKeyValueLines(server.headers);
			if (extraHeaders != null && !extraHeaders.isEmpty()) {
				headers.putAll(extraHeaders);
			}
			Duration timeout = resolveRequestTimeout(server);
			McpClient client = new SseMcpClient(mapper, effectiveUrl, null, headers, effectiveSink, timeout);
			McpClient wrapped = new be.celerex.polymr.mcp.client.TimeoutMcpClient(client, timeout);
			return new McpClientSession(wrapped, () -> closeManaged(client, managed, sshTunnel));
		}
		if (server.httpUrl == null || server.httpUrl.isBlank()) {
			throw new IllegalStateException("HTTP URL is required for streamable servers");
		}
		ManagedHttpProcess managed = startHttpProcessIfNeeded(server);
		String httpUrl = resolveHttpUrl(server.httpUrl, managed);
		ManagedSshTunnel sshTunnel = startSshTunnelIfNeeded(server, httpUrl);
		String effectiveUrl = sshTunnel == null ? httpUrl : sshTunnel.forwardedUrl();
		Map<String, String> headers = parseKeyValueLines(server.headers);
		if (extraHeaders != null && !extraHeaders.isEmpty()) {
			headers.putAll(extraHeaders);
		}
		Duration timeout = resolveRequestTimeout(server);
		McpClient client = new StreamableHttpMcpClient(mapper, effectiveUrl, headers, effectiveSink, timeout);
		McpClient wrapped = new be.celerex.polymr.mcp.client.TimeoutMcpClient(client, timeout);
		return new McpClientSession(wrapped, () -> closeManaged(client, managed, sshTunnel));
	}

	private Duration resolveRequestTimeout(McpServer server) {
		long seconds = defaultTimeoutSeconds;
		JsonNode config = server == null ? null : server.configurationJson;
		Long override = readTimeoutSeconds(config);
		if (override != null && override >= MIN_TIMEOUT_SECONDS) {
			seconds = override;
		}
		if (seconds < MIN_TIMEOUT_SECONDS) {
			seconds = MIN_TIMEOUT_SECONDS;
		}
		return Duration.ofSeconds(seconds);
	}

	private static Long readTimeoutSeconds(JsonNode config) {
		if (config == null || !config.isObject()) {
			return null;
		}
		Long value = readLong(config, TIMEOUT_SECONDS_KEY);
		if (value != null) {
			return value;
		}
		value = readLong(config, TIMEOUT_SECONDS_CAMEL);
		if (value != null) {
			return value;
		}
		value = readLong(config, TIMEOUT_SECONDS_ALT);
		if (value != null) {
			return value;
		}
		return readLong(config, TIMEOUT_SECONDS_ALT_CAMEL);
	}

	private static Long readLong(JsonNode node, String field) {
		if (node == null || field == null || field.isBlank()) {
			return null;
		}
		JsonNode value = node.get(field);
		if (value == null || value.isNull()) {
			return null;
		}
		if (value.isNumber()) {
			return value.asLong();
		}
		if (value.isTextual()) {
			try {
				return Long.parseLong(value.asText().trim());
			}
			catch (NumberFormatException ex) {
				return null;
			}
		}
		return null;
	}

	private static JsonRpcConnection.Framing toFraming(String framing) {
		if (framing == null) {
			return JsonRpcConnection.Framing.NDJSON;
		}
		String value = framing.trim().toLowerCase();
		if (value.equals("content-length")) {
			return JsonRpcConnection.Framing.CONTENT_LENGTH;
		}
		return JsonRpcConnection.Framing.NDJSON;
	}

	private static Map<String, String> parseKeyValueLines(String raw) {
		Map<String, String> result = new HashMap<>();
		if (raw == null || raw.isBlank()) {
			return result;
		}
		String[] lines = raw.split("\n");
		for (String line : lines) {
			if (line == null) {
				continue;
			}
			String trimmed = line.trim();
			if (trimmed.isBlank()) {
				continue;
			}
			String[] parts = trimmed.split("=", 2);
			if (parts.length != 2) {
				continue;
			}
			String key = parts[0].trim();
			String value = parts[1].trim();
			if (key.isEmpty()) {
				continue;
			}
			result.put(key, value);
		}
		return result;
	}

	private static Process startProcess(McpServer server) throws Exception {
		ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", server.command);
		if (server.cwd != null && !server.cwd.isBlank()) {
			builder.directory(new File(server.cwd));
		}
		Map<String, String> env = parseKeyValueLines(server.environment);
		if (!env.isEmpty()) {
			builder.environment().putAll(env);
		}
		return builder.start();
	}

	private ManagedHttpProcess startHttpProcessIfNeeded(McpServer server) throws Exception {
		if (server.command == null || server.command.isBlank()) {
			return ManagedHttpProcess.none();
		}
		Process process = startProcess(server);
		CompletableFuture<Integer> portFuture = new CompletableFuture<>();
		Thread reader = new Thread(() -> readPortFromStdout(process, portFuture), "mcp-http-port-reader");
		reader.setDaemon(true);
		reader.start();
		Thread stderrReader = new Thread(() -> drainStream(process.getErrorStream()), "mcp-http-stderr-drain");
		stderrReader.setDaemon(true);
		stderrReader.start();
		return new ManagedHttpProcess(process, portFuture);
	}

	private static String resolveHttpUrl(String fallback, ManagedHttpProcess managed) {
		if (managed == null || managed.portFuture == null || managed.process == null) {
			return fallback;
		}
		Integer port = awaitPort(managed.portFuture);
		if (port == null) {
			return fallback;
		}
		String updated = applyPort(fallback, port);
		return updated == null ? fallback : updated;
	}

	private static Integer awaitPort(CompletableFuture<Integer> portFuture) {
		try {
			return portFuture.get(PORT_DETECTION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (Exception ex) {
			return null;
		}
	}

	private static void readPortFromStdout(Process process, CompletableFuture<Integer> portFuture) {
		try (BufferedReader reader = new BufferedReader( new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8) )) {
			String line;
			while ((line = reader.readLine()) != null) {
				Matcher matcher = PORT_PATTERN.matcher(line);
				if (matcher.find()) {
					int port = Integer.parseInt(matcher.group(1));
					portFuture.complete(port);
				}
			}
		}
		catch (Exception ex) {
			portFuture.complete(null);
		}
	}

	private static void drainStream(InputStream stream) {
		if (stream == null) {
			return;
		}
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
			while (reader.readLine() != null) {}
		}
		catch (Exception ignored) {}
	}

	private static String applyPort(String httpUrl, int port) {
		try {
			URI uri = URI.create(httpUrl);
			String host = uri.getHost();
			if (host == null || uri.getScheme() == null) {
				return httpUrl;
			}
			return new URI(uri.getScheme(), uri.getUserInfo(), host, port, uri.getPath(), uri.getQuery(), uri.getFragment())
				.toString();
		}
		catch (Exception ex) {
			return httpUrl;
		}
	}

	private ManagedSshTunnel startSshTunnelIfNeeded(McpServer server, String httpUrl) throws Exception {
		if (server == null || !server.sshEnabled) {
			return null;
		}
		JsonNode ssh = server.sshTunnel;
		if (ssh == null || !ssh.isObject()) {
			throw new IllegalStateException("SSH tunnel configuration is required");
		}
		return SshTunnelSupport.startTunnel(
			new SshTunnelConfig(
				true,
				readText(ssh, "server"),
				readInt(ssh, "port", 22),
				readText(ssh, "username"),
				readText(ssh, "password"),
				readText(ssh, "key_path"),
				readText(ssh, "key")
			),
			httpUrl,
			"mcp-ssh-tunnel"
		);
	}

	private static String readText(JsonNode node, String field) {
		if (node == null || field == null) {
			return null;
		}
		JsonNode value = node.get(field);
		if (value == null || value.isNull() || !value.isValueNode()) {
			return null;
		}
		String text = value.asText();
		return text == null ? null : text.trim();
	}

	private static int readInt(JsonNode node, String field, int fallback) {
		if (node == null || field == null) {
			return fallback;
		}
		JsonNode value = node.get(field);
		if (value == null || value.isNull()) {
			return fallback;
		}
		if (value.canConvertToInt()) {
			return value.asInt();
		}
		if (value.isTextual()) {
			try {
				return Integer.parseInt(value.asText().trim());
			}
			catch (NumberFormatException ignored) {}
		}
		return fallback;
	}

	private static void closeManaged(McpClient client, ManagedHttpProcess managed, ManagedSshTunnel sshTunnel) {
		if (client != null) {
			client.close();
		}
		if (sshTunnel != null) {
			sshTunnel.close();
		}
		if (managed != null && managed.process != null) {
			managed.process.destroy();
		}
	}

	private record ManagedHttpProcess(Process process, CompletableFuture<Integer> portFuture) {
		private static ManagedHttpProcess none() {
			return new ManagedHttpProcess(null, null);
		}
	}

	public record McpClientSession(McpClient client, Runnable close) {
		public void shutdown() {
			if (close != null) {
				close.run();
			}
		}
	}
}
