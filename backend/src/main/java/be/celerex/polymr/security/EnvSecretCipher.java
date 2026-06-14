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

import jakarta.enterprise.context.ApplicationScoped;
import java.security.SecureRandom;
import org.eclipse.microprofile.config.ConfigProvider;
import javax.crypto.spec.SecretKeySpec;

@ApplicationScoped
public class EnvSecretCipher implements SecretCipher {
	private static final int NONCE_BYTES = 12;
	private static final int TAG_BITS = 128;
	private final SecretKeySpec keySpec;
	private final SecureRandom secureRandom = new SecureRandom();

	public EnvSecretCipher() {
		this.keySpec = initKey(loadKey());
	}

	@Override
	public EncryptedSecret encrypt(String plaintext) {
		return SecretCrypto.encrypt(plaintext, keySpec, secureRandom);
	}

	@Override
	public String decrypt(EncryptedSecret secret) {
		return SecretCrypto.decrypt(secret, keySpec);
	}

	private String loadKey() {
		try {
			return ConfigProvider.getConfig().getOptionalValue("polymr.secrets.key", String.class).orElse(null);
		}
		catch (IllegalStateException ex) {
			return null;
		}
	}

	private SecretKeySpec initKey(String base64Key) {
		return SecretCrypto.initKey(base64Key);
	}
}
