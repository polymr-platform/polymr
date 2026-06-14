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

package be.celerex.polymr.workflow;

import be.celerex.polymr.model.WorkflowDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.util.UUID;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.List;

@ApplicationScoped
public class WorkflowDefinitionService {
	@Inject
	EntityManager entityManager;

	@Inject
	ObjectMapper objectMapper;

	public boolean isConversationDefinition(WorkflowDefinition definition) {
		if (definition == null) {
			return false;
		}
		String name = definition.name == null ? "" : definition.name;
		return "conversation".equalsIgnoreCase(name);
	}

	public ObjectNode defaultDefinition() {
		ObjectNode definition = objectMapper.createObjectNode();
		definition.put("type", "graph");
		definition.put("name", "Workflow");
		definition.put("start", "node-1");
		definition.putArray("end_nodes");
		definition.putArray("return_nodes");
		ObjectNode nodes = definition.putObject("nodes");
		ObjectNode node = nodes.putObject("node-1");
		node.put("type", "ai");
		node.put("name", "");
		node.put("goal", "");
		node.putObject("output_schema");
		node.putObject("scopes").putArray("allow_scopes");
		node.with("scopes").putArray("deny_scopes");
		ObjectNode edges = definition.putObject("edges");
		edges.putArray("node-1");
		return definition;
	}

	@Transactional
	public WorkflowDefinition loadDefinition(UUID definitionId) {
		return entityManager.createQuery("select w from WorkflowDefinition w where w.id = :id", WorkflowDefinition.class)
			.setParameter("id", definitionId)
			.getResultStream()
			.findFirst()
			.orElse(null);
	}

	public void validateDefinition(JsonNode definition) {
		if (definition == null || definition.isNull() || !definition.isObject()) {
			throw new WebApplicationException("Definition is required", Response.Status.BAD_REQUEST);
		}
		ObjectNode object = (ObjectNode) definition;
		stripInternalNodeFields(object);
		JsonNode nodes = object.get("nodes");
		JsonNode edges = object.get("edges");
		String start = object.has("start") ? object.get("start").asText(null) : null;
		JsonNode endNodes = object.get("end_nodes");
		JsonNode returnNodes = object.get("return_nodes");
		if (nodes == null || !nodes.isObject()) {
			throw new WebApplicationException("Definition nodes are required", Response.Status.BAD_REQUEST);
		}
		if (edges == null || !edges.isObject()) {
			throw new WebApplicationException("Definition edges are required", Response.Status.BAD_REQUEST);
		}
		if (start == null || start.isBlank()) {
			throw new WebApplicationException("Definition start is required", Response.Status.BAD_REQUEST);
		}
		if (!nodes.has(start)) {
			throw new WebApplicationException("Definition start node is missing", Response.Status.BAD_REQUEST);
		}
		if (endNodes != null && !endNodes.isNull()) {
			if (!endNodes.isArray()) {
				throw new WebApplicationException("Definition end_nodes must be an array", Response.Status.BAD_REQUEST);
			}
			for (JsonNode endNode : endNodes) {
				String endNodeId = endNode == null ? null : endNode.asText(null);
				if (endNodeId == null || endNodeId.isBlank() || !nodes.has(endNodeId)) {
					throw new WebApplicationException("Definition end node is missing", Response.Status.BAD_REQUEST);
				}
			}
		}
		if (returnNodes != null && !returnNodes.isNull()) {
			if (!returnNodes.isArray()) {
				throw new WebApplicationException("Definition return_nodes must be an array", Response.Status.BAD_REQUEST);
			}
			for (JsonNode returnNode : returnNodes) {
				String returnNodeId = returnNode == null ? null : returnNode.asText(null);
				if (returnNodeId == null || returnNodeId.isBlank() || !nodes.has(returnNodeId)) {
					throw new WebApplicationException("Definition return node is missing", Response.Status.BAD_REQUEST);
				}
			}
		}
		java.util.Map<String, String> nodeTypes = new java.util.HashMap<>();
		java.util.Iterator<java.util.Map.Entry<String, JsonNode>> fields = nodes.fields();
		while (fields.hasNext()) {
			java.util.Map.Entry<String, JsonNode> entry = fields.next();
			JsonNode node = entry.getValue();
			if (node == null || !node.isObject()) {
				throw new WebApplicationException("Definition node must be an object: " + entry.getKey(), Response.Status.BAD_REQUEST);
			}
			String type = node.path("type").asText(null);
			if (type == null || type.isBlank()) {
				throw new WebApplicationException("Definition node type is required: " + entry.getKey(), Response.Status.BAD_REQUEST);
			}
			if ("return".equals(type)) {
				throw new WebApplicationException("Return step nodes are no longer supported: " + entry.getKey(), Response.Status.BAD_REQUEST);
			}
			nodeTypes.put(entry.getKey(), type);
		}
		validateEdges((ObjectNode) edges, (ObjectNode) nodes, nodeTypes, endNodes, returnNodes);
		validateReleaseFlow((ObjectNode) nodes, (ObjectNode) edges, nodeTypes, endNodes, returnNodes, start);
	}

