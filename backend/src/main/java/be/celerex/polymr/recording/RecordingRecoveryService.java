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

import be.celerex.polymr.model.Recording;
import be.celerex.polymr.model.RecordingStatus;
import io.quarkus.runtime.Startup;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
@Startup
public class RecordingRecoveryService {
	private static final Duration STUCK_THRESHOLD = Duration.ofMinutes(5);
	private static final Set<RecordingStatus> RECOVERABLE = EnumSet.of(
		RecordingStatus.UPLOADED,
		RecordingStatus.TRANSCRIBING,
		RecordingStatus.TRANSCRIBED,
		RecordingStatus.OPTIMIZING,
		RecordingStatus.OPTIMIZED,
		RecordingStatus.SUMMARIZING
	);
	@Inject
	EntityManager entityManager;

	@Inject
	RecordingProcessingService processingService;

	@Scheduled(every = "1m")
	@Transactional
	void recoverStuck() {
		scanAndRecover();
	}

	@jakarta.annotation.PostConstruct
	void onStartup() {
		scanAndRecover();
	}

	@Transactional
	public void scanAndRecover() {
		Instant cutoff = Instant.now().minus(STUCK_THRESHOLD);
		List<Recording> stuck = entityManager.createQuery("select r from Recording r where r.status in :statuses and r.updatedAt < :cutoff", Recording.class)
			.setParameter("statuses", RECOVERABLE)
			.setParameter("cutoff", cutoff)
			.getResultList();
		for (Recording recording : stuck) {
			UUID id = recording.id;
			if (id == null) {
				continue;
			}
			processingService.process(id);
		}
	}
}
