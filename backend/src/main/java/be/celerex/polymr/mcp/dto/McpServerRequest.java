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

import be.celerex.polymr.model.McpProtocol;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record McpServerRequest(
		String name,
		String description,
		McpProtocol protocol,
		String framing,
		String command,
		String cwd,
		String http_url,
		String virtual_type,
		String headers,
		String environment,
		Boolean ssh_enabled,
		JsonNode ssh_tunnel,
		Boolean allow_policy,
		Boolean oauth_enabled,
		String visibility,
		String instructions,
		String prompt,
		String tool_name_prefix,
		Boolean custom_instructions,
		JsonNode config_schema,
		JsonNode configuration_json,
		JsonNode auth,
		List<McpServerImportToolRequest> tools) {}
