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
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "workflow_runs", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"session_id"})
})
public class WorkflowRun extends BaseEntity {
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "session_id", nullable = false)
	public Session session;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "workflow_definition_id", nullable = false)
	public WorkflowDefinition workflowDefinition;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "workflow_definition_version_id")
	public WorkflowDefinitionVersion workflowDefinitionVersion;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	public WorkflowRunStatus status = WorkflowRunStatus.QUEUED;

	@Column(name = "current_node")
	public String currentNode;

	@Column(name = "runtime_server_id")
	public String runtimeServerId;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "checkpoint_json")
	public JsonNode checkpointJson;
}
