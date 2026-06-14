<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue';
import * as Vue from 'vue';
import { useRoute, useRouter } from 'vue-router';
import {
	approvePage,
	compilePageDraft,
	getInstalledPages,
	getPage,
	validatePageSlug,
	getPageDraft,
	getWorkspaceUsers,
	deletePageDraft,
	updatePage,
	updatePageDraft
} from '../api';
import { loadActiveTenant } from '../api';
import SessionView from './SessionView.vue';
import { compilePageSource } from '../utils/pageCompile';
import { buildPolymrRuntimeApi, navigateToWorkspacePage } from '../utils/polymrPageSupport';
import { ensurePolymrRuntimeGlobals, instantiateCompiledBundle, loadExternalFrontendImports } from '../utils/polymrRuntime';
const route = useRoute()
const router = useRouter()
const workspaceId = route.params.workspaceId
const pageId = route.params.pageId
const tenantId = loadActiveTenant()
const lastAssistantKey = computed(() => `polymr.lastAssistant.${workspaceId}`)
const tabs = ['Design', 'Source', 'Test', 'Configuration']
const activeTab = ref('Design')
const page = ref(null)
const draft = ref(null)
const draftLoading = ref(true)
const draftError = ref('')
const approveStatus = ref('')
const dependencyInput = ref('')
const designSessionKey = ref(0)
const isCompiling = ref(false)
const compileError = ref('')
const toast = ref('')
const toastTone = ref('')
let toastTimer = null
const compileErrorDetail = ref('')
const compiledComponent = ref(null)
const saveError = ref('')
const savingSettings = ref(false)
const deleteDraftOpen = ref(false)
const saveStatus = ref('')
const pageForm = ref({
	label: '',
	type: 'PAGE',
	route_suffix: '',
	usage_guide: '',
	query_params: '',
	input_params: '',
	import_allowlist: '',
	icon_svg: ''
})
const previewInputs = ref({ routeVars: {}, query: {}, inputs: {} })
const isCompiled = computed(() => !!draft.value?.compiled_bundle && !draft.value?.compile_errors)
const designSessionId = computed(() => draft.value?.design_session_id || '')
const previewStorageKey = computed(() => `polymr.page.preview.${pageId}`)
const sourceBlocks = ref([])
const sourcePasteActive = ref(false)
const sourcePasteValue = ref('')
const parsedQueryParams = computed(() => pageForm.value.query_params.split('\n').map((entry) => entry.trim()).filter((entry) => entry.length > 0))
const isComponentType = computed(() => pageForm.value.type === 'COMPONENT')
const parsedInputParams = computed(() => {
	if (!pageForm.value.input_params.trim()) {
		return []
	}
	try {
		const parsed = JSON.parse(pageForm.value.input_params)
		return Array.isArray(parsed) ? parsed : []
	}
	catch {
		return []
	}
})
const routeParamPattern = /\{([^}]+)\}|:([A-Za-z0-9_]+)/g
const routeVars = computed(() => {
	const suffix = pageForm.value.route_suffix || ''
	const matches = [...suffix.matchAll(routeParamPattern)]
	const names = matches.map((match) => match[1] || match[2]).filter(Boolean)
	return Array.from(new Set(names))
})
const previewProps = computed(() => {
	const props = {}
	parsedInputParams.value
		.forEach((param) => {
			const name = param?.name
			if (!name) {
				return
			}
			let value = previewInputs.value.inputs?.[name]
			const type = (param?.type || '').toLowerCase()
			if (type === 'number' && value !== '' && value !== null && value !== undefined) {
				const parsed = Number(value)
				value = Number.isNaN(parsed) ? value : parsed
			}
			if (type === 'boolean') {
				if (value === 'true') {
					value = true
				}
				else if (value === 'false') {
					value = false
				}
			}
			props[name] = value
		})
	props.routeParams = { ...previewInputs.value.routeVars }
	props.queryParams = { ...previewInputs.value.query }
	return props
})
const previewRoutePath = computed(() => {
	const base = `pages/${page.value?.slug || ''}`
	let suffix = pageForm.value.route_suffix || ''
	routeVars.value
		.forEach((name) => {
			const replacement = previewInputs.value.routeVars?.[name] || `{${name}}`
			suffix = suffix.replace(new RegExp(`\\{${name}\\}|:${name}(?=\\b|/)`, 'g'), replacement)
		})
	return `${base}${suffix}`
})
const previewQueryString = computed(() => {
	const params = new URLSearchParams()
	parsedQueryParams.value
		.forEach((name) => {
			const value = previewInputs.value.query?.[name]
			if (value !== undefined && value !== '') {
				params.set(name, value)
			}
		})
	const value = params.toString()
	return value ? `?${value}` : ''
})
const notify = async(payload, tone = '') => {
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
const navigateTo = async(path, params = {}) => {
	return navigateToWorkspacePage({
		tenantId,
		workspaceId,
		router,
		path,
		params
	})
}
const polymrApi = buildPolymrRuntimeApi({
	tenantId,
	workspaceId,
	router,
	notify,
	getUsers: () => getWorkspaceUsers(tenantId),
	attachmentTargetId: () => pageId
})
ensurePolymrRuntimeGlobals(polymrApi)
const applyPageConfig = (pageData) => {
	const allowlist = Array.isArray(pageData?.import_allowlist) ? pageData.import_allowlist : []
	const queryParams = Array.isArray(pageData?.query_params) ? pageData.query_params : []
	const inputParams = Array.isArray(pageData?.input_params) ? pageData.input_params : []
	pageForm.value = {
		label: pageData?.label || '',
		namespace: pageData?.namespace || '',
		type: pageData?.type || 'PAGE',
		route_suffix: pageData?.route_suffix || '',
		usage_guide: pageData?.usage_guide || '',
		query_params: queryParams.join('\n'),
		input_params: inputParams.length ? JSON.stringify(inputParams, null, 2) : '',
		import_allowlist: allowlist.join('\n'),
		icon_svg: pageData?.icon_svg || ''
	}
}
const loadPage = async() => {
	draftLoading.value = true
	draftError.value = ''
	try {
		page.value = await getPage(tenantId, workspaceId, pageId)
		const assistantId = localStorage.getItem(lastAssistantKey.value) || ''
		draft.value = await getPageDraft(tenantId, workspaceId, pageId, { assistant_id: assistantId })
		applyPageConfig(page.value)
		await validateSlug()
		restorePreviewInputs()
		await restoreCompiledComponent()
		designSessionKey.value += 1
	}
	catch (error) {
		draftError.value = error?.message || 'Unable to load page.'
	}
	finally {
		draftLoading.value = false
	}
}
const refreshDraft = async() => {
	if (!tenantId || !workspaceId || !pageId) {
		return
	}
	try {
		draft.value = await getPageDraft(tenantId, workspaceId, pageId)
		await restoreCompiledComponent()
	}
	catch (error) {
		draftError.value = error?.message || 'Unable to refresh draft.'
	}
}
const restorePreviewInputs = () => {
	const raw = localStorage.getItem(previewStorageKey.value)
	if (!raw) {
		previewInputs.value = { routeVars: {}, query: {}, inputs: {} }
		return
	}
	try {
		const parsed = JSON.parse(raw)
		previewInputs.value = { routeVars: parsed?.routeVars || {}, query: parsed?.query || {}, inputs: parsed?.inputs || {} }
	}
	catch {
		previewInputs.value = { routeVars: {}, query: {}, inputs: {} }
	}
}
const persistPreviewInputs = () => {
	localStorage.setItem(previewStorageKey.value, JSON.stringify(previewInputs.value))
}
const emitPagesUpdated = () => {
	if (typeof window !== 'undefined') {
		window.dispatchEvent(new CustomEvent('polymr.pages.updated'))
	}
}
const validateSlug = async() => {
	if (!tenantId || !workspaceId || !page.value?.slug) {
		return
	}
	await validatePageSlug(
		tenantId,
		workspaceId,
		{ namespace: pageForm.value.namespace || undefined, slug: page.value.slug, exclude_page_id: pageId }
	)
}
const saveAll = async() => {
	saveError.value = ''
	saveStatus.value = ''
	const inputParams = parsedInputParams.value
	if (pageForm.value.input_params.trim() && inputParams.length === 0) {
		saveError.value = 'Input params must be valid JSON array.'
		return
	}
	savingSettings.value = true
	try {
		const payload = {
			label: pageForm.value.label.trim() || null,
			namespace: pageForm.value.namespace || null,
			type: pageForm.value.type,
			route_suffix: pageForm.value.route_suffix.trim(),
			usage_guide: pageForm.value.usage_guide.trim() || null,
			query_params: parsedQueryParams.value,
			input_params: inputParams,
			import_allowlist: pageForm.value
				.import_allowlist
				.split('\n')
				.map((entry) => entry.trim())
				.filter((entry) => entry.length > 0),
			icon_svg: pageForm.value.icon_svg || null,
			dependency_ids: dependencyInput.value.split('\n').map((entry) => entry.trim()).filter((entry) => entry.length > 0)
		}
		page.value = await updatePage(tenantId, workspaceId, pageId, payload)
		saveStatus.value = 'Saved.'
		emitPagesUpdated()
	}
	catch (error) {
		saveError.value = error?.message || 'Unable to save.'
	}
	finally {
		savingSettings.value = false
	}
}
const restoreCompiledComponent = async() => {
	compileError.value = ''
	compileErrorDetail.value = ''
	compiledComponent.value = null
	if (draft.value?.compile_errors) {
		compileError.value = draft.value.compile_errors
	}
	if (!draft.value?.compiled_bundle) {
		return
	}
	try {
		await loadExternalFrontendImports(page.value?.workspace_external_frontend_imports)
		compiledComponent.value = instantiateCompiledBundle({ bundle: draft.value.compiled_bundle, api: polymrApi })
	}
	catch (error) {
		compileError.value = error?.message || 'Unable to restore compiled preview.'
	}
}
const rewriteImports = (code, allowlist, builtins) => {
	if (!code) {
		return ''
	}
	const lines = code.split('\n')
	const output = []
	const importPattern = /^import\s+([^'";]+)\s+from\s+['"]([^'"]+)['"];?$/
	const importSideEffect = /^import\s+['"]([^'"]+)['"];?$/
	lines.forEach((line) => {
			const match = line.match(importPattern)
			const sideEffect = line.match(importSideEffect)
			if (match) {
				const spec = match[1].trim()
				const mod = match[2].trim()
				if (mod !== 'vue') {
					if (!allowlist.includes(mod) && !builtins.includes(mod)) {
						throw new Error(`Import not allowed: ${mod}`)
					}
					if (mod.startsWith('@polymr/components')) {
						output.push(renderPolymrComponentImport(spec, mod))
						return
					}
					if (mod.startsWith('@polymr/api')) {
						output.push(renderPolymrApiImport(spec, mod))
						return
					}
					throw new Error(`Import not supported in preview: ${mod}`)
				}
				if (spec.startsWith('{')) {
					output.push(`const ${normalizeNamedImports(spec)} = Vue`)
				}
				else if (spec.includes(',')) {
					const [defaultImport, named] = spec.split(',')
					output.push(`const ${defaultImport.trim()} = Vue`)
					if (named) {
						output.push(`const ${normalizeNamedImports(named.trim())} = Vue`)
					}
				}
				else if (spec.startsWith('*')) {
					const name = spec.split('as')[1]?.trim() || 'Vue'
					output.push(`const ${name} = Vue`)
				}
				else {
					output.push(`const ${spec} = Vue`)
				}
				return
			}
			if (sideEffect) {
				const mod = sideEffect[1].trim()
				if (!allowlist.includes(mod) && !builtins.includes(mod)) {
					throw new Error(`Import not allowed: ${mod}`)
				}
				if (mod.startsWith('@polymr/components')) {
					return
				}
				if (mod.startsWith('@polymr/api')) {
					return
				}
				throw new Error(`Import not supported in preview: ${mod}`)
			}
			output.push(line)
		})
	return output.join('\n')
}
const renderPolymrComponentImport = (spec, mod) => {
	if (mod === '@polymr/components') {
		if (spec.startsWith('{')) {
			return `const ${normalizeNamedImports(spec)} = PolymrComponents`
		}
		if (spec.includes(',')) {
			const [defaultImport, named] = spec.split(',')
			const parts = []
			if (defaultImport.trim()) {
				parts.push(`const ${defaultImport.trim()} = PolymrComponents`)
			}
			if (named) {
				parts.push(`const ${normalizeNamedImports(named.trim())} = PolymrComponents`)
			}
			return parts.join('\n')
		}
		return `const ${spec} = PolymrComponents`
	}
	if (mod.startsWith('@polymr/components/')) {
		const componentName = mod.split('/').pop()
		if (spec.startsWith('{')) {
			return `const ${spec} = PolymrComponents`
		}
		return `const ${spec} = PolymrComponents.${componentName}`
	}
	return ''
}
const renderPolymrApiImport = (spec, mod) => {
	if (mod === '@polymr/api') {
		if (spec.startsWith('{')) {
			return `const ${normalizeNamedImports(spec)} = PolymrApi`
		}
		if (spec.includes(',')) {
			const [defaultImport, named] = spec.split(',')
			const parts = []
			if (defaultImport.trim()) {
				parts.push(`const ${defaultImport.trim()} = PolymrApi`)
			}
			if (named) {
				parts.push(`const ${normalizeNamedImports(named.trim())} = PolymrApi`)
			}
			return parts.join('\n')
		}
		return `const ${spec} = PolymrApi`
	}
	if (mod.startsWith('@polymr/api/')) {
		const fnName = mod.split('/').pop()
		if (spec.startsWith('{')) {
			return `const ${spec} = PolymrApi`
		}
		return `const ${spec} = PolymrApi.${fnName}`
	}
	return ''
}
const normalizeNamedImports = (spec) => {
	if (!spec || !spec.includes('{')) {
		return spec
	}
	return spec.replace(/\sas\s/g, ': ')
}
const compileDraft = async() => {
	compileError.value = ''
	compileErrorDetail.value = ''
	compiledComponent.value = null
	if (!draft.value?.source_sfc) {
		compileError.value = 'Draft source is empty.'
		return
	}
	isCompiling.value = true
	try {
		const allowlist = Array.isArray(page.value?.import_allowlist) ? page.value.import_allowlist : []
		const externalFrontendImports = Array.isArray(page.value?.workspace_external_frontend_imports)
			? Object.fromEntries(
				page.value
						.workspace_external_frontend_imports
						.filter((entry) => entry?.specifier && entry?.global_name && entry?.source_url)
						.map((entry) => [entry.specifier, entry.global_name])
			)
			: {}
		const result = await compilePageSource({
			source: draft.value.source_sfc,
			allowlist,
			externalFrontendImports,
			pageId
		})
		compileError.value = result.compileErrors || ''
		compileErrorDetail.value = result.compileErrorDetail || ''
		const bundle = result.compiledBundle || ''
		await loadExternalFrontendImports(page.value?.workspace_external_frontend_imports)
		compiledComponent.value = instantiateCompiledBundle({ bundle, api: polymrApi })
		draft.value = await compilePageDraft(
			tenantId,
			workspaceId,
			pageId,
			{
				source_sfc: draft.value.source_sfc,
				compiled_bundle: bundle,
				compile_errors: compileError.value || ''
			}
		)
	}
	catch (error) {
		compileError.value = error?.message || 'Compile failed.'
		compileErrorDetail.value = error?.stack || JSON.stringify(error, null, 2)
		await compilePageDraft(
			tenantId,
			workspaceId,
			pageId,
			{ source_sfc: draft.value.source_sfc, compiled_bundle: '', compile_errors: compileError.value }
		)
	}
	finally {
		isCompiling.value = false
	}
}
watch(
	() => previewInputs.value,
	() => {
		persistPreviewInputs()
	},
	{ deep: true }
)
watch(
	activeTab,
	(next) => {
		if (next === 'Source') {
			refreshDraft()
			highlightSource()
		}
		if (next === 'Test' && !isCompiling.value) {
			refreshDraft().then(() => compileDraft())
		}
		if (next === 'Configuration') {
			refreshConfiguration()
		}
	}
)
const refreshConfiguration = async() => {
	if (!tenantId || !workspaceId || !pageId) {
		return
	}
	try {
		page.value = await getPage(tenantId, workspaceId, pageId)
		applyPageConfig(page.value)
	}
	catch (error) {
		notify({ message: error?.message || 'Unable to refresh configuration.', variant: 'error' })
	}
}
const stripCommonIndent = (text) => {
	const lines = text.replace(/\r\n?/g, '\n').split('\n')
	while (lines.length && lines[0].trim() === '') {
		lines.shift()
	}
	while (lines.length && lines[lines.length - 1].trim() === '') {
		lines.pop()
	}
	let minIndent = null
	lines.forEach((line) => {
			if (!line.trim()) {
				return
			}
			const match = line.match(/^\s+/)
			const indent = match ? match[0].length : 0
			minIndent = minIndent === null ? indent : Math.min(minIndent, indent)
		})
	if (!minIndent) {
		return lines.join('\n')
	}
	return lines.map((line) => line.slice(minIndent)).join('\n')
}
const parseSourceBlocks = (source) => {
	const content = source || ''
	const blocks = []
	const pushBlock = (type, lang, body) => {
		if (body && body.trim()) {
			blocks.push({ type, lang, content: stripCommonIndent(body) })
		}
	}
	const templateMatch = content.match(/<template[^>]*>([\s\S]*?)<\/template>/i)
	const scriptMatch = content.match(/<script[^>]*>([\s\S]*?)<\/script>/i)
	const styleMatch = content.match(/<style[^>]*>([\s\S]*?)<\/style>/i)
	pushBlock('Template', 'markup', templateMatch?.[1])
	pushBlock('Script', 'javascript', scriptMatch?.[1])
	pushBlock('Style', 'css', styleMatch?.[1])
	if (!blocks.length && content.trim()) {
		blocks.push({ type: 'Source', lang: 'markup', content: stripCommonIndent(content) })
	}
	return blocks
}
const highlightSource = async() => {
	if (activeTab.value !== 'Source') {
		return
	}
	sourceBlocks.value = parseSourceBlocks(draft.value?.source_sfc || '')
	await nextTick()
	const prism = typeof window !== 'undefined' ? window.Prism : null
	if (!prism) {
		return
	}
	const codeNodes = document.querySelectorAll('.source-block code[class*="language-"]')
	codeNodes.forEach((node) => {
			prism.highlightElement(node)
		})
}
const copySource = async() => {
	const content = draft.value?.source_sfc || ''
	if (!content) {
		notify({ message: 'No source to copy.', variant: 'error' })
		return
	}
	try {
		await navigator.clipboard.writeText(content)
		notify({ message: 'Source copied to clipboard.' })
	}
	catch {
		notify({ message: 'Unable to copy source.', variant: 'error' })
	}
}
const applySourcePaste = async() => {
	const content = sourcePasteValue.value
	if (!content.trim()) {
		notify({ message: 'Paste new source content first.', variant: 'error' })
		return
	}
	try {
		draft.value = await updatePageDraft(tenantId, workspaceId, pageId, { source_sfc: content })
		notify({ message: 'Source updated.' })
		sourcePasteValue.value = ''
		sourcePasteActive.value = false
		highlightSource()
	}
	catch (error) {
		notify({ message: error?.message || 'Unable to update source.', variant: 'error' })
	}
}
watch(
	() => draft.value?.source_sfc,
	() => {
		highlightSource()
	}
)
const approveDraft = async() => {
	approveStatus.value = ''
	try {
		draft.value = await approvePage(tenantId, workspaceId, pageId)
		approveStatus.value = 'Approved.'
		emitPagesUpdated()
		router.push(`/workspace/${workspaceId}`)
	}
	catch (error) {
		approveStatus.value = error?.message || 'Unable to approve.'
		notify({ message: approveStatus.value, variant: 'error' })
	}
}
const deleteDraft = async() => {
	try {
		await deletePageDraft(tenantId, workspaceId, pageId)
		router.push(`/workspace/${workspaceId}`)
	}
	catch (error) {
		approveStatus.value = error?.message || 'Unable to delete draft.'
	}
}
onMounted(loadPage)
</script>
<template>
	<section class="page-editor-view">
		<header class="section-header">
			<div>
				<p class="eyebrow">Pages</p>
				<h1>{{ page?.name || 'Page editor' }}</h1>
			</div>
		</header>
		<div class="editor-shell">
			<div class="editor-header">
				<nav class="editor-tabs">
					<button
						v-for="tab in tabs"
						:key="tab"
						class="tab"
						:class="{ active: activeTab === tab }"
						type="button"
						@click="activeTab = tab">{{ tab }}</button>
				</nav>
				<div class="editor-actions">
					<button
						class="control size-m danger"
						type="button"
						@click="deleteDraftOpen = true">Delete draft</button>
					<button
						class="control size-m approve-button"
						:class="!isCompiled ? 'tooltip tip-left' : ''"
						type="button"
						:disabled="!isCompiled"
						:data-tip="!isCompiled ? 'Release is disabled until the page successfully compiles.' : null"
						@click="approveDraft">Release</button>
				</div>
			</div>
			<div v-if="draftLoading" class="empty">Loading…</div>
			<p v-else-if="draftError" class="form-error">{{ draftError }}</p>
			<div v-else class="editor-body">
				<div v-if="activeTab === 'Design'" class="editor-section">
					<div v-if="designSessionId" class="design-session">
						<SessionView
							:key="designSessionKey"
							class="embedded-session"
							:force-session-id="designSessionId"/>
					</div>
					<p v-else class="subtle">Design session is not available yet.</p>
				</div>
				<div v-else-if="activeTab === 'Source'" class="panel editor-panel editor-section">
					<div class="source-actions">
						<button
							class="control size-s"
							type="button"
							@click="copySource">Copy source</button>
						<button
							class="control size-s secondary"
							type="button"
							@click="sourcePasteActive = !sourcePasteActive">{{ sourcePasteActive ? 'Cancel paste' : 'Paste new source' }}</button>
					</div>
					<div v-if="sourcePasteActive" class="source-paste">
						<textarea
							v-model="sourcePasteValue"
							rows="6"
							placeholder="Paste a full .vue source here"></textarea>
						<div class="source-paste-actions">
							<button
								class="control size-s"
								type="button"
								@click="applySourcePaste">Update source</button>
						</div>
					</div>
					<div v-if="!sourceBlocks.length" class="empty">No source yet.</div>
					<details
						v-for="block in sourceBlocks"
						:key="block.type"
						class="source-block"
						open>
						<summary>{{ block.type }}</summary>
						<pre class="code-block" :class="`language-${block.lang}`">
							<code :class="`language-${block.lang}`">{{ block.content }}</code>
						</pre>
					</details>
				</div>
				<div v-else-if="activeTab === 'Test'" class="panel editor-panel editor-section">
					<div class="test-status">
						<span v-if="isCompiling" class="subtle">Compiling preview…</span>
						<span v-if="compileError" class="form-error">{{ compileError }}</span>
					</div>
					<details v-if="compileErrorDetail" class="compile-details">
						<summary>Show compiler details</summary>
						<pre>{{ compileErrorDetail }}</pre>
					</details>
					<div v-if="routeVars.length" class="preview-grid">
						<div
							v-for="name in routeVars"
							:key="name"
							class="field">
							<span>Path {{ name }}</span>
							<input v-model="previewInputs.routeVars[name]" type="text"/>
						</div>
					</div>
					<div v-if="parsedQueryParams.length" class="preview-grid">
						<div
							v-for="name in parsedQueryParams"
							:key="name"
							class="field">
							<span>Query {{ name }}</span>
							<input v-model="previewInputs.query[name]" type="text"/>
						</div>
					</div>
					<div v-if="parsedInputParams.length" class="preview-grid">
						<div
							v-for="param in parsedInputParams"
							:key="param.name"
							class="field">
							<span>{{ param.label || param.name }}</span>
							<input v-model="previewInputs.inputs[param.name]" type="text"/>
						</div>
					</div>
					<div v-if="compiledComponent" class="preview-surface">
						<component :is="compiledComponent" v-bind="previewProps"/>
					</div>
				</div>
				<div v-else class="panel editor-panel editor-section">
					<div class="stack">
						<div class="field">
							<span>Namespace</span>
							<input
								v-model="pageForm.namespace"
								type="text"
								placeholder="root"/>
						</div>
						<div class="field">
							<span>Page icon</span>
							<div v-if="pageForm.icon_svg" class="page-icon-field">
								<span class="page-icon-preview" v-html="pageForm.icon_svg"></span>
								<button
									class="control ghost icon-button tooltip tip-left page-icon-delete"
									type="button"
									data-tip="Clear icon"
									@click="pageForm.icon_svg = ''">X</button>
							</div>
							<p v-else class="subtle">No icon set.</p>
						</div>
						<div class="field">
							<span>Menu label</span>
							<input
								v-model="pageForm.label"
								type="text"
								placeholder="Optional nicer menu name"/>
							<p class="subtle">Used in the installed page menu. Defaults to the page name when empty.</p>
						</div>
						<div class="field">
							<span>Type</span>
							<select v-model="pageForm.type">
								<option value="PAGE">Page</option>
								<option value="COMPONENT">Component</option>
							</select>
							<p class="subtle">Pages are routable. Components are meant to be imported and should include a usage guide.</p>
						</div>
						<div class="field">
							<span>Route suffix</span>
							<input
								v-model="pageForm.route_suffix"
								type="text"
								placeholder="/{id}"/>
							<p class="subtle">Base path is always /pages/&lt;slug&gt;. Use {var} for path variables.</p>
						</div>
						<div class="field">
							<span>Query parameters</span>
							<textarea
								v-model="pageForm.query_params"
								rows="3"
								placeholder="filter\nstatus"></textarea>
						</div>
						<div class="field">
							<span>Input parameters (JSON)</span>
							<textarea
								v-model="pageForm.input_params"
								rows="5"
								placeholder='[{
  "name": "customerId",
  "label": "Customer ID",
  "type": "string"
}]'></textarea>
						</div>
						<div v-if="isComponentType" class="field">
							<span>Usage guide</span>
							<textarea
								v-model="pageForm.usage_guide"
								rows="8"
								placeholder="Explain the component briefly and include at least one minimal example usage."></textarea>
							<p class="subtle">
								Shown to humans and available to LLMs. Include the minimal rules for using this component and at least one example import/render snippet.
							</p>
						</div>
						<div class="field">
							<span>Import allowlist</span>
							<textarea
								v-model="pageForm.import_allowlist"
								rows="3"
								placeholder="date-fns\nchart.js"></textarea>
						</div>
						<div class="field">
							<span>Dependencies</span>
							<textarea
								v-model="dependencyInput"
								rows="4"
								placeholder="Add dependency ids (one per line)."></textarea>
							<p class="subtle">Dependencies are installed recursively.</p>
						</div>
						<button
							class="control size-m secondary"
							type="button"
							:disabled="savingSettings"
							@click="saveAll">{{ savingSettings ? 'Saving…' : 'Save configuration' }}</button>
						<p v-if="saveError" class="form-error">{{ saveError }}</p>
						<p v-else-if="saveStatus" class="subtle">{{ saveStatus }}</p>
						<p v-else-if="approveStatus" class="subtle">{{ approveStatus }}</p>
					</div>
				</div>
			</div>
		</div>
		<div
			v-if="toast"
			class="toast"
			:class="toastTone">{{ toast }}</div>
	</section>
	<ConfirmModal
		v-model:open="deleteDraftOpen"
		title="Delete draft"
		message="Delete this draft? This cannot be undone."
		confirm-label="Delete"
		:destructive="true"
		@confirm="deleteDraft"
		@cancel="deleteDraftOpen = false"/>
