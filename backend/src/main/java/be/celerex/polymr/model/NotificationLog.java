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

@Entity
@Table(name = "notification_logs")
public class NotificationLog extends BaseEntity {
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "workspace_id", nullable = false)
	public Workspace workspace;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "session_id")
	@org.hibernate.annotations.OnDelete(action = org.hibernate.annotations.OnDeleteAction.SET_NULL)
	public Session session;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "initiator_user_id")
	public User initiator;

	@Enumerated(EnumType.STRING)
	@Column(name = "target", nullable = false)
	public NotificationTarget target;

	@Column(name = "title", nullable = false)
	public String title;

	@Column(name = "body", nullable = false)
	public String body;

	@Column(name = "destination", nullable = false)
	public String destination;

	@Column(name = "eligible_count", nullable = false)
	public int eligibleCount;

	@Column(name = "sent_count", nullable = false)
	public int sentCount;
}
