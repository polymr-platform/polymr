<script setup>
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { getMcpServers, getMcpCallLogs, getNotificationLogs, getScriptCallLogs, loadActiveTenant } from '../api';
const route = useRoute()
const workspaceId = route.params.workspaceId
const tenantId = ref(loadActiveTenant())
const logs = ref([])
const notificationLogs = ref([])
const scriptLogs = ref([])
const servers = ref([])
const loading = ref(false)
const error = ref('')
const serverFilter = ref('')
const methodFilter = ref('')
const sessionFilter = ref('')
const beforeCursor = ref('')
const notificationBefore = ref('')
const scriptBefore = ref('')
const expanded = ref({})
const activeTab = ref('mcp')
const serverMap = computed(() => (servers.value || []).reduce(
	(acc, server) => {
		acc[server.id] = server.display_name || server.name || server.command || 'MCP Server'
		return acc
	},
	{}
))
const filteredLogs = computed(() => {
	const methodValue = methodFilter.value.trim().toLowerCase()
	const sessionValue = sessionFilter.value.trim().toLowerCase()
	return logs.value
		.filter((entry) => {
			if (serverFilter.value && entry.mcp_server_id !== serverFilter.value) {
				return false
			}
			if (methodValue && !(entry.method || '').toLowerCase().includes(methodValue)) {
				return false
			}
			if (sessionValue && !(entry.session_id || '').toLowerCase().includes(sessionValue)) {
				return false
			}
			return true
		})
})
const filteredNotificationLogs = computed(() => {
	const sessionValue = sessionFilter.value.trim().toLowerCase()
	return notificationLogs.value
		.filter((entry) => {
			if (sessionValue && !(entry.session_id || '').toLowerCase().includes(sessionValue)) {
				return false
			}
			return true
		})
})
const filteredScriptLogs = computed(() => {
	const sessionValue = sessionFilter.value.trim().toLowerCase()
	return scriptLogs.value
		.filter((entry) => {
			if (sessionValue && !(entry.session_id || '').toLowerCase().includes(sessionValue)) {
				return false
			}
			return true
		})
})
const formatTimestamp = (value) => {
	if (!value) {
		return ''
	}
	const date = new Date(value)
	if (Number.isNaN(date.getTime())) {
		return String(value)
	}
	return date.toLocaleString()
}
const scriptToolCalls = (scriptCallId) => logs.value.filter((entry) => entry.script_call_id === scriptCallId)
const toggleExpanded = (id) => {
	if (!id) {
		return
	}
	expanded.value = { ...expanded.value, [id]: !expanded.value[id] }
}
const isExpanded = (id) => !!expanded.value[id]
const loadServers = async() => {
	if (!tenantId.value || !workspaceId) {
		return
	}
	try {
		servers.value = await getMcpServers(tenantId.value, workspaceId)
	}
	catch {
		servers.value = []
	}
}
const loadLogs = async({ reset = false } = {}) => {
	if (!tenantId.value || !workspaceId) {
		return
	}
	loading.value = true
	error.value = ''
	if (reset) {
		logs.value = []
		beforeCursor.value = ''
	}
	try {
		const query = {
			server_id: serverFilter.value || undefined,
			session_id: sessionFilter.value || undefined,
			before: beforeCursor.value || undefined,
			limit: 200
		}
		const result = await getMcpCallLogs(tenantId.value, workspaceId, query)
		const next = Array.isArray(result) ? result : []
		logs.value = reset ? next : [...logs.value, ...next]
		const last = next[next.length - 1]
		beforeCursor.value = last?.created_at || beforeCursor.value
	}
	catch (err) {
		error.value = err?.message || 'Unable to load logs.'
	}
	finally {
		loading.value = false
	}
}
const loadNotificationLogEntries = async({ reset = false } = {}) => {
	if (!tenantId.value || !workspaceId) {
		return
	}
	loading.value = true
	error.value = ''
	if (reset) {
		notificationLogs.value = []
		notificationBefore.value = ''
	}
	try {
		const query = { before: notificationBefore.value || undefined, limit: 200 }
		const result = await getNotificationLogs(tenantId.value, workspaceId, query)
		const next = Array.isArray(result) ? result : []
		notificationLogs.value = reset ? next : [...notificationLogs.value, ...next]
		const last = next[next.length - 1]
		notificationBefore.value = last?.created_at || notificationBefore.value
	}
	catch (err) {
		error.value = err?.message || 'Unable to load logs.'
	}
	finally {
		loading.value = false
	}
}
const loadScriptLogs = async({ reset = false } = {}) => {
	if (!tenantId.value || !workspaceId) {
		return
	}
	loading.value = true
	error.value = ''
	if (reset) {
		scriptLogs.value = []
		scriptBefore.value = ''
	}
	try {
		const query = { before: scriptBefore.value || undefined, limit: 200 }
		const result = await getScriptCallLogs(tenantId.value, workspaceId, query)
		const next = Array.isArray(result) ? result : []
		scriptLogs.value = reset ? next : [...scriptLogs.value, ...next]
		const last = next[next.length - 1]
		scriptBefore.value = last?.created_at || scriptBefore.value
	}
	catch (err) {
		error.value = err?.message || 'Unable to load script logs.'
	}
	finally {
		loading.value = false
	}
}
watch(
	[serverFilter],
	() => {
		if (activeTab.value === 'mcp') {
			loadLogs({ reset: true })
		}
	}
)
watch(
	activeTab,
	(value) => {
		if (value === 'mcp') {
			loadLogs({ reset: true })
		}
		else if (value === 'notifications') {
			loadNotificationLogEntries({ reset: true })
		}
		else {
			loadScriptLogs({ reset: true })
		}
	}
)
onMounted(async() => {
	await loadServers()
	if (activeTab.value === 'mcp') {
		await loadLogs({ reset: true })
	}
	else if (activeTab.value === 'notifications') {
		await loadNotificationLogEntries({ reset: true })
	}
	else {
		await loadScriptLogs({ reset: true })
	}
})
</script>
<template>
	<section class="workspace-page">
		<header class="page-header">
			<div>
				<h1>Logs</h1>
				<p class="subtle">Review MCP call logs and notification delivery history.</p>
			</div>
			<button
				class="control size-s ghost"
				type="button"
				:disabled="loading"
				@click="activeTab === 'mcp'
          ? loadLogs({ reset: true })
          : activeTab === 'scripts'
            ? loadScriptLogs({ reset: true })
            : loadNotificationLogEntries({ reset: true })">Refresh</button>
		</header>
		<div class="panel tab-bar">
			<button
				class="control size-s"
				:class="{ active: activeTab === 'mcp' }"
				type="button"
				@click="activeTab = 'mcp'">MCP Logs</button>
			<button
				class="control size-s"
				:class="{ active: activeTab === 'scripts' }"
				type="button"
				@click="activeTab = 'scripts'">Script Logs</button>
			<button
				class="control size-s"
				:class="{ active: activeTab === 'notifications' }"
				type="button"
				@click="activeTab = 'notifications'">Notification Logs</button>
		</div>
		<div v-if="activeTab === 'mcp'" class="panel filters-panel">
			<div class="filter-row">
				<label class="field">
					<span>Server</span>
					<select v-model="serverFilter" class="control">
						<option value="">All servers</option>
						<option
							v-for="server in servers"
							:key="server.id"
							:value="server.id">{{ serverMap[server.id] || server.id }}</option>
					</select>
				</label>
				<label class="field">
					<span>Method</span>
					<input
						v-model="methodFilter"
						class="control"
						placeholder="tools/call"/>
				</label>
				<label class="field">
					<span>Session</span>
					<input
						v-model="sessionFilter"
						class="control"
						placeholder="Session ID"/>
				</label>
			</div>
		</div>
		<div v-else-if="activeTab === 'scripts'" class="panel filters-panel">
			<div class="filter-row">
				<label class="field">
					<span>Session</span>
					<input
						v-model="sessionFilter"
						class="control"
						placeholder="Session ID"/>
				</label>
			</div>
		</div>
		<div v-else-if="activeTab === 'notifications'" class="panel filters-panel">
			<div class="filter-row">
				<label class="field">
					<span>Session</span>
					<input
						v-model="sessionFilter"
						class="control"
						placeholder="Session ID"/>
				</label>
			</div>
		</div>
		<div v-if="activeTab === 'scripts'" class="panel logs-panel">
			<div v-if="error" class="form-error">{{ error }}</div>
			<div v-if="filteredScriptLogs.length === 0 && !loading" class="empty">No logs yet.</div>
			<div
				v-for="entry in filteredScriptLogs"
				:key="entry.id"
				class="log-entry">
				<div class="log-entry-header">
					<div>
						<div class="log-entry-title">{{ entry.script_path || entry.script_name || entry.script_slug || entry.script_id || 'Script' }}</div>
						<div class="log-entry-meta">
							<span v-if="entry.script_name && entry.script_path && entry.script_name !== entry.script_path">Name {{ entry.script_name }}</span>
							<span v-else-if="entry.script_slug && entry.script_slug !== entry.script_path">Slug {{ entry.script_slug }}</span>
							<span v-if="entry.session_id">Session {{ entry.session_id }}</span>
							<span v-if="entry.user_id">User {{ entry.user_id }}</span>
							<span>{{ formatTimestamp(entry.created_at) }}</span>
							<span v-if="entry.status">Status {{ entry.status }}</span>
						</div>
					</div>
					<button
						class="control size-xs ghost"
						type="button"
						@click="toggleExpanded(entry.id)">{{ isExpanded(entry.id) ? 'Hide' : 'Show' }}</button>
				</div>
				<div v-if="isExpanded(entry.id)" class="log-entry-body">
					<div class="log-block">
						<div class="log-block-title">Input</div>
						<pre class="code-block">{{ entry.input || '—' }}</pre>
					</div>
					<div class="log-block">
						<div class="log-block-title">Output</div>
						<pre class="code-block">{{ entry.output || '—' }}</pre>
					</div>
					<div class="log-block" v-if="entry.script_id">
						<div class="log-block-title">Tool calls</div>
						<div v-if="scriptToolCalls(entry.id).length === 0" class="empty">No tool calls.</div>
						<div
							v-for="call in scriptToolCalls(entry.id)"
							:key="call.id"
							class="tool-call">
							<div class="log-entry-meta">
								<span>{{ serverMap[call.mcp_server_id] || call.mcp_server_id }}</span>
								<span>{{ call.method || 'tools/call' }}</span>
								<span v-if="call.request_id">#{{ call.request_id }}</span>
								<span>{{ formatTimestamp(call.created_at) }}</span>
								<span v-if="call.status">Status {{ call.status }}</span>
							</div>
							<details>
								<summary>Show payload</summary>
								<div class="log-block">
									<div class="log-block-title">Input</div>
									<pre class="code-block">{{ call.input || '—' }}</pre>
								</div>
								<div class="log-block">
									<div class="log-block-title">Output</div>
									<pre class="code-block">{{ call.output || '—' }}</pre>
								</div>
							</details>
						</div>
					</div>
				</div>
			</div>
			<div class="log-footer">
				<button
					class="control size-s ghost"
					type="button"
					:disabled="loading || !scriptBefore"
					@click="loadScriptLogs()">Load more</button>
			</div>
		</div>
		<div v-if="activeTab === 'mcp'" class="panel logs-panel">
			<div v-if="error" class="form-error">{{ error }}</div>
			<div v-if="filteredLogs.length === 0 && !loading" class="empty">No logs yet.</div>
			<div
				v-for="entry in filteredLogs"
				:key="entry.id"
				class="log-entry">
				<div class="log-entry-header">
					<div>
						<div class="log-entry-title">{{ entry.method || 'JSON-RPC' }}</div>
						<div class="log-entry-meta">
							<span>{{ serverMap[entry.mcp_server_id] || entry.mcp_server_id }}</span>
							<span v-if="entry.request_id">#{{ entry.request_id }}</span>
							<span v-if="entry.session_id">Session {{ entry.session_id }}</span>
							<span v-if="entry.user_id">User {{ entry.user_id }}</span>
							<span>{{ formatTimestamp(entry.created_at) }}</span>
						</div>
					</div>
					<button
						class="control size-xs ghost"
						type="button"
						@click="toggleExpanded(entry.id)">{{ isExpanded(entry.id) ? 'Hide' : 'Show' }}</button>
				</div>
				<div v-if="isExpanded(entry.id)" class="log-entry-body">
					<div class="log-block">
						<div class="log-block-title">Input</div>
						<pre class="code-block">{{ entry.input || '—' }}</pre>
					</div>
					<div class="log-block">
						<div class="log-block-title">Output</div>
						<pre class="code-block">{{ entry.output || '—' }}</pre>
					</div>
				</div>
			</div>
			<div class="log-footer">
				<button
					class="control size-s ghost"
					type="button"
					:disabled="loading || !beforeCursor"
					@click="loadLogs()">Load more</button>
			</div>
		</div>
		<div v-else-if="activeTab === 'notifications'" class="panel logs-panel">
			<div v-if="error" class="form-error">{{ error }}</div>
			<div v-if="filteredNotificationLogs.length === 0 && !loading" class="empty">No logs yet.</div>
			<div
				v-for="entry in filteredNotificationLogs"
				:key="entry.id"
				class="log-entry">
				<div class="log-entry-header">
					<div>
						<div class="log-entry-title">Push notification</div>
						<div class="log-entry-meta">
							<span>{{ entry.target || 'unknown' }}</span>
							<span v-if="entry.session_id">Session {{ entry.session_id }}</span>
							<span v-if="entry.initiator_user_id">User {{ entry.initiator_user_id }}</span>
							<span>{{ formatTimestamp(entry.created_at) }}</span>
						</div>
					</div>
					<button
						class="control size-xs ghost"
						type="button"
						@click="toggleExpanded(entry.id)">{{ isExpanded(entry.id) ? 'Hide' : 'Show' }}</button>
				</div>
				<div v-if="isExpanded(entry.id)" class="log-entry-body">
					<div class="log-block">
						<div class="log-block-title">Message</div>
						<div class="log-entry-meta">
							<span>{{ entry.title }}</span>
							<span>·</span>
							<span>{{ entry.body }}</span>
						</div>
						<div class="log-entry-meta">
							<span>Eligible {{ entry.eligible_count }}</span>
							<span>Sent {{ entry.sent_count }}</span>
							<span v-if="entry.destination">Destination {{ entry.destination }}</span>
						</div>
					</div>
					<div class="log-block">
						<div class="log-block-title">Recipients</div>
						<div v-if="!entry.recipients || entry.recipients.length === 0" class="subtle">No recipients.</div>
						<div
							v-for="recipient in entry.recipients"
							:key="recipient.user_id"
							class="log-entry-meta">
							<span>User {{ recipient.user_id }}</span>
							<span>{{ recipient.status }}</span>
							<span v-if="recipient.detail">{{ recipient.detail }}</span>
						</div>
					</div>
				</div>
			</div>
			<div class="log-footer">
				<button
					class="control size-s ghost"
					type="button"
					:disabled="loading || !notificationBefore"
					@click="loadNotificationLogEntries()">Load more</button>
			</div>
		</div>
	</section>
