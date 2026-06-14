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

package be.celerex.polymr.modelregistry.provider;

import java.util.Map;

public class ProviderConfig {
	private final Map<String, Object> config;

	public ProviderConfig(Map<String, Object> config) {
		this.config = config == null ? Map.of() : config;
	}

	public String string(String key) {
		Object value = config.get(key);
		if (value == null) {
			return null;
		}
		return value.toString();
	}

	public String requiredString(String key) {
		String value = string(key);
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("Missing required config: " + key);
		}
		return value;
	}

	public Double number(String key) {
		Object value = config.get(key);
		if (value == null) {
			return null;
		}
		if (value instanceof Number number) {
			return number.doubleValue();
		}
		if (value instanceof String text && !text.isBlank()) {
			return Double.parseDouble(text);
		}
		return null;
	}

	public Integer integer(String key) {
		Double value = number(key);
		return value == null ? null : value.intValue();
	}

	public Boolean bool(String key) {
		Object value = config.get(key);
		if (value == null) {
			return null;
		}
		if (value instanceof Boolean bool) {
			return bool;
		}
		if (value instanceof String text && !text.isBlank()) {
			return Boolean.parseBoolean(text);
		}
		return null;
	}
}
