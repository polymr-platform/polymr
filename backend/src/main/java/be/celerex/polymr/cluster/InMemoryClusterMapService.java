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

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class InMemoryClusterMapService implements ClusterMapService {
	private final Map<String, ClusteredMap<?, ?>> maps = new ConcurrentHashMap<>();

	@SuppressWarnings("unchecked")
	@Override
	public <K, V> ClusteredMap<K, V> getMap(String name, Class<K> keyType, Class<V> valueType) {
		return (ClusteredMap<K, V>) maps.computeIfAbsent(name, key -> new InMemoryClusteredMap<>());
	}
}
