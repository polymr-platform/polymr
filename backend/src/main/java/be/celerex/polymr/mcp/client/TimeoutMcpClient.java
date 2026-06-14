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

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

public class TimeoutMcpClient implements McpClient {
	private final McpClient delegate;
	private final Duration timeout;

	public TimeoutMcpClient(McpClient delegate, Duration timeout) {
		this.delegate = delegate;
		this.timeout = timeout == null ? Duration.ofMinutes(5) : timeout;
	}

	@Override
	public CompletableFuture<JsonNode> initialize(JsonNode configuration) {
		return withTimeout(delegate.initialize(configuration));
	}

	@Override
	public CompletableFuture<JsonNode> callTool(String name, JsonNode arguments, JsonNode meta) {
		return withTimeout(delegate.callTool(name, arguments, meta));
	}

	@Override
	public CompletableFuture<JsonNode> listTools() {
		return withTimeout(delegate.listTools());
	}

	@Override
	public CompletableFuture<JsonNode> listResources() {
		return withTimeout(delegate.listResources());
	}

	@Override
	public CompletableFuture<JsonNode> readResource(String uri) {
		return withTimeout(delegate.readResource(uri));
	}

	@Override
	public void cancel() {
		delegate.cancel();
	}

	@Override
	public void close() {
		delegate.close();
	}

	private CompletableFuture<JsonNode> withTimeout(CompletableFuture<JsonNode> future) {
		long timeoutMillis = Math.max(1, timeout.toMillis());
		return future.orTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
			.exceptionally(
				error -> {
					Throwable cause = error instanceof CompletionException ? error.getCause() : error;
					if (cause instanceof java.util.concurrent.TimeoutException) {
						throw new McpTimeoutException("MCP request timed out after " + timeout.getSeconds() + "s");
					}
					if (cause instanceof RuntimeException runtime) {
						throw runtime;
					}
					throw new RuntimeException(cause);
				}
			);
	}
}
