<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router';
import WorkspaceMenu from '../components/WorkspaceMenu.vue';
import {
	createSession,
	getAssistants,
	getChannels,
	getWorkspaceAssistants,
	getInstalledPageDetails,
	getWorkspaceMcpApplications,
	getWorkspaces,
	loadActiveTenant,
	loadSession
} from '../api';
import { useWorkspaceSessions } from '../state/workspaceSessions';
import { useWorkspaceSocket } from '../state/workspaceSocket';
import { handleSessionStatusEvent, useSessionQueueing } from '../state/sessionQueueing';
import { loadQueueingJitter, loadSmartQueueing } from '../state/queueingSettings';
const route = useRoute()
const router = useRouter()
const workspaceId = route.params.workspaceId
const tenantId = ref(loadActiveTenant())
const currentUserId = ref(loadSession()?.userId || '')
const sessions = useWorkspaceSessions(workspaceId)
const visibleSessions = computed(() => (sessions.value || []).filter((session) => {
		const visibility = (session?.visibility || '').toUpperCase()
		if (visibility === 'HIDDEN') {
			return false
		}
		if (visibility === 'FLEXIBLE') {
			return !session?.locked
		}
		return true
	}))
const queueState = useSessionQueueing(workspaceId)
const queuedMessagesBySession = queueState.queues
const dequeuePendingBySession = queueState.pending
const dequeueTimersBySession = queueState.timers
const smartQueueing = ref(loadSmartQueueing())
const queueingJitter = ref(loadQueueingJitter())
const menuCollapsedKey = computed(() => `polymr.workspace.menu.${workspaceId}`)
const menuCollapsed = ref(false)
const workspaceSocketState = useWorkspaceSocket(tenantId.value, workspaceId)
let unregisterSocketHandler = null
const assistants = ref([])
const applications = ref([])
const pages = ref([])
const syncingState = ref(false)
const initialSyncDone = ref(false)
const sessionListPending = ref(true)
const hasConnectedOnce = ref(false)
const workspaceName = ref('')
const defaultTitle = ref(typeof document !== 'undefined' ? document.title : '')
let pagesListener = null
const activeStatuses = ['ACTIVE', 'PAUSED']
const lastAssistantKey = computed(() => `polymr.lastAssistant.${workspaceId}`)
const workspaceAssistants = computed(() => assistants.value.filter((assistant) => assistant.workspace_id === workspaceId))
const tenantAssistants = computed(() => assistants.value.filter((assistant) => !assistant.workspace_id))
const connectionStatus = computed(() => {
	if (!workspaceSocketState.initialized.value) {
		return { code: 'connecting', label: 'Connecting' }
	}
	if (!workspaceSocketState.connected.value) {
		return { code: 'disconnected', label: 'Offline' }
	}
	return { code: 'connected', label: 'Connected' }
})
const connectionMessage = computed(() => {
	if (!workspaceSocketState.initialized.value) {
		return { title: 'Connecting…', detail: 'Trying to reach the workspace server.' }
	}
	if (!workspaceSocketState.connected.value) {
		return { title: 'Connection failed', detail: 'Websocket is offline. Check your network and refresh.' }
	}
	return null
})
const loadMenuState = () => {
	const stored = localStorage.getItem(menuCollapsedKey.value)
	menuCollapsed.value = stored === null ? true : stored === 'true'
}
const toggleMenu = () => {
	menuCollapsed.value = !menuCollapsed.value
	localStorage.setItem(menuCollapsedKey.value, menuCollapsed.value ? 'true' : 'false')
}
const mergeLiveState = (session, existing) => {
	const merged = { ...session }
	if (!existing) {
		return merged
	}
	if (session.participants === undefined) {
		merged.participants = existing.participants
	}
	if (session.model_telemetry === undefined) {
		merged.model_telemetry = existing.model_telemetry
	}
	return merged
}
const loadAssistants = async() => {
	if (!tenantId.value) {
		return
	}
	const [tenantAssistantsList, workspaceAssistantsList] = await Promise.all([
		getAssistants(tenantId.value),
		getWorkspaceAssistants(tenantId.value, workspaceId),
	])
	assistants.value = [...tenantAssistantsList, ...workspaceAssistantsList]
}
const loadApplications = async() => {
	if (!tenantId.value) {
		return
	}
	try {
		applications.value = await getWorkspaceMcpApplications(tenantId.value, workspaceId)
	}
	catch {
		applications.value = []
	}
}
const loadPages = async() => {
	if (!tenantId.value) {
		return
	}
	try {
		const pageList = await getInstalledPageDetails(tenantId.value, workspaceId)
		pages.value = Array.isArray(pageList) ? pageList : []
	}
	catch {
		pages.value = []
	}
}
const loadWorkspaceName = async() => {
	if (!tenantId.value || !workspaceId) {
		return
	}
	try {
		const list = await getWorkspaces(tenantId.value)
		const match = Array.isArray(list) ? list.find((item) => item.id === workspaceId) : null
		workspaceName.value = match?.name || ''
	}
	catch {
		workspaceName.value = ''
	}
}
const handleWorkspaceMessage = (event) => {
	if (!event || typeof event !== 'object') {
		return
	}
	if (event.type === 'session.list' && Array.isArray(event.payload)) {
		const existing = new Map(sessions.value.map((item) => [item.id, item]))
		sessions.value = event.payload.map((session) => mergeLiveState(session, existing.get(session.id)))
		sessionListPending.value = false
		return
	}
	if (event.type === 'session.created' && event.payload) {
		const existing = sessions.value.find((item) => item.id === event.payload.id)
		if (!existing) {
			sessions.value = [event.payload, ...sessions.value]
		}
		return
	}
	if (event.type === 'session.telemetry' && event.session_id && event.payload) {
		const updatedId = event.session_id
		sessions.value = sessions.value.map((item) => item.id === updatedId ? { ...item, model_telemetry: event.payload } : item)
		return
	}
	if (event.type === 'session.updated' && event.payload?.id) {
		const updatedId = event.payload.id
		const next = sessions.value.map((item) => item.id === updatedId ? mergeLiveState({ ...item, ...event.payload }, item) : item)
		sessions.value = next.filter((item) => activeStatuses.includes(item.status))
		return
	}
	if (event.type === 'session.status' && event.payload?.id) {
		const updatedId = event.payload.id
		const status = event.payload.status
		if (!status) {
			return
		}
		const locked = !!event.payload.locked
		const next = sessions.value
			.map((item) => item.id === updatedId ? {
			...item,
			status,
			needs_input: status === 'PAUSED',
			locked
		} : item)
		sessions.value = next.filter((item) => activeStatuses.includes(item.status))
		handleSessionStatusEvent({
			event,
			queuesRef: queuedMessagesBySession,
			pendingRef: dequeuePendingBySession,
			timersRef: dequeueTimersBySession,
			smartQueueing: smartQueueing.value,
			send: workspaceSocketState.send,
			jitter: queueingJitter.value
		})
		return
	}
	if (event.type === 'session.deleted' && event.payload?.id) {
		sessions.value = sessions.value.filter((item) => item.id !== event.payload.id)
		return
	}
	if (event.type === 'mcp.update') {
		loadApplications()
		return
	}
	if (event.type === 'session.participants' && event.session_id) {
		const participants = Array.isArray(event.payload) ? event.payload : []
		sessions.value = sessions.value.map((item) => item.id === event.session_id ? { ...item, participants } : item)
	}
}
const openSettings = () => {
	router.push({ name: 'workspace-detail', params: { workspaceId } })
}
const openHistory = () => {
	router.push({ name: 'workspace-history', params: { workspaceId } })
}
const openApp = (appId) => {
	if (!appId) {
		return
	}
	router.push({ name: 'workspace-app', params: { workspaceId, appId } })
}
const channels = ref([])
const loadChannels = async() => {
	if (!tenantId.value) {
		channels.value = []
		return
	}
	try {
		channels.value = await getChannels(tenantId.value, workspaceId)
	}
	catch {
		channels.value = []
	}
}
const startSession = async(channel = null) => {
	if (!tenantId.value) {
		return
	}
	const stored = localStorage.getItem(lastAssistantKey.value) || ''
	const fallback = channel?.assistant_id || workspaceAssistants.value[0]?.id || tenantAssistants.value[0]?.id || ''
	const payload = {}
	if (channel?.id) {
		payload.channel_id = channel.id
	}
	if (stored || fallback) {
		payload.assistant_id = stored || fallback
	}
	const created = await createSession(tenantId.value, workspaceId, payload)
	if (created?.id) {
		router.push({ name: 'workspace-session', params: { workspaceId, sessionId: created.id } })
	}
}
const openSession = (id) => {
	if (!id) {
		return
	}
	router.push({ name: 'workspace-session', params: { workspaceId, sessionId: id } })
}
onMounted(async() => {
	loadMenuState()
	sessions.value = []
	sessionListPending.value = true
	unregisterSocketHandler = workspaceSocketState.registerHandler(handleWorkspaceMessage)
	await loadAssistants()
	await loadApplications()
	await loadPages()
	await loadChannels()
	await loadWorkspaceName()
	initialSyncDone.value = true
	if (typeof window !== 'undefined') {
		pagesListener = () => loadPages()
		window.addEventListener('polymr.pages.updated', pagesListener)
	}
})
watch(
	workspaceName,
	(next) => {
		if (typeof document === 'undefined') {
			return
		}
		if (next && next.trim()) {
			document.title = next.trim()
		}
	}
)
watch(
	() => workspaceSocketState.connected.value,
	async(next, prev) => {
		if (prev && !next) {
			sessions.value = []
			sessionListPending.value = true
			syncingState.value = false
			return
		}
		if (!next || prev === next || !initialSyncDone.value) {
			return
		}
		if (hasConnectedOnce.value && route.name === 'workspace-session') {
			syncingState.value = true
		}
		hasConnectedOnce.value = true
		try {
			await loadApplications()
			await loadPages()
		}
		finally {
			syncingState.value = false
		}
	}
)
onBeforeUnmount(() => {
	if (unregisterSocketHandler) {
		unregisterSocketHandler()
		unregisterSocketHandler = null
	}
	if (pagesListener && typeof window !== 'undefined') {
		window.removeEventListener('polymr.pages.updated', pagesListener)
	}
})
onBeforeRouteLeave((to) => {
	const workspacePath = `/workspace/${workspaceId}`
	if (!to?.path?.startsWith(workspacePath)) {
		workspaceSocketState.close()
		if (typeof document !== 'undefined') {
			document.title = defaultTitle.value || document.title
		}
	}
})
onBeforeUnmount(() => {
	if (typeof document !== 'undefined') {
		document.title = defaultTitle.value || document.title
	}
})
</script>
<template>
	<section class="workspace-shell">
		<WorkspaceMenu
			:workspace-id="workspaceId"
			:workspace-name="workspaceName"
			:sessions="visibleSessions"
			:session-list-pending="sessionListPending"
			:selected-session-id="route.params.sessionId || ''"
			:selected-app-id="route.params.appId || ''"
			:selected-page-slug="route.params.slug || ''"
			:applications="applications"
			:pages="pages"
			:collapsed="menuCollapsed"
			:connection-status="connectionStatus"
			:channels="channels"
			@create="startSession"
			@select="openSession"
			@open-app="openApp"
			@toggle-history="openHistory"
			@toggle-collapse="toggleMenu"
			@delete="openSettings"/>
		<RouterView/>
		<div v-if="connectionMessage" class="connection-overlay">
			<div class="connection-card">
				<h3>{{ connectionMessage.title }}</h3>
				<p class="subtle">{{ connectionMessage.detail }}</p>
			</div>
		</div>
		<div v-if="syncingState" class="modal-backdrop">
			<div class="modal-card">
				<h3>Synchronizing state</h3>
				<p class="subtle">Reconnecting to the workspace…</p>
			</div>
		</div>
	</section>
</template>
