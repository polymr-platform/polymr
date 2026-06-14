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
	name = "workspace_asset_bundle_entries",
	uniqueConstraints = {
		@UniqueConstraint(columnNames = { "bundle_id", "resource_path" })
	}
)
public class WorkspaceAssetBundleEntry extends BaseEntity {
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "bundle_id", nullable = false)
	public WorkspaceAssetBundle bundle;

	@Column(name = "resource_path", nullable = false)
	public String resourcePath;

	@Column(name = "blob_hash", nullable = false)
	public String blobHash;

	@Column(name = "source_uri")
	public String sourceUri;

	@Column(name = "mime_type")
	public String mimeType;
}
