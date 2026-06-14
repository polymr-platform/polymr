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

package be.celerex.polymr.assistant.dto;

import java.util.UUID;
import java.util.List;

public record AssistantRequest(
		String name,
		String description,
		String prompt_text,
		UUID persona_id,
		UUID model_id,
		UUID workspace_id,
		Integer max_output_tokens,
		Integer max_turns,
		List<UUID> skill_ids,
		List<UUID> rule_ids,
		Boolean worker_enabled,
		String worker_trigger,
		List<String> worker_allow_scopes,
		List<String> worker_deny_scopes) {}
