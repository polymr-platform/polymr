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
@Table(name = "sfc_pages", uniqueConstraints = @UniqueConstraint(columnNames = { "workspace_id", "slug" }))
public class SfcPage extends BaseEntity {
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "workspace_id", nullable = false)
	public Workspace workspace;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "created_by_user_id")
	public User createdBy;

	@Column(name = "name", nullable = false)
	public String name;

	@Column(name = "description")
	public String description;

	@Column(name = "label")
	public String label;

	@Column(name = "namespace")
	public String namespace;

	@Column(name = "slug", nullable = false)
	public String slug;

	@Enumerated(EnumType.STRING)
	@Column(name = "page_type", nullable = false)
	public SfcPageType type = SfcPageType.PAGE;

	@Column(name = "menu_visible", nullable = false)
	public boolean menuVisible = true;

	@Column(name = "disabled", nullable = false)
	public boolean disabled = false;

	@Column(name = "icon_svg", columnDefinition = "text")
	public String iconSvg;

	@Column(name = "route_suffix")
	public String routeSuffix;

	@Column(name = "usage_guide", columnDefinition = "text")
	public String usageGuide;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "import_allowlist")
	public JsonNode importAllowlist;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "query_params")
	public JsonNode queryParams;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "input_params")
	public JsonNode inputParams;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "active_version_id")
	public SfcPageVersion activeVersion;
}
