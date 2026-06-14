<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import ConfirmModal from '../components/ConfirmModal.vue';
import JsonSchemaEditor from '../components/JsonSchemaEditor.vue';
import SessionView from './SessionView.vue';
import { jsonSchemaMeta } from '../data/jsonSchemaMeta';
import { approveScript, deleteScriptDraft, getScript, getScriptDraft, updateScriptDraft, runScript } from '../api';
import { loadActiveTenant } from '../api';
const route = useRoute()
const router = useRouter()
const workspaceId = route.params.workspaceId
const scriptId = route.params.scriptId
const tenantId = loadActiveTenant()
const lastAssistantKey = computed(() => `polymr.lastAssistant.${workspaceId}`)
const tabs = ['Design', 'Source', 'Test', 'Configuration']
const isWorkflowContext = computed(() => route.query?.workflow === '1')
const activeTab = ref('Design')
const script = ref(null)
const draft = ref(null)
const draftLoading = ref(true)
const draftError = ref('')
const approveStatus = ref('')
const deleteDraftOpen = ref(false)
const designSessionKey = ref(0)
const toast = ref('')
const toastTone = ref('')
let toastTimer = null
const savingSettings = ref(false)
const saveError = ref('')
const saveStatus = ref('')
const scriptForm = ref({ input_schema: { type: 'object' }, output_schema: { type: 'object' }, type: 'STANDALONE' })
const workflowStateSchema = ref({ type: 'object' })
const schemaEditorSchema = jsonSchemaMeta
const sourcePasteActive = ref(false)
const sourcePasteValue = ref('')
const testInput = ref({})
const testState = ref({})
const testOutput = ref(null)
const testError = ref('')
const testRunning = ref(false)
const designSessionId = computed(() => draft.value?.design_session_id || '')
const isStandalone = computed(() => scriptForm.value.type === 'STANDALONE')
const hideInputOutput = computed(() => isStandalone.value && script.value?.tool_hook_enabled)
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
const applyScriptConfig = (scriptData) => {
	scriptForm.value = {
		input_schema: draft.value?.input_schema || scriptData?.input_schema || { type: 'object' },
		output_schema: draft.value?.output_schema || scriptData?.output_schema || { type: 'object' },
		type: scriptData?.type || 'STANDALONE'
	}
	workflowStateSchema.value = scriptData?.workflow_state_schema || { type: 'object' }
}
const loadScript = async() => {
	draftLoading.value = true
	draftError.value = ''
	try {
		script.value = await getScript(tenantId, workspaceId, scriptId)
		const assistantId = localStorage.getItem(lastAssistantKey.value) || ''
		draft.value = await getScriptDraft(tenantId, workspaceId, scriptId, { assistant_id: assistantId })
		applyScriptConfig(script.value)
		designSessionKey.value += 1
	}
	catch (error) {
		draftError.value = error?.message || 'Unable to load script.'
	}
	finally {
		draftLoading.value = false
	}
}
const refreshConfiguration = async() => {
	if (!tenantId || !workspaceId || !scriptId) {
		return
	}
	try {
		script.value = await getScript(tenantId, workspaceId, scriptId)
		applyScriptConfig(script.value)
	}
	catch (error) {
		notify({ message: error?.message || 'Unable to refresh configuration.', variant: 'error' })
	}
}
const refreshDraft = async() => {
	try {
		draft.value = await getScriptDraft(tenantId, workspaceId, scriptId)
	}
	catch (error) {
		notify({ message: error?.message || 'Unable to refresh draft.', variant: 'error' })
	}
}
const copySource = async() => {
	const content = draft.value?.source_groovy || ''
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
		draft.value = await updateScriptDraft(tenantId, workspaceId, scriptId, { source_groovy: content })
		notify({ message: 'Source updated.' })
		sourcePasteValue.value = ''
		sourcePasteActive.value = false
		highlightSource()
	}
	catch (error) {
		notify({ message: error?.message || 'Unable to update source.', variant: 'error' })
	}
}
const highlightSource = async() => {
	if (activeTab.value !== 'Source') {
		return
	}
	await nextTick()
	const prism = typeof window !== 'undefined' ? window.Prism : null
	if (!prism) {
		return
	}
	const codeNodes = document.querySelectorAll('.script-editor-view code[class*="language-"]')
	codeNodes.forEach((node) => {
			prism.highlightElement(node)
		})
}
const saveAll = async() => {
	if (!tenantId || !workspaceId || !scriptId) {
		return
	}
	saveStatus.value = ''
	saveError.value = ''
	savingSettings.value = true
	try {
		draft.value = await updateScriptDraft(
			tenantId,
			workspaceId,
			scriptId,
			{
				input_schema: scriptForm.value.input_schema || { type: 'object' },
				output_schema: scriptForm.value.output_schema || { type: 'object' }
			}
		)
		applyScriptConfig(script.value)
		saveStatus.value = 'Draft configuration saved.'
	}
	catch (error) {
		saveError.value = error?.message || 'Unable to save configuration.'
	}
	finally {
		savingSettings.value = false
	}
}
const approveDraft = async() => {
	approveStatus.value = ''
	try {
		draft.value = await approveScript(tenantId, workspaceId, scriptId)
		approveStatus.value = 'Released.'
		router.push(`/workspace/${workspaceId}`)
	}
	catch (error) {
		approveStatus.value = error?.message || 'Unable to release.'
		notify({ message: approveStatus.value, variant: 'error' })
	}
}
const deleteDraft = async() => {
	try {
		await deleteScriptDraft(tenantId, workspaceId, scriptId)
		if (isWorkflowContext.value) {
			await refreshDraft()
			notify({ message: 'Draft reset.' })
		}
		else {
			router.push(`/workspace/${workspaceId}`)
		}
	}
	catch (error) {
		approveStatus.value = error?.message || 'Unable to delete draft.'
	}
}
const runTest = async() => {
	testError.value = ''
	testOutput.value = null
	testRunning.value = true
	try {
		const payload = scriptForm.value.type === 'WORKFLOW' ? { state: testState.value } : { input: testInput.value }
		const response = await runScript(tenantId, workspaceId, scriptId, payload)
		testOutput.value = response?.output ?? null
	}
	catch (error) {
		if (error?.details || error?.stack) {
			testError.value = [
				error?.message || 'Script failed.',
				error?.details ? `Details: ${error.details}` : null,
				error?.stack ? `Stack: ${error.stack}` : null,
			].filter(Boolean)
				.join('\n')
		}
		else {
			testError.value = error?.message || 'Unable to run script.'
		}
	}
	finally {
		testRunning.value = false
	}
}
watch(
	activeTab,
	(next) => {
		if (next === 'Source') {
			refreshDraft()
			highlightSource()
		}
		if (next === 'Configuration') {
			refreshConfiguration()
		}
	}
)
watch(
	() => [draft.value?.source_groovy, draft.value?.input_schema, draft.value?.output_schema],
	() => {
		applyScriptConfig(script.value)
		highlightSource()
	},
	{ deep: true }
)
onMounted(() => {
	loadScript()
})
</script>
<template>
	<section class="script-editor-view">
		<header class="section-header">
			<div>
				<p class="eyebrow">Scripts</p>
				<h1>{{ script?.name || 'Script editor' }}</h1>
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
						@click="deleteDraftOpen = true">{{ isWorkflowContext ? 'Reset draft' : 'Delete draft' }}</button>
					<button
						v-if="!isWorkflowContext"
						class="control size-m approve-button"
						type="button"
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
							rows="10"
							placeholder="Paste Groovy script source here"></textarea>
						<div class="source-paste-actions">
							<button
								class="control size-s"
								type="button"
								@click="applySourcePaste">Update source</button>
						</div>
					</div>
					<div v-if="!draft?.source_groovy" class="empty">No source yet.</div>
					<pre v-else class="code-block language-groovy">
						<code class="language-groovy">{{ draft?.source_groovy }}</code>
					</pre>
				</div>
				<div v-else-if="activeTab === 'Test'" class="panel editor-panel editor-section">
					<div class="test-actions">
						<button
							class="control size-s"
							type="button"
							:disabled="testRunning"
							@click="runTest">{{ testRunning ? 'Running…' : 'Run' }}</button>
					</div>
					<div class="stack">
						<div v-if="scriptForm.type === 'WORKFLOW'" class="field">
							<span>State</span>
							<JsonSchemaEditor
								class="compact-editor"
								v-model="testState"
								:schema="workflowStateSchema || { type: 'object' }"
								:default-collapsed="false"/>
						</div>
						<div v-else-if="!hideInputOutput" class="field">
							<span>Input</span>
							<JsonSchemaEditor
								class="compact-editor"
								v-model="testInput"
								:schema="scriptForm.input_schema || { type: 'object' }"
								:default-collapsed="false"/>
						</div>
						<p v-else class="subtle">Tool hook scripts use MCP tools and do not accept input.</p>
						<div class="field">
							<span>{{ scriptForm.type === 'WORKFLOW' ? 'Updated state' : 'Output' }}</span>
							<pre class="code-block">{{ testOutput ? JSON.stringify(testOutput, null, 2) : '' }}</pre>
						</div>
						<pre v-if="testError" class="form-error error-block">{{ testError }}</pre>
					</div>
				</div>
				<div v-else class="panel editor-panel editor-section">
					<div class="stack">
						<div class="field">
							<span>Type</span>
							<p class="subtle">
								{{ script?.type === 'WORKFLOW' ? 'Workflow-only (managed by workflow release).' : (script?.type || 'STANDALONE') }}
							</p>
						</div>
						<div v-if="scriptForm.type !== 'WORKFLOW' && !hideInputOutput" class="field">
							<div class="section-head">
								<span>Input schema</span>
							</div>
							<JsonSchemaEditor
								class="compact-editor"
								v-model="scriptForm.input_schema"
								:schema="schemaEditorSchema"/>
						</div>
						<div v-if="scriptForm.type !== 'WORKFLOW' && !hideInputOutput" class="field">
							<div class="section-head">
								<span>Output schema</span>
							</div>
							<JsonSchemaEditor
								class="compact-editor"
								v-model="scriptForm.output_schema"
								:schema="schemaEditorSchema"/>
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
	</section>
	<ConfirmModal
		v-model:open="deleteDraftOpen"
		:title="isWorkflowContext ? 'Reset draft' : 'Delete draft'"
		:message=
      "isWorkflowContext
        ? 'Reset the draft? This will discard current edits and start a fresh draft.'
        : 'Delete the current draft? This cannot be undone.'"
		:confirm-label="isWorkflowContext ? 'Reset' : 'Delete'"
		destructive
		@confirm="deleteDraft"/>
	<div
		v-if="toast"
		class="toast"
		:class="toastTone">{{ toast }}</div>