</template>
<style scoped>
.workspace-page {
	padding: 28px 32px 40px;
	min-height: 100%;
}

.page-header {
	display: flex;
	justify-content: space-between;
	align-items: center;
	gap: 16px;
	margin-bottom: 20px;
}

.page-header h1 {
	margin: 0 0 6px;
}

.tab-bar {
	display: flex;
	gap: 8px;
	margin-bottom: 16px;
}

.tab-bar .control.active {
	background: var(--surface-strong);
	border-color: var(--border-strong);
}

.panel {
	background: var(--panel-bg, rgba(10, 14, 25, 0.92));
	border-radius: 16px;
	padding: 18px;
	border: 1px solid var(--panel-border, rgba(148, 163, 184, 0.18));
	box-shadow: 0 18px 40px rgba(2, 6, 23, 0.4);
}

.filters-panel {
	margin-bottom: 18px;
}

.filter-row {
	display: grid;
	gap: 14px;
	grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
}

.field {
	display: flex;
	flex-direction: column;
	gap: 6px;
	font-size: 0.9rem;
}

.logs-panel {
	display: flex;
	flex-direction: column;
	gap: 12px;
}

.log-entry {
	border: 1px solid rgba(148, 163, 184, 0.2);
	border-radius: 14px;
	padding: 14px;
	background: rgba(15, 23, 42, 0.72);
}