</template>
<style scoped>
.page-editor-view {
	display: flex;
	flex-direction: column;
	gap: 1.5rem;
}

.page-editor-view .section-header {
	margin-bottom: 0;
	padding: 0 var(--space-2xl);
}

.editor-shell {
	display: flex;
	flex-direction: column;
	gap: 1rem;
	flex-grow: 1;
}

.editor-header {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 1rem;
	flex-wrap: wrap;
	padding: 0 var(--space-2xl);
	border-bottom: var(--border-subtle);
}

.editor-actions {
	display: flex;
	flex-wrap: wrap;
	gap: 0.75rem;
}

.editor-actions .control {
	font-size: 0.85rem;
	min-height: auto;
	border: none;
}

.editor-tabs {
	display: flex;
	gap: 0.75rem;
	flex-wrap: wrap;
}

.tab {
	border: 1px solid var(--border-subtle);
	padding: 0.4rem 0.9rem;
	background: transparent;
	color: inherit;
	cursor: pointer;
	font-size: 0.95rem;
	border-bottom: 2px solid transparent;
	border-radius: 0;
}

.tab.active {
	background: var(--panel-strong);
	border-color: transparent;
	border-bottom-color: var(--accent);
}

.editor-panel {
	min-height: 360px;
	height: 100%;
	background: transparent;
	border: none;
	box-shadow: none;
}

