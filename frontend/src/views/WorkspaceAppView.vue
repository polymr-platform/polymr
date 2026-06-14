<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { callMcpTool, getWorkspaceMcpApplications, loadActiveTenant, readMcpResource } from '../api';
import { mcpThemeVars, observeMcpTheme } from '../utils/mcpTheme';
const route = useRoute()
const workspaceId = route.params.workspaceId
const tenantId = ref(loadActiveTenant())
const appId = computed(() => route.params.appId)
const app = ref(null)
const loading = ref(false)
const error = ref('')
const appViewRef = ref(null)
let stopThemeObserver = null
const decodeBase64ToUint8Array = (value) => {
	const binary = atob(value)
	const bytes = new Uint8Array(binary.length)
	for (let i = 0; i < binary.length; i += 1) {
		bytes[i] = binary.charCodeAt(i)
	}
	return bytes
}
const resolveResource = async(uri) => {
	if (!app.value?.server_id) {
		return 'No application selected.'
	}
	try {
		const response = await readMcpResource(tenantId.value, workspaceId, app.value.server_id, { uri })
		if (typeof response === 'string') {
			return response
		}
		const contents = response?.contents ?? response?.content ?? []
		if (Array.isArray(contents) && contents.length) {
			const first = contents.find((entry) => entry && typeof entry === 'object') || contents[0]
			if (typeof first?.text === 'string') {
				return first.text
			}
			if (typeof first?.base64 === 'string') {
				return decodeBase64ToUint8Array(first.base64)
			}
			if (typeof first?.bytes === 'string') {
				return decodeBase64ToUint8Array(first.bytes)
			}
			if (typeof first?.data === 'string') {
				return decodeBase64ToUint8Array(first.data)
			}
		}
		return JSON.stringify(response ?? '', null, 2)
	}
	catch (err) {
		return err?.message || 'Failed to resolve application resource.'
	}
}
const callTool = async(params) => {
	if (!app.value?.server_id) {
		throw new Error('No application selected.')
	}
	const toolName = params?.name
	if (!toolName || typeof toolName !== 'string') {
		throw new Error('Tool name is required.')
	}
	const argumentsPayload = params?.arguments ?? {}
	const rawMeta = params?._meta
	const meta = rawMeta && typeof rawMeta === 'object' ? rawMeta : undefined
	const response = await callMcpTool(
		tenantId.value,
		workspaceId,
		app.value
				.server_id,
		{ tool_name: toolName, arguments: argumentsPayload, meta }
	)
	if (response && typeof response === 'object' && 'result' in response) {
		return response.result
	}
	return response
}
const applyMcpTheme = () => {
	if (!appViewRef.value) {
		return
	}
	const vars = mcpThemeVars()
	appViewRef.value.deferRender = true
	appViewRef.value.hostStyleVariables = vars
	appViewRef.value.render()
	appViewRef.value.deferRender = false
}
const attachMcpView = () => {
	if (!appViewRef.value) {
		return
	}
	appViewRef.value.resolver = resolveResource
	appViewRef.value.toolCaller = callTool
	applyMcpTheme()
}
const loadApp = async() => {
	if (!tenantId.value) {
		return
	}
	loading.value = true
	error.value = ''
	try {
		const apps = await getWorkspaceMcpApplications(tenantId.value, workspaceId)
		app.value = apps.find((entry) => entry.id === appId.value) || null
		if (!app.value) {
			error.value = 'Application not found.'
		}
	}
	catch (err) {
		error.value = err?.message || 'Unable to load application.'
	}
	finally {
		loading.value = false
		nextTick(attachMcpView)
	}
}
watch(
	appId,
	() => {
		loadApp()
	}
)
onMounted(() => {
	loadApp()
	stopThemeObserver = observeMcpTheme(() => {
		applyMcpTheme()
	})
})
onBeforeUnmount(() => {
	if (stopThemeObserver) {
		stopThemeObserver()
		stopThemeObserver = null
	}
})
</script>
<template>
	<main class="detail-view app-view">
		<header class="section-header">
			<div>
				<p class="eyebrow">Workspace</p>
				<h1>{{ app?.display_name || app?.app_name || 'Application' }}</h1>
				<p class="subtle">{{ app?.server_name ? `From ${app.server_name}` : 'Open MCP application' }}</p>
			</div>
		</header>
		<section class="panel app-panel">
			<p v-if="loading" class="subtle">Loading application…</p>
			<p v-else-if="error" class="form-error">{{ error }}</p>
			<div v-else class="app-frame">
				<mcp-view
					ref="appViewRef"
					class="app-mcp-view"
					:src="app?.app_uri"></mcp-view>
			</div>
		</section>
	</main>
</template>
