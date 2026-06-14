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

package be.celerex.polymr.modelregistry;

import be.celerex.polymr.model.AiModel;
import be.celerex.polymr.model.AiModelType;
import be.celerex.polymr.model.TenantMembership;
import be.celerex.polymr.model.TenantRole;
import be.celerex.polymr.modelregistry.dto.ModelRequest;
import be.celerex.polymr.modelregistry.dto.ModelDefinitionResponse;
import be.celerex.polymr.modelregistry.dto.ModelProviderResponse;
import be.celerex.polymr.modelregistry.dto.ModelResponse;
import be.celerex.polymr.modelregistry.dto.ProviderProperty;
import be.celerex.polymr.modelregistry.dto.ProviderPropertyType;
import be.celerex.polymr.modelregistry.provider.AiChatModelDefinition;
import be.celerex.polymr.modelregistry.provider.AiChatModelProviderRegistry;
import be.celerex.polymr.modelregistry.provider.AiEmbeddingModelDefinition;
import be.celerex.polymr.modelregistry.provider.AiEmbeddingModelProvider;
import be.celerex.polymr.modelregistry.provider.AiModelProvider;
import be.celerex.polymr.security.SecretCipher;
import be.celerex.polymr.tenant.TenantAccessService;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/api/tenants/{tenantId}/models")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ModelResource {
	@Inject
	EntityManager entityManager;

	@Inject
	TenantAccessService tenantAccessService;

	@Inject
	SecretCipher secretCipher;

	@Inject
	ObjectMapper objectMapper;

	@Inject
	AiChatModelProviderRegistry providerRegistry;

	@Context
	SecurityContext securityContext;

	@GET
	public List<ModelResponse> list(@PathParam("tenantId") UUID tenantId) {
		requireMembership(tenantId);
		return entityManager.createQuery("select m from AiModel m where m.tenant.id = :tenantId order by m.updatedAt desc", AiModel.class)
			.setParameter("tenantId", tenantId)
			.getResultList()
			.stream()
			.map(this::toResponse)
			.toList();
	}

	@GET
	@Path("/providers")
	public List<ModelProviderResponse> listProviders(@PathParam("tenantId") UUID tenantId) {
		requireMembership(tenantId);
		java.util.Map<String, AiModelProvider> providers = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		providerRegistry.list()
			.forEach(provider -> providers.put(provider.id(), provider));
		providerRegistry.listEmbedding()
			.forEach(provider -> providers.putIfAbsent(provider.id(), provider));
		return providers.values()
			.stream()
			.map(
				provider -> {
					var chatProvider = providerRegistry.find(provider.id()).orElse(null);
					var embeddingProvider = providerRegistry.findEmbedding(provider.id()).orElse(null);
					return new ModelProviderResponse(
						provider.id(),
						provider.displayName(),
						chatProvider != null,
						embeddingProvider != null,
						provider.properties(),
						chatProvider == null
							? List.of()
							: chatProvider.supportedChatModels()
								.stream()
								.map(
									model -> new ModelDefinitionResponse(
										model.id(),
										model.displayName(),
										modelProperties(chatProvider, AiModelType.CHAT, model.properties()),
										model.contextLimit(),
										null,
										model.useCase()
									)
								)
								.toList(),
						embeddingProvider == null
							? List.of()
							: embeddingProvider.supportedEmbeddingModels()
								.stream()
								.map(
									model -> new ModelDefinitionResponse(
										model.id(),
										model.displayName(),
										modelProperties(embeddingProvider, AiModelType.EMBEDDING, model.properties()),
										null,
										model.maxSegmentSize(),
										null
									)
								)
								.toList()
					);
				}
			)
			.toList();
	}

	@POST
	@Transactional
	public ModelResponse create(@PathParam("tenantId") UUID tenantId, ModelRequest request) {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		ProviderValidation validation = validateAndNormalize(request, null);

		AiModel model = new AiModel();
		model.tenant = membership.tenant;
		model.name = request.name().trim();
		model.provider = request.provider().trim();
		model.type = validation.type;
		model.enabled = request.enabled();
		model.configJson = validation.config;

		entityManager.persist(model);
		return toResponse(model);
	}

	@PUT
	@Path("/{modelId}")
	@Transactional
	public ModelResponse update(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("modelId") UUID modelId,
			ModelRequest request) {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		AiModel model = entityManager.find(AiModel.class, modelId);
		if (model == null || !model.tenant.id.equals(tenantId)) {
			throw new WebApplicationException("Model not found", Response.Status.NOT_FOUND);
		}
		ProviderValidation validation = validateAndNormalize(request, model);
		model.name = request.name().trim();
		model.provider = request.provider().trim();
		model.type = validation.type;
		model.enabled = request.enabled();
		model.configJson = validation.config;
		return toResponse(model);
	}

	@DELETE
	@Path("/{modelId}")
	@Transactional
	public Response delete(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("modelId") UUID modelId) {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		AiModel model = entityManager.find(AiModel.class, modelId);
		if (model == null || !model.tenant.id.equals(tenantId)) {
			throw new WebApplicationException("Model not found", Response.Status.NOT_FOUND);
		}
		entityManager.remove(model);
		return Response.noContent().build();
	}

	private ProviderValidation validateAndNormalize(ModelRequest request, AiModel existing) {
		if (request == null || request.name() == null || request.name().isBlank()) {
			throw new WebApplicationException("Name is required", Response.Status.BAD_REQUEST);
		}
		if (request.provider() == null || request.provider().isBlank()) {
			throw new WebApplicationException("Provider is required", Response.Status.BAD_REQUEST);
		}
		AiModelType type = parseModelType(request.type(), existing == null ? null : existing.type);
		if (type == AiModelType.CHAT && providerRegistry.find(request.provider().trim()).isEmpty()) {
			throw new WebApplicationException("Provider is not supported", Response.Status.BAD_REQUEST);
		}
		if (type == AiModelType.EMBEDDING
				&& providerRegistry.findEmbedding(request.provider().trim())
					.isEmpty()) {
			throw new WebApplicationException("Provider does not support embedding models", Response.Status.BAD_REQUEST);
		}
		JsonNode configNode = request.config();
		if (configNode == null || !configNode.isObject()) {
			throw new WebApplicationException("Config is required", Response.Status.BAD_REQUEST);
		}
		AiModelProvider provider = type == AiModelType.CHAT
			? providerRegistry.find(request.provider().trim()).orElseThrow()
			: providerRegistry.findEmbedding(request.provider().trim()).orElseThrow();
		ObjectNode config = objectMapper.createObjectNode();
		ObjectNode existingConfig = existing != null && existing.configJson != null && existing.configJson.isObject()
			? (ObjectNode) existing.configJson
			: objectMapper.createObjectNode();

		String modelId = configNode.path("model_id").asText(null);
		if (modelId == null || modelId.isBlank()) {
			throw new WebApplicationException("Model id is required", Response.Status.BAD_REQUEST);
		}
		List<ProviderProperty> properties = new java.util.ArrayList<>(provider.properties());
		if (type == AiModelType.CHAT) {
			properties.addAll(be.celerex.polymr.modelregistry.provider.ModelBehaviorConfig.properties());
			AiChatModelDefinition modelDefinition = ((be.celerex.polymr.modelregistry.provider.AiChatModelProvider) provider)
				.resolveChatModel(modelId)
				.orElse(null);
			if (modelDefinition != null) {
				properties.addAll(modelDefinition.properties());
			}
		}
		else {
			AiEmbeddingModelDefinition modelDefinition = ((AiEmbeddingModelProvider) provider)
				.resolveEmbeddingModel(modelId)
				.orElse(null);
			if (modelDefinition != null) {
				properties.addAll(modelDefinition.properties());
			}
		}
		boolean hasModelId = false;
		for (ProviderProperty property : properties) {
			if ("model_id".equals(property.key())) {
				hasModelId = true;
			}
			JsonNode valueNode = configNode.get(property.key());
			boolean hasExisting = existingConfig.has(property.key());
			boolean hasNewValue = valueNode != null && !valueNode.isNull() && !valueNode.asText().isBlank();
			if (property.required() && !hasNewValue && !hasExisting) {
				throw new WebApplicationException(property.label() + " is required", Response.Status.BAD_REQUEST);
			}
			if (property.type() == ProviderPropertyType.SECRET) {
				if (valueNode != null && !valueNode.isNull() && !valueNode.asText().isBlank()) {
					SecretCipher.EncryptedSecret encrypted = secretCipher.encrypt(valueNode.asText());
					ObjectNode secretNode = objectMapper.createObjectNode();
					secretNode.put("ciphertext", encrypted.ciphertext());
					secretNode.put("nonce", encrypted.nonce());
					secretNode.put("hint", maskSecret(valueNode.asText()));
					config.set(property.key(), secretNode);
				}
				else if (existingConfig.has(property.key())) {
					config.set(property.key(), existingConfig.get(property.key()));
				}
			}
			else if (valueNode != null) {
				config.set(property.key(), valueNode);
			}
			else if (existingConfig.has(property.key())) {
				config.set(property.key(), existingConfig.get(property.key()));
			}
			else if (property.defaultValue() != null) {
				config.put(property.key(), property.defaultValue());
			}
		}
		if (!hasModelId) {
			config.put("model_id", modelId.trim());
		}
		if (type == AiModelType.EMBEDDING) {
			AiEmbeddingModelDefinition modelDefinition = ((AiEmbeddingModelProvider) provider)
				.resolveEmbeddingModel(modelId)
				.orElse(null);
			Integer resolvedMaxSegmentSize = modelDefinition == null ? null : modelDefinition.maxSegmentSize();
			JsonNode valueNode = config.get("max_segment_size");
			Integer configuredMaxSegmentSize = valueNode == null || valueNode.isNull() ? null : valueNode.asInt();
			if (configuredMaxSegmentSize != null && configuredMaxSegmentSize > 0) {
				config.put("max_segment_size", configuredMaxSegmentSize);
			}
			else if (resolvedMaxSegmentSize != null) {
				config.put("max_segment_size", resolvedMaxSegmentSize);
			}
			else {
				throw new WebApplicationException("Max segment size is required", Response.Status.BAD_REQUEST);
			}
		}
		return new ProviderValidation(type, config);
	}

	private AiModelType parseModelType(String requestedType, AiModelType existingType) {
		String value = requestedType == null || requestedType.isBlank()
			? (existingType == null ? AiModelType.CHAT.name() : existingType.name())
			: requestedType.trim().toUpperCase();
		try {
			return AiModelType.valueOf(value);
		}
		catch (IllegalArgumentException ex) {
			throw new WebApplicationException("Model type is not supported", Response.Status.BAD_REQUEST);
		}
	}

	private List<ProviderProperty> modelProperties(AiModelProvider provider, AiModelType type, List<ProviderProperty> modelProperties) {
		List<ProviderProperty> properties = new java.util.ArrayList<>(provider.properties());
		if (type == AiModelType.CHAT) {
			properties.addAll(be.celerex.polymr.modelregistry.provider.ModelBehaviorConfig.properties());
		}
		properties.addAll(modelProperties);
		return properties;
	}

	private ModelResponse toResponse(AiModel model) {
		ObjectNode view = objectMapper.createObjectNode();
		if (model.configJson != null && model.configJson.isObject()) {
			model.configJson
				.fields()
				.forEachRemaining(
					entry -> {
						if (entry.getValue().isObject() && entry.getValue().has("ciphertext")) {
							ObjectNode secretView = objectMapper.createObjectNode();
							secretView.put("present", true);
							if (entry.getValue().has("hint")) {
								secretView.put("hint", entry.getValue().get("hint").asText());
							}
							view.set(entry.getKey(), secretView);
						}
						else {
							view.set(entry.getKey(), entry.getValue());
						}
					}
				);
		}
		return new ModelResponse(
			model.id,
			model.name,
			model.provider,
			model.type == null ? null : model.type.name(),
			model.enabled,
			view
		);
	}

	private String maskSecret(String value) {
		if (value == null || value.length() <= 8) {
			return "***";
		}
		return value.substring(0, 4) + "..." + value.substring(value.length() - 4);
	}

	private TenantMembership requireMembership(UUID tenantId) {
		return tenantAccessService.requireMembership(tenantId, requireUserId());
	}

	private UUID requireUserId() {
		if (securityContext == null || securityContext.getUserPrincipal() == null) {
			throw new WebApplicationException("Unauthorized", Response.Status.UNAUTHORIZED);
		}
		return UUID.fromString(securityContext.getUserPrincipal().getName());
	}

	private record ProviderValidation(AiModelType type, ObjectNode config) {}
}
