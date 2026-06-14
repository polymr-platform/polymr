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

import be.celerex.polymr.cluster.ClusterMapService;
import be.celerex.polymr.cluster.ClusteredMap;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.quarkus.scheduler.Scheduled;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class UserBlobLinkCache {
	private static final String MAP_NAME = "user-blob-links";
	private final ClusteredMap<String, PublicBlobLinkEntry> cache;

	@Inject
	public UserBlobLinkCache(ClusterMapService clusterMapService) {
		this.cache = clusterMapService.getMap(MAP_NAME, String.class, PublicBlobLinkEntry.class);
	}

	public Optional<PublicBlobLink> get(UUID workspaceId, String hash) {
		if (workspaceId == null || hash == null || hash.isBlank()) {
			return Optional.empty();
		}
		String key = workspaceId + ":" + hash;
		PublicBlobLinkEntry existing = cache.get(key);
		if (existing == null) {
			return Optional.empty();
		}
		if (existing.expiresAt().isBefore(Instant.now())) {
			cache.remove(key);
			return Optional.empty();
		}
		return Optional.of(new PublicBlobLink(existing.uri(), existing.expiresAt()));
	}

	public void put(UUID workspaceId, String hash, PublicBlobLink link) {
		if (workspaceId == null || hash == null || hash.isBlank() || link == null) {
			return;
		}
		String key = workspaceId + ":" + hash;
		cache.put(key, new PublicBlobLinkEntry(link.uri(), link.expiresAt()));
	}

	@Scheduled(every = "5m")
	void reapExpired() {
		if (!cache.tryLock()) {
			return;
		}
		try {
			Instant now = Instant.now();
			for (Map.Entry<String, PublicBlobLinkEntry> entry : cache.entrySet()) {
				if (entry.getValue() == null || !entry.getValue().expiresAt().isAfter(now)) {
					cache.remove(entry.getKey());
				}
			}
		}
		finally {
			cache.unlock();
		}
	}

	public record PublicBlobLinkEntry(java.net.URI uri, java.time.Instant expiresAt) {}
}
