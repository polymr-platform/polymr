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

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.util.Optional;

@Path("/api/public/assets")
public class PublicAssetResource {
	@Inject
	PublicAssetTokenService tokenService;

	@Inject
	WorkspaceBlobStore blobStore;

	@GET
	@Path("/{token}")
	@Produces("*/*")
	public Response load(@PathParam("token") String token) {
		Optional<PublicAssetTokenService.PublicAssetToken> parsed = tokenService.verify(token);
		if (parsed.isEmpty()) {
			throw new WebApplicationException("Invalid token", Response.Status.NOT_FOUND);
		}
		PublicAssetTokenService.PublicAssetToken asset = parsed.get();
		StoredBlob blob = blobStore.load(asset.workspaceId(), asset.hash())
			.orElseThrow(() -> new WebApplicationException("Asset not found", Response.Status.NOT_FOUND));
		String mimeType = blob.mimeType() == null || blob.mimeType().isBlank() ? "application/octet-stream" : blob.mimeType();
		return Response.ok(blob.bytes()).type(mimeType).build();
	}
}
