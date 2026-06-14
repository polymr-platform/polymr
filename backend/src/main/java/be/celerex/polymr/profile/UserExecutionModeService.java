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

package be.celerex.polymr.profile;

import be.celerex.polymr.model.Script;
import be.celerex.polymr.model.ScriptVersion;
import be.celerex.polymr.model.SfcPage;
import be.celerex.polymr.model.SfcPageVersion;
import be.celerex.polymr.model.User;
import be.celerex.polymr.model.UserExecutionMode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.UUID;

@ApplicationScoped
public class UserExecutionModeService {
	@Inject
	EntityManager entityManager;

	public UserExecutionMode resolve(User user) {
		if (user == null || user.executionMode == null) {
			return UserExecutionMode.RELEASED;
		}
		return user.executionMode;
	}

	public ScriptVersion resolveScriptVersion(Script script, UUID userId) {
		if (script == null) {
			return null;
		}
		if (resolve(findUser(userId)) == UserExecutionMode.LATEST) {
			ScriptVersion draft = findDraftScriptVersion(script.id);
			if (draft != null) {
				return draft;
			}
		}
		return script.activeVersion;
	}

	public SfcPageVersion resolvePageVersion(SfcPage page, UUID userId) {
		if (page == null) {
			return null;
		}
		if (resolve(findUser(userId)) == UserExecutionMode.LATEST) {
			SfcPageVersion draft = findDraftPageVersion(page.id);
			if (draft != null) {
				return draft;
			}
		}
		return page.activeVersion;
	}

	private User findUser(UUID userId) {
		if (userId == null) {
			return null;
		}
		return entityManager.find(User.class, userId);
	}

	private ScriptVersion findDraftScriptVersion(UUID scriptId) {
		if (scriptId == null) {
			return null;
		}
		return entityManager.createQuery(
				"select v from ScriptVersion v where v.script.id = :scriptId and v.releasedAt is null",
				ScriptVersion.class
			)
			.setParameter("scriptId", scriptId)
			.setMaxResults(1)
			.getResultStream()
			.findFirst()
			.orElse(null);
	}

	private SfcPageVersion findDraftPageVersion(UUID pageId) {
		if (pageId == null) {
			return null;
		}
		return entityManager.createQuery(
				"select v from SfcPageVersion v where v.page.id = :pageId and v.releasedAt is null",
				SfcPageVersion.class
			)
			.setParameter("pageId", pageId)
			.setMaxResults(1)
			.getResultStream()
			.findFirst()
			.orElse(null);
	}
}
