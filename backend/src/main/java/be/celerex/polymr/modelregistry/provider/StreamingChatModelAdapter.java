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

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.jboss.logging.Logger;

public class StreamingChatModelAdapter implements ChatModel {
	private static final Logger LOGGER = Logger.getLogger(StreamingChatModelAdapter.class);
	private final StreamingChatModel delegate;

	public StreamingChatModelAdapter(StreamingChatModel delegate) {
		this.delegate = delegate;
	}

	@Override
	public ChatResponse chat(List<ChatMessage> messages) {
		CompletableFuture<ChatResponse> future = new CompletableFuture<>();
		StringBuilder buffer = new StringBuilder();
		delegate.chat(
			messages,
			new StreamingChatResponseHandler() {
				@Override
				public void onPartialResponse(String partialResponse) {
					if (partialResponse != null) {
						buffer.append(partialResponse);
					}
				}

				@Override
				public void onCompleteResponse(ChatResponse response) {
					if (response != null) {
						future.complete(response);
						return;
					}
					future.complete(ChatResponse.builder().aiMessage(AiMessage.from(buffer.toString())).build());
				}

				@Override
				public void onError(Throwable error) {
					LOGGER.error("Streaming chat model failed", error);
					future.completeExceptionally(error);
				}
			}
		);
		return future.join();
	}

	@Override
	public ChatResponse chat(ChatRequest request) {
		CompletableFuture<ChatResponse> future = new CompletableFuture<>();
		StringBuilder buffer = new StringBuilder();
		delegate.chat(
			request,
			new StreamingChatResponseHandler() {
				@Override
				public void onPartialResponse(String partialResponse) {
					if (partialResponse != null) {
						buffer.append(partialResponse);
					}
				}

				@Override
				public void onCompleteResponse(ChatResponse response) {
					if (response != null) {
						future.complete(response);
						return;
					}
					future.complete(ChatResponse.builder().aiMessage(AiMessage.from(buffer.toString())).build());
				}

				@Override
				public void onError(Throwable error) {
					LOGGER.error("Streaming chat model failed", error);
					future.completeExceptionally(error);
				}
			}
		);
		return future.join();
	}
}
