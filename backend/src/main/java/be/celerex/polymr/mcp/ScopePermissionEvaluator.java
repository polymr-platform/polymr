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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@ApplicationScoped
public class ScopePermissionEvaluator {
	public boolean isAllowed(List<String> toolScopes, List<String> allowScopes, List<String> denyScopes) {
		if (toolScopes == null || toolScopes.isEmpty()) {
			return true;
		}
		List<String> allows = allowScopes == null ? List.of() : allowScopes;
		List<String> denies = denyScopes == null ? List.of() : denyScopes;
		boolean anyAllowed = allows.isEmpty();
		for (String toolScope : toolScopes) {
			Match allow = bestMatch(toolScope, allows);
			Match deny = bestMatch(toolScope, denies);
			if (deny != null && (allow == null || allow.specificity <= deny.specificity)) {
				return false;
			}
			if (allow != null) {
				anyAllowed = true;
			}
		}
		return anyAllowed;
	}

	private Match bestMatch(String scope, List<String> candidates) {
		if (scope == null || scope.isBlank() || candidates == null || candidates.isEmpty()) {
			return null;
		}
		List<Match> matches = new ArrayList<>();
		for (String candidate : candidates) {
			if (candidate == null || candidate.isBlank()) {
				continue;
			}
			if (scope.equals(candidate) || scope.startsWith(candidate + ":")) {
				matches.add(new Match(candidate, candidate.split(":").length));
			}
		}
		return matches.stream()
			.max(Comparator.comparingInt(match -> match.specificity))
			.orElse(null);
	}

	private record Match(String scope, int specificity) {}
}
