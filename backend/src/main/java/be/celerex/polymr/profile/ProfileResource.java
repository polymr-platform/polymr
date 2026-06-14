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

package be.celerex.polymr.profile;

import be.celerex.polymr.model.Rule;
import be.celerex.polymr.model.RuleScope;
import be.celerex.polymr.model.User;
import be.celerex.polymr.model.UserExecutionMode;
import be.celerex.polymr.profile.dto.ProfileRequest;
import be.celerex.polymr.profile.dto.ProfileResponse;
import be.celerex.polymr.profile.dto.UserRuleRequest;
import be.celerex.polymr.profile.dto.UserRuleResponse;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.Context;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.Base64;

@Path("/api/users")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ProfileResource {
	@Inject
	EntityManager entityManager;

	@Context
	SecurityContext securityContext;

	@Inject
	UserExecutionModeService userExecutionModeService;

	@GET
	@Path("/{userId}")
	public ProfileResponse getProfile(@PathParam("userId") UUID userId) {
		User user = requireUser(userId);
		return new ProfileResponse(
			user.id,
			user.email,
			user.nickname,
			avatarUrl(user),
			user.notificationsSnoozedUntil == null ? null : user.notificationsSnoozedUntil.toString(),
			userExecutionModeService.resolve(user).name().toLowerCase(Locale.ROOT)
		);
	}

	@PUT
	@Path("/{userId}")
	@Transactional
	public ProfileResponse updateProfile(@PathParam("userId") UUID userId, ProfileRequest request) {
		User user = requireUser(userId);
		if (request.email() != null && !request.email().isBlank()) {
			user.email = request.email().trim().toLowerCase(Locale.ROOT);
		}
		if (request.nickname() != null) {
			user.nickname = request.nickname().isBlank() ? null : request.nickname().trim();
		}
		if (request.avatar_base64() != null) {
			if (request.avatar_base64().isBlank()) {
				user.avatarBytes = null;
				user.avatarContentType = null;
				user.avatarUpdatedAt = Instant.now();
			}
			else {
				String contentType = request.avatar_content_type();
				if (contentType == null || contentType.isBlank()) {
					contentType = "image/png";
				}
				byte[] bytes = Base64.getDecoder().decode(request.avatar_base64());
				user.avatarBytes = bytes;
				user.avatarContentType = contentType.trim();
				user.avatarUpdatedAt = Instant.now();
			}
		}
		if (request.notifications_snoozed_until() != null) {
			if (request.notifications_snoozed_until().isBlank()) {
				user.notificationsSnoozedUntil = null;
			}
			else {
				try {
					user.notificationsSnoozedUntil = Instant.parse(request.notifications_snoozed_until());
				}
				catch (Exception ex) {
					throw new WebApplicationException("Invalid snooze timestamp", Response.Status.BAD_REQUEST);
				}
			}
		}
		if (request.execution_mode() != null) {
			String value = request.execution_mode().trim();
			if (value.isEmpty()) {
				user.executionMode = UserExecutionMode.RELEASED;
			}
			else {
				try {
					user.executionMode = UserExecutionMode.valueOf(value.toUpperCase(Locale.ROOT));
				}
				catch (IllegalArgumentException ex) {
					throw new WebApplicationException("Invalid execution mode", Response.Status.BAD_REQUEST);
				}
			}
		}
		return new ProfileResponse(
			user.id,
			user.email,
			user.nickname,
			avatarUrl(user),
			user.notificationsSnoozedUntil == null ? null : user.notificationsSnoozedUntil.toString(),
			userExecutionModeService.resolve(user).name().toLowerCase(Locale.ROOT)
		);
	}

	@GET
	@Path("/{userId}/avatar")
	@Produces({"image/png", "image/jpeg", "image/webp", MediaType.APPLICATION_OCTET_STREAM})
	public Response getAvatar(@PathParam("userId") UUID userId) {
		requireAuthenticated();
		User user = entityManager.find(User.class, userId);
		if (user == null || user.avatarBytes == null || user.avatarBytes.length == 0) {
			throw new WebApplicationException("Avatar not found", Response.Status.NOT_FOUND);
		}
		String contentType = user.avatarContentType == null || user.avatarContentType.isBlank()
			? MediaType.APPLICATION_OCTET_STREAM
			: user.avatarContentType;
		return Response.ok(user.avatarBytes)
			.type(contentType)
			.header("Cache-Control", "public, max-age=3600")
			.build();
	}

	@GET
	@Path("/{userId}/rules")
	public List<UserRuleResponse> listUserRules(@PathParam("userId") UUID userId) {
		requireUser(userId);
		return entityManager.createQuery(
				"select r from Rule r where r.scope = :scope and r.user.id = :userId "
					+ "order by coalesce(r.order, 0) desc, r.name",
				Rule.class
			)
			.setParameter("scope", RuleScope.USER)
			.setParameter("userId", userId)
			.getResultList()
			.stream()
			.map(this::toRuleResponse)
			.toList();
	}

	@POST
	@Path("/{userId}/rules")
	@Transactional
	public UserRuleResponse createUserRule(@PathParam("userId") UUID userId, UserRuleRequest request) {
		User user = requireUser(userId);
		validateRule(request);
		Rule rule = new Rule();
		rule.scope = RuleScope.USER;
		rule.user = user;
		rule.name = request.name() == null || request.name().isBlank() ? "User rule" : request.name().trim();
		rule.content = request.content().trim();
		rule.alwaysIncluded = true;
		rule.enabled = request.enabled() == null ? true : request.enabled();
		entityManager.persist(rule);
		return toRuleResponse(rule);
	}

	@PUT
	@Path("/{userId}/rules/{ruleId}")
	@Transactional
	public UserRuleResponse updateUserRule(
			@PathParam("userId") UUID userId,
			@PathParam("ruleId") UUID ruleId,
			UserRuleRequest request) {
		requireUser(userId);
		validateRule(request);
		Rule rule = entityManager.find(Rule.class, ruleId);
		if (rule == null || rule.user == null || !rule.user.id.equals(userId)) {
			throw new WebApplicationException("Rule not found", Response.Status.NOT_FOUND);
		}
		rule.name = request.name() == null || request.name().isBlank() ? rule.name : request.name().trim();
		rule.content = request.content().trim();
		if (request.enabled() != null) {
			rule.enabled = request.enabled();
		}
		return toRuleResponse(rule);
	}

	@DELETE
	@Path("/{userId}/rules/{ruleId}")
	@Transactional
	public Response deleteUserRule(@PathParam("userId") UUID userId, @PathParam("ruleId") UUID ruleId) {
		requireUser(userId);
		Rule rule = entityManager.find(Rule.class, ruleId);
		if (rule == null || rule.user == null || !rule.user.id.equals(userId)) {
			throw new WebApplicationException("Rule not found", Response.Status.NOT_FOUND);
		}
		entityManager.remove(rule);
		return Response.noContent().build();
	}

	private User requireUser(UUID userId) {
		requireAuthorizedUser(userId);
		User user = entityManager.find(User.class, userId);
		if (user == null) {
			throw new WebApplicationException("User not found", Response.Status.NOT_FOUND);
		}
		return user;
	}

	private void requireAuthorizedUser(UUID userId) {
		if (securityContext == null || securityContext.getUserPrincipal() == null) {
			throw new WebApplicationException("Unauthorized", Response.Status.UNAUTHORIZED);
		}
		String principalId = securityContext.getUserPrincipal().getName();
		if (!userId.toString().equals(principalId)) {
			throw new WebApplicationException("Forbidden", Response.Status.FORBIDDEN);
		}
	}

	private void requireAuthenticated() {
		if (securityContext == null || securityContext.getUserPrincipal() == null) {
			throw new WebApplicationException("Unauthorized", Response.Status.UNAUTHORIZED);
		}
	}

	private String avatarUrl(User user) {
		if (user == null || user.avatarBytes == null || user.avatarBytes.length == 0) {
			return null;
		}
		String suffix = user.avatarUpdatedAt == null ? "" : "?v=" + user.avatarUpdatedAt.toEpochMilli();
		return "/api/users/" + user.id + "/avatar" + suffix;
	}

	private void validateRule(UserRuleRequest request) {
		if (request == null || request.content() == null || request.content().isBlank()) {
			throw new WebApplicationException("Content is required", Response.Status.BAD_REQUEST);
		}
	}

	private UserRuleResponse toRuleResponse(Rule rule) {
		return new UserRuleResponse(rule.id, rule.name, rule.content, rule.enabled);
	}
}
