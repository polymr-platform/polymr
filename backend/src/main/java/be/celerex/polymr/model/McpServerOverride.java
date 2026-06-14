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
import jakarta.persistence.Index;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
    name = "mcp_server_overrides",
    uniqueConstraints = {
        @UniqueConstraint(columnNames = {"mcp_server_id", "tag_id"})
    },
    indexes = {
        @Index(name = "idx_mcp_server_overrides_server", columnList = "mcp_server_id"),
        @Index(name = "idx_mcp_server_overrides_tag", columnList = "tag_id"),
        @Index(name = "idx_mcp_server_overrides_oauth_provider", columnList = "oauth_provider_id")
    }
)
public class McpServerOverride extends BaseEntity {
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "mcp_server_id", nullable = false)
	public McpServer mcpServer;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "tag_id", nullable = false)
	public TagValue tag;

	@Column(name = "logical_name", nullable = false)
	public String logicalName;

	@Column(name = "http_url")
	public String httpUrl;

	@Column(name = "headers")
	public String headers;

	@Column(name = "ssh_enabled")
	public Boolean sshEnabled;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "ssh_tunnel")
	public JsonNode sshTunnel;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "oauth_provider_id")
	public McpOauthProvider oauthProvider;
}
