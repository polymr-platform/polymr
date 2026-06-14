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

package be.celerex.polymr.workflow;

import be.celerex.polymr.session.SessionChatService;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import be.celerex.polymr.lock.LockService;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class WorkflowRunDispatcher {
	private static final Logger LOGGER = Logger.getLogger(WorkflowRunDispatcher.class);
	@ConfigProperty(name = "polymr.runtime.worker-threads", defaultValue = "10")
	int workerThreads;

	@ConfigProperty(name = "polymr.runtime.queue-overshoot-percent", defaultValue = "10")
	double overshootPercent;

	@Inject
	SessionChatService sessionChatService;

	@Inject
	WorkflowRunLeaseService leaseService;

	@Inject
	LockService lockService;

	private ExecutorService executor;
	private final AtomicInteger active = new AtomicInteger();
	private static final String DISPATCH_LOCK_SCOPE = "workflow-dispatch";
	private static final String DISPATCH_LOCK_KEY = "dispatch";

	@PostConstruct
	void init() {
		executor = Executors.newFixedThreadPool(Math.max(1, workerThreads));
		dispatch();
	}

	@PreDestroy
	void shutdown() {
		if (executor != null) {
			executor.shutdownNow();
		}
	}

	@Scheduled(every = "{polymr.runtime.queue-poll-interval:60s}")
	void scheduledDispatch() {
		dispatch();
	}

	public void notifyWorkAvailable() {
		dispatch();
	}

	private void dispatch() {
		if (lockService == null || !lockService.tryAcquire(DISPATCH_LOCK_SCOPE, DISPATCH_LOCK_KEY)) {
			return;
		}
		try {
			int capacity = Math.max(0, workerThreads - active.get());
			int overshoot = (int) Math.ceil(workerThreads * Math.max(0.0, overshootPercent) / 100.0);
			int target = Math.max(0, capacity + overshoot);
			if (target == 0) {
				return;
			}
			for (int i = 0; i < target; i++) {
				UUID runId = leaseService.claimNext();
				if (runId == null) {
					break;
				}
				submit(runId);
			}
		}
		finally {
			lockService.release(DISPATCH_LOCK_SCOPE, DISPATCH_LOCK_KEY);
		}
	}

	private void submit(UUID runId) {
		active.incrementAndGet();
		executor.submit(
			() -> {
				try {
					sessionChatService.runLeased(runId);
				}
				catch (Exception ex) {
					LOGGER.warnf(ex, "Failed to execute workflow run %s", runId);
					sessionChatService.recoverLeasedRun(runId, ex);
				}
				finally {
					active.decrementAndGet();
					notifyWorkAvailable();
				}
			}
		);
	}

	UUID claimNext() {
		return leaseService.claimNext();
	}
}
