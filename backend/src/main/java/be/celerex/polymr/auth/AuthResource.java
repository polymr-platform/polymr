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

import be.celerex.polymr.auth.dto.AuthResponse;
import be.celerex.polymr.auth.dto.LoginRequest;
import be.celerex.polymr.auth.dto.RegisterRequest;
import be.celerex.polymr.model.Token;
import be.celerex.polymr.model.User;
import jakarta.inject.Inject;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Path("/api/auth")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class AuthResource {
	@Inject
	AuthService authService;

	@Inject
	TokenService tokenService;

	private static final String DEVICE_COOKIE = "polymr_device";
	private static final String REFRESH_COOKIE = "polymr_refresh";

	@POST
	@Path("/register")
	public Response register(RegisterRequest request, @CookieParam(DEVICE_COOKIE) String deviceId) {
		User user = authService.register(request);
		return buildAuthResponse(user, ensureDeviceId(deviceId));
	}

	@POST
	@Path("/login")
	public Response login(LoginRequest request, @CookieParam(DEVICE_COOKIE) String deviceId) {
		User user = authService.login(request);
		return buildAuthResponse(user, ensureDeviceId(deviceId));
	}

	@POST
	@Path("/refresh")
	public Response refresh(
			@CookieParam(REFRESH_COOKIE) String refreshToken,
			@CookieParam(DEVICE_COOKIE) String deviceId) {
		if (refreshToken == null || deviceId == null) {
			return Response.status(Response.Status.UNAUTHORIZED).build();
		}
		Token token = tokenService.verifyRefreshToken(refreshToken, deviceId).orElse(null);
		if (token == null) {
			return Response.status(Response.Status.UNAUTHORIZED).build();
		}
		TokenService.TokenPair issued = tokenService.refresh(token);
		AuthResponse response = buildResponse(token.user, issued.access().value(), issued.access().expiresAt());
		return Response.ok(response)
			.cookie(buildRefreshCookie(issued.refresh().value(), issued.refresh().expiresAt()))
			.build();
	}

	@POST
	@Path("/logout")
	public Response logout(
			@CookieParam(REFRESH_COOKIE) String refreshToken,
			@CookieParam(DEVICE_COOKIE) String deviceId) {
		if (refreshToken != null && deviceId != null) {
			tokenService.verifyRefreshToken(refreshToken, deviceId).ifPresent(tokenService::revokeToken);
		}
		NewCookie refreshCookie = clearCookie(REFRESH_COOKIE);
		return Response.noContent().cookie(refreshCookie).build();
	}

	private Response buildAuthResponse(User user, Device device) {
		TokenService.TokenIssue access = tokenService.issueAccessToken(user, device.id());
		TokenService.TokenIssue refresh = tokenService.issueRefreshToken(user, device.id());
		AuthResponse response = buildResponse(user, access.value(), access.expiresAt());
		Response.ResponseBuilder builder = Response.ok(response)
			.cookie(buildRefreshCookie(refresh.value(), refresh.expiresAt()));
		if (device.isNew()) {
			builder.cookie(buildDeviceCookie(device.id()));
		}
		return builder.build();
	}

	private AuthResponse buildResponse(User user, String accessToken, Instant expiresAt) {
		long expiresIn = Duration.between(Instant.now(), expiresAt).getSeconds();
		return new AuthResponse(user.id, user.email, accessToken, expiresIn);
	}

	private Device ensureDeviceId(String deviceId) {
		if (deviceId != null && !deviceId.isBlank()) {
			return new Device(deviceId, false);
		}
		return new Device(UUID.randomUUID().toString(), true);
	}

	private NewCookie buildDeviceCookie(String deviceId) {
		return new NewCookie.Builder(DEVICE_COOKIE)
			.value(deviceId)
			.path("/")
			.httpOnly(true)
			.sameSite(NewCookie.SameSite.STRICT)
			.build();
	}

	private NewCookie buildRefreshCookie(String refreshToken, Instant expiresAt) {
		return new NewCookie.Builder(REFRESH_COOKIE)
			.value(refreshToken)
			.path("/")
			.httpOnly(true)
			.maxAge((int) Duration.between(Instant.now(), expiresAt).getSeconds())
			.sameSite(NewCookie.SameSite.STRICT)
			.build();
	}

	private NewCookie clearCookie(String name) {
		return new NewCookie.Builder(name)
			.value("")
			.path("/")
			.httpOnly(true)
			.maxAge(0)
			.sameSite(NewCookie.SameSite.STRICT)
			.build();
	}

	private record Device(String id, boolean isNew) {}
}
