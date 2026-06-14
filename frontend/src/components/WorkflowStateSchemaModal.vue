<script setup>
import { computed, ref, toRaw, watch } from 'vue';
import JsonSchemaEditor from './JsonSchemaEditor.vue';
import WorkflowStateFieldRows from './WorkflowStateFieldRows.vue';
import { jsonSchemaMeta } from '../data/jsonSchemaMeta';
const props = defineProps({
	open: { type: Boolean, default: false },
	modelValue: { type: Object, default: () => ({ type: 'object' }) }
})
const emit = defineEmits(['update:open', 'update:modelValue'])
let nextFieldId = 0
const createField = () => ({
	id: `field-${nextFieldId++}`,
	name: '',
	type: 'string',
	format: '',
	isArray: false,
	required: false,
	children: []
})
const cloneField = (field) => ({
	id: `field-${nextFieldId++}`,
	name: field?.name || '',
	type: field?.type || 'string',
	format: field?.format || '',
	isArray: !!field?.isArray,
	required: !!field?.required,
	children: Array.isArray(field?.children) ? field.children.map(cloneField) : []
})
const normalizeSchema = (schema) => {
	const source = schema && typeof schema === 'object' ? toRaw(schema) : schema
	if (!source || typeof source !== 'object' || Array.isArray(source)) {
		return { type: 'object', properties: {}, required: [] }
	}
	const normalized = JSON.parse(JSON.stringify(source))
	if (normalized.type !== 'object') {
		normalized.type = 'object'
	}
	if (!normalized.properties
			|| typeof normalized.properties !== 'object'
			|| Array.isArray(normalized.properties)) {
		normalized.properties = {}
	}
	if (!Array.isArray(normalized.required)) {
		normalized.required = []
	}
	return normalized
}
const fieldFromSchema = (name, schema, requiredNames = []) => {
	const sourceSchema = schema?.type === 'array' ? schema?.items || { type: 'string' } : schema
	const type = sourceSchema?.type === 'object'
			|| sourceSchema?.properties
		? 'object'
		: sourceSchema?.type === 'string'
				&& sourceSchema?.format === 'date'
			? 'date'
			: sourceSchema?.type === 'string'
					&& sourceSchema?.format === 'time'
				? 'time'
				: sourceSchema?.type === 'string'
						&& sourceSchema?.format === 'date-time'
					? 'dateTime'
					: ['string', 'number', 'integer', 'boolean'].includes(sourceSchema?.type)
						? sourceSchema.type
						: 'string'
	const field = createField()
	field.name = name || ''
	field.type = type
	field.isArray = schema?.type === 'array'
	field.required = requiredNames.includes(name)
	if (type === 'object') {
		const properties = sourceSchema?.properties
				&& typeof sourceSchema.properties === 'object'
			? sourceSchema.properties
			: {}
		const nestedRequired = Array.isArray(sourceSchema?.required) ? sourceSchema.required : []
		field.children = Object.entries(properties)
			.map(([childName, childSchema]) => fieldFromSchema(childName, childSchema, nestedRequired))
	}
	return field
}
const fieldsFromSchema = (schema) => {
	const normalized = normalizeSchema(schema)
	return Object.entries(normalized.properties)
		.map(([name, propertySchema]) => fieldFromSchema(name, propertySchema, normalized.required))
}
const schemaFromField = (field) => {
	let schema
	if (field.type === 'object') {
		const properties = {}
		const required = []
		field.children
			.forEach((child) => {
				const trimmed = child.name.trim()
				if (!trimmed) {
					return
				}
				properties[trimmed] = schemaFromField(child)
				if (child.required) {
					required.push(trimmed)
				}
			})
		schema = { type: 'object', properties }
		if (required.length) {
			schema.required = required
		}
	}
	else {
		schema = field.type === 'date'
			? { type: 'string', format: 'date' }
			: field.type === 'time'
				? { type: 'string', format: 'time' }
				: field.type === 'dateTime' ? { type: 'string', format: 'date-time' } : { type: field.type }
	}
	if (field.isArray) {
		return { type: 'array', items: schema }
	}
	return schema
}
const schemaFromFields = (fields) => {
	const properties = {}
	const required = []
	fields.forEach((field) => {
			const trimmed = field.name.trim()
			if (!trimmed) {
				return
			}
			properties[trimmed] = schemaFromField(field)
			if (field.required) {
				required.push(trimmed)
			}
		})
	const schema = { type: 'object', properties }
	if (required.length) {
		schema.required = required
	}
	return schema
}
const exampleValueFromSchema = (schema) => {
	if (!schema || typeof schema !== 'object') {
		return ''
	}
	if (schema.type === 'object' || schema.properties) {
		const value = {}
		Object.entries(schema.properties || {})
			.forEach(([name, child]) => {
				value[name] = exampleValueFromSchema(child)
			})
		return value
	}
	if (schema.type === 'array') {
		return [exampleValueFromSchema(schema.items || { type: 'string' })]
	}
	if (schema.type === 'integer' || schema.type === 'number') {
		return 0
	}
	if (schema.type === 'boolean') {
		return false
	}
	if (schema.type === 'string' && schema.format === 'date') {
		return '2026-01-31'
	}
	if (schema.type === 'string' && schema.format === 'time') {
		return '13:45:00'
	}
	if (schema.type === 'string' && schema.format === 'date-time') {
		return '2026-01-31T13:45:00Z'
	}
	return ''
}
const ensureArrayShape = (field) => {
	if (field.type === 'object' && !Array.isArray(field.children)) {
		field.children = []
	}
	if (Array.isArray(field.children)) {
		field.children.forEach(ensureArrayShape)
	}
}
const mode = ref('fields')
const schemaExpanded = ref(false)
const draftFields = ref(fieldsFromSchema(props.modelValue))
const advancedSchema = ref(normalizeSchema(props.modelValue))
const fieldEditorSchema = computed(() => schemaFromFields(draftFields.value))
const previewSchema = computed(() => mode.value === 'advanced' ? normalizeSchema(advancedSchema.value) : fieldEditorSchema.value)
const previewJson = computed(() => JSON.stringify(exampleValueFromSchema(previewSchema.value), null, 2))
watch(
	() => props.open,
	(open) => {
		if (!open) {
			return
		}
		draftFields.value = fieldsFromSchema(props.modelValue).map(cloneField)
		draftFields.value.forEach(ensureArrayShape)
		advancedSchema.value = normalizeSchema(props.modelValue)
		schemaExpanded.value = false
		mode.value = 'fields'
	}
)
watch(
	advancedSchema,
	(value) => {
		if (mode.value !== 'advanced') {
			return
		}
		draftFields.value = fieldsFromSchema(value).map(cloneField)
		draftFields.value.forEach(ensureArrayShape)
	},
	{ deep: true }
)
watch(
	mode,
	(value) => {
		if (value === 'advanced') {
			advancedSchema.value = normalizeSchema(fieldEditorSchema.value)
		}
		else {
			draftFields.value = fieldsFromSchema(advancedSchema.value).map(cloneField)
			draftFields.value.forEach(ensureArrayShape)
		}
	}
)
const addField = (target) => {
	target.push(createField())
}
const removeField = (target, id) => {
	const index = target.findIndex((field) => field.id === id)
	if (index >= 0) {
		target.splice(index, 1)
	}
}
const onTypeChange = (field) => {
	if (field.type === 'object') {
		if (!Array.isArray(field.children)) {
			field.children = []
		}
	}
	else {
		field.children = []
	}
}
const close = () => emit('update:open', false)
const save = () => {
	emit('update:modelValue', previewSchema.value)
	emit('update:open', false)
}
</script>
<template>
	<teleport to="body">
		<div
			v-if="open"
			class="modal-backdrop"
			@click.self="close">
			<div
				class="modal-card workflow-state-modal"
				role="dialog"
				aria-modal="true">
				<div class="workflow-state-modal-header">
					<div>
						<h3>Workflow state</h3>
						<p class="subtle">Define the workflow state shape for this workflow.</p>
					</div>
					<button
						class="control size-m ghost"
						type="button"
						@click="close">Close</button>
				</div>
				<div class="workflow-state-mode-tabs">
					<button
						class="control size-s ghost"
						:class="{ active: mode === 'fields' }"
						type="button"
						@click="mode = 'fields'">Field editor</button>
					<button
						class="control size-s ghost"
						:class="{ active: mode === 'advanced' }"
						type="button"
						@click="mode = 'advanced'">Advanced JSON Schema</button>
				</div>
				<div class="workflow-state-modal-body">
					<div class="workflow-state-editor-pane stack">
						<div v-if="mode === 'fields'" class="workflow-state-field-list">
							<WorkflowStateFieldRows
								:fields="draftFields"
								:root="true"
								@add-field="addField"
								@remove-field="removeField"
								@type-change="onTypeChange"/>
						</div>
						<JsonSchemaEditor
							v-else
							class="large-editor"
							v-model="advancedSchema"
							:schema="jsonSchemaMeta"/>
					</div>
					<div class="workflow-state-preview-pane stack">
						<div class="section-head">
							<span>Example JSON</span>
						</div>
						<pre class="code-block workflow-state-preview">{{ previewJson }}</pre>
						<div class="section-head">
							<span>Generated schema</span>
							<button
								class="control size-xs ghost"
								type="button"
								@click="schemaExpanded = !schemaExpanded">{{ schemaExpanded ? 'Hide schema' : 'Show schema' }}</button>
						</div>
						<pre v-if="schemaExpanded" class="code-block workflow-state-preview">{{ JSON.stringify(previewSchema, null, 2) }}</pre>
					</div>
				</div>
				<div class="row-actions">
					<button
						class="control size-m ghost"
						type="button"
						@click="close">Cancel</button>
					<button
						class="control size-m primary"
						type="button"
						@click="save">Save</button>
				</div>
			</div>
		</div>
	</teleport>
</template>
