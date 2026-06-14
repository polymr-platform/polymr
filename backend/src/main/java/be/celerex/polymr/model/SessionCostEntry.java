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
import java.math.BigDecimal;

@Entity
@Table(name = "session_cost_entries")
public class SessionCostEntry extends BaseEntity {
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "session_id", nullable = false)
	public Session session;

	@Column(name = "model_id")
	public String modelId;

	@Column(name = "amount", precision = 18, scale = 6)
	public BigDecimal amount;

	@Column(name = "currency")
	public String currency;

	@Column(name = "input_tokens")
	public Long inputTokens;

	@Column(name = "output_tokens")
	public Long outputTokens;

	@Column(name = "reasoning_tokens")
	public Long reasoningTokens;

	@Column(name = "cached_input_tokens")
	public Long cachedInputTokens;

	@Column(name = "cache_ratio")
	public Double cacheRatio;

	@Column(name = "breakdown_json", columnDefinition = "text")
	public String breakdownJson;
}
