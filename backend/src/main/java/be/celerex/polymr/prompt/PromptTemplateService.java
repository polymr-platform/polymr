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

package be.celerex.polymr.prompt;

import be.celerex.polymr.model.PromptTemplate;
import be.celerex.polymr.model.PromptTemplateSection;
import be.celerex.polymr.model.Tenant;
import be.celerex.polymr.model.Workspace;
import liqp.Template;
import liqp.TemplateParser;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class PromptTemplateService {
	@Inject
	EntityManager entityManager;

	private final TemplateParser parser = new TemplateParser.Builder().build();

	public PromptTemplate resolveTemplate(UUID tenantId, UUID workspaceId, PromptTemplateSection section) {
		if (section == null) {
			return null;
		}
		PromptTemplate workspaceTemplate = workspaceId == null ? null : findTemplate(tenantId, workspaceId, section);
		if (workspaceTemplate != null && workspaceTemplate.enabled) {
			return workspaceTemplate;
		}
		PromptTemplate tenantTemplate = tenantId == null ? null : findTemplate(tenantId, null, section);
		if (tenantTemplate != null && tenantTemplate.enabled) {
			return tenantTemplate;
		}
		return findTemplate(null, null, section);
	}

	public String render(PromptTemplate template, Map<String, Object> context) {
		if (template == null || template.content == null) {
			return null;
		}
		return renderContent(template.content, context);
	}

	public String renderContent(String content, Map<String, Object> context) {
		if (content == null) {
			return null;
		}
		Map<String, Object> safeContext = context == null ? Map.of() : new HashMap<>(context);
		Template parsed = parser.parse(content);
		return parsed.render(safeContext);
	}

	public PromptTemplate findTemplate(UUID tenantId, UUID workspaceId, PromptTemplateSection section) {
		String query = "select p from PromptTemplate p where p.section = :section "
			+ "and p.tenant "
			+ (tenantId == null ? "is null" : "= :tenant")
			+ " "
			+ "and p.workspace "
			+ (workspaceId == null ? "is null" : "= :workspace");
		var typed = entityManager.createQuery(query, PromptTemplate.class)
			.setParameter("section", section);
		if (tenantId != null) {
			Tenant tenant = entityManager.getReference(Tenant.class, tenantId);
			typed.setParameter("tenant", tenant);
		}
		if (workspaceId != null) {
			Workspace workspace = entityManager.getReference(Workspace.class, workspaceId);
			typed.setParameter("workspace", workspace);
		}
		return typed.getResultStream().findFirst().orElse(null);
	}
}
