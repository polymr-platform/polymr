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
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "assistants")
public class Assistant extends BaseEntity {
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "tenant_id", nullable = false)
	public Tenant tenant;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "workspace_id")
	public Workspace workspace;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "persona_id")
	public Persona persona;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "model_id")
	public AiModel model;

	@Column(name = "name", nullable = false)
	public String name;

	@Column(name = "description")
	public String description;

	@Column(name = "prompt_text", columnDefinition = "text")
	public String promptText;

	@Column(name = "max_output_tokens")
	public Integer maxOutputTokens;

	@Column(name = "max_turns")
	public Integer maxTurns;

	@Column(name = "worker_enabled", nullable = false)
	public boolean workerEnabled = false;

	@Column(name = "worker_trigger")
	public String workerTrigger;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "worker_allow_scopes")
	public List<String> workerAllowScopes;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "worker_deny_scopes")
	public List<String> workerDenyScopes;
}
