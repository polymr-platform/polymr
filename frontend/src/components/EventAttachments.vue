<script setup>
defineProps({
	attachments: { type: Array, default: () => [] },
	downloadStateFor: { type: Function, required: true },
	downloadAttachment: { type: Function, required: true },
	openImagePreview: { type: Function, required: true },
	attachmentUrl: { type: Function, required: true }
})
</script>
<template>
	<div v-if="attachments.length" class="event-attachments">
		<template v-for="(attachment, index) in attachments" :key="`${attachment.name}-${index}`">
			<button
				v-if="attachment.isImage"
				class="attachment-thumb"
				type="button"
				@click="openImagePreview(attachmentUrl(attachment))"><img :src="attachmentUrl(attachment)" :alt="attachment.name"/></button>
			<div v-else class="attachment-file compact">
				<div class="attachment-meta">
					<span class="attachment-name">{{ attachment.name }}</span>
					<span class="attachment-type">{{ attachment.type || 'File' }}</span>
				</div>
				<div class="attachment-actions">
					<button
						v-if="!downloadStateFor(attachment)"
						class="control size-xs ghost"
						type="button"
						@click="downloadAttachment(attachment)">Download</button>
					<div v-else class="download-progress fixed">
						<span class="download-label">Downloading...</span>
						<span
							v-if="downloadStateFor(attachment).determinate"
							class="download-progress-bar"
							:style="{ width: `${downloadStateFor(attachment).progress || 0}%` }"></span>
					</div>
				</div>
			</div>
		</template>
	</div>
</template>
