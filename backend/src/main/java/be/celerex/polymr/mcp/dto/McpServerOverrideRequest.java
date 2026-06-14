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

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

public record McpServerOverrideRequest(
		UUID tag_id,
		String logical_name,
		String http_url,
		String headers,
		Boolean ssh_enabled,
		JsonNode ssh_tunnel,
		JsonNode auth) {}
