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

import be.celerex.polymr.llm.LlmHttpClientFactory;
import dev.langchain4j.http.client.HttpClientBuilder;
import java.lang.reflect.Method;

final class LlmHttpClientConfigurer {
	private LlmHttpClientConfigurer() {}

	static void applyIfSupported(Object builder, LlmHttpClientFactory factory) {
		if (builder == null || factory == null || !factory.isEnabled()) {
			return;
		}
		try {
			HttpClientBuilder clientBuilder = factory.builder();
			if (clientBuilder == null) {
				return;
			}
			Method method = builder.getClass().getMethod("httpClientBuilder", HttpClientBuilder.class);
			method.invoke(builder, clientBuilder);
		}
		catch (NoSuchMethodException ignored) {}
		catch (Exception ex) {
			org.jboss.logging.Logger
				.getLogger(LlmHttpClientConfigurer.class)
				.debugf(ex, "Failed to apply HTTP client configuration");
		}
	}
}
