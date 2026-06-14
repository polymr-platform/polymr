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

import be.celerex.polymr.auth.dto.LoginRequest;
import be.celerex.polymr.auth.dto.RegisterRequest;
import be.celerex.polymr.model.Tenant;
import be.celerex.polymr.model.TenantMembership;
import be.celerex.polymr.model.TenantRole;
import be.celerex.polymr.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@ApplicationScoped
public class AuthService {
	@Inject
	EntityManager entityManager;

	@Inject
	PasswordHasher passwordHasher;

	@Transactional
	public User register(RegisterRequest request) {
		String email = normalizeEmail(request.email());
		if (email == null || request.password() == null || request.password().isBlank()) {
			throw new WebApplicationException("Email and password are required", Response.Status.BAD_REQUEST);
		}
		if (emailExists(email)) {
			throw new WebApplicationException("Email already registered", Response.Status.CONFLICT);
		}

		PasswordHasher.HashedPassword hashed = passwordHasher.hash(request.password().toCharArray());
		User user = new User();
		user.email = email;
		user.passwordHash = hashed.hash();
		user.passwordSalt = hashed.salt();
		entityManager.persist(user);

		Tenant tenant = new Tenant();
		tenant.name = "Personal";
		tenant.owner = user;
		entityManager.persist(tenant);

		TenantMembership membership = new TenantMembership();
		membership.tenant = tenant;
		membership.user = user;
		membership.role = TenantRole.OWNER;
		entityManager.persist(membership);
		return user;
	}

	@Transactional
	public User login(LoginRequest request) {
		String email = normalizeEmail(request.email());
		if (email == null || request.password() == null) {
			throw new WebApplicationException("Email and password are required", Response.Status.BAD_REQUEST);
		}
		User user = findByEmail(email);
		if (user == null) {
			throw new WebApplicationException("Invalid credentials", Response.Status.UNAUTHORIZED);
		}
		boolean ok = passwordHasher.verify(user.passwordHash, request.password().toCharArray(), user.passwordSalt);
		if (!ok) {
			throw new WebApplicationException("Invalid credentials", Response.Status.UNAUTHORIZED);
		}
		return user;
	}

	private boolean emailExists(String email) {
		Long count = entityManager.createQuery("select count(u) from User u where lower(u.email) = :email", Long.class)
			.setParameter("email", email)
			.getSingleResult();
		return count != null && count > 0;
	}

	private User findByEmail(String email) {
		List<User> result = entityManager.createQuery("select u from User u where lower(u.email) = :email", User.class)
			.setParameter("email", email)
			.setMaxResults(1)
			.getResultList();
		return result.isEmpty() ? null : result.getFirst();
	}

	private String normalizeEmail(String email) {
		if (email == null) {
			return null;
		}
		String trimmed = email.trim();
		if (trimmed.isBlank()) {
			return null;
		}
		return trimmed.toLowerCase(Locale.ROOT);
	}
}
