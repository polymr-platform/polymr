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

package be.celerex.polymr.workflow.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import org.bsc.langgraph4j.state.AgentState;

public final class PendingState {
	public static final String PENDING = "pending";
	public static final String EVENTS = "events";
	public static final String SESSION_STATUS = "session_status";
	public static final String RUN_STATUS = "run_status";
	public static final String LOCKED = "locked";
	public static final String TOOL_REQUESTS = "tool_requests";
	public static final String TOOL_APPROVAL = "tool_approval";
	public static final String TOOL_APPROVAL_PENDING = "pending";
	public static final String TOOL_APPROVAL_DECISION = "decision";
	public static final String TOOL_APPROVAL_REQUEST_ID = "request_id";
	public static final String TOOL_APPROVAL_BYPASS = "bypass";
	public static final String TOOL_APPROVAL_ALLOW_SCOPES = "allow_scopes";
	public static final String TOOL_APPROVAL_DENY_SCOPES = "deny_scopes";
	public static final String TOOL_FAILURE = "tool_failure";
	public static final String TOOL_USAGE = "tool_usage";

	private PendingState() {}

	public static Builder builder(ObjectMapper objectMapper) {
		return new Builder(objectMapper);
	}

	public static ObjectNode toObjectNode(ObjectMapper objectMapper, Object raw) {
		if (raw instanceof ObjectNode objectNode) {
			return objectNode;
		}
		if (raw instanceof Map<?, ?>) {
			return objectMapper.convertValue(raw, ObjectNode.class);
		}
		if (raw instanceof JsonNode jsonNode && jsonNode.isObject()) {
			return (ObjectNode) jsonNode;
		}
		return objectMapper.createObjectNode();
	}

	public static ObjectNode pendingFromState(ConversationGraphState state, ObjectMapper objectMapper) {
		if (state == null) {
			return objectMapper.createObjectNode();
		}
		Object raw = state.value(PENDING).orElse(null);
		return toObjectNode(objectMapper, raw);
	}

	public static ObjectNode pendingFromCheckpoint(JsonNode checkpoint, ObjectMapper objectMapper) {
		if (checkpoint == null || checkpoint.isNull()) {
			return objectMapper.createObjectNode();
		}
		JsonNode node = checkpoint.get(PENDING);
		if (node instanceof ObjectNode objectNode) {
			return objectNode;
		}
		if (node != null && node.isObject()) {
			return objectMapper.convertValue(node, ObjectNode.class);
		}
		return objectMapper.createObjectNode();
	}

	public static JsonNode read(JsonNode root, String...path) {
		JsonNode current = root;
		for (String key : path) {
			if (current == null || current.isNull()) {
				return null;
			}
			current = current.get(key);
		}
		return current;
	}

	public static String readText(JsonNode root, String...path) {
		JsonNode node = read(root, path);
		return node == null || node.isNull() ? null : node.asText(null);
	}

	public static boolean readBoolean(JsonNode root, String...path) {
		JsonNode node = read(root, path);
		return node != null && node.asBoolean(false);
	}

	public static final class Builder {
		private final ObjectMapper objectMapper;
		private final ObjectNode pending;

		private Builder(ObjectMapper objectMapper) {
			this.objectMapper = objectMapper;
			this.pending = objectMapper.createObjectNode();
		}

		public Builder events(Object events) {
			if (events != null && events != AgentState.MARK_FOR_REMOVAL) {
				pending.set(EVENTS, objectMapper.valueToTree(events));
			}
			return this;
		}

		public Builder sessionStatus(String status) {
			if (status != null) {
				pending.put(SESSION_STATUS, status);
			}
			return this;
		}

		public Builder runStatus(String status) {
			if (status != null) {
				pending.put(RUN_STATUS, status);
			}
			return this;
		}

		public Builder locked(Boolean locked) {
			if (locked != null) {
				pending.put(LOCKED, locked);
			}
			return this;
		}

		public Builder toolRequests(Object requests) {
			if (requests != null && requests != AgentState.MARK_FOR_REMOVAL) {
				pending.set(TOOL_REQUESTS, objectMapper.valueToTree(requests));
			}
			return this;
		}

		public Builder toolApprovalPending(Boolean pendingValue) {
			if (pendingValue != null) {
				toolApprovalNode().put(TOOL_APPROVAL_PENDING, pendingValue);
			}
			return this;
		}

		public Builder toolApprovalDecision(String decision) {
			if (decision != null && decision != AgentState.MARK_FOR_REMOVAL) {
				toolApprovalNode().put(TOOL_APPROVAL_DECISION, decision);
			}
			return this;
		}

		public Builder toolApprovalRequestId(String requestId) {
			if (requestId != null && requestId != AgentState.MARK_FOR_REMOVAL) {
				toolApprovalNode().put(TOOL_APPROVAL_REQUEST_ID, requestId);
			}
			return this;
		}

		public Builder toolApprovalBypass(Boolean bypass) {
			if (bypass != null) {
				toolApprovalNode().put(TOOL_APPROVAL_BYPASS, bypass);
			}
			return this;
		}

		public Builder toolApprovalAllowScopes(Object allowScopes) {
			if (allowScopes != null && allowScopes != AgentState.MARK_FOR_REMOVAL) {
				toolApprovalNode().set(TOOL_APPROVAL_ALLOW_SCOPES, objectMapper.valueToTree(allowScopes));
			}
			return this;
		}

		public Builder toolApprovalDenyScopes(Object denyScopes) {
			if (denyScopes != null && denyScopes != AgentState.MARK_FOR_REMOVAL) {
				toolApprovalNode().set(TOOL_APPROVAL_DENY_SCOPES, objectMapper.valueToTree(denyScopes));
			}
			return this;
		}

		public Builder toolFailure(String message) {
			if (message != null) {
				pending.put(TOOL_FAILURE, message);
			}
			return this;
		}

		public Builder toolUsage(Object usage) {
			if (usage != null && usage != AgentState.MARK_FOR_REMOVAL) {
				pending.set(TOOL_USAGE, objectMapper.valueToTree(usage));
			}
			return this;
		}

		public void apply(Map<String, Object> updates) {
			if (pending.isEmpty()) {
				updates.put(PENDING, AgentState.MARK_FOR_REMOVAL);
				return;
			}
			updates.put(PENDING, pending);
		}

		private ObjectNode toolApprovalNode() {
			ObjectNode node = pending.with(TOOL_APPROVAL);
			return node == null ? pending.putObject(TOOL_APPROVAL) : node;
		}
	}
}
