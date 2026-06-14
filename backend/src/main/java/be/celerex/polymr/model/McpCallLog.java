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
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(
    name = "mcp_call_logs",
    indexes = {
        @Index(name = "idx_mcp_call_logs_override_name", columnList = "mcp_server_override_name"),
        @Index(name = "idx_mcp_call_logs_override_tag", columnList = "mcp_server_override_tag_name")
    }
)
public class McpCallLog extends BaseEntity {
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "tenant_id", nullable = false)
	public Tenant tenant;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "workspace_id", nullable = false)
	public Workspace workspace;

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "mcp_server_id")
	public McpServer mcpServer;

	@Column(name = "mcp_server_name")
	public String mcpServerName;

	@Column(name = "mcp_server_protocol")
	public String mcpServerProtocol;

	@Column(name = "mcp_server_override_name")
	public String mcpServerOverrideName;

	@Column(name = "mcp_server_override_tag_name")
	public String mcpServerOverrideTagName;

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "session_id", foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
	public Session session;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id")
	public User user;

	@Column(name = "connection_id", nullable = false)
	public UUID connectionId;

	@Column(name = "request_id")
	public Integer requestId;

	@Column(name = "method")
	public String method;

	@Column(name = "protocol")
	public String protocol;

	@Column(name = "input", columnDefinition = "text")
	public String input;

	@Column(name = "output", columnDefinition = "text")
	public String output;

	@Column(name = "status")
	public String status;

	@Column(name = "script_call_id")
	public java.util.UUID scriptCallId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "script_id")
	public Script script;
}
