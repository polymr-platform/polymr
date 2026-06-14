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

package be.celerex.polymr.cluster;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

class InMemoryClusteredMap<K, V> implements ClusteredMap<K, V> {
	private final Map<K, V> map = new ConcurrentHashMap<>();
	private final ReentrantLock lock = new ReentrantLock();

	@Override
	public V get(K key) {
		return map.get(key);
	}

	@Override
	public void put(K key, V value) {
		map.put(key, value);
	}

	@Override
	public V remove(K key) {
		return map.remove(key);
	}

	@Override
	public Set<Map.Entry<K, V>> entrySet() {
		return map.entrySet();
	}

	@Override
	public boolean tryLock() {
		return lock.tryLock();
	}

	@Override
	public void unlock() {
		if (lock.isHeldByCurrentThread()) {
			lock.unlock();
		}
	}
}
