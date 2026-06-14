<script setup>
const props = defineProps({ fields: { type: Array, required: true }, root: { type: Boolean, default: false } })
const emit = defineEmits(['add-field', 'remove-field', 'type-change'])
const addChild = (field) => {
	if (!Array.isArray(field.children)) {
		field.children = []
	}
	emit('add-field', field.children)
}
const removeField = (field) => {
	emit('remove-field', props.fields, field.id)
}
const onTypeChange = (field) => {
	emit('type-change', field)
}
</script>
<template>
	<div class="stack">
		<div
			v-for="field in fields"
			:key="field.id"
			class="workflow-state-field-card">
			<div class="workflow-state-field-row">
				<input
					v-model="field.name"
					class="workflow-state-control workflow-state-control-input"
					type="text"
					placeholder="property_name"/>
				<select
					v-model="field.type"
					class="workflow-state-control workflow-state-control-select"
					@change="onTypeChange(field)">
					<option value="string">String</option>
					<option value="date">Date</option>
					<option value="time">Time</option>
					<option value="dateTime">Date time</option>
					<option value="number">Decimal</option>
					<option value="integer">Integer</option>
					<option value="boolean">Boolean</option>
					<option value="object">Object</option>
				</select>
				<label class="workflow-state-required">
					<input v-model="field.isArray" type="checkbox"/>
					<span>Array</span>
				</label>
				<label class="workflow-state-required">
					<input v-model="field.required" type="checkbox"/>
					<span>Required</span>
				</label>
				<button
					class="icon-button icon-ghost danger"
					type="button"
					aria-label="Remove field"
					title="Remove field"
					@click="removeField(field)">
					<svg
						viewBox="0 0 24 24"
						fill="none"
						stroke="currentColor"
						stroke-width="1.8"
						stroke-linecap="round"
						stroke-linejoin="round"
						aria-hidden="true">
						<path d="M3 6h18"/>
						<path d="M8 6V4h8v2"/>
						<path d="M19 6l-1 14H6L5 6"/>
						<path d="M10 11v6"/>
						<path d="M14 11v6"/>
					</svg>
				</button>
			</div>
			<div v-if="field.type === 'object'" class="workflow-state-nested stack">
				<div class="workflow-state-nested-head">
					<span>Nested properties</span>
					<button
						class="control size-xs ghost"
						type="button"
						@click="addChild(field)">Add property</button>
				</div>
				<WorkflowStateFieldRows
					:fields="field.children"
					@add-field="$emit('add-field', $event)"
					@remove-field="(target, id) => $emit('remove-field', target, id)"
					@type-change="$emit('type-change', $event)"/>
			</div>
		</div>
		<div class="workflow-state-field-actions">
			<button
				class="control size-s ghost"
				type="button"
				@click="$emit('add-field', fields)">{{ root ? 'Add property' : 'Add nested property' }}</button>
		</div>
	</div>
</template>
