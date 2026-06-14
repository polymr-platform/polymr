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

import be.celerex.polymr.model.SecretStoreEntry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.util.Base64;
import java.util.Optional;
import java.util.function.Supplier;

@ApplicationScoped
public class DatabaseSecretStore implements SecretStore {
	@Inject
	EntityManager entityManager;

	@Inject
	SecretCipher cipher;

	@Override
	public Optional<byte[]> get(String key) {
		if (key == null || key.isBlank()) {
			return Optional.empty();
		}
		SecretStoreEntry entry = findByKey(key);
		if (entry == null) {
			return Optional.empty();
		}
		String plaintext = cipher.decrypt(new SecretCipher.EncryptedSecret(entry.ciphertext, entry.nonce));
		if (plaintext == null) {
			return Optional.empty();
		}
		byte[] decoded = Base64.getDecoder().decode(plaintext);
		return Optional.of(decoded);
	}

	@Override
	@Transactional
	public byte[] getOrCreate(String key, Supplier<byte[]> generator) {
		Optional<byte[]> existing = get(key);
		if (existing.isPresent()) {
			return existing.get();
		}
		byte[] generated = generator.get();
		put(key, generated);
		return get(key).orElse(generated);
	}

	@Override
	@Transactional
	public void put(String key, byte[] value) {
		if (key == null || key.isBlank() || value == null) {
			return;
		}
		SecretStoreEntry entry = findByKey(key);
		String encoded = Base64.getEncoder().encodeToString(value);
		SecretCipher.EncryptedSecret encrypted = cipher.encrypt(encoded);
		if (entry == null) {
			entry = new SecretStoreEntry();
			entry.key = key;
			entry.ciphertext = encrypted.ciphertext();
			entry.nonce = encrypted.nonce();
			entityManager.persist(entry);
			return;
		}
		entry.ciphertext = encrypted.ciphertext();
		entry.nonce = encrypted.nonce();
	}

	private SecretStoreEntry findByKey(String key) {
		return entityManager.createQuery("select s from SecretStoreEntry s where s.key = :key", SecretStoreEntry.class)
			.setParameter("key", key)
			.getResultStream()
			.findFirst()
			.orElse(null);
	}
}
