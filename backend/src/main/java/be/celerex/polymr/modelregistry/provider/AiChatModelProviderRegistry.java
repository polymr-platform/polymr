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

package be.celerex.polymr.modelregistry.provider;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class AiChatModelProviderRegistry {
	private final Instance<AiChatModelProvider> providers;
	private final Instance<AiEmbeddingModelProvider> embeddingProviders;

	public AiChatModelProviderRegistry(
			Instance<AiChatModelProvider> providers,
			Instance<AiEmbeddingModelProvider> embeddingProviders) {
		this.providers = providers;
		this.embeddingProviders = embeddingProviders;
	}

	public List<AiChatModelProvider> list() {
		return providers.stream()
			.sorted(Comparator.comparing(AiChatModelProvider::displayName))
			.toList();
	}

	public List<AiEmbeddingModelProvider> listEmbedding() {
		return embeddingProviders.stream()
			.sorted(Comparator.comparing(AiEmbeddingModelProvider::displayName))
			.toList();
	}

	public Optional<AiChatModelProvider> find(String id) {
		if (id == null) {
			return Optional.empty();
		}
		return providers.stream()
			.filter(provider -> id.equals(provider.id()))
			.findFirst();
	}

	public Optional<AiEmbeddingModelProvider> findEmbedding(String id) {
		if (id == null) {
			return Optional.empty();
		}
		return embeddingProviders.stream()
			.filter(provider -> id.equals(provider.id()))
			.findFirst();
	}
}
