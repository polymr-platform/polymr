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

package be.celerex.polymr.notifications;

import be.celerex.polymr.security.SecretStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@ApplicationScoped
public class VapidKeyService {
	private static final String PUBLIC_KEY = "vapid.default.public";
	private static final String PRIVATE_KEY = "vapid.default.private";
	@Inject
	SecretStore secretStore;

	@Transactional
	public VapidKeys loadKeys() {
		byte[] publicKey = secretStore.get(PUBLIC_KEY).orElse(null);
		byte[] privateKey = secretStore.get(PRIVATE_KEY).orElse(null);
		if (publicKey == null || privateKey == null) {
			KeyPair pair = generate();
			publicKey = encodePublicKey((ECPublicKey) pair.getPublic());
			privateKey = encodePrivateKey((ECPrivateKey) pair.getPrivate());
			secretStore.put(PUBLIC_KEY, publicKey);
			secretStore.put(PRIVATE_KEY, privateKey);
		}
		return new VapidKeys(new String(publicKey, StandardCharsets.UTF_8), new String(privateKey, StandardCharsets.UTF_8));
	}

	private KeyPair generate() {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
			generator.initialize(256);
			return generator.generateKeyPair();
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("Unable to generate VAPID keys", ex);
		}
	}

	private byte[] encodePublicKey(ECPublicKey publicKey) {
		byte[] x = publicKey.getW().getAffineX().toByteArray();
		byte[] y = publicKey.getW().getAffineY().toByteArray();
		byte[] x32 = toFixedLength(x, 32);
		byte[] y32 = toFixedLength(y, 32);
		byte[] raw = new byte[65];
		raw[0] = 0x04;
		System.arraycopy(x32, 0, raw, 1, 32);
		System.arraycopy(y32, 0, raw, 33, 32);
		return Base64.getUrlEncoder().withoutPadding().encode(raw);
	}

	private byte[] encodePrivateKey(ECPrivateKey privateKey) {
		byte[] s = privateKey.getS().toByteArray();
		byte[] fixed = toFixedLength(s, 32);
		return Base64.getUrlEncoder().withoutPadding().encode(fixed);
	}

	private byte[] toFixedLength(byte[] value, int length) {
		if (value.length == length) {
			return value;
		}
		byte[] output = new byte[length];
		int copyStart = Math.max(0, value.length - length);
		int copyLength = Math.min(value.length, length);
		System.arraycopy(value, copyStart, output, length - copyLength, copyLength);
		return output;
	}

	public record VapidKeys(String publicKey, String privateKey) {}
}
