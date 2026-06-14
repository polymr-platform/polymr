<script setup>
import { computed } from 'vue';
defineOptions({ name: 'JsonTree' })
const props = defineProps({ value: { type: null, default: null } })
const isArray = computed(() => Array.isArray(props.value))
const isObject = computed(() => props.value !== null && typeof props.value === 'object' && !isArray.value)
const entries = computed(() => {
	if (isArray.value) {
		return props.value.map((item, index) => [`[${index}]`, item])
	}
	if (isObject.value) {
		return Object.entries(props.value)
	}
	return []
})
const isPrimitive = computed(() => !isArray.value && !isObject.value)
const displayValue = computed(() => {
	const value = props.value
	if (value === null) {
		return 'null'
	}
	if (value === undefined) {
		return 'undefined'
	}
	if (typeof value === 'string') {
		return `"${value}"`
	}
	return String(value)
})
</script>
<template>
	<div class="json-tree">
		<template v-if="isPrimitive">
			<span class="json-primitive">{{ displayValue }}</span>
		</template>
		<template v-else>
			<div v-if="entries.length === 0" class="json-empty">Empty</div>
			<div
				v-for="([key, val], index) in entries"
				:key="`${key}-${index}`"
				class="json-node">
				<div class="json-key">{{ key }}</div>
				<div class="json-value">
					<JsonTree :value="val"/>
				</div>
			</div>
		</template>
	</div>
</template>
<style scoped>
.json-tree {
	display: flex;
	flex-direction: column;
	gap: 0.35rem;
	font-size: 0.85rem;
	line-height: 1.3;
}

.json-node {
	display: grid;
	grid-template-columns: minmax(0, 180px) 1fr;
	gap: 0.6rem;
	padding: 0.35rem 0;
	border-bottom: 1px dashed rgba(148, 163, 184, 0.15);
}

.json-node:last-child {
	border-bottom: none;
}

.json-key {
	font-weight: 600;
	color: rgba(226, 232, 240, 0.85);
	word-break: break-word;
}

.json-value {
	color: rgba(226, 232, 240, 0.9);
	word-break: break-word;
}

.json-primitive {
	color: rgba(226, 232, 240, 0.9);
	white-space: pre-wrap;
}

.json-empty {
	color: rgba(226, 232, 240, 0.6);
	font-style: italic;
}

@media (max-width: 720px) {
	.json-node {
		grid-template-columns: 1fr;
	}
}
</style>
