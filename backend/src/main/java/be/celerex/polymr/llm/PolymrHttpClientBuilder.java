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

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import java.time.Duration;
import java.util.List;

public class PolymrHttpClientBuilder implements HttpClientBuilder {
	private Duration connectTimeout;
	private Duration readTimeout;
	private final Duration forcedConnectTimeout;
	private final Duration forcedReadTimeout;
	private final LlmCallRegistry callRegistry;
	private final List<LlmHttpInterceptor> interceptors;

	public PolymrHttpClientBuilder(
			Duration connectTimeout,
			Duration readTimeout,
			LlmCallRegistry callRegistry,
			List<LlmHttpInterceptor> interceptors) {
		this.connectTimeout = connectTimeout;
		this.readTimeout = readTimeout;
		this.forcedConnectTimeout = connectTimeout;
		this.forcedReadTimeout = readTimeout;
		this.callRegistry = callRegistry;
		this.interceptors = interceptors;
	}

	@Override
	public Duration connectTimeout() {
		return connectTimeout;
	}

	@Override
	public HttpClientBuilder connectTimeout(Duration timeout) {
		if (forcedConnectTimeout == null) {
			this.connectTimeout = timeout;
		}
		else {
			this.connectTimeout = forcedConnectTimeout;
		}
		return this;
	}

	@Override
	public Duration readTimeout() {
		return readTimeout;
	}

	@Override
	public HttpClientBuilder readTimeout(Duration timeout) {
		if (forcedReadTimeout == null) {
			this.readTimeout = timeout;
		}
		else {
			this.readTimeout = forcedReadTimeout;
		}
		return this;
	}

	@Override
	public HttpClient build() {
		return new PolymrHttpClient(connectTimeout, readTimeout, callRegistry, interceptors);
	}
}