	public ObjectNode enrichDefinition(JsonNode definition) {
		validateDefinition(definition);
		ObjectNode object = (ObjectNode) definition;
		JsonNode nodes = object.get("nodes");
		boolean hasLogicalNodes = false;
		if (nodes != null && nodes.isObject()) {
			java.util.Iterator<java.util.Map.Entry<String, JsonNode>> fields = nodes.fields();
			while (fields.hasNext()) {
				JsonNode node = fields.next().getValue();
				String type = node.path("type").asText(null);
				if (type == null) {
					continue;
				}
				if (!isRuntimeNodeType(type)) {
					hasLogicalNodes = true;
					break;
				}
			}
		}
		if (!hasLogicalNodes) {
			ensureAwaitChildren(object);
			return object;
		}
		ObjectNode enriched = objectMapper.createObjectNode();
		copyIfPresent(object, enriched, "type");
		copyIfPresent(object, enriched, "name");
		copyIfPresent(object, enriched, "description");
		copyIfPresent(object, enriched, "mcp");
		copyIfPresent(object, enriched, "scopes");
		copyIfPresent(object, enriched, "tags");
		copyIfPresent(object, enriched, "audience");
		enriched.put("start", "user_input");
		enriched.set("logical_start", object.get("start"));
		if (object.has("end_nodes")) {
			enriched.set("logical_end_nodes", object.get("end_nodes"));
		}
		if (object.has("return_nodes")) {
			enriched.set("logical_return_nodes", object.get("return_nodes"));
		}
		enriched.set("logical_nodes", object.get("nodes"));
		enriched.set("logical_edges", object.get("edges"));
		ObjectNode runtimeNodes = enriched.putObject("nodes");
		runtimeNodes.putObject("user_input").put("type", "user_input");
		runtimeNodes.putObject("llm").put("type", "llm");
		runtimeNodes.putObject("tool_exec").put("type", "tool_exec");
		runtimeNodes.putObject("await_children").put("type", "await_children");
		ObjectNode runtimeEdges = enriched.putObject("edges");
		runtimeEdges.set(
			"user_input",
			edgeObject(List.of("default", "tool_exec", "pause"), List.of("llm", "tool_exec", "__END__"))
		);
		runtimeEdges.set(
			"llm",
			edgeObject(
				List.of("default", "tool_exec", "continue", "complete"),
				List.of("user_input", "tool_exec", "llm", "__END__")
			)
		);
		runtimeEdges.set(
			"tool_exec",
			edgeObject(
				List.of("default", "needs_approval", "await_children"),
				List.of("llm", "user_input", "await_children")
			)
		);
		runtimeEdges.set("await_children", edgeObject(List.of("default", "pause"), List.of("llm", "__END__")));
		ObjectNode recovery = enriched.putObject("recovery");
		recovery.put("llm", "user_input");
		recovery.put("tool_exec", "llm");
		recovery.put("await_children", "await_children");
		return enriched;
	}

	private void ensureAwaitChildren(ObjectNode definition) {
		if (definition == null) {
			return;
		}
		ObjectNode nodes = definition.get("nodes") instanceof ObjectNode node ? node : definition.putObject("nodes");
		if (!nodes.has("await_children")) {
			nodes.putObject("await_children").put("type", "await_children");
		}
		ObjectNode edges = definition.get("edges") instanceof ObjectNode edge ? edge : definition.putObject("edges");
		if (!edges.has("await_children")) {
			edges.set("await_children", edgeObject(List.of("default", "pause"), List.of("llm", "__END__")));
		}
		ObjectNode recovery = definition.get("recovery") instanceof ObjectNode recoveryNode
			? recoveryNode
			: definition.putObject("recovery");
		if (!recovery.has("await_children")) {
			recovery.put("await_children", "await_children");
		}
	}

