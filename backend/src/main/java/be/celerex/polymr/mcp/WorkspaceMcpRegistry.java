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

import be.celerex.polymr.lock.LockService;
import be.celerex.polymr.mcp.client.McpClient;
import be.celerex.polymr.mcp.McpAuthRequiredException;
import be.celerex.polymr.model.McpProtocol;
import be.celerex.polymr.model.McpServer;
import be.celerex.polymr.model.McpServerOverride;
import be.celerex.polymr.model.TagCategory;
import be.celerex.polymr.model.TagValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.jboss.logging.Logger;

@ApplicationScoped
public class WorkspaceMcpRegistry {
	private static final Logger LOGGER = Logger.getLogger(WorkspaceMcpRegistry.class);
	private static final String TIMEOUT_SECONDS_KEY = "request_timeout_seconds";
	private static final String TIMEOUT_SECONDS_CAMEL = "requestTimeoutSeconds";
	private static final String TIMEOUT_SECONDS_ALT = "timeout_seconds";
	private static final String TIMEOUT_SECONDS_ALT_CAMEL = "timeoutSeconds";
	private static final long MIN_TIMEOUT_SECONDS = 1;
	@org.eclipse.microprofile.config.inject.ConfigProperty(
	name = "polymr.mcp.request-timeout-seconds",
	defaultValue = "300"
	)
	long defaultTimeoutSeconds;

	@Inject
	EntityManager entityManager;

	@Inject
	McpClientFactory clientFactory;

	@Inject
	McpToolCatalogService toolCatalogService;

	@Inject
	ObjectMapper objectMapper;

	@Inject
	McpCallLogService callLogService;

	@Inject
	McpOAuthService oauthService;

	@Inject
	VirtualMcpService virtualMcpService;

	@Inject
	be.celerex.polymr.scripts.ScriptToolHookService toolHookService;

	@Inject
	LockService lockService;

	@Inject
	McpCallRegistry callRegistry;

	private static final AtomicInteger VIRTUAL_REQUEST_SEQ = new AtomicInteger(1);
	private static final String MCP_UPDATE_CHANNEL = "mcp.server.update";
	private final Map<UUID, Map<SessionKey, SessionHolder>> sessions = new ConcurrentHashMap<>();

	@PostConstruct
	void subscribeToUpdates() {
		lockService.subscribe(MCP_UPDATE_CHANNEL, this::handleClusterUpdate);
	}

	public JsonNode call(
			UUID workspaceId,
			UUID serverId,
			String toolName,
			JsonNode arguments,
			JsonNode meta,
			UUID sessionId,
			UUID userId) {
		return callInternal(workspaceId, serverId, toolName, arguments, meta, sessionId, userId, List.of(), true);
	}

	public JsonNode call(
			UUID workspaceId,
			UUID serverId,
			String toolName,
			JsonNode arguments,
			JsonNode meta,
			UUID sessionId,
			UUID userId,
			List<UUID> tagIds) {
		return callInternal(workspaceId, serverId, toolName, arguments, meta, sessionId, userId, tagIds, true);
	}

