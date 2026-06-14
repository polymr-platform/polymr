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
import be.celerex.polymr.model.TokenType;
import be.celerex.polymr.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class TokenService {
	private static final int SECRET_BYTES = 32;
	private static final Logger LOGGER = Logger.getLogger(TokenService.class);
	@Inject
	EntityManager entityManager;

	@Inject
	PasswordHasher passwordHasher;

	@ConfigProperty(name = "polymr.tokens.access-ttl", defaultValue = "PT15M")
	Duration accessTtl;

	@ConfigProperty(name = "polymr.tokens.refresh-ttl", defaultValue = "P30D")
	Duration refreshTtl;

	private volatile SecureRandom secureRandom;

	@Transactional
	public TokenIssue issueAccessToken(User user, String deviceId) {
		return issueToken(user, deviceId, TokenType.ACCESS, accessTtl);
	}

	@Transactional
	public TokenIssue issueRefreshToken(User user, String deviceId) {
		return issueToken(user, deviceId, TokenType.REFRESH, refreshTtl);
	}

	@ActivateRequestContext
	@Transactional(Transactional.TxType.SUPPORTS)
	public Optional<Token> verifyAccessToken(String tokenValue) {
		return verifyToken(tokenValue, TokenType.ACCESS, null);
	}

	@ActivateRequestContext
	@Transactional(Transactional.TxType.SUPPORTS)
	public Optional<Token> verifyRefreshToken(String tokenValue, String deviceId) {
		return verifyToken(tokenValue, TokenType.REFRESH, deviceId);
	}

	@Transactional
	public TokenPair refresh(Token refreshToken) {
		refreshToken.usedAt = Instant.now();
		TokenIssue access = issueAccessToken(refreshToken.user, refreshToken.deviceId);
		TokenIssue refresh = issueRefreshToken(refreshToken.user, refreshToken.deviceId);
		return new TokenPair(access, refresh);
	}

	@Transactional
	public void revokeToken(Token token) {
		token.revokedAt = Instant.now();
	}

	private TokenIssue issueToken(User user, String deviceId, TokenType type, Duration ttl) {
		String secret = generateSecret();
		Token token = new Token();
		token.user = user;
		token.type = type;
		token.deviceId = deviceId;
		token.secretHash = passwordHasher.hashSecret(secret);
		token.expiresAt = Instant.now().plus(ttl);
		entityManager.persist(token);
		String value = token.id + "." + secret;
		return new TokenIssue(value, token.expiresAt);
	}

	private Optional<Token> verifyToken(String tokenValue, TokenType expectedType, String deviceId) {
		TokenParts parts = parseToken(tokenValue);
		if (parts == null) {
			return Optional.empty();
		}
		Token token = entityManager.createQuery("select t from Token t join fetch t.user where t.id = :id", Token.class)
			.setParameter("id", parts.id)
			.getResultStream()
			.findFirst()
			.orElse(null);
		if (token == null) {
			return Optional.empty();
		}
		if (token.type != expectedType) {
			return Optional.empty();
		}
		if (token.revokedAt != null) {
			return Optional.empty();
		}
		if (token.expiresAt.isBefore(Instant.now())) {
			return Optional.empty();
		}
		if (expectedType == TokenType.REFRESH && token.usedAt != null) {
			return Optional.empty();
		}
		if (deviceId != null && !deviceId.equals(token.deviceId)) {
			return Optional.empty();
		}
		boolean ok = passwordHasher.verifySecret(token.secretHash, parts.secret);
		return ok ? Optional.of(token) : Optional.empty();
	}

	private String generateSecret() {
		byte[] bytes = new byte[SECRET_BYTES];
		getSecureRandom().nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private SecureRandom getSecureRandom() {
		SecureRandom current = secureRandom;
		if (current != null) {
			return current;
		}
		synchronized (this) {
			if (secureRandom == null) {
				secureRandom = new SecureRandom();
			}
			return secureRandom;
		}
	}

	private TokenParts parseToken(String tokenValue) {
		if (tokenValue == null || tokenValue.isBlank()) {
			return null;
		}
		String[] parts = tokenValue.split("\\.", 2);
		if (parts.length != 2) {
			return null;
		}
		try {
			UUID id = UUID.fromString(parts[0]);
			if (parts[1].isBlank()) {
				return null;
			}
			return new TokenParts(id, parts[1]);
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	public record TokenIssue(String value, Instant expiresAt) {}

	public record TokenPair(TokenIssue access, TokenIssue refresh) {}

	private record TokenParts(UUID id, String secret) {}
}
