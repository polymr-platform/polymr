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
import java.time.Instant;

@Entity
@Table(name = "mcp_oauth_tokens")
public class McpOauthToken extends BaseEntity {
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "provider_id", nullable = false)
	public McpOauthProvider provider;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "workspace_id", nullable = false)
	public Workspace workspace;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "auth_scope_value_id")
	public TagValue authScopeValue;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id")
	public User user;

	@Column(name = "active", nullable = false)
	public boolean active = true;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "source_token_id")
	public McpOauthToken sourceToken;

	@Column(name = "access_token_ciphertext")
	public String accessTokenCiphertext;

	@Column(name = "access_token_nonce")
	public String accessTokenNonce;

	@Column(name = "access_token_hint")
	public String accessTokenHint;

	@Column(name = "refresh_token_ciphertext")
	public String refreshTokenCiphertext;

	@Column(name = "refresh_token_nonce")
	public String refreshTokenNonce;

	@Column(name = "refresh_token_hint")
	public String refreshTokenHint;

	@Column(name = "access_expires_at")
	public Instant accessExpiresAt;

	@Column(name = "refresh_expires_at")
	public Instant refreshExpiresAt;

	@Column(name = "refresh_failed_at")
	public Instant refreshFailedAt;

	@Column(name = "refresh_error_message")
	public String refreshErrorMessage;
}
