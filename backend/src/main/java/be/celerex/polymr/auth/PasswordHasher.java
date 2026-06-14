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

import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;
import jakarta.enterprise.context.ApplicationScoped;
import java.security.SecureRandom;
import java.util.Base64;

@ApplicationScoped
public class PasswordHasher {
	private static final int ITERATIONS = 3;
	private static final int MEMORY = 65536;
	private static final int PARALLELISM = 1;
	private static final int SALT_BYTES = 16;
	private volatile SecureRandom secureRandom;

	public HashedPassword hash(char[] password) {
		byte[] salt = new byte[SALT_BYTES];
		getSecureRandom().nextBytes(salt);
		Argon2 argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id);
		String encodedSalt = Base64.getEncoder().encodeToString(salt);
		String combined = encodedSalt + ":" + new String(password);
		String hash = argon2.hash(ITERATIONS, MEMORY, PARALLELISM, combined.toCharArray());
		argon2.wipeArray(password);
		return new HashedPassword(hash, encodedSalt);
	}

	public String hashSecret(String secret) {
		Argon2 argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id);
		char[] chars = secret.toCharArray();
		String hash = argon2.hash(ITERATIONS, MEMORY, PARALLELISM, chars);
		argon2.wipeArray(chars);
		return hash;
	}

	public boolean verifySecret(String hash, String secret) {
		Argon2 argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id);
		char[] chars = secret.toCharArray();
		boolean ok = argon2.verify(hash, chars);
		argon2.wipeArray(chars);
		return ok;
	}

	public boolean verify(String hash, char[] password, String salt) {
		Argon2 argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id);
		String combined = salt + ":" + new String(password);
		boolean ok = argon2.verify(hash, combined.toCharArray());
		argon2.wipeArray(password);
		return ok;
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

	public record HashedPassword(String hash, String salt) {}
}
