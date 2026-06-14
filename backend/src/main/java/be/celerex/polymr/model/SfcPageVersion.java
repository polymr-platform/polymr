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
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sfc_page_versions")
public class SfcPageVersion extends BaseEntity {
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "page_id", nullable = false)
	public SfcPage page;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "created_by_user_id")
	public User createdBy;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "released_by_user_id")
	public User releasedBy;

	@Column(name = "source_sfc", columnDefinition = "text")
	public String sourceSfc;

	@Column(name = "compiled_bundle", columnDefinition = "text")
	public String compiledBundle;

	@Column(name = "compile_errors", columnDefinition = "text")
	public String compileErrors;

	@Column(name = "version", nullable = false)
	public int version;

	@Column(name = "released_at")
	public Instant releasedAt;

	@Column(name = "deprecated_at")
	public Instant deprecatedAt;

	@Column(name = "design_session_id")
	public UUID designSessionId;
}