</template>
<style scoped>
.script-editor-view {
	display: flex;
	flex-direction: column;
	gap: var(--space-l);
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

.editor-panel {
	min-height: 360px;
	height: 100%;
	background: transparent;
	border: none;
	box-shadow: none;
}

.source-actions {
	display: flex;
	gap: var(--space-s);
	margin-bottom: var(--space-m);
}

.source-paste textarea {
	width: 100%;
	margin-bottom: var(--space-s);
}

.code-block {
	white-space: pre-wrap;
	background: var(--panel-subtle);
	padding: 1rem;
	border-radius: 0.75rem;
	font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', monospace);
	font-size: 0.9rem;
}

.test-actions {
	display: flex;
	justify-content: flex-end;
	margin-bottom: var(--space-m);
}

.error-block {
	white-space: pre-wrap;
}

.code-block > code {
	display: block;
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

.stack {
	display: flex;
	flex-direction: column;
	gap: var(--space-m);
}

.toast {
	position: fixed;
	bottom: var(--space-l);
	right: var(--space-l);
	background: var(--bg-panel-strong);
	color: var(--text-primary);
	padding: 10px 14px;
	border-radius: 12px;
	box-shadow: var(--shadow-panel);
}

.toast.danger {
	border: 1px solid var(--color-danger);
}

@media (max-width: 900px) {
	.editor-header {
		flex-direction: column;
		align-items: flex-start;
	}
}
</style>