	private JsonNode callInternal(
			UUID workspaceId,
			UUID serverId,
			String toolName,
			JsonNode arguments,
			JsonNode meta,
			UUID sessionId,
			UUID userId,
			List<UUID> tagIds,
			boolean retryAuth) {
		ResolvedServerConfig resolved = resolveServerConfig(workspaceId, serverId, tagIds);
		McpServer server = resolved.server;
		if (server != null && server.protocol == McpProtocol.VIRTUAL) {
			JsonNode tools = virtualMcpService.listTools(server);
			toolCatalogService.refreshTools(server, tools);
			Integer requestId = VIRTUAL_REQUEST_SEQ.getAndIncrement();
			UUID connectionId = UUID.randomUUID();
			McpCallLogService.McpCallLogEntry inputEntry = buildVirtualLogEntry(
				server,
				sessionId,
				userId,
				connectionId,
				requestId,
				buildVirtualRequest(requestId, toolName, arguments, meta),
				null,
				resolveScriptCallId(),
				resolveScriptId(),
				resolved.overrideName,
				resolved.overrideTagName
			);
			callLogService.recordInput(inputEntry);
			try {
				JsonNode result = virtualMcpService.callTool(server, toolName, arguments, meta, sessionId, userId);
				McpCallLogService.McpCallLogEntry outputEntry = buildVirtualLogEntry(
					server,
					sessionId,
					userId,
					connectionId,
					requestId,
					buildVirtualResult(requestId, result, null),
					null,
					resolveScriptCallId(),
					resolveScriptId(),
					resolved.overrideName,
					resolved.overrideTagName
				);
				callLogService.recordOutput(outputEntry);
				return result;
			}
			catch (RuntimeException ex) {
				LOGGER.errorf(ex, "Virtual MCP tool call failed server=%s tool=%s", serverId, toolName);
				McpCallLogService.McpCallLogEntry outputEntry = buildVirtualLogEntry(
					server,
					sessionId,
					userId,
					connectionId,
					requestId,
					buildVirtualResult(requestId, null, ex.getMessage()),
					null,
					resolveScriptCallId(),
					resolveScriptId(),
					resolved.overrideName,
					resolved.overrideTagName
				);
				callLogService.recordOutput(outputEntry);
				throw ex;
			}
		}
		JsonNode effectiveArguments = arguments;
		if (!ToolHookContextHolder.isActive()) {
			be.celerex.polymr.scripts.ScriptToolHookService.HookResult beforeHook = toolHookService.applyBeforeHooks(workspaceId, toolName, effectiveArguments, userId);
			if (beforeHook.canceled()) {
				String message = "Tool call canceled by script: " + beforeHook.scriptName();
				recordHookCancellation(server, sessionId, userId, toolName, effectiveArguments, message);
				return objectMapper.getNodeFactory().textNode(message);
			}
			effectiveArguments = beforeHook.input();
		}
		SessionHolder holder;
		try {
			holder = getOrConnect(workspaceId, resolved, userId);
		}
		catch (McpAuthRequiredException authRequired) {
			logFailedToolAttempt(
				server,
				sessionId,
				userId,
				toolName,
				effectiveArguments,
				meta,
				authRequired.getMessage(),
				resolved.overrideName,
				resolved.overrideTagName
			);
			if (retryAuth && forceRefreshAuth(resolved, userId)) {
				return callInternal(workspaceId, serverId, toolName, arguments, meta, sessionId, userId, tagIds, false);
			}
			recordAuthRequired(resolved, authRequired.resourceMetadataUrl());
			throw new RuntimeException("Authentication required for MCP server", authRequired);
		}
		catch (RuntimeException ex) {
			logFailedToolAttempt(
				server,
				sessionId,
				userId,
				toolName,
				effectiveArguments,
				meta,
				ex.getMessage(),
				resolved.overrideName,
				resolved.overrideTagName
			);
			throw ex;
		}
		McpLogContext context = new McpLogContext(sessionId, userId, null, null, resolved.overrideName, resolved.overrideTagName);
		McpLogContextHolder.set(context);
		Duration timeout = resolveRequestTimeout(server);
		holder.beginUse();
		java.util.concurrent.CompletableFuture<JsonNode> future = null;
		McpCallRegistry.McpCancelable cancelable = null;
		try {
			future = holder.session.client().callTool(toolName, effectiveArguments, meta);
			if (sessionId != null) {
				java.util.concurrent.CompletableFuture<JsonNode> activeFuture = future;
				cancelable = () -> {
					activeFuture.cancel(true);
					holder.session.client().cancel();
				};
				callRegistry.register(sessionId, cancelable);
			}
			JsonNode result = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
			if (!ToolHookContextHolder.isActive()) {
				be.celerex.polymr.scripts.ScriptToolHookService.HookResult afterHook = toolHookService.applyAfterHooks(workspaceId, toolName, effectiveArguments, result, userId);
				if (afterHook.canceled()) {
					String message = "Tool call canceled by script: " + afterHook.scriptName();
					recordHookCancellation(server, sessionId, userId, toolName, effectiveArguments, message);
					return objectMapper.getNodeFactory().textNode(message);
				}
				result = afterHook.output();
			}
			return result;
		}
		catch (Exception ex) {
			LOGGER.errorf(ex, "MCP tool call failed server=%s tool=%s", serverId, toolName);
			Throwable cause = ex instanceof java.util.concurrent.ExecutionException ? ex.getCause() : ex;
			if (cause instanceof java.util.concurrent.CancellationException
					|| (cause != null
							&& "MCP request canceled".equals(cause.getMessage()))) {
				LOGGER.infof("MCP tool call canceled server=%s tool=%s session=%s", serverId, toolName, sessionId);
				throw new RuntimeException("MCP request canceled", cause == null ? ex : cause);
			}
			if (cause instanceof java.util.concurrent.TimeoutException) {
				LOGGER.warnf("MCP tool call timed out server=%s tool=%s timeout=%ss", serverId, toolName, timeout.getSeconds());
				throw new RuntimeException("MCP request timed out after " + timeout.getSeconds() + "s", cause);
			}
			if (cause instanceof McpAuthRequiredException authRequired) {
				if (retryAuth && forceRefreshAuth(resolved, userId)) {
					return callInternal(workspaceId, serverId, toolName, arguments, meta, sessionId, userId, tagIds, false);
				}
				recordAuthRequired(resolved, authRequired.resourceMetadataUrl());
				throw new RuntimeException("Authentication required for MCP server", authRequired);
			}
			if (cause instanceof RuntimeException runtime && runtime.getMessage() != null) {
				String message = runtime.getMessage().trim();
				if (message.startsWith("{") && message.endsWith("}")) {
					throw runtime;
				}
			}
			if (cause != null && cause.getMessage() != null) {
				throw new RuntimeException(cause.getMessage(), cause);
			}
			throw new IllegalStateException("MCP tool call failed", ex);
		}
		finally {
			if (sessionId != null && cancelable != null) {
				callRegistry.clear(sessionId, cancelable);
			}
			holder.endUse();
			McpLogContextHolder.clear();
		}
	}

