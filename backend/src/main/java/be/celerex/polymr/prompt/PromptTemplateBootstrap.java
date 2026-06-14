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
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@ApplicationScoped
@Startup
public class PromptTemplateBootstrap {
	private static final Map<PromptTemplateSection, String> TEMPLATE_FILES = Map.of(
		PromptTemplateSection.PERSONALITY,
		"prompt-templates/personality.md",
		PromptTemplateSection.CORE_RULES,
		"prompt-templates/core_rules.md",
		PromptTemplateSection.SKILLS,
		"prompt-templates/skills.md",
		PromptTemplateSection.FORMATTING,
		"prompt-templates/formatting.md",
		PromptTemplateSection.WORKER_AUTONOMY,
		"prompt-templates/worker_autonomy.md"
	);
	@Inject
	EntityManager entityManager;

	@Inject
	PromptTemplateService templateService;

	@PostConstruct
	void init() {
		syncDefaults();
	}

	@Transactional
	public void syncDefaults() {
		TEMPLATE_FILES.forEach(
			(section, path) -> {
				String content = readResource(path);
				if (content == null) {
					return;
				}
				PromptTemplate existing = templateService.findTemplate(null, null, section);
				if (existing == null) {
					PromptTemplate template = new PromptTemplate();
					template.section = section;
					template.content = content;
					template.enabled = true;
					entityManager.persist(template);
					return;
				}
				if (!content.equals(existing.content)) {
					existing.content = content;
				}
				if (!existing.enabled) {
					existing.enabled = true;
				}
			}
		);
	}

	private String readResource(String path) {
		if (path == null || path.isBlank()) {
			return null;
		}
		try (InputStream stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
			if (stream == null) {
				return null;
			}
			BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
			StringBuilder builder = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) {
				builder.append(line).append("\n");
			}
			return builder.toString().trim();
		}
		catch (Exception error) {
			return null;
		}
	}
}
