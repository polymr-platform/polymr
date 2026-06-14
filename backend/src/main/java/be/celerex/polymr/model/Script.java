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
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "scripts", uniqueConstraints = @UniqueConstraint(columnNames = { "workspace_id", "slug" }))
public class Script extends BaseEntity {
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "workspace_id", nullable = false)
	public Workspace workspace;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "workflow_definition_id")
	public WorkflowDefinition workflowDefinition;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "created_by_user_id")
	public User createdBy;

	@Column(name = "name", nullable = false)
	public String name;

	@Column(name = "description")
	public String description;

	@Column(name = "namespace")
	public String namespace;

	@Column(name = "slug", nullable = false)
	public String slug;

	@Column(name = "disabled", nullable = false)
	public boolean disabled = false;

	@Enumerated(EnumType.STRING)
	@Column(name = "script_type", nullable = false)
	public ScriptType type = ScriptType.STANDALONE;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "active_version_id")
	public ScriptVersion activeVersion;

	@Column(name = "scheduled", nullable = false)
	public boolean scheduled = false;

	@Column(name = "schedule_rrule")
	public String scheduleRrule;

	@Column(name = "schedule_timezone")
	public String scheduleTimezone;

	@Column(name = "schedule_start_at")
	public Instant scheduleStartAt;

	@Column(name = "schedule_end_at")
	public Instant scheduleEndAt;

	@Column(name = "next_run_at")
	public Instant nextRunAt;

	@Column(name = "last_run_at")
	public Instant lastRunAt;

	@Column(name = "tool_hook_enabled", nullable = false)
	public boolean toolHookEnabled = false;

	@Enumerated(EnumType.STRING)
	@Column(name = "tool_hook_phase")
	public ScriptToolHookPhase toolHookPhase;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "tool_hook_tool_names")
	public List<String> toolHookToolNames;
}
