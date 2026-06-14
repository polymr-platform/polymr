<script setup>
import { ref } from 'vue';
defineOptions({ name: 'ConditionGroupEditor' })
const props = defineProps({
	group: { type: Object, required: true },
	depth: { type: Number, default: 0 },
	variableOptions: { type: Array, default: () => [] },
	variableSchema: { type: Object, default: () => ({ type: 'object', properties: {} }) }
})
const expandedRules = ref(new Set())
const expandedGroups = ref(new Set())
const addCondition = () => {
	if (!props.group.conditions) {
		props.group.conditions = []
	}
	props.group
		.conditions
		.push({
		kind: 'condition',
		path: '',
		operator: 'equals',
		value: '',
		value_type: 'string'
	})
}
const addGroup = () => {
	if (!props.group.conditions) {
		props.group.conditions = []
	}
	props.group.conditions.push({ kind: 'group', mode: 'and', conditions: [] })
}
const removeEntry = (index) => {
	if (!props.group.conditions) {
		return
	}
	props.group.conditions.splice(index, 1)
}
const updateMode = (value) => {
	props.group.mode = value
}
const toggleRule = (key) => {
	const next = new Set()
	if (!expandedRules.value.has(key)) {
		next.add(key)
	}
	expandedRules.value = next
}
const toggleGroup = (key) => {
	const next = new Set(expandedGroups.value)
	if (next.has(key)) {
		next.delete(key)
	}
	else {
		next.add(key)
	}
	expandedGroups.value = next
}
const groupSummary = (entry) => {
	if (!entry || !Array.isArray(entry.conditions) || entry.conditions.length === 0) {
		return 'Empty group'
	}
	const label = (condition) => {
		if (!condition) {
			return ''
		}
		if (condition.kind === 'group') {
			const inner = (condition.conditions || []).map(label).filter(Boolean)
			if (!inner.length) {
				return ''
			}
			const joiner = condition.mode === 'or' ? ' OR ' : ' AND '
			return `(${inner.join(joiner)})`
		}
		if (condition.operator === 'is_empty') {
			return `${condition.path} is empty`
		}
		if (condition.operator === 'is_not_empty') {
			return `${condition.path} is not empty`
		}
		if (condition.operator === 'has_value') {
			return `${condition.path} has value`
		}
		if (condition.operator === 'has_no_value') {
			return `${condition.path} has no value`
		}
		if (condition.operator === 'contains') {
			const valueText = condition.value_type === 'variable' ? condition.value : `"${condition.value}"`
			return `${condition.path} contains ${valueText}`
		}
		if (!condition.path || !condition.value) {
			return condition.path || ''
		}
		const operatorText = condition.operator === 'not_equals' ? '!=' : condition.operator === 'in' ? 'in' : '='
		const valueText = condition.value_type === 'variable' ? condition.value : `"${condition.value}"`
		return `${condition.path} ${operatorText} ${valueText}`
	}
	const joiner = entry.mode === 'or' ? ' OR ' : ' AND '
	const inner = entry.conditions.map(label).filter(Boolean)
	return inner.length ? inner.join(joiner) : 'Empty group'
}
const emit = defineEmits(['change'])
const pathOptionsForEntry = (entry) => {
	const options = Array.isArray(props.variableOptions) ? [...props.variableOptions] : []
	const current = String(entry?.path || '').trim()
	if (current && !options.some((option) => option.value === current)) {
		options.unshift({ value: current, label: `${current} (current)` })
	}
	return options
}
const schemaAtPath = (path) => {
	if (!path || !props.variableSchema || typeof props.variableSchema !== 'object') {
		return null
	}
	const parts = String(path).split('.').map((part) => part.trim()).filter(Boolean)
	let current = props.variableSchema
	for (const part of parts) {
		if (!current || typeof current !== 'object') {
			return null
		}
		if (current.type === 'array') {
			current = current.items || null
		}
		if (!current || typeof current !== 'object') {
			return null
		}
		const properties = current.properties && typeof current.properties === 'object' ? current.properties : null
		if (!properties || !properties[part]) {
			return null
		}
		current = properties[part]
	}
	return current
}
const pathSchema = (entry) => schemaAtPath(entry?.path)
const isArrayPath = (path) => schemaAtPath(path)?.type === 'array'
const isScalarArraySchema = (schema) => {
	const itemType = schema?.items?.type
	return ['string', 'number', 'integer', 'boolean'].includes(itemType)
}
const operatorOptionsForEntry = (entry) => {
	const schema = pathSchema(entry)
	if (schema?.type === 'array') {
		const options = [
			{ value: 'is_empty', label: 'Is empty' },
			{ value: 'is_not_empty', label: 'Is not empty' },
		]
		if (isScalarArraySchema(schema)) {
			options.push({ value: 'contains', label: 'Contains' })
		}
		return options
	}
	return [
		{ value: 'equals', label: 'Equals' },
		{ value: 'not_equals', label: 'Not equals' },
		{ value: 'in', label: 'In list' },
		{ value: 'has_value', label: 'Has value' },
		{ value: 'has_no_value', label: 'Has no value' },
	]
}
const requiresValue = (entry) => !['is_empty', 'is_not_empty', 'has_value', 'has_no_value'].includes(entry?.operator)
const ensureValidOperator = (entry) => {
	const options = operatorOptionsForEntry(entry)
	if (!options.some((option) => option.value === entry.operator)) {
		entry.operator = options[0]?.value || 'equals'
	}
	if (!requiresValue(entry)) {
		entry.value = ''
		entry.value_type = 'string'
	}
}
const onPathChange = (entry) => {
	ensureValidOperator(entry)
	notifyChange()
}
const notifyChange = () => {
	emit('change')
}
</script>
<template>
	<div class="workflow-condition-group" :style="{ marginLeft: `${depth * 12}px` }">
		<div class="workflow-condition-header">
			<div class="workflow-condition-mode">
				<span class="subtle">Match</span>
				<select :value="group.mode || 'and'" @change="updateMode($event.target.value); notifyChange()">
					<option value="and">All (AND)</option>
					<option value="or">Any (OR)</option>
				</select>
			</div>
			<div class="workflow-condition-actions">
				<button
					class="control size-xs ghost"
					type="button"
					@click="addCondition(); notifyChange()">Add condition</button>
				<button
					class="control size-xs ghost"
					type="button"
					@click="addGroup(); notifyChange()">Add group</button>
			</div>
		</div>
		<div v-if="!group.conditions || group.conditions.length === 0" class="subtle">
			No conditions yet.
		</div>
		<div v-else class="workflow-condition-list">
			<div
				v-for="(entry, index) in group.conditions"
				:key="index"
				class="workflow-condition-row">
				<template v-if="entry.kind === 'group'">
					<div class="workflow-condition-inline">
						<span class="workflow-condition-connector">{{ (group.mode || 'and').toUpperCase() }}</span>
						<span class="workflow-condition-text">{{ groupSummary(entry) }}</span>
						<button
							class="control size-xs ghost icon-button"
							type="button"
							@click="toggleGroup(`${depth}-${index}`)">
							<svg
								viewBox="0 0 24 24"
								aria-hidden="true"
								focusable="false">
								<path
									fill="currentColor"
									:d="expandedGroups.has(`${depth}-${index}`)
                    ? 'M18.3 5.71 12 12l6.3 6.29-1.41 1.42L10.59 13.4 4.29 19.7 2.88 18.3l6.3-6.3-6.3-6.29L4.29 4.3l6.3 6.3 6.29-6.3z'
                    : 'M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zm2.92 2.33H5v-.92l9.06-9.06.92.92L5.92 19.58zM20.71 7.04a1 1 0 0 0 0-1.41l-2.34-2.34a1 1 0 0 0-1.41 0l-1.5 1.5 3.75 3.75 1.5-1.5z'"/>
							</svg>
						</button>
					</div>
					<ConditionGroupEditor
						v-if="expandedGroups.has(`${depth}-${index}`)"
						:group="entry"
						:depth="depth + 1"
						:variable-options="variableOptions"
						:variable-schema="variableSchema"
						@change="notifyChange"/>
					<button
						class="control size-xs ghost"
						type="button"
						@click="removeEntry(index)">Remove group</button>
				</template>
				<template v-else>
					<div class="workflow-condition-inline">
						<span class="workflow-condition-connector">{{ (group.mode || 'and').toUpperCase() }}</span>
						<span class="workflow-condition-text">
							{{ entry.path }}
							<span v-if="entry.operator === 'is_empty'">is empty</span>
							<span v-else-if="entry.operator === 'is_not_empty'">is not empty</span>
							<span v-else-if="entry.operator === 'has_value'">has value</span>
							<span v-else-if="entry.operator === 'has_no_value'">has no value</span>
							<span v-else-if="entry.operator === 'contains' && entry.path && entry.value">
								contains
								<span v-if="entry.value_type === 'variable'">{{ entry.value }}</span>
								<span v-else>"{{ entry.value }}"</span>
							</span>
							<span v-else-if="entry.path && entry.value">
								{{ entry.operator === 'not_equals' ? '!=' : entry.operator === 'in' ? 'in' : '=' }}
								<span v-if="entry.value_type === 'variable'">{{ entry.value }}</span>
								<span v-else>"{{ entry.value }}"</span>
							</span>
						</span>
						<button
							class="control size-xs ghost icon-button"
							type="button"
							@click="toggleRule(`${depth}-${index}`)">
							<svg
								viewBox="0 0 24 24"
								aria-hidden="true"
								focusable="false">
								<path
									fill="currentColor"
									:d="expandedRules.has(`${depth}-${index}`)
                    ? 'M18.3 5.71 12 12l6.3 6.29-1.41 1.42L10.59 13.4 4.29 19.7 2.88 18.3l6.3-6.3-6.3-6.29L4.29 4.3l6.3 6.3 6.29-6.3z'
                    : 'M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zm2.92 2.33H5v-.92l9.06-9.06.92.92L5.92 19.58zM20.71 7.04a1 1 0 0 0 0-1.41l-2.34-2.34a1 1 0 0 0-1.41 0l-1.5 1.5 3.75 3.75 1.5-1.5z'"/>
							</svg>
						</button>
						<button
							class="control size-xs ghost"
							type="button"
							@click="removeEntry(index)">Remove</button>
					</div>
					<div v-if="expandedRules.has(`${depth}-${index}`)" class="workflow-condition-editor">
						<label class="field">
							<span>Path</span>
							<select v-model="entry.path" @change="onPathChange(entry)">
								<option value="" disabled>Select a variable</option>
								<option
									v-for="option in pathOptionsForEntry(entry)"
									:key="option.value"
									:value="option.value">{{ option.label }}</option>
							</select>
						</label>
						<label class="field">
							<span>Operator</span>
							<select v-model="entry.operator" @change="notifyChange">
								<option
									v-for="option in operatorOptionsForEntry(entry)"
									:key="option.value"
									:value="option.value">{{ option.label }}</option>
							</select>
						</label>
						<label class="field">
							<span>Value</span>
							<template v-if="requiresValue(entry)">
								<div class="workflow-value-toggle">
									<button
										class="control size-xs ghost"
										:class="{ active: (entry.value_type || 'string') === 'string' }"
										type="button"
										@click="entry.value_type = 'string'; notifyChange()">String</button>
									<button
										class="control size-xs ghost"
										:class="{ active: entry.value_type === 'variable' }"
										type="button"
										@click="entry.value_type = 'variable'; notifyChange()">Variable</button>
								</div>
								<input
									v-model="entry.value"
									type="text"
									placeholder="A, B, C"
									@input="notifyChange"/>
							</template>
							<p v-else class="subtle">No value needed for this operator.</p>
						</label>
					</div>
				</template>
			</div>
		</div>
	</div>
</template>