.editor-body {
	display: flex;
	flex-direction: column;
	flex-grow: 1;
}

.editor-section {
	height: 100%;
}

.design-session {
	height: 100%;
}

:deep(.sessions-main) {
	height: 100%;
}

.embedded-session {
	height: 100%;
}

.embedded-session :deep(.session-container) {
	padding: 0;
}

.embedded-session :deep(.sessions-main) {
	height: 100%;
}

.embedded-session :deep(.session-grid) {
	grid-template-columns: 1fr;
}

.embedded-session :deep(.session-sidebar) {
	display: none;
}

.embedded-session :deep(.session-toolbar) {
	display: none;
}

.editor-section {
	display: flex;
	flex-direction: column;
	gap: 1rem;
}

.code-block {
	white-space: pre-wrap;
	background: var(--panel-subtle);
	padding: 1rem;
	border-radius: 0.75rem;
	font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', monospace);
	font-size: 0.9rem;
}

.source-block {
	border: 1px solid var(--border-subtle);
	border-radius: 12px;
	padding: 0.6rem 0.9rem;
	background: var(--panel-strong);
	display: flex;
	flex-direction: column;
	gap: 0.75rem;
}

.source-block summary {
	cursor: pointer;
	font-weight: 600;
	color: var(--text-primary);
}

