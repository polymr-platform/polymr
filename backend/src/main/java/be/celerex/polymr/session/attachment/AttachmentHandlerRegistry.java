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

package be.celerex.polymr.session.attachment;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Optional;

@ApplicationScoped
public class AttachmentHandlerRegistry {
	@Inject
	Instance<AttachmentHandler> handlers;

	public boolean hasHandler(String mimeType) {
		if (mimeType == null || mimeType.isBlank()) {
			return false;
		}
		for (AttachmentHandler handler : handlers) {
			if (handler != null && handler.supports(mimeType)) {
				return true;
			}
		}
		return false;
	}

	public Optional<AttachmentHandlerResult> handle(AttachmentPayload payload) {
		if (payload == null || payload.mimeType() == null || payload.mimeType().isBlank()) {
			return Optional.empty();
		}
		for (AttachmentHandler handler : handlers) {
			if (handler != null && handler.supports(payload.mimeType())) {
				return Optional.ofNullable(handler.handle(payload));
			}
		}
		return Optional.empty();
	}
}
