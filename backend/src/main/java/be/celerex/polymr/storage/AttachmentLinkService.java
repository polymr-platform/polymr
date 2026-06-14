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
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class AttachmentLinkService {
	@Inject
	PublicBlobLinkCache linkCache;

	@Inject
	UserBlobLinkCache userLinkCache;

	@Inject
	PublicAssetTokenService tokenService;

	@ConfigProperty(name = "polymr.public.assets.ttl", defaultValue = "12h")
	Duration publicAssetTtl;

	public Optional<PublicBlobLink> resolvePublicLink(UUID workspaceId, String hash, PublicWorkspaceBlobStore store) {
		if (workspaceId == null || hash == null || hash.isBlank() || store == null) {
			return Optional.empty();
		}
		Optional<PublicBlobLink> cached = linkCache.get(workspaceId, hash);
		if (cached.isPresent()) {
			return cached;
		}
		Duration ttl = publicAssetTtl == null ? Duration.ofHours(12) : publicAssetTtl;
		Optional<PublicBlobLink> created = store.createPublicLink(workspaceId, hash, ttl);
		created.ifPresent(link -> linkCache.put(workspaceId, hash, link));
		return created;
	}

	public Optional<PublicBlobLink> resolveUserLink(
			UUID tenantId,
			UUID workspaceId,
			String hash,
			PublicWorkspaceBlobStore store) {
		if (tenantId == null || workspaceId == null || hash == null || hash.isBlank()) {
			return Optional.empty();
		}
		Optional<PublicBlobLink> cached = userLinkCache.get(workspaceId, hash);
		if (cached.isPresent()) {
			return cached;
		}
		if (store != null) {
			Optional<PublicBlobLink> created = resolvePublicLink(workspaceId, hash, store);
			if (created.isPresent()) {
				return created;
			}
		}
		Duration ttl = publicAssetTtl == null ? Duration.ofHours(12) : publicAssetTtl;
		Instant expiresAt = Instant.now().plus(ttl);
		String token = tokenService.createToken(workspaceId, hash, expiresAt);
		URI uri = URI.create("/api/tenants/" + tenantId + "/workspaces/" + workspaceId + "/attachments/" + token);
		PublicBlobLink link = new PublicBlobLink(uri, expiresAt);
		userLinkCache.put(workspaceId, hash, link);
		return Optional.of(link);
	}
}
