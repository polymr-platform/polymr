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

import java.util.Map;
import be.celerex.polymr.workflow.runtime.PendingState;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

public class ConversationGraphState extends AgentState {
	public static final String STATUS = "status";
	public static final String ROUTE = "route";
	public static final String PAYLOAD = "payload";
	public static final String SESSION_ID = "session_id";
	public static final String USER_ID = "user_id";
	public static final String MCP_SNAPSHOT = "mcp_snapshot";
	public static final String SCOPE_SNAPSHOT = "scope_snapshot";
	public static final String PENDING = PendingState.PENDING;
	public static final String LOGICAL_NODE_ID = "logical_node_id";
	public static final String LOGICAL_NODE_INSTANCE_ID = "logical_node_instance_id";
	public static final String RUNTIME_NODE_TYPE = "runtime_node_type";
	public static final String STATE = "state";
	public static final String INTERNAL = "internal";
	public static final Map<String, Channel<?>> SCHEMA = Map.ofEntries(
		Map.entry(STATUS, Channels.base(() -> "normal")),
		Map.entry(ROUTE, Channels.base(() -> "default")),
		Map.entry(PAYLOAD, Channels.base((org.bsc.langgraph4j.state.Reducer<Object>) null)),
		Map.entry(SESSION_ID, Channels.base((org.bsc.langgraph4j.state.Reducer<Object>) null)),
		Map.entry(USER_ID, Channels.base((org.bsc.langgraph4j.state.Reducer<Object>) null)),
		Map.entry(MCP_SNAPSHOT, Channels.base((org.bsc.langgraph4j.state.Reducer<Object>) null)),
		Map.entry(SCOPE_SNAPSHOT, Channels.base((org.bsc.langgraph4j.state.Reducer<Object>) null)),
		Map.entry(PENDING, Channels.base((org.bsc.langgraph4j.state.Reducer<Object>) null)),
		Map.entry(LOGICAL_NODE_ID, Channels.base((org.bsc.langgraph4j.state.Reducer<Object>) null)),
		Map.entry(LOGICAL_NODE_INSTANCE_ID, Channels.base((org.bsc.langgraph4j.state.Reducer<Object>) null)),
		Map.entry(RUNTIME_NODE_TYPE, Channels.base((org.bsc.langgraph4j.state.Reducer<Object>) null)),
		Map.entry(STATE, Channels.base((org.bsc.langgraph4j.state.Reducer<Object>) null)),
		Map.entry(INTERNAL, Channels.base((org.bsc.langgraph4j.state.Reducer<Object>) null))
	);

	public ConversationGraphState(Map<String, Object> initData) {
		super(initData);
	}
}
