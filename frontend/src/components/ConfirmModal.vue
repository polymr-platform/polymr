<script setup>
const props = defineProps({
	open: { type: Boolean, default: false },
	title: { type: String, default: 'Confirm action' },
	message: { type: String, default: '' },
	confirmLabel: { type: String, default: 'Confirm' },
	cancelLabel: { type: String, default: 'Cancel' },
	destructive: { type: Boolean, default: false }
})
const emit = defineEmits(['confirm', 'cancel', 'update:open'])
const handleCancel = () => {
	emit('update:open', false)
	emit('cancel')
}
const handleConfirm = () => {
	emit('confirm')
	emit('update:open', false)
}
</script>
<template>
	<teleport to="body">
		<div
			v-if="open"
			class="modal-backdrop"
			@click.self="handleCancel">
			<div
				class="modal-card"
				role="dialog"
				aria-modal="true">
				<h3>{{ title }}</h3>
				<p v-if="message" class="subtle">{{ message }}</p>
				<slot/>
				<div class="row-actions">
					<button
						class="control size-m ghost"
						type="button"
						@click="handleCancel">{{ cancelLabel }}</button>
					<button
						class="control size-m primary"
						:class="{ danger: destructive }"
						type="button"
						@click="handleConfirm">{{ confirmLabel }}</button>
				</div>
			</div>
		</div>
	</teleport>
</template>
