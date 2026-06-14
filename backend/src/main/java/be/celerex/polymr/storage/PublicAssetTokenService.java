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

package be.celerex.polymr.storage;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class PublicAssetTokenService {
	private static final String HMAC_ALGO = "HmacSHA256";
	private final byte[] secret;

	@Inject
	public PublicAssetTokenService(
			@ConfigProperty(name = "polymr.public.assets.secret", defaultValue = "${polymr.secrets.key:changeit}")
            String secretValue) {
		this.secret = secretValue.getBytes(StandardCharsets.UTF_8);
	}

	public String createToken(UUID workspaceId, String hash, Instant expiresAt) {
		String payload = workspaceId + ":" + hash + ":" + expiresAt.getEpochSecond();
		String encodedPayload = base64Url(payload.getBytes(StandardCharsets.UTF_8));
		String signature = base64Url(hmac(payload));
		return encodedPayload + "." + signature;
	}

	public Optional<PublicAssetToken> verify(String token) {
		if (token == null || token.isBlank()) {
			return Optional.empty();
		}
		String[] parts = token.split("\\.", 2);
		if (parts.length != 2) {
			return Optional.empty();
		}
		byte[] payloadBytes = base64UrlDecode(parts[0]);
		if (payloadBytes == null) {
			return Optional.empty();
		}
		String payload = new String(payloadBytes, StandardCharsets.UTF_8);
		String expectedSignature = base64Url(hmac(payload));
		if (!constantTimeEquals(expectedSignature, parts[1])) {
			return Optional.empty();
		}
		String[] payloadParts = payload.split(":", 3);
		if (payloadParts.length != 3) {
			return Optional.empty();
		}
		UUID workspaceId;
		try {
			workspaceId = UUID.fromString(payloadParts[0]);
		}
		catch (IllegalArgumentException ex) {
			return Optional.empty();
		}
		String hash = payloadParts[1];
		long expiresAt;
		try {
			expiresAt = Long.parseLong(payloadParts[2]);
		}
		catch (NumberFormatException ex) {
			return Optional.empty();
		}
		if (Instant.now().isAfter(Instant.ofEpochSecond(expiresAt))) {
			return Optional.empty();
		}
		return Optional.of(new PublicAssetToken(workspaceId, hash, Instant.ofEpochSecond(expiresAt)));
	}

	private byte[] hmac(String payload) {
		try {
			Mac mac = Mac.getInstance(HMAC_ALGO);
			mac.init(new SecretKeySpec(secret, HMAC_ALGO));
			return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
		}
		catch (Exception ex) {
			throw new IllegalStateException("Unable to sign token", ex);
		}
	}

	private String base64Url(byte[] bytes) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private byte[] base64UrlDecode(String value) {
		try {
			return Base64.getUrlDecoder().decode(value);
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private boolean constantTimeEquals(String a, String b) {
		if (a == null || b == null || a.length() != b.length()) {
			return false;
		}
		int result = 0;
		for (int i = 0; i < a.length(); i++) {
			result |= a.charAt(i) ^ b.charAt(i);
		}
		return result == 0;
	}

	public record PublicAssetToken(UUID workspaceId, String hash, Instant expiresAt) {}
}
