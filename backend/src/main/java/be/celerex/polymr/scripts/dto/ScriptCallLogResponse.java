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

import java.time.Instant;
import java.util.UUID;

public record ScriptCallLogResponse(
		UUID id,
		UUID script_id,
		String script_name,
		String script_slug,
		String script_path,
		UUID workspace_id,
		UUID tenant_id,
		UUID user_id,
		UUID session_id,
		String input,
		String output,
		String status,
		Instant created_at) {}
