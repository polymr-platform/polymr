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

package be.celerex.polymr.util;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public final class SlugSupport {
	private SlugSupport() {}

	public static String normalizeNamespace(String value) {
		if (value == null) {
			return null;
		}
		String normalized = Arrays.stream(value.trim().split("/+"))
			.map(String::trim)
			.filter(segment -> !segment.isBlank())
			.collect(Collectors.joining("/"));
		return normalized.isBlank() ? null : normalized;
	}

	public static String buildSlug(String namespace, String name) {
		String normalizedNamespace = normalizeNamespace(namespace);
		List<String> parts = Arrays.stream(normalizedNamespace == null ? new String[0] : normalizedNamespace.split("/"))
			.map(SlugSupport::toPartSlug)
			.filter(part -> !part.isBlank())
			.collect(Collectors.toList());
		String namePart = toPartSlug(name);
		if (!namePart.isBlank()) {
			parts.add(namePart);
		}
		String slug = String.join("-", parts);
		if (slug.isBlank()) {
			throw new WebApplicationException("Slug is required", Response.Status.BAD_REQUEST);
		}
		return slug;
	}

	public static String normalizeSlugValue(String value) {
		return toPartSlug(value);
	}

	public static String slugFromPath(String path) {
		if (path == null) {
			return "";
		}
		String normalized = normalizeNamespace(path);
		if (normalized == null) {
			return "";
		}
		if (normalized.toLowerCase().endsWith(".groovy")) {
			normalized = normalized.substring(0, normalized.length() - 7);
		}
		else if (normalized.toLowerCase().endsWith(".vue")) {
			normalized = normalized.substring(0, normalized.length() - 4);
		}
		String[] parts = normalized.split("/");
		String slug = Arrays.stream(parts)
			.map(SlugSupport::normalizeSlugValue)
			.filter(part -> !part.isBlank())
			.collect(Collectors.joining("-"));
		return slug;
	}

	private static String toPartSlug(String value) {
		if (value == null) {
			return "";
		}
		return value.trim()
			.toLowerCase()
			.replaceAll("[^a-z0-9]+", "_")
			.replaceAll("_+", "_")
			.replaceAll("^_+|_+$", "");
	}
}
