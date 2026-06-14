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

package be.celerex.polymr.scheduling;

import be.celerex.polymr.lock.LockService;
import be.celerex.polymr.model.Script;
import be.celerex.polymr.model.ScriptType;
import be.celerex.polymr.scripts.ScriptRuntimeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ScriptScheduleDispatcher implements Runnable {
	private static final Logger LOGGER = Logger.getLogger(ScriptScheduleDispatcher.class);
	private static final String LOCK_SCOPE = "script-schedule";
	private static final Duration MAX_SLEEP = Duration.ofMinutes(5);
	@ConfigProperty(name = "polymr.scripts.scheduler-worker-threads", defaultValue = "4")
	int workerThreads;

	@Inject
	EntityManager entityManager;

	@Inject
	ScriptRuntimeService runtimeService;

	@Inject
	ScriptScheduleService scheduleService;

	@Inject
	LockService lockService;

	@Inject
	ObjectMapper objectMapper;

	private ExecutorService executor;
	private Thread schedulerThread;
	private final AtomicBoolean running = new AtomicBoolean(true);

	@PostConstruct
	void init() {
		executor = Executors.newFixedThreadPool(Math.max(1, workerThreads));
		schedulerThread = new Thread(this, "script-scheduler");
		schedulerThread.setDaemon(true);
		schedulerThread.start();
	}

	@PreDestroy
	void shutdown() {
		running.set(false);
		if (schedulerThread != null) {
			schedulerThread.interrupt();
		}
		if (executor != null) {
			executor.shutdownNow();
		}
	}

	@Override
	public void run() {
		while (running.get()) {
			try {
				dispatchDue();
				Instant now = Instant.now();
				Instant nextRun = findNextRun();
				Duration sleepFor = computeSleep(now, nextRun);
				Thread.sleep(sleepFor.toMillis());
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			catch (Exception ex) {
				LOGGER.warnf(ex, "Script scheduler loop failed");
			}
		}
	}

	private Duration computeSleep(Instant now, Instant nextRun) {
		if (nextRun == null) {
			return MAX_SLEEP;
		}
		Duration until = Duration.between(now, nextRun);
		if (until.isNegative() || until.isZero()) {
			return Duration.ofSeconds(1);
		}
		return until.compareTo(MAX_SLEEP) > 0 ? MAX_SLEEP : until;
	}

	@Transactional
	Instant findNextRun() {
		List<Instant> results = entityManager.createQuery(
				"select s.nextRunAt from Script s where s.scheduled = true "
					+ "and s.type = :type and s.disabled = false and s.nextRunAt is not null "
					+ "order by s.nextRunAt asc",
				Instant.class
			)
			.setParameter("type", ScriptType.STANDALONE)
			.setMaxResults(1)
			.getResultList();
		return results.isEmpty() ? null : results.get(0);
	}

	void dispatchDue() {
		List<UUID> due = findDueScripts();
		for (UUID scriptId : due) {
			if (scriptId == null) {
				continue;
			}
			executor.submit(() -> runScheduled(scriptId));
		}
	}

	@Transactional
	List<UUID> findDueScripts() {
		return entityManager.createQuery(
				"select s.id from Script s where s.scheduled = true and s.type = :type "
					+ "and s.disabled = false and s.nextRunAt is not null and s.nextRunAt <= :now",
				UUID.class
			)
			.setParameter("type", ScriptType.STANDALONE)
			.setParameter("now", Instant.now())
			.getResultList();
	}

	void runScheduled(UUID scriptId) {
		if (lockService != null && !lockService.tryAcquire(LOCK_SCOPE, scriptId.toString())) {
			return;
		}
		try {
			ScheduledScriptPayload payload = prepareScript(scriptId);
			if (payload == null) {
				return;
			}
			runtimeService.runScriptWithSource(
				payload.script,
				payload.userId,
				null,
				payload.source,
				objectMapper.createObjectNode(),
				payload.inputSchema,
				payload.outputSchema
			);
		}
		catch (Exception ex) {
			LOGGER.warnf(ex, "Scheduled script execution failed %s", scriptId);
		}
		finally {
			if (lockService != null) {
				lockService.release(LOCK_SCOPE, scriptId.toString());
			}
		}
	}

	@Transactional
	ScheduledScriptPayload prepareScript(UUID scriptId) {
		Script script = entityManager.find(Script.class, scriptId);
		if (script == null || !script.scheduled || script.disabled || script.type != ScriptType.STANDALONE) {
			return null;
		}
		if (script.activeVersion == null
				|| script.activeVersion.sourceGroovy == null
				|| script.activeVersion.sourceGroovy.isBlank()) {
			return null;
		}
		Instant now = Instant.now();
		if (script.nextRunAt == null || script.nextRunAt.isAfter(now)) {
			return null;
		}
		script.lastRunAt = now;
		script.nextRunAt = scheduleService.resolveNextRun(script, now);
		UUID userId = script.createdBy == null ? null : script.createdBy.id;
		String source = script.activeVersion.sourceGroovy;
		return new ScheduledScriptPayload(script, userId, source, script.activeVersion.inputSchema, script.activeVersion.outputSchema);
	}

	private record ScheduledScriptPayload(
			Script script,
			UUID userId,
			String source,
			com.fasterxml.jackson.databind.JsonNode inputSchema,
			com.fasterxml.jackson.databind.JsonNode outputSchema) {}
}
