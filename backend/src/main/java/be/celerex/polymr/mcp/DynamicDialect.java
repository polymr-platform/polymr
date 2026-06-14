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

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class DynamicDialect implements CustomDialect {
	@Inject
	Instance<CustomDialect> dialects;

	@ConfigProperty(name = "quarkus.datasource.db-kind")
	String dbKind;

	@ConfigProperty(name = "quarkus.datasource.jdbc.driver")
	java.util.Optional<String> jdbcDriver;

	private CustomDialect delegate;

	@PostConstruct
	void init() {
		String resolvedKind = resolveDialectKey();
		List<CustomDialect> candidates = dialects.stream()
			.filter(dialect -> !DynamicDialect.class.isAssignableFrom(dialect.getClass()))
			.filter(dialect -> dialect.supports(resolvedKind))
			.toList();
		if (candidates.isEmpty()) {
			throw new WebApplicationException(
				"Unsupported datasource dialect selection for db-kind=" + dbKind + ", driver="
					+ jdbcDriver.orElse(""),
				Response.Status.INTERNAL_SERVER_ERROR
			);
		}
		if (candidates.size() > 1) {
			throw new WebApplicationException(
				"Multiple custom dialects matched datasource dialect selection for db-kind=" + dbKind + ", driver="
					+ jdbcDriver.orElse(""),
				Response.Status.INTERNAL_SERVER_ERROR
			);
		}
		delegate = candidates.getFirst();
	}

	private String resolveDialectKey() {
		String normalizedKind = dbKind == null ? "" : dbKind.toLowerCase(Locale.ROOT);
		String normalizedDriver = jdbcDriver.orElse("").toLowerCase(Locale.ROOT);
		if ("other".equals(normalizedKind) && normalizedDriver.contains("duckdb")) {
			return "duckdb";
		}
		if ("other".equals(normalizedKind) && normalizedDriver.contains("postgresql")) {
			return "postgresql";
		}
		if ("other".equals(normalizedKind) && normalizedDriver.contains("sqlite")) {
			return "sqlite";
		}
		if ("other".equals(normalizedKind) && normalizedDriver.contains("hsqldb")) {
			return "hsqldb";
		}
		return normalizedKind;
	}

	@Override
	public boolean supports(String dbKind) {
		return true;
	}

	@Override
	public Query createScriptPathSearchQuery(EntityManager entityManager, UUID workspaceId, String root, String pattern, boolean countOnly) {
		return delegate.createScriptPathSearchQuery(entityManager, workspaceId, root, pattern, countOnly);
	}

	@Override
	public Query createScriptContentSearchQuery(
			EntityManager entityManager,
			UUID workspaceId,
			String root,
			String patternText,
			int offset,
			int limit) {
		return delegate.createScriptContentSearchQuery(entityManager, workspaceId, root, patternText, offset, limit);
	}

	@Override
	public Query createPagePathSearchQuery(
			EntityManager entityManager,
			UUID workspaceId,
			String root,
			String pattern,
			String pageType,
			boolean countOnly) {
		return delegate.createPagePathSearchQuery(entityManager, workspaceId, root, pattern, pageType, countOnly);
	}

	@Override
	public Query createPageContentSearchQuery(
			EntityManager entityManager,
			UUID workspaceId,
			String root,
			String patternText,
			int offset,
			int limit) {
		return delegate.createPageContentSearchQuery(entityManager, workspaceId, root, patternText, offset, limit);
	}
}
