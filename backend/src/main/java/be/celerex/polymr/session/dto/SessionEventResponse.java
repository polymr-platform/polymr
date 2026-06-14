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

package be.celerex.polymr.session.dto;

import be.celerex.polymr.model.SessionEventType;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record SessionEventResponse(
		UUID id,
		SessionEventType type,
		JsonNode payload,
		Instant created_at,
		Integer epoch_id,
		Integer input_tokens,
		Integer output_tokens,
		Integer reasoning_tokens,
		Integer cached_input_tokens,
		String tokenizer_model_id,
		BigDecimal price_snapshot,
		String price_currency) {}
