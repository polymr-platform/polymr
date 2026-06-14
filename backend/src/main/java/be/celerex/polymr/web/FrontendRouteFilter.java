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

package be.celerex.polymr.web;

import java.util.Set;
import io.quarkus.vertx.web.RouteFilter;
import io.vertx.core.http.HttpHeaders;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class FrontendRouteFilter {
	private static final Set<String> STATIC_EXTENSIONS = Set.of(
		".css",
		".js",
		".map",
		".png",
		".jpg",
		".jpeg",
		".gif",
		".svg",
		".ico",
		".webp",
		".woff",
		".woff2",
		".ttf",
		".eot",
		".txt",
		".json",
		".webmanifest"
	);

	@RouteFilter(1000)
	void serveFrontendIndex(RoutingContext context) {
		if (!"GET".equals(context.request().method().name())) {
			context.next();
			return;
		}
		String path = context.normalizedPath();
		if (path == null || path.isBlank()) {
			context.next();
			return;
		}
		if ("/index.html".equals(path) || "/".equals(path)) {
			context.next();
			return;
		}
		if (path.startsWith("/api") || path.startsWith("/q") || path.startsWith("/ws")) {
			context.next();
			return;
		}
		if (isStaticAsset(path)) {
			context.next();
			return;
		}
		context.response().putHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
		context.reroute("/index.html");
	}

	private boolean isStaticAsset(String path) {
		int extensionIndex = path.lastIndexOf('.');
		if (extensionIndex < 0) {
			return false;
		}
		return STATIC_EXTENSIONS.contains(path.substring(extensionIndex));
	}
}
