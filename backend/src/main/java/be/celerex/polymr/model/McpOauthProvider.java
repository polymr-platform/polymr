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
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "mcp_oauth_providers")
public class McpOauthProvider extends BaseEntity {
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "workspace_id", nullable = false)
	public Workspace workspace;

	@Column(name = "issuer")
	public String issuer;

	@Column(name = "resource_metadata_url")
	public String resourceMetadataUrl;

	@Column(name = "well_known_url")
	public String wellKnownUrl;

	@Column(name = "authorization_endpoint")
	public String authorizationEndpoint;

	@Column(name = "token_endpoint")
	public String tokenEndpoint;

	@Column(name = "registration_endpoint")
	public String registrationEndpoint;

	@Column(name = "global_auth", nullable = false)
	public boolean globalAuth = true;

	@Column(name = "scopes")
	public String scopes;

	@ManyToMany(fetch = FetchType.LAZY)
	@JoinTable(
	name = "mcp_oauth_provider_scope_categories",
	joinColumns = @JoinColumn(name = "provider_id"),
	inverseJoinColumns = @JoinColumn(name = "category_id")
	)
	public Set<TagCategory> scopeCategories = new HashSet<>();
}
