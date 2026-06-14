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

import be.celerex.polymr.mcp.ToolHookContext;
import be.celerex.polymr.mcp.ToolHookContextHolder;
import be.celerex.polymr.mcp.ToolHookMcpService;
import be.celerex.polymr.model.Script;
import be.celerex.polymr.model.ScriptToolHookPhase;
import be.celerex.polymr.model.ScriptType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@ApplicationScoped
public class ScriptToolHookService {
	@Inject
	EntityManager entityManager;

	@Inject
	ScriptRuntimeService runtimeService;

	@Inject
	ObjectMapper objectMapper;

	@Inject
	ToolHookMcpService hookMcpService;

	public HookResult applyBeforeHooks(UUID workspaceId, String toolName, JsonNode input, UUID userId) {
		return applyHooks(workspaceId, toolName, input, null, ScriptToolHookPhase.BEFORE, userId);
	}

	public HookResult applyAfterHooks(UUID workspaceId, String toolName, JsonNode input, JsonNode output, UUID userId) {
		return applyHooks(workspaceId, toolName, input, output, ScriptToolHookPhase.AFTER, userId);
	}

	private HookResult applyHooks(
			UUID workspaceId,
			String toolName,
			JsonNode input,
			JsonNode output,
			ScriptToolHookPhase phase,
			UUID userId) {
		if (workspaceId == null || toolName == null || toolName.isBlank()) {
			return new HookResult(false, null, input, output);
		}
		List<Script> scripts = entityManager.createQuery(
				"select s from Script s where s.workspace.id = :workspaceId "
					+ "and s.disabled = false and s.toolHookEnabled = true and s.type = :type "
					+ "and s.toolHookPhase = :phase order by s.updatedAt asc",
				Script.class
			)
			.setParameter("workspaceId", workspaceId)
			.setParameter("type", ScriptType.STANDALONE)
			.setParameter("phase", phase)
			.getResultList();
		if (scripts.isEmpty()) {
			return new HookResult(false, null, input, output);
		}
		String normalizedName = toolName.trim().toLowerCase(Locale.ROOT);
		String displayName = toolName.trim();
		JsonNode currentInput = input;
		JsonNode currentOutput = output;
		for (Script script : scripts) {
			if (!matchesToolName(script, normalizedName)) {
				continue;
			}
			if (script.activeVersion == null
					|| script.activeVersion.sourceGroovy == null
					|| script.activeVersion.sourceGroovy.isBlank()) {
				continue;
			}
			hookMcpService.ensureToolHookServer(script.workspace);
			ToolHookContext context = new ToolHookContext(objectMapper, displayName, currentInput, currentOutput);
			ToolHookContextHolder.set(context);
			try {
				runtimeService.runScript(script, userId, null, objectMapper.createObjectNode());
			}
			finally {
				ToolHookContextHolder.clear();
			}
			if (context.canceled()) {
				return new HookResult(true, script.name, context.input(), context.output());
			}
			currentInput = context.input();
			currentOutput = context.output();
		}
		return new HookResult(false, null, currentInput, currentOutput);
	}

	private boolean matchesToolName(Script script, String toolName) {
		if (script == null || script.toolHookToolNames == null || script.toolHookToolNames.isEmpty()) {
			return false;
		}
		for (String name : script.toolHookToolNames) {
			if (name == null || name.isBlank()) {
				continue;
			}
			if (toolName.equals(name.trim().toLowerCase(Locale.ROOT))) {
				return true;
			}
		}
		return false;
	}

	public record HookResult(boolean canceled, String scriptName, JsonNode input, JsonNode output) {}
}
