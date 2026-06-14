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

import be.celerex.polymr.lock.LockService;
import be.celerex.polymr.model.WorkflowDefinition;
import be.celerex.polymr.model.WorkflowDefinitionVersion;
import be.celerex.polymr.model.Workspace;
import be.celerex.polymr.model.WorkflowStartTrigger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.time.Instant;
import java.util.List;
import org.jboss.logging.Logger;

@ApplicationScoped
@Startup
public class ConversationWorkflowStartup {
	private static final Logger LOGGER = Logger.getLogger(ConversationWorkflowStartup.class);
	@Inject
	EntityManager entityManager;

	@Inject
	ObjectMapper objectMapper;

	@Inject
	LockService lockService;

	@Inject
	WorkflowDefinitionService workflowDefinitionService;

	@PostConstruct
	void ensureConversationWorkflow() {
		LOGGER.info("Ensuring Conversation workflow definition exists");
		if (!lockService.tryAcquire("workflow-definition", "conversation")) {
			return;
		}
		try {
			createIfMissing();
		}
		finally {
			lockService.release("workflow-definition", "conversation");
		}
	}

	@Transactional
	void createIfMissing() {
		ObjectNode definitionJson = loadConversationDefinition();
		List<be.celerex.polymr.model.Workspace> workspaces = entityManager.createQuery("select w from Workspace w", be.celerex.polymr.model.Workspace.class)
			.getResultList();
		for (be.celerex.polymr.model.Workspace workspace : workspaces) {
			ensureForWorkspace(workspace, definitionJson);
		}
	}

	@Transactional
	public void ensureForWorkspace(Workspace workspace) {
		if (workspace == null) {
			return;
		}
		ObjectNode definitionJson = loadConversationDefinition();
		ensureForWorkspace(workspace, definitionJson);
	}

	private void ensureForWorkspace(Workspace workspace, ObjectNode definitionJson) {
		List<WorkflowDefinition> existing = entityManager.createQuery(
				"select w from WorkflowDefinition w where w.workspace.id = :workspaceId and w.deletedAt is null",
				WorkflowDefinition.class
			)
			.setParameter("workspaceId", workspace.id)
			.getResultList();
		WorkflowDefinition conversation = existing.stream()
			.filter(workflowDefinitionService::isConversationDefinition)
			.findFirst()
			.orElse(null);
		if (conversation != null) {
			if (!isDefinitionUpToDate(conversation, definitionJson)) {
				WorkflowDefinitionVersion active = findActiveReleased(conversation.id);
				if (active == null) {
					WorkflowDefinitionVersion version = new WorkflowDefinitionVersion();
					version.workflowDefinition = conversation;
					version.definitionJson = definitionJson.deepCopy();
					version.releasedAt = Instant.now();
					entityManager.persist(version);
				}
				else {
					active.definitionJson = definitionJson.deepCopy();
				}
				conversation.startTrigger = WorkflowStartTrigger.USER_PROMPT;
				LOGGER.warnf("Updated Conversation workflow definition for workspace %s", workspace.id);
			}
			return;
		}
		WorkflowDefinition definition = new WorkflowDefinition();
		definition.tenant = workspace.tenant;
		definition.workspace = workspace;
		definition.name = "Conversation";
		definition.description = "Default conversational workflow";
		definition.startTrigger = WorkflowStartTrigger.USER_PROMPT;
		entityManager.persist(definition);
		WorkflowDefinitionVersion version = new WorkflowDefinitionVersion();
		version.workflowDefinition = definition;
		version.definitionJson = definitionJson.deepCopy();
		version.releasedAt = Instant.now();
		entityManager.persist(version);
		LOGGER.warnf("Created default Conversation workflow definition for workspace %s", workspace.id);
	}

	private boolean isDefinitionUpToDate(WorkflowDefinition definition, ObjectNode expected) {
		if (definition == null) {
			return false;
		}
		if (!workflowDefinitionService.isConversationDefinition(definition)) {
			return true;
		}
		WorkflowDefinitionVersion active = findActiveReleased(definition.id);
		JsonNode json = active == null ? null : active.definitionJson;
		return json != null && json.equals(expected);
	}

	private WorkflowDefinitionVersion findActiveReleased(UUID workflowId) {
		return entityManager.createQuery(
				"select v from WorkflowDefinitionVersion v where v.workflowDefinition.id = :workflowId "
					+ "and v.releasedAt is not null and v.deprecatedAt is null",
				WorkflowDefinitionVersion.class
			)
			.setParameter("workflowId", workflowId)
			.setMaxResults(1)
			.getResultStream()
			.findFirst()
			.orElse(null);
	}

	private ObjectNode loadConversationDefinition() {
		try (InputStream input = ConversationWorkflowStartup.class .getResourceAsStream("/workflows/conversation.json")) {
			if (input == null) {
				throw new IllegalStateException("Missing workflow definition resource: /workflows/conversation.json");
			}
			ObjectNode definition = objectMapper.readValue(input, ObjectNode.class);
			if (definition == null) {
				throw new IllegalStateException("Conversation workflow definition is empty");
			}
			return definition;
		}
		catch (IOException exception) {
			throw new IllegalStateException("Failed to load conversation workflow definition", exception);
		}
	}
}
