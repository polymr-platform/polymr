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

package be.celerex.polymr.persona;

import be.celerex.polymr.model.Persona;
import be.celerex.polymr.model.TenantMembership;
import be.celerex.polymr.model.TenantRole;
import be.celerex.polymr.model.User;
import be.celerex.polymr.persona.dto.PersonaRequest;
import be.celerex.polymr.persona.dto.PersonaResponse;
import be.celerex.polymr.tenant.TenantAccessService;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
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
import java.util.List;
import java.util.UUID;

@Path("/api/tenants/{tenantId}/personas")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class PersonaResource {
	@Inject
	EntityManager entityManager;

	@Inject
	TenantAccessService tenantAccessService;

	@Context
	SecurityContext securityContext;

	@GET
	public List<PersonaResponse> list(@PathParam("tenantId") UUID tenantId) {
		TenantMembership membership = requireMembership(tenantId);
		return entityManager.createQuery(
				"select new be.celerex.polymr.persona.dto.PersonaResponse(p.id, p.name, p.description, "
					+ "p.promptText) "
					+ "from Persona p where p.tenant.id = :tenantId",
				PersonaResponse.class
			)
			.setParameter("tenantId", tenantId)
			.getResultList();
	}

	@POST
	@Transactional
	public PersonaResponse create(@PathParam("tenantId") UUID tenantId, PersonaRequest request) {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		if (request == null || request.name() == null || request.name().isBlank()) {
			throw new WebApplicationException("Name is required", Response.Status.BAD_REQUEST);
		}
		Persona persona = new Persona();
		persona.tenant = membership.tenant;
		persona.name = request.name().trim();
		persona.description = request.description();
		persona.promptText = normalizePrompt(request.prompt_text());
		entityManager.persist(persona);
		return new PersonaResponse(persona.id, persona.name, persona.description, persona.promptText);
	}

	@PUT
	@Path("/{personaId}")
	@Transactional
	public PersonaResponse update(
			@PathParam("tenantId") UUID tenantId,
			@PathParam("personaId") UUID personaId,
			PersonaRequest request) {
		TenantMembership membership = requireMembership(tenantId);
		tenantAccessService.requireRole(membership, TenantRole.OWNER, TenantRole.ADMIN);
		Persona persona = entityManager.find(Persona.class, personaId);
		if (persona == null || !persona.tenant.id.equals(tenantId)) {
			throw new WebApplicationException("Persona not found", Response.Status.NOT_FOUND);
		}
		if (request == null || request.name() == null || request.name().isBlank()) {
			throw new WebApplicationException("Name is required", Response.Status.BAD_REQUEST);
		}
		persona.name = request.name().trim();
		persona.description = request.description();
		persona.promptText = normalizePrompt(request.prompt_text());
		return new PersonaResponse(persona.id, persona.name, persona.description, persona.promptText);
	}

	private String normalizePrompt(String prompt) {
		if (prompt == null) {
			return null;
		}
		String trimmed = prompt.trim();
		return trimmed.isBlank() ? null : trimmed;
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
