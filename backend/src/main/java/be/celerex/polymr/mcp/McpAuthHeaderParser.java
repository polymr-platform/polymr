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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class McpAuthHeaderParser {
	private static final Pattern RESOURCE_METADATA_QUOTED = Pattern.compile("resource_metadata\\s*=\\s*\"([^\"]+)\"");
	private static final Pattern RESOURCE_METADATA_BARE = Pattern.compile("resource_metadata\\s*=\\s*([^,\\s]+)");

	private McpAuthHeaderParser() {}

	public static String extractResourceMetadata(String headerValue) {
		if (headerValue == null || headerValue.isBlank()) {
			return null;
		}
		Matcher quoted = RESOURCE_METADATA_QUOTED.matcher(headerValue);
		if (quoted.find()) {
			return quoted.group(1).trim();
		}
		Matcher bare = RESOURCE_METADATA_BARE.matcher(headerValue);
		if (bare.find()) {
			return bare.group(1).trim();
		}
		return null;
	}
}
