<script setup>
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { getPageBundle, getPageBySlug, getWorkspaceUsers, loadActiveTenant } from '../api';
import { buildPolymrRuntimeApi } from '../utils/polymrPageSupport';
import { ensurePolymrRuntimeGlobals, instantiateCompiledBundle, loadExternalFrontendImports } from '../utils/polymrRuntime';
const route = useRoute()
const router = useRouter()
const tenantId = loadActiveTenant()
const workspaceId = route.params.workspaceId
const slug = computed(() => String(route.params.slug || ''))
const page = ref(null)
const loading = ref(true)
const error = ref('')
const runtimeComponent = ref(null)
const toast = ref('')
const toastTone = ref('')
let toastTimer = null
const notify = (payload, tone = '') => {
	const message = typeof payload === 'string' ? payload : payload?.message
	const variant = typeof payload === 'string' ? tone : payload?.variant
	if (!message) {
		return
	}
	toast.value = message
	toastTone.value = variant === 'error' ? 'danger' : (variant || '')
	if (toastTimer) {
		clearTimeout(toastTimer)
	}
	toastTimer = setTimeout(
		() => {
			toast.value = ''
			toastTone.value = ''
		},
		4000
	)
}
const getUsers = async() => {
	return getWorkspaceUsers(tenantId)
}
const polymrApi = buildPolymrRuntimeApi({
	tenantId,
	workspaceId,
	router,
	notify,
	getUsers,
	attachmentTargetId: () => page.value?.id
})
ensurePolymrRuntimeGlobals(polymrApi)
const loadPage = async() => {
	loading.value = true
	error.value = ''
	runtimeComponent.value = null
	try {
		page.value = await getPageBySlug(tenantId, workspaceId, slug.value)
		const bundleResponse = page.value?.id ? await getPageBundle(tenantId, workspaceId, page.value.id) : null
		const bundle = bundleResponse?.compiled_bundle || ''
		if (!bundle) {
			throw new Error(bundleResponse?.compile_errors || 'Page bundle is missing')
		}
		await loadExternalFrontendImports(bundleResponse?.external_frontend_imports)
		runtimeComponent.value = instantiateCompiledBundle({ bundle, api: polymrApi })
	}
	catch (err) {
		error.value = err?.message || 'Unable to load page.'
	}
	finally {
		loading.value = false
	}
}
onMounted(loadPage)
watch(
	slug,
	() => {
		loadPage()
	}
)
</script>
<template>
	<main class="page-runtime">
		<p v-if="loading" class="subtle">Loading page…</p>
		<p v-else-if="error" class="form-error">{{ error }}</p>
		<component
			v-else-if="runtimeComponent"
			:is="runtimeComponent"
			:key="slug"/>
		<div
			v-if="toast"
			class="toast"
			:class="toastTone">{{ toast }}</div>
	</main>
</template>
<style scoped>
.page-runtime {
	min-height: 100%;
	height: calc(100vh - 50px);
	padding: var(--space-2xl);
	overflow: auto;
}
</style>
