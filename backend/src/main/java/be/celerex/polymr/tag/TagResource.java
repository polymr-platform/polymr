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

package be.celerex.polymr.tag;

import be.celerex.polymr.model.McpServerPolicy;
import be.celerex.polymr.model.TagCategory;
import be.celerex.polymr.model.TagValue;
import be.celerex.polymr.model.TenantMembership;
import be.celerex.polymr.model.TenantRole;
import be.celerex.polymr.model.Workspace;
import be.celerex.polymr.model.WorkspaceTagSelection;
import be.celerex.polymr.tag.dto.TagCategoryRequest;
import be.celerex.polymr.tag.dto.TagCategoryResponse;
import be.celerex.polymr.tag.dto.TagValueRequest;
import be.celerex.polymr.tag.dto.TagValueResponse;
import be.celerex.polymr.tenant.TenantAccessService;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
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
import java.text.Normalizer;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Path("/api/tenants/{tenantId}/workspaces/{workspaceId}/tags")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class TagResource {
	@Inject
	EntityManager entityManager;

	@Inject
	TenantAccessService tenantAccessService;

	@Context
	SecurityContext securityContext;

	@ConfigProperty(name = "polymr.tags.hard-delete-enabled", defaultValue = "true")
	boolean hardDeleteEnabled;

	@GET
	public List<TagCategoryResponse> list(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId) {
		requireMembership(tenantId);
		requireWorkspace(tenantId, workspaceId);
		List<TagCategory> categories = entityManager.createQuery(
				"select c from TagCategory c where c.tenant.id = :tenantId and c.workspace.id "
					+ "= :workspaceId order by c.priority asc, lower(c.name) asc",
				TagCategory.class
			)
			.setParameter("tenantId", tenantId)
			.setParameter("workspaceId", workspaceId)
			.getResultList();
		return categories.stream().map(this::toResponse).collect(Collectors.toList());
	}

	@POST
	@Transactional
	public TagCategoryResponse createCategory(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			TagCategoryRequest request) {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		Workspace workspace = requireWorkspace(tenantId, workspaceId);
		validateCategoryRequest(request, true);
		String slug = normalizeSlug(request.slug(), request.name());
		ensureUniqueCategorySlug(tenantId, workspaceId, slug, null);
		TagCategory category = new TagCategory();
		category.tenant = workspace.tenant;
		category.workspace = workspace;
		category.name = request.name().trim();
		category.slug = slug;
		category.priority = request.priority() == null ? 0 : request.priority();
		entityManager.persist(category);
		return toResponse(category);
	}

	@PUT
	@Path("/{categoryId}")
	@Transactional
	public TagCategoryResponse updateCategory(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("categoryId") UUID categoryId,
			TagCategoryRequest request) {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		requireWorkspace(tenantId, workspaceId);
		TagCategory category = requireCategory(tenantId, workspaceId, categoryId);
		validateCategoryRequest(request, false);
		if (request.name() != null && !request.name().isBlank()) {
			category.name = request.name().trim();
		}
		if (request.priority() != null) {
			category.priority = request.priority();
		}
		return toResponse(category);
	}

	@DELETE
	@Path("/{categoryId}")
	@Transactional
	public void deleteCategory(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("categoryId") UUID categoryId) {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		requireWorkspace(tenantId, workspaceId);
		TagCategory category = requireCategory(tenantId, workspaceId, categoryId);
		entityManager.createQuery("delete from McpServerPolicy p where p.tag.category.id = :categoryId")
			.setParameter("categoryId", category.id)
			.executeUpdate();
		entityManager.createQuery("update WorkspaceTagSelection s set s.value = null where s.category.id = :categoryId")
			.setParameter("categoryId", category.id)
			.executeUpdate();
		if (hardDeleteEnabled) {
			entityManager.createQuery("delete from TagValue v where v.category.id = :categoryId")
				.setParameter("categoryId", category.id)
				.executeUpdate();
			entityManager.remove(category);
		}
		else {
			Instant now = Instant.now();
			List<TagValue> values = entityManager.createQuery("select v from TagValue v where v.category.id = :categoryId", TagValue.class)
				.setParameter("categoryId", category.id)
				.getResultList();
			values.forEach(value -> value.deletedAt = now);
			category.deletedAt = now;
		}
	}

	@POST
	@Path("/{categoryId}/values")
	@Transactional
	public TagValueResponse createValue(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("categoryId") UUID categoryId,
			TagValueRequest request) {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		requireWorkspace(tenantId, workspaceId);
		TagCategory category = requireCategory(tenantId, workspaceId, categoryId);
		validateValueRequest(request, true);
		String slug = normalizeSlug(request.slug(), request.name());
		ensureUniqueValueSlug(category.id, slug, null);
		TagValue value = new TagValue();
		value.category = category;
		value.name = request.name().trim();
		value.slug = slug;
		value.priority = request.priority() == null ? 0 : request.priority();
		entityManager.persist(value);
		return toValueResponse(value);
	}

	@PUT
	@Path("/{categoryId}/values/{valueId}")
	@Transactional
	public TagValueResponse updateValue(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("categoryId") UUID categoryId,
			@PathParam("valueId") UUID valueId,
			TagValueRequest request) {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		requireWorkspace(tenantId, workspaceId);
		requireCategory(tenantId, workspaceId, categoryId);
		TagValue value = requireValue(categoryId, valueId);
		validateValueRequest(request, false);
		if (request.name() != null && !request.name().isBlank()) {
			value.name = request.name().trim();
		}
		if (request.priority() != null) {
			value.priority = request.priority();
		}
		return toValueResponse(value);
	}

	@DELETE
	@Path("/{categoryId}/values/{valueId}")
	@Transactional
	public void deleteValue(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("workspaceId") UUID workspaceId,
			@PathParam("categoryId") UUID categoryId,
			@PathParam("valueId") UUID valueId) {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		requireWorkspace(tenantId, workspaceId);
		requireCategory(tenantId, workspaceId, categoryId);
		TagValue value = requireValue(categoryId, valueId);
		entityManager.createQuery("delete from McpServerPolicy p where p.tag.id = :valueId")
			.setParameter("valueId", value.id)
			.executeUpdate();
		entityManager.createQuery("update WorkspaceTagSelection s set s.value = null where s.value.id = :valueId")
			.setParameter("valueId", value.id)
			.executeUpdate();
		if (hardDeleteEnabled) {
			entityManager.remove(value);
		}
		else {
			value.deletedAt = Instant.now();
		}
	}

	private void validateCategoryRequest(TagCategoryRequest request, boolean creating) {
		if (creating && (request == null || request.name() == null || request.name().isBlank())) {
			throw new WebApplicationException("Category name is required", Response.Status.BAD_REQUEST);
		}
	}

	private void validateValueRequest(TagValueRequest request, boolean creating) {
		if (creating && (request == null || request.name() == null || request.name().isBlank())) {
			throw new WebApplicationException("Tag value name is required", Response.Status.BAD_REQUEST);
		}
	}

	private void ensureUniqueCategorySlug(UUID tenantId, UUID workspaceId, String slug, UUID excludedId) {
		List<TagCategory> matches = entityManager.createQuery(
				"select c from TagCategory c where c.tenant.id = :tenantId and c.workspace.id "
					+ "= :workspaceId and c.slug = :slug and c.deletedAt is null",
				TagCategory.class
			)
			.setParameter("tenantId", tenantId)
			.setParameter("workspaceId", workspaceId)
			.setParameter("slug", slug)
			.getResultList();
		boolean conflict = matches.stream()
			.anyMatch(category -> excludedId == null || !category.id.equals(excludedId));
		if (conflict) {
			throw new WebApplicationException("Category slug already exists", Response.Status.CONFLICT);
		}
	}

	private void ensureUniqueValueSlug(UUID categoryId, String slug, UUID excludedId) {
		List<TagValue> matches = entityManager.createQuery(
				"select v from TagValue v where v.category.id = :categoryId and v.slug = "
					+ ":slug and v.deletedAt is null",
				TagValue.class
			)
			.setParameter("categoryId", categoryId)
			.setParameter("slug", slug)
			.getResultList();
		boolean conflict = matches.stream()
			.anyMatch(value -> excludedId == null || !value.id.equals(excludedId));
		if (conflict) {
			throw new WebApplicationException("Tag value slug already exists", Response.Status.CONFLICT);
		}
	}

	private String normalizeSlug(String explicitSlug, String fallbackName) {
		String source = explicitSlug != null && !explicitSlug.isBlank() ? explicitSlug : fallbackName;
		if (source == null || source.isBlank()) {
			throw new WebApplicationException("Slug is required", Response.Status.BAD_REQUEST);
		}
		String normalized = Normalizer.normalize(source, Normalizer.Form.NFD)
			.replaceAll("\\p{M}+", "")
			.toLowerCase()
			.replaceAll("[^a-z0-9]+", "-")
			.replaceAll("^-+|-+$", "");
		if (normalized.isBlank()) {
			throw new WebApplicationException("Slug is required", Response.Status.BAD_REQUEST);
		}
		return normalized;
	}

	private TagCategoryResponse toResponse(TagCategory category) {
		List<TagValueResponse> values = entityManager.createQuery(
				"select v from TagValue v where v.category.id = :categoryId order by "
					+ "v.priority asc, lower(v.name) asc",
				TagValue.class
			)
			.setParameter("categoryId", category.id)
			.getResultList()
			.stream()
			.map(this::toValueResponse)
			.collect(Collectors.toList());
		return new TagCategoryResponse(category.id, category.name, category.slug, category.priority, category.deletedAt != null, values);
	}

	private TagValueResponse toValueResponse(TagValue value) {
		return new TagValueResponse(value.id, value.name, value.slug, value.priority, value.deletedAt != null);
	}

	private TagCategory requireCategory(UUID tenantId, UUID workspaceId, UUID categoryId) {
		TagCategory category = entityManager.find(TagCategory.class, categoryId);
		if (category == null
				|| category.tenant == null
				|| !category.tenant.id.equals(tenantId)
				|| category.workspace == null
				|| !category.workspace.id.equals(workspaceId)) {
			throw new WebApplicationException("Tag category not found", Response.Status.NOT_FOUND);
		}
		return category;
	}

	private TagValue requireValue(UUID categoryId, UUID valueId) {
		TagValue value = entityManager.find(TagValue.class, valueId);
		if (value == null || value.category == null || !value.category.id.equals(categoryId)) {
			throw new WebApplicationException("Tag value not found", Response.Status.NOT_FOUND);
		}
		return value;
	}

	private Workspace requireWorkspace(UUID tenantId, UUID workspaceId) {
		Workspace workspace = entityManager.find(Workspace.class, workspaceId);
		if (workspace == null || !workspace.tenant.id.equals(tenantId)) {
			throw new WebApplicationException("Workspace not found", Response.Status.NOT_FOUND);
		}
		return workspace;
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
}
