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

package be.celerex.polymr.lock;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@ApplicationScoped
public class NoopLockService implements LockService {
	private final java.util.Map<String, Set<Consumer<String>>> subscribersByChannel = new ConcurrentHashMap<>();

	@Override
	public boolean tryAcquire(String scope, String key) {
		return true;
	}

	@Override
	public void release(String scope, String key) {}

	@Override
	public void publish(String channel, String payload) {
		Set<Consumer<String>> subscribers = subscribersByChannel.get(channel);
		if (subscribers == null || subscribers.isEmpty()) {
			return;
		}
		for (Consumer<String> handler : subscribers) {
			handler.accept(payload);
		}
	}

	@Override
	public AutoCloseable subscribe(String channel, Consumer<String> handler) {
		subscribersByChannel.computeIfAbsent(channel, key -> ConcurrentHashMap.newKeySet())
			.add(handler);
		return () -> {
			Set<Consumer<String>> subscribers = subscribersByChannel.get(channel);
			if (subscribers == null) {
				return;
			}
			subscribers.remove(handler);
			if (subscribers.isEmpty()) {
				subscribersByChannel.remove(channel, subscribers);
			}
		};
	}
}
