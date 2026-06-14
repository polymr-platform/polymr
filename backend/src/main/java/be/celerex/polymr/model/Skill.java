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
@Table(name = "skills", uniqueConstraints = {
		@UniqueConstraint(columnNames = {"tenant_id", "workspace_id", "name"})
})
public class Skill extends BaseEntity {
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "tenant_id", nullable = false)
	public Tenant tenant;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "workspace_id")
	public Workspace workspace;

	@Column(name = "name", nullable = false)
	public String name;

	@Column(name = "trigger", nullable = false)
	public String trigger;

	@Column(name = "description", nullable = false)
	public String description;

	@Column(name = "always_included", nullable = false)
	public boolean alwaysIncluded;

	@Column(name = "prompt_text", columnDefinition = "text")
	public String promptText;
}
