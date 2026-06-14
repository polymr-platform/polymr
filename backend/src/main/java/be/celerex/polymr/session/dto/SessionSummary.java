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

import be.celerex.polymr.model.SessionStatus;
import be.celerex.polymr.model.SessionVisibility;
import be.celerex.polymr.model.WorkflowRunStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SessionSummary(
		UUID id,
		String title,
		SessionStatus status,
		boolean needs_input,
		boolean locked,
		List<SessionParticipantResponse> participants,
		UUID workflow_definition_id,
		String workflow_definition_name,
		WorkflowRunStatus workflow_run_status,
		String workflow_current_node,
		String workflow_checkpoint_status,
		UUID default_assistant_id,
		UUID channel_id,
		String channel_name,
		SessionVisibility visibility,
		Instant updated_at,
		SessionModelTelemetry model_telemetry,
		com.fasterxml.jackson.databind.JsonNode current_activity) {}
