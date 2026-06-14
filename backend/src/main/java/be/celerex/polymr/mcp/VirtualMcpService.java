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
import be.celerex.polymr.model.Assistant;
import be.celerex.polymr.model.NotificationLog;
import be.celerex.polymr.model.NotificationRecipient;
import be.celerex.polymr.model.NotificationTarget;
import be.celerex.polymr.model.PushSubscription;
import be.celerex.polymr.model.PushWorkspacePreference;
import be.celerex.polymr.model.Session;
import be.celerex.polymr.model.SessionParticipant;
import be.celerex.polymr.model.SessionParticipantRole;
import be.celerex.polymr.model.SessionStatus;
import be.celerex.polymr.model.SessionTagSelection;
import be.celerex.polymr.model.SessionVisibility;
import be.celerex.polymr.model.SessionCanvas;
import be.celerex.polymr.model.SessionEvent;
import be.celerex.polymr.model.TenantMembership;
import be.celerex.polymr.model.User;
import be.celerex.polymr.model.Workspace;
import be.celerex.polymr.model.SfcPage;
import be.celerex.polymr.model.SfcPageVersion;
import be.celerex.polymr.model.TenantAutomationTask;
import be.celerex.polymr.model.Script;
import be.celerex.polymr.model.ScriptVersion;
import be.celerex.polymr.model.McpServerTool;
import be.celerex.polymr.model.WorkflowRun;
import be.celerex.polymr.storage.AttachmentLinkService;
import be.celerex.polymr.storage.PublicBlobLink;
import be.celerex.polymr.storage.PublicWorkspaceBlobStore;
import be.celerex.polymr.util.SlugSupport;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import be.celerex.polymr.modelregistry.provider.AiChatModelProviderRegistry;
import be.celerex.polymr.modelregistry.provider.AiEmbeddingModelDefinition;
import be.celerex.polymr.modelregistry.provider.AiEmbeddingModelProvider;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import be.celerex.polymr.model.WorkflowRunStatus;
import be.celerex.polymr.notifications.PushNotificationService;
import be.celerex.polymr.pages.compiler.PageCompilationRequest;
import be.celerex.polymr.pages.compiler.PageCompilationResult;
import be.celerex.polymr.pages.compiler.PageCompilerService;
import be.celerex.polymr.pages.SfcPageCatalogService;
import be.celerex.polymr.workflow.runtime.ConversationGraphState;
import be.celerex.polymr.session.SessionChatService;
import be.celerex.polymr.session.SessionEventService;
import be.celerex.polymr.session.dto.SessionEventResponse;
import be.celerex.polymr.ws.WorkspaceSocketEvent;
import be.celerex.polymr.ws.WorkspaceSocketManager;
import be.celerex.polymr.assistant.AssistantSlug;
import be.celerex.polymr.automation.PromptService;
import be.celerex.polymr.prompt.PromptTemplateService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.stream.Stream;

@ApplicationScoped
public class VirtualMcpService {
	@ConfigProperty(name = "polymr.worker.scope-inheritance", defaultValue = "host")
	String workerScopeInheritance;

	@ConfigProperty(name = "polymr.pages.compile-on-write", defaultValue = "true")
	boolean compilePagesOnWrite;

	private static final int TITLE_MAX = 80;
	private static final int BODY_MAX = 300;
	private static final String VIRTUAL_TYPE_POLYMR = "polymr";
	private static final String VIRTUAL_TYPE_POLYMR_DESIGN = "polymr_design";
	private static final String VIRTUAL_TYPE_POLYMR_SCRIPT = "polymr_script";
	private static final String VIRTUAL_TYPE_POLYMR_SCRIPTS = "polymr_scripts";
	private static final String VIRTUAL_TYPE_POLYMR_PAGES = "polymr_pages";
	private static final String VIRTUAL_TYPE_POLYMR_CANVAS = "polymr_canvas";
	private static final String VIRTUAL_TYPE_SCRIPT = "script";
	private static final String VIRTUAL_TYPE_POLYMR_WORKFLOW = "polymr_workflow";
	private static final String VIRTUAL_TYPE_POLYMR_WORKER = "polymr_worker";
	private static final String VIRTUAL_TYPE_POLYMR_TOOL_HOOK = "polymr_tool_hook";
	private static final String TOOL_SEND_PUSH = "send_push_notification";
	private static final String TOOL_LIST_TRANSCRIPTS = "list_transcripts";
	private static final String TOOL_GET_TRANSCRIPT = "get_transcript";
	private static final String TOOL_LIST_WORKSPACE_USERS = "list_workspace_users";
	private static final String TOOL_SPAWN_WORKERS = "spawn_workers";
	private static final String TOOL_CREATE_EMBEDDING = "create_embedding";
	private static final String TOOL_CREATE_PUBLIC_LINK_FOR_BLOB = "create_public_link_for_blob";
	private static final String WORKER_SCOPE = "execute:polymr:worker";
	private static final String TOOL_COMPLETE_GOAL = "complete_goal";
	private static final String TOOL_FAIL_GOAL = "fail_goal";
	private static final String WORKER_FEEDBACK_SCOPE = "execute:polymr:worker_feedback";
	public static final String TOOL_ACTIVATE_MCP_SERVER = "activate_tool_for_session";
	private static final String TOOL_EDIT_PAGE = "polymr_edit_page";
	private static final String TOOL_SEARCH_PAGE = "polymr_search_page";
	private static final String TOOL_READ_PAGE = "polymr_read_page";
	private static final String TOOL_WRITE_PAGE = "polymr_write_page";
	private static final String TOOL_GET_PAGE_CONFIG = "polymr_get_page_configuration";
	private static final String TOOL_SET_PAGE_CONFIG = "polymr_set_page_configuration";
	private static final String TOOL_EDIT_PAGE_FS = "edit_polymr_page";
	private static final String TOOL_WRITE_PAGE_FS = "write_polymr_page";
	private static final String TOOL_SEARCH_PAGES = "search_polymr_pages";
	private static final String TOOL_FIND_PAGES = "find_polymr_pages";
	private static final String TOOL_LIST_WORKSPACE_EXTERNAL_FRONTEND_IMPORTS = "list_polymr_page_imports";
	private static final String TOOL_SET_WORKSPACE_EXTERNAL_FRONTEND_IMPORTS = "set_polymr_page_imports";
	private static final String TOOL_READ_PAGE_FS = "read_polymr_page";
	private static final String TOOL_RELEASE_PAGE_FS = "release_polymr_page";
	private static final String TOOL_CREATE_OR_UPDATE_CANVAS = "create_or_update_canvas";
	private static final String TOOL_REFRESH_CANVAS = "refresh_canvas";
	private static final String TOOL_READ_PAGE_METADATA_FS = "read_polymr_page_metadata";
	private static final String TOOL_EDIT_PAGE_METADATA_FS = "edit_polymr_page_metadata";
	private static final String TOOL_GET_API_DEF = "polymr_get_api_definition";
	private static final String TOOL_GET_TOOL_DEF = "polymr_get_service_definition";
	private static final String TOOL_EDIT_SCRIPT = "polymr_edit_script";
	private static final String TOOL_SEARCH_SCRIPT = "polymr_search_script";
	private static final String TOOL_READ_SCRIPT = "polymr_read_script";
	private static final String TOOL_WRITE_SCRIPT = "polymr_write_script";
	private static final String TOOL_GET_SCRIPT_CONFIG = "polymr_get_script_configuration";
	private static final String TOOL_SET_SCRIPT_CONFIG = "polymr_set_script_configuration";
	private static final String TOOL_GET_SCRIPT_API_DEF = "polymr_get_script_api_definition";
	private static final String TOOL_GET_SCRIPT_TOOL_DEF = "polymr_get_script_service_definition";
	private static final String TOOL_GET_SCRIPT_DEFINITION = "polymr_get_script_definition";
	private static final String TOOL_EDIT_SCRIPT_METADATA = "polymr_edit_script_metadata";
	private static final String TOOL_EDIT_SCRIPT_METADATA_FS = "edit_polymr_script_metadata";
	private static final String TOOL_READ_SCRIPT_METADATA_FS = "read_polymr_script_metadata";
	private static final String TOOL_EDIT_SCRIPT_FS = "edit_polymr_script";
	private static final String DIFF_RESOURCE_URI = "ui://polymr/assets/diff.html";
	private static final String DIFF_RESOURCE_PATH = "diff.html";
	private static final String TOOL_WRITE_SCRIPT_FS = "write_polymr_script";
	private static final String TOOL_SEARCH_SCRIPTS = "search_polymr_scripts";
	private static final String TOOL_FIND_SCRIPTS = "find_polymr_scripts";
	private static final String TOOL_READ_SCRIPT_FS = "read_polymr_script";
	private static final String TOOL_RUN_SCRIPT_FS = "run_polymr_script";
	private static final String TOOL_RELEASE_SCRIPT_FS = "release_polymr_script";
	private static final String TOOL_WORKFLOW_STATE_GET = "workflow_state_get";
	private static final String TOOL_WORKFLOW_STATE_SET = "workflow_state_set";
	private static final String TOOL_WORKFLOW_STATE_PATCH = "workflow_state_patch";
	private static final String TOOL_WORKFLOW_STATE_SCHEMA = "workflow_state_schema";
	private static final String TOOL_HOOK_GET_NAME = "tool_hook_get_name";
	private static final String TOOL_HOOK_GET_INPUT = "tool_hook_get_input";
	private static final String TOOL_HOOK_SET_INPUT = "tool_hook_set_input";
	private static final String TOOL_HOOK_PATCH_INPUT = "tool_hook_patch_input";
	private static final String TOOL_HOOK_GET_OUTPUT = "tool_hook_get_output";
	private static final String TOOL_HOOK_SET_OUTPUT = "tool_hook_set_output";
	private static final String TOOL_HOOK_PATCH_OUTPUT = "tool_hook_patch_output";
	private static final String TOOL_HOOK_CANCEL = "tool_hook_cancel";
	private static final int TRANSCRIPT_LIMIT_MAX = 10;
	private static final int SCRIPT_SEARCH_BUFFER_LIMIT = 50;
	private static final int SCRIPT_SEARCH_LIMIT_DEFAULT = 100;
	private static final int SCRIPT_SEARCH_LIMIT_MAX = 200;
	@Inject
	EntityManager entityManager;

	@Inject
	DynamicDialect searchDialect;

	@Inject
	ObjectMapper objectMapper;

	@Inject
	PushNotificationService pushService;

	@Inject
	PageCompilerService pageCompilerService;

	@Inject
	be.celerex.polymr.workflow.WorkflowCheckpointService checkpointService;

	@Inject
	SessionChatService sessionChatService;

	@Inject
	SessionEventService eventService;

	@Inject
	WorkspaceSocketManager socketManager;

	@Inject
	WorkflowMcpSnapshotService snapshotService;

	@Inject
	be.celerex.polymr.pages.DesignApiDefinitionService apiDefinitionService;

	@Inject
	be.celerex.polymr.scripts.ScriptApiDefinitionService scriptApiDefinitionService;

	@Inject
	be.celerex.polymr.scripts.ScriptRuntimeService scriptRuntimeService;

	@Inject
	PromptService promptService;

	@Inject
	PromptTemplateService promptTemplateService;

	@Inject
	SfcPageCatalogService sfcPageCatalogService;

	@Inject
	AiChatModelProviderRegistry modelProviderRegistry;

	@Inject
	AttachmentLinkService attachmentLinkService;

	public String resolvePrompt(McpServer server, UUID sessionId) {
		if (server == null || server.virtualType == null || server.virtualType.isBlank()) {
			return null;
		}
		if (VIRTUAL_TYPE_POLYMR_SCRIPTS.equalsIgnoreCase(server.virtualType)) {
			return promptService.loadPrompt("virtual-mcp/polymr_scripts");
		}
		if (VIRTUAL_TYPE_POLYMR_PAGES.equalsIgnoreCase(server.virtualType)) {
			return promptService.loadPrompt("virtual-mcp/polymr_pages");
		}
		if (VIRTUAL_TYPE_POLYMR_CANVAS.equalsIgnoreCase(server.virtualType)) {
			return renderCanvasPrompt(server, sessionId);
		}
		return null;
	}

	private String renderCanvasPrompt(McpServer server, UUID sessionId) {
		String template = promptService.loadPrompt("virtual-mcp/polymr_canvas");
		if (template == null || template.isBlank()) {
			return template;
		}
		Map<String, Object> context = new HashMap<>();
		boolean hasPages = hasVirtualServerInSession(sessionId, VIRTUAL_TYPE_POLYMR_PAGES);
		context.put("hasPages", hasPages);
		context.put("hasScripts", hasVirtualServerInSession(sessionId, VIRTUAL_TYPE_POLYMR_SCRIPTS));
		context.put(
			"workspaceId",
			server.workspace == null || server.workspace.id == null ? null : server.workspace.id.toString()
		);
		context.put("externalImports", hasPages ? List.of() : workspaceExternalFrontendImportEntries(server.workspace));
		context.put(
			"componentUsageGuide",
			hasPages
				? ""
				: sfcPageCatalogService.buildComponentUsageGuide(server.workspace == null ? null : server.workspace.id)
		);
		return promptTemplateService.renderContent(template, context);
	}

	private List<Map<String, String>> workspaceExternalFrontendImportEntries(Workspace workspace) {
		if (workspace == null
				|| workspace.externalFrontendImports == null
				|| !workspace.externalFrontendImports.isArray()) {
			return List.of();
		}
		List<Map<String, String>> imports = new ArrayList<>();
		for (JsonNode entry : workspace.externalFrontendImports) {
			if (entry == null || !entry.isObject()) {
				continue;
			}
			String specifier = entry.path("specifier").asText("").trim();
			String globalName = entry.path("global_name").asText("").trim();
			String sourceUrl = entry.path("source_url").asText("").trim();
			if (specifier.isBlank() || globalName.isBlank() || sourceUrl.isBlank()) {
				continue;
			}
			Map<String, String> item = new HashMap<>();
			item.put("specifier", specifier);
			item.put("global_name", globalName);
			item.put("source_url", sourceUrl);
			JsonNode cssUrls = entry.get("css_urls");
			if (cssUrls != null && cssUrls.isArray()) {
				item.put("css_urls", cssUrls.toString());
			}
			imports.add(item);
		}
		return imports;
	}

	private boolean hasVirtualServerInSession(UUID sessionId, String virtualType) {
		if (sessionId == null || virtualType == null || virtualType.isBlank()) {
			return false;
		}
		Session session = entityManager.find(Session.class, sessionId);
		if (session == null || session.workspace == null) {
			return false;
		}
		WorkflowRun run = resolveWorkflowRun(sessionId);
		JsonNode definitionJson = loadWorkflowDefinitionJson(run);
		ObjectNode snapshot = resolveSnapshotFromRun(run, definitionJson, session);
		if (snapshot == null) {
			snapshot = snapshotService.buildSnapshot(definitionJson, session);
		}
		if (snapshot == null) {
			return false;
		}
		JsonNode mcp = snapshot.get("mcp");
		if (!(mcp instanceof ObjectNode mcpNode)) {
			return false;
		}
		JsonNode servers = mcpNode.get("servers");
		if (!(servers instanceof ArrayNode serverArray) || serverArray.isEmpty()) {
			return false;
		}
		List<UUID> serverIds = new ArrayList<>();
		for (JsonNode entry : serverArray) {
			if (entry != null && entry.isTextual()) {
				try {
					serverIds.add(UUID.fromString(entry.asText()));
				}
				catch (IllegalArgumentException ignored) {}
			}
		}
		if (serverIds.isEmpty()) {
			return false;
		}
		return !entityManager.createQuery(
				"select s.id from McpServer s where s.workspace.id = :workspaceId and s.id "
					+ "in :serverIds and lower(s.virtualType) = :virtualType",
				UUID.class
			)
			.setParameter("workspaceId", session.workspace.id)
			.setParameter("serverIds", serverIds)
			.setParameter("virtualType", virtualType.toLowerCase())
			.setMaxResults(1)
			.getResultList()
			.isEmpty();
	}

	public JsonNode listTools(McpServer server) {
		ArrayNode tools = objectMapper.createArrayNode();
		if (server == null || server.virtualType == null) {
			ObjectNode payload = objectMapper.createObjectNode();
			payload.set("tools", tools);
			return payload;
		}
		if (VIRTUAL_TYPE_POLYMR.equalsIgnoreCase(server.virtualType)) {
			tools.add(buildPushTool());
			tools.add(buildTranscriptListTool());
			tools.add(buildTranscriptGetTool());
			tools.add(buildWorkspaceUsersTool());
			tools.add(buildActivateMcpServerTool());
			tools.add(buildSpawnWorkersTool());
			tools.add(buildCreateEmbeddingTool());
			// Prefer page/script-specific attachment helpers over exposing the generic blob link tool to the LLM.
		}
		else if (VIRTUAL_TYPE_POLYMR_DESIGN.equalsIgnoreCase(server.virtualType)) {
			tools.add(buildEditPageTool());
			tools.add(buildSearchPageTool());
			tools.add(buildReadPageTool());
			tools.add(buildWritePageTool());
			tools.add(buildGetPageConfigTool());
			tools.add(buildSetPageConfigTool());
			tools.add(buildGetApiDefinitionTool());
			tools.add(buildGetToolDefinitionTool());
		}
		else if (VIRTUAL_TYPE_POLYMR_SCRIPT.equalsIgnoreCase(server.virtualType)) {
			tools.add(buildEditScriptTool());
			tools.add(buildSearchScriptTool());
			tools.add(buildReadScriptTool());
			tools.add(buildWriteScriptTool());
			tools.add(buildGetScriptConfigTool());
			tools.add(buildSetScriptConfigTool());
			tools.add(buildEditScriptMetadataTool());
			tools.add(buildGetScriptApiDefinitionTool());
			tools.add(buildGetScriptToolDefinitionTool());
			tools.add(buildGetScriptDefinitionTool());
		}
		else if (VIRTUAL_TYPE_POLYMR_SCRIPTS.equalsIgnoreCase(server.virtualType)) {
			tools.add(buildEditScriptFsTool());
			tools.add(buildEditScriptMetadataFsTool());
			tools.add(buildReadScriptMetadataFsTool());
			tools.add(buildWriteScriptFsTool());
			tools.add(buildSearchScriptsTool());
			tools.add(buildFindScriptsTool());
			tools.add(buildReadScriptFsTool());
			tools.add(buildRunScriptFsTool());
			tools.add(buildReleaseScriptFsTool());
		}
		else if (VIRTUAL_TYPE_POLYMR_PAGES.equalsIgnoreCase(server.virtualType)) {
			tools.add(buildEditPageFsTool());
			tools.add(buildEditPageMetadataFsTool());
			tools.add(buildReadPageMetadataFsTool());
			tools.add(buildWritePageFsTool());
			tools.add(buildSearchPagesTool());
			tools.add(buildFindPagesTool());
			tools.add(buildReadPageFsTool());
			tools.add(buildReleasePageFsTool());
			tools.add(buildListWorkspaceExternalFrontendImportsTool());
			tools.add(buildSetWorkspaceExternalFrontendImportsTool());
		}
		else if (VIRTUAL_TYPE_POLYMR_CANVAS.equalsIgnoreCase(server.virtualType)) {
			tools.add(buildCreateOrUpdateCanvasTool());
			tools.add(buildRefreshCanvasTool());
		}
		else if (VIRTUAL_TYPE_SCRIPT.equalsIgnoreCase(server.virtualType)) {
			tools.addAll(buildScriptTools(server));
		}
		else if (VIRTUAL_TYPE_POLYMR_WORKFLOW.equalsIgnoreCase(server.virtualType)) {
			tools.add(buildWorkflowStateGetTool());
			tools.add(buildWorkflowStateSetTool());
			tools.add(buildWorkflowStatePatchTool());
			tools.add(buildWorkflowStateSchemaTool());
		}
		else if (VIRTUAL_TYPE_POLYMR_WORKER.equalsIgnoreCase(server.virtualType)) {
			tools.add(buildCompleteGoalTool());
			tools.add(buildFailGoalTool());
		}
		else if (VIRTUAL_TYPE_POLYMR_TOOL_HOOK.equalsIgnoreCase(server.virtualType)) {
			tools.add(buildToolHookGetNameTool());
			tools.add(buildToolHookGetInputTool());
			tools.add(buildToolHookSetInputTool());
			tools.add(buildToolHookPatchInputTool());
			tools.add(buildToolHookGetOutputTool());
			tools.add(buildToolHookSetOutputTool());
			tools.add(buildToolHookPatchOutputTool());
			tools.add(buildToolHookCancelTool());
		}
		ObjectNode payload = objectMapper.createObjectNode();
		payload.set("tools", tools);
		return payload;
	}

	public JsonNode listResources(McpServer server) {
		ObjectNode payload = objectMapper.createObjectNode();
		ArrayNode resources = payload.putArray("resources");
		if (supportsDiffResource(server)) {
			ObjectNode resource = resources.addObject();
			resource.put("uri", DIFF_RESOURCE_URI);
			resource.put("name", "Diff viewer");
			resource.put("mimeType", "text/html;profile=mcp-app");
		}
		return payload;
	}

	public JsonNode readResource(McpServer server, String uri) {
		if (!supportsDiffResource(server) || !DIFF_RESOURCE_URI.equals(uri)) {
			throw new RuntimeException("Resource not found");
		}
		ObjectNode payload = objectMapper.createObjectNode();
		ArrayNode contents = payload.putArray("contents");
		ObjectNode content = contents.addObject();
		content.put("uri", DIFF_RESOURCE_URI);
		content.put("mimeType", "text/html;profile=mcp-app");
		content.put("text", readClasspathResource(DIFF_RESOURCE_PATH));
		return payload;
	}

	@Transactional
	public JsonNode callTool(
			McpServer server,
			String toolName,
			JsonNode arguments,
			JsonNode meta,
			UUID sessionId,
			UUID userId) {
		if (server == null || server.virtualType == null) {
			return error("Virtual MCP server not configured");
		}
		if (VIRTUAL_TYPE_POLYMR.equalsIgnoreCase(server.virtualType)) {
			if (TOOL_CREATE_EMBEDDING.equals(toolName)) {
				return callCreateEmbeddingTool(arguments);
			}
			return callPolymrTool(server, toolName, arguments, sessionId, userId);
		}
		if (VIRTUAL_TYPE_POLYMR_DESIGN.equalsIgnoreCase(server.virtualType)) {
			return callDesignTool(server, toolName, arguments, sessionId);
		}
		if (VIRTUAL_TYPE_POLYMR_SCRIPT.equalsIgnoreCase(server.virtualType)) {
			return callScriptTool(server, toolName, arguments, meta, sessionId);
		}
		if (VIRTUAL_TYPE_POLYMR_SCRIPTS.equalsIgnoreCase(server.virtualType)) {
			return callScriptsTool(toolName, arguments, meta, sessionId, userId);
		}
		if (VIRTUAL_TYPE_POLYMR_PAGES.equalsIgnoreCase(server.virtualType)) {
			return callPagesTool(toolName, arguments, meta, sessionId, userId);
		}
		if (VIRTUAL_TYPE_POLYMR_CANVAS.equalsIgnoreCase(server.virtualType)) {
			return callCanvasTool(toolName, arguments, sessionId, userId);
		}
		if (VIRTUAL_TYPE_SCRIPT.equalsIgnoreCase(server.virtualType)) {
			return callScriptServerTool(server, toolName, arguments, sessionId, userId);
		}
		if (VIRTUAL_TYPE_POLYMR_WORKFLOW.equalsIgnoreCase(server.virtualType)) {
			return callWorkflowStateTool(server, toolName, arguments, sessionId);
		}
		if (VIRTUAL_TYPE_POLYMR_WORKER.equalsIgnoreCase(server.virtualType)) {
			return callWorkerTool(toolName, arguments, sessionId);
		}
		if (VIRTUAL_TYPE_POLYMR_TOOL_HOOK.equalsIgnoreCase(server.virtualType)) {
			return callToolHookTool(toolName, arguments);
		}
		return error("Virtual MCP server not configured");
	}

	private JsonNode callPolymrTool(
			McpServer server,
			String toolName,
			JsonNode arguments,
			UUID sessionId,
			UUID userId) {
		if (TOOL_SEND_PUSH.equals(toolName)) {
			return sendPush(server, arguments, sessionId, userId);
		}
		if (TOOL_LIST_TRANSCRIPTS.equals(toolName)) {
			return listTranscripts(server, arguments);
		}
		if (TOOL_GET_TRANSCRIPT.equals(toolName)) {
			return getTranscript(server, arguments);
		}
		if (TOOL_LIST_WORKSPACE_USERS.equals(toolName)) {
			return listWorkspaceUsers(server);
		}
		if (TOOL_SPAWN_WORKERS.equals(toolName)) {
			return spawnWorkers(server, arguments, sessionId, userId);
		}
		if (TOOL_ACTIVATE_MCP_SERVER.equals(toolName)) {
			return activateMcpServer(server, arguments, sessionId);
		}
		if (TOOL_CREATE_PUBLIC_LINK_FOR_BLOB.equals(toolName)) {
			// Keep the implementation for now, but hide the tool so page/script-specific helpers are used first.
			return error("Tool not available");
		}
		return error("Unknown tool");
	}

	private JsonNode callWorkerTool(String toolName, JsonNode arguments, UUID sessionId) {
		if (TOOL_COMPLETE_GOAL.equals(toolName)) {
			return recordWorkerCompletion(sessionId, "completed", arguments);
		}
		if (TOOL_FAIL_GOAL.equals(toolName)) {
			return recordWorkerCompletion(sessionId, "failed", arguments);
		}
		return error("Unknown tool");
	}

	private JsonNode callDesignTool(McpServer server, String toolName, JsonNode arguments, UUID sessionId) {
		if (TOOL_EDIT_PAGE.equals(toolName)) {
			return editPage(arguments, sessionId);
		}
		if (TOOL_SEARCH_PAGE.equals(toolName)) {
			return searchPage(arguments, sessionId);
		}
		if (TOOL_READ_PAGE.equals(toolName)) {
			return readPage(arguments, sessionId);
		}
		if (TOOL_WRITE_PAGE.equals(toolName)) {
			return writePage(arguments, sessionId);
		}
		if (TOOL_GET_PAGE_CONFIG.equals(toolName)) {
			return getPageConfig(arguments, sessionId);
		}
		if (TOOL_SET_PAGE_CONFIG.equals(toolName)) {
			return setPageConfig(arguments, sessionId);
		}
		if (TOOL_GET_API_DEF.equals(toolName)) {
			return getApiDefinition(arguments);
		}
		if (TOOL_GET_TOOL_DEF.equals(toolName)) {
			return getToolDefinition(arguments, sessionId);
		}
		return error("Unknown tool");
	}

	private JsonNode callScriptTool(McpServer server, String toolName, JsonNode arguments, JsonNode meta, UUID sessionId) {
		if (TOOL_EDIT_SCRIPT.equals(toolName)) {
			return editScript(arguments, sessionId);
		}
		if (TOOL_SEARCH_SCRIPT.equals(toolName)) {
			return searchScript(arguments, sessionId);
		}
		if (TOOL_READ_SCRIPT.equals(toolName)) {
			return readScript(arguments, sessionId);
		}
		if (TOOL_WRITE_SCRIPT.equals(toolName)) {
			return writeScript(arguments, sessionId);
		}
		if (TOOL_GET_SCRIPT_CONFIG.equals(toolName)) {
			return getScriptConfig(arguments, sessionId);
		}
		if (TOOL_SET_SCRIPT_CONFIG.equals(toolName)) {
			return setScriptConfig(arguments, sessionId);
		}
		if (TOOL_EDIT_SCRIPT_METADATA.equals(toolName)) {
			return setScriptConfig(arguments, sessionId);
		}
		if (TOOL_GET_SCRIPT_API_DEF.equals(toolName)) {
			return getScriptApiDefinition(arguments);
		}
		if (TOOL_GET_SCRIPT_TOOL_DEF.equals(toolName)) {
			return getToolDefinition(arguments, sessionId);
		}
		if (TOOL_GET_SCRIPT_DEFINITION.equals(toolName)) {
			return getScriptDefinition(server, arguments, sessionId);
		}
		return error("Unknown tool");
	}

