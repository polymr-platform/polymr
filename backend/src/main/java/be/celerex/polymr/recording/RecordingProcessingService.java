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

package be.celerex.polymr.recording;

import org.eclipse.microprofile.context.ManagedExecutor;
import io.smallrye.context.api.ManagedExecutorConfig;
import org.eclipse.microprofile.context.ThreadContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;
import org.jboss.logging.Logger;

@ApplicationScoped
public class RecordingProcessingService {
	private static final Logger LOGGER = Logger.getLogger(RecordingProcessingService.class);
	@Inject
	RecordingProcessingWorker worker;

	@Inject
	@ManagedExecutorConfig(cleared = ThreadContext.TRANSACTION)
	ManagedExecutor processingExecutor;

	public void process(UUID recordingId) {
		if (recordingId == null) {
			return;
		}
		processingExecutor.runAsync(
			() -> {
				try {
					worker.process(recordingId);
				}
				catch (Exception ex) {
					LOGGER.errorf(ex, "Async recording processing failed for recording %s", recordingId);
					throw ex;
				}
			}
		);
	}
}
