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
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "users")
public class User extends BaseEntity {
	@Column(name = "email", nullable = false, unique = true)
	public String email;

	@Column(name = "nickname")
	public String nickname;

	@Column(name = "avatar_url")
	public String avatarUrl;

	@JdbcTypeCode(SqlTypes.VARBINARY)
	@Column(name = "avatar_bytes")
	public byte[] avatarBytes;

	@Column(name = "avatar_content_type")
	public String avatarContentType;

	@Column(name = "avatar_updated_at")
	public Instant avatarUpdatedAt;

	@Column(name = "password_hash", nullable = false)
	public String passwordHash;

	@Column(name = "password_salt", nullable = false)
	public String passwordSalt;

	@Column(name = "notifications_snoozed_until")
	public Instant notificationsSnoozedUntil;

	@Column(name = "execution_mode")
	public UserExecutionMode executionMode;
}
