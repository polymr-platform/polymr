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
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "prompt_templates")
public class PromptTemplate {
	@Id
	@GeneratedValue
	public UUID id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "tenant_id")
	public Tenant tenant;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "workspace_id")
	public Workspace workspace;

	@Enumerated(EnumType.STRING)
	@Column(name = "section", nullable = false)
	public PromptTemplateSection section;

	@Column(name = "content", nullable = false, columnDefinition = "text")
	public String content;

	@Column(name = "enabled", nullable = false)
	public boolean enabled = true;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false)
	public Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	public Instant updatedAt;
}
