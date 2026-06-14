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

public class ToolHookContext {
	private final ObjectMapper objectMapper;
	private final String toolName;
	private ObjectNode input;
	private JsonNode output;
	private boolean canceled;

	public ToolHookContext(ObjectMapper objectMapper, String toolName, JsonNode input, JsonNode output) {
		this.objectMapper = objectMapper;
		this.toolName = toolName;
		this.input = input instanceof ObjectNode objectNode
			? (ObjectNode) objectNode.deepCopy()
			: objectMapper.createObjectNode();
		this.output = output;
		this.canceled = false;
	}

	public String toolName() {
		return toolName;
	}

	public ObjectNode input() {
		return input;
	}

	public JsonNode output() {
		return output;
	}

	public boolean canceled() {
		return canceled;
	}

	public void cancel() {
		this.canceled = true;
	}

	public void setInputUpdates(JsonNode updates) {
		if (updates == null || !updates.isArray()) {
			return;
		}
		for (JsonNode update : updates) {
			if (update == null || !update.isObject()) {
				continue;
			}
			JsonNode pathNode = update.get("path");
			if (pathNode == null || !pathNode.isTextual()) {
				continue;
			}
			String path = pathNode.asText();
			JsonNode value = update.get("value");
			writePath(input, path, value == null ? objectMapper.nullNode() : value);
		}
	}

	public void patchInput(JsonNode patch) {
		if (patch == null || !patch.isObject()) {
			return;
		}
		applyMergePatch(input, patch);
	}

	public void setOutputUpdates(JsonNode updates) {
		if (updates == null || !updates.isArray()) {
			return;
		}
		ObjectNode target = output instanceof ObjectNode objectNode ? (ObjectNode) objectNode : objectMapper.createObjectNode();
		for (JsonNode update : updates) {
			if (update == null || !update.isObject()) {
				continue;
			}
			JsonNode pathNode = update.get("path");
			if (pathNode == null || !pathNode.isTextual()) {
				continue;
			}
			String path = pathNode.asText();
			JsonNode value = update.get("value");
			writePath(target, path, value == null ? objectMapper.nullNode() : value);
		}
		output = target;
	}

	public void patchOutput(JsonNode patch) {
		if (patch == null || !patch.isObject()) {
			return;
		}
		ObjectNode target = output instanceof ObjectNode objectNode ? (ObjectNode) objectNode : objectMapper.createObjectNode();
		applyMergePatch(target, patch);
		output = target;
	}

	private void writePath(ObjectNode root, String path, JsonNode value) {
		if (root == null || path == null || path.isBlank()) {
			return;
		}
		String normalized = normalizePath(path);
		if (normalized.isBlank()) {
			if (value instanceof ObjectNode objectValue) {
				root.removeAll();
				root.setAll(objectValue);
			}
			return;
		}
		String[] parts = normalized.split("\\.");
		ObjectNode current = root;
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

	private String normalizePath(String path) {
		if (path == null) {
			return "";
		}
		String normalized = path.trim();
		if (normalized.startsWith("input.")) {
			normalized = normalized.substring("input.".length());
		}
		if (normalized.startsWith("output.")) {
			normalized = normalized.substring("output.".length());
		}
		if ("input".equals(normalized) || "output".equals(normalized)) {
			return "";
		}
		return normalized;
	}

	private void applyMergePatch(ObjectNode target, JsonNode patch) {
		if (target == null || patch == null || !patch.isObject()) {
			return;
		}
		patch.fields()
			.forEachRemaining(
				entry -> {
					String field = entry.getKey();
					JsonNode value = entry.getValue();
					if (value == null || value.isNull()) {
						target.remove(field);
						return;
					}
					if (value.isObject()) {
						JsonNode existing = target.get(field);
						if (existing != null && existing.isObject()) {
							applyMergePatch((ObjectNode) existing, value);
						}
						else {
							target.set(field, value.deepCopy());
						}
						return;
					}
					target.set(field, value.deepCopy());
				}
			);
	}
}
