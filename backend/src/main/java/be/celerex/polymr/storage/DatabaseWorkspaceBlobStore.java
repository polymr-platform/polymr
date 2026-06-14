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

import be.celerex.polymr.model.Workspace;
import be.celerex.polymr.model.WorkspaceAssetBlob;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.net.URI;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import io.quarkus.runtime.Startup;

@ApplicationScoped
@Startup
public class DatabaseWorkspaceBlobStore implements PublicWorkspaceBlobStore {
	@Inject
	EntityManager entityManager;

	@Inject
	PublicAssetTokenService tokenService;

	@ConfigProperty(name = "polymr.public.assets.base-url")
	Optional<String> publicBaseUrl;

	@ConfigProperty(name = "polymr.blob.cache.max-bytes", defaultValue = "104857600")
	long cacheMaxBytes;

	private final Map<String, CachedBlob> cache = new LinkedHashMap<>(256, 0.75f, true);
	private long cacheBytes = 0;

	@Override
	@Transactional
	public StoredBlob store(UUID workspaceId, byte[] bytes, String mimeType) {
		if (bytes == null) {
			throw new IllegalArgumentException("Blob bytes are required");
		}
		String hash = sha256(bytes);
		WorkspaceAssetBlob existing = findBlob(workspaceId, hash);
		if (existing != null) {
			return new StoredBlob(existing.hash, existing.mimeType, existing.byteSize, existing.bytes);
		}
		WorkspaceAssetBlob blob = new WorkspaceAssetBlob();
		blob.workspace = entityManager.getReference(Workspace.class, workspaceId);
		blob.hash = hash;
		blob.mimeType = mimeType;
		blob.byteSize = bytes.length;
		blob.bytes = bytes;
		entityManager.persist(blob);
		StoredBlob stored = new StoredBlob(blob.hash, blob.mimeType, blob.byteSize, blob.bytes);
		putCache(workspaceId, stored);
		return stored;
	}

	@Override
	@Transactional
	public Optional<StoredBlob> load(UUID workspaceId, String hash) {
		StoredBlob cached = getCache(workspaceId, hash);
		if (cached != null) {
			return Optional.of(cached);
		}
		WorkspaceAssetBlob blob = findBlob(workspaceId, hash);
		if (blob == null) {
			return Optional.empty();
		}
		StoredBlob stored = new StoredBlob(blob.hash, blob.mimeType, blob.byteSize, blob.bytes);
		putCache(workspaceId, stored);
		return Optional.of(stored);
	}

	@Override
	@Transactional
	public void delete(UUID workspaceId, String hash) {
		if (workspaceId == null || hash == null || hash.isBlank()) {
			return;
		}
		WorkspaceAssetBlob blob = findBlob(workspaceId, hash);
		if (blob == null) {
			return;
		}
		entityManager.remove(blob);
		synchronized (cache) {
			String key = cacheKey(workspaceId, hash);
			CachedBlob cached = cache.remove(key);
			if (cached != null) {
				cacheBytes -= cached.byteSize;
			}
		}
	}

	@Override
	public Optional<PublicBlobLink> createPublicLink(UUID workspaceId, String hash, Duration ttl) {
		if (workspaceId == null || hash == null || hash.isBlank()) {
			return Optional.empty();
		}
		if (publicBaseUrl == null || publicBaseUrl.isEmpty() || publicBaseUrl.get().isBlank()) {
			return Optional.empty();
		}
		Instant expiresAt = Instant.now().plus(ttl == null ? Duration.ofHours(6) : ttl);
		String token = tokenService.createToken(workspaceId, hash, expiresAt);
		String baseValue = publicBaseUrl.get();
		String base = baseValue.endsWith("/") ? baseValue.substring(0, baseValue.length() - 1) : baseValue;
		URI uri = URI.create(base + "/api/public/assets/" + token);
		return Optional.of(new PublicBlobLink(uri, expiresAt));
	}

	private WorkspaceAssetBlob findBlob(UUID workspaceId, String hash) {
		if (workspaceId == null || hash == null) {
			return null;
		}
		return entityManager.createQuery(
				"select b from WorkspaceAssetBlob b where b.workspace.id = :workspaceId and b.hash = :hash",
				WorkspaceAssetBlob.class
			)
			.setParameter("workspaceId", workspaceId)
			.setParameter("hash", hash)
			.setMaxResults(1)
			.getResultStream()
			.findFirst()
			.orElse(null);
	}

	private String sha256(byte[] bytes) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(bytes));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("Missing SHA-256 support", ex);
		}
	}

	private String cacheKey(UUID workspaceId, String hash) {
		return workspaceId + ":" + hash;
	}

	private StoredBlob getCache(UUID workspaceId, String hash) {
		if (workspaceId == null || hash == null || hash.isBlank()) {
			return null;
		}
		synchronized (cache) {
			CachedBlob cached = cache.get(cacheKey(workspaceId, hash));
			if (cached == null) {
				return null;
			}
			return new StoredBlob(cached.hash, cached.mimeType, cached.byteSize, cached.bytes);
		}
	}

	private void putCache(UUID workspaceId, StoredBlob blob) {
		if (workspaceId == null || blob == null || blob.bytes() == null) {
			return;
		}
		synchronized (cache) {
			String key = cacheKey(workspaceId, blob.hash());
			CachedBlob existing = cache.get(key);
			if (existing != null) {
				cacheBytes -= existing.byteSize;
			}
			CachedBlob cached = new CachedBlob(blob.hash(), blob.mimeType(), blob.byteSize(), blob.bytes());
			cache.put(key, cached);
			cacheBytes += cached.byteSize;
			trimCache();
		}
	}

	private void trimCache() {
		if (cacheMaxBytes <= 0) {
			return;
		}
		while (cacheBytes > cacheMaxBytes && !cache.isEmpty()) {
			Map.Entry<String, CachedBlob> entry = cache.entrySet().iterator().next();
			cacheBytes -= entry.getValue().byteSize;
			cache.remove(entry.getKey());
		}
	}

	private record CachedBlob(String hash, String mimeType, long byteSize, byte[] bytes) {}
}
