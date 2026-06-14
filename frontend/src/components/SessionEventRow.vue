<script setup>
import { computed } from 'vue';
import MarkdownMessage from './MarkdownMessage.vue';
import EventAttachments from './EventAttachments.vue';
import ScopeViewer from './ScopeViewer.vue';
const props = defineProps({
	event: { type: Object, required: true },
	isStreaming: { type: Boolean, default: false },
	streamingContent: { type: String, default: '' },
	streamingThinkText: { type: String, default: '' },
	formatTimestamp: { type: Function, required: true },
	eventTypeLabel: { type: Function, required: true },
	eventPreview: { type: Function, required: true },
	eventText: { type: Function, required: true },
	eventComment: { type: Function, required: true },
	eventThinkText: { type: Function, required: true },
	eventAborted: { type: Function, required: true },
	eventAttachments: { type: Function, required: true },
	attachmentUrl: { type: Function, required: true },
	downloadAttachment: { type: Function, required: true },
	downloadStateFor: { type: Function, required: true },
	openImagePreview: { type: Function, required: true },
	isToolEvent: { type: Function, required: true },
	toolInputTemplate: { type: Function, required: true },
	shouldHideToolResponse: { type: Function, required: true },
	toolResultSummaryText: { type: Function, required: true },
	toolResultSummaryError: { type: Function, required: true },
	toolStatus: { type: Function, required: true },
	isToolPending: { type: Function, required: true },
	toolReviewUri: { type: Function, required: true },
	reviewOpenFor: { type: Function, required: true },
	toolUiViewState: { type: Function, required: true },
	noopToolCaller: { type: Function, required: true },
	openDetails: { type: Object, required: true },
	eventDetailPayload: { type: Function, required: true },
	toolOutputTemplate: { type: Function, required: true },
	visibleToolOutputTemplate: { type: Function, required: true },
	toolOutputTemplateTruncated: { type: Function, required: true },
	toggleToolOutputExpanded: { type: Function, required: true },
	toolOutputExpandedFor: { type: Function, required: true },
	toggleReview: { type: Function, required: true },
	toggleEventDetails: { type: Function, required: true },
	copyEventPayload: { type: Function, required: true },
	inspectEvent: { type: Function, required: true },
	decisionRequests: { type: Function, required: true },
	decisionRequestSummary: { type: Function, required: true },
	decisionStatus: { type: Function, required: true },
	decisionRequestResult: { type: Function, required: true },
	decisionApprovedBy: { type: Function, required: true },
	decisionRequestKey: { type: Function, required: true },
	toggleDecisionRequestDetails: { type: Function, required: true },
	openDecisionInspect: { type: Function, required: true },
	decisionRequestDetailsOpenFor: { type: Function, required: true },
	decisionHasPreview: { type: Function, required: true },
	decisionInputTemplate: { type: Function, required: true },
	decisionReviewUri: { type: Function, required: true },
	decisionToolViewState: { type: Function, required: true },
	decisionRequestedScopes: { type: Function, required: true },
	decisionScopeState: { type: Object, required: true },
	updateDecisionScopeState: { type: Function, required: true },
	sendDecision: { type: Function, required: true },
	decisionAllUndecided: { type: Function, required: true },
	decisionMissingScopes: { type: Function, required: true },
	decisionRememberValue: { type: Function, required: true },
	decisionRemember: { type: Object, required: true },
	userLabelForEvent: { type: Function, required: true },
	userAvatarUrl: { type: Function, required: true },
	userAvatarColor: { type: Function, required: true },
	userAvatarInitial: { type: Function, required: true },
	canInspectEvent: { type: Function, required: true },
	eventKind: { type: Function, required: true },
	openWorkerPane: { type: Function, required: true },
	workerToolEventsByCallId: { type: Object, required: true }
})
const content = () => {
	if (props.isStreaming) {
		return props.streamingContent
	}
	return props.eventText(props.event)
}
const thinkText = () => {
	if (props.isStreaming) {
		return props.streamingThinkText
	}
	return props.eventThinkText(props.event)
}
const isWorkerProgress = computed(() => props.event?.type === 'SYSTEM' && props.event?.payload?.kind === 'worker_progress')
const workerSummary = computed(() => {
	const payload = props.event?.payload || {}
	const done = payload.done ?? 0
	const total = payload.total ?? 0
	const failed = payload.failed ?? 0
	return `Workers: ${done}/${total} done, ${failed} failed`
})
const workerToolEvent = computed(() => {
	const payload = props.event?.payload || {}
	const toolCallId = payload.tool_call_id || payload.toolCallId || ''
	if (!toolCallId) {
		return null
	}
	return props.workerToolEventsByCallId?.[toolCallId] || null
})
</script>
<template>
	<div
		class="log-row"
		:class="[
			isStreaming ? 'assistant streaming' : (isWorkerProgress ? 'tool-result' : eventKind(event)),
			{ pending: isToolPending(event) },
		]"
		:data-event-id="isStreaming ? 'assistant-stream' : (event.renderId || event.id)">
		<div class="log-meta">
			<div class="log-labels">
				<span v-if="isStreaming" class="log-type">Assistant</span>
				<span
					v-else-if="event.type === 'USER_MESSAGE' || (event.type === 'SESSION_TAG_CHANGE' && event.user_id)"
					class="event-user">
					<span
						class="event-avatar"
						:style="{ backgroundColor: userAvatarUrl(event) ? '' : userAvatarColor(event) }">
						<img
							v-if="userAvatarUrl(event)"
							:src="userAvatarUrl(event)"
							alt=""/>
						<span v-else class="event-avatar-initial">{{ userAvatarInitial(event) }}</span>
					</span>
					<span class="event-user-name">{{ userLabelForEvent(event) }}</span>
				</span>
				<span v-else-if="isWorkerProgress" class="tool-request-label"><span class="tool-request-text">Spawn workers for parallel tasks</span></span>
				<span
					v-else-if="eventTypeLabel(event) && !isToolEvent(event) && !isWorkerProgress"
					class="log-type">{{ eventTypeLabel(event) }}</span>
				<span v-else-if="isToolEvent(event)" class="tool-request-label"><span class="tool-request-text">{{ eventTypeLabel(event) }}</span></span>
			</div>
			<div class="log-actions">
				<div class="log-buttons" v-if="!isStreaming">
					<button
						v-if="workerToolEvent"
						class="control size-xs icon-button tooltip log-icon"
						type="button"
						data-tip="Inspect I/O"
						@click="inspectEvent(workerToolEvent)">
						<svg
							viewBox="0 0 24 24"
							aria-hidden="true"
							focusable="false">
							<path
								fill="currentColor"
								d="M12 4C6.5 4 2 7.9 2 12s4.5 8 10 8 10-3.9 10-8-4.5-8-10-8zm0 13a5 5 0 1 1 0-10 5 5 0 0 1 0 10zm0-8a3 3 0 1 0 0 6 3 3 0 0 0 0-6z"/>
						</svg>
					</button>
					<button
						v-if="workerToolEvent"
						class="control size-xs icon-button tooltip log-icon"
						type="button"
						:data-tip="openDetails[workerToolEvent.id] ? 'Hide tool call details' : 'Show tool call details'"
						@click="toggleEventDetails(workerToolEvent.id)">
						<svg
							viewBox="0 0 24 24"
							aria-hidden="true"
							focusable="false">
							<path
								fill="currentColor"
								d="M5 5h14v2H5V5zm0 6h14v2H5v-2zm0 6h10v2H5v-2z"/>
						</svg>
					</button>
					<button
						v-if="toolReviewUri(event)"
						class="control size-xs icon-button tooltip log-icon"
						type="button"
						:data-tip="reviewOpenFor(event.id) ? 'Hide tool UI' : 'Show tool UI'"
						@click="toggleReview(event.id)">
						<svg
							viewBox="0 0 24 24"
							aria-hidden="true"
							focusable="false">
							<path
								fill="currentColor"
								d="M12 4C6.5 4 2 7.9 2 12s4.5 8 10 8 10-3.9 10-8-4.5-8-10-8zm0 13a5 5 0 1 1 0-10 5 5 0 0 1 0 10zm0-8a3 3 0 1 0 0 6 3 3 0 0 0 0-6z"/>
						</svg>
					</button>
					<button
						v-if="canInspectEvent(event)"
						class="control size-xs icon-button tooltip log-icon"
						type="button"
						data-tip="Inspect I/O"
						@click="inspectEvent(event)">
						<svg
							viewBox="0 0 24 24"
							aria-hidden="true"
							focusable="false">
							<path
								fill="currentColor"
								d="M12 4C6.5 4 2 7.9 2 12s4.5 8 10 8 10-3.9 10-8-4.5-8-10-8zm0 13a5 5 0 1 1 0-10 5 5 0 0 1 0 10zm0-8a3 3 0 1 0 0 6 3 3 0 0 0 0-6z"/>
						</svg>
					</button>
					<button
						class="control size-xs icon-button tooltip log-icon"
						type="button"
						:data-tip="openDetails[event.id] ? 'Hide details' : 'Show details'"
						@click="toggleEventDetails(event.id)">
						<svg
							viewBox="0 0 24 24"
							aria-hidden="true"
							focusable="false">
							<path
								fill="currentColor"
								d="M5 5h14v2H5V5zm0 6h14v2H5v-2zm0 6h10v2H5v-2z"/>
						</svg>
					</button>
					<button
						class="control size-xs icon-button tooltip log-icon"
						type="button"
						data-tip="Copy raw"
						@click="copyEventPayload(event)">
						<svg
							viewBox="0 0 24 24"
							aria-hidden="true"
							focusable="false">
							<path
								fill="currentColor"
								d="M16 1H6a2 2 0 0 0-2 2v12h2V3h10V1zm3 4H10a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h9a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2zm0 16H10V7h9v14z"/>
						</svg>
					</button>
				</div>
				<span
					v-if="!isStreaming && event.type === 'ASSISTANT_MESSAGE' && eventAborted(event)"
					class="control size-2xs log-status aborted"
					:class="{ tooltip: eventComment(event) }"
					:data-tip="eventComment(event)">Aborted</span>
				<button
					v-if="thinkText()"
					class="control size-2xs icon-button tooltip log-icon think-icon"
					type="button"
					:data-tip="thinkText()"
					@click.stop.prevent>
					<svg
						viewBox="0 0 24 24"
						aria-hidden="true"
						focusable="false">
						<path
							fill="currentColor"
							d="M12 2a7 7 0 0 0-7 7c0 2.38 1.19 4.47 3 5.74V17h8v-2.26c1.81-1.27 3-3.36 3-5.74a7 7 0 0 0-7-7zm-3 19h6v-2H9v2z"/>
					</svg>
				</button>
				<span v-if="!isStreaming" class="log-time">{{ formatTimestamp(event.created_at) }}</span>
				<span v-if="isStreaming" class="control size-2xs log-ui">Answering...</span>
			</div>
		</div>
		<div
			v-if="!isStreaming && isToolEvent(event) && toolInputTemplate(event)"
			class="tool-input-template">
			<MarkdownMessage :content="toolInputTemplate(event)"/>
		</div>
		<div class="event-body">
			<div v-if="!isStreaming && isWorkerProgress" class="worker-event">
				<div class="tool-response">
					<span
						class="tool-icon"
						aria-hidden="true"
						title="Tool response">
						<svg viewBox="0 0 24 24" focusable="false">
							<path fill="currentColor" d="M5 12h10.6l-4-4 1.4-1.4L19.4 12l-6.4 6.4-1.4-1.4 4-4H5z"/>
						</svg>
					</span>
					<p class="event-preview tool-response-text">{{ workerSummary }}</p>
				</div>
				<button
					class="control size-xs ghost worker-event-action"
					type="button"
					@click="openWorkerPane(event)">Show worker details</button>
				<div v-if="workerToolEvent && openDetails[workerToolEvent.id]" class="event-detail">
					<pre class="code-block">{{ JSON.stringify(eventDetailPayload(workerToolEvent), null, 2) }}</pre>
				</div>
			</div>
			<div v-else-if="!isStreaming && event.type === 'DECISION_REQUEST'" class="decision-card">
				<div class="decision-requests">
					<div
						v-for="(request, index) in decisionRequests(event)"
						:key="request.id || index"
						class="decision-request">
						<div class="decision-request-row">
							<div class="decision-request-main">
								<div class="decision-summary-line">
									<span class="decision-tool">{{ decisionRequestSummary(request) || request.tool_name }}</span>
									<span v-if="request.preview_failed" class="control size-2xs pill tag error">Preview failed</span>
									<span
										v-if="decisionStatus(event) && decisionRequestResult(event, request)"
										class="control size-2xs pill tag"
										:class="decisionRequestResult(event, request) === 'allow' ? 'success' : 'error'">{{ decisionRequestResult(event, request) === 'allow' ? 'Allowed' : 'Denied' }}</span>
									<span
										v-for="scope in !decisionStatus(event) ? request?.scopes || [] : []"
										:key="scope"
										class="control size-2xs pill tag">{{ scope }}</span>
								</div>
								<span v-if="request.error" class="form-error">{{ request.error }}</span>
								<span v-if="decisionRequestSummary(request)" class="decision-tool-name">{{ request.tool_name }}</span>
								<div v-else class="decision-result">
									<span
										v-if="decisionRequestResult(event, request)"
										class="control size-xs pill tag"
										:class="decisionRequestResult(event, request) === 'allow' ? 'success' : 'error'">{{ decisionRequestResult(event, request) === 'allow' ? 'Approved' : 'Denied' }}</span>
									<span v-if="decisionStatus(event)?.remember" class="control size-xs pill tag muted">Remember</span>
									<span v-if="decisionApprovedBy(event)" class="control size-xs pill tag muted">
										Approved by {{ decisionApprovedBy(event) }}
									</span>
								</div>
							</div>
							<button
								class="control size-xs icon-button tooltip tip-left log-icon"
								type="button"
								data-tip="Details"
								@click="toggleDecisionRequestDetails(event.id, decisionRequestKey(request, index))">
								<svg
									viewBox="0 0 24 24"
									aria-hidden="true"
									focusable="false">
									<path fill="currentColor" d="M5 5h14v2H5V5zm0 6h14v2H5v-2zm0 6h10v2H5v-2z"/>
								</svg>
							</button>
							<button
								class="control size-xs icon-button tooltip tip-left log-icon"
								type="button"
								data-tip="Inspect I/O"
								@click="openDecisionInspect(request, decisionRequestSummary(request))">
								<svg
									viewBox="0 0 24 24"
									aria-hidden="true"
									focusable="false">
									<path
										fill="currentColor"
										d="M12 4C6.5 4 2 7.9 2 12s4.5 8 10 8 10-3.9 10-8-4.5-8-10-8zm0 13a5 5 0 1 1 0-10 5 5 0 0 1 0 10zm0-8a3 3 0 1 0 0 6 3 3 0 0 0 0-6z"/>
								</svg>
							</button>
						</div>
						<div
							v-if="decisionRequestDetailsOpenFor(event.id, decisionRequestKey(request, index))"
							class="decision-args">
							<pre class="code-block">{{ JSON.stringify(request.arguments, null, 2) }}</pre>
						</div>
						<div
							v-if="!decisionStatus(event) && decisionInputTemplate(request)"
							class="decision-template">
							<MarkdownMessage :content="decisionInputTemplate(request)"/>
						</div>
						<div v-if="!decisionStatus(event) && decisionReviewUri(request)" class="event-ui">
							<mcp-view
								defer
								:src="decisionToolViewState(request, index).src"
								:resource-base="decisionToolViewState(request, index).resourceBase"
								:tool-caller="noopToolCaller"
								:css="decisionToolViewState(request, index).css"
								:auto-height="true"
								:max-height="800"
								:data="decisionToolViewState(request, index).data"
								:toolResult="decisionToolViewState(request, index).toolResult"
								:allowedOrigins="decisionToolViewState(request, index).allowedOrigins"
								:csp="decisionToolViewState(request, index).csp"></mcp-view>
						</div>
					</div>
				</div>
				<div v-if="!decisionStatus(event)" class="decision-scopes">
					<ScopeViewer
						:scopes="decisionRequestedScopes(event)"
						:allow-scopes="decisionScopeState[event.id]?.allow || []"
						:deny-scopes="decisionScopeState[event.id]?.deny || []"
						:editable="true"
						:allow-dynamic="true"
						allow-label="Allow"
						deny-label="Deny"
						dynamic-label="Undecided"
						@update:scopes="(payload) => updateDecisionScopeState(event.id, payload)"/>
				</div>
				<div v-if="!decisionStatus(event)" class="decision-footer">
					<div class="decision-buttons">
						<button
							v-if="decisionAllUndecided(event)"
							class="control size-s"
							type="button"
							@click="sendDecision(event, 'allow')">Allow</button>
						<button
							v-if="decisionAllUndecided(event)"
							class="control size-s secondary"
							type="button"
							@click="sendDecision(event, 'deny')">Deny</button>
						<button
							v-else
							class="control size-s secondary"
							type="button"
							:disabled="decisionMissingScopes(event).length > 0"
							@click="sendDecision(event, 'allow')">Apply</button>
						<label class="switch">
							<input
								type="checkbox"
								:checked="decisionRememberValue(event)"
								@change="decisionRemember[event.id] = !decisionRememberValue(event)"/>
							<span>Remember</span>
						</label>
					</div>
				</div>
			</div>
			<template v-else-if="!isStreaming && event.type === 'ASSISTANT_MESSAGE'">
				<MarkdownMessage :content="content()"/>
				<EventAttachments
					:attachments="eventAttachments(event)"
					:attachment-url="attachmentUrl"
					:download-attachment="downloadAttachment"
					:download-state-for="downloadStateFor"
					:open-image-preview="openImagePreview"/>
			</template>
			<template v-else-if="!isStreaming && event.type === 'USER_MESSAGE'">
				<MarkdownMessage v-if="content()" :content="content()"/>
				<EventAttachments
					:attachments="eventAttachments(event)"
					:attachment-url="attachmentUrl"
					:download-attachment="downloadAttachment"
					:download-state-for="downloadStateFor"
					:open-image-preview="openImagePreview"/>
			</template>
			<template v-else-if="!isStreaming && event.type === 'CONTEXT_MESSAGE'">
				<details class="context-block">
					<summary>Context</summary>
					<div class="context-body">
						<MarkdownMessage v-if="content()" :content="content()"/>
					</div>
				</details>
			</template>
			<template v-else-if="!isStreaming && isToolEvent(event)">
				<div v-if="!shouldHideToolResponse(event)" class="tool-response">
					<span
						class="tool-icon"
						aria-hidden="true"
						title="Tool response">
						<svg viewBox="0 0 24 24" focusable="false">
							<path fill="currentColor" d="M5 12h10.6l-4-4 1.4-1.4L19.4 12l-6.4 6.4-1.4-1.4 4-4H5z"/>
						</svg>
					</span>
					<p
						v-if="toolResultSummaryText(event)"
						class="event-preview tool-response-text"
						:class="{ bad: toolResultSummaryError(event) || toolStatus(event) === 'error' || toolStatus(event) === 'cancelled' }">{{ toolResultSummaryText(event) }}</p>
					<p v-else-if="isToolPending(event)" class="event-preview tool-response-text tool-pending">Working...</p>
					<p
						v-else-if="eventPreview(event)"
						class="event-preview tool-response-text"
						:class="{ bad: toolStatus(event) === 'error' || toolStatus(event) === 'cancelled' }">{{ eventPreview(event) }}</p>
				</div>
			</template>
			<MarkdownMessage v-else-if="isStreaming" :content="content()"/>
			<p v-else-if="eventPreview(event)" class="event-preview">{{ eventPreview(event) }}</p>
			<div
				v-if="!isStreaming && toolReviewUri(event) && reviewOpenFor(event.id)"
				class="event-ui tool-ui">
				<mcp-view
					defer
					:src="toolUiViewState(event).src"
					:resource-base="toolUiViewState(event).resourceBase"
					:tool-caller="noopToolCaller"
					:css="toolUiViewState(event).css"
					:auto-height="true"
					:max-height="800"
					:data="toolUiViewState(event).data"
					:toolResult="toolUiViewState(event).toolResult"
					:allowedOrigins="toolUiViewState(event).allowedOrigins"
					:csp="toolUiViewState(event).csp"></mcp-view>
			</div>
			<div v-if="!isStreaming && openDetails[event.id]" class="event-detail">
				<pre class="code-block">{{ JSON.stringify(eventDetailPayload(event), null, 2) }}</pre>
			</div>
		</div>
		<div v-if="!isStreaming && toolOutputTemplate(event)" class="tool-output-template">
			<MarkdownMessage :content="visibleToolOutputTemplate(event)"/>
			<button
				v-if="toolOutputTemplateTruncated(event)"
				class="control size-xs ghost tool-output-toggle"
				type="button"
				@click="toggleToolOutputExpanded(event.id)">{{ toolOutputExpandedFor(event.id) ? 'View less' : 'View more' }}</button>
		</div>
	</div>
</template>
<style scoped>
.worker-event {
	display: flex;
	flex-direction: column;
	gap: var(--space-xs);
	padding: 0;
	border-radius: 10px;
	border: 0;
	background: none;
	> .tool-request-label {
		margin-bottom: 0.1rem;
	}
}

.worker-event-action {
	align-self: start;
	color: color-mix(in srgb, var(--accent) 70%, var(--text-muted));
	padding: 0;
	font-size: var(--font-size-s);
}

.worker-event-action:hover {
	color: var(--accent-strong);
}
</style>
