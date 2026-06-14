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

package be.celerex.polymr.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "recordings")
public class Recording extends BaseEntity {
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "tenant_id", nullable = false)
	public Tenant tenant;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "workspace_id", nullable = false)
	public Workspace workspace;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "created_by_user_id")
	public User createdBy;

	@Column(name = "title", nullable = false)
	public String title;

	@Column(name = "audio_hash", nullable = false)
	public String audioHash;

	@Column(name = "audio_mime_type")
	public String audioMimeType;

	@Column(name = "audio_size_bytes")
	public Long audioSizeBytes;

	@Column(name = "duration_seconds")
	public Integer durationSeconds;

	@Column(name = "started_at")
	public java.time.Instant startedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	public RecordingStatus status = RecordingStatus.UPLOADED;

	@Column(name = "error_message", length = 2048)
	public String errorMessage;

	@Column(name = "transcript_text", columnDefinition = "text")
	public String transcriptText;

	@Column(name = "optimized_text", columnDefinition = "text")
	public String optimizedText;

	@Column(name = "summary_text", columnDefinition = "text")
	public String summaryText;
}
