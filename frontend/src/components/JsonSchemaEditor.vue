<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
const props = defineProps({
	modelValue: { type: [Object, Array, String, Number, Boolean, null], default: null },
	schema: { type: Object, default: null },
	lenient: { type: Boolean, default: false },
	defaultCollapsed: { type: Boolean, default: true }
})
const emit = defineEmits(['update:modelValue'])
const container = ref(null)
let editor = null
let editorModule = null
const ensureEditor = async() => {
	if (editor || !container.value) {
		return
	}
	editorModule = await import(/* @vite-ignore */ 'https://cdn.jsdelivr.net/gh/celerex/json-editor@master/dist/editor.esm.js')
	editor = editorModule.createDomEditor({
		container: container.value,
		value: props.modelValue ?? {},
		schema: props.schema || null,
		lenient: props.lenient,
		defaultCollapsed: props.defaultCollapsed,
		onChange(next) {
			emit('update:modelValue', next)
		}
	})
}
onMounted(ensureEditor)
onBeforeUnmount(() => {
	if (editor) {
		editor.destroy()
		editor = null
	}
})
watch(
	() => props.schema,
	(next) => {
		if (!editor) {
			ensureEditor()
			return
		}
		editor.setSchema(next || null)
	}
)
watch(
	() => props.modelValue,
	(next) => {
		if (!editor) {
			return
		}
		editor.setValue(next ?? {})
	}
)
</script>
<template>
	<div ref="container" class="json-editor-host"></div>
</template>
