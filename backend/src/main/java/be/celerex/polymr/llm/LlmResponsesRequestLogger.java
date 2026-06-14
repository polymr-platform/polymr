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

import dev.langchain4j.http.client.HttpRequest;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class LlmResponsesRequestLogger implements LlmHttpInterceptor {
	private static final Logger LOGGER = Logger.getLogger(LlmResponsesRequestLogger.class);
	@ConfigProperty(name = "polymr.llm.http.log-responses-requests", defaultValue = "false")
	boolean enabled;

	@Override
	public HttpRequest onRequest(HttpRequest request) {
		if (!enabled || request == null) {
			return request;
		}
		String url = request.url();
		if (url == null || !url.contains("/responses")) {
			return request;
		}
		String body = request.body();
		if (body == null || body.isBlank()) {
			LOGGER.debugf("LLM responses request url=%s body=<empty>", url);
			return request;
		}
		LOGGER.debugf("LLM responses request url=%s body=%s", url, trimPayload(body));
		return request;
	}

	private static String trimPayload(String payload) {
		if (payload == null) {
			return "";
		}
		String trimmed = payload.trim();
		if (trimmed.length() <= 1000) {
			return trimmed;
		}
		return trimmed.substring(0, 1000) + "...";
	}
}
