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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.Locale;
import java.util.UUID;

@ApplicationScoped
public class HsqldbDialect implements CustomDialect {
	@Override
	public boolean supports(String dbKind) {
		return dbKind != null && dbKind.toLowerCase(Locale.ROOT).contains("hsqldb");
	}

	@Override
	public Query createScriptPathSearchQuery(EntityManager entityManager, UUID workspaceId, String root, String pattern, boolean countOnly) {
		String selectClause = countOnly ? "count(*)" : "path";
		String sql = "select " + selectClause + " from ("
			+ " select case"
			+ " when s.namespace is null or trim(s.namespace) = '' then coalesce(nullif(trim(s.name), "
			+ "''), s.slug) || '.groovy'"
			+ " else s.namespace || '/' || coalesce(nullif(trim(s.name), ''), s.slug) || '.groovy'"
			+ " end as path"
			+ " from scripts s"
			+ " where s.workspace_id = :workspaceId and s.disabled = false"
			+ " ) script_paths"
			+ " where (:root is null or path = :root or path like :rootPrefix)"
			+ " and (:pattern is null or lower(path) like :patternLike)"
			+ (countOnly ? "" : " order by lower(path)");
		Query query = entityManager.createNativeQuery(sql);
		query.setParameter("workspaceId", workspaceId);
		query.setParameter("root", root);
		query.setParameter("rootPrefix", root == null ? null : root + "/%");
		query.setParameter("pattern", pattern == null ? null : pattern.toLowerCase(Locale.ROOT));
		query.setParameter("patternLike", pattern == null ? null : "%" + pattern.toLowerCase(Locale.ROOT) + "%");
		return query;
	}

	@Override
	public Query createScriptContentSearchQuery(
			EntityManager entityManager,
			UUID workspaceId,
			String root,
			String patternText,
			int offset,
			int limit) {
		String sql = "select path, source from ("
			+ " select case"
			+ " when s.namespace is null or trim(s.namespace) = '' then coalesce(nullif(trim(s.name), "
			+ "''), s.slug) || '.groovy'"
			+ " else s.namespace || '/' || coalesce(nullif(trim(s.name), ''), s.slug) || '.groovy'"
			+ " end as path,"
			+ " coalesce(draft.source_groovy, active.source_groovy, '') as source"
			+ " from scripts s"
			+ " left join script_versions draft on draft.script_id = s.id and draft.released_at is null"
			+ " left join script_versions active on active.id = s.active_version_id"
			+ " where s.workspace_id = :workspaceId and s.disabled = false"
			+ " ) searchable_scripts"
			+ " where (:root is null or path = :root or path like :rootPrefix)"
			+ " and regexp_like(source, :pattern)"
			+ " order by regexp_count(source, :pattern) desc, lower(path)"
			+ " limit :limit offset :offset";
		Query query = entityManager.createNativeQuery(sql);
		query.setParameter("workspaceId", workspaceId);
		query.setParameter("root", root);
		query.setParameter("rootPrefix", root == null ? null : root + "/%");
		query.setParameter("pattern", patternText);
		query.setParameter("offset", offset);
		query.setParameter("limit", limit);
		return query;
	}

	@Override
	public Query createPagePathSearchQuery(
			EntityManager entityManager,
			UUID workspaceId,
			String root,
			String pattern,
			String pageType,
			boolean countOnly) {
		String selectClause = countOnly ? "count(*)" : "path";
		String sql = "select " + selectClause + " from ("
			+ " select case"
			+ " when p.namespace is null or trim(p.namespace) = '' then coalesce(nullif(trim(p.name), "
			+ "''), p.slug) || '.vue'"
			+ " else p.namespace || '/' || coalesce(nullif(trim(p.name), ''), p.slug) || '.vue'"
			+ " end as path"
			+ " from sfc_pages p"
			+ " where p.workspace_id = :workspaceId and p.disabled = false"
			+ " and (:pageType is null or upper(cast(p.page_type as varchar(255))) = :pageType)"
			+ " ) page_paths"
			+ " where (:root is null or path = :root or path like :rootPrefix)"
			+ " and (:pattern is null or regexp_like(path, :pattern, 'i'))"
			+ (countOnly ? "" : " order by lower(path)");
		Query query = entityManager.createNativeQuery(sql);
		query.setParameter("workspaceId", workspaceId);
		query.setParameter("pageType", pageType);
		query.setParameter("root", root);
		query.setParameter("rootPrefix", root == null ? null : root + "/%");
		query.setParameter("pattern", pattern);
		return query;
	}

	@Override
	public Query createPageContentSearchQuery(
			EntityManager entityManager,
			UUID workspaceId,
			String root,
			String patternText,
			int offset,
			int limit) {
		String sql = "select path, source from ("
			+ " select case"
			+ " when p.namespace is null or trim(p.namespace) = '' then coalesce(nullif(trim(p.name), "
			+ "''), p.slug) || '.vue'"
			+ " else p.namespace || '/' || coalesce(nullif(trim(p.name), ''), p.slug) || '.vue'"
			+ " end as path,"
			+ " coalesce(draft.source_sfc, active.source_sfc, '') as source"
			+ " from sfc_pages p"
			+ " left join sfc_page_versions draft on draft.page_id = p.id and draft.released_at is null"
			+ " left join sfc_page_versions active on active.id = p.active_version_id"
			+ " where p.workspace_id = :workspaceId and p.disabled = false"
			+ " ) searchable_pages"
			+ " where (:root is null or path = :root or path like :rootPrefix)"
			+ " and regexp_like(source, :pattern)"
			+ " order by regexp_count(source, :pattern) desc, lower(path)"
			+ " limit :limit offset :offset";
		Query query = entityManager.createNativeQuery(sql);
		query.setParameter("workspaceId", workspaceId);
		query.setParameter("root", root);
		query.setParameter("rootPrefix", root == null ? null : root + "/%");
		query.setParameter("pattern", patternText);
		query.setParameter("offset", offset);
		query.setParameter("limit", limit);
		return query;
	}
}
