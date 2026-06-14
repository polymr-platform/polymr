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

package be.celerex.polymr.pages;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@ApplicationScoped
public class DesignApiDefinitionService {
	private static final String BASE_PATH = "/design-api/";
	private static final Map<String, String> NAME_TO_FILE = Map.of(
		"getUsers",
		"getUsers.md",
		"callTool",
		"callTool.md",
		"callScript",
		"callScript.md",
		"notify",
		"notify.md"
	);

	public String loadDefinition(String name) {
		if (name == null || name.isBlank()) {
			return null;
		}
		String file = NAME_TO_FILE.get(name.trim());
		if (file == null) {
			return null;
		}
		try (InputStream stream = getClass().getResourceAsStream(BASE_PATH + file)) {
			if (stream == null) {
				return null;
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (Exception ex) {
			org.jboss.logging.Logger
				.getLogger(DesignApiDefinitionService.class)
				.debugf(ex, "Failed to load design API definition %s", name);
		}
		return null;
	}
}