	private void validateEdges(
			ObjectNode edges,
			ObjectNode nodes,
			java.util.Map<String, String> nodeTypes,
			JsonNode endNodes,
			JsonNode returnNodes) {
		java.util.Set<String> terminalNodeIds = new java.util.HashSet<>();
		if (endNodes != null && endNodes.isArray()) {
			for (JsonNode entry : endNodes) {
				if (entry != null) {
					String endNodeId = entry.asText(null);
					if (endNodeId != null && !endNodeId.isBlank()) {
						terminalNodeIds.add(endNodeId);
					}
				}
			}
		}
		if (returnNodes != null && returnNodes.isArray()) {
			for (JsonNode entry : returnNodes) {
				if (entry != null) {
					String returnNodeId = entry.asText(null);
					if (returnNodeId != null && !returnNodeId.isBlank()) {
						terminalNodeIds.add(returnNodeId);
					}
				}
			}
		}
		java.util.Iterator<java.util.Map.Entry<String, JsonNode>> fields = edges.fields();
		while (fields.hasNext()) {
			java.util.Map.Entry<String, JsonNode> entry = fields.next();
			String sourceId = entry.getKey();
			JsonNode edge = entry.getValue();
			if (!nodes.has(sourceId)) {
				throw new WebApplicationException("Definition edge source is missing: " + sourceId, Response.Status.BAD_REQUEST);
			}
			if (terminalNodeIds.contains(sourceId)) {
				throw new WebApplicationException("End and return nodes cannot have outgoing edges: " + sourceId, Response.Status.BAD_REQUEST);
			}
			String sourceType = nodeTypes.get(sourceId);
			if (edge == null || edge.isNull()) {
				continue;
			}
			if (edge.isArray()) {
				validateTargetArray((ArrayNode) edge, nodes, sourceId);
				continue;
			}
			if (!edge.isObject()) {
				throw new WebApplicationException("Definition edge must be an object or array: " + sourceId, Response.Status.BAD_REQUEST);
			}
			ObjectNode edgeObject = (ObjectNode) edge;
			JsonNode defaultTargets = edgeObject.get("default");
			if (defaultTargets instanceof ArrayNode array) {
				validateTargetArray(array, nodes, sourceId);
			}
			JsonNode routes = edgeObject.get("routes");
			if (routes != null && !routes.isNull()) {
				if (!routes.isObject()) {
					throw new WebApplicationException("Definition routes must be an object: " + sourceId, Response.Status.BAD_REQUEST);
				}
				java.util.Iterator<java.util.Map.Entry<String, JsonNode>> routeFields = ((ObjectNode) routes).fields();
				while (routeFields.hasNext()) {
					java.util.Map.Entry<String, JsonNode> routeEntry = routeFields.next();
					validateRouteName(sourceType, routeEntry.getKey(), sourceId);
					if (!(routeEntry.getValue() instanceof ArrayNode routeTargets)) {
						throw new WebApplicationException("Definition route targets must be an array: " + sourceId, Response.Status.BAD_REQUEST);
					}
					validateTargetArray(routeTargets, nodes, sourceId);
				}
			}
			JsonNode conditions = edgeObject.get("conditions");
			if (conditions != null && !conditions.isNull()) {
				if (!conditions.isArray()) {
					throw new WebApplicationException("Definition conditions must be an array: " + sourceId, Response.Status.BAD_REQUEST);
				}
				for (JsonNode condition : conditions) {
					if (condition == null || !condition.isObject()) {
						throw new WebApplicationException("Definition condition must be an object: " + sourceId, Response.Status.BAD_REQUEST);
					}
					String target = condition.path("target").asText(null);
					if (target == null || target.isBlank() || !nodes.has(target)) {
						throw new WebApplicationException("Definition condition target is missing: " + sourceId, Response.Status.BAD_REQUEST);
					}
					validateRouteName(sourceType, condition.path("route").asText("default"), sourceId);
				}
			}
		}
	}

	private void validateTargetArray(ArrayNode targets, ObjectNode nodes, String sourceId) {
		for (JsonNode target : targets) {
			String targetId = target == null ? null : target.asText(null);
			if (targetId == null || targetId.isBlank() || !nodes.has(targetId)) {
				throw new WebApplicationException("Definition edge target is missing: " + sourceId, Response.Status.BAD_REQUEST);
			}
		}
	}

