<script setup>
import SessionEventRow from './SessionEventRow.vue';
defineProps({
	events: { type: Array, default: () => [] },
	streamingContent: { type: String, default: '' },
	streamingThinkText: { type: String, default: '' },
	selectedSessionId: { type: String, default: '' },
	rowProps: { type: Object, required: true }
})
</script>
<template>
	<SessionEventRow
		v-for="event in events"
		:key="event.renderId || event.id"
		:event="event"
		v-bind="rowProps"/>
	<SessionEventRow
		v-if="streamingContent && selectedSessionId"
		:key="`stream-${selectedSessionId}`"
		:event="{ id: `stream-${selectedSessionId}`, type: 'ASSISTANT_MESSAGE', created_at: new Date().toISOString() }"
		:is-streaming="true"
		:streaming-content="streamingContent"
		:streaming-think-text="streamingThinkText"
		v-bind="rowProps"/>
	<p v-if="events.length === 0 && !streamingContent" class="empty">No events yet.</p>
</template>
