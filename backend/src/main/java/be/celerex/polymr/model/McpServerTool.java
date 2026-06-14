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
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
    name = "mcp_server_tools",
    uniqueConstraints = {
        @UniqueConstraint(columnNames = { "mcp_server_id", "tool_name" })
    }
)
public class McpServerTool extends BaseEntity {
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "mcp_server_id", nullable = false)
	public McpServer mcpServer;

	@Column(name = "tool_name", nullable = false, columnDefinition = "text")
	public String toolName;

	@Column(name = "tool_alias", columnDefinition = "text")
	public String toolAlias;

	@Column(name = "description", columnDefinition = "text")
	public String description;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "scopes")
	public JsonNode scopes;

	@Column(name = "dynamic_scopes")
	public boolean dynamicScopes;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "custom_scopes")
	public JsonNode customScopes;

	@Column(name = "intent_template", columnDefinition = "text")
	public String intentTemplate;

	@Column(name = "input_template", columnDefinition = "text")
	public String inputTemplate;

	@Column(name = "output_template", columnDefinition = "text")
	public String outputTemplate;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "input_schema")
	public JsonNode inputSchema;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "output_schema")
	public JsonNode outputSchema;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "meta")
	public JsonNode meta;

	@Column(name = "preview_supported", nullable = false)
	public boolean previewSupported;

	@Column(name = "disabled", nullable = false)
	public boolean disabled;

	@Column(name = "deleted", nullable = false)
	public boolean deleted;
}
