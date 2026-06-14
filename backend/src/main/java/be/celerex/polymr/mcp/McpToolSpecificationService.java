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
import be.celerex.polymr.model.McpServerTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonAnyOfSchema;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNullSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class McpToolSpecificationService {
	@Inject
	EntityManager entityManager;

	@Inject
	ObjectMapper objectMapper;

	public List<ToolSpecification> toolSpecifications(List<UUID> serverIds) {
		if (serverIds == null || serverIds.isEmpty()) {
			return List.of();
		}
		List<McpServerTool> tools = entityManager.createQuery(
				"select t from McpServerTool t where t.mcpServer.id in :serverIds and "
					+ "t.deleted = false and t.disabled = false",
				McpServerTool.class
			)
			.setParameter("serverIds", serverIds)
			.getResultList();
		return toolSpecificationsForTools(tools);
	}

	public List<ToolSpecification> toolSpecificationsForTools(List<McpServerTool> tools) {
		if (tools == null || tools.isEmpty()) {
			return List.of();
		}
		List<ToolSpecification> specs = new ArrayList<>();
		for (McpServerTool tool : tools) {
			if (tool == null) {
				continue;
			}
			String name = toolName(tool);
			JsonObjectSchema schema = inputSchema(tool.inputSchema);
			ToolSpecification spec = ToolSpecification.builder()
				.name(name)
				.description(tool.description)
				.parameters(schema)
				.build();
			specs.add(spec);
		}
		return specs;
	}

	public String toolName(McpServerTool tool) {
		if (tool == null) {
			return null;
		}
		return applyToolNamePrefix(tool.mcpServer, baseToolName(tool));
	}

	public boolean matchesToolName(McpServerTool tool, String requestedName) {
		if (tool == null || requestedName == null || requestedName.isBlank()) {
			return false;
		}
		String normalizedRequestedName = requestedName.trim();
		String effectiveName = toolName(tool);
		return effectiveName != null && normalizedRequestedName.equalsIgnoreCase(effectiveName);
	}

	private String baseToolName(McpServerTool tool) {
		if (tool == null) {
			return null;
		}
		if (tool.toolAlias != null && !tool.toolAlias.isBlank()) {
			return tool.toolAlias.trim();
		}
		return tool.toolName;
	}

	private String applyToolNamePrefix(McpServer server, String toolName) {
		if (toolName == null) {
			return null;
		}
		if (server == null || server.toolNamePrefix == null || server.toolNamePrefix.isBlank()) {
			return toolName;
		}
		return server.toolNamePrefix.trim() + toolName;
	}

	private JsonObjectSchema inputSchema(JsonNode schema) {
		if (schema == null || schema.isNull()) {
			return JsonObjectSchema.builder().additionalProperties(true).build();
		}
		if (!schema.isObject()) {
			return JsonObjectSchema.builder().additionalProperties(true).build();
		}
		JsonObjectSchema parsed = parseObjectSchema(schema);
		return parsed == null ? JsonObjectSchema.builder().additionalProperties(true).build() : parsed;
	}

	private JsonObjectSchema parseObjectSchema(JsonNode schema) {
		if (schema == null || !schema.isObject()) {
			return null;
		}
		String type = schema.path("type").asText(null);
		boolean looksLikeObject = "object".equals(type) || schema.has("properties") || schema.has("required");
		if (!looksLikeObject) {
			return null;
		}
		JsonObjectSchema.Builder builder = JsonObjectSchema.builder();
		builder.description(schema.path("description").asText(null));

		JsonNode properties = schema.get("properties");
		if (properties != null && properties.isObject()) {
			properties.fields()
				.forEachRemaining(
					entry -> {
						JsonSchemaElement element = parseSchemaElement(entry.getValue());
						if (element != null) {
							builder.addProperty(entry.getKey(), element);
						}
					}
				);
		}

		List<String> required = readStringArray(schema.get("required"));
		if (!required.isEmpty()) {
			builder.required(required);
		}

		JsonNode additionalProperties = schema.get("additionalProperties");
		if (additionalProperties != null && additionalProperties.isBoolean()) {
			builder.additionalProperties(additionalProperties.asBoolean());
		}
		else if (additionalProperties != null && additionalProperties.isObject()) {
			builder.additionalProperties(true);
		}

		JsonNode definitions = schema.has("$defs") ? schema.get("$defs") : schema.get("definitions");
		if (definitions != null && definitions.isObject()) {
			Map<String, JsonSchemaElement> definitionMap = new java.util.LinkedHashMap<>();
			definitions.fields()
				.forEachRemaining(
					entry -> {
						JsonSchemaElement element = parseSchemaElement(entry.getValue());
						if (element != null) {
							definitionMap.put(entry.getKey(), element);
						}
					}
				);
			if (!definitionMap.isEmpty()) {
				builder.definitions(definitionMap);
			}
		}

		return builder.build();
	}

	private JsonSchemaElement parseSchemaElement(JsonNode schema) {
		if (schema == null || schema.isNull()) {
			return new JsonNullSchema();
		}
		if (!schema.isObject()) {
			return JsonStringSchema.builder().build();
		}
		String description = schema.path("description").asText(null);
		JsonNode anyOf = schema.get("anyOf");
		if (anyOf != null && anyOf.isArray()) {
			return JsonAnyOfSchema.builder()
				.description(description)
				.anyOf(anyOfElements(anyOf))
				.build();
		}
		JsonNode oneOf = schema.get("oneOf");
		if (oneOf != null && oneOf.isArray()) {
			return JsonAnyOfSchema.builder()
				.description(description)
				.anyOf(anyOfElements(oneOf))
				.build();
		}
		JsonNode enumNode = schema.get("enum");
		if (enumNode != null && enumNode.isArray()) {
			List<String> values = new java.util.ArrayList<>();
			enumNode.forEach(value -> values.add(value.asText()));
			return JsonEnumSchema.builder().enumValues(values).description(description).build();
		}
		JsonNode typeNode = schema.get("type");
		if (typeNode != null && typeNode.isArray()) {
			return JsonAnyOfSchema.builder()
				.description(description)
				.anyOf(typesToElements(typeNode, schema))
				.build();
		}
		String type = typeNode == null ? null : typeNode.asText(null);
		if (type == null && (schema.has("properties") || schema.has("required"))) {
			JsonObjectSchema objectSchema = parseObjectSchema(schema);
			return objectSchema == null ? JsonStringSchema.builder().description(description).build() : objectSchema;
		}
		if ("string".equals(type)) {
			return JsonStringSchema.builder().description(description).build();
		}
		if ("integer".equals(type)) {
			return JsonIntegerSchema.builder().description(description).build();
		}
		if ("number".equals(type)) {
			return JsonNumberSchema.builder().description(description).build();
		}
		if ("boolean".equals(type)) {
			return JsonBooleanSchema.builder().description(description).build();
		}
		if ("array".equals(type)) {
			JsonSchemaElement items = parseSchemaElement(schema.get("items"));
			if (items == null) {
				items = JsonStringSchema.builder().build();
			}
			return JsonArraySchema.builder().description(description).items(items).build();
		}
		if ("object".equals(type)) {
			JsonObjectSchema objectSchema = parseObjectSchema(schema);
			return objectSchema == null ? JsonStringSchema.builder().description(description).build() : objectSchema;
		}
		if ("null".equals(type)) {
			return new JsonNullSchema();
		}
		return JsonStringSchema.builder().description(description).build();
	}

	private List<JsonSchemaElement> anyOfElements(JsonNode array) {
		List<JsonSchemaElement> elements = new java.util.ArrayList<>();
		array.forEach(
			entry -> {
				JsonSchemaElement element = parseSchemaElement(entry);
				if (element != null) {
					elements.add(element);
				}
			}
		);
		return elements.isEmpty() ? List.of(JsonStringSchema.builder().build()) : elements;
	}

	private List<JsonSchemaElement> typesToElements(JsonNode types, JsonNode schema) {
		List<JsonSchemaElement> elements = new java.util.ArrayList<>();
		types.forEach(
			entry -> {
				if (!entry.isTextual()) {
					return;
				}
				ObjectNode typed = objectMapper.createObjectNode();
				typed.put("type", entry.asText());
				if (schema.has("description")) {
					typed.set("description", schema.get("description"));
				}
				if (schema.has("properties")) {
					typed.set("properties", schema.get("properties"));
				}
				if (schema.has("required")) {
					typed.set("required", schema.get("required"));
				}
				if (schema.has("items")) {
					typed.set("items", schema.get("items"));
				}
				JsonSchemaElement element = parseSchemaElement(typed);
				if (element != null) {
					elements.add(element);
				}
			}
		);
		return elements.isEmpty() ? List.of(JsonStringSchema.builder().build()) : elements;
	}

	private List<String> readStringArray(JsonNode node) {
		if (node == null || node.isNull() || !node.isArray()) {
			return List.of();
		}
		List<String> values = new java.util.ArrayList<>();
		node.forEach(entry -> {
			if (entry.isTextual()) {
				values.add(entry.asText());
			}
		});
		return values;
	}
}
