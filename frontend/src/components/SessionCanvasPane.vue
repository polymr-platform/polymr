<script setup>
import { computed, defineComponent, defineExpose, h, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { toggleElementFullscreen, isElementFullscreen } from '../utils/fullscreen';
import { instantiateCompiledBundle, loadExternalFrontendImports, loadReferencedPageModules } from '../utils/polymrRuntime';
import { buildPolymrRuntimeApi } from '../utils/polymrPageSupport';
import { loadActiveTenant } from '../api';
const props = defineProps({
	tabs: { type: Array, default: () => [] },
	activeCanvasId: { type: String, default: '' },
	width: { type: Number, required: true }
})
const emit = defineEmits(['update:activeCanvasId'])
const route = useRoute()
const tenantId = loadActiveTenant()
const workspaceId = computed(() => String(route.params.workspaceId || ''))
const activeCanvas = computed(() => props.tabs.find((canvas) => canvas.logical_id === props.activeCanvasId) || props.tabs[0] || null)
const polymrApi = buildPolymrRuntimeApi({
	tenantId,
	workspaceId: workspaceId.value,
	notify: () => {},
	getUsers: async() => [],
	callScriptEnabled: false,
	createSessionEnabled: false,
	navigateEnabled: false
})
const activeCanvasComponent = ref(null)
const canvasRenderKey = ref(0)
const rootRef = ref(null)
const isFullscreen = ref(false)
const canvasError = ref('')
let refreshTimer = null
let refreshSequence = 0
const renderCanvasComponent = async(bundle) => {
	if (!bundle) {
		return null
	}
	const pages = await loadReferencedPageModules({
		tenantId,
		workspaceId: workspaceId.value,
		bundle,
		api: polymrApi
	})
	return instantiateCompiledBundle({ bundle, api: polymrApi, pages })
}
const refreshActiveCanvasComponent = async() => {
	const sequence = refreshSequence + 1
	refreshSequence = sequence
	const canvas = activeCanvas.value
	if (!canvas?.compiled_bundle) {
		if (sequence === refreshSequence) {
			activeCanvasComponent.value = null
			canvasError.value = ''
		}
		return
	}
	try {
		canvasError.value = ''
		await loadExternalFrontendImports(canvas.external_frontend_imports)
		if (sequence !== refreshSequence
				|| activeCanvas.value?.logical_id !== canvas.logical_id
				|| activeCanvas.value?.compiled_bundle !== canvas.compiled_bundle) {
			return
		}
		activeCanvasComponent.value = await renderCanvasComponent(canvas.compiled_bundle)
		canvasRenderKey.value += 1
	}
	catch (error) {
		if (sequence === refreshSequence) {
			activeCanvasComponent.value = null
			canvasError.value = error?.message ? String(error.message) : 'Canvas preview unavailable.'
		}
	}
}
watch(
	() => [
		activeCanvas.value?.logical_id || '',
		activeCanvas.value?.compiled_bundle || '',
		JSON.stringify(activeCanvas.value?.external_frontend_imports || []),
	],
	() => {
		if (refreshTimer) {
			clearTimeout(refreshTimer)
			refreshTimer = null
		}
		refreshActiveCanvasComponent()
	},
	{ immediate: true }
)
watch(
	() => props.width,
	() => {
		if (refreshTimer) {
			clearTimeout(refreshTimer)
		}
		refreshTimer = window.setTimeout(
			() => {
				refreshTimer = null
				refreshActiveCanvasComponent()
			},
			150
		)
	}
)
const CanvasRenderer = defineComponent({
	name: 'CanvasRenderer',
	props: { component: { type: [Object, Function], default: null } },
	setup(canvasProps) {
		return () => (canvasProps.component ? h(canvasProps.component) : null)
	}
})
const selectCanvas = (logicalId) => {
	emit('update:activeCanvasId', logicalId)
}
const syncFullscreenState = () => {
	isFullscreen.value = isElementFullscreen(rootRef.value)
	refreshActiveCanvasComponent()
}
const toggleFullscreen = async() => {
	try {
		await toggleElementFullscreen(rootRef.value)
	}
	catch {}
}
onMounted(() => {
	document.addEventListener('fullscreenchange', syncFullscreenState)
	syncFullscreenState()
})
onBeforeUnmount(() => {
	document.removeEventListener('fullscreenchange', syncFullscreenState)
	if (refreshTimer) {
		clearTimeout(refreshTimer)
		refreshTimer = null
	}
})
defineExpose({ rootRef })
</script>
<template>
	<aside
		ref="rootRef"
		class="canvas-pane"
		:class="{ fullscreen: isFullscreen }"
		:style="isFullscreen ? null : { width: `${width}px` }">
		<div class="canvas-tabs">
			<div class="canvas-tab-list">
				<button
					v-for="canvas in tabs"
					:key="canvas.logical_id"
					class="canvas-tab"
					:class="{ active: activeCanvasId === canvas.logical_id }"
					type="button"
					@click="selectCanvas(canvas.logical_id)">{{ canvas.title }}</button>
			</div>
			<button
				class="canvas-fullscreen-toggle"
				type="button"
				@click="toggleFullscreen">{{ isFullscreen ? 'Pop back' : 'Full screen' }}</button>
		</div>
		<div class="canvas-surface">
			<div class="canvas-viewport">
				<CanvasRenderer
					v-if="activeCanvasComponent"
					:key="canvasRenderKey"
					:component="activeCanvasComponent"/>
				<div v-else class="canvas-empty subtle">{{ canvasError || 'Canvas preview unavailable.' }}</div>
			</div>
		</div>
	</aside>
</template>
<style scoped>
.canvas-pane {
	display: flex;
	flex: 0 0 auto;
	flex-direction: column;
	min-width: 0;
	min-height: 0;
	max-width: calc(100% - 360px);
	border: 1px solid rgba(148, 163, 184, 0.18);
	border-radius: 16px;
	background: rgba(15, 23, 42, 0.3);
	overflow: hidden;
}

.canvas-pane.fullscreen {
	width: 100%;
	max-width: none;
	height: 100%;
	border-radius: 0;
}

.canvas-tabs {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 0.75rem;
	padding: 0.75rem;
	border-bottom: 1px solid rgba(148, 163, 184, 0.14);
}

.canvas-tab-list {
	display: flex;
	flex: 1 1 auto;
	flex-wrap: wrap;
	gap: 0.5rem;
	min-width: 0;
}

.canvas-fullscreen-toggle {
	flex: 0 0 auto;
	border: 1px solid rgba(148, 163, 184, 0.22);
	background: rgba(30, 41, 59, 0.45);
	color: inherit;
	border-radius: 999px;
	padding: 0.35rem 0.75rem;
	cursor: pointer;
	white-space: nowrap;
}

.canvas-fullscreen-toggle:hover {
	border-color: rgba(96, 165, 250, 0.45);
}

.canvas-tab {
	border: 1px solid rgba(148, 163, 184, 0.22);
	background: rgba(30, 41, 59, 0.45);
	color: inherit;
	border-radius: 999px;
	padding: 0.35rem 0.75rem;
	cursor: pointer;
}

.canvas-tab.active {
	background: rgba(96, 165, 250, 0.2);
	border-color: rgba(96, 165, 250, 0.45);
}

.canvas-surface {
	flex: 1;
	min-width: 0;
	min-height: 0;
	overflow: auto;
	padding: 0.75rem;
}

.canvas-viewport {
	display: block;
	width: 100%;
	height: 100%;
	min-width: 0;
	min-height: 0;
	max-width: 100%;
	max-height: 100%;
	overflow: auto;
}

.canvas-viewport > * {
	display: block;
	width: 100%;
	min-width: 0;
	max-width: 100%;
}

.canvas-empty {
	padding: 1rem;
}

@media (max-width: 900px) {
	.canvas-tabs {
		align-items: stretch;
		flex-direction: column;
	}
	.canvas-fullscreen-toggle {
		align-self: flex-end;
	}
}
</style>