.source-block pre.code-block {
	margin: 0;
}

.source-actions {
	display: flex;
	justify-content: flex-end;
	gap: 0.5rem;
}

.source-paste {
	display: flex;
	flex-direction: column;
	gap: 0.75rem;
	padding: 0.8rem 1rem;
	border: 1px dashed var(--border-subtle);
	border-radius: 12px;
	background: var(--panel-subtle);
}

.source-paste textarea {
	min-height: 140px;
	resize: vertical;
}

.source-paste-actions {
	display: flex;
	justify-content: flex-end;
}

.code-block > code {
	display: block;
}

.page-icon-preview :deep(svg), .page-icon-preview svg {
	width: 28px;
	height: 28px;
	display: block;
}

.page-icon-field {
	display: flex;
	align-items: center;
	gap: 0.6rem;
	position: relative;
	width: fit-content;
}

.field-row {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 0.6rem;
}

.page-icon-actions {
	display: inline-flex;
	align-items: center;
}

.page-icon-delete {
	opacity: 0;
	transition: opacity 0.15s ease;
}

.page-icon-field:hover .page-icon-delete, .page-icon-field:focus-within .page-icon-delete {
	opacity: 1;
}

.actions {
	display: flex;
	flex-wrap: wrap;
	gap: 0.75rem;
}

.test-actions {
	display: flex;
	flex-wrap: wrap;
	gap: 0.75rem;
	align-items: center;
	justify-content: flex-end;
}

.test-status {
	display: flex;
	flex-direction: column;
	gap: 0.35rem;
}

.preview-surface {
	border: 1px solid var(--border-subtle);
	border-radius: 0.75rem;
	padding: 1rem;
	background: var(--panel-subtle);
	flex-grow: 1;
}

.approve-button {
	background: transparent;
	color: var(--accent);
	border: none;
}

.approve-button:disabled {
	opacity: 0.5;
	cursor: not-allowed;
}

.preview-grid {
	display: grid;
	grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
	gap: 0.75rem;
}

.preview-meta {
	padding: 0.5rem 0.75rem;
	border-radius: 0.6rem;
	background: var(--panel-subtle);
}

.compile-details {
	background: var(--panel-subtle);
	border-radius: 0.6rem;
	padding: 0.75rem;
}

.compile-details summary {
	cursor: pointer;
	color: var(--text-muted);
	margin-bottom: 0.5rem;
}

.compile-details pre {
	white-space: pre-wrap;
	font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', monospace);
	font-size: 0.85rem;
	color: var(--text-primary);
}
</style>
