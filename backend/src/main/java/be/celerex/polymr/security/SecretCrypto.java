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

package be.celerex.polymr.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class SecretCrypto {
	private static final int NONCE_BYTES = 12;
	private static final int TAG_BITS = 128;

	private SecretCrypto() {}

	public static SecretKeySpec initKey(String keyValue) {
		if (keyValue == null || keyValue.isBlank()) {
			return null;
		}
		byte[] key = tryDecodeBase64(keyValue);
		if (key != null && key.length == 32) {
			return new SecretKeySpec(key, "AES");
		}
		return new SecretKeySpec(hashKey(keyValue), "AES");
	}

	public static SecretCipher.EncryptedSecret encrypt(String plaintext, SecretKeySpec keySpec, SecureRandom secureRandom) {
		try {
			if (keySpec == null) {
				throw new IllegalStateException("Missing polymr.secrets.key");
			}
			byte[] nonce = new byte[NONCE_BYTES];
			secureRandom.nextBytes(nonce);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, nonce));
			byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
			return new SecretCipher.EncryptedSecret(Base64.getEncoder().encodeToString(ciphertext), Base64.getEncoder().encodeToString(nonce));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("Failed to encrypt secret", ex);
		}
	}

	public static String decrypt(SecretCipher.EncryptedSecret secret, SecretKeySpec keySpec) {
		try {
			if (keySpec == null) {
				throw new IllegalStateException("Missing polymr.secrets.key");
			}
			byte[] nonce = Base64.getDecoder().decode(secret.nonce());
			byte[] ciphertext = Base64.getDecoder().decode(secret.ciphertext());
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, nonce));
			byte[] plaintext = cipher.doFinal(ciphertext);
			return new String(plaintext, StandardCharsets.UTF_8);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("Failed to decrypt secret", ex);
		}
	}

	private static byte[] tryDecodeBase64(String value) {
		try {
			return Base64.getDecoder().decode(value);
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private static byte[] hashKey(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return digest.digest(value.getBytes(StandardCharsets.UTF_8));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("Missing SHA-256 support", ex);
		}
	}
}