	private JsonNode callScriptsTool(String toolName, JsonNode arguments, JsonNode meta, UUID sessionId, UUID userId) {
		if (TOOL_EDIT_SCRIPT_FS.equals(toolName)) {
			return editScriptFile(arguments, meta, sessionId, userId);
		}
		if (TOOL_EDIT_SCRIPT_METADATA_FS.equals(toolName)) {
			return editScriptMetadataFile(arguments, sessionId, userId);
		}
		if (TOOL_READ_SCRIPT_METADATA_FS.equals(toolName)) {
			return readScriptMetadataFile(arguments, sessionId, userId);
		}
		if (TOOL_WRITE_SCRIPT_FS.equals(toolName)) {
			return writeScriptFile(arguments, meta, sessionId, userId);
		}
		if (TOOL_SEARCH_SCRIPTS.equals(toolName)) {
			return searchScriptFiles(arguments, sessionId, userId);
		}
		if (TOOL_FIND_SCRIPTS.equals(toolName)) {
			return findScriptFiles(arguments, sessionId, userId);
		}
		if (TOOL_READ_SCRIPT_FS.equals(toolName)) {
			return readScriptFile(arguments, sessionId, userId);
		}
		if (TOOL_RUN_SCRIPT_FS.equals(toolName)) {
			return runScriptFile(arguments, sessionId, userId);
		}
		if (TOOL_RELEASE_SCRIPT_FS.equals(toolName)) {
			return releaseScriptFile(arguments, sessionId, userId);
		}
		return error("Unknown tool");
	}

	private JsonNode callPagesTool(String toolName, JsonNode arguments, JsonNode meta, UUID sessionId, UUID userId) {
		if (TOOL_EDIT_PAGE_FS.equals(toolName)) {
			return editPageFile(arguments, meta, sessionId, userId);
		}
		if (TOOL_EDIT_PAGE_METADATA_FS.equals(toolName)) {
			return editPageMetadataFile(arguments, sessionId, userId);
		}
		if (TOOL_READ_PAGE_METADATA_FS.equals(toolName)) {
			return readPageMetadataFile(arguments, sessionId, userId);
		}
		if (TOOL_WRITE_PAGE_FS.equals(toolName)) {
			return writePageFile(arguments, meta, sessionId, userId);
		}
		if (TOOL_SEARCH_PAGES.equals(toolName)) {
			return searchPageFiles(arguments, sessionId, userId);
		}
		if (TOOL_FIND_PAGES.equals(toolName)) {
			return findPageFiles(arguments, sessionId, userId);
		}
		if (TOOL_READ_PAGE_FS.equals(toolName)) {
			return readPageFile(arguments, sessionId, userId);
		}
		if (TOOL_RELEASE_PAGE_FS.equals(toolName)) {
			return releasePageFile(arguments, sessionId, userId);
		}
		if (TOOL_LIST_WORKSPACE_EXTERNAL_FRONTEND_IMPORTS.equals(toolName)) {
			return listWorkspaceExternalFrontendImports(arguments, sessionId, userId);
		}
		if (TOOL_SET_WORKSPACE_EXTERNAL_FRONTEND_IMPORTS.equals(toolName)) {
			return setWorkspaceExternalFrontendImports(arguments, sessionId, userId);
		}
		return error("Unknown tool");
	}

	private JsonNode callWorkflowStateTool(McpServer server, String toolName, JsonNode arguments, UUID sessionId) {
		if (TOOL_WORKFLOW_STATE_GET.equals(toolName)) {
			return getWorkflowState(arguments, sessionId);
		}
		if (TOOL_WORKFLOW_STATE_SET.equals(toolName)) {
			return setWorkflowState(arguments, sessionId);
		}
		if (TOOL_WORKFLOW_STATE_PATCH.equals(toolName)) {
			return patchWorkflowState(arguments, sessionId);
		}
		if (TOOL_WORKFLOW_STATE_SCHEMA.equals(toolName)) {
			return getWorkflowStateSchema(sessionId, server);
		}
		return error("Unknown tool");
	}

	private JsonNode callCanvasTool(String toolName, JsonNode arguments, UUID sessionId, UUID userId) {
		if (TOOL_CREATE_OR_UPDATE_CANVAS.equals(toolName)) {
			return createOrUpdateCanvas(arguments, sessionId, userId);
		}
		if (TOOL_REFRESH_CANVAS.equals(toolName)) {
			return refreshCanvas(arguments, sessionId);
		}
		return error("Unknown tool");
	}

	private JsonNode callToolHookTool(String toolName, JsonNode arguments) {
		ToolHookContext context = ToolHookContextHolder.get();
		if (context == null) {
			return error("Tool hook context not available");
		}
		if (TOOL_HOOK_GET_NAME.equals(toolName)) {
			ObjectNode result = objectMapper.createObjectNode();
			result.put("name", context.toolName());
			return result;
		}
		if (TOOL_HOOK_GET_INPUT.equals(toolName)) {
			ObjectNode result = objectMapper.createObjectNode();
			result.set("input", context.input());
			return result;
		}
		if (TOOL_HOOK_SET_INPUT.equals(toolName)) {
			JsonNode updates = arguments == null ? null : arguments.get("updates");
			context.setInputUpdates(updates);
			ObjectNode result = objectMapper.createObjectNode();
			result.set("input", context.input());
			return result;
		}
		if (TOOL_HOOK_PATCH_INPUT.equals(toolName)) {
			context.patchInput(arguments);
			ObjectNode result = objectMapper.createObjectNode();
			result.set("input", context.input());
			return result;
		}
		if (TOOL_HOOK_GET_OUTPUT.equals(toolName)) {
			ObjectNode result = objectMapper.createObjectNode();
			JsonNode output = context.output();
			if (output == null) {
				result.putNull("output");
			}
			else {
				result.set("output", output);
			}
			return result;
		}
		if (TOOL_HOOK_SET_OUTPUT.equals(toolName)) {
			JsonNode updates = arguments == null ? null : arguments.get("updates");
			context.setOutputUpdates(updates);
			ObjectNode result = objectMapper.createObjectNode();
			result.set("output", context.output());
			return result;
		}
		if (TOOL_HOOK_PATCH_OUTPUT.equals(toolName)) {
			context.patchOutput(arguments);
			ObjectNode result = objectMapper.createObjectNode();
			result.set("output", context.output());
			return result;
		}
		if (TOOL_HOOK_CANCEL.equals(toolName)) {
			context.cancel();
			ObjectNode result = objectMapper.createObjectNode();
			result.put("canceled", true);
			return result;
		}
		return error("Unknown tool");
	}

	@Transactional
	JsonNode activateMcpServer(McpServer server, JsonNode arguments, UUID sessionId) {
		if (sessionId == null) {
			return error("Session is required");
		}
		String serverName = readText(arguments, "server_name");
		if (serverName == null || serverName.isBlank()) {
			return error("Server name is required");
		}
		Session session = entityManager.find(Session.class, sessionId);
		if (session == null) {
			return error("Session not found");
		}
		String normalized = serverName.trim().toLowerCase();
		List<McpServer> matches = entityManager.createQuery(
				"select s from McpServer s where s.workspace.id = :workspaceId and lower(s.name) = :name",
				McpServer.class
			)
			.setParameter("workspaceId", session.workspace.id)
			.setParameter("name", normalized)
			.getResultList();
		if (matches.isEmpty()) {
			return error("MCP server not found");
		}
		if (matches.size() > 1) {
			return error("Multiple MCP servers match that name");
		}
		McpServer target = matches.get(0);
		if (target.visibility == be.celerex.polymr.model.McpServerVisibility.VISIBLE) {
			return error("Server is already included by default");
		}
		var run = checkpointService.requireRun(session);
		ObjectNode checkpoint = run.checkpointJson instanceof ObjectNode node ? node : objectMapper.createObjectNode();
		ObjectNode snapshot = checkpoint.get(be.celerex.polymr.workflow.runtime.ConversationGraphState.MCP_SNAPSHOT)
            instanceof ObjectNode node
			? node
			: snapshotService.buildSnapshot(run.workflowDefinition, session);
		ObjectNode mcp = snapshot.get("mcp") instanceof ObjectNode node ? node : snapshot.putObject("mcp");
		ArrayNode servers = mcp.get("servers") instanceof ArrayNode arr ? arr : mcp.putArray("servers");
		for (JsonNode entry : servers) {
			if (entry.isTextual() && entry.asText().equals(target.id.toString())) {
				ObjectNode result = objectMapper.createObjectNode();
				putTextContent(result, "Server already active");
				result.put("server_id", target.id.toString());
				result.put("server_name", target.name);
				return result;
			}
		}
		servers.add(target.id.toString());
		checkpoint.set(be.celerex.polymr.workflow.runtime.ConversationGraphState.MCP_SNAPSHOT, snapshot);
		checkpointService.updateProjectionFromCheckpoint(run, state -> checkpoint);
		ObjectNode result = objectMapper.createObjectNode();
		putTextContent(result, "Server activated");
		result.put("server_id", target.id.toString());
		result.put("server_name", target.name);
		return result;
	}

	private JsonNode sendPush(McpServer server, JsonNode arguments, UUID sessionId, UUID userId) {
		String title = readText(arguments, "title");
		String body = readText(arguments, "body");
		String target = readText(arguments, "target");
		String requestedUrl = readText(arguments, "url");
		if (title == null || title.isBlank()) {
			return error("Title is required");
		}
		if (body == null || body.isBlank()) {
			return error("Body is required");
		}
		if (title.length() > TITLE_MAX) {
			return error("Title exceeds maximum length");
		}
		if (body.length() > BODY_MAX) {
			return error("Body exceeds maximum length");
		}
		NotificationTarget resolvedTarget = resolveTarget(target);
		if (resolvedTarget == null) {
			return error("Invalid target");
		}
		if (!pushService.isConfigured()) {
			return error("Push notifications are not configured");
		}
		Session session = sessionId == null ? null : entityManager.find(Session.class, sessionId);
		Workspace workspace = server.workspace;
		User initiator = userId == null ? null : entityManager.find(User.class, userId);
		String destination = (requestedUrl == null || requestedUrl.isBlank())
			? (session == null
				? "/workspace/" + workspace.id
				: "/workspace/" + workspace.id + "/sessions/" + session.id)
			: requestedUrl;
		Set<UUID> targetUsers = resolveRecipients(resolvedTarget, workspace, session, initiator);
		if (targetUsers.isEmpty()) {
			return error("No recipients found");
		}
		Map<UUID, PushWorkspacePreference> preferences = loadPreferences(workspace.id, targetUsers);
		Map<UUID, List<PushSubscription>> subscriptions = loadSubscriptions(targetUsers);
		Instant now = Instant.now();
		int eligibleCount = 0;
		int sentCount = 0;
		NotificationLog log = new NotificationLog();
		log.workspace = workspace;
		log.session = session;
		log.initiator = initiator;
		log.target = resolvedTarget;
		log.title = title;
		log.body = body;
		log.destination = destination;
		entityManager.persist(log);
		for (UUID recipientId : targetUsers) {
			User user = entityManager.find(User.class, recipientId);
			if (user == null) {
				continue;
			}
			NotificationRecipient recipient = new NotificationRecipient();
			recipient.log = log;
			recipient.user = user;
			boolean enabled = preferences.get(recipientId) != null && preferences.get(recipientId).enabled;
			if (!enabled) {
				recipient.status = "skipped";
				recipient.detail = "workspace_disabled";
				entityManager.persist(recipient);
				continue;
			}
			if (user.notificationsSnoozedUntil != null && user.notificationsSnoozedUntil.isAfter(now)) {
				recipient.status = "skipped";
				recipient.detail = "snoozed";
				entityManager.persist(recipient);
				continue;
			}
			List<PushSubscription> targets = subscriptions.getOrDefault(recipientId, List.of())
				.stream()
				.filter(sub -> sub.active)
				.toList();
			if (targets.isEmpty()) {
				recipient.status = "skipped";
				recipient.detail = "no_subscription";
				entityManager.persist(recipient);
				continue;
			}
			eligibleCount += 1;
			boolean sentAny = false;
			for (PushSubscription subscription : targets) {
				boolean sent = pushService.send(subscription, title, body, destination);
				sentAny = sentAny || sent;
			}
			if (sentAny) {
				recipient.status = "sent";
				recipient.detail = null;
				sentCount += 1;
			}
			else {
				recipient.status = "failed";
				recipient.detail = "send_failed";
			}
			entityManager.persist(recipient);
		}
		log.eligibleCount = eligibleCount;
		log.sentCount = sentCount;
		ObjectNode result = objectMapper.createObjectNode();
		putTextContent(result, "Sent successfully");
		result.put("sent_count", sentCount);
		result.put("eligible_count", eligibleCount);
		result.put("target", resolvedTarget.name().toLowerCase());
		return result;
	}

