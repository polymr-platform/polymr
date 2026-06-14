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
import jakarta.persistence.Index;
import java.math.BigDecimal;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
    name = "sessions",
    indexes = {
        @Index(name = "sessions_parent_session_idx", columnList = "parent_session_id")
    }
)
public class Session extends BaseEntity {
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "tenant_id", nullable = false)
	public Tenant tenant;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "workspace_id", nullable = false)
	public Workspace workspace;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "assistant_id")
	public Assistant defaultAssistant;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "parent_session_id")
	public Session parentSession;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "channel_id")
	public Channel channel;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "created_by_user_id")
	public User createdBy;

	@Column(name = "title")
	public String title;

	@Enumerated(EnumType.STRING)
	@Column(name = "visibility", nullable = false)
	public SessionVisibility visibility = SessionVisibility.PRIVATE;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	public SessionStatus status = SessionStatus.ACTIVE;

	@Column(name = "locked", nullable = false)
	public boolean locked = false;

	@Column(name = "title_locked", nullable = false)
	public boolean titleLocked = false;

	@Column(name = "total_cost", precision = 18, scale = 6)
	public BigDecimal totalCost;

	@Column(name = "total_cost_currency")
	public String totalCostCurrency;

	@Column(name = "last_input_tokens")
	public Long lastInputTokens;

	@Column(name = "last_output_tokens")
	public Long lastOutputTokens;

	@Column(name = "last_reasoning_tokens")
	public Long lastReasoningTokens;

	@Column(name = "last_cached_input_tokens")
	public Long lastCachedInputTokens;

	@Column(name = "cumulative_input_tokens")
	public Long cumulativeInputTokens;

	@Column(name = "cumulative_output_tokens")
	public Long cumulativeOutputTokens;

	@Column(name = "cumulative_reasoning_tokens")
	public Long cumulativeReasoningTokens;

	@Column(name = "cumulative_cached_input_tokens")
	public Long cumulativeCachedInputTokens;

	@Column(name = "cumulative_pruned_tokens")
	public Long cumulativePrunedTokens;

	@Column(name = "pruning_threshold_offset_ratio")
	public Double pruningThresholdOffsetRatio;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "current_activity")
	public com.fasterxml.jackson.databind.JsonNode currentActivity;
}