	private void validateRouteName(String sourceType, String route, String sourceId) {
		String resolved = route == null || route.isBlank() ? "default" : route;
		java.util.Set<String> allowed = allowedRoutesForNodeType(sourceType);
		if (!allowed.contains(resolved)) {
			throw new WebApplicationException("Invalid route '" + resolved + "' for node: " + sourceId, Response.Status.BAD_REQUEST);
		}
	}

	private java.util.Set<String> allowedRoutesForNodeType(String sourceType) {
		if ("for_each".equals(sourceType)) {
			return java.util.Set.of("loop", "done");
		}
		if ("router".equals(sourceType)) {
			return java.util.Set.of("action", "done");
		}
		return java.util.Set.of("default");
	}

	private void validateReleaseFlow(
			ObjectNode nodes,
			ObjectNode edges,
			java.util.Map<String, String> nodeTypes,
			JsonNode endNodes,
			JsonNode returnNodes,
			String startNodeId) {
		java.util.Set<String> workflowEndNodeIds = readNodeIdSet(endNodes);
		java.util.Set<String> workflowReturnNodeIds = readNodeIdSet(returnNodes);
		validateTopLevelReturns(startNodeId, edges, nodeTypes, workflowReturnNodeIds, workflowEndNodeIds);
		java.util.Iterator<java.util.Map.Entry<String, JsonNode>> fields = nodes.fields();
		while (fields.hasNext()) {
			java.util.Map.Entry<String, JsonNode> entry = fields.next();
			String nodeId = entry.getKey();
			String type = nodeTypes.get(nodeId);
			if (!"for_each".equals(type) && !"router".equals(type)) {
				continue;
			}
			validateControlExit(
				nodeId,
				nodeId,
				edges,
				nodeTypes,
				workflowEndNodeIds,
				workflowReturnNodeIds,
				new java.util.HashSet<>()
			);
		}
	}

	private void validateTopLevelReturns(
			String startNodeId,
			ObjectNode edges,
			java.util.Map<String, String> nodeTypes,
			java.util.Set<String> workflowReturnNodeIds,
			java.util.Set<String> workflowEndNodeIds) {
		if (startNodeId == null || startNodeId.isBlank() || workflowReturnNodeIds.isEmpty()) {
			return;
		}
		java.util.Set<String> visited = new java.util.HashSet<>();
		java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
		queue.add(startNodeId);
		while (!queue.isEmpty()) {
			String nodeId = queue.removeFirst();
			if (nodeId == null || nodeId.isBlank() || !visited.add(nodeId)) {
				continue;
			}
			if (workflowReturnNodeIds.contains(nodeId)) {
				throw new WebApplicationException(
					"Return node must only be reachable from inside a router or for_each branch: " + nodeId,
					Response.Status.CONFLICT
				);
			}
			if (workflowEndNodeIds.contains(nodeId)) {
				continue;
			}
			String type = nodeTypes.get(nodeId);
			if ("for_each".equals(type) || "router".equals(type)) {
				for (String next : resolveRouteTargets(edges, nodeId, "done")) {
					queue.addLast(next);
				}
				continue;
			}
			for (String next : resolveRouteTargets(edges, nodeId, "default")) {
				queue.addLast(next);
			}
		}
	}

	private void validateControlExit(
			String controlNodeId,
			String nodeId,
			ObjectNode edges,
			java.util.Map<String, String> nodeTypes,
			java.util.Set<String> workflowEndNodeIds,
			java.util.Set<String> workflowReturnNodeIds,
			java.util.Set<String> visited) {
		if (nodeId == null || nodeId.isBlank() || !visited.add(nodeId)) {
			return;
		}
		if (workflowEndNodeIds.contains(nodeId) || workflowReturnNodeIds.contains(nodeId)) {
			return;
		}
		String type = nodeTypes.get(nodeId);
		if (nodeId.equals(controlNodeId)) {
			java.util.List<String> branchTargets = new java.util.ArrayList<>();
			if ("for_each".equals(type)) {
				branchTargets.addAll(resolveRouteTargets(edges, nodeId, "loop"));
			}
			else if ("router".equals(type)) {
				branchTargets.addAll(resolveRouteTargets(edges, nodeId, "action"));
			}
			for (String target : branchTargets) {
				validateControlExit(
					controlNodeId,
					target,
					edges,
					nodeTypes,
					workflowEndNodeIds,
					workflowReturnNodeIds,
					new java.util.HashSet<>()
				);
			}
			return;
		}
		if ("for_each".equals(type) || "router".equals(type)) {
			return;
		}
		java.util.List<String> nextTargets = resolveRouteTargets(edges, nodeId, "default");
		if (nextTargets.isEmpty()) {
			return;
		}
		for (String target : nextTargets) {
			if (controlNodeId.equals(target)) {
				throw new WebApplicationException(
					"Control node branch loops back to itself and must terminate through a dead end, "
						+ "done route, workflow end, or return: "
						+ controlNodeId,
					Response.Status.CONFLICT
				);
			}
			validateControlExit(controlNodeId, target, edges, nodeTypes, workflowEndNodeIds, workflowReturnNodeIds, visited);
		}
	}

