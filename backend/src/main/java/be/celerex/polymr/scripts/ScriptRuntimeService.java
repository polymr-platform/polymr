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

package be.celerex.polymr.scripts;

import com.fasterxml.jackson.databind.JsonNode;
import be.celerex.polymr.model.Script;
import be.celerex.polymr.model.ScriptVersion;
import be.celerex.polymr.model.Workspace;
import be.celerex.polymr.profile.UserExecutionModeService;
import be.celerex.polymr.storage.AttachmentLinkService;
import be.celerex.polymr.storage.PublicBlobLink;
import be.celerex.polymr.storage.PublicWorkspaceBlobStore;
import be.celerex.polymr.storage.StoredBlob;
import be.celerex.polymr.storage.WorkspaceBlobStore;
import be.celerex.polymr.util.SlugSupport;
import be.celerex.polymr.model.ScriptCallLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ScriptRuntimeService {
	private static final Logger LOGGER = Logger.getLogger(ScriptRuntimeService.class);
	@Inject
	ObjectMapper objectMapper;

	@Inject
	ScriptToolService toolService;

	@Inject
	WorkspaceBlobStore blobStore;

	@Inject
	AttachmentLinkService attachmentLinkService;

	@Inject
	ScriptCallLogService callLogService;

	@Inject
	jakarta.persistence.EntityManager entityManager;

	@Inject
	UserExecutionModeService userExecutionModeService;

	public JsonNode run(
			UUID workspaceId,
			UUID userId,
			String source,
			JsonNode input,
			JsonNode inputSchema,
			JsonNode outputSchema) {
		return runInternal(null, workspaceId, userId, null, source, input, inputSchema, outputSchema);
	}

	public JsonNode runScript(
			Script script,
			UUID userId,
			UUID sessionId,
			JsonNode input) {
		ScriptVersion version = userExecutionModeService.resolveScriptVersion(script, userId);
		if (script == null
				|| version == null
				|| version.sourceGroovy == null
				|| version.sourceGroovy.isBlank()) {
			throw new WebApplicationException("Script not available", Response.Status.CONFLICT);
		}
		return runScriptWithSource(script, userId, sessionId, version.sourceGroovy, input, version.inputSchema, version.outputSchema);
	}

	public JsonNode runScriptWithSource(
			Script script,
			UUID userId,
			UUID sessionId,
			String source,
			JsonNode input,
			JsonNode inputSchema,
			JsonNode outputSchema) {
		return runInternal(script, script.workspace.id, userId, sessionId, source, input, inputSchema, outputSchema);
	}

	private JsonNode runInternal(
			Script script,
			UUID workspaceId,
			UUID userId,
			UUID sessionId,
			String source,
			JsonNode input,
			JsonNode inputSchema,
			JsonNode outputSchema) {
		if (source == null || source.isBlank()) {
			throw new WebApplicationException("Script source is empty", Response.Status.CONFLICT);
		}
		JsonNode inputNode = input == null ? objectMapper.createObjectNode() : input;
		inputNode = coerceJsonText(inputNode);
		inputNode = unwrapInput(inputNode);
		inputNode = sanitizeOptionalNulls(inputSchema, inputNode);
		UUID logId = null;
		be.celerex.polymr.mcp.McpLogContext previous = be.celerex.polymr.mcp.McpLogContextHolder.get();
		if (script != null && script.workspace != null && script.workspace.tenant != null) {
			String inputPayload = safeJson(inputNode);
			ScriptCallLog log = callLogService.recordStart(script.workspace.tenant.id, script.workspace.id, script.id, userId, sessionId, inputPayload);
			logId = log == null ? null : log.id;
			be.celerex.polymr.mcp.McpLogContextHolder.set(new be.celerex.polymr.mcp.McpLogContext(sessionId, userId, logId, script.id, null, null));
		}
		try {
			validateSchema(inputSchema, inputNode, "Input");
			Binding binding = new Binding();
			Map<String, Object> inputMap = toMap(inputNode);
			binding.setProperty("input", inputMap);
			binding.setProperty("inputJson", inputNode);
			binding.setProperty("inputMap", inputMap);
			binding.setProperty("api", new ScriptApi(workspaceId, userId));
			GroovyShell shell = new GroovyShell(binding);
			Object result = shell.evaluate(source);
			JsonNode outputNode = normalizeOutput(result);
			validateSchema(outputSchema, outputNode, "Output");
			if (logId != null) {
				callLogService.recordSuccess(logId, safeJson(outputNode));
			}
			return outputNode;
		}
		catch (Exception ex) {
			if (logId != null) {
				callLogService.recordFailure(logId, buildFailureDetails(ex));
			}
			LOGGER.errorf(
				ex,
				"Script execution failed scriptId=%s workspaceId=%s userId=%s sessionId=%s logId=%s",
				script == null ? null : script.id,
				workspaceId,
				userId,
				sessionId,
				logId
			);
			if (ex instanceof RuntimeException runtimeException) {
				throw runtimeException;
			}
			throw new RuntimeException(ex);
		}
		finally {
			if (previous != null) {
				be.celerex.polymr.mcp.McpLogContextHolder.set(previous);
			}
			else {
				be.celerex.polymr.mcp.McpLogContextHolder.clear();
			}
		}
	}

	private String buildFailureDetails(Throwable throwable) {
		if (throwable == null) {
			return null;
		}
		java.io.StringWriter writer = new java.io.StringWriter();
		java.io.PrintWriter printWriter = new java.io.PrintWriter(writer);
		throwable.printStackTrace(printWriter);
		printWriter.flush();
		return writer.toString();
	}

	private String safeJson(JsonNode node) {
		try {
			return node == null ? null : objectMapper.writeValueAsString(node);
		}
		catch (Exception ex) {
			return null;
		}
	}

	private void validateSchema(JsonNode schemaNode, JsonNode payload, String label) {
		if (schemaNode == null || schemaNode.isNull()) {
			throw new WebApplicationException(label + " schema is required", Response.Status.BAD_REQUEST);
		}
		JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
		JsonSchema schema = factory.getSchema(schemaNode);
		Set<ValidationMessage> errors = schema.validate(payload == null ? objectMapper.createObjectNode() : payload);
		if (errors == null || errors.isEmpty()) {
			return;
		}
		String message = errors.stream().findFirst().map(ValidationMessage::getMessage).orElse("Invalid schema");
		throw new WebApplicationException(label + " validation failed: " + message, Response.Status.BAD_REQUEST);
	}

	private JsonNode sanitizeOptionalNulls(JsonNode schemaNode, JsonNode payload) {
		if (schemaNode == null || schemaNode.isNull() || payload == null || payload.isNull()) {
			return payload;
		}
		if (!payload.isObject()) {
			return payload;
		}
		JsonNode schemaType = schemaNode.get("type");
		if (schemaType != null && schemaType.isTextual() && !"object".equalsIgnoreCase(schemaType.asText())) {
			return payload;
		}
		JsonNode properties = schemaNode.get("properties");
		if (properties == null || !properties.isObject()) {
			return payload;
		}
		java.util.Set<String> required = new java.util.HashSet<>();
		JsonNode requiredNode = schemaNode.get("required");
		if (requiredNode != null && requiredNode.isArray()) {
			requiredNode.forEach(entry -> {
				if (entry != null && entry.isTextual()) {
					required.add(entry.asText());
				}
			});
		}
		ObjectNode sanitized = ((ObjectNode) payload).deepCopy();
		return sanitizeOptionalNullsObject(properties, required, sanitized);
	}

	private JsonNode sanitizeOptionalNullsObject(JsonNode properties, java.util.Set<String> required, ObjectNode payload) {
		java.util.Iterator<String> names = payload.fieldNames();
		java.util.List<String> fields = new java.util.ArrayList<>();
		while (names.hasNext()) {
			fields.add(names.next());
		}
		for (String field : fields) {
			JsonNode value = payload.get(field);
			JsonNode fieldSchema = properties.get(field);
			if (value == null) {
				continue;
			}
			if (value.isNull() && !required.contains(field) && !allowsNull(fieldSchema)) {
				payload.remove(field);
				continue;
			}
			if (value.isObject() && fieldSchema != null) {
				JsonNode nestedType = fieldSchema.get("type");
				JsonNode nestedProperties = fieldSchema.get("properties");
				if ((nestedType == null || !nestedType.isTextual() || "object".equalsIgnoreCase(nestedType.asText()))
						&& nestedProperties != null
						&& nestedProperties.isObject()) {
					java.util.Set<String> nestedRequired = new java.util.HashSet<>();
					JsonNode nestedRequiredNode = fieldSchema.get("required");
					if (nestedRequiredNode != null && nestedRequiredNode.isArray()) {
						nestedRequiredNode.forEach(entry -> {
							if (entry != null && entry.isTextual()) {
								nestedRequired.add(entry.asText());
							}
						});
					}
					sanitizeOptionalNullsObject(nestedProperties, nestedRequired, (ObjectNode) value);
				}
			}
		}
		return payload;
	}

	private boolean allowsNull(JsonNode schemaNode) {
		if (schemaNode == null || schemaNode.isNull()) {
			return false;
		}
		JsonNode typeNode = schemaNode.get("type");
		if (typeNode == null) {
			return false;
		}
		if (typeNode.isTextual()) {
			return "null".equalsIgnoreCase(typeNode.asText());
		}
		if (typeNode.isArray()) {
			for (JsonNode entry : typeNode) {
				if (entry != null && entry.isTextual() && "null".equalsIgnoreCase(entry.asText())) {
					return true;
				}
			}
		}
		return false;
	}

	private JsonNode normalizeOutput(Object output) {
		if (output == null) {
			return objectMapper.createObjectNode();
		}
		if (output instanceof JsonNode node) {
			return node;
		}
		return objectMapper.valueToTree(normalizeGroovyValue(output));
	}

	private JsonNode coerceJsonText(JsonNode node) {
		if (node == null || !node.isTextual()) {
			return node;
		}
		String text = node.textValue();
		if (text == null) {
			return node;
		}
		String trimmed = text.trim();
		if (trimmed.isEmpty()) {
			return node;
		}
		if ((!trimmed.startsWith("{") || !trimmed.endsWith("}"))
				&& (!trimmed.startsWith("[") || !trimmed.endsWith("]"))) {
			return node;
		}
		try {
			return objectMapper.readTree(trimmed);
		}
		catch (Exception ex) {
			return node;
		}
	}

	private JsonNode unwrapInput(JsonNode inputNode) {
		if (inputNode == null || !inputNode.isObject()) {
			return inputNode;
		}
		JsonNode nested = inputNode.get("input");
		if (nested != null && nested.isObject() && inputNode.size() == 1) {
			return nested;
		}
		return inputNode;
	}

	private Map<String, Object> toMap(JsonNode node) {
		if (node == null || node.isNull()) {
			return Map.of();
		}
		return objectMapper.convertValue(node, Map.class);
	}

	class ScriptApi {
		private final UUID workspaceId;
		private final UUID userId;

		ScriptApi(UUID workspaceId, UUID userId) {
			this.workspaceId = workspaceId;
			this.userId = userId;
		}

		public Object callTool(Object tool) {
			return callTool(tool, null, null);
		}

		public Object callTool(Object tool, Object args) {
			return callTool(tool, args, null);
		}

		public Object callTool(Object tool, Object args, Object options) {
			String toolName = null;
			JsonNode arguments = null;
			JsonNode opts = null;
			if (tool instanceof Map<?, ?> map) {
				Object name = map.get("tool");
				if (name == null) {
					name = map.get("tool_name");
				}
				if (name == null) {
					name = map.get("service");
				}
				toolName = name == null ? null : name.toString();
				Object rawArgs = map.get("arguments");
				arguments = rawArgs == null ? null : objectMapper.valueToTree(normalizeGroovyValue(rawArgs));
				Object rawOptions = map.get("options");
				opts = rawOptions == null ? null : objectMapper.valueToTree(normalizeGroovyValue(rawOptions));
			}
			if (toolName == null && tool != null) {
				toolName = tool.toString();
			}
			if (arguments == null && args != null) {
				arguments = objectMapper.valueToTree(normalizeGroovyValue(args));
			}
			if (opts == null && options != null) {
				opts = objectMapper.valueToTree(normalizeGroovyValue(options));
			}
			if (toolName == null || toolName.isBlank()) {
				throw new IllegalArgumentException("Tool is required");
			}
			JsonNode response = toolService.callTool(workspaceId, userId, toolName, arguments, opts);
			if (response == null || response.isNull()) {
				return null;
			}
			if (shouldReturnRawResponse(opts)) {
				return objectMapper.convertValue(response, Object.class);
			}
			JsonNode structuredContent = response.get("structuredContent");
			if (structuredContent == null || structuredContent.isNull()) {
				return null;
			}
			return objectMapper.convertValue(structuredContent, Object.class);
		}

		public Object createSession(Object payload) {
			return callTool("createSession", payload, null);
		}

		public Object createSession(Object title, Object context) {
			if (title == null || title.toString().trim().isEmpty()) {
				throw new IllegalArgumentException("Title is required");
			}
			Map<String, Object> payload = new java.util.LinkedHashMap<>();
			payload.put("title", title.toString());
			if (context != null) {
				payload.put("context", context);
			}
			return callTool("createSession", payload, null);
		}

		public Object createSession(Object title, Object context, Object participants) {
			if (title == null || title.toString().trim().isEmpty()) {
				throw new IllegalArgumentException("Title is required");
			}
			Map<String, Object> payload = new java.util.LinkedHashMap<>();
			payload.put("title", title.toString());
			if (context != null) {
				payload.put("context", context);
			}
			if (participants != null) {
				payload.put("participant_ids", participants);
			}
			return callTool("createSession", payload, null);
		}

		public Object callScript(Object slug, Object input) {
			if (slug == null || slug.toString().trim().isEmpty()) {
				throw new IllegalArgumentException("Script slug is required");
			}
			UUID workspaceIdValue = workspaceId;
			if (workspaceIdValue == null) {
				throw new IllegalArgumentException("Workspace is required");
			}
			String slugValue = normalizeScriptIdentifier(slug);
			Script script = entityManager.createQuery(
					"select s from Script s where s.workspace.id = :workspaceId and "
						+ "lower(s.slug) = :slug and s.disabled = false",
					Script.class
				)
				.setParameter("workspaceId", workspaceIdValue)
				.setParameter("slug", slugValue)
				.setMaxResults(1)
				.getResultStream()
				.findFirst()
				.orElse(null);
			ScriptVersion version = userExecutionModeService.resolveScriptVersion(script, userId);
			if (script == null
					|| version == null
					|| version.sourceGroovy == null
					|| version.sourceGroovy.isBlank()) {
				throw new IllegalArgumentException("Script not available");
			}
			JsonNode inputNode = input == null
				? objectMapper.createObjectNode()
				: objectMapper.valueToTree(normalizeGroovyValue(input));
			JsonNode output = ScriptRuntimeService.this.runScript(script, userId, null, inputNode);
			return output == null || output.isNull() ? null : objectMapper.convertValue(output, Object.class);
		}

		public Object uploadAttachment(Object fileOrBlob) {
			return uploadAttachment(fileOrBlob, null);
		}

		public Object uploadAttachment(Object fileOrBlob, Object options) {
			byte[] bytes = extractAttachmentBytes(fileOrBlob);
			if (bytes.length == 0) {
				throw new IllegalArgumentException("Attachment file is required");
			}
			if (workspaceId == null) {
				throw new IllegalArgumentException("Workspace is required");
			}
			String mimeType = resolveAttachmentMimeType(fileOrBlob, options);
			StoredBlob stored = blobStore.store(workspaceId, bytes, mimeType);
			return buildAttachmentResponse(workspaceId, stored, resolveAttachmentFilename(options));
		}

		public Object createAttachmentUrl(Object blobUri) {
			return createAttachmentUrl(blobUri, null);
		}

		public Object createAttachmentUrl(Object blobUri, Object options) {
			BlobReference reference = parseBlobReference(blobUri == null ? null : blobUri.toString());
			if (reference == null || !reference.workspaceId().equals(workspaceId)) {
				throw new IllegalArgumentException("blobUri must be in the form blob:/workspaceId/hash");
			}
			StoredBlob stored = blobStore.load(reference.workspaceId(), reference.hash())
				.orElseThrow(() -> new IllegalArgumentException("Attachment not found"));
			return buildAttachmentResponse(reference.workspaceId(), stored, resolveAttachmentFilename(options));
		}

		private boolean shouldReturnRawResponse(JsonNode options) {
			if (options == null) {
				return false;
			}
			JsonNode raw = options.get("raw");
			return raw != null && raw.asBoolean(false);
		}

		private String normalizeScriptIdentifier(Object identifier) {
			if (identifier == null) {
				return null;
			}
			String value = identifier.toString().trim();
			if (value.isEmpty()) {
				return null;
			}
			if (value.indexOf('/') >= 0 || value.toLowerCase().endsWith(".groovy")) {
				return SlugSupport.slugFromPath(value).toLowerCase();
			}
			return value.toLowerCase();
		}

		private Map<String, Object> buildAttachmentResponse(UUID workspaceIdValue, StoredBlob stored, String filename) {
			Workspace workspace = entityManager.find(Workspace.class, workspaceIdValue);
			if (workspace == null || workspace.tenant == null || workspace.tenant.id == null) {
				throw new IllegalArgumentException("Workspace not found");
			}
			PublicWorkspaceBlobStore publicStore = blobStore instanceof PublicWorkspaceBlobStore candidate ? candidate : null;
			PublicBlobLink link = attachmentLinkService.resolveUserLink(workspace.tenant.id, workspaceIdValue, stored.hash(), publicStore)
				.orElseThrow(() -> new IllegalStateException("Unable to create attachment link"));
			Map<String, Object> response = new java.util.LinkedHashMap<>();
			response.put("blobUri", "blob:/" + workspaceIdValue + "/" + stored.hash());
			response.put("hash", stored.hash());
			response.put("filename", filename == null ? "" : filename);
			response.put("mimeType", stored.mimeType());
			response.put("sizeBytes", stored.byteSize());
			response.put("url", link.uri().toString());
			response.put("expiresAt", link.expiresAt() == null ? null : link.expiresAt().toString());
			return response;
		}

		private byte[] extractAttachmentBytes(Object fileOrBlob) {
			if (fileOrBlob instanceof InputStream stream) {
				return readAttachmentStream(stream);
			}
			if (fileOrBlob instanceof byte[] bytes) {
				return bytes;
			}
			throw new IllegalArgumentException("Attachment file must be byte[] or InputStream");
		}

		private byte[] readAttachmentStream(InputStream stream) {
			try {
				return stream.readAllBytes();
			}
			catch (IOException exception) {
				throw new IllegalArgumentException("Unable to read attachment stream", exception);
			}
		}

		private String resolveAttachmentMimeType(Object fileOrBlob, Object options) {
			String optionMimeType = readAttachmentString(options, "mimeType", "mime_type");
			if (optionMimeType != null && !optionMimeType.isBlank()) {
				return optionMimeType;
			}
			return "application/octet-stream";
		}

		private String resolveAttachmentFilename(Object options) {
			String optionFilename = readAttachmentString(options, "filename");
			if (optionFilename != null) {
				return optionFilename;
			}
			return "";
		}

		private String readAttachmentString(Object value, String...keys) {
			Object normalized = normalizeGroovyValue(value);
			if (!(normalized instanceof Map<?, ?> map)) {
				return null;
			}
			for (String key : keys) {
				Object entry = map.get(key);
				if (entry != null) {
					return entry.toString();
				}
			}
			return null;
		}

		private BlobReference parseBlobReference(String blobUri) {
			if (blobUri == null || blobUri.isBlank() || !blobUri.startsWith("blob:/")) {
				return null;
			}
			String value = blobUri.substring("blob:/".length());
			int slash = value.indexOf('/');
			if (slash <= 0 || slash == value.length() - 1) {
				return null;
			}
			try {
				return new BlobReference(UUID.fromString(value.substring(0, slash)), value.substring(slash + 1));
			}
			catch (IllegalArgumentException exception) {
				return null;
			}
		}
	}

	private record BlobReference(UUID workspaceId, String hash) {}

	private Object normalizeGroovyValue(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof groovy.lang.GString gstring) {
			return gstring.toString();
		}
		if (value instanceof Map<?, ?> map) {
			Map<Object, Object> normalized = new java.util.LinkedHashMap<>();
			map.forEach((key, entry) -> normalized.put(key, normalizeGroovyValue(entry)));
			return normalized;
		}
		if (value instanceof java.util.List<?> list) {
			java.util.List<Object> normalized = new java.util.ArrayList<>();
			for (Object entry : list) {
				normalized.add(normalizeGroovyValue(entry));
			}
			return normalized;
		}
		if (value.getClass().isArray()) {
			int length = java.lang.reflect.Array.getLength(value);
			java.util.List<Object> normalized = new java.util.ArrayList<>();
			for (int i = 0; i < length; i += 1) {
				normalized.add(normalizeGroovyValue(java.lang.reflect.Array.get(value, i)));
			}
			return normalized;
		}
		return value;
	}
}
