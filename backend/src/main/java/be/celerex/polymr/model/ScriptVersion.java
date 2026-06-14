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

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "script_versions")
public class ScriptVersion extends BaseEntity {
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "script_id", nullable = false)
	public Script script;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "created_by_user_id")
	public User createdBy;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "released_by_user_id")
	public User releasedBy;

	@Column(name = "source_groovy", columnDefinition = "text")
	public String sourceGroovy;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "input_schema")
	public JsonNode inputSchema;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "output_schema")
	public JsonNode outputSchema;

	@Column(name = "version", nullable = false)
	public int version;

	@Column(name = "released_at")
	public Instant releasedAt;

	@Column(name = "deprecated_at")
	public Instant deprecatedAt;

	@Column(name = "design_session_id")
	public UUID designSessionId;
}
