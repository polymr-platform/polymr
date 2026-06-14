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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class WorkflowStateStoreMemory implements WorkflowStateStore {
	private final ObjectMapper objectMapper;
	private final ObjectNode state;
	private final JsonNode schema;

	public WorkflowStateStoreMemory(ObjectMapper objectMapper, JsonNode initialState, JsonNode schema) {
		this.objectMapper = objectMapper;
		ObjectNode base = objectMapper.createObjectNode();
		if (initialState instanceof ObjectNode objectNode) {
			base.setAll(objectNode);
		}
		this.state = base;
		this.schema = schema;
	}

	@Override
	public ObjectNode getState() {
		return state;
	}

	@Override
	public JsonNode getState(String path) {
		if (path == null || path.isBlank()) {
			return state;
		}
		String normalized = normalizeStatePath(path);
		if (normalized == null || normalized.isBlank()) {
			return state;
		}
		String[] parts = normalized.split("\\.");
		JsonNode current = state;
		for (String part : parts) {
			if (current == null || current.isNull()) {
				return null;
			}
			if (!current.isObject()) {
				return null;
			}
			current = current.get(part);
		}
		return current;
	}

	@Override
	public void setState(String path, JsonNode value) {
		if (path == null || path.isBlank()) {
			return;
		}
		String normalized = normalizeStatePath(path);
		if (normalized == null) {
			return;
		}
		if (normalized.isBlank()) {
			if (value instanceof ObjectNode objectNode) {
				state.removeAll();
				state.setAll(objectNode);
			}
			return;
		}
		String[] parts = normalized.split("\\.");
		ObjectNode current = state;
		for (int i = 0; i < parts.length; i++) {
			String part = parts[i];
			if (part.isBlank()) {
				continue;
			}
			if (i == parts.length - 1) {
				current.set(part, value == null ? objectMapper.nullNode() : value);
				return;
			}
			JsonNode next = current.get(part);
			if (!(next instanceof ObjectNode)) {
				ObjectNode created = objectMapper.createObjectNode();
				current.set(part, created);
				current = created;
			}
			else {
				current = (ObjectNode) next;
			}
		}
	}

	@Override
	public void patchState(JsonNode updates) {
		if (updates == null || !updates.isArray()) {
			return;
		}
		for (JsonNode update : updates) {
			if (update == null || !update.isObject()) {
				continue;
			}
			JsonNode path = update.get("path");
			if (path == null || !path.isTextual()) {
				continue;
			}
			JsonNode value = update.get("value");
			setState(path.asText(), value);
		}
	}

	@Override
	public JsonNode getSchema() {
		return schema;
	}

	private String normalizeStatePath(String path) {
		if (path == null) {
			return null;
		}
		String normalized = path.trim();
		if (normalized.startsWith("state.")) {
			normalized = normalized.substring("state.".length());
		}
		if ("state".equals(normalized)) {
			return "";
		}
		return normalized;
	}
}
