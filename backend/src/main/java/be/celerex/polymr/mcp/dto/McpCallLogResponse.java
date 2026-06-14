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

package be.celerex.polymr.mcp.dto;

import java.time.Instant;
import java.util.UUID;

public record McpCallLogResponse(
		UUID id,
		UUID tenant_id,
		UUID workspace_id,
		UUID mcp_server_id,
		String mcp_server_name,
		String mcp_server_protocol,
		String mcp_server_override_name,
		String mcp_server_override_tag_name,
		UUID session_id,
		UUID user_id,
		UUID connection_id,
		Integer request_id,
		String method,
		String protocol,
		String input,
		String output,
		String status,
		UUID script_call_id,
		UUID script_id,
		Instant created_at) {}
