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

package be.celerex.polymr.session;

import be.celerex.polymr.model.Session;
import be.celerex.polymr.model.SessionCostEntry;
import be.celerex.polymr.modelregistry.telemetry.PriceResult;
import be.celerex.polymr.modelregistry.telemetry.PriceBreakdownItem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;

@ApplicationScoped
public class SessionCostService {
	@Inject
	EntityManager entityManager;

	@Inject
	ObjectMapper objectMapper;

	@Transactional
	public void applyPriceResult(UUID sessionId, String modelId, PriceResult result) {
		if (sessionId == null || result == null) {
			return;
		}
		Session session = entityManager.find(Session.class, sessionId);
		if (session == null) {
			return;
		}
		long inputTokens = result.input_tokens() == null ? 0 : result.input_tokens();
		long outputTokens = result.output_tokens() == null ? 0 : result.output_tokens();
		long reasoningTokens = result.reasoning_tokens() == null ? 0 : result.reasoning_tokens();
		long cachedInputTokens = result.cached_input_tokens() == null ? 0 : result.cached_input_tokens();
		long cumulativeInput = (session.cumulativeInputTokens == null ? 0 : session.cumulativeInputTokens) + inputTokens;
		long cumulativeOutput = (session.cumulativeOutputTokens == null ? 0 : session.cumulativeOutputTokens) + outputTokens;
		long cumulativeReasoning = (session.cumulativeReasoningTokens == null ? 0 : session.cumulativeReasoningTokens)
			+ reasoningTokens;
		long cumulativeCached = (session.cumulativeCachedInputTokens == null ? 0 : session.cumulativeCachedInputTokens)
			+ cachedInputTokens;
		session.cumulativeInputTokens = cumulativeInput;
		session.cumulativeOutputTokens = cumulativeOutput;
		session.cumulativeReasoningTokens = cumulativeReasoning;
		session.cumulativeCachedInputTokens = cumulativeCached;
		session.lastInputTokens = result.input_tokens();
		session.lastOutputTokens = result.output_tokens();
		session.lastReasoningTokens = result.reasoning_tokens();
		session.lastCachedInputTokens = result.cached_input_tokens();

		BigDecimal effectiveCost = result.total_cost();
		if (effectiveCost == null) {
			return;
		}
		effectiveCost = effectiveCost.setScale(6, RoundingMode.HALF_UP);

		session.totalCost = session.totalCost == null ? effectiveCost : session.totalCost.add(effectiveCost);
		if (result.currency() != null && !result.currency().isBlank()) {
			session.totalCostCurrency = result.currency();
		}

		SessionCostEntry entry = new SessionCostEntry();
		entry.session = session;
		entry.modelId = modelId;
		entry.amount = effectiveCost;
		entry.currency = result.currency();
		entry.inputTokens = result.input_tokens();
		entry.outputTokens = result.output_tokens();
		entry.reasoningTokens = result.reasoning_tokens();
		entry.cachedInputTokens = result.cached_input_tokens();
		entry.cacheRatio = inputTokens == 0 ? 0 : (double) cachedInputTokens / (double) inputTokens;
		entry.breakdownJson = serializeBreakdown(result.breakdown());
		entityManager.persist(entry);
	}

	private String serializeBreakdown(List<PriceBreakdownItem> breakdown) {
		if (breakdown == null || breakdown.isEmpty()) {
			return null;
		}
		try {
			return objectMapper.writeValueAsString(breakdown);
		}
		catch (JsonProcessingException ex) {
			return null;
		}
	}
}
