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

package be.celerex.polymr.automation;

import org.eclipse.microprofile.context.ManagedExecutor;
import io.smallrye.context.api.ManagedExecutorConfig;
import org.eclipse.microprofile.context.ThreadContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;
import org.jboss.logging.Logger;

@ApplicationScoped
public class TitleGenerationService {
	private static final Logger LOGGER = Logger.getLogger(TitleGenerationService.class);
	@Inject
	TitleGenerationWorker worker;

	@Inject
	@ManagedExecutorConfig(cleared = ThreadContext.TRANSACTION)
	ManagedExecutor titleExecutor;

	public void queueTitleGeneration(UUID sessionId, String userText) {
		if (sessionId == null || userText == null || userText.isBlank()) {
			return;
		}
		titleExecutor.runAsync(
			() -> {
				try {
					worker.generateTitle(sessionId, userText);
				}
				catch (Exception ex) {
					LOGGER.errorf(ex, "Async title generation failed for session %s", sessionId);
					throw ex;
				}
			}
		);
	}
}
