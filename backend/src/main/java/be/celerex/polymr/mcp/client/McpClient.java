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
import java.util.concurrent.CompletableFuture;

public interface McpClient {
	CompletableFuture<JsonNode> initialize(JsonNode configuration);

	CompletableFuture<JsonNode> listTools();

	CompletableFuture<JsonNode> listResources();

	CompletableFuture<JsonNode> callTool(String name, JsonNode arguments, JsonNode meta);

	CompletableFuture<JsonNode> readResource(String uri);

	default void cancel() {
	}

	default void close() {
	}
}
