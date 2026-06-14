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

package be.celerex.polymr.session;

import be.celerex.polymr.lock.LockService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

@ApplicationScoped
public class SessionProcessingGate {
	private static final String LOCK_SCOPE = "session-processing";
	@Inject
	LockService lockService;

	private final ConcurrentMap<UUID, Semaphore> localLocks = new ConcurrentHashMap<>();
	private final ConcurrentMap<UUID, AtomicInteger> inflight = new ConcurrentHashMap<>();

	public Gate acquire(UUID sessionId) {
		if (sessionId == null) {
			return Gate.NOOP;
		}
		Semaphore local = localLocks.computeIfAbsent(sessionId, id -> new Semaphore(1));
		local.acquireUninterruptibly();
		try {
			lockService.acquire(LOCK_SCOPE, sessionId.toString());
		}
		catch (RuntimeException ex) {
			local.release();
			throw ex;
		}
		inflight.computeIfAbsent(sessionId, id -> new AtomicInteger())
			.incrementAndGet();
		return new Gate(this, sessionId, local);
	}

	public Gate tryAcquire(UUID sessionId) {
		if (sessionId == null) {
			return null;
		}
		Semaphore local = localLocks.computeIfAbsent(sessionId, id -> new Semaphore(1));
		if (!local.tryAcquire()) {
			return null;
		}
		boolean acquired = lockService.tryAcquire(LOCK_SCOPE, sessionId.toString());
		if (!acquired) {
			local.release();
			return null;
		}
		inflight.computeIfAbsent(sessionId, id -> new AtomicInteger())
			.incrementAndGet();
		return new Gate(this, sessionId, local);
	}

	public boolean isRunning(UUID sessionId) {
		if (sessionId == null) {
			return false;
		}
		AtomicInteger count = inflight.get(sessionId);
		return count != null && count.get() > 0;
	}

	private void release(UUID sessionId, Semaphore local) {
		try {
			lockService.release(LOCK_SCOPE, sessionId.toString());
		}
		finally {
			AtomicInteger count = inflight.get(sessionId);
			if (count != null && count.decrementAndGet() <= 0) {
				inflight.remove(sessionId, count);
			}
			local.release();
		}
	}

	public static final class Gate implements AutoCloseable {
		static final Gate NOOP = new Gate(null, null, null);
		private final SessionProcessingGate gate;
		private final UUID sessionId;
		private final Semaphore local;
		private boolean closed = false;

		private Gate(SessionProcessingGate gate, UUID sessionId, Semaphore local) {
			this.gate = gate;
			this.sessionId = sessionId;
			this.local = local;
		}

		@Override
		public void close() {
			if (closed) {
				return;
			}
			closed = true;
			if (gate != null && sessionId != null && local != null) {
				gate.release(sessionId, local);
			}
		}
	}
}
