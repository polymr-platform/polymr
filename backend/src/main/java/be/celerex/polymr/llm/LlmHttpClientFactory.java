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

import dev.langchain4j.http.client.HttpClientBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class LlmHttpClientFactory {
	private final boolean enabled;
	private final long connectTimeoutSeconds;
	private final long readTimeoutSeconds;
	private final LlmCallRegistry callRegistry;
	private final Instance<LlmHttpInterceptor> interceptors;

	@Inject
	public LlmHttpClientFactory(
			@ConfigProperty(name = "polymr.llm.http.custom-enabled", defaultValue = "true") boolean enabled,
			@ConfigProperty(name = "polymr.llm.http.connect-timeout-seconds", defaultValue = "60") long connectTimeoutSeconds,
			@ConfigProperty(name = "polymr.llm.http.read-timeout-seconds", defaultValue = "900") long readTimeoutSeconds,
			LlmCallRegistry callRegistry,
			Instance<LlmHttpInterceptor> interceptors) {
		this.enabled = enabled;
		this.connectTimeoutSeconds = connectTimeoutSeconds;
		this.readTimeoutSeconds = readTimeoutSeconds;
		this.callRegistry = callRegistry;
		this.interceptors = interceptors;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public HttpClientBuilder builder() {
		if (!enabled) {
			return null;
		}
		org.jboss.logging.Logger
			.getLogger(LlmHttpClientFactory.class)
			.infof("LLM HTTP config connect=%ss read=%ss", connectTimeoutSeconds, readTimeoutSeconds);
		List<LlmHttpInterceptor> active = new ArrayList<>();
		if (interceptors != null) {
			interceptors.forEach(active::add);
		}
		return new PolymrHttpClientBuilder(
			Duration.ofSeconds(connectTimeoutSeconds),
			Duration.ofSeconds(readTimeoutSeconds),
			callRegistry,
			active
		);
	}
}
