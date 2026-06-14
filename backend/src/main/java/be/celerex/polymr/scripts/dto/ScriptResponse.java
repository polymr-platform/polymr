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

package be.celerex.polymr.scripts.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ScriptResponse(
		UUID id,
		String name,
		String description,
		String namespace,
		String slug,
		JsonNode input_schema,
		JsonNode output_schema,
		JsonNode workflow_state_schema,
		String type,
		UUID workflow_definition_id,
		boolean draft_present,
		boolean released,
		UUID active_version_id,
		UUID created_by_user_id,
		Instant updated_at,
		boolean scheduled,
		String schedule_rrule,
		String schedule_timezone,
		Instant schedule_start_at,
		Instant schedule_end_at,
		Instant next_run_at,
		Instant last_run_at,
		boolean tool_hook_enabled,
		String tool_hook_phase,
		List<String> tool_hook_tool_names) {}
