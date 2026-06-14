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

package be.celerex.polymr.recording;

import be.celerex.polymr.model.RecordingStatus;
import java.time.Instant;
import java.util.UUID;

public record RecordingDetailResponse(
		UUID id,
		String title,
		RecordingStatus status,
		String transcript_text,
		String optimized_text,
		String summary_text,
		String error_message,
		String audio_mime_type,
		Long audio_size_bytes,
		Integer duration_seconds,
		Instant started_at,
		Instant created_at,
		Instant updated_at) {}
