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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.http.client.FormDataFile;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventListenerUtils;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import dev.langchain4j.http.client.sse.DefaultServerSentEventParser;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class PolymrHttpClient implements HttpClient {
	private static final org.jboss.logging.Logger LOGGER = org.jboss.logging.Logger.getLogger(PolymrHttpClient.class);
	private static final MediaType DEFAULT_MEDIA_TYPE = MediaType.parse("application/octet-stream");
	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
	private final OkHttpClient delegate;
	private final LlmCallRegistry callRegistry;
	private final List<LlmHttpInterceptor> interceptors;

	public PolymrHttpClient(
			Duration connectTimeout,
			Duration readTimeout,
			LlmCallRegistry callRegistry,
			List<LlmHttpInterceptor> interceptors) {
		OkHttpClient.Builder httpClientBuilder = new OkHttpClient.Builder();
		if (connectTimeout != null) {
			httpClientBuilder.connectTimeout(connectTimeout);
		}
		if (readTimeout != null) {
			httpClientBuilder.readTimeout(readTimeout);
		}
		LOGGER.infof(
			"LLM HTTP client timeouts connect=%ss read=%ss",
			connectTimeout == null ? "default" : connectTimeout.getSeconds(),
			readTimeout == null ? "default" : readTimeout.getSeconds()
		);
		this.delegate = httpClientBuilder.build();
		this.callRegistry = callRegistry;
		this.interceptors = interceptors == null ? List.of() : interceptors;
	}

	@Override
	public SuccessfulHttpResponse execute(HttpRequest request) throws HttpException {
		HttpRequest prepared = applyRequestInterceptors(request);
		Request okRequest = toOkHttpRequest(prepared);
		try (Response response = delegate.newCall(okRequest).execute()) {
			if (!response.isSuccessful()) {
				String body = readBody(response.body());
				HttpException exception = new HttpException(response.code(), body);
				notifyError(prepared, exception);
				throw exception;
			}
			String body = readBody(response.body());
			SuccessfulHttpResponse result = SuccessfulHttpResponse.builder()
				.statusCode(response.code())
				.headers(response.headers().toMultimap())
				.body(body)
				.build();
			notifyResponse(prepared, result);
			return result;
		}
		catch (IOException e) {
			RuntimeException exception = new RuntimeException(e);
			notifyError(prepared, exception);
			throw exception;
		}
	}

	@Override
	public void execute(HttpRequest request, ServerSentEventListener listener) {
		execute(request, new DefaultServerSentEventParser(), listener);
	}

	@Override
	public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
		HttpRequest prepared = applyRequestInterceptors(request);
		Request okRequest = toOkHttpRequest(prepared);
		UUID sessionId = callRegistry.currentSessionId();
		Call call = delegate.newCall(okRequest);
		LlmCallRegistry.LlmCancelable cancelable = call::cancel;
		if (sessionId != null) {
			callRegistry.register(sessionId, cancelable);
		}
		call.enqueue(
			new Callback() {
				@Override
				public void onFailure(Call call, IOException e) {
					try {
						LOGGER.errorf(e, "LLM stream failure url=%s error=%s", prepared.url(), e.getClass().getSimpleName());
						notifyError(prepared, e);
						ServerSentEventListenerUtils.ignoringExceptions(() -> listener.onError(e));
					}
					finally {
						if (sessionId != null) {
							callRegistry.clear(sessionId, cancelable);
						}
					}
				}

				@Override
				public void onResponse(Call call, Response response) {
					try (Response responseRef = response) {
						LOGGER.debugf("LLM stream response url=%s status=%s", prepared.url(), responseRef.code());
						if (!responseRef.isSuccessful()) {
							HttpException exception = new HttpException(responseRef.code(), readBody(responseRef.body()));
							notifyError(prepared, exception);
							ServerSentEventListenerUtils.ignoringExceptions(() -> listener.onError(exception));
							return;
						}
						SuccessfulHttpResponse result = SuccessfulHttpResponse.builder()
							.statusCode(responseRef.code())
							.headers(responseRef.headers().toMultimap())
							.body(null)
							.build();
						notifyResponse(prepared, result);
						ServerSentEventListenerUtils.ignoringExceptions(() -> listener.onOpen(result));
						ResponseBody responseBody = responseRef.body();
						if (responseBody == null) {
							LOGGER.warnf("LLM stream returned empty body url=%s", prepared.url());
							RuntimeException exception = new RuntimeException("Empty response body");
							notifyError(prepared, exception);
							ServerSentEventListenerUtils.ignoringExceptions(() -> listener.onError(exception));
							ServerSentEventListenerUtils.ignoringExceptions(listener::onClose);
							return;
						}
						AtomicBoolean sawEvent = new AtomicBoolean(false);
						AtomicBoolean sawError = new AtomicBoolean(false);
						AtomicBoolean sawCompletion = new AtomicBoolean(false);
						AtomicBoolean sawFailure = new AtomicBoolean(false);
						boolean isResponsesStream = prepared.url() != null && prepared.url().contains("/responses");
						ServerSentEventListener wrapped = new ServerSentEventListener() {
							@Override
							public void onOpen(SuccessfulHttpResponse response) {
								listener.onOpen(response);
							}

							@Override
							public void onEvent(dev.langchain4j.http.client.sse.ServerSentEvent event) {
								sawEvent.set(true);
								if (isResponsesStream) {
									updateResponsesFlags(event, sawCompletion, sawFailure);
								}
								listener.onEvent(event);
							}

							@Override
							public void onEvent(
									dev.langchain4j.http.client.sse.ServerSentEvent event,
									dev.langchain4j.http.client.sse.ServerSentEventContext context) {
								sawEvent.set(true);
								if (isResponsesStream) {
									updateResponsesFlags(event, sawCompletion, sawFailure);
								}
								listener.onEvent(event, context);
							}

							@Override
							public void onClose() {
								listener.onClose();
							}

							@Override
							public void onError(Throwable error) {
								sawError.set(true);
								listener.onError(error);
							}
						};
						try (InputStream inputStream = responseBody.byteStream()) {
							parser.parse(inputStream, wrapped);
							if (!sawEvent.get() && !sawError.get()) {
								LOGGER.warnf("LLM stream produced no events url=%s", prepared.url());
								RuntimeException exception = new RuntimeException("Empty response stream");
								notifyError(prepared, exception);
								ServerSentEventListenerUtils.ignoringExceptions(() -> wrapped.onError(exception));
							}
							if (isResponsesStream && sawEvent.get() && !sawCompletion.get() && !sawFailure.get()) {
								LOGGER.warnf("LLM stream ended without completion event url=%s", prepared.url());
								RuntimeException exception = new RuntimeException("Response stream ended without completion");
								notifyError(prepared, exception);
								ServerSentEventListenerUtils.ignoringExceptions(() -> wrapped.onError(exception));
							}
							ServerSentEventListenerUtils.ignoringExceptions(wrapped::onClose);
						}
					}
					catch (Exception ex) {
						LOGGER.errorf(ex, "LLM stream processing failed url=%s", prepared.url());
						notifyError(prepared, ex);
						ServerSentEventListenerUtils.ignoringExceptions(() -> listener.onError(ex));
					}
					finally {
						if (sessionId != null) {
							callRegistry.clear(sessionId, cancelable);
						}
					}
				}
			}
		);
	}

	private HttpRequest applyRequestInterceptors(HttpRequest request) {
		HttpRequest current = request;
		for (LlmHttpInterceptor interceptor : interceptors) {
			if (interceptor == null) {
				continue;
			}
			current = interceptor.onRequest(current);
		}
		return current;
	}

	private void notifyResponse(HttpRequest request, SuccessfulHttpResponse response) {
		for (LlmHttpInterceptor interceptor : interceptors) {
			if (interceptor == null) {
				continue;
			}
			interceptor.onResponse(request, response);
		}
	}

	private void notifyError(HttpRequest request, Throwable error) {
		for (LlmHttpInterceptor interceptor : interceptors) {
			if (interceptor == null) {
				continue;
			}
			interceptor.onError(request, error);
		}
	}

	private Request toOkHttpRequest(HttpRequest request) {
		Request.Builder builder = new Request.Builder().url(URI.create(request.url()).toString());
		request.headers()
			.forEach(
				(name, values) -> {
					if (values != null) {
						values.forEach(value -> builder.addHeader(name, value));
					}
				}
			);
		boolean hasFormData = !request.formDataFields().isEmpty() || !request.formDataFiles().isEmpty();
		RequestBody body = null;
		if (hasFormData) {
			MultipartBody.Builder multipart = new MultipartBody.Builder().setType(MultipartBody.FORM);
			for (Map.Entry<String, String> entry : request.formDataFields()
				.entrySet()) {
				multipart.addFormDataPart(entry.getKey(), entry.getValue());
			}
			for (Map.Entry<String, FormDataFile> entry : request.formDataFiles()
				.entrySet()) {
				FormDataFile file = entry.getValue();
				MediaType mediaType = resolveMediaType(file == null ? null : file.contentType());
				byte[] content = file == null ? new byte[0] : file.content();
				RequestBody fileBody = RequestBody.create(content, mediaType);
				String fileName = file == null ? "file" : file.fileName();
				multipart.addFormDataPart(entry.getKey(), fileName, fileBody);
			}
			body = multipart.build();
		}
		else if (request.body() != null) {
			body = RequestBody.create(request.body(), null);
		}
		String method = request.method().name();
		builder.method(
			method,
			requiresRequestBody(method) ? (body != null ? body : RequestBody.create(new byte[0], null)) : body
		);
		return builder.build();
	}

	private static boolean requiresRequestBody(String method) {
		return "POST".equalsIgnoreCase(method)
			|| "PUT".equalsIgnoreCase(method)
			|| "PATCH".equalsIgnoreCase(method)
			|| "PROPPATCH".equalsIgnoreCase(method)
			|| "REPORT".equalsIgnoreCase(method);
	}

	private static MediaType resolveMediaType(String contentType) {
		if (contentType == null || contentType.isBlank()) {
			return DEFAULT_MEDIA_TYPE;
		}
		MediaType resolved = MediaType.parse(contentType);
		return resolved == null ? DEFAULT_MEDIA_TYPE : resolved;
	}

	private static String readBody(ResponseBody body) {
		if (body == null) {
			return "";
		}
		try {
			return body.string();
		}
		catch (IOException e) {
			return "Cannot read error response body: " + e.getMessage();
		}
	}

	private static String trimPayload(String payload) {
		if (payload == null) {
			return "";
		}
		String trimmed = payload.trim();
		if (trimmed.length() <= 500) {
			return trimmed;
		}
		return trimmed.substring(0, 500) + "...";
	}

	private static void updateResponsesFlags(
			dev.langchain4j.http.client.sse.ServerSentEvent event,
			AtomicBoolean sawCompletion,
			AtomicBoolean sawFailure) {
		if (event == null) {
			return;
		}
		String data = event.data();
		if (data == null || data.isBlank()) {
			return;
		}
		String trimmed = data.trim();
		if (!trimmed.startsWith("{")) {
			return;
		}
		try {
			JsonNode node = OBJECT_MAPPER.readTree(trimmed);
			String type = node.path("type").asText(null);
			if (type == null) {
				return;
			}
			if ("response.completed".equals(type) || "response.incomplete".equals(type)) {
				sawCompletion.set(true);
				return;
			}
			if ("response.failed".equals(type) || "response.error".equals(type)) {
				sawFailure.set(true);
			}
		}
		catch (Exception ex) {
			LOGGER.debugf(ex, "Failed to parse responses event payload");
		}
	}
}
