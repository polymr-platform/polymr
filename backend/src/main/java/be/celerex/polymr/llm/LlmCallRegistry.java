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

package be.celerex.polymr.llm;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class LlmCallRegistry {
	private static final org.jboss.logging.Logger LOGGER = org.jboss.logging.Logger.getLogger(LlmCallRegistry.class);

	public interface LlmCancelable {
        void cancel();
    }

	private final ConcurrentHashMap<UUID, LlmCancelable> calls = new ConcurrentHashMap<>();
	private final ThreadLocal<UUID> currentSessionId = new ThreadLocal<>();

	public void withSession(UUID sessionId, Runnable runnable) {
		if (sessionId == null || runnable == null) {
			if (runnable != null) {
				runnable.run();
			}
			return;
		}
		currentSessionId.set(sessionId);
		try {
			runnable.run();
		}
		finally {
			currentSessionId.remove();
		}
	}

	public UUID currentSessionId() {
		return currentSessionId.get();
	}

	public void register(UUID sessionId, LlmCancelable call) {
		if (sessionId == null || call == null) {
			return;
		}
		LlmCancelable previous = calls.put(sessionId, call);
		if (previous != null && previous != call) {
			LOGGER.warnf("LLM call registry overwrite session=%s", sessionId);
		}
		else {
			LOGGER.debugf("LLM call registered session=%s", sessionId);
		}
	}

	public void clear(UUID sessionId, LlmCancelable call) {
		if (sessionId == null || call == null) {
			return;
		}
		boolean removed = calls.remove(sessionId, call);
		if (!removed) {
			LOGGER.debugf("LLM call clear skipped session=%s", sessionId);
		}
		else {
			LOGGER.debugf("LLM call cleared session=%s", sessionId);
		}
	}

	public boolean cancel(UUID sessionId) {
		if (sessionId == null) {
			return false;
		}
		LlmCancelable call = calls.remove(sessionId);
		if (call != null) {
			LOGGER.infof("LLM call cancel requested session=%s", sessionId);
			call.cancel();
			return true;
		}
		LOGGER.warnf("LLM call cancel missed (no active call) session=%s", sessionId);
		return false;
	}
}