	private java.util.Set<String> readNodeIdSet(JsonNode nodeIds) {
		java.util.Set<String> result = new java.util.HashSet<>();
		if (nodeIds == null || !nodeIds.isArray()) {
			return result;
		}
		for (JsonNode entry : nodeIds) {
			if (entry == null) {
				continue;
			}
			String nodeId = entry.asText(null);
			if (nodeId != null && !nodeId.isBlank()) {
				result.add(nodeId);
			}
		}
		return result;
	}

	private java.util.List<String> resolveRouteTargets(ObjectNode edges, String nodeId, String route) {
		java.util.List<String> targets = new java.util.ArrayList<>();
		if (edges == null || nodeId == null || nodeId.isBlank()) {
			return targets;
		}
		JsonNode edge = edges.get(nodeId);
		if (!(edge instanceof ObjectNode edgeObject)) {
			if ("default".equals(route) && edge instanceof ArrayNode array) {
				array.forEach(
					target -> {
						if (target != null) {
							String value = target.asText(null);
							if (value != null && !value.isBlank()) {
								targets.add(value);
							}
						}
					}
				);
			}
			return targets;
		}
		JsonNode conditions = edgeObject.get("conditions");
		if (conditions != null && conditions.isArray()) {
			for (JsonNode condition : conditions) {
				if (condition == null || !condition.isObject()) {
					continue;
				}
				String conditionRoute = condition.path("route").asText("default");
				if (!route.equals(conditionRoute)) {
					continue;
				}
				String target = condition.path("target").asText(null);
				if (target != null && !target.isBlank()) {
					targets.add(target);
				}
			}
		}
		JsonNode routes = edgeObject.get("routes");
		if (routes != null && routes.isObject() && routes.has(route)) {
			JsonNode routeTargets = routes.get(route);
			if (routeTargets != null && routeTargets.isArray()) {
				routeTargets.forEach(
					target -> {
						if (target != null) {
							String value = target.asText(null);
							if (value != null && !value.isBlank()) {
								targets.add(value);
							}
						}
					}
				);
			}
		}
		if ("default".equals(route)) {
			JsonNode defaultTargets = edgeObject.get("default");
			if (defaultTargets != null && defaultTargets.isArray()) {
				defaultTargets.forEach(
					target -> {
						if (target != null) {
							String value = target.asText(null);
							if (value != null && !value.isBlank()) {
								targets.add(value);
							}
						}
					}
				);
			}
		}
		return targets;
	}

	private boolean isRuntimeNodeType(String type) {
		if (type == null) {
			return false;
		}
		return "user_input".equals(type)
			|| "llm".equals(type)
			|| "tool_exec".equals(type)
			|| "await_children".equals(type);
	}

	private void stripInternalNodeFields(ObjectNode definition) {
		if (definition == null) {
			return;
		}
		JsonNode nodes = definition.get("nodes");
		if (!(nodes instanceof ObjectNode objectNode)) {
			return;
		}
		objectNode.fieldNames()
			.forEachRemaining(
				nodeId -> {
					JsonNode rawNode = objectNode.get(nodeId);
					if (!(rawNode instanceof ObjectNode node)) {
						return;
					}
					node.remove("queue_field");
				}
			);
	}

	private void copyIfPresent(ObjectNode source, ObjectNode target, String key) {
		if (source != null && target != null && key != null && source.has(key)) {
			target.set(key, source.get(key));
		}
	}

	private ObjectNode edgeObject(List<String> keys, List<String> targets) {
		ObjectNode node = objectMapper.createObjectNode();
		for (int i = 0; i < keys.size(); i++) {
			ArrayNode array = node.putArray(keys.get(i));
			array.add(targets.get(i));
		}
		return node;
	}
}