	public JsonNode call(UUID workspaceId, UUID serverId, String toolName, JsonNode arguments, JsonNode meta) {
		return call(workspaceId, serverId, toolName, arguments, meta, null, null, List.of());
	}

	public JsonNode readResource(UUID workspaceId, UUID serverId, String uri) {
		return readResourceInternal(workspaceId, serverId, uri, List.of(), true);
	}

	public JsonNode readResource(UUID workspaceId, UUID serverId, String uri, List<UUID> tagIds) {
		return readResourceInternal(workspaceId, serverId, uri, tagIds, true);
	}

	private JsonNode readResourceInternal(UUID workspaceId, UUID serverId, String uri, List<UUID> tagIds, boolean retryAuth) {
		ResolvedServerConfig resolved = resolveServerConfig(workspaceId, serverId, tagIds);
		McpServer server = resolved.server;
		if (server != null && server.protocol == McpProtocol.VIRTUAL) {
			return virtualMcpService.readResource(server, uri);
		}
		SessionHolder holder;
		try {
			holder = getOrConnect(workspaceId, resolved, null);
		}
		catch (McpAuthRequiredException authRequired) {
			if (retryAuth && forceRefreshAuth(resolved, null)) {
				return readResourceInternal(workspaceId, serverId, uri, tagIds, false);
			}
			recordAuthRequired(resolved, authRequired.resourceMetadataUrl());
			throw new RuntimeException("Authentication required for MCP server", authRequired);
		}
		McpLogContextHolder.set(new McpLogContext(null, null, null, null, resolved.overrideName, resolved.overrideTagName));
		Duration timeout = resolveRequestTimeout(server);
		holder.beginUse();
		try {
			return holder
                .session
				.client()
				.readResource(uri)
				.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (Exception ex) {
			Throwable cause = ex instanceof java.util.concurrent.ExecutionException ? ex.getCause() : ex;
			if (cause instanceof java.util.concurrent.TimeoutException) {
				LOGGER.warnf("MCP resource read timed out server=%s uri=%s timeout=%ss", serverId, uri, timeout.getSeconds());
				throw new RuntimeException("MCP request timed out after " + timeout.getSeconds() + "s", cause);
			}
			if (cause instanceof McpAuthRequiredException authRequired) {
				if (retryAuth && forceRefreshAuth(resolved, null)) {
					return readResourceInternal(workspaceId, serverId, uri, tagIds, false);
				}
				recordAuthRequired(resolved, authRequired.resourceMetadataUrl());
				throw new RuntimeException("Authentication required for MCP server", authRequired);
			}
			if (cause != null && cause.getMessage() != null) {
				throw new RuntimeException(cause.getMessage(), cause);
			}
			throw new IllegalStateException("MCP resource read failed", ex);
		}
		finally {
			holder.endUse();
			McpLogContextHolder.clear();
		}
	}

	public void refreshTools(UUID workspaceId, UUID serverId) {
		McpServer server = entityManager.find(McpServer.class, serverId);
		if (server != null && server.protocol == McpProtocol.VIRTUAL) {
			JsonNode tools = virtualMcpService.listTools(server);
			toolCatalogService.refreshTools(server, tools);
			return;
		}
		ResolvedServerConfig resolved = resolveServerConfig(workspaceId, serverId, List.of());
		SessionHolder holder;
		try {
			holder = getOrConnect(workspaceId, resolved, null);
		}
		catch (McpAuthRequiredException authRequired) {
			recordAuthRequired(resolved, authRequired.resourceMetadataUrl());
			throw new RuntimeException("Authentication required for MCP server", authRequired);
		}
		McpLogContextHolder.set(new McpLogContext(null, null, null, null, resolved.overrideName, resolved.overrideTagName));
		Duration timeout = resolveRequestTimeout(server);
		holder.beginUse();
		try {
			JsonNode tools = holder
                .session
				.client()
				.listTools()
				.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
			McpServer refreshedServer = entityManager.find(McpServer.class, serverId);
			if (refreshedServer == null || !refreshedServer.workspace.id.equals(workspaceId)) {
				throw new IllegalStateException("MCP server not found");
			}
			toolCatalogService.refreshTools(refreshedServer, tools);
		}
		catch (Exception ex) {
			Throwable cause = ex instanceof java.util.concurrent.ExecutionException ? ex.getCause() : ex;
			if (cause instanceof java.util.concurrent.TimeoutException) {
				LOGGER.warnf("MCP list tools timed out server=%s timeout=%ss", serverId, timeout.getSeconds());
				throw new RuntimeException("MCP request timed out after " + timeout.getSeconds() + "s", cause);
			}
			if (cause instanceof McpAuthRequiredException authRequired) {
				recordAuthRequired(resolved, authRequired.resourceMetadataUrl());
			}
			LOGGER.warnf(cause, "Failed to refresh MCP tools for server %s", serverId);
			throw new IllegalStateException("Failed to refresh MCP tools", ex);
		}
		finally {
			holder.endUse();
			McpLogContextHolder.clear();
		}
	}

	private McpCallLogService.McpCallLogEntry buildVirtualLogEntry(
			McpServer server,
			UUID sessionId,
			UUID userId,
			UUID connectionId,
			Integer requestId,
			String payload,
			String status,
			UUID scriptCallId,
			UUID scriptId,
			String overrideName,
			String overrideTagName) {
		UUID tenantId = server == null || server.workspace == null || server.workspace.tenant == null
			? null
			: server.workspace.tenant.id;
		UUID workspaceId = server == null || server.workspace == null ? null : server.workspace.id;
		UUID serverId = server == null ? null : server.id;
		return new McpCallLogService.McpCallLogEntry(
			tenantId,
			workspaceId,
			serverId,
			sessionId,
			userId,
			connectionId,
			requestId,
			"tools/call",
			"VIRTUAL",
			payload,
			status,
			scriptCallId,
			scriptId,
			overrideName,
			overrideTagName
		);
	}

	private void recordHookCancellation(
			McpServer server,
			UUID sessionId,
			UUID userId,
			String toolName,
			JsonNode arguments,
			String message) {
		if (server == null || server.workspace == null || server.workspace.tenant == null) {
			return;
		}
		ObjectNode payload = objectMapper.createObjectNode();
		payload.put("tool_name", toolName);
		if (arguments != null) {
			payload.set("arguments", arguments);
		}
		payload.put("message", message);
		String json;
		try {
			json = objectMapper.writeValueAsString(payload);
		}
		catch (Exception ex) {
			json = message;
		}
		McpCallLogService.McpCallLogEntry entry = new McpCallLogService.McpCallLogEntry(
			server.workspace.tenant.id,
			server.workspace.id,
			server.id,
			sessionId,
			userId,
			UUID.randomUUID(),
			null,
			"tools/call",
			server.protocol == null ? null : server.protocol.name(),
			json,
			"canceled_by_hook",
			resolveScriptCallId(),
			resolveScriptId(),
			null,
			null
		);
		callLogService.recordIntercepted(entry);
	}

	private void logFailedToolAttempt(
			McpServer server,
			UUID sessionId,
			UUID userId,
			String toolName,
			JsonNode arguments,
			JsonNode meta,
			String errorMessage,
			String overrideName,
			String overrideTagName) {
		if (server == null || server.workspace == null) {
			return;
		}
		UUID connectionId = UUID.randomUUID();
		Integer requestId = (int) (System.nanoTime() & 0x7fffffff);
		String requestPayload = buildVirtualRequest(requestId, toolName, arguments, meta);
		McpCallLogService.McpCallLogEntry inputEntry = buildVirtualLogEntry(
			server,
			sessionId,
			userId,
			connectionId,
			requestId,
			requestPayload,
			null,
			resolveScriptCallId(),
			resolveScriptId(),
			overrideName,
			overrideTagName
		);
		callLogService.recordInput(inputEntry);
		String resultPayload = buildVirtualResult(requestId, null, errorMessage == null ? "Failed to connect" : errorMessage);
		McpCallLogService.McpCallLogEntry outputEntry = buildVirtualLogEntry(
			server,
			sessionId,
			userId,
			connectionId,
			requestId,
			resultPayload,
			"failed_connect",
			resolveScriptCallId(),
			resolveScriptId(),
			overrideName,
			overrideTagName
		);
		callLogService.recordOutput(outputEntry);
	}

	private UUID resolveScriptCallId() {
		McpLogContext context = McpLogContextHolder.get();
		return context == null ? null : context.scriptCallId();
	}

	private UUID resolveScriptId() {
		McpLogContext context = McpLogContextHolder.get();
		return context == null ? null : context.scriptId();
	}

	private String buildVirtualRequest(Integer requestId, String toolName, JsonNode arguments, JsonNode meta) {
		ObjectNode request = objectMapper.createObjectNode();
		request.put("jsonrpc", "2.0");
		if (requestId != null) {
			request.put("id", requestId);
		}
		request.put("method", "tools/call");
		ObjectNode params = request.putObject("params");
		params.put("name", toolName);
		if (arguments != null) {
			params.set("arguments", arguments);
		}
		if (meta != null) {
			params.set("_meta", meta);
		}
		try {
			return objectMapper.writeValueAsString(request);
		}
		catch (Exception ex) {
			return request.toString();
		}
	}

	private String buildVirtualResult(Integer requestId, JsonNode result, String errorMessage) {
		ObjectNode response = objectMapper.createObjectNode();
		response.put("jsonrpc", "2.0");
		if (requestId != null) {
			response.put("id", requestId);
		}
		if (errorMessage != null && !errorMessage.isBlank()) {
			ObjectNode error = response.putObject("error");
			error.put("code", -32000);
			error.put("message", errorMessage);
		}
		else if (result != null) {
			response.set("result", result);
		}
		try {
			return objectMapper.writeValueAsString(response);
		}
		catch (Exception ex) {
			return response.toString();
		}
	}

	private Duration resolveRequestTimeout(McpServer server) {
		long seconds = defaultTimeoutSeconds;
		Long override = readTimeoutSeconds(server == null ? null : server.configurationJson);
		if (override != null && override >= MIN_TIMEOUT_SECONDS) {
			seconds = override;
		}
		if (seconds < MIN_TIMEOUT_SECONDS) {
			seconds = MIN_TIMEOUT_SECONDS;
		}
		return Duration.ofSeconds(seconds);
	}

	private static Long readTimeoutSeconds(com.fasterxml.jackson.databind.JsonNode config) {
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

	private static Long readLong(com.fasterxml.jackson.databind.JsonNode node, String field) {
		if (node == null || field == null || field.isBlank()) {
			return null;
		}
		com.fasterxml.jackson.databind.JsonNode value = node.get(field);
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

	private ResolvedServerConfig resolveServerConfig(UUID workspaceId, UUID serverId, List<UUID> tagIds) {
		if (workspaceId == null || serverId == null) {
			throw new IllegalStateException("Missing workspace or server id");
		}
		McpServer server = entityManager.find(McpServer.class, serverId);
		if (server == null || server.workspace == null || !server.workspace.id.equals(workspaceId)) {
			throw new IllegalStateException("MCP server not found");
		}
		McpServerOverride override = resolveOverride(server, tagIds);
		McpServer effectiveServer = applyOverride(server, override);
		String overrideName = override == null ? null : override.logicalName;
		String overrideTagName = override == null || override.tag == null ? null : override.tag.name;
		UUID overrideId = override == null ? null : override.id;
		return new ResolvedServerConfig(server, effectiveServer, overrideId, overrideName, overrideTagName);
	}

	private McpServerOverride resolveOverride(McpServer server, List<UUID> tagIds) {
		if (server == null || tagIds == null || tagIds.isEmpty()) {
			return null;
		}
		if (server.protocol != McpProtocol.SSE && server.protocol != McpProtocol.STREAMABLE_HTTP) {
			return null;
		}
		List<McpServerOverride> overrides = entityManager.createQuery(
				"select o from McpServerOverride o join fetch o.tag t join fetch t.category c "
					+ "where o.mcpServer.id = :serverId and o.tag.id in :tagIds "
					+ "and t.deletedAt is null and c.deletedAt is null",
				McpServerOverride.class
			)
			.setParameter("serverId", server.id)
			.setParameter("tagIds", tagIds)
			.getResultList();
		if (overrides.isEmpty()) {
			return null;
		}
		McpServerOverride best = null;
		for (McpServerOverride candidate : overrides) {
			if (candidate == null) {
				continue;
			}
			if (best == null) {
				best = candidate;
				continue;
			}
			if (compareOverrides(candidate, best) > 0) {
				best = candidate;
			}
		}
		return best;
	}

	private int compareOverrides(McpServerOverride left, McpServerOverride right) {
		TagValue leftTag = left == null ? null : left.tag;
		TagValue rightTag = right == null ? null : right.tag;
		TagCategory leftCategory = leftTag == null ? null : leftTag.category;
		TagCategory rightCategory = rightTag == null ? null : rightTag.category;
		int leftCategoryPriority = leftCategory == null ? 0 : leftCategory.priority;
		int rightCategoryPriority = rightCategory == null ? 0 : rightCategory.priority;
		if (leftCategoryPriority != rightCategoryPriority) {
			return Integer.compare(leftCategoryPriority, rightCategoryPriority);
		}
		UUID leftCategoryId = leftCategory == null ? null : leftCategory.id;
		UUID rightCategoryId = rightCategory == null ? null : rightCategory.id;
		if (leftCategoryId != null && rightCategoryId != null && !leftCategoryId.equals(rightCategoryId)) {
			return leftCategoryId.toString().compareTo(rightCategoryId.toString());
		}
		int leftTagPriority = leftTag == null ? 0 : leftTag.priority;
		int rightTagPriority = rightTag == null ? 0 : rightTag.priority;
		if (leftTagPriority != rightTagPriority) {
			return Integer.compare(leftTagPriority, rightTagPriority);
		}
		UUID leftTagId = leftTag == null ? null : leftTag.id;
		UUID rightTagId = rightTag == null ? null : rightTag.id;
		if (leftTagId == null && rightTagId == null) {
			return 0;
		}
		if (leftTagId == null) {
			return -1;
		}
		if (rightTagId == null) {
			return 1;
		}
		return leftTagId.toString().compareTo(rightTagId.toString());
	}

	private McpServer applyOverride(McpServer server, McpServerOverride override) {
		if (server == null) {
			return null;
		}
		if (override == null) {
			return server;
		}
		McpServer effective = new McpServer();
		effective.id = server.id;
		effective.workspace = server.workspace;
		effective.name = server.name;
		effective.description = server.description;
		effective.protocol = server.protocol;
		effective.framing = server.framing;
		effective.command = server.command;
		effective.cwd = server.cwd;
		effective.httpUrl = server.httpUrl;
		effective.virtualType = server.virtualType;
		effective.headers = server.headers;
		effective.environment = server.environment;
		effective.supportsDynamicConfig = server.supportsDynamicConfig;
		effective.allowPolicy = server.allowPolicy;
		effective.visibility = server.visibility;
		effective.internal = server.internal;
		effective.instructions = server.instructions;
		effective.prompt = server.prompt;
		effective.customInstructions = server.customInstructions;
		effective.configSchema = server.configSchema;
		effective.configurationJson = server.configurationJson;
		effective.oauthEnabled = server.oauthEnabled;
		effective.oauthProvider = server.oauthProvider;
		effective.toolsHash = server.toolsHash;
		effective.sshEnabled = server.sshEnabled;
		effective.sshTunnel = server.sshTunnel;
		if (override.httpUrl != null && !override.httpUrl.isBlank()) {
			effective.httpUrl = override.httpUrl;
		}
		if (override.headers != null) {
			effective.headers = override.headers;
		}
		if (override.sshEnabled != null) {
			effective.sshEnabled = override.sshEnabled;
		}
		if (override.sshTunnel != null) {
			effective.sshTunnel = deepMergeJson(server.sshTunnel, override.sshTunnel);
		}
		if (override.oauthProvider != null) {
			effective.oauthProvider = override.oauthProvider;
		}
		return effective;
	}

	private JsonNode deepMergeJson(JsonNode base, JsonNode override) {
		if (override == null || override.isNull()) {
			return base;
		}
		if (base == null || base.isNull()) {
			return override.deepCopy();
		}
		if (!override.isObject() || !base.isObject()) {
			return override.deepCopy();
		}
		ObjectNode merged = objectMapper.createObjectNode();
		base.fields()
			.forEachRemaining(entry -> merged.set(entry.getKey(), entry.getValue().deepCopy()));
		override.fields()
			.forEachRemaining(
				entry -> {
					String key = entry.getKey();
					JsonNode value = entry.getValue();
					if (value == null || value.isNull()) {
						return;
					}
					JsonNode existing = merged.get(key);
					merged.set(key, deepMergeJson(existing, value));
				}
			);
		return merged;
	}

	private SessionHolder getOrConnect(UUID workspaceId, ResolvedServerConfig resolved, UUID userId) {
		if (workspaceId == null || resolved == null || resolved.server == null) {
			throw new IllegalStateException("Missing workspace or server id");
		}
		Map<SessionKey, SessionHolder> registry = sessions.computeIfAbsent(workspaceId, key -> new ConcurrentHashMap<>());
		AuthResolution auth = resolveAuthToken(resolved.effectiveServer, userId);
		SessionKey sessionKey = new SessionKey(resolved.server.id, auth.userId, resolved.overrideId);
		return registry.compute(
			sessionKey,
			(key, existing) -> {
				String currentToken = auth.token;
				if (existing == null) {
					return connect(workspaceId, resolved, currentToken, auth.userId);
				}
				if (existing.restartRequested()) {
					if (existing.tryShutdownIfIdle()) {
						return connect(workspaceId, resolved, currentToken, auth.userId);
					}
					return existing;
				}
				if (currentToken != null && !currentToken.equals(existing.authToken)) {
					existing.shutdown();
					return connect(workspaceId, resolved, currentToken, auth.userId);
				}
				if (currentToken == null && existing.authToken != null) {
					existing.shutdown();
					return connect(workspaceId, resolved, null, auth.userId);
				}
				return existing;
			}
		);
	}

	private SessionHolder connect(UUID workspaceId, ResolvedServerConfig resolved, String token, UUID authUserId) {
		McpServer server = resolved.server;
		if (server == null || !server.workspace.id.equals(workspaceId)) {
			throw new IllegalStateException("MCP server not found");
		}
		try {
			Map<String, String> headers = buildAuthHeaders(token);
			McpClientFactory.McpClientSession session = clientFactory.connect(resolved.effectiveServer, headers, buildLogSink(server));
			McpLogContextHolder.set(new McpLogContext(null, null, null, null, resolved.overrideName, resolved.overrideTagName));
			Duration timeout = resolveRequestTimeout(server);
			JsonNode initialize = session.client()
				.initialize(server.configurationJson)
				.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
			JsonNode tools = session.client()
				.listTools()
				.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
			McpLogContextHolder.clear();
			oauthService.clearRefreshFailure(
				resolved.effectiveServer,
				oauthService.resolveActiveAuthScopeValue(resolved.effectiveServer),
				authUserId
			);
			toolCatalogService.updateInstructions(server, initialize);
			toolCatalogService.refreshTools(server, tools);
			LOGGER.debugf("Initialized MCP server %s", server.id);
			return new SessionHolder(session, token);
		}
		catch (Exception ex) {
			McpLogContextHolder.clear();
			Throwable cause = ex instanceof java.util.concurrent.ExecutionException ? ex.getCause() : ex;
			if (cause instanceof McpAuthRequiredException authRequired) {
				recordAuthRequired(resolved, authRequired.resourceMetadataUrl());
			}
			throw new IllegalStateException("Failed to connect MCP server", ex);
		}
	}

	private McpCallLogSink buildLogSink(McpServer server) {
		if (server == null || server.workspace == null || server.workspace.tenant == null) {
			return null;
		}
		return new McpCallLogSink(
			objectMapper,
			callLogService,
			server.workspace.tenant.id,
			server.workspace.id,
			server.id,
			server.protocol == null ? null : server.protocol.name(),
			java.util.UUID.randomUUID()
		);
	}

	private Map<String, String> buildAuthHeaders(String token) {
		if (token == null || token.isBlank()) {
			return Map.of();
		}
		return Map.of("Authorization", "Bearer " + token);
	}

	private AuthResolution resolveAuthToken(McpServer server, UUID userId) {
		if (server == null || server.workspace == null) {
			return new AuthResolution(null, null);
		}
		boolean globalAuth = oauthService.isGlobalAuth(server);
		UUID authUserId = globalAuth ? null : userId;
		UUID authScopeValueId = oauthService.resolveActiveAuthScopeValue(server);
		String token = oauthService.resolveAccessToken(server, authScopeValueId, authUserId);
		return new AuthResolution(token, authUserId);
	}

	private boolean forceRefreshAuth(ResolvedServerConfig resolved, UUID userId) {
		McpServer server = resolved == null ? null : resolved.effectiveServer;
		if (server == null || server.workspace == null) {
			return false;
		}
		UUID authScopeValueId = oauthService.resolveActiveAuthScopeValue(server);
		return oauthService.forceRefreshAccessToken(server, authScopeValueId, userId);
	}

	@Transactional
	void recordAuthRequired(ResolvedServerConfig resolved, String resourceMetadataUrl) {
		McpServer server = resolved == null ? null : resolved.effectiveServer;
		if (server == null || server.workspace == null) {
			return;
		}
		oauthService.recordAuthRequired(server, resourceMetadataUrl);
	}

	public void publishServerUpdated(UUID workspaceId, UUID serverId) {
		if (workspaceId == null || serverId == null) {
			return;
		}
		markServerForRestart(workspaceId, serverId);
		ObjectNode payload = objectMapper.createObjectNode();
		payload.put("workspaceId", workspaceId.toString());
		payload.put("serverId", serverId.toString());
		lockService.publish(MCP_UPDATE_CHANNEL, payload.toString());
	}

	private void handleClusterUpdate(String payload) {
		if (payload == null || payload.isBlank()) {
			return;
		}
		try {
			JsonNode message = objectMapper.readTree(payload);
			UUID workspaceId = readUuid(message, "workspaceId");
			UUID serverId = readUuid(message, "serverId");
			if (workspaceId == null || serverId == null) {
				return;
			}
			markServerForRestart(workspaceId, serverId);
		}
		catch (Exception ex) {
			LOGGER.debugf(ex, "Failed to process MCP update payload");
		}
	}

	private void markServerForRestart(UUID workspaceId, UUID serverId) {
		Map<SessionKey, SessionHolder> registry = sessions.get(workspaceId);
		if (registry == null || registry.isEmpty()) {
			return;
		}
		registry.forEach(
			(key, holder) -> {
				if (key != null && serverId.equals(key.serverId()) && holder != null) {
					LOGGER.infof(
						"Flagging MCP server for restart workspace=%s server=%s user=%s override=%s",
						workspaceId,
						serverId,
						key.userId(),
						key.overrideId()
					);
					holder.requestRestart();
					if (holder.tryShutdownIfIdle()) {
						registry.remove(key, holder);
					}
				}
			}
		);
	}

	private UUID readUuid(JsonNode node, String field) {
		if (node == null || field == null || field.isBlank()) {
			return null;
		}
		JsonNode value = node.get(field);
		if (value == null || value.isNull() || !value.isTextual()) {
			return null;
		}
		try {
			return UUID.fromString(value.asText().trim());
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private static final class SessionHolder {
		private final McpClientFactory.McpClientSession session;
		private final String authToken;
		private final AtomicInteger activeUses = new AtomicInteger();
		private final AtomicBoolean restartRequested = new AtomicBoolean(false);

		private SessionHolder(McpClientFactory.McpClientSession session, String authToken) {
			this.session = session;
			this.authToken = authToken;
		}

		private void beginUse() {
			activeUses.incrementAndGet();
		}

		private void endUse() {
			activeUses.updateAndGet(current -> current > 0 ? current - 1 : 0);
		}

		private void requestRestart() {
			restartRequested.set(true);
		}

		private boolean restartRequested() {
			return restartRequested.get();
		}

		private boolean tryShutdownIfIdle() {
			if (!restartRequested() || activeUses.get() > 0) {
				return false;
			}
			if (!restartRequested.compareAndSet(true, false)) {
				return false;
			}
			shutdown();
			return true;
		}

		private void shutdown() {
			if (session != null) {
				session.shutdown();
			}
		}
	}

	private record SessionKey(UUID serverId, UUID userId, UUID overrideId) {}

	private record AuthResolution(String token, UUID userId) {}

	private record ResolvedServerConfig(
			McpServer server,
			McpServer effectiveServer,
			UUID overrideId,
			String overrideName,
			String overrideTagName) {}
}