.log-entry-header {
	display: flex;
	justify-content: space-between;
	gap: 12px;
	align-items: flex-start;
}

.log-entry-title {
	font-weight: 600;
	margin-bottom: 6px;
}

.log-entry-meta {
	display: flex;
	flex-wrap: wrap;
	gap: 10px;
	color: rgba(226, 232, 240, 0.7);
	font-size: 0.85rem;
}

.log-entry-body {
	margin-top: 12px;
	display: grid;
	gap: 12px;
}

.log-block-title {
	font-size: 0.85rem;
	margin-bottom: 6px;
	color: rgba(226, 232, 240, 0.7);
	text-transform: uppercase;
	letter-spacing: 0.06em;
}

.code-block {
	background: rgba(2, 6, 23, 0.92);
	color: #e2e8f0;
	border-radius: 12px;
	padding: 12px;
	font-size: 0.8rem;
	border: 1px solid rgba(148, 163, 184, 0.2);
	overflow: auto;
}

.log-footer {
	display: flex;
	justify-content: center;
	padding-top: 4px;
}

.empty {
	text-align: center;
	color: rgba(226, 232, 240, 0.6);
}

@media (max-width: 720px) {
	.workspace-page {
		padding: 20px;
	}
	.page-header {
		flex-direction: column;
		align-items: flex-start;
	}
}
</style>
