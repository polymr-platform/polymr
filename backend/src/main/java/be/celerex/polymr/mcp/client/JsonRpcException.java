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

package be.celerex.polymr.mcp.client;

import com.fasterxml.jackson.databind.JsonNode;

public class JsonRpcException extends RuntimeException {
	private final int code;
	private final String data;
	private final JsonNode error;

	public JsonRpcException(JsonNode error) {
		super(error == null ? "JSON-RPC error" : error.toString());
		this.error = error;
		this.code = error == null ? 0 : error.path("code").asInt(0);
		JsonNode dataNode = error == null ? null : error.get("data");
		this.data = dataNode == null ? null : dataNode.toString();
	}

	public int code() {
		return code;
	}

	public String data() {
		return data;
	}

	public JsonNode error() {
		return error;
	}
}
