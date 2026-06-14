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

@Entity
@Table(
    name = "mcp_server_applications",
    uniqueConstraints = {
        @UniqueConstraint(columnNames = { "mcp_server_id", "app_uri" })
    }
)
public class McpServerApplication extends BaseEntity {
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "mcp_server_id", nullable = false)
	public McpServer mcpServer;

	@Column(name = "app_uri", nullable = false)
	public String appUri;

	@Column(name = "app_name")
	public String appName;

	@Column(name = "display_name")
	public String displayName;

	@Column(name = "icon_svg", columnDefinition = "text")
	public String iconSvg;

	@Column(name = "disabled", nullable = false)
	public boolean disabled;
}
