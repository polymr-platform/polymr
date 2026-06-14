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

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.UUID;

public interface CustomDialect {
	boolean supports(String dbKind);

	Query createScriptPathSearchQuery(EntityManager entityManager, UUID workspaceId, String root, String pattern, boolean countOnly);

	Query createScriptContentSearchQuery(EntityManager entityManager, UUID workspaceId, String root, String patternText, int offset, int limit);

	Query createPagePathSearchQuery(EntityManager entityManager, UUID workspaceId, String root, String pattern, String pageType, boolean countOnly);

	Query createPageContentSearchQuery(EntityManager entityManager, UUID workspaceId, String root, String patternText, int offset, int limit);
}
