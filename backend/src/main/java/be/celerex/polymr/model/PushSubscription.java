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
import java.time.Instant;

@Entity
@Table(name = "push_subscriptions", uniqueConstraints = {
    @UniqueConstraint(columnNames = { "user_id", "endpoint" })
})
public class PushSubscription extends BaseEntity {
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	public User user;

	@Column(name = "endpoint", nullable = false, columnDefinition = "text")
	public String endpoint;

	@Column(name = "p256dh", nullable = false)
	public String p256dh;

	@Column(name = "auth", nullable = false)
	public String auth;

	@Column(name = "user_agent")
	public String userAgent;

	@Column(name = "active", nullable = false)
	public boolean active = true;

	@Column(name = "last_seen_at")
	public Instant lastSeenAt;
}
