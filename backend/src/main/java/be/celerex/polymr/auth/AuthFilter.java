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

package be.celerex.polymr.auth;

import be.celerex.polymr.model.Token;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.ext.Provider;
import java.security.Principal;
import java.util.Optional;
import org.jboss.logging.Logger;

@Provider
@Priority(Priorities.AUTHENTICATION)
public class AuthFilter implements ContainerRequestFilter {
	private static final Logger LOGGER = Logger.getLogger(AuthFilter.class);
	private static final boolean DEBUG_LOG = Boolean.getBoolean("polymr.auth.debug");
	@Inject
	TokenService tokenService;

	@Override
	public void filter(ContainerRequestContext requestContext) {
		String header = requestContext.getHeaderString("Authorization");
		String path = requestContext.getUriInfo().getPath();
		if (path.startsWith("/")) {
			path = path.substring(1);
		}
		if ("OPTIONS".equalsIgnoreCase(requestContext.getMethod())) {
			return;
		}
		if (!path.startsWith("api/") || path.startsWith("api/auth")) {
			return;
		}
		if (path.startsWith("api/public/")) {
			return;
		}
		if (path.startsWith("api/tenants/") && path.contains("/workspaces/") && path.contains("/attachments/")) {
			return;
		}
		if (path.startsWith("api/tenants/") && path.contains("/workspaces/") && path.contains("/ui-assets/")) {
			return;
		}
		if (path.startsWith("api/tenants/")
				&& path.contains("/workspaces/")
				&& path.contains("/mcp-servers/")
				&& path.contains("/oauth/callback")) {
			return;
		}

		if (header == null || !header.startsWith("Bearer ")) {
			if (DEBUG_LOG) {
				System.out.println("AUTH_DEBUG Missing or invalid Authorization header");
			}
			requestContext.abortWith(jakarta.ws.rs.core.Response.status(401).build());
			return;
		}
		String tokenValue = header.substring("Bearer ".length()).trim();
		Optional<Token> token = tokenService.verifyAccessToken(tokenValue);
		if (token.isEmpty()) {
			requestContext.abortWith(jakarta.ws.rs.core.Response.status(401).build());
			return;
		}
		String userId = token.get().user.id.toString();
		SecurityContext securityContext = new UserSecurityContext(userId, requestContext.getSecurityContext());
		requestContext.setSecurityContext(securityContext);
	}

	private record UserSecurityContext(String userId, SecurityContext delegate) implements SecurityContext {
		@Override
		public Principal getUserPrincipal() {
			return () -> userId;
		}

		@Override
		public boolean isUserInRole(String role) {
			return false;
		}

		@Override
		public boolean isSecure() {
			return delegate.isSecure();
		}

		@Override
		public String getAuthenticationScheme() {
			return "Bearer";
		}
	}
}