	private ObjectNode buildPushTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_SEND_PUSH);
		tool.put("description", "Send a push notification to workspace members or conversation participants.");
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		ArrayNode required = inputSchema.putArray("required");
		required.add("title");
		required.add("body");
		required.add("target");
		ObjectNode properties = inputSchema.putObject("properties");
		properties.putObject("title")
			.put("type", "string")
			.put("maxLength", TITLE_MAX)
			.put("description", "Title (max 80 characters).");
		properties.putObject("body")
			.put("type", "string")
			.put("maxLength", BODY_MAX)
			.put("description", "Message body (max 300 characters).");
		properties.putObject("url")
			.put("type", "string")
			.put("description", "Optional URL to open. Defaults to the originating session.");
		ObjectNode target = properties.putObject("target");
		target.put("type", "string");
		ArrayNode targetEnum = target.putArray("enum");
		targetEnum.add("user");
		targetEnum.add("participants");
		targetEnum.add("workspace");
		target.put(
			"description",
			"Choose who receives the notification. 'user' targets the initiator, "
				+ "'participants' targets everyone linked to the conversation, and 'workspace' targets "
				+ "all workspace members."
		);
		tool.set("inputSchema", inputSchema);
		ObjectNode annotations = tool.putObject("annotations");
		ArrayNode scopes = annotations.putArray("scopes");
		scopes.add("notify:push");
		annotations.put("intentTemplate", "Send a push notification to {target}: {title}");
		return tool;
	}

	private ObjectNode buildTranscriptListTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_LIST_TRANSCRIPTS);
		tool.put("description", "List fully processed transcripts newest first. Optionally filter by title/content.");
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		ObjectNode properties = inputSchema.putObject("properties");
		properties.putObject("query")
			.put("type", "string")
			.put("description", "Optional search query matched against transcript title and content.");
		properties.putObject("limit")
			.put("type", "integer")
			.put("description", "Optional max results (default 5, max 10).");
		properties.putObject("offset")
			.put("type", "integer")
			.put("description", "Optional offset for paging (default 0).");
		tool.set("inputSchema", inputSchema);
		ObjectNode annotations = tool.putObject("annotations");
		ArrayNode scopes = annotations.putArray("scopes");
		scopes.add("read:polymr:transcript");
		annotations.put("intentTemplate", "List transcripts [for: {query}]");
		return tool;
	}

	private ObjectNode buildTranscriptGetTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_GET_TRANSCRIPT);
		tool.put("description", "Fetch a transcript's raw content by id.");
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		ArrayNode required = inputSchema.putArray("required");
		required.add("id");
		ObjectNode properties = inputSchema.putObject("properties");
		properties.putObject("id")
			.put("type", "string")
			.put("description", "Transcript id from search_transcripts.");
		tool.set("inputSchema", inputSchema);
		ObjectNode annotations = tool.putObject("annotations");
		ArrayNode scopes = annotations.putArray("scopes");
		scopes.add("read:polymr:transcript");
		return tool;
	}

	private ObjectNode buildWorkspaceUsersTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_LIST_WORKSPACE_USERS);
		tool.put("description", "List users in this workspace with id, name, email, and avatar URL.");
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		tool.set("inputSchema", inputSchema);
		ObjectNode annotations = tool.putObject("annotations");
		ArrayNode scopes = annotations.putArray("scopes");
		scopes.add("read:polymr:users");
		return tool;
	}

	private ObjectNode buildSpawnWorkersTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_SPAWN_WORKERS);
		tool.put("description", "Spawn one or more workers for parallel tasks.");
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		ArrayNode required = inputSchema.putArray("required");
		required.add("children");
		ObjectNode properties = inputSchema.putObject("properties");
		ObjectNode children = properties.putObject("children");
		children.put("type", "array");
		ObjectNode childItem = children.putObject("items");
		childItem.put("type", "object");
		ArrayNode childRequired = childItem.putArray("required");
		childRequired.add("assistant_name");
		childRequired.add("task");
		ObjectNode childProps = childItem.putObject("properties");
		childProps.putObject("assistant_name")
			.put("type", "string")
			.put("description", "Slug of the worker to use.");
		childProps.putObject("task")
			.put("type", "string")
			.put("description", "Task prompt for the worker.");
		childProps.putObject("title")
			.put("type", "string")
			.put("description", "Short 10-word task title.");
		ObjectNode requestedTools = childProps.putObject("requested_tools");
		requestedTools.put("type", "array");
		requestedTools.putObject("items").put("type", "string");
		tool.set("inputSchema", inputSchema);
		ObjectNode annotations = tool.putObject("annotations");
		ArrayNode scopes = annotations.putArray("scopes");
		scopes.add(WORKER_SCOPE);
		annotations.put("intentTemplate", "Spawn workers for parallel tasks");
		return tool;
	}

	private ObjectNode buildCompleteGoalTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_COMPLETE_GOAL);
		tool.put("description", "Mark the goal as completed with a concise summary.");
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		ArrayNode required = inputSchema.putArray("required");
		required.add("message");
		ObjectNode properties = inputSchema.putObject("properties");
		properties.putObject("message")
			.put("type", "string")
			.put("description", "Concise summary of the completed work.");
		properties.putObject("data")
			.put("type", "object")
			.put("description", "Optional structured output for the host.");
		tool.set("inputSchema", inputSchema);
		ObjectNode annotations = tool.putObject("annotations");
		ArrayNode scopes = annotations.putArray("scopes");
		scopes.add(WORKER_FEEDBACK_SCOPE);
		return tool;
	}

	private ObjectNode buildFailGoalTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_FAIL_GOAL);
		tool.put("description", "Mark the goal as failed with a concise reason.");
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		ArrayNode required = inputSchema.putArray("required");
		required.add("message");
		ObjectNode properties = inputSchema.putObject("properties");
		properties.putObject("message")
			.put("type", "string")
			.put("description", "Reason for failure.");
		properties.putObject("data")
			.put("type", "object")
			.put("description", "Optional structured output for the host.");
		tool.set("inputSchema", inputSchema);
		ObjectNode annotations = tool.putObject("annotations");
		ArrayNode scopes = annotations.putArray("scopes");
		scopes.add(WORKER_FEEDBACK_SCOPE);
		return tool;
	}

	private ObjectNode buildEditPageTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_EDIT_PAGE);
		tool.put("description", "Replace one string with another in the draft page.");
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		ArrayNode required = inputSchema.putArray("required");
		required.add("edits");
		ObjectNode properties = inputSchema.putObject("properties");
		ObjectNode edits = properties.putObject("edits");
		edits.put("type", "array");
		ObjectNode item = edits.putObject("items");
		item.put("type", "object");
		ArrayNode editRequired = item.putArray("required");
		editRequired.add("find");
		editRequired.add("replace");
		ObjectNode editProps = item.putObject("properties");
		editProps.putObject("find").put("type", "string");
		editProps.putObject("replace").put("type", "string");
		tool.set("inputSchema", inputSchema);
		ObjectNode annotations = tool.putObject("annotations");
		ArrayNode scopes = annotations.putArray("scopes");
		scopes.add("write:polymr:page");
		annotations.put("intentTemplate", "Edit the draft page source");
		tool.putObject("_meta").putObject("ui").put("resourceUri", DIFF_RESOURCE_URI);
		return tool;
	}

	private ObjectNode buildSearchPageTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_SEARCH_PAGE);
		tool.put("description", "Search the draft page with a regex pattern.");
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		ArrayNode required = inputSchema.putArray("required");
		required.add("pattern");
		ObjectNode properties = inputSchema.putObject("properties");
		properties.putObject("pattern").put("type", "string");
		properties.putObject("before_context").put("type", "integer");
		properties.putObject("after_context").put("type", "integer");
		tool.set("inputSchema", inputSchema);
		ObjectNode annotations = tool.putObject("annotations");
		ArrayNode scopes = annotations.putArray("scopes");
		scopes.add("read:polymr:page");
		annotations.put("intentTemplate", "Search the draft page source");
		return tool;
	}

	private ObjectNode buildReadPageTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_READ_PAGE);
		tool.put("description", "Read a section of the draft page.");
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		ArrayNode required = inputSchema.putArray("required");
		required.add("start_line");
		required.add("limit");
		ObjectNode properties = inputSchema.putObject("properties");
		properties.putObject("start_line").put("type", "integer");
		properties.putObject("limit").put("type", "integer");
		tool.set("inputSchema", inputSchema);
		ObjectNode annotations = tool.putObject("annotations");
		ArrayNode scopes = annotations.putArray("scopes");
		scopes.add("read:polymr:page");
		annotations.put("intentTemplate", "Read the draft page source");
		return tool;
	}

	private ObjectNode buildWritePageTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_WRITE_PAGE);
		tool.put("description", "Write content to the draft page.");
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		ArrayNode required = inputSchema.putArray("required");
		required.add("content");
		ObjectNode properties = inputSchema.putObject("properties");
		properties.putObject("content").put("type", "string");
		ObjectNode mode = properties.putObject("mode");
		mode.put("type", "string");
		ArrayNode allowed = mode.putArray("enum");
		allowed.add("overwrite");
		allowed.add("append");
		allowed.add("prepend");
		tool.set("inputSchema", inputSchema);
		ObjectNode annotations = tool.putObject("annotations");
		ArrayNode scopes = annotations.putArray("scopes");
		scopes.add("write:polymr:page");
		annotations.put("intentTemplate", "Write the draft page source");
		tool.putObject("_meta").putObject("ui").put("resourceUri", DIFF_RESOURCE_URI);
		return tool;
	}

	private ObjectNode buildGetPageConfigTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_GET_PAGE_CONFIG);
		tool.put("description", "Get page configuration for the current draft page.");
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		ObjectNode properties = inputSchema.putObject("properties");
		tool.set("inputSchema", inputSchema);
		ObjectNode annotations = tool.putObject("annotations");
		ArrayNode scopes = annotations.putArray("scopes");
		scopes.add("read:polymr:page");
		annotations.put("intentTemplate", "Get the draft page configuration");
		return tool;
	}

	private ObjectNode buildSetPageConfigTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_SET_PAGE_CONFIG);
		tool.put("description", "Update page configuration for the current draft page.");
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		ObjectNode properties = inputSchema.putObject("properties");
		properties.putObject("description").put("type", "string");
		properties.putObject("icon_svg")
			.put("type", "string")
			.put("description", "Inline SVG markup (must be a full <svg> element) for the page icon.");
		properties.putObject("route_suffix").put("type", "string");
		properties.putObject("query_params").put("type", "array").putObject("items").put("type", "string");
		properties.putObject("input_params").put("type", "array").putObject("items").put("type", "object");
		properties.putObject("import_allowlist")
			.put("type", "array")
			.putObject("items")
			.put("type", "string");
		tool.set("inputSchema", inputSchema);
		ObjectNode annotations = tool.putObject("annotations");
		ArrayNode scopes = annotations.putArray("scopes");
		scopes.add("write:polymr:page");
		annotations.put("intentTemplate", "Update the draft page configuration");
		return tool;
	}

	private ObjectNode buildEditScriptTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_EDIT_SCRIPT);
		tool.put("description", "Replace one string with another in the draft Groovy script.");
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		ArrayNode required = inputSchema.putArray("required");
		required.add("edits");
		ObjectNode properties = inputSchema.putObject("properties");
		ObjectNode edits = properties.putObject("edits");
		edits.put("type", "array");
		ObjectNode editItem = edits.putObject("items");
		editItem.put("type", "object");
		ArrayNode itemRequired = editItem.putArray("required");
		itemRequired.add("find");
		ObjectNode editProps = editItem.putObject("properties");
		editProps.putObject("find").put("type", "string");
		editProps.putObject("replace").put("type", "string");
		tool.set("inputSchema", inputSchema);
		ObjectNode annotations = tool.putObject("annotations");
		ArrayNode scopes = annotations.putArray("scopes");
		scopes.add("write:polymr:script");
		annotations.put("intentTemplate", "Edit the draft Groovy script source");
		tool.putObject("_meta").putObject("ui").put("resourceUri", DIFF_RESOURCE_URI);
		return tool;
	}

	private ObjectNode buildSearchScriptTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_SEARCH_SCRIPT);
		tool.put("description", "Search the draft Groovy script with a regex pattern.");
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		ArrayNode required = inputSchema.putArray("required");
		required.add("pattern");
		ObjectNode properties = inputSchema.putObject("properties");
		properties.putObject("pattern").put("type", "string");
		properties.putObject("before_context").put("type", "integer");
		properties.putObject("after_context").put("type", "integer");
		tool.set("inputSchema", inputSchema);
		ObjectNode annotations = tool.putObject("annotations");
		ArrayNode scopes = annotations.putArray("scopes");
		scopes.add("read:polymr:script");
		annotations.put("intentTemplate", "Search the draft Groovy script source");
		return tool;
	}

	private ObjectNode buildReadScriptTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_READ_SCRIPT);
		tool.put("description", "Read a section of the draft Groovy script.");
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		ObjectNode properties = inputSchema.putObject("properties");
		properties.putObject("start_line").put("type", "integer");
		properties.putObject("limit").put("type", "integer");
		tool.set("inputSchema", inputSchema);
		ObjectNode annotations = tool.putObject("annotations");
		ArrayNode scopes = annotations.putArray("scopes");
		scopes.add("read:polymr:script");
		annotations.put("intentTemplate", "Read the draft Groovy script source");
		return tool;
	}

	private ObjectNode buildWriteScriptTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_WRITE_SCRIPT);
		tool.put("description", "Write content to the draft Groovy script.");
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		ArrayNode required = inputSchema.putArray("required");
		required.add("content");
		ObjectNode properties = inputSchema.putObject("properties");
		properties.putObject("content").put("type", "string");
		ObjectNode mode = properties.putObject("mode");
		mode.put("type", "string");
		ArrayNode allowed = mode.putArray("enum");
		allowed.add("overwrite");
		allowed.add("append");
		allowed.add("prepend");
		tool.set("inputSchema", inputSchema);
		ObjectNode annotations = tool.putObject("annotations");
		ArrayNode scopes = annotations.putArray("scopes");
		scopes.add("write:polymr:script");
		annotations.put("intentTemplate", "Write the draft Groovy script source");
		tool.putObject("_meta").putObject("ui").put("resourceUri", DIFF_RESOURCE_URI);
		return tool;
	}

	private ObjectNode buildGetScriptConfigTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_GET_SCRIPT_CONFIG);
		tool.put("description", "Get metadata for the current draft Groovy script.");
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		tool.set("inputSchema", inputSchema);
		ObjectNode annotations = tool.putObject("annotations");
		ArrayNode scopes = annotations.putArray("scopes");
		scopes.add("read:polymr:script");
		annotations.put("intentTemplate", "Get the draft Groovy script metadata");
		return tool;
	}

	private ObjectNode buildEditScriptFsTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_EDIT_SCRIPT_FS);
		tool.put(
			"description",
			"Replace exact matches in a Polymr-managed Groovy script "
				+ "draft resolved by path. These scripts are managed by Polymr and are not accessible "
				+ "through generic filesystem tools; use Polymr script tools only."
		);
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		ArrayNode required = inputSchema.putArray("required");
		required.add("path");
		required.add("edits");
		ObjectNode properties = inputSchema.putObject("properties");
		properties.putObject("path").put("type", "string");
		ObjectNode edits = properties.putObject("edits");
		edits.put("type", "array");
		ObjectNode item = edits.putObject("items");
		item.put("type", "object");
		ArrayNode itemRequired = item.putArray("required");
		itemRequired.add("find");
		ObjectNode itemProperties = item.putObject("properties");
		itemProperties.putObject("find").put("type", "string");
		itemProperties.putObject("replace").put("type", "string");
		tool.set("inputSchema", inputSchema);
		ObjectNode annotations = tool.putObject("annotations");
		ArrayNode scopes = annotations.putArray("scopes");
		scopes.add("write:polymr:script:draft");
		annotations.put("intentTemplate", "Edit Groovy script {path}");
		annotations.put("preview", true);
		tool.putObject("_meta").putObject("ui").put("resourceUri", DIFF_RESOURCE_URI);
		return tool;
	}

	private ObjectNode buildWriteScriptFsTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_WRITE_SCRIPT_FS);
		tool.put(
			"description",
			"Use this tool only to append/prepend or overwrite a "
				+ "Polymr-managed Groovy script draft as a whole; prefer edit_polymr_script for "
				+ "targeted replacement. If the script does not exist yet at the path, it is created. "
				+ "These scripts are managed by Polymr and are not accessible through generic "
				+ "filesystem tools; use Polymr script tools only."
		);
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		ArrayNode required = inputSchema.putArray("required");
		required.add("path");
		required.add("content");
		ObjectNode properties = inputSchema.putObject("properties");
		properties.putObject("path").put("type", "string");
		properties.putObject("content").put("type", "string");
		ObjectNode mode = properties.putObject("mode");
		mode.put("type", "string");
		ArrayNode allowed = mode.putArray("enum");
		allowed.add("overwrite");
		allowed.add("append");
		allowed.add("prepend");
		tool.set("inputSchema", inputSchema);
		ObjectNode annotations = tool.putObject("annotations");
		ArrayNode scopes = annotations.putArray("scopes");
		scopes.add("write:polymr:script:draft");
		annotations.put("intentTemplate", "Write Groovy script {path}");
		annotations.put("preview", true);
		tool.putObject("_meta").putObject("ui").put("resourceUri", DIFF_RESOURCE_URI);
		return tool;
	}

	private ObjectNode buildRunScriptFsTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_RUN_SCRIPT_FS);
		tool.put(
			"description",
			"Run the Polymr-managed Groovy script resolved by path. Use the `" + TOOL_READ_SCRIPT_METADATA_FS
				+ "` tool to get the schema for the expected input and output. These scripts are managed by "
				+ "Polymr and are not accessible through generic filesystem tools; use Polymr script "
				+ "tools only."
		);
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		inputSchema.putArray("required").add("path");
		ObjectNode properties = inputSchema.putObject("properties");
		properties.putObject("path").put("type", "string");
		properties.putObject("input");
		tool.set("inputSchema", inputSchema);
		ObjectNode annotations = tool.putObject("annotations");
		ArrayNode scopes = annotations.putArray("scopes");
		scopes.add("execute:polymr:script:draft");
		annotations.put("intentTemplate", "Run Groovy script {path}");
		return tool;
	}

	private ObjectNode buildReleaseScriptFsTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_RELEASE_SCRIPT_FS);
		tool.put(
			"description",
			"Publish the current working Polymr-managed Groovy script "
				+ "resolved by path as a new immutable released version. These scripts are managed by "
				+ "Polymr and are not accessible through generic filesystem tools; use Polymr script "
				+ "tools only."
		);
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		inputSchema.putArray("required").add("path");
		inputSchema.putObject("properties").putObject("path").put("type", "string");
		tool.set("inputSchema", inputSchema);
		ObjectNode annotations = tool.putObject("annotations");
		ArrayNode scopes = annotations.putArray("scopes");
		scopes.add("write:polymr:script:release");
		annotations.put("intentTemplate", "Release Groovy script {path}");
		return tool;
	}

	private ObjectNode buildSearchScriptsTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_SEARCH_SCRIPTS);
		tool.put(
			"description",
			"Search Polymr-managed Groovy script contents using a regex "
				+ "pattern. These scripts are managed by Polymr and are not accessible through generic "
				+ "filesystem tools; use Polymr script tools only."
		);
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		ArrayNode required = inputSchema.putArray("required");
		required.add("pattern");
		ObjectNode properties = inputSchema.putObject("properties");
		properties.putObject("root").put("type", "string");
		properties.putObject("pattern").put("type", "string");
		properties.putObject("before_context").put("type", "integer");
		properties.putObject("after_context").put("type", "integer");
		properties.putObject("context").put("type", "integer");
		properties.putObject("case_sensitive");
		properties.putObject("limit").put("type", "integer");
		properties.putObject("offset").put("type", "integer");
		tool.set("inputSchema", inputSchema);
		ObjectNode annotations = tool.putObject("annotations");
		ArrayNode scopes = annotations.putArray("scopes");
		scopes.add("read:polymr:script");
		annotations.put("intentTemplate", "Search Groovy scripts[ under {root}]");
		return tool;
	}

	private ObjectNode buildFindScriptsTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_FIND_SCRIPTS);
		tool.put(
			"description",
			"Find Polymr-managed Groovy scripts by path or name. These "
				+ "scripts are managed by Polymr and are not accessible through generic filesystem "
				+ "tools; use Polymr script tools only."
		);
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		ObjectNode properties = inputSchema.putObject("properties");
		properties.putObject("root").put("type", "string");
		properties.putObject("pattern").put("type", "string");
		properties.putObject("limit").put("type", "integer");
		properties.putObject("offset").put("type", "integer");
		tool.set("inputSchema", inputSchema);
		ObjectNode annotations = tool.putObject("annotations");
		ArrayNode scopes = annotations.putArray("scopes");
		scopes.add("read:polymr:script");
		annotations.put("intentTemplate", "Find Groovy scripts[ under {root}]");
		return tool;
	}

	private ObjectNode buildReadScriptFsTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_READ_SCRIPT_FS);
		tool.put(
			"description",
			"Read a Polymr-managed Groovy script resolved by path. "
				+ "These scripts are managed by Polymr and are not accessible through generic "
				+ "filesystem tools; use Polymr script tools only."
		);
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		ArrayNode required = inputSchema.putArray("required");
		required.add("path");
		ObjectNode properties = inputSchema.putObject("properties");
		properties.putObject("path").put("type", "string");
		properties.putObject("start_line").put("type", "integer");
		properties.putObject("limit").put("type", "integer");
		tool.set("inputSchema", inputSchema);
		ObjectNode annotations = tool.putObject("annotations");
		ArrayNode scopes = annotations.putArray("scopes");
		scopes.add("read:polymr:script");
		annotations.put("intentTemplate", "Read Groovy script {path}");
		return tool;
	}

	private ObjectNode buildReadScriptMetadataFsTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_READ_SCRIPT_METADATA_FS);
		tool.put(
			"description",
			"Read Polymr-managed Groovy script metadata resolved by "
				+ "path. These scripts are managed by Polymr and are not accessible through generic "
				+ "filesystem tools; use Polymr script tools only."
		);
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		inputSchema.putArray("required").add("path");
		ObjectNode properties = inputSchema.putObject("properties");
		properties.putObject("path").put("type", "string");
		tool.set("inputSchema", inputSchema);
		ObjectNode annotations = tool.putObject("annotations");
		ArrayNode scopes = annotations.putArray("scopes");
		scopes.add("read:polymr:script");
		annotations.put("intentTemplate", "Read Groovy script metadata for {path}");
		return tool;
	}

	private ObjectNode buildEditPageFsTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_EDIT_PAGE_FS);
		tool.put(
			"description",
			"Replace exact matches in a Polymr-managed page draft "
				+ "resolved by path. These pages are managed by Polymr and are not accessible through "
				+ "generic filesystem tools; use Polymr page tools only."
		);
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		ArrayNode required = inputSchema.putArray("required");
		required.add("path");
		required.add("edits");
		ObjectNode properties = inputSchema.putObject("properties");
		properties.putObject("path").put("type", "string");
		ObjectNode edits = properties.putObject("edits");
		edits.put("type", "array");
		ObjectNode item = edits.putObject("items");
		item.put("type", "object");
		ArrayNode itemRequired = item.putArray("required");
		itemRequired.add("find");
		ObjectNode itemProperties = item.putObject("properties");
		itemProperties.putObject("find").put("type", "string");
		itemProperties.putObject("replace").put("type", "string");
		tool.set("inputSchema", inputSchema);
		ObjectNode annotations = tool.putObject("annotations");
		ArrayNode scopes = annotations.putArray("scopes");
		scopes.add("write:polymr:page:draft");
		annotations.put("intentTemplate", "Edit page {path}");
		annotations.put("preview", true);
		tool.putObject("_meta").putObject("ui").put("resourceUri", DIFF_RESOURCE_URI);
		return tool;
	}

	private ObjectNode buildWritePageFsTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_WRITE_PAGE_FS);
		tool.put(
			"description",
			"Use this tool only to append/prepend or overwrite a "
				+ "Polymr-managed page draft as a whole; prefer edit_polymr_page for targeted "
				+ "replacement. If the page does not exist yet at the path, it is created. These pages "
				+ "are managed by Polymr and are not accessible through generic filesystem tools; use "
				+ "Polymr page tools only."
		);
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		ArrayNode required = inputSchema.putArray("required");
		required.add("path");
		required.add("content");
		ObjectNode properties = inputSchema.putObject("properties");
		properties.putObject("path").put("type", "string");
		properties.putObject("content").put("type", "string");
		ObjectNode mode = properties.putObject("mode");
		mode.put("type", "string");
		ArrayNode allowed = mode.putArray("enum");
		allowed.add("overwrite");
		allowed.add("append");
		allowed.add("prepend");
		tool.set("inputSchema", inputSchema);
		ObjectNode annotations = tool.putObject("annotations");
		ArrayNode scopes = annotations.putArray("scopes");
		scopes.add("write:polymr:page:draft");
		annotations.put("intentTemplate", "Write page {path}");
		annotations.put("preview", true);
		tool.putObject("_meta").putObject("ui").put("resourceUri", DIFF_RESOURCE_URI);
		return tool;
	}

	private ObjectNode buildSearchPagesTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_SEARCH_PAGES);
		tool.put(
			"description",
			"Search Polymr-managed page contents using a regex pattern. "
				+ "These pages are managed by Polymr and are not accessible through generic filesystem "
				+ "tools; use Polymr page tools only."
		);
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		ArrayNode required = inputSchema.putArray("required");
		required.add("pattern");
		ObjectNode properties = inputSchema.putObject("properties");
		properties.putObject("root").put("type", "string");
		properties.putObject("pattern").put("type", "string");
		properties.putObject("before_context").put("type", "integer");
		properties.putObject("after_context").put("type", "integer");
		properties.putObject("context").put("type", "integer");
		properties.putObject("case_sensitive");
		properties.putObject("limit").put("type", "integer");
		properties.putObject("offset").put("type", "integer");
		tool.set("inputSchema", inputSchema);
		ObjectNode annotations = tool.putObject("annotations");
		ArrayNode scopes = annotations.putArray("scopes");
		scopes.add("read:polymr:page");
		annotations.put("intentTemplate", "Search pages[ under {root}]");
		return tool;
	}

	private ObjectNode buildFindPagesTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_FIND_PAGES);
		tool.put(
			"description",
			"Find Polymr-managed pages by path or name. These pages are "
				+ "managed by Polymr and are not accessible through generic filesystem tools; use "
				+ "Polymr page tools only."
		);
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		ObjectNode properties = inputSchema.putObject("properties");
		properties.putObject("root").put("type", "string");
		properties.putObject("pattern").put("type", "string");
		ObjectNode pageType = properties.putObject("type");
		pageType.put("type", "string");
		pageType.putArray("enum").add("PAGE").add("COMPONENT");
		properties.putObject("limit").put("type", "integer");
		properties.putObject("offset").put("type", "integer");
		tool.set("inputSchema", inputSchema);
		ObjectNode annotations = tool.putObject("annotations");
		ArrayNode scopes = annotations.putArray("scopes");
		scopes.add("read:polymr:page");
		annotations.put("intentTemplate", "Find pages[ under {root}]");
		return tool;
	}

	private ObjectNode buildReadPageFsTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_READ_PAGE_FS);
		tool.put(
			"description",
			"Read a Polymr-managed page resolved by path. These pages "
				+ "are managed by Polymr and are not accessible through generic filesystem tools; use "
				+ "Polymr page tools only."
		);
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		ArrayNode required = inputSchema.putArray("required");
		required.add("path");
		ObjectNode properties = inputSchema.putObject("properties");
		properties.putObject("path").put("type", "string");
		properties.putObject("start_line").put("type", "integer");
		properties.putObject("limit").put("type", "integer");
		tool.set("inputSchema", inputSchema);
		ObjectNode annotations = tool.putObject("annotations");
		ArrayNode scopes = annotations.putArray("scopes");
		scopes.add("read:polymr:page");
		annotations.put("intentTemplate", "Read page {path}");
		return tool;
	}

	private ObjectNode buildReleasePageFsTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_RELEASE_PAGE_FS);
		tool.put(
			"description",
			"Publish the current working Polymr-managed page resolved "
				+ "by path as a new immutable released version. These pages are managed by Polymr and "
				+ "are not accessible through generic filesystem tools; use Polymr page tools only."
		);
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		inputSchema.putArray("required").add("path");
		inputSchema.putObject("properties").putObject("path").put("type", "string");
		tool.set("inputSchema", inputSchema);
		ObjectNode annotations = tool.putObject("annotations");
		ArrayNode scopes = annotations.putArray("scopes");
		scopes.add("write:polymr:page:release");
		annotations.put("intentTemplate", "Release page {path}");
		return tool;
	}

	private ObjectNode buildReadPageMetadataFsTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_READ_PAGE_METADATA_FS);
		tool.put(
			"description",
			"Read Polymr-managed page metadata resolved by path. These "
				+ "pages are managed by Polymr and are not accessible through generic filesystem tools; "
				+ "use Polymr page tools only."
		);
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		inputSchema.putArray("required").add("path");
		ObjectNode properties = inputSchema.putObject("properties");
		properties.putObject("path").put("type", "string");
		tool.set("inputSchema", inputSchema);
		ObjectNode annotations = tool.putObject("annotations");
		ArrayNode scopes = annotations.putArray("scopes");
		scopes.add("read:polymr:page");
		annotations.put("intentTemplate", "Read page metadata for {path}");
		return tool;
	}

	private ObjectNode buildCreateOrUpdateCanvasTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_CREATE_OR_UPDATE_CANVAS);
		tool.put(
			"description",
			"Create or update one or more session canvases. Each canvas "
				+ "must provide a logical id, a short title of five words or fewer, and Vue SFC "
				+ "content. The content is compiled before persistence. If compilation fails, the tool "
				+ "returns a soft error so the canvas can be corrected."
		);
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		inputSchema.putArray("required").add("canvases");
		ObjectNode properties = inputSchema.putObject("properties");
		ObjectNode canvases = properties.putObject("canvases");
		canvases.put("type", "array");
		canvases.put("minItems", 1);
		ObjectNode items = canvases.putObject("items");
		items.put("type", "object");
		ArrayNode itemRequired = items.putArray("required");
		itemRequired.add("logical_id");
		itemRequired.add("title");
		itemRequired.add("content");
		ObjectNode itemProperties = items.putObject("properties");
		itemProperties.putObject("logical_id")
			.put("type", "string")
			.put("description", "Stable logical id for this canvas within the session.");
		itemProperties.putObject("title")
			.put("type", "string")
			.put("description", "Short canvas title, five words or fewer.");
		itemProperties.putObject("content")
			.put("type", "string")
			.put("description", "Vue single-file component source for the canvas.");
		tool.set("inputSchema", inputSchema);
		ObjectNode annotations = tool.putObject("annotations");
		annotations.put("intentTemplate", "Create or update session canvases");
		return tool;
	}

	private ObjectNode buildRefreshCanvasTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_REFRESH_CANVAS);
		tool.put(
			"description",
			"Refresh an existing session canvas by its logical "
				+ "identifier. Use this when the canvas already exists but the data it depends on has "
				+ "changed and the view needs to be updated."
		);
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		inputSchema.putArray("required").add("logical_id");
		ObjectNode properties = inputSchema.putObject("properties");
		properties.putObject("logical_id")
			.put("type", "string")
			.put("description", "Stable logical id for an existing canvas within the session.");
		tool.set("inputSchema", inputSchema);
		ObjectNode annotations = tool.putObject("annotations");
		annotations.put("intentTemplate", "Refresh session canvas {logical_id}");
		return tool;
	}

	private ObjectNode buildListWorkspaceExternalFrontendImportsTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_LIST_WORKSPACE_EXTERNAL_FRONTEND_IMPORTS);
		tool.put(
			"description",
			"List the workspace-level external frontend imports used by "
				+ "Polymr-managed pages. These imports are shared across pages in the workspace."
		);
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		tool.set("inputSchema", inputSchema);
		tool.set("outputSchema", buildWorkspaceExternalFrontendImportsOutputSchema());
		ObjectNode annotations = tool.putObject("annotations");
		ArrayNode scopes = annotations.putArray("scopes");
		scopes.add("read:polymr:workspace:imports");
		annotations.put("intentTemplate", "List workspace external frontend imports");
		return tool;
	}

	private ObjectNode buildSetWorkspaceExternalFrontendImportsTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_SET_WORKSPACE_EXTERNAL_FRONTEND_IMPORTS);
		tool.put(
			"description",
			"Update the workspace-level frontend imports used by "
				+ "Polymr-managed pages as a whole. Use this for adding, updating, or deleting entries. "
				+ "Be careful when updating or removing existing entries because this may impact all "
				+ "pages that depend on them."
		);
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		inputSchema.putArray("required").add("external_frontend_imports");
		ObjectNode properties = inputSchema.putObject("properties");
		ObjectNode imports = properties.putObject("external_frontend_imports");
		imports.put("type", "array");
		imports.put("description", "Workspace-level external frontend imports.");
		ObjectNode items = imports.putObject("items");
		items.put("type", "object");
		ArrayNode itemRequired = items.putArray("required");
		itemRequired.add("specifier");
		itemRequired.add("global_name");
		itemRequired.add("source_url");
		ObjectNode itemProperties = items.putObject("properties");
		itemProperties.putObject("specifier")
			.put("type", "string")
			.put("description", "Import specifier used by pages, for example vue.");
		itemProperties.putObject("global_name")
			.put("type", "string")
			.put("description", "Global browser variable exposed by the external script, for example Vue.");
		itemProperties.putObject("source_url")
			.put("type", "string")
			.put("description", "URL of the external script source loaded for this import.");
		itemProperties.putObject("css_urls")
			.put("type", "array")
			.put("description", "Optional stylesheet URLs loaded alongside this import.")
			.putObject("items")
			.put("type", "string");
		tool.set("inputSchema", inputSchema);
		tool.set("outputSchema", buildWorkspaceExternalFrontendImportsOutputSchema());
		ObjectNode annotations = tool.putObject("annotations");
		ArrayNode scopes = annotations.putArray("scopes");
		scopes.add("write:polymr:workspace:imports");
		annotations.put("intentTemplate", "Replace workspace external frontend imports");
		return tool;
	}

	private ObjectNode buildWorkspaceExternalFrontendImportsOutputSchema() {
		ObjectNode schema = objectMapper.createObjectNode();
		schema.put("type", "object");
		ArrayNode required = schema.putArray("required");
		required.add("external_frontend_imports");
		required.add("entry_count");
		ObjectNode properties = schema.putObject("properties");
		ObjectNode imports = properties.putObject("external_frontend_imports");
		imports.put("type", "array");
		ObjectNode items = imports.putObject("items");
		items.put("type", "object");
		ArrayNode itemRequired = items.putArray("required");
		itemRequired.add("specifier");
		itemRequired.add("global_name");
		itemRequired.add("source_url");
		ObjectNode itemProperties = items.putObject("properties");
		itemProperties.putObject("specifier").put("type", "string");
		itemProperties.putObject("global_name").put("type", "string");
		itemProperties.putObject("source_url").put("type", "string");
		itemProperties.putObject("css_urls").put("type", "array").putObject("items").put("type", "string");
		properties.putObject("entry_count").put("type", "integer");
		return schema;
	}

	private ObjectNode buildEditPageMetadataFsTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_EDIT_PAGE_METADATA_FS);
		tool.put(
			"description",
			"Update Polymr-managed page metadata resolved by path, "
				+ "including description, route suffix, component usage guide, icon, query params, "
				+ "input params, import allowlist, type, and menu visibility. When updating a COMPONENT,"
				+ " always add a clear, concrete usage guide that contains a small example of how to "
				+ "use the component. These pages are managed by Polymr and are not accessible through "
				+ "generic filesystem tools; use Polymr page tools only."
		);
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		inputSchema.putArray("required").add("path");
		ObjectNode properties = inputSchema.putObject("properties");
		properties.putObject("path").put("type", "string");
		properties.putObject("description").put("type", "string");
		properties.putObject("icon_svg").put("type", "string");
		properties.putObject("route_suffix").put("type", "string");
		properties.putObject("usage_guide").put("type", "string");
		properties.putObject("query_params").put("type", "array").putObject("items").put("type", "string");
		properties.putObject("input_params").put("type", "array").putObject("items").put("type", "object");
		properties.putObject("import_allowlist")
			.put("type", "array")
			.putObject("items")
			.put("type", "string");
		properties.putObject("type").put("type", "string");
		properties.putObject("menu_visible").put("type", "boolean");
		tool.set("inputSchema", inputSchema);
		ObjectNode annotations = tool.putObject("annotations");
		ArrayNode scopes = annotations.putArray("scopes");
		scopes.add("write:polymr:page:draft");
		annotations.put("intentTemplate", "Edit metadata for page {path}");
		return tool;
	}

	private ObjectNode buildSetScriptConfigTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_SET_SCRIPT_CONFIG);
		tool.put("description", "Update metadata for the current draft Groovy script.");
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		ObjectNode properties = inputSchema.putObject("properties");
		properties.putObject("name").put("type", "string");
		properties.putObject("slug").put("type", "string");
		properties.putObject("description").put("type", "string");
		properties.putObject("input_schema").put("type", "object");
		properties.putObject("output_schema").put("type", "object");
		tool.set("inputSchema", inputSchema);
		ObjectNode annotations = tool.putObject("annotations");
		ArrayNode scopes = annotations.putArray("scopes");
		scopes.add("write:polymr:script");
		annotations.put("intentTemplate", "Update the draft Groovy script metadata");
		return tool;
	}

	private ObjectNode buildEditScriptMetadataTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_EDIT_SCRIPT_METADATA);
		tool.put(
			"description",
			"Update metadata for the current draft Groovy script, "
				+ "including name, slug, description, input schema, and output schema."
		);
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		ObjectNode properties = inputSchema.putObject("properties");
		properties.putObject("name").put("type", "string");
		properties.putObject("slug").put("type", "string");
		properties.putObject("description").put("type", "string");
		properties.putObject("input_schema").put("type", "object");
		properties.putObject("output_schema").put("type", "object");
		tool.set("inputSchema", inputSchema);
		ObjectNode annotations = tool.putObject("annotations");
		ArrayNode scopes = annotations.putArray("scopes");
		scopes.add("write:polymr:script");
		annotations.put("intentTemplate", "Edit the draft Groovy script metadata");
		return tool;
	}

	private ObjectNode buildEditScriptMetadataFsTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_EDIT_SCRIPT_METADATA_FS);
		tool.put(
			"description",
			"Update Polymr-managed Groovy script metadata resolved by "
				+ "path, including description, input schema, and output schema. These scripts are "
				+ "managed by Polymr and are not accessible through generic filesystem tools; use "
				+ "Polymr script tools only."
		);
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		inputSchema.putArray("required").add("path");
		ObjectNode properties = inputSchema.putObject("properties");
		properties.putObject("path").put("type", "string");
		properties.putObject("description").put("type", "string");
		properties.putObject("input_schema").put("type", "object");
		properties.putObject("output_schema").put("type", "object");
		tool.set("inputSchema", inputSchema);
		ObjectNode annotations = tool.putObject("annotations");
		ArrayNode scopes = annotations.putArray("scopes");
		scopes.add("write:polymr:script:draft");
		annotations.put("intentTemplate", "Edit metadata for Groovy script {path}");
		return tool;
	}

	private ObjectNode buildGetScriptApiDefinitionTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_GET_SCRIPT_API_DEF);
		tool.put(
			"description",
			"Get the full API definition for a script helper function (use the bare name, e.g. callTool)."
		);
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		ArrayNode required = inputSchema.putArray("required");
		required.add("name");
		ObjectNode properties = inputSchema.putObject("properties");
		properties.putObject("name")
			.put("type", "string")
			.put("description", "API function name (e.g. callTool)");
		tool.set("inputSchema", inputSchema);
		ObjectNode annotations = tool.putObject("annotations");
		ArrayNode scopes = annotations.putArray("scopes");
		scopes.add("read:polymr:definition");
		annotations.put("intentTemplate", "Get script API definition");
		return tool;
	}

	private ObjectNode buildGetScriptToolDefinitionTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_GET_SCRIPT_TOOL_DEF);
		tool.put(
			"description",
			"Get the full definition for a backend service. For Groovy "
				+ "script tools, the script body is evaluated directly with bound variables like input "
				+ "and api; do not return a closure such as input -> .... The script must output data "
				+ "compatible with the output schema."
		);
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		ArrayNode required = inputSchema.putArray("required");
		required.add("service");
		ObjectNode properties = inputSchema.putObject("properties");
		properties.putObject("service")
			.put("type", "string")
			.put("description", "Backend service name or alias (no server prefix)");
		tool.set("inputSchema", inputSchema);
		ObjectNode annotations = tool.putObject("annotations");
		ArrayNode scopes = annotations.putArray("scopes");
		scopes.add("read:polymr:definition");
		annotations.put("intentTemplate", "Get backend service definition");
		return tool;
	}

	private ObjectNode buildGetScriptDefinitionTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_GET_SCRIPT_DEFINITION);
		tool.put(
			"description",
			"Get the definition for a Groovy script tool by slug. "
				+ "Groovy scripts are evaluated as direct script bodies with bound variables like input "
				+ "and api, not as closures such as input -> ... ."
		);
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		ArrayNode required = inputSchema.putArray("required");
		required.add("slug");
		ObjectNode properties = inputSchema.putObject("properties");
		properties.putObject("slug")
			.put("type", "string")
			.put("description", "Script slug");
		tool.set("inputSchema", inputSchema);
		ObjectNode annotations = tool.putObject("annotations");
		ArrayNode scopes = annotations.putArray("scopes");
		scopes.add("read:polymr:definition");
		annotations.put("intentTemplate", "Get script tool definition");
		return tool;
	}

	private ObjectNode buildWorkflowStateGetTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_WORKFLOW_STATE_GET);
		tool.put("description", "Read workflow state or a specific state path.");
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		ObjectNode properties = inputSchema.putObject("properties");
		properties.putObject("path")
			.put("type", "string")
			.put("description", "Optional state path (e.g. state.order.id or order.id). Omit to read full state.");
		tool.set("inputSchema", inputSchema);
		ObjectNode annotations = tool.putObject("annotations");
		ArrayNode scopes = annotations.putArray("scopes");
		scopes.add("read:workflow:state");
		annotations.put("intentTemplate", "Read workflow state");
		return tool;
	}

	private ObjectNode buildWorkflowStateSetTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_WORKFLOW_STATE_SET);
		tool.put("description", "Set one or more workflow state paths to values.");
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		ArrayNode required = inputSchema.putArray("required");
		required.add("updates");
		ObjectNode properties = inputSchema.putObject("properties");
		ObjectNode updates = properties.putObject("updates");
		updates.put("type", "array");
		ObjectNode items = updates.putObject("items");
		items.put("type", "object");
		ArrayNode itemRequired = items.putArray("required");
		itemRequired.add("path");
		itemRequired.add("value");
		ObjectNode itemProps = items.putObject("properties");
		itemProps.putObject("path")
			.put("type", "string")
			.put("description", "State path to update.");
		itemProps.putObject("value")
			.put("description", "Value to set at the path.");
		tool.set("inputSchema", inputSchema);
		ObjectNode annotations = tool.putObject("annotations");
		ArrayNode scopes = annotations.putArray("scopes");
		scopes.add("write:workflow:state");
		annotations.put("intentTemplate", "Set workflow state fields");
		return tool;
	}

	private ObjectNode buildWorkflowStatePatchTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_WORKFLOW_STATE_PATCH);
		tool.put(
			"description",
			"Apply a JSON Merge Patch (RFC 7396) to workflow state "
				+ "(object merge only). Arrays replace, null removes keys."
		);
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		inputSchema.put("additionalProperties", true);
		tool.set("inputSchema", inputSchema);
		ObjectNode annotations = tool.putObject("annotations");
		ArrayNode scopes = annotations.putArray("scopes");
		scopes.add("write:workflow:state");
		annotations.put("intentTemplate", "Patch workflow state");
		return tool;
	}

	private ObjectNode buildWorkflowStateSchemaTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_WORKFLOW_STATE_SCHEMA);
		tool.put("description", "Read the workflow state schema for the active workflow version.");
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		tool.set("inputSchema", inputSchema);
		ObjectNode annotations = tool.putObject("annotations");
		ArrayNode scopes = annotations.putArray("scopes");
		scopes.add("read:workflow:state");
		annotations.put("intentTemplate", "Read workflow state schema");
		return tool;
	}

	private ObjectNode buildToolHookGetNameTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_HOOK_GET_NAME);
		tool.put("description", "Get the current tool name for this hook.");
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		tool.set("inputSchema", inputSchema);
		tool.putObject("annotations").put("intentTemplate", "Read tool hook name");
		return tool;
	}

	private ObjectNode buildToolHookGetInputTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_HOOK_GET_INPUT);
		tool.put("description", "Read the current tool call input.");
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		tool.set("inputSchema", inputSchema);
		tool.putObject("annotations").put("intentTemplate", "Read tool hook input");
		return tool;
	}

	private ObjectNode buildToolHookSetInputTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_HOOK_SET_INPUT);
		tool.put("description", "Set one or more tool call input paths.");
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		inputSchema.putArray("required").add("updates");
		ObjectNode properties = inputSchema.putObject("properties");
		ObjectNode updates = properties.putObject("updates");
		updates.put("type", "array");
		ObjectNode items = updates.putObject("items");
		items.put("type", "object");
		ArrayNode itemRequired = items.putArray("required");
		itemRequired.add("path");
		itemRequired.add("value");
		ObjectNode itemProps = items.putObject("properties");
		itemProps.putObject("path").put("type", "string");
		itemProps.putObject("value");
		tool.set("inputSchema", inputSchema);
		tool.putObject("annotations").put("intentTemplate", "Update tool hook input");
		return tool;
	}

	private ObjectNode buildToolHookPatchInputTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_HOOK_PATCH_INPUT);
		tool.put("description", "Apply a JSON Merge Patch (RFC 7396) to the tool call input.");
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		inputSchema.put("additionalProperties", true);
		tool.set("inputSchema", inputSchema);
		tool.putObject("annotations").put("intentTemplate", "Patch tool hook input");
		return tool;
	}

	private ObjectNode buildToolHookGetOutputTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_HOOK_GET_OUTPUT);
		tool.put("description", "Read the current tool call output.");
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		tool.set("inputSchema", inputSchema);
		tool.putObject("annotations").put("intentTemplate", "Read tool hook output");
		return tool;
	}

	private ObjectNode buildToolHookSetOutputTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_HOOK_SET_OUTPUT);
		tool.put("description", "Set one or more tool call output paths.");
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		inputSchema.putArray("required").add("updates");
		ObjectNode properties = inputSchema.putObject("properties");
		ObjectNode updates = properties.putObject("updates");
		updates.put("type", "array");
		ObjectNode items = updates.putObject("items");
		items.put("type", "object");
		ArrayNode itemRequired = items.putArray("required");
		itemRequired.add("path");
		itemRequired.add("value");
		ObjectNode itemProps = items.putObject("properties");
		itemProps.putObject("path").put("type", "string");
		itemProps.putObject("value");
		tool.set("inputSchema", inputSchema);
		tool.putObject("annotations").put("intentTemplate", "Update tool hook output");
		return tool;
	}

	private ObjectNode buildToolHookPatchOutputTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_HOOK_PATCH_OUTPUT);
		tool.put("description", "Apply a JSON Merge Patch (RFC 7396) to the tool call output.");
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		inputSchema.put("additionalProperties", true);
		tool.set("inputSchema", inputSchema);
		tool.putObject("annotations").put("intentTemplate", "Patch tool hook output");
		return tool;
	}

	private ObjectNode buildToolHookCancelTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_HOOK_CANCEL);
		tool.put("description", "Cancel the current tool call.");
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		tool.set("inputSchema", inputSchema);
		tool.putObject("annotations").put("intentTemplate", "Cancel tool call");
		return tool;
	}

	private JsonNode getScriptDefinition(McpServer server, JsonNode arguments, UUID sessionId) {
		String slug = readText(arguments, "slug");
		if (slug == null || slug.isBlank()) {
			return error("Script slug is required");
		}
		Session session = sessionId == null ? null : entityManager.find(Session.class, sessionId);
		Workspace workspace = session != null ? session.workspace : (server == null ? null : server.workspace);
		if (workspace == null) {
			return error("Workspace not found");
		}
		Script script = entityManager.createQuery(
				"select s from Script s where s.workspace.id = :workspaceId and "
					+ "lower(s.slug) = :slug and s.disabled = false",
				Script.class
			)
			.setParameter("workspaceId", workspace.id)
			.setParameter("slug", slug.trim().toLowerCase())
			.setMaxResults(1)
			.getResultStream()
			.findFirst()
			.orElse(null);
		if (script == null) {
			return error("Script not found");
		}
		ObjectNode result = objectMapper.createObjectNode();
		result.put("name", script.name);
		result.put("slug", script.slug);
		result.put("description", script.description == null ? "" : script.description);
		if (script.activeVersion != null && script.activeVersion.inputSchema != null) {
			result.set("input_schema", script.activeVersion.inputSchema);
		}
		if (script.activeVersion != null && script.activeVersion.outputSchema != null) {
			result.set("output_schema", script.activeVersion.outputSchema);
		}
		return result;
	}

	private JsonNode getWorkflowState(JsonNode arguments, UUID sessionId) {
		WorkflowStateStore store = resolveWorkflowStateStore(sessionId);
		if (store == null) {
			return error("Workflow run not found");
		}
		ObjectNode state = store.getState();
		String path = readText(arguments, "path");
		if (path != null && !path.isBlank()) {
			JsonNode value = store.getState(path);
			ObjectNode result = objectMapper.createObjectNode();
			if (value == null) {
				putTextContent(result, "null");
				result.putNull("value");
			}
			else {
				result.set("value", value);
				putJsonContent(result, value);
			}
			return result;
		}
		ObjectNode result = objectMapper.createObjectNode();
		result.set("state", state);
		putJsonContent(result, state);
		return result;
	}

	private JsonNode setWorkflowState(JsonNode arguments, UUID sessionId) {
		WorkflowStateStore store = resolveWorkflowStateStore(sessionId);
		if (store == null) {
			return error("Workflow run not found");
		}
		JsonNode updates = arguments == null ? null : arguments.get("updates");
		if (updates == null || !updates.isArray()) {
			return error("Updates array is required");
		}
		store.patchState(updates);
		ObjectNode result = objectMapper.createObjectNode();
		ObjectNode updatedState = store.getState();
		result.set("state", updatedState);
		putJsonContent(result, updatedState);
		return result;
	}

	private JsonNode patchWorkflowState(JsonNode arguments, UUID sessionId) {
		WorkflowStateStore store = resolveWorkflowStateStore(sessionId);
		if (store == null) {
			return error("Workflow run not found");
		}
		if (arguments == null || arguments.isNull()) {
			return error("Patch object is required");
		}
		if (!arguments.isObject()) {
			return error("Patch must be a JSON object");
		}
		ObjectNode state = store.getState();
		applyMergePatch(state, arguments);
		ObjectNode result = objectMapper.createObjectNode();
		result.set("state", state);
		putJsonContent(result, state);
		return result;
	}

	private JsonNode getWorkflowStateSchema(UUID sessionId, McpServer server) {
		WorkflowStateStore store = resolveWorkflowStateStore(sessionId);
		if (store == null) {
			return error("Workflow run not found");
		}
		JsonNode schema = store.getSchema();
		ObjectNode result = objectMapper.createObjectNode();
		if (schema == null || schema.isNull()) {
			ObjectNode fallback = objectMapper.createObjectNode();
			fallback.put("type", "object");
			result.set("schema", fallback);
			putJsonContent(result, fallback);
		}
		else {
			result.set("schema", schema);
			putJsonContent(result, schema);
		}
		return result;
	}

	private ObjectNode buildGetApiDefinitionTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_GET_API_DEF);
		tool.put("description", "Get the full API definition for a helper function.");
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		ArrayNode required = inputSchema.putArray("required");
		required.add("name");
		ObjectNode properties = inputSchema.putObject("properties");
		properties.putObject("name")
			.put("type", "string")
			.put("description", "API function name (e.g. api.getUsers)");
		tool.set("inputSchema", inputSchema);
		ObjectNode annotations = tool.putObject("annotations");
		ArrayNode scopes = annotations.putArray("scopes");
		scopes.add("read:polymr:definition");
		annotations.put("intentTemplate", "Get API definition");
		return tool;
	}

	private ObjectNode buildGetToolDefinitionTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_GET_TOOL_DEF);
		tool.put(
			"description",
			"Get the full definition for a backend service. The output "
				+ "schema covers the structuredContent field of the MCP tool result."
		);
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		ArrayNode required = inputSchema.putArray("required");
		required.add("service");
		ObjectNode properties = inputSchema.putObject("properties");
		properties.putObject("service")
			.put("type", "string")
			.put("description", "Backend service name or alias (no server prefix)");
		tool.set("inputSchema", inputSchema);
		ObjectNode annotations = tool.putObject("annotations");
		ArrayNode scopes = annotations.putArray("scopes");
		scopes.add("read:polymr:definition");
		annotations.put("intentTemplate", "Get backend service definition");
		return tool;
	}

	@Transactional
	JsonNode editPage(JsonNode arguments, UUID sessionId) {
		SfcPageVersion draft = resolveDraft(arguments, sessionId);
		if (draft == null) {
			return error("Draft page not found");
		}
		String source = draft.sourceSfc == null ? "" : draft.sourceSfc;
		JsonNode editsNode = arguments == null ? null : arguments.get("edits");
		if (editsNode == null || !editsNode.isArray() || editsNode.isEmpty()) {
			return error("Edits are required");
		}
		String updated = source;
		for (JsonNode edit : editsNode) {
			String find = edit == null ? null : readText(edit, "find");
			String replace = edit == null ? null : readText(edit, "replace");
			if (find == null) {
				continue;
			}
			if (!updated.contains(find)) {
				return error("No matching text found for: " + find);
			}
			String safeReplace = replace == null ? "" : replace;
			updated = updated.replaceAll(Pattern.quote(find), Matcher.quoteReplacement(safeReplace));
		}
		draft.sourceSfc = updated;
		draft.compiledBundle = null;
		draft.compileErrors = null;
		ObjectNode result = objectMapper.createObjectNode();
		putTextContent(result, "Page updated");
		result.put("length", updated.length());
		return result;
	}

	private JsonNode searchPage(JsonNode arguments, UUID sessionId) {
		SfcPageVersion draft = resolveDraft(arguments, sessionId);
		if (draft == null) {
			return error("Draft page not found");
		}
		String patternText = readText(arguments, "pattern");
		if (patternText == null || patternText.isBlank()) {
			return error("Pattern is required");
		}
		int before = readInt(arguments, "before_context", 0);
		int after = readInt(arguments, "after_context", 0);
		Pattern pattern;
		try {
			pattern = Pattern.compile(patternText);
		}
		catch (Exception ex) {
			return error("Invalid regex pattern");
		}
		String source = draft.sourceSfc == null ? "" : draft.sourceSfc;
		String[] lines = source.split("\n", -1);
		List<String> output = new ArrayList<>();
		int total = lines.length;
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			Matcher matcher = pattern.matcher(line);
			if (!matcher.find()) {
				continue;
			}
			int start = Math.max(0, i - before);
			int end = Math.min(lines.length - 1, i + after);
			for (int j = start; j <= end; j++) {
				String prefix;
				if (j == i) {
					prefix = (j + 1) + ": ";
				}
				else if (j < i) {
					prefix = (j + 1) + "- ";
				}
				else {
					prefix = (j + 1) + "+ ";
				}
				String rendered = prefix + lines[j];
				if (output.isEmpty() || !output.get(output.size() - 1).equals(rendered)) {
					output.add(rendered);
				}
			}
		}
		ObjectNode result = objectMapper.createObjectNode();
		ArrayNode linesNode = result.putArray("lines");
		output.forEach(linesNode::add);
		result.put("totalCount", total);
		return result;
	}

	private JsonNode readPage(JsonNode arguments, UUID sessionId) {
		SfcPageVersion draft = resolveDraft(arguments, sessionId);
		if (draft == null) {
			return error("Draft page not found");
		}
		int start = Math.max(1, readInt(arguments, "start_line", 1));
		int limit = Math.max(1, Math.min(200, readInt(arguments, "limit", 200)));
		String source = draft.sourceSfc == null ? "" : draft.sourceSfc;
		String[] lines = source.split("\n", -1);
		int total = lines.length;
		int startIndex = Math.min(total, start) - 1;
		int endIndex = Math.min(total, startIndex + limit);
		ObjectNode result = objectMapper.createObjectNode();
		ArrayNode linesNode = result.putArray("lines");
		for (int i = startIndex; i < endIndex; i++) {
			linesNode.add((i + 1) + ": " + lines[i]);
		}
		result.put("totalCount", total);
		result.put("start_line", start);
		result.put("limit", limit);
		result.put("truncated", endIndex < total);
		return result;
	}

	@Transactional
	JsonNode writePage(JsonNode arguments, UUID sessionId) {
		SfcPageVersion draft = resolveDraft(arguments, sessionId);
		if (draft == null) {
			return error("Draft page not found");
		}
		String content = readText(arguments, "content");
		if (content == null) {
			return error("Content is required");
		}
		String mode = readText(arguments, "mode");
		String source = draft.sourceSfc == null ? "" : draft.sourceSfc;
		String updated;
		if ("append".equalsIgnoreCase(mode)) {
			updated = source + content;
		}
		else if ("prepend".equalsIgnoreCase(mode)) {
			updated = content + source;
		}
		else {
			updated = content;
		}
		draft.sourceSfc = updated;
		draft.compiledBundle = null;
		draft.compileErrors = null;
		ObjectNode result = objectMapper.createObjectNode();
		putTextContent(result, "Page updated");
		result.put("length", updated.length());
		return result;
	}

	private JsonNode getPageConfig(JsonNode arguments, UUID sessionId) {
		SfcPageVersion draft = resolveDraft(arguments, sessionId);
		if (draft == null || draft.page == null) {
			return error("Draft page not found");
		}
		SfcPage page = draft.page;
		ObjectNode result = objectMapper.createObjectNode();
		result.put("name", page.name);
		result.put("slug", page.slug);
		result.put("description", page.description == null ? "" : page.description);
		result.put("route_suffix", page.routeSuffix == null ? "" : page.routeSuffix);
		result.put("icon_svg", page.iconSvg == null ? "" : page.iconSvg);
		if (page.queryParams != null) {
			result.set("query_params", page.queryParams);
		}
		if (page.inputParams != null) {
			result.set("input_params", page.inputParams);
		}
		if (page.importAllowlist != null) {
			result.set("import_allowlist", page.importAllowlist);
		}
		return result;
	}

	@Transactional
	JsonNode setPageConfig(JsonNode arguments, UUID sessionId) {
		SfcPageVersion draft = resolveDraft(arguments, sessionId);
		if (draft == null || draft.page == null) {
			return error("Draft page not found");
		}
		SfcPage page = draft.page;
		if (arguments != null) {
			JsonNode description = arguments.get("description");
			if (description != null && description.isTextual()) {
				page.description = description.asText();
			}
			JsonNode routeSuffix = arguments.get("route_suffix");
			if (routeSuffix != null && routeSuffix.isTextual()) {
				page.routeSuffix = routeSuffix.asText();
			}
			JsonNode iconSvg = arguments.get("icon_svg");
			if (iconSvg != null && iconSvg.isTextual()) {
				page.iconSvg = iconSvg.asText();
			}
			JsonNode queryParams = arguments.get("query_params");
			if (queryParams != null && queryParams.isArray()) {
				page.queryParams = queryParams.deepCopy();
			}
			JsonNode inputParams = arguments.get("input_params");
			if (inputParams != null && inputParams.isArray()) {
				page.inputParams = inputParams.deepCopy();
			}
			JsonNode importAllowlist = arguments.get("import_allowlist");
			if (importAllowlist != null && importAllowlist.isArray()) {
				page.importAllowlist = importAllowlist.deepCopy();
			}
		}
		ObjectNode result = objectMapper.createObjectNode();
		putTextContent(result, "Page configuration updated");
		return result;
	}

	@Transactional
	JsonNode editScript(JsonNode arguments, UUID sessionId) {
		ScriptVersion draft = resolveScriptDraft(arguments, sessionId);
		if (draft == null) {
			return error("Draft script not found");
		}
		String source = draft.sourceGroovy == null ? "" : draft.sourceGroovy;
		JsonNode editsNode = arguments == null ? null : arguments.get("edits");
		if (editsNode == null || !editsNode.isArray() || editsNode.isEmpty()) {
			return error("Edits are required");
		}
		String updated = source;
		for (JsonNode edit : editsNode) {
			String find = edit == null ? null : readText(edit, "find");
			String replace = edit == null ? null : readText(edit, "replace");
			if (find == null) {
				continue;
			}
			if (!updated.contains(find)) {
				return error("No matching text found for: " + find);
			}
			String safeReplace = replace == null ? "" : replace;
			updated = updated.replaceAll(Pattern.quote(find), Matcher.quoteReplacement(safeReplace));
		}
		draft.sourceGroovy = updated;
		ObjectNode result = objectMapper.createObjectNode();
		putTextContent(result, "Script updated");
		result.put("length", updated.length());
		return result;
	}

	private JsonNode searchScript(JsonNode arguments, UUID sessionId) {
		ScriptVersion draft = resolveScriptDraft(arguments, sessionId);
		if (draft == null) {
			return error("Draft script not found");
		}
		String patternText = readText(arguments, "pattern");
		if (patternText == null || patternText.isBlank()) {
			return error("Pattern is required");
		}
		int before = readInt(arguments, "before_context", 0);
		int after = readInt(arguments, "after_context", 0);
		Pattern pattern;
		try {
			pattern = Pattern.compile(patternText);
		}
		catch (Exception ex) {
			return error("Invalid regex pattern");
		}
		String source = draft.sourceGroovy == null ? "" : draft.sourceGroovy;
		String[] lines = source.split("\n", -1);
		List<String> output = new ArrayList<>();
		int total = lines.length;
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			Matcher matcher = pattern.matcher(line);
			if (!matcher.find()) {
				continue;
			}
			int start = Math.max(0, i - before);
			int end = Math.min(lines.length - 1, i + after);
			for (int j = start; j <= end; j++) {
				String prefix;
				if (j == i) {
					prefix = (j + 1) + ": ";
				}
				else if (j < i) {
					prefix = (j + 1) + "- ";
				}
				else {
					prefix = (j + 1) + "+ ";
				}
				String rendered = prefix + lines[j];
				if (output.isEmpty() || !output.get(output.size() - 1).equals(rendered)) {
					output.add(rendered);
				}
			}
		}
		ObjectNode result = objectMapper.createObjectNode();
		ArrayNode linesNode = result.putArray("lines");
		output.forEach(linesNode::add);
		result.put("totalCount", total);
		return result;
	}

	private JsonNode readScript(JsonNode arguments, UUID sessionId) {
		ScriptVersion draft = resolveScriptDraft(arguments, sessionId);
		if (draft == null) {
			return error("Draft script not found");
		}
		int start = Math.max(1, readInt(arguments, "start_line", 1));
		int limit = Math.max(1, Math.min(200, readInt(arguments, "limit", 200)));
		String source = draft.sourceGroovy == null ? "" : draft.sourceGroovy;
		String[] lines = source.split("\n", -1);
		int total = lines.length;
		int startIndex = Math.min(total, start) - 1;
		int endIndex = Math.min(total, startIndex + limit);
		ObjectNode result = objectMapper.createObjectNode();
		ArrayNode linesNode = result.putArray("lines");
		for (int i = startIndex; i < endIndex; i++) {
			linesNode.add((i + 1) + ": " + lines[i]);
		}
		result.put("totalCount", total);
		result.put("start_line", start);
		result.put("limit", limit);
		result.put("truncated", endIndex < total);
		return result;
	}

	@Transactional
	JsonNode writeScript(JsonNode arguments, UUID sessionId) {
		ScriptVersion draft = resolveScriptDraft(arguments, sessionId);
		if (draft == null) {
			return error("Draft script not found");
		}
		String content = readText(arguments, "content");
		if (content == null) {
			return error("Content is required");
		}
		String mode = readText(arguments, "mode");
		String source = draft.sourceGroovy == null ? "" : draft.sourceGroovy;
		String updated;
		if ("append".equalsIgnoreCase(mode)) {
			updated = source + content;
		}
		else if ("prepend".equalsIgnoreCase(mode)) {
			updated = content + source;
		}
		else {
			updated = content;
		}
		draft.sourceGroovy = updated;
		ObjectNode result = objectMapper.createObjectNode();
		putTextContent(result, "Script updated");
		result.put("length", updated.length());
		return result;
	}

	private JsonNode getScriptConfig(JsonNode arguments, UUID sessionId) {
		ScriptVersion draft = resolveScriptDraft(arguments, sessionId);
		if (draft == null || draft.script == null) {
			return error("Draft script not found");
		}
		Script script = draft.script;
		ObjectNode result = objectMapper.createObjectNode();
		result.put("name", script.name);
		result.put("slug", script.slug);
		result.put("description", script.description == null ? "" : script.description);
		if (draft.inputSchema != null) {
			result.set("input_schema", draft.inputSchema);
		}
		if (draft.outputSchema != null) {
			result.set("output_schema", draft.outputSchema);
		}
		return result;
	}

	@Transactional
	JsonNode setScriptConfig(JsonNode arguments, UUID sessionId) {
		ScriptVersion draft = resolveScriptDraft(arguments, sessionId);
		if (draft == null || draft.script == null) {
			return error("Draft script not found");
		}
		applyScriptMetadata(draft, arguments);
		ObjectNode result = objectMapper.createObjectNode();
		putTextContent(result, "Script configuration updated");
		return result;
	}

	private JsonNode getScriptApiDefinition(JsonNode arguments) {
		String name = readText(arguments, "name");
		if (name == null || name.isBlank()) {
			return error("API name is required");
		}
		String definition = scriptApiDefinitionService.loadDefinition(name.trim());
		if (definition == null) {
			return error("API definition not found");
		}
		ObjectNode result = objectMapper.createObjectNode();
		result.put("name", name.trim());
		result.put("definition", definition);
		return result;
	}

	private List<ObjectNode> buildScriptTools(McpServer server) {
		List<ObjectNode> tools = new ArrayList<>();
		if (server == null || server.configurationJson == null || server.configurationJson.isNull()) {
			return tools;
		}
		List<Script> scripts = resolveScriptConfig(server.configurationJson, server.workspace == null ? null : server.workspace.id);
		for (Script script : scripts) {
			if (script == null || script.slug == null || script.slug.isBlank()) {
				continue;
			}
			ObjectNode tool = objectMapper.createObjectNode();
			tool.put("name", script.slug);
			tool.put("description", script.description == null ? "" : script.description);
			if (script.activeVersion != null && script.activeVersion.inputSchema != null) {
				tool.set("inputSchema", script.activeVersion.inputSchema);
			}
			if (script.activeVersion != null && script.activeVersion.outputSchema != null) {
				tool.set("outputSchema", script.activeVersion.outputSchema);
			}
			ObjectNode annotations = tool.putObject("annotations");
			ArrayNode scopes = annotations.putArray("scopes");
			scopes.add("execute:script");
			annotations.put("intentTemplate", "Run script: " + script.name);
			tools.add(tool);
		}
		return tools;
	}

	private JsonNode callScriptServerTool(
			McpServer server,
			String toolName,
			JsonNode arguments,
			UUID sessionId,
			UUID userId) {
		if (server == null || server.configurationJson == null || server.configurationJson.isNull()) {
			return error("Script server is not configured");
		}
		UUID workspaceId = server.workspace == null ? null : server.workspace.id;
		if (workspaceId == null) {
			return error("Workspace not found");
		}
		Script script = resolveScriptByToolName(server.configurationJson, workspaceId, toolName);
		if (script == null) {
			return error("Script not found");
		}
		ScriptVersion version = script.activeVersion;
		if (version == null || version.sourceGroovy == null || version.sourceGroovy.isBlank()) {
			return error("Script has no released version");
		}
		try {
			JsonNode output = scriptRuntimeService.run(workspaceId, userId, version.sourceGroovy, arguments, version.inputSchema, version.outputSchema);
			return output;
		}
		catch (WebApplicationException ex) {
			String message = ex.getMessage();
			return error(message == null || message.isBlank() ? "Script failed" : message);
		}
		catch (RuntimeException ex) {
			return error(ex.getMessage() == null ? "Script failed" : ex.getMessage());
		}
	}

	private List<Script> resolveScriptConfig(JsonNode config, UUID workspaceId) {
		if (config == null || workspaceId == null) {
			return List.of();
		}
		JsonNode scriptsNode = config.get("scripts");
		if (scriptsNode == null || !scriptsNode.isArray()) {
			return List.of();
		}
		List<UUID> ids = new ArrayList<>();
		List<String> slugs = new ArrayList<>();
		for (JsonNode entry : scriptsNode) {
			if (entry == null || entry.isNull()) {
				continue;
			}
			String value = entry.asText(null);
			if (value == null || value.isBlank()) {
				continue;
			}
			try {
				ids.add(UUID.fromString(value));
			}
			catch (IllegalArgumentException ex) {
				slugs.add(value.trim().toLowerCase());
			}
		}
		List<Script> results = new ArrayList<>();
		if (!ids.isEmpty()) {
			results.addAll(
				entityManager.createQuery(
						"select s from Script s where s.workspace.id = :workspaceId and s.id in :ids and s.disabled = false",
						Script.class
					)
					.setParameter("workspaceId", workspaceId)
					.setParameter("ids", ids)
					.getResultList()
			);
		}
		if (!slugs.isEmpty()) {
			results.addAll(
				entityManager.createQuery(
						"select s from Script s where s.workspace.id = :workspaceId and "
							+ "lower(s.slug) in :slugs and s.disabled = false",
						Script.class
					)
					.setParameter("workspaceId", workspaceId)
					.setParameter("slugs", slugs)
					.getResultList()
			);
		}
		return results;
	}

	private Script resolveScriptByToolName(JsonNode config, UUID workspaceId, String toolName) {
		if (toolName == null || toolName.isBlank()) {
			return null;
		}
		String slug = toolName.trim().toLowerCase();
		List<Script> scripts = resolveScriptConfig(config, workspaceId);
		for (Script script : scripts) {
			if (script != null && script.slug != null && script.slug.trim().equalsIgnoreCase(slug)) {
				return script;
			}
		}
		return null;
	}

	private JsonNode getApiDefinition(JsonNode arguments) {
		String name = readText(arguments, "name");
		if (name == null || name.isBlank()) {
			return error("API name is required");
		}
		String definition = apiDefinitionService.loadDefinition(name.trim());
		if (definition == null) {
			return error("API definition not found");
		}
		ObjectNode result = objectMapper.createObjectNode();
		result.put("name", name.trim());
		result.put("definition", definition);
		return result;
	}

	private JsonNode getToolDefinition(JsonNode arguments, UUID sessionId) {
		String serviceName = readText(arguments, "service");
		if (serviceName == null || serviceName.isBlank()) {
			serviceName = readText(arguments, "tool");
		}
		if (serviceName == null || serviceName.isBlank()) {
			return error("Backend service is required");
		}
		final String resolvedServiceName = serviceName;
		List<McpServerTool> tools = entityManager.createQuery(
				"select t from McpServerTool t where t.mcpServer.workspace.id = :workspaceId "
					+ "and t.deleted = false and t.mcpServer.internal = false",
				McpServerTool.class
			)
			.setParameter("workspaceId", resolveWorkspaceId(sessionId))
			.getResultList();
		List<McpServerTool> matches = tools.stream()
			.filter(
				item -> resolvedServiceName.equalsIgnoreCase(item.toolName)
					|| (item.toolAlias != null && resolvedServiceName.equalsIgnoreCase(item.toolAlias))
			)
			.toList();
		if (matches.isEmpty()) {
			return error("Backend service not found");
		}
		if (matches.size() > 1) {
			return error("Multiple backend services share that alias");
		}
		McpServerTool tool = matches.get(0);
		McpServer server = tool.mcpServer;
		if (tool == null) {
			return error("Backend service not found");
		}
		ObjectNode result = objectMapper.createObjectNode();
		result.put("server", server.name);
		result.put("service", tool.toolName);
		if (tool.toolAlias != null) {
			result.put("service_alias", tool.toolAlias);
		}
		result.put("description", tool.description == null ? "" : tool.description);
		if (tool.inputSchema != null) {
			result.set("input_schema", tool.inputSchema);
		}
		if (tool.outputSchema != null) {
			result.set("output_schema", tool.outputSchema);
		}
		if (tool.intentTemplate != null) {
			result.put("intent_template", tool.intentTemplate);
		}
		if (tool.inputTemplate != null) {
			result.put("input_template", tool.inputTemplate);
		}
		if (tool.outputTemplate != null) {
			result.put("output_template", tool.outputTemplate);
		}
		if (tool.scopes != null) {
			result.set("scopes", tool.scopes);
		}
		return result;
	}

	private UUID resolveWorkspaceId(UUID sessionId) {
		if (sessionId == null) {
			return null;
		}
		Session session = entityManager.find(Session.class, sessionId);
		return session == null ? null : session.workspace.id;
	}

	@Transactional
	JsonNode editScriptFile(JsonNode arguments, JsonNode meta, UUID sessionId, UUID userId) {
		ScriptVersion draft = resolveScriptDraftByPath(arguments, sessionId, userId, true);
		if (draft == null) {
			return error("Script not found");
		}
		String source = draft.sourceGroovy == null ? "" : draft.sourceGroovy;
		JsonNode editsNode = arguments == null ? null : arguments.get("edits");
		if (editsNode == null || !editsNode.isArray() || editsNode.isEmpty()) {
			return error("Edits are required");
		}
		String updated = source;
		int matchCount = 0;
		for (JsonNode edit : editsNode) {
			String find = edit == null ? null : readText(edit, "find");
			String replace = edit == null ? null : readText(edit, "replace");
			if (find == null) {
				continue;
			}
			int occurrences = countOccurrences(updated, find);
			if (occurrences == 0) {
				return error("No matching text found for: " + find);
			}
			if (occurrences > 1) {
				return error("Expected exactly one match for: " + find + ", found " + occurrences);
			}
			matchCount += occurrences;
			updated = updated.replace(find, replace == null ? "" : replace);
		}
		boolean preview = meta != null && meta.path("preview").asBoolean(false);
		if (!preview) {
			draft.sourceGroovy = updated;
		}
		ObjectNode result = objectMapper.createObjectNode();
		result.put("path", buildScriptPath(draft.script));
		putTextContent(result, preview ? "Script update preview" : "Script updated");
		result.put("length", updated.length());
		result.put("match_count", matchCount);
		putDiffMeta(result, buildScriptPath(draft.script), source, updated);
		result.withObject("/_meta")
			.put("displayMessage", (preview ? "Previewed " : "Replaced ") + matchCount + " occurrences");
		return result;
	}

	@Transactional
	JsonNode editScriptMetadataFile(JsonNode arguments, UUID sessionId, UUID userId) {
		ScriptVersion draft = resolveScriptDraftByPath(arguments, sessionId, userId, true);
		if (draft == null || draft.script == null) {
			return error("Script not found");
		}
		applyScriptMetadata(draft, arguments);
		ObjectNode result = objectMapper.createObjectNode();
		result.put("path", buildScriptPath(draft.script));
		putTextContent(result, "Script metadata updated");
		result.putObject("_meta").put("displayMessage", "Updated script metadata");
		return result;
	}

	@Transactional
	JsonNode writeScriptFile(JsonNode arguments, JsonNode meta, UUID sessionId, UUID userId) {
		ScriptVersion draft = resolveScriptDraftByPath(arguments, sessionId, userId, true);
		if (draft == null) {
			return error("Script not found");
		}
		String content = readText(arguments, "content");
		if (content == null) {
			return error("Content is required");
		}
		String mode = readText(arguments, "mode");
		String source = draft.sourceGroovy == null ? "" : draft.sourceGroovy;
		String updated;
		if ("append".equalsIgnoreCase(mode)) {
			updated = source + content;
		}
		else if ("prepend".equalsIgnoreCase(mode)) {
			updated = content + source;
		}
		else {
			updated = content;
		}
		boolean preview = meta != null && meta.path("preview").asBoolean(false);
		if (!preview) {
			draft.sourceGroovy = updated;
		}
		ObjectNode result = objectMapper.createObjectNode();
		result.put("path", buildScriptPath(draft.script));
		putTextContent(result, preview ? "Script update preview" : "Script updated");
		result.put("length", updated.length());
		putDiffMeta(result, buildScriptPath(draft.script), source, updated);
		result.withObject("/_meta")
			.put("displayMessage", (preview ? "Previewed writing " : "Wrote ") + updated.length() + " chars");
		return result;
	}

	private JsonNode readScriptFile(JsonNode arguments, UUID sessionId, UUID userId) {
		ScriptVersion version = resolveScriptDraftByPath(arguments, sessionId, userId, false);
		if (version == null) {
			return error("Script not found");
		}
		int start = Math.max(1, readInt(arguments, "start_line", 1));
		int limit = readInt(arguments, "limit", 200);
		String source = version.sourceGroovy == null ? "" : version.sourceGroovy;
		String[] lines = source.split("\n", -1);
		int total = lines.length;
		int startIndex = Math.min(total, start) - 1;
		int endIndex = limit <= 0 ? total : Math.min(total, startIndex + Math.max(1, limit));
		ObjectNode result = objectMapper.createObjectNode();
		ArrayNode linesNode = result.putArray("lines");
		for (int i = Math.max(0, startIndex); i < endIndex; i++) {
			linesNode.add((i + 1) + ": " + lines[i]);
		}
		result.put("path", buildScriptPath(version.script));
		result.put("total", total);
		result.put("count", Math.max(0, endIndex - Math.max(0, startIndex)));
		result.put("start_line", start);
		result.put("limit", limit);
		result.put("truncated", endIndex < total);
		putTextContent(result, source);
		return result;
	}

	private JsonNode readScriptMetadataFile(JsonNode arguments, UUID sessionId, UUID userId) {
		ScriptVersion version = resolveScriptDraftByPath(arguments, sessionId, userId, false);
		if (version == null || version.script == null) {
			return error("Script not found");
		}
		Script script = version.script;
		ObjectNode result = objectMapper.createObjectNode();
		result.put("path", buildScriptPath(script));
		result.put("description", script.description == null ? "" : script.description);
		if (version.inputSchema != null) {
			result.set("input_schema", version.inputSchema);
		}
		if (version.outputSchema != null) {
			result.set("output_schema", version.outputSchema);
		}
		return result;
	}

	@Transactional
	JsonNode runScriptFile(JsonNode arguments, UUID sessionId, UUID userId) {
		ScriptVersion version = resolveScriptDraftByPath(arguments, sessionId, userId, false);
		if (version == null || version.script == null) {
			return error("Script not found");
		}
		if (version.releasedAt != null) {
			return error("Script has no draft version");
		}
		if (version.sourceGroovy == null || version.sourceGroovy.isBlank()) {
			return error("Draft has no source");
		}
		try {
			JsonNode output = scriptRuntimeService.runScriptWithSource(
				version.script,
				userId,
				sessionId,
				version.sourceGroovy,
				arguments == null ? null : arguments.get("input"),
				version.inputSchema,
				version.outputSchema
			);
			ObjectNode result = objectMapper.createObjectNode();
			result.put("path", buildScriptPath(version.script));
			result.set("output", output);
			return result;
		}
		catch (WebApplicationException ex) {
			throw ex;
		}
		catch (Exception ex) {
			return error(ex.getMessage() == null ? "Failed to run draft script" : ex.getMessage());
		}
	}

	@Transactional
	JsonNode releaseScriptFile(JsonNode arguments, UUID sessionId, UUID userId) {
		ScriptVersion draft = resolveScriptDraftByPath(arguments, sessionId, userId, false);
		if (draft == null || draft.script == null) {
			return error("Script not found");
		}
		if (draft.releasedAt != null) {
			return error("Script has no draft version");
		}
		if (draft.sourceGroovy == null || draft.sourceGroovy.isBlank()) {
			return error("Draft has no source");
		}
		Script script = draft.script;
		ScriptVersion previous = script.activeVersion;
		if (previous != null && previous != draft && previous.deprecatedAt == null) {
			previous.deprecatedAt = Instant.now();
		}
		draft.releasedBy = userId == null ? null : entityManager.find(User.class, userId);
		draft.releasedAt = Instant.now();
		script.activeVersion = draft;
		ObjectNode result = objectMapper.createObjectNode();
		result.put("path", buildScriptPath(script));
		putTextContent(result, "Draft released");
		result.put("released_version_id", draft.id.toString());
		return result;
	}

	@Transactional
	JsonNode releasePageFile(JsonNode arguments, UUID sessionId, UUID userId) {
		SfcPageVersion draft = resolvePageDraftByPath(arguments, sessionId, userId, false);
		if (draft == null || draft.page == null) {
			return error("Page not found");
		}
		if (draft.releasedAt != null) {
			return error("Page has no draft version");
		}
		if (draft.sourceSfc == null || draft.sourceSfc.isBlank()) {
			return error("Draft has no source");
		}
		if (draft.compiledBundle == null || draft.compiledBundle.isBlank()) {
			if (draft.compileErrors != null && !draft.compileErrors.isBlank()) {
				return error("Draft compilation failed: " + draft.compileErrors);
			}
			return error("Draft has not been compiled successfully");
		}
		if (draft.compileErrors != null && !draft.compileErrors.isBlank()) {
			return error("Draft compilation failed: " + draft.compileErrors);
		}
		SfcPage page = draft.page;
		SfcPageVersion previous = page.activeVersion;
		if (previous != null && previous != draft && previous.deprecatedAt == null) {
			previous.deprecatedAt = Instant.now();
		}
		draft.releasedBy = userId == null ? null : entityManager.find(User.class, userId);
		draft.releasedAt = Instant.now();
		page.activeVersion = draft;
		ObjectNode result = objectMapper.createObjectNode();
		result.put("path", buildPagePath(page));
		putTextContent(result, "Draft released");
		result.put("released_version_id", draft.id.toString());
		return result;
	}

	private JsonNode findScriptFiles(JsonNode arguments, UUID sessionId, UUID userId) {
		UUID workspaceId = resolveWorkspaceId(sessionId);
		if (workspaceId == null) {
			return error("Session is required");
		}
		String root = normalizeScriptRoot(readText(arguments, "root"));
		String pattern = normalizeNullable(readText(arguments, "pattern"));
		int offset = Math.max(0, readInt(arguments, "offset", 0));
		int limit = readInt(arguments, "limit", 200);
		boolean unlimited = limit <= 0;
		int limitValue = unlimited ? Integer.MAX_VALUE : limit;
		Query query = searchDialect.createScriptPathSearchQuery(entityManager, workspaceId, root, pattern, false);
		query.setFirstResult(offset);
		if (!unlimited) {
			query.setMaxResults(limitValue + 1);
		}
		@SuppressWarnings("unchecked")
		List<String> rows = query.getResultList();
		ObjectNode result = objectMapper.createObjectNode();
		ArrayNode array = result.putArray("matches");
		int count = rows.size();
		boolean truncated = false;
		if (!unlimited && count > limitValue) {
			count = limitValue;
			truncated = true;
		}
		for (int i = 0; i < count; i++) {
			array.add(rows.get(i));
		}
		putTextContent(result, count == 0 ? "No scripts found" : array.toString());
		result.put("count", count);
		result.put("offset", offset);
		result.put("limit", limit);
		result.put("truncated", truncated);
		return result;
	}

	private JsonNode searchScriptFiles(JsonNode arguments, UUID sessionId, UUID userId) {
		UUID workspaceId = resolveWorkspaceId(sessionId);
		if (workspaceId == null) {
			return error("Session is required");
		}
		String patternText = readText(arguments, "pattern");
		if (patternText == null || patternText.isBlank()) {
			return error("Pattern is required");
		}
		int context = readInt(arguments, "context", -1);
		int before = context >= 0 ? context : readInt(arguments, "before_context", 0);
		int after = context >= 0 ? context : readInt(arguments, "after_context", 0);
		Pattern pattern;
		try {
			pattern = Pattern.compile(patternText, resolveSearchPatternFlags(arguments, patternText));
		}
		catch (Exception ex) {
			return error("Invalid regex pattern");
		}
		String root = normalizeScriptRoot(readText(arguments, "root"));
		int offset = Math.max(0, readInt(arguments, "offset", 0));
		int requestedLimit = readInt(arguments, "limit", SCRIPT_SEARCH_LIMIT_DEFAULT);
		int limit = requestedLimit <= 0
			? SCRIPT_SEARCH_LIMIT_DEFAULT
			: Math.min(requestedLimit, SCRIPT_SEARCH_LIMIT_MAX);
		List<ObjectNode> fileResults = new ArrayList<>();
		int totalFiles = 0;
		int totalMatches = 0;
		@SuppressWarnings("unchecked")
		Stream<Object[]> rows = searchDialect.createScriptContentSearchQuery(entityManager, workspaceId, root, patternText, offset, limit + 1).getResultStream();
		try (rows) {
			for (Object[] row : rows.toList()) {
				String path = row[0] == null ? null : row[0].toString();
				String source = row[1] == null ? "" : row[1].toString();
				String[] lines = source.split("\n", -1);
				ArrayNode output = objectMapper.createArrayNode();
				int fileMatchCount = 0;
				for (int i = 0; i < lines.length; i++) {
					if (!pattern.matcher(lines[i]).find()) {
						continue;
					}
					fileMatchCount++;
					int start = Math.max(0, i - before);
					int end = Math.min(lines.length - 1, i + after);
					for (int j = start; j <= end; j++) {
						String separator;
						if (j == i) {
							separator = ":";
						}
						else if (j < i) {
							separator = "-";
						}
						else {
							separator = "+";
						}
						String rendered = path + separator + (j + 1) + ":" + lines[j];
						if (output.isEmpty() || !rendered.equals(output.get(output.size() - 1).asText())) {
							output.add(rendered);
						}
					}
				}
				if (fileMatchCount == 0) {
					continue;
				}
				totalFiles++;
				totalMatches += fileMatchCount;
				if (fileResults.size() < SCRIPT_SEARCH_BUFFER_LIMIT) {
					ObjectNode file = objectMapper.createObjectNode();
					file.put("path", path);
					file.put("count", fileMatchCount);
					file.set("matches", output);
					fileResults.add(file);
				}
			}
		}
		boolean hitLimitReached = totalFiles > limit;
		return buildTruncatedScriptSearchResult(
			fileResults,
			patternText,
			root,
			Math.min(totalFiles, limit),
			totalMatches,
			offset,
			requestedLimit,
			hitLimitReached
		);
	}

	@Transactional
	JsonNode editPageFile(JsonNode arguments, JsonNode meta, UUID sessionId, UUID userId) {
		SfcPageVersion draft = resolvePageDraftByPath(arguments, sessionId, userId, true);
		if (draft == null) {
			return error("Page not found");
		}
		String source = draft.sourceSfc == null ? "" : draft.sourceSfc;
		JsonNode editsNode = arguments == null ? null : arguments.get("edits");
		if (editsNode == null || !editsNode.isArray() || editsNode.isEmpty()) {
			return error("Edits are required");
		}
		String updated = source;
		int matchCount = 0;
		for (JsonNode edit : editsNode) {
			String find = edit == null ? null : readText(edit, "find");
			String replace = edit == null ? null : readText(edit, "replace");
			if (find == null) {
				continue;
			}
			int occurrences = countOccurrences(updated, find);
			if (occurrences == 0) {
				return error("No matching text found for: " + find);
			}
			if (occurrences > 1) {
				return error("Expected exactly one match for: " + find + ", found " + occurrences);
			}
			matchCount += occurrences;
			updated = updated.replace(find, replace == null ? "" : replace);
		}
		boolean preview = meta != null && meta.path("preview").asBoolean(false);
		if (!preview) {
			draft.sourceSfc = updated;
		}
		ObjectNode result = objectMapper.createObjectNode();
		result.put("path", buildPagePath(draft.page));
		result.put("length", updated.length());
		result.put("match_count", matchCount);
		putDiffMeta(result, buildPagePath(draft.page), source, updated);
		result.withObject("/_meta")
			.put("displayMessage", (preview ? "Previewed " : "Replaced ") + matchCount + " occurrences");
		return finalizePageWriteResult(preview ? null : draft, result, preview ? "Page update preview" : "Page updated");
	}

	@Transactional
	JsonNode editPageMetadataFile(JsonNode arguments, UUID sessionId, UUID userId) {
		SfcPageVersion draft = resolvePageDraftByPath(arguments, sessionId, userId, true);
		if (draft == null || draft.page == null) {
			return error("Page not found");
		}
		applyPageMetadata(draft.page, arguments);
		ObjectNode result = objectMapper.createObjectNode();
		result.put("path", buildPagePath(draft.page));
		putTextContent(result, "Page metadata updated");
		return result;
	}

	@Transactional
	JsonNode writePageFile(JsonNode arguments, JsonNode meta, UUID sessionId, UUID userId) {
		SfcPageVersion draft = resolvePageDraftByPath(arguments, sessionId, userId, true);
		if (draft == null) {
			return error("Page not found");
		}
		String content = readText(arguments, "content");
		if (content == null) {
			return error("Content is required");
		}
		String mode = readText(arguments, "mode");
		String source = draft.sourceSfc == null ? "" : draft.sourceSfc;
		String updated;
		if ("append".equalsIgnoreCase(mode)) {
			updated = source + content;
		}
		else if ("prepend".equalsIgnoreCase(mode)) {
			updated = content + source;
		}
		else {
			updated = content;
		}
		boolean preview = meta != null && meta.path("preview").asBoolean(false);
		if (!preview) {
			draft.sourceSfc = updated;
		}
		ObjectNode result = objectMapper.createObjectNode();
		result.put("path", buildPagePath(draft.page));
		result.put("length", updated.length());
		putDiffMeta(result, buildPagePath(draft.page), source, updated);
		result.withObject("/_meta")
			.put("displayMessage", (preview ? "Previewed writing " : "Wrote ") + updated.length() + " chars");
		return finalizePageWriteResult(preview ? null : draft, result, preview ? "Page update preview" : "Page updated");
	}

	private ObjectNode finalizePageWriteResult(SfcPageVersion draft, ObjectNode result, String successMessage) {
		if (draft == null) {
			putTextContent(result, successMessage);
			return result;
		}
		if (!compilePagesOnWrite) {
			draft.compiledBundle = null;
			draft.compileErrors = null;
			putTextContent(result, successMessage);
			return result;
		}
		PageCompilationResult compilation = compilePageDraftForTools(draft);
		draft.compiledBundle = compilation.compiledBundle() == null
				|| compilation.compiledBundle().isBlank()
			? null
			: compilation.compiledBundle();
		draft.compileErrors = compilation.compileErrors() == null
				|| compilation.compileErrors().isBlank()
			? null
			: compilation.compileErrors();
		if (draft.compileErrors != null && !draft.compileErrors.isBlank()) {
			draft.compiledBundle = null;
			result.put("compile_errors", draft.compileErrors);
			if (compilation.compileErrorDetail() != null && !compilation.compileErrorDetail().isBlank()) {
				result.put("compile_error_detail", compilation.compileErrorDetail());
			}
			putTextContent(
				result,
				successMessage + ". Changes were persisted, but compilation reported problems: "
					+ draft.compileErrors
			);
			return result;
		}
		putTextContent(result, successMessage + ". Changes were persisted and compilation succeeded.");
		return result;
	}

	private PageCompilationResult compilePageDraftForTools(SfcPageVersion draft) {
		Map<String, String> workspaceImports = workspaceExternalFrontendImports(draft.page);
		List<String> allowlist = new ArrayList<>(pageImportAllowlist(draft.page));
		for (String specifier : workspaceImports.keySet()) {
			if (!allowlist.contains(specifier)) {
				allowlist.add(specifier);
			}
		}
		return pageCompilerService.compile(
			new PageCompilationRequest(
				draft.sourceSfc,
				allowlist,
				workspaceImports,
				draft.page == null || draft.page.id == null ? "page" : draft.page.id.toString(),
				availablePageModulesForWorkspace(draft.page)
			)
		);
	}

	private List<String> pageImportAllowlist(SfcPage page) {
		if (page == null || page.importAllowlist == null || !page.importAllowlist.isArray()) {
			return List.of();
		}
		List<String> allowlist = new ArrayList<>();
		for (JsonNode entry : page.importAllowlist) {
			if (entry != null && entry.isTextual() && !entry.asText().isBlank()) {
				allowlist.add(entry.asText());
			}
		}
		return allowlist;
	}

	private Map<String, String> workspaceExternalFrontendImports(SfcPage page) {
		if (page == null
				|| page.workspace == null
				|| page.workspace.externalFrontendImports == null
				|| !page.workspace.externalFrontendImports.isArray()) {
			return Map.of();
		}
		Map<String, String> imports = new HashMap<>();
		for (JsonNode entry : page.workspace.externalFrontendImports) {
			if (entry == null || !entry.isObject()) {
				continue;
			}
			JsonNode specifierNode = entry.get("specifier");
			JsonNode globalNode = entry.get("global_name");
			JsonNode urlNode = entry.get("source_url");
			if (specifierNode == null || !specifierNode.isTextual() || specifierNode.asText().isBlank()) {
				continue;
			}
			if (globalNode == null || !globalNode.isTextual() || globalNode.asText().isBlank()) {
				continue;
			}
			if (urlNode == null || !urlNode.isTextual() || urlNode.asText().isBlank()) {
				continue;
			}
			imports.put(specifierNode.asText(), globalNode.asText());
		}
		return imports;
	}

	private JsonNode listWorkspaceExternalFrontendImports(JsonNode arguments, UUID sessionId, UUID userId) {
		Workspace workspace = resolveWorkspaceForPageImports(sessionId);
		if (workspace == null) {
			return error("Workspace not found");
		}
		ObjectNode result = objectMapper.createObjectNode();
		ArrayNode importArray = workspace.externalFrontendImports != null
				&& workspace.externalFrontendImports.isArray()
			? (ArrayNode) workspace.externalFrontendImports.deepCopy()
			: objectMapper.createArrayNode();
		ObjectNode structured = result.putObject("structuredContent");
		structured.set("external_frontend_imports", importArray);
		structured.put("entry_count", importArray.size());
		result.withObject("/_meta")
			.put("displayMessage", "Loaded " + importArray.size() + " workspace external imports");
		putTextContent(result, importArray.isEmpty() ? "[]" : importArray.toPrettyString());
		return result;
	}

	private JsonNode setWorkspaceExternalFrontendImports(JsonNode arguments, UUID sessionId, UUID userId) {
		Workspace workspace = resolveWorkspaceForPageImports(sessionId);
		if (workspace == null) {
			return error("Workspace not found");
		}
		JsonNode imports = arguments == null ? null : arguments.get("external_frontend_imports");
		if (imports == null) {
			return error("external_frontend_imports is required");
		}
		workspace.externalFrontendImports = imports.deepCopy();
		entityManager.flush();
		ObjectNode result = objectMapper.createObjectNode();
		ArrayNode importArray = workspace.externalFrontendImports != null
				&& workspace.externalFrontendImports.isArray()
			? (ArrayNode) workspace.externalFrontendImports.deepCopy()
			: objectMapper.createArrayNode();
		ObjectNode structured = result.putObject("structuredContent");
		structured.set("external_frontend_imports", importArray);
		structured.put("entry_count", importArray.size());
		result.withObject("/_meta")
			.put("displayMessage", "Updated workspace external imports with " + importArray.size() + " entries");
		putTextContent(result, "Workspace external frontend imports updated");
		return result;
	}

	private Workspace resolveWorkspaceForPageImports(UUID sessionId) {
		UUID workspaceId = resolveWorkspaceId(sessionId);
		if (workspaceId == null) {
			return null;
		}
		return entityManager.find(Workspace.class, workspaceId);
	}

	private Map<String, String> availablePageModulesForWorkspace(SfcPage page) {
		return page == null
				|| page.workspace == null
			? Map.of()
			: availablePageModulesForWorkspace(page.workspace.id);
	}

	private Map<String, String> availablePageModulesForWorkspace(UUID workspaceId) {
		if (workspaceId == null) {
			return Map.of();
		}
		List<SfcPage> pages = entityManager.createQuery(
				"select p from SfcPage p where p.workspace.id = :workspaceId and p.disabled = false",
				SfcPage.class
			)
			.setParameter("workspaceId", workspaceId)
			.getResultList();
		Map<String, String> modules = new HashMap<>();
		for (SfcPage entry : pages) {
			String path = buildPagePath(entry);
			if (path != null && !path.isBlank()) {
				modules.put(path, path);
			}
		}
		return modules;
	}

	private JsonNode readPageFile(JsonNode arguments, UUID sessionId, UUID userId) {
		SfcPageVersion version = resolvePageDraftByPath(arguments, sessionId, userId, false);
		if (version == null) {
			return error("Page not found");
		}
		int start = Math.max(1, readInt(arguments, "start_line", 1));
		int limit = readInt(arguments, "limit", 200);
		String source = version.sourceSfc == null ? "" : version.sourceSfc;
		String[] lines = source.split("\n", -1);
		int total = lines.length;
		int startIndex = Math.min(total, start) - 1;
		int endIndex = limit <= 0 ? total : Math.min(total, startIndex + Math.max(1, limit));
		ObjectNode result = objectMapper.createObjectNode();
		ArrayNode linesNode = result.putArray("lines");
		for (int i = Math.max(0, startIndex); i < endIndex; i++) {
			linesNode.add((i + 1) + ": " + lines[i]);
		}
		result.put("path", buildPagePath(version.page));
		result.put("total", total);
		result.put("count", Math.max(0, endIndex - Math.max(0, startIndex)));
		result.put("start_line", start);
		result.put("limit", limit);
		result.put("truncated", endIndex < total);
		putTextContent(result, source);
		return result;
	}

	private JsonNode createOrUpdateCanvas(JsonNode arguments, UUID sessionId, UUID userId) {
		if (sessionId == null) {
			return error("Session is required");
		}
		Session session = entityManager.find(Session.class, sessionId);
		if (session == null || session.workspace == null) {
			return error("Session not found");
		}
		JsonNode canvasArray = arguments == null ? null : arguments.get("canvases");
		if (!(canvasArray instanceof ArrayNode canvases) || canvases.isEmpty()) {
			return error("canvases is required");
		}
		ArrayNode results = objectMapper.createArrayNode();
		ArrayNode failures = objectMapper.createArrayNode();
		List<SessionCanvas> persisted = new ArrayList<>();
		User updatedBy = userId == null ? null : entityManager.find(User.class, userId);
		for (JsonNode canvasNode : canvases) {
			String logicalId = readText(canvasNode, "logical_id");
			String title = readText(canvasNode, "title");
			String content = readText(canvasNode, "content");
			ObjectNode entry = results.addObject();
			entry.put("logical_id", logicalId == null ? "" : logicalId);
			if (logicalId == null || logicalId.isBlank()) {
				entry.put("error", "logical_id is required");
				failures.add(entry.deepCopy());
				continue;
			}
			if (title == null || title.isBlank()) {
				entry.put("error", "title is required");
				failures.add(entry.deepCopy());
				continue;
			}
			if (title.trim().split("\\s+").length > 5) {
				entry.put("error", "title must be five words or fewer");
				failures.add(entry.deepCopy());
				continue;
			}
			if (content == null || content.isBlank()) {
				entry.put("error", "content is required");
				failures.add(entry.deepCopy());
				continue;
			}
			Map<String, String> workspaceImports = workspaceExternalFrontendImports(session.workspace);
			PageCompilationResult compilation = pageCompilerService.compile(
				new PageCompilationRequest(
					content,
					new ArrayList<>(workspaceImports.keySet()),
					workspaceImports,
					logicalId,
					availablePageModulesForWorkspace(session.workspace.id)
				)
			);
			String compileErrors = compilation.compileErrors() == null ? "" : compilation.compileErrors().trim();
			if (!compileErrors.isBlank()) {
				entry.put("title", title);
				entry.put("error", compileErrors);
				if (compilation.compileErrorDetail() != null && !compilation.compileErrorDetail().isBlank()) {
					entry.put("error_detail", compilation.compileErrorDetail());
				}
				failures.add(entry.deepCopy());
				continue;
			}
			SessionCanvas canvas = entityManager.createQuery(
					"select c from SessionCanvas c where c.session.id = :sessionId and c.logicalId = :logicalId",
					SessionCanvas.class
				)
				.setParameter("sessionId", sessionId)
				.setParameter("logicalId", logicalId)
				.getResultStream()
				.findFirst()
				.orElse(null);
			boolean newCanvas = false;
			if (canvas == null) {
				canvas = new SessionCanvas();
				canvas.session = session;
				canvas.logicalId = logicalId;
				newCanvas = true;
			}
			canvas.updatedBy = updatedBy;
			canvas.title = title.trim();
			canvas.sourceSfc = content;
			canvas.compiledBundle = compilation.compiledBundle() == null ? "" : compilation.compiledBundle();
			if (newCanvas) {
				entityManager.persist(canvas);
			}
			persisted.add(canvas);
			entry.put("id", canvas.id == null ? "" : canvas.id.toString());
			entry.put("logical_id", canvas.logicalId);
			entry.put("title", canvas.title);
			entry.put("updated", true);
		}
		entityManager.flush();
		if (!persisted.isEmpty()) {
			ArrayNode payload = objectMapper.createArrayNode();
			for (SessionCanvas canvas : persisted) {
				ObjectNode canvasPayload = payload.addObject();
				if (canvas.id == null) {
					canvasPayload.putNull("id");
				}
				else {
					canvasPayload.putPOJO("id", canvas.id);
				}
				canvasPayload.put("logical_id", canvas.logicalId);
				canvasPayload.put("title", canvas.title);
				canvasPayload.put("source_sfc", canvas.sourceSfc);
				canvasPayload.put("compiled_bundle", canvas.compiledBundle);
				if (canvas.session == null
						|| canvas.session.workspace == null
						|| canvas.session.workspace.externalFrontendImports == null) {
					canvasPayload.putNull("external_frontend_imports");
				}
				else {
					canvasPayload.set("external_frontend_imports", canvas.session.workspace.externalFrontendImports.deepCopy());
				}
				if (canvas.updatedAt == null) {
					canvasPayload.putNull("updated_at");
				}
				else {
					canvasPayload.putPOJO("updated_at", canvas.updatedAt);
				}
			}
			socketManager.broadcastToSession(
				session.id,
				new WorkspaceSocketEvent("session.canvases", session.workspace.id, session.id, payload)
			);
		}
		ObjectNode result = objectMapper.createObjectNode();
		result.set("results", results);
		if (!failures.isEmpty()) {
			result.set("failures", failures);
			result.put("isError", true);
			StringBuilder message = new StringBuilder("One or more canvases failed to compile.");
			for (JsonNode failure : failures) {
				String failureTitle = readText(failure, "title");
				String failureLogicalId = readText(failure, "logical_id");
				String failureError = readText(failure, "error");
				String failureDetail = readText(failure, "error_detail");
				message.append("\n- ");
				if (failureTitle != null && !failureTitle.isBlank()) {
					message.append(failureTitle.trim());
				}
				else if (failureLogicalId != null && !failureLogicalId.isBlank()) {
					message.append(failureLogicalId.trim());
				}
				else {
					message.append("Canvas");
				}
				if (failureError != null && !failureError.isBlank()) {
					message.append(": ").append(failureError.trim());
				}
				if (failureDetail != null && !failureDetail.isBlank()) {
					message.append("\n  ").append(failureDetail.trim().replace("\n", "\n  "));
				}
			}
			putTextContent(result, message.toString());
			return result;
		}
		putTextContent(result, persisted.size() == 1 ? "Canvas updated successfully." : "Canvases updated successfully.");
		return result;
	}

	private JsonNode refreshCanvas(JsonNode arguments, UUID sessionId) {
		if (sessionId == null) {
			return error("Session is required");
		}
		String logicalId = readText(arguments, "logical_id");
		if (logicalId == null || logicalId.isBlank()) {
			return error("logical_id is required");
		}
		SessionCanvas canvas = entityManager.createQuery(
				"select c from SessionCanvas c where c.session.id = :sessionId and c.logicalId = :logicalId",
				SessionCanvas.class
			)
			.setParameter("sessionId", sessionId)
			.setParameter("logicalId", logicalId)
			.getResultStream()
			.findFirst()
			.orElse(null);
		if (canvas == null || canvas.session == null || canvas.session.workspace == null) {
			return error("Canvas not found");
		}
		ArrayNode payload = objectMapper.createArrayNode();
		ObjectNode canvasPayload = payload.addObject();
		if (canvas.id == null) {
			canvasPayload.putNull("id");
		}
		else {
			canvasPayload.putPOJO("id", canvas.id);
		}
		canvasPayload.put("logical_id", canvas.logicalId);
		canvasPayload.put("title", canvas.title);
		canvasPayload.put("source_sfc", canvas.sourceSfc);
		canvasPayload.put("compiled_bundle", canvas.compiledBundle);
		if (canvas.session.workspace.externalFrontendImports == null) {
			canvasPayload.putNull("external_frontend_imports");
		}
		else {
			canvasPayload.set("external_frontend_imports", canvas.session.workspace.externalFrontendImports.deepCopy());
		}
		if (canvas.updatedAt == null) {
			canvasPayload.putNull("updated_at");
		}
		else {
			canvasPayload.putPOJO("updated_at", canvas.updatedAt);
		}
		socketManager.broadcastToSession(
			canvas.session.id,
			new WorkspaceSocketEvent("session.canvases", canvas.session.workspace.id, canvas.session.id, payload)
		);
		ObjectNode result = objectMapper.createObjectNode();
		result.put("logical_id", canvas.logicalId);
		result.put("refreshed", true);
		putTextContent(result, "Canvas refreshed successfully.");
		return result;
	}

	private Map<String, String> workspaceExternalFrontendImports(Workspace workspace) {
		if (workspace == null
				|| workspace.externalFrontendImports == null
				|| !workspace.externalFrontendImports.isArray()) {
			return Map.of();
		}
		Map<String, String> imports = new HashMap<>();
		for (JsonNode entry : workspace.externalFrontendImports) {
			if (entry == null || !entry.isObject()) {
				continue;
			}
			JsonNode specifierNode = entry.get("specifier");
			JsonNode globalNode = entry.get("global_name");
			JsonNode urlNode = entry.get("source_url");
			if (specifierNode == null || !specifierNode.isTextual() || specifierNode.asText().isBlank()) {
				continue;
			}
			if (globalNode == null || !globalNode.isTextual() || globalNode.asText().isBlank()) {
				continue;
			}
			if (urlNode == null || !urlNode.isTextual() || urlNode.asText().isBlank()) {
				continue;
			}
			imports.put(specifierNode.asText(), globalNode.asText());
		}
		return imports;
	}

	private JsonNode readPageMetadataFile(JsonNode arguments, UUID sessionId, UUID userId) {
		SfcPageVersion version = resolvePageDraftByPath(arguments, sessionId, userId, false);
		if (version == null || version.page == null) {
			return error("Page not found");
		}
		SfcPage page = version.page;
		ObjectNode result = objectMapper.createObjectNode();
		result.put("path", buildPagePath(page));
		result.put("name", page.name == null ? "" : page.name);
		result.put("slug", page.slug == null ? "" : page.slug);
		result.put("description", page.description == null ? "" : page.description);
		result.put("route_suffix", page.routeSuffix == null ? "" : page.routeSuffix);
		result.put("usage_guide", page.usageGuide == null ? "" : page.usageGuide);
		result.put("icon_svg", page.iconSvg == null ? "" : page.iconSvg);
		result.put("type", page.type == null ? "PAGE" : page.type.name());
		result.put("menu_visible", page.menuVisible);
		if (page.queryParams != null) {
			result.set("query_params", page.queryParams);
		}
		if (page.inputParams != null) {
			result.set("input_params", page.inputParams);
		}
		if (page.importAllowlist != null) {
			result.set("import_allowlist", page.importAllowlist);
		}
		return result;
	}

	private JsonNode findPageFiles(JsonNode arguments, UUID sessionId, UUID userId) {
		UUID workspaceId = resolveWorkspaceId(sessionId);
		if (workspaceId == null) {
			return error("Session is required");
		}
		String root = normalizeScriptRoot(readText(arguments, "root"));
		String pattern = normalizeNullable(readText(arguments, "pattern"));
		String pageType = normalizeNullable(readText(arguments, "type"));
		if (pageType != null) {
			pageType = pageType.trim().toUpperCase();
			if (!"PAGE".equals(pageType) && !"COMPONENT".equals(pageType)) {
				return error("Invalid type. Expected PAGE or COMPONENT");
			}
		}
		int offset = Math.max(0, readInt(arguments, "offset", 0));
		int limit = readInt(arguments, "limit", 200);
		boolean unlimited = limit <= 0;
		int limitValue = unlimited ? Integer.MAX_VALUE : limit;
		Query query = searchDialect.createPagePathSearchQuery(entityManager, workspaceId, root, pattern, pageType, false);
		query.setFirstResult(offset);
		if (!unlimited) {
			query.setMaxResults(limitValue + 1);
		}
		@SuppressWarnings("unchecked")
		List<String> rows = query.getResultList();
		ObjectNode result = objectMapper.createObjectNode();
		ArrayNode array = result.putArray("matches");
		int count = rows.size();
		boolean truncated = false;
		if (!unlimited && count > limitValue) {
			count = limitValue;
			truncated = true;
		}
		for (int i = 0; i < count; i++) {
			array.add(rows.get(i));
		}
		putTextContent(result, count == 0 ? "No pages found" : array.toString());
		result.put("count", count);
		result.put("offset", offset);
		result.put("limit", limit);
		result.put("truncated", truncated);
		return result;
	}

	private JsonNode searchPageFiles(JsonNode arguments, UUID sessionId, UUID userId) {
		UUID workspaceId = resolveWorkspaceId(sessionId);
		if (workspaceId == null) {
			return error("Session is required");
		}
		String patternText = readText(arguments, "pattern");
		if (patternText == null || patternText.isBlank()) {
			return error("Pattern is required");
		}
		int context = readInt(arguments, "context", -1);
		int before = context >= 0 ? context : readInt(arguments, "before_context", 0);
		int after = context >= 0 ? context : readInt(arguments, "after_context", 0);
		Pattern pattern;
		try {
			pattern = Pattern.compile(patternText, resolveSearchPatternFlags(arguments, patternText));
		}
		catch (Exception ex) {
			return error("Invalid regex pattern");
		}
		String root = normalizeScriptRoot(readText(arguments, "root"));
		int offset = Math.max(0, readInt(arguments, "offset", 0));
		int requestedLimit = readInt(arguments, "limit", SCRIPT_SEARCH_LIMIT_DEFAULT);
		int limit = requestedLimit <= 0
			? SCRIPT_SEARCH_LIMIT_DEFAULT
			: Math.min(requestedLimit, SCRIPT_SEARCH_LIMIT_MAX);
		List<ObjectNode> fileResults = new ArrayList<>();
		int totalFiles = 0;
		int totalMatches = 0;
		@SuppressWarnings("unchecked")
		Stream<Object[]> rows = searchDialect.createPageContentSearchQuery(entityManager, workspaceId, root, patternText, offset, limit + 1).getResultStream();
		try (rows) {
			for (Object[] row : rows.toList()) {
				String path = row[0] == null ? null : row[0].toString();
				String source = row[1] == null ? "" : row[1].toString();
				String[] lines = source.split("\n", -1);
				ArrayNode output = objectMapper.createArrayNode();
				int fileMatchCount = 0;
				for (int i = 0; i < lines.length; i++) {
					if (!pattern.matcher(lines[i]).find()) {
						continue;
					}
					fileMatchCount++;
					int start = Math.max(0, i - before);
					int end = Math.min(lines.length - 1, i + after);
					for (int j = start; j <= end; j++) {
						String separator;
						if (j == i) {
							separator = ":";
						}
						else if (j < i) {
							separator = "-";
						}
						else {
							separator = "+";
						}
						String rendered = path + separator + (j + 1) + ":" + lines[j];
						if (output.isEmpty() || !rendered.equals(output.get(output.size() - 1).asText())) {
							output.add(rendered);
						}
					}
				}
				if (fileMatchCount == 0) {
					continue;
				}
				totalFiles++;
				totalMatches += fileMatchCount;
				if (fileResults.size() < SCRIPT_SEARCH_BUFFER_LIMIT) {
					ObjectNode file = objectMapper.createObjectNode();
					file.put("path", path);
					file.put("count", fileMatchCount);
					file.set("matches", output);
					fileResults.add(file);
				}
			}
		}
		boolean hitLimitReached = totalFiles > limit;
		return buildTruncatedPageSearchResult(
			fileResults,
			patternText,
			root,
			Math.min(totalFiles, limit),
			totalMatches,
			offset,
			requestedLimit,
			hitLimitReached
		);
	}

	private ScriptVersion resolveScriptDraftByPath(JsonNode arguments, UUID sessionId, UUID userId, boolean createDraft) {
		UUID workspaceId = resolveWorkspaceId(sessionId);
		if (workspaceId == null) {
			return null;
		}
		String path = normalizeNullable(readText(arguments, "path"));
		if (path == null) {
			return null;
		}
		Script script = resolveScriptByPath(workspaceId, path);
		if (script == null) {
			if (!createDraft) {
				return null;
			}
			script = createScriptByPath(workspaceId, path, userId);
			if (script == null) {
				return null;
			}
		}
		if (!createDraft) {
			return resolveDraftOrActive(script.id);
		}
		ScriptVersion draft = entityManager.createQuery(
				"select v from ScriptVersion v where v.script.id = :scriptId and v.releasedAt is null",
				ScriptVersion.class
			)
			.setParameter("scriptId", script.id)
			.getResultStream()
			.findFirst()
			.orElse(null);
		if (draft != null) {
			return draft;
		}
		draft = new ScriptVersion();
		draft.script = script;
		draft.version = nextScriptVersionNumber(script.id);
		if (userId != null) {
			draft.createdBy = entityManager.find(User.class, userId);
		}
		if (script.activeVersion != null) {
			draft.sourceGroovy = script.activeVersion.sourceGroovy;
		}
		entityManager.persist(draft);
		return draft;
	}

	private SfcPageVersion resolvePageDraftByPath(JsonNode arguments, UUID sessionId, UUID userId, boolean createDraft) {
		UUID workspaceId = resolveWorkspaceId(sessionId);
		if (workspaceId == null) {
			return null;
		}
		String path = normalizeNullable(readText(arguments, "path"));
		if (path == null) {
			return null;
		}
		SfcPage page = resolvePageByPath(workspaceId, path);
		if (page == null) {
			if (!createDraft) {
				return null;
			}
			page = createPageByPath(workspaceId, path, userId);
			if (page == null) {
				return null;
			}
		}
		if (!createDraft) {
			return resolvePageDraftOrActive(page.id);
		}
		SfcPageVersion draft = entityManager.createQuery(
				"select v from SfcPageVersion v where v.page.id = :pageId and v.releasedAt is null",
				SfcPageVersion.class
			)
			.setParameter("pageId", page.id)
			.getResultStream()
			.findFirst()
			.orElse(null);
		if (draft != null) {
			return draft;
		}
		draft = new SfcPageVersion();
		draft.page = page;
		draft.version = nextPageVersionNumber(page.id);
		if (userId != null) {
			draft.createdBy = entityManager.find(User.class, userId);
		}
		if (page.activeVersion != null) {
			draft.sourceSfc = page.activeVersion.sourceSfc;
			draft.compiledBundle = page.activeVersion.compiledBundle;
			draft.compileErrors = page.activeVersion.compileErrors;
		}
		entityManager.persist(draft);
		return draft;
	}

	private SfcPageVersion resolvePageDraftOrActive(UUID pageId) {
		if (pageId == null) {
			return null;
		}
		SfcPageVersion draft = entityManager.createQuery(
				"select v from SfcPageVersion v where v.page.id = :pageId and v.releasedAt is null",
				SfcPageVersion.class
			)
			.setParameter("pageId", pageId)
			.getResultStream()
			.findFirst()
			.orElse(null);
		if (draft != null) {
			return draft;
		}
		SfcPage page = entityManager.find(SfcPage.class, pageId);
		return page == null ? null : page.activeVersion;
	}

	private SfcPage resolvePageByPath(UUID workspaceId, String path) {
		ScriptPathParts parts = parsePagePath(path);
		if (workspaceId == null || parts == null) {
			return null;
		}
		String slug = SlugSupport.buildSlug(parts.namespace(), parts.name());
		List<SfcPage> matches = entityManager.createQuery(
				"select p from SfcPage p where p.workspace.id = :workspaceId and p.disabled "
					+ "= false and lower(p.slug) = :slug",
				SfcPage.class
			)
			.setParameter("workspaceId", workspaceId)
			.setParameter("slug", slug.toLowerCase())
			.getResultList();
		return matches.isEmpty() ? null : matches.get(0);
	}

	private SfcPage createPageByPath(UUID workspaceId, String path, UUID userId) {
		ScriptPathParts parts = parsePagePath(path);
		if (workspaceId == null || parts == null) {
			return null;
		}
		Workspace workspace = entityManager.find(Workspace.class, workspaceId);
		if (workspace == null) {
			return null;
		}
		SfcPage page = new SfcPage();
		page.workspace = workspace;
		page.name = parts.name();
		page.namespace = parts.namespace();
		page.slug = SlugSupport.buildSlug(parts.namespace(), parts.name());
		if (userId != null) {
			page.createdBy = entityManager.find(User.class, userId);
		}
		entityManager.persist(page);
		return page;
	}

	private ScriptPathParts parsePagePath(String path) {
		if (path == null || path.isBlank()) {
			return null;
		}
		String normalizedPath = path.trim();
		while (normalizedPath.startsWith("/")) {
			normalizedPath = normalizedPath.substring(1);
		}
		while (normalizedPath.endsWith("/")) {
			normalizedPath = normalizedPath.substring(0, normalizedPath.length() - 1);
		}
		if (normalizedPath.endsWith(".vue")) {
			normalizedPath = normalizedPath.substring(0, normalizedPath.length() - 4);
		}
		if (normalizedPath.isBlank()) {
			return null;
		}
		int slash = normalizedPath.lastIndexOf('/');
		String namespace = slash >= 0 ? normalizedPath.substring(0, slash) : null;
		String name = slash >= 0 ? normalizedPath.substring(slash + 1) : normalizedPath;
		if (name.isBlank()) {
			return null;
		}
		return new ScriptPathParts(normalizeNullable(namespace), name.trim());
	}

	private ScriptVersion resolveDraftOrActive(UUID scriptId) {
		if (scriptId == null) {
			return null;
		}
		ScriptVersion draft = entityManager.createQuery(
				"select v from ScriptVersion v where v.script.id = :scriptId and v.releasedAt is null",
				ScriptVersion.class
			)
			.setParameter("scriptId", scriptId)
			.getResultStream()
			.findFirst()
			.orElse(null);
		if (draft != null) {
			return draft;
		}
		Script script = entityManager.find(Script.class, scriptId);
		return script == null ? null : script.activeVersion;
	}

	private int nextScriptVersionNumber(UUID scriptId) {
		Integer currentMax = entityManager.createQuery("select max(v.version) from ScriptVersion v where v.script.id = :scriptId", Integer.class)
			.setParameter("scriptId", scriptId)
			.getSingleResult();
		return currentMax == null ? 1 : currentMax + 1;
	}

	private int nextPageVersionNumber(UUID pageId) {
		Integer currentMax = entityManager.createQuery("select max(v.version) from SfcPageVersion v where v.page.id = :pageId", Integer.class)
			.setParameter("pageId", pageId)
			.getSingleResult();
		return currentMax == null ? 1 : currentMax + 1;
	}

	private Script resolveScriptByPath(UUID workspaceId, String path) {
		ScriptPathParts parts = parseScriptPath(path);
		if (workspaceId == null || parts == null) {
			return null;
		}
		String slug = SlugSupport.buildSlug(parts.namespace(), parts.name());
		List<Script> matches = entityManager.createQuery(
				"select s from Script s where s.workspace.id = :workspaceId and s.disabled "
					+ "= false and lower(s.slug) = :slug",
				Script.class
			)
			.setParameter("workspaceId", workspaceId)
			.setParameter("slug", slug.toLowerCase())
			.getResultList();
		return matches.isEmpty() ? null : matches.get(0);
	}

	private Script createScriptByPath(UUID workspaceId, String path, UUID userId) {
		ScriptPathParts parts = parseScriptPath(path);
		if (workspaceId == null || parts == null) {
			return null;
		}
		Workspace workspace = entityManager.find(Workspace.class, workspaceId);
		if (workspace == null) {
			return null;
		}
		Script script = new Script();
		script.workspace = workspace;
		script.name = parts.name();
		script.namespace = parts.namespace();
		script.slug = SlugSupport.buildSlug(parts.namespace(), parts.name());
		if (userId != null) {
			script.createdBy = entityManager.find(User.class, userId);
		}
		entityManager.persist(script);
		return script;
	}

	private ScriptPathParts parseScriptPath(String path) {
		if (path == null || path.isBlank()) {
			return null;
		}
		String normalizedPath = path.trim();
		while (normalizedPath.startsWith("/")) {
			normalizedPath = normalizedPath.substring(1);
		}
		while (normalizedPath.endsWith("/")) {
			normalizedPath = normalizedPath.substring(0, normalizedPath.length() - 1);
		}
		if (normalizedPath.endsWith(".groovy")) {
			normalizedPath = normalizedPath.substring(0, normalizedPath.length() - 7);
		}
		if (normalizedPath.isBlank()) {
			return null;
		}
		int slash = normalizedPath.lastIndexOf('/');
		String namespace = slash >= 0 ? normalizedPath.substring(0, slash) : null;
		String name = slash >= 0 ? normalizedPath.substring(slash + 1) : normalizedPath;
		if (name.isBlank()) {
			return null;
		}
		return new ScriptPathParts(normalizeNullable(namespace), name.trim());
	}

	private record ScriptPathParts(String namespace, String name) {}

	private String buildScriptPath(Script script) {
		if (script == null) {
			return null;
		}
		String name = normalizeNullable(script.name);
		if (name == null) {
			name = normalizeNullable(script.slug);
		}
		if (name == null) {
			return null;
		}
		String namespace = normalizeNullable(script.namespace);
		if (namespace == null) {
			return name + ".groovy";
		}
		return namespace + "/" + name + ".groovy";
	}

	private String buildPagePath(SfcPage page) {
		if (page == null) {
			return null;
		}
		String name = normalizeNullable(page.name);
		if (name == null) {
			name = normalizeNullable(page.slug);
		}
		if (name == null) {
			return null;
		}
		String namespace = normalizeNullable(page.namespace);
		if (namespace == null) {
			return name + ".vue";
		}
		return namespace + "/" + name + ".vue";
	}

	private void putDiffMeta(ObjectNode result, String path, String original, String updated) {
		if (result == null) {
			return;
		}
		ObjectNode meta = result.putObject("_meta");
		ObjectNode ui = meta.putObject("ui");
		ui.put("resourceUri", DIFF_RESOURCE_URI);
		ObjectNode structured = result.putObject("structuredContent");
		structured.put("path", path == null ? "" : path);
		structured.put("original", original == null ? "" : original);
		structured.put("diff", buildUnifiedDiff(original, updated, path));
	}

	private String buildUnifiedDiff(String original, String updated, String path) {
		String originalText = original == null ? "" : original;
		String updatedText = updated == null ? "" : updated;
		if (originalText.equals(updatedText)) {
			return "";
		}
		List<String> originalLines = splitLines(originalText);
		List<String> updatedLines = splitLines(updatedText);
		String normalizedPath = path == null ? "" : path;
		StringBuilder diff = new StringBuilder();
		diff.append("--- a/").append(normalizedPath).append('\n');
		diff.append("+++ b/").append(normalizedPath).append('\n');
		appendDiffHunks(diff, originalLines, updatedLines);
		return diff.toString();
	}

	private void appendDiffHunks(StringBuilder diff, List<String> originalLines, List<String> updatedLines) {
		int[][] lcs = buildLcsMatrix(originalLines, updatedLines);
		List<DiffLine> lines = buildDiffLines(originalLines, updatedLines, lcs);
		int index = 0;
		while (index < lines.size()) {
			int changeStart = findNextChange(lines, index);
			if (changeStart < 0) {
				return;
			}
			int contextStart = Math.max(0, changeStart - 3);
			int changeEnd = findChangeEnd(lines, changeStart);
			int contextEnd = Math.min(lines.size(), changeEnd + 3);
			HunkStats stats = calculateHunkStats(lines, contextStart, contextEnd);
			diff.append("@@ -")
				.append(formatRange(stats.originalStart, stats.originalLength))
				.append(" +")
				.append(formatRange(stats.updatedStart, stats.updatedLength))
				.append(" @@\n");
			for (int i = contextStart; i < contextEnd; i++) {
				DiffLine line = lines.get(i);
				diff.append(line.prefix).append(line.text).append('\n');
			}
			index = contextEnd;
		}
	}

	private int[][] buildLcsMatrix(List<String> originalLines, List<String> updatedLines) {
		int[][] lcs = new int[originalLines.size() + 1][updatedLines.size() + 1];
		for (int i = originalLines.size() - 1; i >= 0; i--) {
			for (int j = updatedLines.size() - 1; j >= 0; j--) {
				if (originalLines.get(i).equals(updatedLines.get(j))) {
					lcs[i][j] = lcs[i + 1][j + 1] + 1;
				}
				else {
					lcs[i][j] = Math.max(lcs[i + 1][j], lcs[i][j + 1]);
				}
			}
		}
		return lcs;
	}

	private List<DiffLine> buildDiffLines(List<String> originalLines, List<String> updatedLines, int[][] lcs) {
		List<DiffLine> lines = new ArrayList<>();
		int originalIndex = 0;
		int updatedIndex = 0;
		int originalLineNumber = 1;
		int updatedLineNumber = 1;
		while (originalIndex < originalLines.size() && updatedIndex < updatedLines.size()) {
			String originalLine = originalLines.get(originalIndex);
			String updatedLine = updatedLines.get(updatedIndex);
			if (originalLine.equals(updatedLine)) {
				lines.add(new DiffLine(' ', originalLine, originalLineNumber++, updatedLineNumber++));
				originalIndex++;
				updatedIndex++;
			}
			else if (lcs[originalIndex + 1][updatedIndex] >= lcs[originalIndex][updatedIndex + 1]) {
				lines.add(new DiffLine('-', originalLine, originalLineNumber++, 0));
				originalIndex++;
			}
			else {
				lines.add(new DiffLine('+', updatedLine, 0, updatedLineNumber++));
				updatedIndex++;
			}
		}
		while (originalIndex < originalLines.size()) {
			lines.add(new DiffLine('-', originalLines.get(originalIndex), originalLineNumber++, 0));
			originalIndex++;
		}
		while (updatedIndex < updatedLines.size()) {
			lines.add(new DiffLine('+', updatedLines.get(updatedIndex), 0, updatedLineNumber++));
			updatedIndex++;
		}
		return lines;
	}

	private int findNextChange(List<DiffLine> lines, int start) {
		for (int i = start; i < lines.size(); i++) {
			if (lines.get(i).prefix != ' ') {
				return i;
			}
		}
		return -1;
	}

	private int findChangeEnd(List<DiffLine> lines, int start) {
		int lastChange = start;
		int trailingContext = 0;
		for (int i = start; i < lines.size(); i++) {
			if (lines.get(i).prefix == ' ') {
				trailingContext++;
				if (trailingContext > 3) {
					break;
				}
			}
			else {
				lastChange = i;
				trailingContext = 0;
			}
		}
		return lastChange + 1;
	}

	private HunkStats calculateHunkStats(List<DiffLine> lines, int start, int end) {
		int originalStart = 0;
		int updatedStart = 0;
		int originalLength = 0;
		int updatedLength = 0;
		for (int i = start; i < end; i++) {
			DiffLine line = lines.get(i);
			if (originalStart == 0 && line.originalLineNumber > 0) {
				originalStart = line.originalLineNumber;
			}
			if (updatedStart == 0 && line.updatedLineNumber > 0) {
				updatedStart = line.updatedLineNumber;
			}
			if (line.prefix != '+') {
				originalLength++;
			}
			if (line.prefix != '-') {
				updatedLength++;
			}
		}
		if (originalStart == 0) {
			originalStart = lines.isEmpty() ? 1 : lines.get(Math.max(0, start - 1)).originalLineNumber + 1;
		}
		if (updatedStart == 0) {
			updatedStart = lines.isEmpty() ? 1 : lines.get(Math.max(0, start - 1)).updatedLineNumber + 1;
		}
		return new HunkStats(originalStart, originalLength, updatedStart, updatedLength);
	}

	private String formatRange(int start, int length) {
		if (length == 1) {
			return String.valueOf(start);
		}
		return start + "," + length;
	}

	private List<String> splitLines(String text) {
		if (text == null || text.isEmpty()) {
			return Collections.emptyList();
		}
		String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
		String[] parts = normalized.split("\n", -1);
		int size = parts.length;
		if (normalized.endsWith("\n")) {
			size--;
		}
		List<String> lines = new ArrayList<>(Math.max(size, 0));
		for (int i = 0; i < size; i++) {
			lines.add(parts[i]);
		}
		return lines;
	}

	private static final class DiffLine {
		final char prefix;
		final String text;
		final int originalLineNumber;
		final int updatedLineNumber;

		DiffLine(char prefix, String text, int originalLineNumber, int updatedLineNumber) {
			this.prefix = prefix;
			this.text = text == null ? "" : text;
			this.originalLineNumber = originalLineNumber;
			this.updatedLineNumber = updatedLineNumber;
		}
	}

	private static final class HunkStats {
		final int originalStart;
		final int originalLength;
		final int updatedStart;
		final int updatedLength;

		HunkStats(int originalStart, int originalLength, int updatedStart, int updatedLength) {
			this.originalStart = originalStart;
			this.originalLength = originalLength;
			this.updatedStart = updatedStart;
			this.updatedLength = updatedLength;
		}
	}

	private String normalizeScriptRoot(String root) {
		String normalized = normalizeNullable(root);
		if (normalized == null || "/".equals(normalized)) {
			return null;
		}
		while (normalized.startsWith("/")) {
			normalized = normalized.substring(1);
		}
		while (normalized.endsWith("/")) {
			normalized = normalized.substring(0, normalized.length() - 1);
		}
		return normalized.isBlank() ? null : normalized;
	}

	private boolean matchesRoot(String path, String root) {
		if (path == null) {
			return false;
		}
		if (root == null || root.isBlank()) {
			return true;
		}
		return path.equals(root) || path.startsWith(root + "/");
	}

	private String normalizeNullable(String value) {
		if (value == null) {
			return null;
		}
		String normalized = value.trim();
		return normalized.isEmpty() ? null : normalized;
	}

	private ObjectNode buildTruncatedScriptSearchResult(
			List<ObjectNode> fileResults,
			String patternText,
			String root,
			int totalFiles,
			int totalMatches,
			int offset,
			int requestedLimit,
			boolean hitLimitReached) {
		final int maxFiles = 20;
		final int maxRenderedMatches = 120;
		final int maxSummaryFiles = 10;
		ObjectNode result = objectMapper.createObjectNode();
		result.put("pattern", patternText);
		result.put("root", root == null ? "" : root);
		result.put("total_files", totalFiles);
		result.put("total_matches", totalMatches);
		result.put("offset", offset);
		result.put("limit", requestedLimit);
		ArrayNode files = result.putArray("files");
		int renderedMatches = 0;
		boolean reduced = false;
		for (ObjectNode file : fileResults) {
			if (files.size() >= maxFiles) {
				reduced = true;
				break;
			}
			JsonNode matchesNode = file.get("matches");
			int size = matchesNode != null && matchesNode.isArray() ? matchesNode.size() : 0;
			if (renderedMatches + size > maxRenderedMatches) {
				reduced = true;
				break;
			}
			files.add(file);
			renderedMatches += size;
		}
		result.put("count", files.size());
		if (!reduced && files.size() == totalFiles && !hitLimitReached) {
			putJsonContent(result, files);
			result.put("truncated", false);
			result.put("mode", "full");
			return result;
		}
		ArrayNode reducedFiles = objectMapper.createArrayNode();
		for (int i = 0; i < files.size(); i++) {
			ObjectNode reducedFile = ((ObjectNode) files.get(i)).deepCopy();
			reducedFile.remove("matches");
			reducedFiles.add(reducedFile);
		}
		if (reducedFiles.size() > 0) {
			result.set("files", reducedFiles);
			putJsonContent(result, reducedFiles);
			result.put("count", reducedFiles.size());
			result.put("truncated", true);
			result.put("mode", "reduced");
			result.put("notice", "Search results were truncated. Context lines were removed to reduce output size.");
			return result;
		}
		ArrayNode summaryFiles = result.putArray("files");
		int summaryCount = Math.min(fileResults.size(), maxSummaryFiles);
		for (int i = 0; i < summaryCount; i++) {
			ObjectNode summaryFile = fileResults.get(i).deepCopy();
			summaryFile.remove("matches");
			summaryFiles.add(summaryFile);
		}
		putJsonContent(result, summaryFiles);
		result.put("count", summaryFiles.size());
		result.put("truncated", true);
		result.put("mode", "summary");
		result.put("notice", "Search results were heavily truncated. Only file match counts are returned.");
		return result;
	}

	private ObjectNode buildTruncatedPageSearchResult(
			List<ObjectNode> fileResults,
			String patternText,
			String root,
			int totalFiles,
			int totalMatches,
			int offset,
			int requestedLimit,
			boolean hitLimitReached) {
		return buildTruncatedScriptSearchResult(fileResults, patternText, root, totalFiles, totalMatches, offset, requestedLimit, hitLimitReached);
	}

	private int resolveSearchPatternFlags(JsonNode arguments, String patternText) {
		JsonNode caseSensitive = arguments == null ? null : arguments.get("case_sensitive");
		if (caseSensitive != null) {
			if (caseSensitive.isBoolean()) {
				return caseSensitive.asBoolean() ? 0 : Pattern.CASE_INSENSITIVE;
			}
			if (caseSensitive.isTextual()) {
				String value = caseSensitive.asText();
				if ("true".equalsIgnoreCase(value)) {
					return 0;
				}
				if ("false".equalsIgnoreCase(value)) {
					return Pattern.CASE_INSENSITIVE;
				}
			}
		}
		return hasUpperCase(patternText) ? 0 : Pattern.CASE_INSENSITIVE;
	}

	private boolean hasUpperCase(String value) {
		if (value == null) {
			return false;
		}
		for (int i = 0; i < value.length(); i++) {
			if (Character.isUpperCase(value.charAt(i))) {
				return true;
			}
		}
		return false;
	}

	private int countOccurrences(String source, String find) {
		if (source == null || find == null || find.isEmpty()) {
			return 0;
		}
		int count = 0;
		int index = 0;
		while ((index = source.indexOf(find, index)) >= 0) {
			count++;
			index += find.length();
		}
		return count;
	}

	private SfcPageVersion resolveDraft(JsonNode arguments, UUID sessionId) {
		if (sessionId == null) {
			return null;
		}
		SfcPageVersion draft = entityManager.createQuery(
				"select v from SfcPageVersion v where v.designSessionId = :sessionId and v.releasedAt is null",
				SfcPageVersion.class
			)
			.setParameter("sessionId", sessionId)
			.getResultStream()
			.findFirst()
			.orElse(null);
		if (draft == null) {
			return null;
		}
		return draft;
	}

	private void applyScriptMetadata(ScriptVersion version, JsonNode arguments) {
		if (version == null || version.script == null || arguments == null) {
			return;
		}
		JsonNode description = arguments.get("description");
		if (description != null && description.isTextual()) {
			version.script.description = description.asText();
		}
		JsonNode inputSchema = arguments.get("input_schema");
		if (inputSchema != null && !inputSchema.isNull()) {
			version.inputSchema = inputSchema.deepCopy();
		}
		JsonNode outputSchema = arguments.get("output_schema");
		if (outputSchema != null && !outputSchema.isNull()) {
			version.outputSchema = outputSchema.deepCopy();
		}
	}

	private void applyPageMetadata(SfcPage page, JsonNode arguments) {
		if (page == null || arguments == null) {
			return;
		}
		JsonNode description = arguments.get("description");
		if (description != null && description.isTextual()) {
			page.description = description.asText();
		}
		JsonNode routeSuffix = arguments.get("route_suffix");
		if (routeSuffix != null && routeSuffix.isTextual()) {
			page.routeSuffix = routeSuffix.asText();
		}
		JsonNode iconSvg = arguments.get("icon_svg");
		if (iconSvg != null && iconSvg.isTextual()) {
			page.iconSvg = iconSvg.asText();
		}
		JsonNode usageGuide = arguments.get("usage_guide");
		if (usageGuide != null && usageGuide.isTextual()) {
			page.usageGuide = usageGuide.asText();
		}
		JsonNode queryParams = arguments.get("query_params");
		if (queryParams != null && queryParams.isArray()) {
			page.queryParams = queryParams.deepCopy();
		}
		JsonNode inputParams = arguments.get("input_params");
		if (inputParams != null && inputParams.isArray()) {
			page.inputParams = inputParams.deepCopy();
		}
		JsonNode importAllowlist = arguments.get("import_allowlist");
		if (importAllowlist != null && importAllowlist.isArray()) {
			page.importAllowlist = importAllowlist.deepCopy();
		}
		JsonNode type = arguments.get("type");
		if (type != null && type.isTextual()) {
			try {
				page.type = be.celerex.polymr.model.SfcPageType.valueOf(type.asText().trim().toUpperCase());
			}
			catch (IllegalArgumentException ignored) {}
		}
		JsonNode menuVisible = arguments.get("menu_visible");
		if (menuVisible != null && menuVisible.isBoolean()) {
			page.menuVisible = menuVisible.asBoolean();
		}
	}

	private ScriptVersion resolveScriptDraft(JsonNode arguments, UUID sessionId) {
		if (sessionId == null) {
			return null;
		}
		ScriptVersion draft = entityManager.createQuery(
				"select v from ScriptVersion v where v.designSessionId = :sessionId and v.releasedAt is null",
				ScriptVersion.class
			)
			.setParameter("sessionId", sessionId)
			.getResultStream()
			.findFirst()
			.orElse(null);
		if (draft == null) {
			return null;
		}
		return draft;
	}

	private ObjectNode buildActivateMcpServerTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_ACTIVATE_MCP_SERVER);
		tool.put("description", "Activate a tool you need for this session.");
		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");
		ArrayNode required = inputSchema.putArray("required");
		required.add("server_name");
		ObjectNode properties = inputSchema.putObject("properties");
		properties.putObject("server_name")
			.put("type", "string")
			.put("description", "Exact MCP server name to activate.");
		tool.set("inputSchema", inputSchema);
		ObjectNode annotations = tool.putObject("annotations");
		ArrayNode scopes = annotations.putArray("scopes");
		scopes.add("configure:polymr:session:tool");
		annotations.put("intentTemplate", "Activate tool: {server_name}");
		return tool;
	}

	private JsonNode listTranscripts(McpServer server, JsonNode arguments) {
		String query = readText(arguments, "query");
		int limit = readInt(arguments, "limit", 5);
		if (limit <= 0) {
			limit = 5;
		}
		limit = Math.min(limit, TRANSCRIPT_LIMIT_MAX);
		int offset = readInt(arguments, "offset", 0);
		if (offset < 0) {
			offset = 0;
		}
		boolean hasQuery = query != null && !query.isBlank();
		String search = hasQuery ? "%" + query.trim().toLowerCase() + "%" : null;
		String baseQuery = "select r from Recording r where r.workspace.id = :workspaceId and r.status = :status";
		if (hasQuery) {
			baseQuery += " and (lower(r.title) like :query or lower(coalesce(r.transcriptText, '')) like :query)";
		}
		baseQuery += " order by r.createdAt desc";
		var queryBuilder = entityManager.createQuery(baseQuery, be.celerex.polymr.model.Recording.class)
			.setParameter("workspaceId", server.workspace.id)
			.setParameter("status", be.celerex.polymr.model.RecordingStatus.SUMMARIZED)
			.setFirstResult(offset)
			.setMaxResults(limit);
		if (hasQuery) {
			queryBuilder.setParameter("query", search);
		}
		List<be.celerex.polymr.model.Recording> recordings = queryBuilder.getResultList();
		ArrayNode results = objectMapper.createArrayNode();
		for (be.celerex.polymr.model.Recording recording : recordings) {
			ObjectNode item = objectMapper.createObjectNode();
			item.put("id", recording.id == null ? null : recording.id.toString());
			item.put("title", recording.title);
			item.put("created_at", recording.createdAt == null ? null : recording.createdAt.toString());
			item.put("updated_at", recording.updatedAt == null ? null : recording.updatedAt.toString());
			if (recording.summaryText != null && !recording.summaryText.isBlank()) {
				item.put("summary", recording.summaryText);
			}
			if (recording.durationSeconds != null) {
				item.put("duration_seconds", recording.durationSeconds);
			}
			results.add(item);
		}
		ObjectNode response = objectMapper.createObjectNode();
		putTextContent(response, results.size() == 0 ? "No transcripts found" : results.toString());
		response.set("results", results);
		response.putObject("_meta").put("displayMessage", "Found " + results.size() + " transcripts");
		return response;
	}

	private JsonNode getTranscript(McpServer server, JsonNode arguments) {
		String id = readText(arguments, "id");
		if (id == null || id.isBlank()) {
			return error("Transcript id is required");
		}
		UUID transcriptId;
		try {
			transcriptId = UUID.fromString(id.trim());
		}
		catch (IllegalArgumentException ex) {
			return error("Invalid transcript id");
		}
		be.celerex.polymr.model.Recording recording = entityManager.find(be.celerex.polymr.model.Recording.class, transcriptId);
		if (recording == null
				|| recording.workspace == null
				|| !recording.workspace.id.equals(server.workspace.id)) {
			return error("Transcript not found");
		}
		if (recording.transcriptText == null || recording.transcriptText.isBlank()) {
			return error("Transcript is empty");
		}
		ObjectNode response = objectMapper.createObjectNode();
		putTextContent(response, recording.transcriptText);
		response.put("title", recording.title);
		response.put("id", recording.id == null ? null : recording.id.toString());
		response.putObject("_meta").put("displayMessage", "Transcript loaded: " + recording.title);
		return response;
	}

	private JsonNode listWorkspaceUsers(McpServer server) {
		if (server == null || server.workspace == null || server.workspace.tenant == null) {
			return error("Workspace not found");
		}
		List<be.celerex.polymr.model.TenantMembership> memberships = entityManager.createQuery(
				"select tm from TenantMembership tm where tm.tenant.id = :tenantId",
				be.celerex.polymr.model.TenantMembership.class
			)
			.setParameter("tenantId", server.workspace.tenant.id)
			.getResultList();
		ArrayNode results = objectMapper.createArrayNode();
		for (be.celerex.polymr.model.TenantMembership membership : memberships) {
			be.celerex.polymr.model.User user = membership.user;
			if (user == null) {
				continue;
			}
			ObjectNode item = objectMapper.createObjectNode();
			item.put("id", user.id == null ? null : user.id.toString());
			item.put("email", user.email);
			item.put("name", displayName(user));
			String avatarUrl = avatarUrl(user);
			if (avatarUrl != null) {
				item.put("avatar_url", avatarUrl);
			}
			results.add(item);
		}
		ObjectNode response = objectMapper.createObjectNode();
		putTextContent(response, results.size() == 0 ? "No users found" : results.toString());
		response.set("users", results);
		response.putObject("_meta").put("displayMessage", "Found " + results.size() + " users");
		return response;
	}

	private JsonNode recordWorkerCompletion(UUID sessionId, String status, JsonNode arguments) {
		if (sessionId == null) {
			return error("Session not found");
		}
		WorkflowStateStore store = resolveWorkflowStateStore(sessionId);
		if (store == null) {
			return error("Workflow state not available");
		}
		ObjectNode completion = objectMapper.createObjectNode();
		completion.put("status", status == null ? "unknown" : status);
		if (arguments instanceof ObjectNode objectNode) {
			JsonNode message = objectNode.get("message");
			if (message != null && message.isTextual()) {
				completion.set("message", message);
			}
			JsonNode data = objectNode.get("data");
			if (data != null && !data.isNull()) {
				completion.set("data", data);
			}
		}
		store.setState("worker_completion", completion);
		ObjectNode response = objectMapper.createObjectNode();
		response.put("status", "ok");
		putTextContent(response, "Recorded worker completion");
		return response;
	}

	@Transactional
	public JsonNode spawnWorkers(
			McpServer server,
			JsonNode arguments,
			UUID sessionId,
			UUID userId) {
		if (sessionId == null) {
			return error("Session not found");
		}
		Session parentSession = entityManager.find(Session.class, sessionId);
		if (parentSession == null || parentSession.workspace == null) {
			return error("Session not found");
		}
		WorkflowRun parentRun = resolveWorkflowRun(sessionId);
		if (parentRun == null || parentRun.workflowDefinition == null) {
			return error("Parent workflow run not found");
		}
		JsonNode definitionJson = loadWorkflowDefinitionJson(parentRun);
		if (definitionJson == null) {
			return error("Workflow definition not found");
		}
		ArrayNode childrenNode = arguments instanceof ObjectNode obj
			? (obj.get("children") instanceof ArrayNode array ? array : null)
			: null;
		String toolCallId = readText(arguments instanceof ObjectNode obj ? obj : null, "tool_call_id");
		if (childrenNode == null || childrenNode.isEmpty()) {
			return error("No worker tasks provided");
		}
		ObjectNode parentSnapshot = resolveSnapshotFromRun(parentRun, definitionJson, parentSession);
		ScopePolicy hostPolicy = resolveScopePolicy(parentSnapshot);
		List<ChildSpec> childSpecs = new ArrayList<>();
		List<String> deniedMessages = new ArrayList<>();
		for (JsonNode entry : childrenNode) {
			if (entry == null || !entry.isObject()) {
				continue;
			}
			String assistantName = readText(entry, "assistant_name");
			String task = readText(entry, "task");
			String title = readText(entry, "title");
			List<String> requestedTools = readStringArray(entry.get("requested_tools"));
			if (assistantName == null || assistantName.isBlank()) {
				deniedMessages.add("Missing assistant_name for worker task");
				continue;
			}
			if (task == null || task.isBlank()) {
				deniedMessages.add("Missing task for worker " + assistantName);
				continue;
			}
			childSpecs.add(new ChildSpec(assistantName, task, title, requestedTools));
		}
		if (childSpecs.isEmpty()) {
			return error("No valid worker tasks provided");
		}
		java.util.Set<String> requestedScopes = new java.util.LinkedHashSet<>();
		java.util.Set<String> approvedScopes = new java.util.LinkedHashSet<>();
		if (arguments instanceof ObjectNode obj) {
			JsonNode approvedNode = obj.get("approved_scopes");
			readStringArray(approvedNode)
				.forEach(scope -> {
					if (scope != null && !scope.isBlank()) {
						approvedScopes.add(scope);
					}
				});
		}
		for (ChildSpec spec : childSpecs) {
			Assistant assistant = resolveWorker(parentSession, spec.assistantName);
			if (assistant == null) {
				deniedMessages.add("Unknown worker: " + spec.assistantName);
				continue;
			}
			ScopePolicy assistantPolicy = resolveWorkerScopePolicy(assistant);
			for (String toolName : spec.requestedTools) {
				McpServerTool tool = resolveWorkspaceTool(parentSession.workspace.id, toolName);
				if (tool == null) {
					deniedMessages.add("Tool not available: " + toolName + " (" + assistant.name + ")");
					continue;
				}
				List<String> toolScopes = resolveToolScopes(tool);
				for (String scope : toolScopes) {
					ScopeDecision decision = decideScope(scope, assistantPolicy);
					if (decision == ScopeDecision.DENY) {
						deniedMessages.add("Worker " + assistant.name + " cannot use scope " + scope);
						continue;
					}
					if (decision == ScopeDecision.DYNAMIC && !hostPolicy.allows(scope)) {
						if (approvedScopes.contains(scope)) {
							continue;
						}
						requestedScopes.add(scope);
					}
				}
			}
		}
		if (!deniedMessages.isEmpty()) {
			throw new RuntimeException(toolFailurePayload("permission_denied", String.join("; ", deniedMessages), null));
		}
		if (!requestedScopes.isEmpty()) {
			throw new RuntimeException(
				toolFailurePayload(
					"permission_required",
					"Tool execution requires approval.",
					new java.util.ArrayList<>(requestedScopes)
				)
			);
		}
		ArrayNode results = objectMapper.createArrayNode();
		List<ChildRef> childRefs = new ArrayList<>();
		UUID effectiveUserId = userId != null ? userId : (parentSession.createdBy == null ? null : parentSession.createdBy.id);
		for (ChildSpec spec : childSpecs) {
			Assistant assistant = resolveWorker(parentSession, spec.assistantName);
			if (assistant == null) {
				continue;
			}
			Session childSession = new Session();
			childSession.tenant = parentSession.tenant;
			childSession.workspace = parentSession.workspace;
			childSession.channel = parentSession.channel;
			childSession.defaultAssistant = assistant;
			childSession.parentSession = parentSession;
			childSession.createdBy = effectiveUserId == null
				? parentSession.createdBy
				: entityManager.getReference(User.class, effectiveUserId);
			childSession.title = spec.title;
			childSession.titleLocked = spec.title != null && !spec.title.isBlank();
			childSession.visibility = SessionVisibility.HIDDEN;
			childSession.status = SessionStatus.ACTIVE;
			entityManager.persist(childSession);

			WorkflowRun run = new WorkflowRun();
			run.session = childSession;
			run.workflowDefinition = parentRun.workflowDefinition;
			run.workflowDefinitionVersion = parentRun.workflowDefinitionVersion;
			run.status = WorkflowRunStatus.QUEUED;

			ObjectNode checkpoint = objectMapper.createObjectNode();
			ObjectNode snapshot = snapshotService.buildSnapshot(definitionJson, childSession);
			applyWorkerScopeInheritance(snapshot, parentSnapshot);
			addWorkerFeedbackServer(childSession, snapshot);
			if (!approvedScopes.isEmpty()) {
				addApprovedWorkerScopes(snapshot, approvedScopes);
			}
			copyTagSnapshot(parentSnapshot, snapshot);
			checkpoint.set(ConversationGraphState.MCP_SNAPSHOT, snapshot);
			ObjectNode state = objectMapper.createObjectNode();
			state.put("parent_session_id", parentSession.id.toString());
			state.put("worker", true);
			if (toolCallId != null && !toolCallId.isBlank()) {
				state.put("tool_call_id", toolCallId);
			}
			ArrayNode allowedTools = state.putArray("worker_allowed_tools");
			for (String tool : spec.requestedTools) {
				if (tool != null && !tool.isBlank()) {
					allowedTools.add(tool);
				}
			}
			allowedTools.add(TOOL_COMPLETE_GOAL);
			allowedTools.add(TOOL_FAIL_GOAL);
			checkpoint.set(ConversationGraphState.STATE, state);
			run.checkpointJson = checkpoint;
			entityManager.persist(run);

			if (effectiveUserId != null) {
				SessionParticipant owner = new SessionParticipant();
				owner.session = childSession;
				owner.user = entityManager.getReference(User.class, effectiveUserId);
				owner.role = SessionParticipantRole.OWNER;
				entityManager.persist(owner);
			}
			copySessionTags(parentSession, childSession);

			ObjectNode payload = objectMapper.createObjectNode();
			payload.put("text", spec.task == null ? "" : spec.task);
			sessionChatService.handleChatSend(childSession, effectiveUserId, payload);

			ObjectNode child = objectMapper.createObjectNode();
			child.put("session_id", childSession.id.toString());
			child.put("run_id", run.id.toString());
			child.put("assistant_name", assistant.name);
			if (childSession.title != null && !childSession.title.isBlank()) {
				child.put("title", childSession.title);
			}
			child.put("status", "RUNNING");
			if (toolCallId != null && !toolCallId.isBlank()) {
				child.put("tool_call_id", toolCallId);
			}
			results.add(child);
			childRefs.add(new ChildRef(childSession, run, assistant.name));
		}

		if (!childRefs.isEmpty()) {
			broadcastWorkerSpawn(parentSession, childRefs, toolCallId);
		}

		ObjectNode response = objectMapper.createObjectNode();
		response.set("children", results);
		putTextContent(response, "Spawned " + results.size() + " workers");
		response.putObject("_meta").put("displayMessage", "Spawned " + results.size() + " workers");
		return response;
	}

	private void broadcastWorkerSpawn(Session parentSession, List<ChildRef> children, String toolCallId) {
		if (parentSession == null || parentSession.workspace == null || children == null) {
			return;
		}
		ObjectNode payload = objectMapper.createObjectNode();
		payload.put("kind", "worker_progress");
		payload.put("total", children.size());
		payload.put("done", 0);
		payload.put("failed", 0);
		if (toolCallId != null && !toolCallId.isBlank()) {
			payload.put("tool_call_id", toolCallId);
		}
		ArrayNode childArray = payload.putArray("children");
		for (ChildRef child : children) {
			ObjectNode item = childArray.addObject();
			item.put("session_id", child.session.id.toString());
			item.put("run_id", child.run.id.toString());
			item.put("assistant_name", child.assistantName);
			if (child.session.title != null && !child.session.title.isBlank()) {
				item.put("title", child.session.title);
			}
			item.put("status", "RUNNING");
			if (toolCallId != null && !toolCallId.isBlank()) {
				item.put("tool_call_id", toolCallId);
			}
		}
		SessionEvent event = eventService.upsertWorkerProgress(parentSession, payload);
		if (event != null) {
			SessionEventResponse response = new SessionEventResponse(
				event.id,
				event.eventType,
				eventService.enrichPayload(event),
				event.createdAt,
				event.epochId,
				event.inputTokens,
				event.outputTokens,
				event.reasoningTokens,
				event.cachedInputTokens,
				event.tokenizerModelId,
				event.priceSnapshot,
				event.priceCurrency
			);
			socketManager.broadcastToSession(
				parentSession.id,
				new WorkspaceSocketEvent("session.event", parentSession.workspace.id, parentSession.id, response)
			);
		}
		socketManager.broadcastToSession(
			parentSession.id,
			new WorkspaceSocketEvent("session.worker_progress", parentSession.workspace.id, parentSession.id, payload)
		);
	}

	private void addWorkerFeedbackServer(Session session, ObjectNode snapshot) {
		if (session == null || session.workspace == null || snapshot == null) {
			return;
		}
		McpServer server = snapshotService.ensureWorkerFeedbackServer(session.workspace);
		if (server == null || server.id == null) {
			return;
		}
		ObjectNode mcp = snapshot.get("mcp") instanceof ObjectNode node ? node : snapshot.putObject("mcp");
		ArrayNode servers = mcp.get("servers") instanceof ArrayNode array ? array : mcp.putArray("servers");
		String id = server.id.toString();
		for (JsonNode entry : servers) {
			if (entry != null && id.equals(entry.asText())) {
				addWorkerFeedbackScopes(snapshot);
				return;
			}
		}
		servers.add(id);
		addWorkerFeedbackScopes(snapshot);
	}

	private void addWorkerFeedbackScopes(ObjectNode snapshot) {
		if (snapshot == null) {
			return;
		}
		ObjectNode nodes = snapshot.get("nodes") instanceof ObjectNode node ? node : snapshot.putObject("nodes");
		nodes.fieldNames()
			.forEachRemaining(
				nodeId -> {
					ObjectNode node = nodes.get(nodeId) instanceof ObjectNode obj ? obj : nodes.putObject(nodeId);
					ObjectNode scopes = node.get("scopes") instanceof ObjectNode scopeNode ? scopeNode : node.putObject("scopes");
					ArrayNode allowArray = scopes.get("allow_scopes") instanceof ArrayNode arr ? arr : scopes.putArray("allow_scopes");
					ensureAllowScope(allowArray, WORKER_FEEDBACK_SCOPE);
				}
			);
	}

	private void addApprovedWorkerScopes(ObjectNode snapshot, java.util.Set<String> approvedScopes) {
		if (snapshot == null || approvedScopes == null || approvedScopes.isEmpty()) {
			return;
		}
		ObjectNode nodes = snapshot.get("nodes") instanceof ObjectNode node ? node : snapshot.putObject("nodes");
		nodes.fieldNames()
			.forEachRemaining(
				nodeId -> {
					ObjectNode node = nodes.get(nodeId) instanceof ObjectNode obj ? obj : nodes.putObject(nodeId);
					ObjectNode scopes = node.get("scopes") instanceof ObjectNode scopeNode ? scopeNode : node.putObject("scopes");
					ArrayNode allowArray = scopes.get("allow_scopes") instanceof ArrayNode arr ? arr : scopes.putArray("allow_scopes");
					approvedScopes.forEach(scope -> ensureAllowScope(allowArray, scope));
				}
			);
	}

	private void applyWorkerScopeInheritance(ObjectNode childSnapshot, ObjectNode parentSnapshot) {
		String mode = workerScopeInheritance == null ? "host" : workerScopeInheritance.trim().toLowerCase();
		if (mode.isBlank()) {
			mode = "host";
		}
		switch (mode) {
			case "workspace" -> {
                return;
            }
			case "explicit" -> {
                clearSnapshotScopes(childSnapshot);
            }
			case "host" -> {
                copySnapshotScopes(parentSnapshot, childSnapshot);
            }
			default -> {
                copySnapshotScopes(parentSnapshot, childSnapshot);
            }
		}
	}

	private void copySnapshotScopes(ObjectNode sourceSnapshot, ObjectNode targetSnapshot) {
		if (sourceSnapshot == null || targetSnapshot == null) {
			return;
		}
		ObjectNode sourceNodes = sourceSnapshot.get("nodes") instanceof ObjectNode node ? node : null;
		if (sourceNodes == null) {
			return;
		}
		ObjectNode targetNodes = targetSnapshot.get("nodes") instanceof ObjectNode node ? node : targetSnapshot.putObject("nodes");
		ObjectNode fallbackSource = resolvePrimaryNodeSnapshot(sourceSnapshot);
		targetNodes.fieldNames()
			.forEachRemaining(
				nodeId -> {
					ObjectNode targetNode = targetNodes.get(nodeId) instanceof ObjectNode obj ? obj : targetNodes.putObject(nodeId);
					ObjectNode sourceNode = sourceNodes.get(nodeId) instanceof ObjectNode obj ? obj : fallbackSource;
					if (sourceNode == null) {
						return;
					}
					copyNodeScopes(sourceNode, targetNode);
				}
			);
	}

	private void copyNodeScopes(ObjectNode sourceNode, ObjectNode targetNode) {
		if (sourceNode == null || targetNode == null) {
			return;
		}
		ObjectNode sourceScopes = sourceNode.get("scopes") instanceof ObjectNode obj ? obj : null;
		if (sourceScopes == null) {
			return;
		}
		ObjectNode targetScopes = targetNode.get("scopes") instanceof ObjectNode obj ? obj : targetNode.putObject("scopes");
		JsonNode allow = sourceScopes.get("allow_scopes");
		if (allow != null && allow.isArray()) {
			targetScopes.set("allow_scopes", allow.deepCopy());
		}
		JsonNode deny = sourceScopes.get("deny_scopes");
		if (deny != null && deny.isArray()) {
			targetScopes.set("deny_scopes", deny.deepCopy());
		}
	}

	private void clearSnapshotScopes(ObjectNode snapshot) {
		if (snapshot == null) {
			return;
		}
		ObjectNode nodes = snapshot.get("nodes") instanceof ObjectNode node ? node : snapshot.putObject("nodes");
		nodes.fieldNames()
			.forEachRemaining(
				nodeId -> {
					ObjectNode node = nodes.get(nodeId) instanceof ObjectNode obj ? obj : nodes.putObject(nodeId);
					ObjectNode scopes = node.get("scopes") instanceof ObjectNode scopeNode ? scopeNode : node.putObject("scopes");
					scopes.set("allow_scopes", objectMapper.createArrayNode());
					scopes.set("deny_scopes", objectMapper.createArrayNode());
				}
			);
	}

	private void ensureAllowScope(ArrayNode allowArray, String scope) {
		if (allowArray == null || scope == null || scope.isBlank()) {
			return;
		}
		for (JsonNode entry : allowArray) {
			if (entry != null && scope.equals(entry.asText())) {
				return;
			}
		}
		allowArray.add(scope);
	}

	private void copySessionTags(Session parent, Session child) {
		if (parent == null || child == null) {
			return;
		}
		List<SessionTagSelection> selections = entityManager.createQuery("select s from SessionTagSelection s where s.session.id = :sessionId", SessionTagSelection.class)
			.setParameter("sessionId", parent.id)
			.getResultList();
		for (SessionTagSelection selection : selections) {
			SessionTagSelection copy = new SessionTagSelection();
			copy.session = child;
			copy.category = selection.category;
			copy.value = selection.value;
			entityManager.persist(copy);
		}
	}

	private void copyTagSnapshot(ObjectNode parentSnapshot, ObjectNode childSnapshot) {
		if (parentSnapshot == null || childSnapshot == null) {
			return;
		}
		ObjectNode parentNode = resolvePrimaryNodeSnapshot(parentSnapshot);
		ObjectNode childNode = resolvePrimaryNodeSnapshot(childSnapshot);
		if (parentNode == null || childNode == null) {
			return;
		}
		ObjectNode parentMcp = parentNode.get("mcp") instanceof ObjectNode obj ? obj : null;
		if (parentMcp == null || !parentMcp.has("tags")) {
			return;
		}
		ObjectNode childMcp = childNode.get("mcp") instanceof ObjectNode obj ? obj : childNode.putObject("mcp");
		childMcp.set("tags", parentMcp.get("tags").deepCopy());
	}

	private ScopePolicy resolveScopePolicy(ObjectNode snapshot) {
		ObjectNode node = resolvePrimaryNodeSnapshot(snapshot);
		ObjectNode scopes = node == null ? null : (node.get("scopes") instanceof ObjectNode s ? s : null);
		List<String> allow = readStringArray(scopes == null ? null : scopes.get("allow_scopes"));
		List<String> deny = readStringArray(scopes == null ? null : scopes.get("deny_scopes"));
		return new ScopePolicy(allow, deny);
	}

	private ScopePolicy resolveWorkerScopePolicy(Assistant assistant) {
		List<String> allow = assistant == null ? List.of() : normalizeScopes(assistant.workerAllowScopes);
		List<String> deny = assistant == null ? List.of() : normalizeScopes(assistant.workerDenyScopes);
		return new ScopePolicy(allow, deny);
	}

	private McpServerTool resolveWorkspaceTool(UUID workspaceId, String toolName) {
		if (workspaceId == null || toolName == null || toolName.isBlank()) {
			return null;
		}
		return entityManager.createQuery(
				"select t from McpServerTool t join fetch t.mcpServer "
					+ "where t.mcpServer.workspace.id = :workspaceId "
					+ "and t.deleted = false and t.disabled = false "
					+ "and lower(coalesce(t.mcpServer.toolNamePrefix, '') || coalesce(t.toolAlias, t.toolName)) = "
					+ "lower(:toolName)",
				McpServerTool.class
			)
			.setParameter("workspaceId", workspaceId)
			.setParameter("toolName", toolName)
			.getResultStream()
			.findFirst()
			.orElse(null);
	}

	private List<String> resolveToolScopes(McpServerTool tool) {
		if (tool == null) {
			return List.of();
		}
		List<String> scopes = normalizeScopes(readStringArray(tool.customScopes));
		if (!scopes.isEmpty()) {
			return scopes;
		}
		return normalizeScopes(readStringArray(tool.scopes));
	}

	private ObjectNode resolveSnapshotFromRun(WorkflowRun run, JsonNode definitionJson, Session session) {
		if (run != null
				&& run.checkpointJson != null
				&& run.checkpointJson.has(ConversationGraphState.MCP_SNAPSHOT)) {
			JsonNode node = run.checkpointJson.get(ConversationGraphState.MCP_SNAPSHOT);
			if (node instanceof ObjectNode objectNode) {
				return objectNode.deepCopy();
			}
			if (node != null && node.isObject()) {
				return objectMapper.convertValue(node, ObjectNode.class);
			}
		}
		if (definitionJson != null && session != null) {
			return snapshotService.buildSnapshot(definitionJson, session);
		}
		return objectMapper.createObjectNode();
	}

	private ObjectNode resolvePrimaryNodeSnapshot(ObjectNode snapshot) {
		if (snapshot == null) {
			return null;
		}
		JsonNode nodesNode = snapshot.get("nodes");
		if (nodesNode == null || !nodesNode.isObject()) {
			return null;
		}
		JsonNode llmNode = nodesNode.get("llm");
		if (llmNode instanceof ObjectNode objectNode) {
			return objectNode;
		}
		return nodesNode.fields().hasNext() ? (ObjectNode) nodesNode.fields().next().getValue() : null;
	}

	private ScopeDecision decideScope(String scope, ScopePolicy policy) {
		int allowSpec = bestSpecificity(scope, policy.allowScopes);
		int denySpec = bestSpecificity(scope, policy.denyScopes);
		if (denySpec > 0 && denySpec >= allowSpec) {
			return ScopeDecision.DENY;
		}
		if (allowSpec > 0) {
			return ScopeDecision.ALLOW;
		}
		return ScopeDecision.DYNAMIC;
	}

	private static int bestSpecificity(String scope, List<String> candidates) {
		if (scope == null || candidates == null || candidates.isEmpty()) {
			return 0;
		}
		int best = 0;
		for (String candidate : candidates) {
			if (candidate == null || candidate.isBlank()) {
				continue;
			}
			if (scope.equals(candidate) || scope.startsWith(candidate + ":")) {
				int specificity = candidate.split(":").length;
				if (specificity > best) {
					best = specificity;
				}
			}
		}
		return best;
	}

	private String toolFailurePayload(String code, String message, List<String> requestedScopes) {
		ObjectNode payload = objectMapper.createObjectNode();
		payload.put("code", code);
		payload.put("message", message);
		if (requestedScopes != null && !requestedScopes.isEmpty()) {
			ObjectNode meta = payload.putObject("_meta");
			ArrayNode scopes = meta.putArray("requested_scopes");
			for (String scope : requestedScopes) {
				scopes.add(scope);
			}
		}
		return payload.toString();
	}

	private Assistant resolveWorker(Session session, String slug) {
		if (session == null || slug == null || slug.isBlank()) {
			return null;
		}
		List<Assistant> assistants = entityManager.createQuery(
				"select a from Assistant a where a.tenant.id = :tenantId and a.workerEnabled = true",
				Assistant.class
			)
			.setParameter("tenantId", session.tenant.id)
			.getResultList();
		for (Assistant assistant : assistants) {
			if (assistant == null) {
				continue;
			}
			if (assistant.workspace != null && !assistant.workspace.id.equals(session.workspace.id)) {
				continue;
			}
			String assistantSlug = AssistantSlug.fromName(assistant.name);
			if (slug.equals(assistantSlug)) {
				return assistant;
			}
		}
		return null;
	}

	private List<String> normalizeScopes(List<String> scopes) {
		if (scopes == null || scopes.isEmpty()) {
			return List.of();
		}
		java.util.Set<String> normalized = new java.util.LinkedHashSet<>();
		for (String scope : scopes) {
			if (scope == null) {
				continue;
			}
			String trimmed = scope.trim();
			if (!trimmed.isBlank()) {
				normalized.add(trimmed);
			}
		}
		return new java.util.ArrayList<>(normalized);
	}

	private List<String> readStringArray(JsonNode node) {
		if (node == null || node.isNull()) {
			return List.of();
		}
		if (!node.isArray()) {
			return List.of();
		}
		List<String> values = new ArrayList<>();
		for (JsonNode entry : node) {
			if (entry != null && entry.isTextual()) {
				values.add(entry.asText());
			}
		}
		return values;
	}

	private List<String> readStringArray(Object raw) {
		if (raw == null) {
			return List.of();
		}
		if (raw instanceof List<?> list) {
			List<String> values = new ArrayList<>();
			for (Object item : list) {
				if (item == null) {
					continue;
				}
				String value = item.toString();
				if (!value.isBlank()) {
					values.add(value);
				}
			}
			return values;
		}
		return List.of();
	}

	private record ChildSpec(String assistantName, String task, String title, List<String> requestedTools) {}

	private record ChildRef(Session session, WorkflowRun run, String assistantName) {}

	private enum ScopeDecision {
		ALLOW,
		DENY,
		DYNAMIC
	}

	private record ScopePolicy(List<String> allowScopes, List<String> denyScopes) {
		boolean allows(String scope) {
			int allowSpec = bestSpecificity(scope, allowScopes);
			int denySpec = bestSpecificity(scope, denyScopes);
			if (denySpec > 0 && denySpec >= allowSpec) {
				return false;
			}
			return allowSpec > 0;
		}
	}

	private NotificationTarget resolveTarget(String value) {
		if (value == null) {
			return null;
		}
		return switch (value.trim().toLowerCase()) {
			case "user" -> NotificationTarget.CURRENT_USER;
			case "participants" -> NotificationTarget.CONVERSATION_PARTICIPANTS;
			case "workspace" -> NotificationTarget.WORKSPACE_MEMBERS;
			default -> null;
		};
	}

	private Set<UUID> resolveRecipients(
			NotificationTarget target,
			Workspace workspace,
			Session session,
			User initiator) {
		Set<UUID> recipients = new HashSet<>();
		if (target == NotificationTarget.CURRENT_USER) {
			if (initiator != null) {
				recipients.add(initiator.id);
			}
			return recipients;
		}
		if (target == NotificationTarget.CONVERSATION_PARTICIPANTS) {
			if (session == null) {
				return recipients;
			}
			List<SessionParticipant> participants = entityManager.createQuery("select p from SessionParticipant p where p.session.id = :sessionId", SessionParticipant.class)
				.setParameter("sessionId", session.id)
				.getResultList();
			participants.forEach(participant -> recipients.add(participant.user.id));
			return recipients;
		}
		if (target == NotificationTarget.WORKSPACE_MEMBERS) {
			List<TenantMembership> memberships = entityManager.createQuery("select m from TenantMembership m where m.tenant.id = :tenantId", TenantMembership.class)
				.setParameter("tenantId", workspace.tenant.id)
				.getResultList();
			memberships.forEach(membership -> recipients.add(membership.user.id));
		}
		return recipients;
	}

	private Map<UUID, PushWorkspacePreference> loadPreferences(UUID workspaceId, Set<UUID> userIds) {
		if (userIds.isEmpty()) {
			return Map.of();
		}
		List<PushWorkspacePreference> prefs = entityManager.createQuery(
				"select p from PushWorkspacePreference p where p.workspace.id = "
					+ ":workspaceId and p.user.id in :userIds",
				PushWorkspacePreference.class
			)
			.setParameter("workspaceId", workspaceId)
			.setParameter("userIds", userIds)
			.getResultList();
		return prefs.stream()
			.collect(Collectors.toMap(pref -> pref.user.id, pref -> pref));
	}

	private Map<UUID, List<PushSubscription>> loadSubscriptions(Set<UUID> userIds) {
		if (userIds.isEmpty()) {
			return Map.of();
		}
		List<PushSubscription> subs = entityManager.createQuery(
				"select s from PushSubscription s where s.user.id in :userIds and s.active = true",
				PushSubscription.class
			)
			.setParameter("userIds", userIds)
			.getResultList();
		Map<UUID, List<PushSubscription>> map = new HashMap<>();
		for (PushSubscription sub : subs) {
			map.computeIfAbsent(sub.user.id, key -> new ArrayList<>())
				.add(sub);
		}
		return map;
	}

	private WorkflowRun resolveWorkflowRun(UUID sessionId) {
		if (sessionId == null) {
			return null;
		}
		Session session = entityManager.find(Session.class, sessionId);
		if (session == null) {
			return null;
		}
		try {
			return checkpointService.requireRun(session);
		}
		catch (Exception ignored) {
			return null;
		}
	}

	private JsonNode loadWorkflowDefinitionJson(WorkflowRun run) {
		if (run == null) {
			return null;
		}
		if (run.workflowDefinitionVersion != null && run.workflowDefinitionVersion.id != null) {
			return entityManager.createQuery("select v.definitionJson from WorkflowDefinitionVersion v where v.id = :id", JsonNode.class)
				.setParameter("id", run.workflowDefinitionVersion.id)
				.getResultStream()
				.findFirst()
				.orElse(null);
		}
		if (run.workflowDefinition == null) {
			return null;
		}
		return entityManager.createQuery(
				"select v.definitionJson from WorkflowDefinitionVersion v where v.workflowDefinition.id = "
					+ ":workflowId "
					+ "and v.releasedAt is not null and v.deprecatedAt is null",
				JsonNode.class
			)
			.setParameter("workflowId", run.workflowDefinition.id)
			.setMaxResults(1)
			.getResultStream()
			.findFirst()
			.orElse(null);
	}

	private ObjectNode ensureCheckpoint(WorkflowRun run) {
		ObjectNode checkpoint = run.checkpointJson instanceof ObjectNode node ? node : objectMapper.createObjectNode();
		run.checkpointJson = checkpoint;
		return checkpoint;
	}

	private ObjectNode ensureWorkflowState(ObjectNode checkpoint) {
		ObjectNode state = checkpoint.get(ConversationGraphState.STATE) instanceof ObjectNode node
			? node
			: objectMapper.createObjectNode();
		checkpoint.set(ConversationGraphState.STATE, state);
		return state;
	}

	private WorkflowStateStore resolveWorkflowStateStore(UUID sessionId) {
		WorkflowStateStore store = WorkflowStateStoreContext.get();
		if (store != null) {
			return store;
		}
		if (sessionId == null) {
			return null;
		}
		return new WorkflowStateStoreDatabase(sessionId);
	}

	private final class WorkflowStateStoreDatabase implements WorkflowStateStore {
		private final UUID sessionId;
		private WorkflowRun cachedRun;

		private WorkflowStateStoreDatabase(UUID sessionId) {
			this.sessionId = sessionId;
		}

		@Override
		public ObjectNode getState() {
			WorkflowRun run = loadRun();
			if (run == null) {
				return objectMapper.createObjectNode();
			}
			ObjectNode checkpoint = ensureCheckpoint(run);
			return ensureWorkflowState(checkpoint);
		}

		@Override
		public JsonNode getState(String path) {
			ObjectNode state = getState();
			return readStatePath(state, path);
		}

		@Override
		public void setState(String path, JsonNode value) {
			WorkflowRun run = loadRun();
			if (run == null) {
				return;
			}
			checkpointService.updateProjectionFromCheckpoint(
				run,
				existing -> {
					ObjectNode checkpoint = ensureCheckpoint(run);
					ObjectNode state = ensureWorkflowState(checkpoint);
					writeStatePath(state, path, value);
					return checkpoint;
				}
			);
		}

		@Override
		public void patchState(JsonNode updates) {
			WorkflowRun run = loadRun();
			if (run == null) {
				return;
			}
			if (updates == null || !updates.isArray()) {
				return;
			}
			checkpointService.updateProjectionFromCheckpoint(
				run,
				existing -> {
					ObjectNode checkpoint = ensureCheckpoint(run);
					ObjectNode state = ensureWorkflowState(checkpoint);
					for (JsonNode update : updates) {
						if (update == null || !update.isObject()) {
							continue;
						}
						String path = readText(update, "path");
						if (path == null || path.isBlank()) {
							continue;
						}
						JsonNode value = update.get("value");
						writeStatePath(state, path, value == null ? objectMapper.nullNode() : value);
					}
					return checkpoint;
				}
			);
		}

		@Override
		public JsonNode getSchema() {
			WorkflowRun run = loadRun();
			if (run == null) {
				return null;
			}
			JsonNode definition = loadWorkflowDefinitionJson(run);
			return definition == null ? null : definition.get("state_schema");
		}

		private WorkflowRun loadRun() {
			if (cachedRun != null) {
				return cachedRun;
			}
			cachedRun = resolveWorkflowRun(sessionId);
			return cachedRun;
		}
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

	private JsonNode readStatePath(ObjectNode state, String path) {
		if (state == null || path == null || path.isBlank()) {
			return null;
		}
		String normalized = normalizeStatePath(path);
		if (normalized == null) {
			return null;
		}
		if (normalized.isBlank()) {
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

	private void writeStatePath(ObjectNode state, String path, JsonNode value) {
		if (state == null || path == null || path.isBlank()) {
			return;
		}
		String normalized = normalizeStatePath(path);
		if (normalized == null) {
			return;
		}
		if (normalized.isBlank()) {
			if (value instanceof ObjectNode objectValue) {
				state.removeAll();
				state.setAll(objectValue);
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

	private String readText(JsonNode node, String field) {
		if (node == null || field == null) {
			return null;
		}
		JsonNode value = node.get(field);
		return value != null && value.isTextual() ? value.asText() : null;
	}

	private int readInt(JsonNode node, String field, int fallback) {
		if (node == null || field == null) {
			return fallback;
		}
		JsonNode value = node.get(field);
		return value != null && value.isNumber() ? value.asInt(fallback) : fallback;
	}

	private boolean supportsDiffResource(McpServer server) {
		if (server == null || server.virtualType == null) {
			return false;
		}
		return VIRTUAL_TYPE_POLYMR_SCRIPTS.equalsIgnoreCase(server.virtualType)
			|| VIRTUAL_TYPE_POLYMR_PAGES.equalsIgnoreCase(server.virtualType);
	}

	private String readClasspathResource(String path) {
		try (InputStream stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
			if (stream == null) {
				throw new RuntimeException("Resource not found");
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new RuntimeException("Failed to read resource", ex);
		}
	}

	private JsonNode error(String message) {
		ObjectNode payload = objectMapper.createObjectNode();
		putTextContent(payload, message == null || message.isBlank() ? "Failed to send" : message);
		payload.put("isError", true);
		payload.put("error", true);
		payload.put("message", message);
		return payload;
	}

	private void putTextContent(ObjectNode payload, String text) {
		if (payload == null || text == null || text.isBlank()) {
			return;
		}
		ArrayNode content = payload.putArray("content");
		ObjectNode entry = content.addObject();
		entry.put("type", "text");
		entry.put("text", text);
	}

	private void putJsonContent(ObjectNode payload, JsonNode value) {
		if (payload == null || value == null || value.isNull()) {
			return;
		}
		putTextContent(payload, value.toString());
	}

	private ObjectNode buildCreateEmbeddingTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_CREATE_EMBEDDING);
		tool.put(
			"description",
			"Create embeddings for text using the registered tenant "
				+ "embedding model. This performs simple recursive chunking to keep each segment below "
				+ "the model max size and does not persist embeddings. It is useful when building a "
				+ "larger application that needs semantic search, not as a standalone storage tool."
		);
		ObjectNode schema = tool.putObject("inputSchema");
		schema.put("type", "object");
		schema.put("additionalProperties", false);
		ObjectNode props = schema.putObject("properties");
		props.putObject("tenant_id")
			.put("type", "string")
			.put("description", "Tenant id used to resolve the tenant embedding task.");
		props.putObject("text").put("type", "string").put("description", "Text to embed.");
		ArrayNode required = schema.putArray("required");
		required.add("tenant_id");
		required.add("text");
		return tool;
	}

	private ObjectNode buildCreatePublicLinkForBlobTool() {
		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", TOOL_CREATE_PUBLIC_LINK_FOR_BLOB);
		tool.put(
			"description",
			"Create a short-lived URL for a blob attachment reference like blob:/workspaceId/hash."
		);
		ObjectNode schema = tool.putObject("inputSchema");
		schema.put("type", "object");
		schema.put("additionalProperties", false);
		ObjectNode props = schema.putObject("properties");
		props.putObject("blob_url")
			.put("type", "string")
			.put("description", "Internal blob reference in the form blob:/workspaceId/hash.");
		ArrayNode required = schema.putArray("required");
		required.add("blob_url");
		return tool;
	}

	private JsonNode callCreateEmbeddingTool(JsonNode arguments) {
		UUID tenantId = readTenantId(arguments);
		if (tenantId == null) {
			return error("Tenant id is required to resolve the embedding task");
		}
		String text = arguments == null ? null : arguments.path("text").asText(null);
		if (text == null || text.isBlank()) {
			return error("Text is required");
		}
		TenantAutomationTask task = resolveEmbeddingTask(tenantId);
		if (task == null || task.model == null) {
			return error(
				"No embedding automation task is configured for this tenant. "
					+ "Configure the embedding task before calling create_embedding."
			);
		}
		AiEmbeddingModelDefinition embeddingDefinition = modelDefinitionFor(task.model);
		if (embeddingDefinition == null) {
			return error("The configured embedding automation task does not reference a supported embedding model.");
		}
		EmbeddingModel embeddingModel = embeddingDefinition.createEmbeddingModel(
			task.model.configJson == null
				? java.util.Map.of()
				: objectMapper.convertValue(task.model.configJson, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {})
		);
		int maxSegmentSize = resolveEmbeddingMaxSegmentSize(embeddingModel);
		Document document = Document.from(text);
		ArrayNode embeddings = objectMapper.createArrayNode();
		String content = document.text();
		if (content != null && content.length() > maxSegmentSize) {
			List<TextSegment> segments = DocumentSplitters.recursive(maxSegmentSize, maxSegmentSize).split(document);
			for (TextSegment segment : segments) {
				addEmbeddingSegment(embeddingModel, embeddings, segment.text());
			}
		}
		else {
			addEmbeddingSegment(embeddingModel, embeddings, content);
		}
		ObjectNode payload = objectMapper.createObjectNode();
		putTextContent(
			payload,
			embeddings.isEmpty() ? "No embeddings created" : "Created " + embeddings.size() + " embeddings"
		);
		payload.set("embeddings", embeddings);
		payload.put("segmentCount", embeddings.size());
		return payload;
	}

	private JsonNode createPublicLinkForBlob(JsonNode arguments, UUID sessionId) {
		String blobUrl = arguments == null ? null : arguments.path("blob_url").asText(null);
		BlobReference reference = parseBlobReference(blobUrl);
		if (reference == null) {
			return error("blob_url must be in the form blob:/workspaceId/hash");
		}
		Session session = sessionId == null ? null : entityManager.find(Session.class, sessionId);
		if (session == null || session.workspace == null || session.workspace.id == null) {
			return error("Session workspace is required");
		}
		if (!session.workspace.id.equals(reference.workspaceId())) {
			return error("Blob reference does not belong to the current session workspace");
		}
		PublicWorkspaceBlobStore publicStore = sessionChatService.publicBlobStore();
		if (publicStore == null) {
			return error("The configured blob store does not support public links");
		}
		PublicBlobLink link = attachmentLinkService.resolvePublicLink(reference.workspaceId(), reference.hash(), publicStore)
			.orElse(null);
		if (link == null || link.uri() == null) {
			return error("Could not create a public link for this blob");
		}
		ObjectNode payload = objectMapper.createObjectNode();
		putTextContent(payload, link.uri().toString());
		payload.put("url", link.uri().toString());
		if (link.expiresAt() != null) {
			payload.put("expiresAt", link.expiresAt().toString());
		}
		return payload;
	}

	private BlobReference parseBlobReference(String blobUrl) {
		if (blobUrl == null || blobUrl.isBlank() || !blobUrl.startsWith("blob:/")) {
			return null;
		}
		String value = blobUrl.substring("blob:/".length());
		int slash = value.indexOf('/');
		if (slash <= 0 || slash == value.length() - 1) {
			return null;
		}
		try {
			UUID workspaceId = UUID.fromString(value.substring(0, slash));
			String hash = value.substring(slash + 1);
			if (hash.isBlank()) {
				return null;
			}
			return new BlobReference(workspaceId, hash);
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private AiEmbeddingModelDefinition modelDefinitionFor(be.celerex.polymr.model.AiModel model) {
		if (model == null || model.provider == null || model.configJson == null) {
			return null;
		}
		return modelProviderRegistry.findEmbedding(model.provider)
			.flatMap(provider -> provider.resolveEmbeddingModel(model.configJson.path("model_id").asText(null)))
			.orElse(null);
	}

	private TenantAutomationTask resolveEmbeddingTask(UUID tenantId) {
		if (tenantId == null) {
			return null;
		}
		return entityManager.createQuery(
				"select t from TenantAutomationTask t join fetch t.model where t.tenant.id "
					+ "= :tenantId and t.taskType = :taskType and t.enabled = true",
				TenantAutomationTask.class
			)
			.setParameter("tenantId", tenantId)
			.setParameter("taskType", "EMBEDDING")
			.getResultStream()
			.findFirst()
			.orElse(null);
	}

	private UUID readTenantId(JsonNode arguments) {
		if (arguments == null) {
			return null;
		}
		JsonNode tenantNode = arguments.get("tenant_id");
		if (tenantNode == null || tenantNode.isNull() || !tenantNode.isTextual()) {
			return null;
		}
		try {
			return UUID.fromString(tenantNode.asText());
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private void addEmbeddingSegment(EmbeddingModel embeddingModel, ArrayNode embeddings, String segmentText) {
		Embedding embedding = embeddingModel.embed(segmentText).content();
		ArrayNode vector = objectMapper.createArrayNode();
		for (float value : embedding.vector()) {
			vector.add(value);
		}
		ObjectNode item = objectMapper.createObjectNode();
		item.put("text", segmentText);
		item.set("vector", vector);
		embeddings.add(item);
	}

	private int resolveEmbeddingMaxSegmentSize(EmbeddingModel embeddingModel) {
		if (embeddingModel == null) {
			return 1000;
		}
		try {
			Object metadata = embeddingModel.getClass().getMethod("maxSegmentSize").invoke(embeddingModel);
			if (metadata instanceof Number number && number.intValue() > 0) {
				return number.intValue();
			}
		}
		catch (Exception ignored) {}
		return 1000;
	}

	private String displayName(be.celerex.polymr.model.User user) {
		if (user == null) {
			return null;
		}
		if (user.nickname != null && !user.nickname.isBlank()) {
			return user.nickname;
		}
		return user.email;
	}

	private String avatarUrl(be.celerex.polymr.model.User user) {
		if (user == null || user.avatarBytes == null || user.avatarBytes.length == 0) {
			return null;
		}
		String suffix = user.avatarUpdatedAt == null ? "" : "?v=" + user.avatarUpdatedAt.toEpochMilli();
		return "/api/users/" + user.id + "/avatar" + suffix;
	}

	private record BlobReference(UUID workspaceId, String hash) {}
}
