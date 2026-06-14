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
import com.fasterxml.jackson.databind.JsonNode;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "mcp_servers")
public class McpServer extends BaseEntity {
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "workspace_id", nullable = false)
	public Workspace workspace;

	@Column(name = "name", nullable = false)
	public String name;

	@Column(name = "description")
	public String description;

	@Enumerated(EnumType.STRING)
	@Column(name = "protocol", nullable = false)
	public McpProtocol protocol;

	@Column(name = "framing")
	public String framing;

	@Column(name = "command")
	public String command;

	@Column(name = "cwd")
	public String cwd;

	@Column(name = "http_url")
	public String httpUrl;

	@Column(name = "virtual_type")
	public String virtualType;

	@Column(name = "headers")
	public String headers;

	@Column(name = "environment")
	public String environment;

	@Column(name = "supports_dynamic_config", nullable = false)
	public boolean supportsDynamicConfig;

	@Column(name = "allow_policy", nullable = false)
	public boolean allowPolicy;

	@Enumerated(EnumType.STRING)
	@Column(name = "visibility", nullable = false)
	public McpServerVisibility visibility = McpServerVisibility.VISIBLE;

	@Column(name = "internal", nullable = false, columnDefinition = "boolean default false")
	public boolean internal;

	@Column(name = "instructions", columnDefinition = "text")
	public String instructions;

	@Column(name = "prompt", columnDefinition = "text")
	public String prompt;

	@Column(name = "tool_name_prefix", columnDefinition = "text")
	public String toolNamePrefix;

	@Column(name = "custom_instructions", nullable = false, columnDefinition = "boolean default false")
	public boolean customInstructions;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "config_schema")
	public JsonNode configSchema;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "configuration_json")
	public JsonNode configurationJson;

	@Column(name = "oauth_enabled", nullable = false)
	public boolean oauthEnabled;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "oauth_provider_id")
	public McpOauthProvider oauthProvider;

	@Column(name = "tools_hash")
	public String toolsHash;

	@Column(name = "ssh_enabled", nullable = false, columnDefinition = "boolean default false")
	public boolean sshEnabled;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "ssh_tunnel")
	public JsonNode sshTunnel;
}
