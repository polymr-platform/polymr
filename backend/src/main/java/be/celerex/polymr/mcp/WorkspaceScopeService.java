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

import be.celerex.polymr.model.WorkspaceScope;
import be.celerex.polymr.model.Workspace;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class WorkspaceScopeService {
	@Inject
	EntityManager entityManager;

	@Inject
	ObjectMapper objectMapper;

	public ScopePermissions load(UUID workspaceId) {
		if (workspaceId == null) {
			return new ScopePermissions(List.of(), List.of());
		}
		WorkspaceScope scopes = entityManager.createQuery("select s from WorkspaceScope s where s.workspace.id = :workspaceId", WorkspaceScope.class)
			.setParameter("workspaceId", workspaceId)
			.getResultStream()
			.findFirst()
			.orElse(null);
		if (scopes == null) {
			return new ScopePermissions(List.of(), List.of());
		}
		return new ScopePermissions(toList(scopes.allowScopes), toList(scopes.denyScopes));
	}

	@Transactional
	public ScopePermissions save(UUID workspaceId, List<String> allowScopes, List<String> denyScopes) {
		if (workspaceId == null) {
			return new ScopePermissions(List.of(), List.of());
		}
		Workspace workspace = entityManager.find(Workspace.class, workspaceId);
		if (workspace == null) {
			return new ScopePermissions(List.of(), List.of());
		}
		WorkspaceScope scopes = entityManager.createQuery("select s from WorkspaceScope s where s.workspace.id = :workspaceId", WorkspaceScope.class)
			.setParameter("workspaceId", workspaceId)
			.getResultStream()
			.findFirst()
			.orElse(null);
		if (scopes == null) {
			scopes = new WorkspaceScope();
			scopes.workspace = workspace;
			entityManager.persist(scopes);
		}
		scopes.allowScopes = objectMapper.valueToTree(allowScopes == null ? List.of() : allowScopes);
		scopes.denyScopes = objectMapper.valueToTree(denyScopes == null ? List.of() : denyScopes);
		return new ScopePermissions(toList(scopes.allowScopes), toList(scopes.denyScopes));
	}

	private List<String> toList(JsonNode node) {
		if (node == null || node.isNull()) {
			return List.of();
		}
		if (node.isArray()) {
			List<String> list = new ArrayList<>();
			node.forEach(entry -> {
				if (entry.isTextual()) {
					list.add(entry.asText());
				}
			});
			return list;
		}
		return objectMapper.convertValue(node, List.class);
	}

	public record ScopePermissions(List<String> allow, List<String> deny) {}
}
