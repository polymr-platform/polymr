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

package be.celerex.polymr.tenant;

import be.celerex.polymr.model.TenantMembership;
import be.celerex.polymr.model.TenantRole;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.util.UUID;

@ApplicationScoped
public class TenantAccessService {
	private final EntityManager entityManager;

	public TenantAccessService(EntityManager entityManager) {
		this.entityManager = entityManager;
	}

	@Transactional
	public TenantMembership requireMembership(UUID tenantId, UUID userId) {
		return entityManager.createQuery(
				"select tm from TenantMembership tm where tm.user.id = :userId and tm.tenant.id = :tenantId",
				TenantMembership.class
			)
			.setParameter("userId", userId)
			.setParameter("tenantId", tenantId)
			.getResultStream()
			.findFirst()
			.orElseThrow(() -> new WebApplicationException("Tenant not found", Response.Status.NOT_FOUND));
	}

	public void requireRole(TenantMembership membership, TenantRole...roles) {
		for (TenantRole role : roles) {
			if (membership.role == role) {
				return;
			}
		}
		throw new WebApplicationException("Forbidden", Response.Status.FORBIDDEN);
	}
}
