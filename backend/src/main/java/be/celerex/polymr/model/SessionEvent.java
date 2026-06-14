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
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
	name = "session_events",
	indexes = {
		@Index(name = "idx_session_events_session_type_created", columnList = "session_id, event_type, created_at"),
		@Index(name = "idx_session_events_user", columnList = "user_id")
	}
)
public class SessionEvent extends BaseEntity {
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "session_id", nullable = false)
	public Session session;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id")
	public User user;

	@Enumerated(EnumType.STRING)
	@Column(name = "event_type", nullable = false)
	public SessionEventType eventType;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "payload_json")
	public JsonNode payloadJson;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "pruned_payload_json")
	public JsonNode prunedPayloadJson;

	@Column(name = "input_tokens")
	public Integer inputTokens;

	@Column(name = "output_tokens")
	public Integer outputTokens;

	@Column(name = "reasoning_tokens")
	public Integer reasoningTokens;

	@Column(name = "cached_input_tokens")
	public Integer cachedInputTokens;

	@Column(name = "tokenizer_model_id")
	public String tokenizerModelId;

	@Column(name = "price_snapshot", precision = 19, scale = 6)
	public BigDecimal priceSnapshot;

	@Column(name = "price_currency")
	public String priceCurrency;

	@Column(name = "epoch_id", nullable = false)
	public Integer epochId = 1;

	@Column(name = "location_lat")
	public Double locationLat;

	@Column(name = "location_lng")
	public Double locationLng;
}
