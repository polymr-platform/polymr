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

package be.celerex.polymr.mcp;

import be.celerex.polymr.model.McpServerPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class McpPolicyResolverService {
	@Inject
	EntityManager entityManager;

	@Inject
	ObjectMapper objectMapper;

	public ObjectNode resolve(UUID serverId, List<UUID> tagIds) {
		if (serverId == null || tagIds == null || tagIds.isEmpty()) {
			return null;
		}
		List<McpServerPolicy> policies = entityManager.createQuery(
				"select p from McpServerPolicy p join fetch p.tag "
					+ "where p.mcpServer.id = :serverId and p.tag.id in :tagIds",
				McpServerPolicy.class
			)
			.setParameter("serverId", serverId)
			.setParameter("tagIds", tagIds)
			.getResultList();
		if (policies.isEmpty()) {
			return null;
		}
		policies.sort(
			Comparator.comparingInt(
				policy -> {
					if (policy.tag == null || policy.tag.category == null) {
						return Integer.MAX_VALUE;
					}
					return policy.tag.category.priority;
				}
			)
		);
		ObjectNode merged = objectMapper.createObjectNode();
		for (McpServerPolicy policy : policies) {
			if (policy.policyJson == null || policy.policyJson.isNull()) {
				continue;
			}
			if (policy.policyJson.isObject()) {
				merge(merged, (ObjectNode) policy.policyJson);
			}
		}
		return merged.isEmpty() ? null : merged;
	}

	private void merge(ObjectNode target, ObjectNode source) {
		source.fieldNames()
			.forEachRemaining(
				field -> {
					JsonNode value = source.get(field);
					JsonNode existing = target.get(field);
					if (value != null && value.isObject() && existing != null && existing.isObject()) {
						merge((ObjectNode) existing, (ObjectNode) value);
					}
					else {
						target.set(field, value);
					}
				}
			);
	}
}
