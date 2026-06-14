<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch, nextTick } from 'vue';
import { useVAD } from '../services/useVAD';
import { usePiperTts } from '../services/piperTts';
import { useRoute, useRouter } from 'vue-router';
import ConfirmModal from '../components/ConfirmModal.vue';
import MarkdownMessage from '../components/MarkdownMessage.vue';
import EventAttachments from '../components/EventAttachments.vue';
import ScopeViewer from '../components/ScopeViewer.vue';
import JsonTree from '../components/JsonTree.vue';
import SessionEventList from '../components/SessionEventList.vue';
import SessionCanvasPane from '../components/SessionCanvasPane.vue';
import { useWorkspaceSessions } from '../state/workspaceSessions';
import { useWorkspaceSocket } from '../state/workspaceSocket';
import { loadQueueingJitter, loadSmartQueueing } from '../state/queueingSettings';
import { handleSessionLeave, handleSessionStatusEvent, useSessionQueueing } from '../state/sessionQueueing';
import { getWorkspaceSetting, setWorkspaceSetting } from '../state/workspaceSettings';
import { getLastLocation, isLocationTrackingEnabled } from '../state/locationTracking';
import { mcpThemeVars, observeMcpTheme } from '../utils/mcpTheme';
import { ensurePolymrRuntimeGlobals } from '../utils/polymrRuntime';
import {
	archiveSession,
	deleteSession,
	getAssistants,
	getWorkspaceAssistants,
	getSessionCanvases,
	getSessionEvents,
	getSessionSummary,
	getSessionTechnicalDetails,
	getSessionPrompt,
	pruneSession,
	getWorkspaceTags,
	getMcpServers,
	getWorkspaceUsers,
	updateSessionTags,
	updateSessionScopes,
	addSessionMcpServer,
	updateSession,
	addSessionParticipant,
	getRecording,
	uploadSessionVoiceRecording,
	loadActiveTenant,
	loadSession,
	apiBaseUrl
} from '../api';
const props = defineProps({ forceSessionId: { type: String, default: '' } })
const route = useRoute()
const router = useRouter()
const workspaceId = route.params.workspaceId
const tenantId = ref(loadActiveTenant())
const currentUserId = ref(loadSession()?.userId || '')
const lastAssistantKey = computed(() => `polymr.lastAssistant.${workspaceId}`)
const recordingSeedId = computed(() => String(route.query.recordingId || ''))
const recordingSeeded = ref(false)
const sessions = useWorkspaceSessions(workspaceId)
const selectedSessionId = ref('')
const selectedSessionSummary = ref(null)
const sessionEvents = ref([])
const selectedEpoch = ref('current')
const sessionWindowSize = 25
const sessionWindowOffset = ref(0)
const hasMoreSessionEvents = ref(false)
const loadingMoreSessionEvents = ref(false)
const sessionParticipants = ref([])
const sessionTags = ref([])
const sessionCanvases = ref([])
const activeCanvasId = ref('')
const workspaceTags = ref([])
const workspaceUsers = ref([])
const uiContainer = ref(null)
const sessionViewRef = ref(null)
const sessionHeaderRef = ref(null)
const sessionLogRef = ref(null)
const canvasPaneRef = ref(null)
const composerPanelRef = ref(null)
const composerInputRef = ref(null)
const voiceButtonRef = ref(null)
const workspaceSocketState = useWorkspaceSocket(tenantId.value, workspaceId)
const socketConnected = computed(() => workspaceSocketState.connected.value)
const socketInitialized = computed(() => workspaceSocketState.initialized.value)
const pendingActiveSessionId = ref('')
const abortingSessions = ref({})
const streamingMessages = ref({})
const thinkingMessages = ref({})
let unregisterSocketHandler = null
const messageInput = ref('')
const attachments = ref([])
const voiceEnabled = ref(getWorkspaceSetting(workspaceId, 'polymr.voice.enabled') === 'true')
const ttsEnabled = ref(getWorkspaceSetting(workspaceId, 'polymr.tts.enabled') === 'true')
const ttsPauseVad = ref(getWorkspaceSetting(workspaceId, 'polymr.tts.pause_vad') !== 'false')
const ttsIsPlaying = ref(false)
const autoSendEnabled = ref(true)
const allowAutoSend = ref(false)
const autoSendDelay = ref(3000)
const sendTimer = ref(null)
const sendProgress = ref(0)
let sendProgressFrame = null
const dropActive = ref(false)
const attachmentsPopupOpen = ref(false)
const composerFileInputRef = ref(null)
const queueState = useSessionQueueing(workspaceId)
const queuedMessagesBySession = queueState.queues
const dequeuePendingBySession = queueState.pending
const dequeueTimersBySession = queueState.timers
const lastSentPayloadBySession = ref({})
const smartQueueing = ref(loadSmartQueueing())
const recentUnlockBySession = ref({})
const queueingJitter = ref(loadQueueingJitter())
const typingBySession = ref({})
const typingClock = ref(Date.now())
const typingTimeoutMs = 2000
const typingHeartbeatMs = 1000
const typingActive = ref(false)
const lastTypingSentAt = ref(0)
let typingInterval = null
const assistants = ref([])
const workerProgressByToolCall = ref({})
const selectedWorkerToolCallId = ref('')
const workerPaneOpen = ref(false)
const status = ref('')
const {
  enqueue: enqueueTts,
  stopCurrent: stopTts,
  setEnabled: setTtsEnabled,
  onPlayStart: onTtsPlayStart,
  onPlayEnd: onTtsPlayEnd,
  resetSession: resetTtsSession,
} = usePiperTts()
const speakStates = ref({})
const speakHistory = ref({})
setTtsEnabled(ttsEnabled.value)
let statusTimer = null
const isAlive = ref(true)
let rafId = null
const didDetach = ref(false)
const isEmbedded = computed(() => !!props.forceSessionId)
const activeStatuses = ['ACTIVE', 'PAUSED']
const canvasHostRefs = ref({})
const canvasStyleIds = new Set()
const sessionContentRef = ref(null)
const defaultCanvasPaneWidth = 560
const canvasPaneWidthSettingKey = 'polymr.session.canvasPaneWidth'
const canvasPaneWidth = ref(
	Number(getWorkspaceSetting(workspaceId, canvasPaneWidthSettingKey)) || defaultCanvasPaneWidth
)
const minCanvasPaneWidth = 320
const minSessionLogWidth = 360
let stopCanvasResize = null
const autoScrollActive = ref(false)
const autoScrollTargetId = ref('')
const pendingScrollText = ref('')
const pendingScrollAfterAssistant = ref(false)
const lastViewportHeight = ref(0)
const isResizing = ref(false)
let resizeTimer = null
let headerObserver = null
let logObserver = null
let logMutationObserver = null
const deleteConfirmOpen = ref(false)
const sessionToDelete = ref(null)
const closeConfirmOpen = ref(false)
const renameOpen = ref(false)
const renameValue = ref('')
const techDetailsOpen = ref(false)
const techDetailsLoading = ref(false)
const techDetailsError = ref('')
const techDetails = ref(null)
const techServerOptions = ref([])
const techServerSelection = ref('')
const techServerLoading = ref(false)
const promptOpen = ref(false)
const promptLoading = ref(false)
const promptError = ref('')
const promptText = ref('')
const promptNodeLabel = ref('')
const techScopeSaving = ref(false)
const expandedToolServers = ref(new Set())
const imagePreviewOpen = ref(false)
const imagePreviewUrl = ref('')
const imagePreviewListener = (event) => {
	if (event.key === 'Escape' && imagePreviewOpen.value) {
		closeImagePreview()
	}
}
const downloadStateByKey = ref({})
const statusTone = ref('')
const decisionRemember = ref({})
const decisionRequestDetailsOpen = ref({})
const decisionScopeState = ref({})
const reviewDetailsOpen = ref({})
const toolOutputExpanded = ref({})
const showResolvedApprovals = ref(false)
const traceEnabled = ref({})
const traceOpen = ref(false)
const traceCollapsed = ref(false)
const traceEventsBySession = ref({})
const traceDroppedBySession = ref({})
const traceDetailsOpen = ref({})
const traceMaxEvents = 500
const sessionPruneToast = ref('')
const pruningSession = ref(false)
let sessionPruneToastTimer = null
const inspectOpen = ref(false)
const inspectTarget = ref(null)
const inspectMode = ref('pretty')
const inspectInputExpanded = ref(false)
const inspectOutputExpanded = ref(false)
const inspectTab = ref('input')
const decisionToolViewCache = new Map()
const toolUiViewCache = new Map()
const notify = (message, tone = '') => {
	status.value = message
	statusTone.value = tone
	if (statusTimer) {
		clearTimeout(statusTimer)
	}
	statusTimer = setTimeout(
		() => {
			status.value = ''
			statusTone.value = ''
		},
		4000
	)
}
const notifySessionPruned = async(payload = {}) => {
	const prunedEvents = Number(payload?.pruned_events || 0)
	if (!Number.isFinite(prunedEvents) || prunedEvents <= 0) {
		return
	}
	const prunedTokens = Number(payload?.pruned_tokens || 0)
	sessionPruneToast.value = prunedTokens > 0
		? `Session pruned. ${prunedEvents} events optimized, saving about ${formatNumber(prunedTokens)} tokens.`
		: `Session pruned. ${prunedEvents} events optimized.`
	if (sessionPruneToastTimer) {
		clearTimeout(sessionPruneToastTimer)
	}
	sessionPruneToastTimer = setTimeout(
		() => {
			sessionPruneToast.value = ''
		},
		5000
	)
	if (!tenantId.value || !workspaceId.value || !selectedSessionId.value) {
		return
	}
	try {
		const sessionId = selectedSessionId.value
		const [summary, events] = await Promise.all(
			[
				getSessionSummary(tenantId.value, workspaceId.value, sessionId),
				getSessionEvents(
					tenantId.value,
					workspaceId.value,
					sessionId,
					{ limit: sessionWindowSize, offset: 0, epoch: selectedEpoch.value }
				),
			]
		)
		if (selectedSessionId.value === summary?.id) {
			selectedSessionSummary.value = summary
		}
		if (selectedSessionId.value === sessionId) {
			sessionEvents.value = Array.isArray(events) ? events : []
			sessionWindowOffset.value = 0
			hasMoreSessionEvents.value = Array.isArray(events) && events.length === sessionWindowSize
		}
	}
	catch {
		// Best effort refresh so telemetry and latest event usage catch up after pruning.
	}
}
const handleSessionExit = (sessionId, message) => {
	if (!sessionId || selectedSessionId.value !== sessionId) {
		return
	}
	selectedSessionId.value = ''
	sessionEvents.value = []
	sessionTags.value = []
	workspaceTags.value = []
	selectedSessionSummary.value = null
	stopTrace(sessionId)
	if (message) {
		notify(message)
	}
	router.replace({ name: 'workspace-history', params: { workspaceId } })
}
const selectedSessionFromStore = computed(() => sessions.value.find((session) => session.id === selectedSessionId.value))
const selectedSession = computed(() => {
	if (!selectedSessionFromStore.value) {
		return selectedSessionSummary.value
	}
	return mergeLiveState(selectedSessionFromStore.value, selectedSessionSummary.value)
})
const sortedTagCategories = computed(() => [...(workspaceTags.value || [])].sort((a, b) => {
		const priorityDelta = Number(a?.priority || 0) - Number(b?.priority || 0)
		if (priorityDelta !== 0) {
			return priorityDelta
		}
		return String(a?.name || '').localeCompare(String(b?.name || ''))
	}))
const sessionTagByCategory = (categoryId) => sessionTags.value.find((tag) => tag?.category_id === categoryId) || null
const tagCategoryLabel = (category) => {
	if (!category) {
		return ''
	}
	const name = String(category.name || category.slug || '')
	const normalized = name.replace(/_/g, ' ')
	return normalized.charAt(0).toUpperCase() + normalized.slice(1)
}
const handleSessionTagChange = async(categoryId, event) => {
	if (!selectedSessionId.value || !categoryId) {
		return
	}
	const nextValue = event?.target?.value || ''
	const nextTagIds = sessionTags.value
		.filter((tag) => tag?.category_id && tag.category_id !== categoryId)
		.map((tag) => tag.value_id)
		.filter(Boolean)
	if (nextValue && nextValue !== '__none__') {
		nextTagIds.push(nextValue)
	}
	try {
		const details = await updateSessionTags(tenantId.value, workspaceId, selectedSessionId.value, { tag_ids: nextTagIds })
		sessionTags.value = Array.isArray(details?.tags) ? details.tags : []
		notify('Session tags updated.')
	}
	catch (error) {
		notify(error?.message || 'Unable to update tags.')
	}
}
const isArchived = computed(() => {
	const status = selectedSession.value?.status
	if (!status) {
		return false
	}
	return !activeStatuses.includes(status)
})
const sessionStatusLabel = computed(() => {
	const status = selectedSession.value?.status
	if (status === 'ACTIVE') {
		return 'Working'
	}
	if (status === 'PAUSED') {
		return 'Waiting'
	}
	if (!status) {
		return ''
	}
	if (status === 'ARCHIVED') {
		return 'Archived'
	}
	return status.replace('_', ' ')
})
const sessionTelemetry = computed(() => selectedSessionSummary.value?.model_telemetry || selectedSession.value?.model_telemetry || null)
const formatNumber = (value) => {
	const numberValue = Number(value)
	if (!Number.isFinite(numberValue)) {
		return value === null || value === undefined ? '' : String(value)
	}
	return new Intl.NumberFormat().format(numberValue)
}
const formatCurrency = (value, currency) => {
	const numberValue = Number(value)
	if (!Number.isFinite(numberValue)) {
		return value === null || value === undefined ? '' : String(value)
	}
	const normalizedCurrency = currency || 'USD'
	try {
		return new Intl.NumberFormat(
			undefined,
			{
				style: 'currency',
				currency: normalizedCurrency,
				currencyDisplay: normalizedCurrency === 'USD' ? 'narrowSymbol' : 'symbol',
				maximumFractionDigits: 3
			}
		).format(numberValue)
	}
	catch {
		return `${formatNumber(numberValue)} ${normalizedCurrency}`.trim()
	}
}
const formatPercent = (value) => {
	const numberValue = Number(value)
	if (!Number.isFinite(numberValue)) {
		return ''
	}
	return `${(numberValue * 100).toFixed(1)}%`
}
const latestTokenEvent = computed(() => {
	for (let index = sessionEvents.value.length - 1; index >= 0; index -= 1) {
		const event = sessionEvents.value[index]
		if (!event) {
			continue
		}
		if (event.input_tokens != null || event.output_tokens != null || event.reasoning_tokens != null) {
			return event
		}
	}
	return null
})
const telemetryTokenLabel = computed(() => {
	const event = latestTokenEvent.value
	if (!event) {
		return ''
	}
	const input = event.input_tokens || 0
	const output = event.output_tokens || 0
	const reasoning = event.reasoning_tokens || 0
	const total = input + output + reasoning
	return formatNumber(total)
})
const telemetryTokenTip = computed(() => {
	const event = latestTokenEvent.value
	if (!event) {
		return ''
	}
	const input = event.input_tokens || 0
	const output = event.output_tokens || 0
	const reasoning = event.reasoning_tokens || 0
	const cached = event.cached_input_tokens || 0
	const parts = [
		`Input: ${formatNumber(input)}`,
		`Output: ${formatNumber(output)}`,
	]
	if (reasoning) {
		parts.push(`Thinking: ${formatNumber(reasoning)}`)
	}
	if (cached) {
		parts.push(`Cached input: ${formatNumber(cached)}`)
	}
	return parts.join('\n')
})
const telemetryCostLabel = computed(() => {
	const cost = sessionTelemetry.value?.cost
	if (!cost || cost.amount === null || cost.amount === undefined) {
		return ''
	}
	return formatCurrency(cost.amount, cost.currency)
})
const telemetryCostTip = computed(() => {
	const cost = sessionTelemetry.value?.cost
	if (!cost || cost.amount === null || cost.amount === undefined) {
		return ''
	}
	const totals = sessionTelemetry.value?.token_totals
	const cachedInput = sessionTelemetry.value?.cached_input_tokens || 0
	const parts = [`Estimated cost: ${formatCurrency(cost.amount, cost.currency)}`]
	if (totals) {
		const input = totals.input || 0
		const nonCachedInput = Math.max(0, input - cachedInput)
		parts.push(`Total input: ${formatNumber(input)}`)
		parts.push(`Total cached input: ${formatNumber(cachedInput)}`)
		parts.push(`Total non-cached input: ${formatNumber(nonCachedInput)}`)
		parts.push(`Total output: ${formatNumber(totals.output || 0)}`)
		if (totals.reasoning != null) {
			parts.push(`Total thinking: ${formatNumber(totals.reasoning)}`)
		}
	}
	const prunedTokens = Number(sessionTelemetry.value?.pruned_tokens || 0)
	if (prunedTokens > 0) {
		parts.push(`Context saved by pruning: ${formatNumber(prunedTokens)}`)
	}
	return parts.join('\n')
})
const telemetryThresholds = computed(() => {
	const thresholds = sessionTelemetry.value?.thresholds
	return Array.isArray(thresholds) ? thresholds : []
})
const telemetryTokenLimit = computed(() => {
	const entry = telemetryThresholds.value.find((threshold) => threshold?.key === 'TOKEN_LIMIT')
	if (!entry || entry.value === null || entry.value === undefined) {
		return null
	}
	return Number(entry.value)
})
const telemetryContextUsage = computed(() => {
	const limit = telemetryTokenLimit.value
	const event = latestTokenEvent.value
	if (!limit || limit <= 0 || !event) {
		return ''
	}
	const input = event.input_tokens || 0
	const output = event.output_tokens || 0
	const reasoning = event.reasoning_tokens || 0
	const total = input + output + reasoning
	const percent = Math.min(100, (total / limit) * 100)
	return `${percent.toFixed(1)}%`
})
const telemetryContextTip = computed(() => {
	const limit = telemetryTokenLimit.value
	const event = latestTokenEvent.value
	if (!limit || limit <= 0 || !event) {
		return ''
	}
	const input = event.input_tokens || 0
	const output = event.output_tokens || 0
	const reasoning = event.reasoning_tokens || 0
	const total = input + output + reasoning
	const percent = ((total / limit) * 100).toFixed(1)
	const upcoming = telemetryThresholds.value
		.filter((threshold) => threshold?.value && threshold.value > total)
		.sort((a, b) => (a.value || 0) - (b.value || 0))[0]
	if (upcoming) {
		const label = upcoming.label || upcoming.key || 'Threshold'
		return `Context: ${formatNumber(total)} / ${formatNumber(limit)} (${percent}%). Next: ${label} at ${formatNumber(upcoming.value)}`
	}
	return `Context: ${formatNumber(total)} / ${formatNumber(limit)} (${percent}%)`
})
const telemetryCacheLabel = computed(() => {
	const ratio = sessionTelemetry.value?.cache_ratio
	if (!ratio || ratio <= 0) {
		return ''
	}
	return formatPercent(ratio)
})
const telemetryCacheTip = computed(() => {
	const ratio = sessionTelemetry.value?.cache_ratio
	if (!ratio || ratio <= 0) {
		return ''
	}
	const cached = sessionTelemetry.value?.cached_input_tokens || 0
	const input = sessionTelemetry.value?.token_totals?.input || 0
	return `Cached input: ${formatNumber(cached)} / ${formatNumber(input)} (${formatPercent(ratio)})`
})
const telemetryContextPercent = computed(() => {
	const limit = telemetryTokenLimit.value
	const event = latestTokenEvent.value
	if (!limit || limit <= 0 || !event) {
		return null
	}
	const input = event.input_tokens || 0
	const output = event.output_tokens || 0
	const reasoning = event.reasoning_tokens || 0
	const total = input + output + reasoning
	return Math.min(100, Math.round((total / limit) * 100))
})
const hasCriticalThreshold = computed(() => telemetryThresholds.value.some((threshold) => threshold?.level === 'critical'))
const hasUserWarningThreshold = computed(() => telemetryThresholds.value.some((threshold) => threshold?.key === 'USER_WARNING_LIMIT'))
const hasSystemWarningThreshold = computed(() => telemetryThresholds.value
	.some((threshold) => threshold?.level === 'warning' && threshold?.key !== 'USER_WARNING_LIMIT'))
const telemetryThresholdLabel = (threshold) => {
	if (!threshold) {
		return ''
	}
	if (threshold.value === null || threshold.value === undefined) {
		return threshold.label || threshold.key || ''
	}
	const label = threshold.label || threshold.key || ''
	return `${label} ${formatNumber(threshold.value)}`.trim()
}
const telemetryThresholdsTip = computed(() => {
	if (!telemetryThresholds.value.length) {
		return ''
	}
	const event = latestTokenEvent.value
	if (!event) {
		return ''
	}
	const input = event.input_tokens || 0
	const output = event.output_tokens || 0
	const reasoning = event.reasoning_tokens || 0
	const total = input + output + reasoning
	const crossed = telemetryThresholds.value
		.filter((threshold) => threshold?.level === 'warning' || threshold?.level === 'critical')
		.filter((threshold) => threshold?.value !== null && threshold?.value !== undefined)
		.filter((threshold) => total >= Number(threshold.value))
		.map((threshold) => {
			const label = telemetryThresholdLabel(threshold)
			const description = threshold.description ? ` (${threshold.description})` : ''
			return `${threshold.level}: ${label}${description}`
		})
	const warningTargets = telemetryThresholds.value
		.filter((threshold) => threshold?.key && threshold.key !== 'USER_WARNING_LIMIT')
		.filter((threshold) => threshold?.value !== null && threshold?.value !== undefined)
		.map((threshold) => {
			const value = Number(threshold.value)
			if (!Number.isFinite(value) || value <= 0) {
				return null
			}
			const approaching = Math.floor(value * 0.9)
			if (total < approaching || total >= value) {
				return null
			}
			const label = telemetryThresholdLabel(threshold)
			const description = threshold.description ? ` (${threshold.description})` : ''
			return `approaching: ${label}${description}`
		})
		.filter(Boolean)
	return [...crossed, ...warningTargets].join(' · ')
})
const telemetryAlertClass = computed(() => {
	if (hasCriticalThreshold.value) {
		return 'telemetry-critical'
	}
	if (hasSystemWarningThreshold.value) {
		return 'telemetry-warning'
	}
	if (hasUserWarningThreshold.value) {
		return 'telemetry-user-warning'
	}
	return ''
})
const hasApproachingSystemThreshold = computed(() => {
	const event = latestTokenEvent.value
	if (!event) {
		return false
	}
	const input = event.input_tokens || 0
	const output = event.output_tokens || 0
	const reasoning = event.reasoning_tokens || 0
	const total = input + output + reasoning
	return telemetryThresholds.value
		.filter((threshold) => threshold?.key && threshold.key !== 'USER_WARNING_LIMIT')
		.filter((threshold) => threshold?.value !== null && threshold?.value !== undefined)
		.some((threshold) => {
			const value = Number(threshold.value)
			if (!Number.isFinite(value) || value <= 0) {
				return false
			}
			const approaching = Math.floor(value * 0.9)
			return total >= approaching && total < value
		})
})
const telemetryContextClass = computed(() => {
	const percent = telemetryContextPercent.value
	if (percent !== null && percent >= 95) {
		return 'telemetry-context-critical'
	}
	if (hasUserWarningThreshold.value) {
		return 'telemetry-context-user'
	}
	if (hasSystemWarningThreshold.value || hasCriticalThreshold.value) {
		return 'telemetry-context-system'
	}
	if (hasApproachingSystemThreshold.value) {
		return 'telemetry-context-approaching'
	}
	return 'telemetry-context-ok'
})
const telemetryInfoTip = computed(() => {
	const telemetry = sessionTelemetry.value
	if (!telemetry) {
		return ''
	}
	const notes = []
	if (isArchived.value || telemetry.cost_basis === 'historical') {
		notes.push('Historical cost does not include dynamic threshold surcharges.')
	}
	if (telemetry.approx) {
		notes.push('Token counts may be approximate due to a model switch.')
	}
	return notes.join(' ')
})
const sessionFromRoute = computed(() => {
	if (props.forceSessionId) {
		return props.forceSessionId
	}
	return typeof route.params.sessionId === 'string'
		? route.params.sessionId
		: typeof route.query.session === 'string' ? route.query.session : ''
})
const activeSessionId = ref('')
const activeRetryTimer = ref(null)
const sessionAssistantSelection = ref('')
const workspaceAssistants = computed(() => assistants.value.filter((assistant) => assistant.workspace_id === workspaceId))
const tenantAssistants = computed(() => assistants.value.filter((assistant) => !assistant.workspace_id))
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
const loadWorkspaceTags = async() => {
	if (!tenantId.value) {
		return
	}
	try {
		workspaceTags.value = await getWorkspaceTags(tenantId.value, workspaceId)
	}
	catch {
		workspaceTags.value = []
	}
}
const canvasTabs = computed(() => Array.isArray(sessionCanvases.value) ? sessionCanvases.value : [])
const hasCanvases = computed(() => canvasTabs.value.length > 0)
const clampCanvasPaneWidth = (nextWidth) => {
	const containerWidth = sessionContentRef.value?.clientWidth || 0
	const maxWidth = containerWidth > 0 ? Math.max(minCanvasPaneWidth, containerWidth - minSessionLogWidth) : nextWidth
	return Math.min(Math.max(nextWidth, minCanvasPaneWidth), maxWidth)
}
const persistCanvasPaneWidth = (width) => {
	setWorkspaceSetting(workspaceId, canvasPaneWidthSettingKey, String(width))
}
const handleCanvasResizeMove = (clientX) => {
	const container = sessionContentRef.value
	if (!container) {
		return
	}
	const bounds = container.getBoundingClientRect()
	const nextWidth = bounds.right - clientX
	canvasPaneWidth.value = clampCanvasPaneWidth(nextWidth)
	persistCanvasPaneWidth(canvasPaneWidth.value)
}
const stopCanvasResizeInteraction = () => {
	document.body.classList.remove('is-resizing-canvas-pane')
	if (stopCanvasResize) {
		stopCanvasResize()
		stopCanvasResize = null
	}
}
const startCanvasResize = (event) => {
	if (!hasCanvases.value) {
		return
	}
	const handle = event.currentTarget
	if (!(handle instanceof HTMLElement)) {
		return
	}
	event.preventDefault()
	stopCanvasResizeInteraction()
	document.body.classList.add('is-resizing-canvas-pane')
	handleCanvasResizeMove(event.clientX)
	handle.setPointerCapture(event.pointerId)
	const move = (moveEvent) => {
		if (moveEvent.pointerId !== event.pointerId) {
			return
		}
		handleCanvasResizeMove(moveEvent.clientX)
	}
	const stop = (moveEvent) => {
		if (moveEvent.pointerId !== event.pointerId) {
			return
		}
		stopCanvasResizeInteraction()
	}
	handle.addEventListener('pointermove', move)
	handle.addEventListener('pointerup', stop)
	handle.addEventListener('pointercancel', stop)
	handle.addEventListener('lostpointercapture', stop)
	stopCanvasResize = () => {
		handle.removeEventListener('pointermove', move)
		handle.removeEventListener('pointerup', stop)
		handle.removeEventListener('pointercancel', stop)
		handle.removeEventListener('lostpointercapture', stop)
		if (handle.hasPointerCapture(event.pointerId)) {
			handle.releasePointerCapture(event.pointerId)
		}
	}
}
const upsertSessionActivityEvent = (sessionId, activity) => {
	if (sessionId !== selectedSessionId.value) {
		return
	}
	const activityEventId = (toolCallId) => `activity:${sessionId}:${toolCallId || 'tool_call'}`
	const removeIndex = sessionEvents.value
		.findIndex((entry) => typeof entry?.id === 'string' && entry.id.startsWith(`activity:${sessionId}:`))
	if (!activity || activity.kind !== 'tool_call') {
		if (removeIndex >= 0) {
			sessionEvents.value.splice(removeIndex, 1)
		}
		return
	}
	const toolCallId = activity.tool_call_id || activity.id || ''
	const payload = {
		tool_call_id: toolCallId || undefined,
		id: toolCallId || undefined,
		tool_name: activity.tool_name,
		summary: activity.summary,
		input_template: activity.input_template,
		arguments: activity.arguments
	}
	const synthetic = {
		id: activityEventId(toolCallId),
		renderId: activityEventId(toolCallId),
		type: 'TOOL_CALL_PENDING',
		created_at: activity.started_at || new Date().toISOString(),
		payload,
		synthetic_activity: true
	}
	if (removeIndex >= 0) {
		sessionEvents.value.splice(removeIndex, 1, synthetic)
	}
	else {
		sessionEvents.value.push(synthetic)
	}
}
const loadSessionSummary = async(sessionId) => {
	if (!tenantId.value || !sessionId) {
		return
	}
	const [summary, canvases] = await Promise.all(
		[
			getSessionSummary(tenantId.value, workspaceId, sessionId),
			getSessionCanvases(tenantId.value, workspaceId, sessionId),
			loadSessionTags(sessionId),
		]
	)
	if (!summary) {
		return
	}
	selectedSessionSummary.value = summary
	sessionCanvases.value = Array.isArray(canvases) ? canvases : []
	if (!activeCanvasId.value
			|| !sessionCanvases.value.some((canvas) => canvas.logical_id === activeCanvasId.value)) {
		activeCanvasId.value = sessionCanvases.value[0]?.logical_id || ''
	}
	if (activeStatuses.includes(summary.status)) {
		sessions.value = sessions.value.map((item) => item.id === summary.id ? mergeLiveState(summary, item) : item)
	}
}
const loadSessionTags = async(sessionId) => {
	if (!tenantId.value || !sessionId) {
		return
	}
	try {
		const details = await getSessionTechnicalDetails(tenantId.value, workspaceId, sessionId)
		sessionTags.value = Array.isArray(details?.tags) ? details.tags : []
	}
	catch {
		sessionTags.value = []
	}
}
const syncDecisionEventState = (event) => {
	if (event?.type !== 'DECISION_REQUEST') {
		return
	}
	ensureDecisionDefaults(event)
	const status = decisionStatus(event)
	if (status && decisionRemember.value[event.id] === undefined) {
		decisionRemember.value = { ...decisionRemember.value, [event.id]: !!status.remember }
	}
}
const normalizeVoiceCommand = (text) => {
	if (typeof text !== 'string') {
		return ''
	}
	const normalized = text.trim().toLowerCase().replace(/[^a-z\s]+/g, '').replace(/\s+/g, ' ')
	// Short command transcriptions tend to hear "allow" as "hello", so accept a small alias set here.
	if (normalized === 'hello' || normalized === 'yellow') {
		return 'allow'
	}
	if (normalized === 'denied') {
		return 'deny'
	}
	if (normalized === 'stop') {
		return 'abort'
	}
	return normalized
}
const latestPendingDecisionEvent = () => {
	for (let index = filteredSessionEvents.value.length - 1; index >= 0; index -= 1) {
		const event = filteredSessionEvents.value[index]
		if (event?.type !== 'DECISION_REQUEST') {
			continue
		}
		if (decisionStatus(event)) {
			continue
		}
		return event
	}
	return null
}
const tryHandleVoiceCommand = (text) => {
	if (messageInput.value.trim() || attachments.value.length) {
		return false
	}
	const command = normalizeVoiceCommand(text)
	if (!command) {
		return false
	}
	const pendingDecisionEvent = latestPendingDecisionEvent()
	if (pendingDecisionEvent && (command === 'allow' || command === 'deny')) {
		sendDecision(pendingDecisionEvent, command)
		triggerVoiceCommandFeedback(command)
		return true
	}
	if (pendingDecisionEvent && (command === 'always allow' || command === 'always deny')) {
		decisionRemember.value = { ...decisionRemember.value, [pendingDecisionEvent.id]: true }
		sendDecision(pendingDecisionEvent, command === 'always allow' ? 'allow' : 'deny')
		triggerVoiceCommandFeedback(command === 'always allow' ? 'allow' : 'deny')
		return true
	}
	if (command === 'abort' && isThinking.value) {
		abortSession()
		triggerVoiceCommandFeedback('abort')
		return true
	}
	return false
}
const voiceTooltip = computed(() => {
	const stateLabel = voiceEnabled.value ? 'Voice on (Alt+R)' : 'Voice off (Alt+R)'
	if (!voiceEnabled.value) {
		return stateLabel
	}
	return `${stateLabel}\nSay: allow, deny, always allow, always deny, abort`
})
const updateWorkerProgress = (payload, timestamp) => {
	if (!payload || typeof payload !== 'object') {
		return
	}
	const toolCallId = payload.tool_call_id || payload.toolCallId || 'default'
	const current = workerProgressByToolCall.value[toolCallId]
		|| {
			tool_call_id: toolCallId,
			total: 0,
			done: 0,
			failed: 0,
			children: [],
			updated_at: 0
		}
	const next = { ...current }
	if (Number.isFinite(payload.total)) {
		next.total = payload.total
	}
	if (Number.isFinite(payload.done)) {
		next.done = payload.done
	}
	if (Number.isFinite(payload.failed)) {
		next.failed = payload.failed
	}
	if (Array.isArray(payload.children)) {
		next.children = payload.children
	}
	if (payload.child && payload.child.session_id) {
		const children = Array.isArray(next.children) ? [...next.children] : []
		const index = children.findIndex((child) => child.session_id === payload.child.session_id)
		if (index >= 0) {
			children[index] = { ...children[index], ...payload.child }
		}
		else {
			children.push(payload.child)
		}
		next.children = children
	}
	if (Array.isArray(next.children) && (payload.children || payload.child)) {
		let done = 0
		let failed = 0
		next.children
			.forEach((child) => {
				const status = (child?.status || '').toLowerCase()
				if (status === 'completed' || status === 'complete') {
					done += 1
				}
				else if (status === 'failed' || status === 'error') {
					failed += 1
				}
			})
		next.done = done
		next.failed = failed
		next.total = next.total || next.children.length
	}
	next.updated_at = Number.isFinite(timestamp) ? timestamp : Date.now()
	workerProgressByToolCall.value = { ...workerProgressByToolCall.value, [toolCallId]: next }
	if (!selectedWorkerToolCallId.value) {
		selectedWorkerToolCallId.value = toolCallId
	}
}
const selectedWorkerProgress = computed(() => {
	const selected = selectedWorkerToolCallId.value
	if (selected && workerProgressByToolCall.value[selected]) {
		return workerProgressByToolCall.value[selected]
	}
	const entries = Object.values(workerProgressByToolCall.value)
		.filter((entry) => entry && entry.tool_call_id)
		.sort((a, b) => (b.updated_at || 0) - (a.updated_at || 0))
	return entries[0] || null
})
const workerStatusPillClass = (status) => {
	const value = String(status || '').toLowerCase()
	if (['failed', 'error'].includes(value)) {
		return 'error'
	}
	if (['completed', 'complete', 'success'].includes(value)) {
		return 'success'
	}
	if (['running', 'queued', 'active'].includes(value)) {
		return 'muted'
	}
	if (['paused', 'cancelled'].includes(value)) {
		return 'muted'
	}
	return 'muted'
}
const workerStatusLabel = (status) => {
	if (!status) {
		return 'UNKNOWN'
	}
	return String(status).toUpperCase()
}
const handleWorkspaceMessage = (event) => {
	if (!event || typeof event !== 'object') {
		return
	}
	if (event.type === 'session.trace' && event.session_id && event.payload) {
		const sessionId = event.session_id
		if (!traceEnabled.value[sessionId]) {
			return
		}
		const entries = traceEventsBySession.value[sessionId] || []
		const traceEntry = {
			...event.payload,
			_key: `${event.payload.timestamp || Date.now()}-${event.payload.type || 'trace'}-${
				Math.random().toString(16).slice(2)
			}`
		}
		const next = [traceEntry, ...entries]
		let dropped = traceDroppedBySession.value[sessionId] || 0
		if (next.length > traceMaxEvents) {
			const overflow = next.length - traceMaxEvents
			next.splice(next.length - overflow, overflow)
			dropped += overflow
		}
		traceEventsBySession.value = { ...traceEventsBySession.value, [sessionId]: next }
		traceDroppedBySession.value = { ...traceDroppedBySession.value, [sessionId]: dropped }
		return
	}
	if (event.type === 'session.event' && event.session_id === selectedSessionId.value && event.payload) {
		const eventRecord = event.payload
		const sessionId = event.session_id
		if (selectedEpochNumber.value && eventRecord?.epoch_id !== selectedEpochNumber.value) {
			return
		}
		const actorId = eventRecord?.user_id || eventRecord?.userId
		if (actorId && typingBySession.value[sessionId]?.[actorId]) {
			const next = { ...typingBySession.value[sessionId] }
			delete next[actorId]
			typingBySession.value = { ...typingBySession.value, [sessionId]: next }
		}
		if (eventRecord?.payload?.kind === 'worker_progress') {
			const index = sessionEvents.value.findIndex((entry) => entry.id === eventRecord.id)
			if (index >= 0) {
				sessionEvents.value.splice(index, 1, eventRecord)
			}
			else {
				sessionEvents.value.push(eventRecord)
			}
			const timestamp = Date.parse(eventRecord?.created_at || eventRecord?.createdAt || '')
			updateWorkerProgress(eventRecord.payload, Number.isFinite(timestamp) ? timestamp : undefined)
		}
		else if (eventRecord?.type === 'TOOL_CALL' || eventRecord?.type === 'TOOL_CALL_PENDING') {
			const toolCallId = eventRecord?.payload?.tool_call_id || eventRecord?.payload?.id || ''
			if (toolCallId) {
				const syntheticRenderId = `activity:${sessionId}:${toolCallId}`
				const syntheticIndex = sessionEvents.value.findIndex((entry) => (entry?.renderId || entry?.id) === syntheticRenderId)
				if (syntheticIndex >= 0) {
					sessionEvents.value.splice(syntheticIndex, 1, { ...eventRecord, renderId: syntheticRenderId })
				}
				else {
					sessionEvents.value.push({ ...eventRecord, renderId: eventRecord.id })
				}
			}
			else {
				sessionEvents.value.push({ ...eventRecord, renderId: eventRecord.id })
			}
		}
		else {
			sessionEvents.value.push({ ...eventRecord, renderId: eventRecord.id })
		}
		syncDecisionEventState(eventRecord)
		if (eventRecord?.type === 'ASSISTANT_MESSAGE') {
			streamingMessages.value = { ...streamingMessages.value, [sessionId]: '' }
			resetSpeakState(sessionId)
			resetThinkState(sessionId)
		}
		if (pendingScrollText.value
				&& eventRecord?.type === 'USER_MESSAGE'
				&& eventText(eventRecord) === pendingScrollText.value) {
			autoScrollActive.value = true
			autoScrollTargetId.value = ''
			pendingScrollText.value = ''
			pendingScrollAfterAssistant.value = true
		}
		if (pendingScrollAfterAssistant.value
				&& autoScrollTargetId.value
				&& (eventRecord?.type === 'TOOL_CALL'
					|| eventRecord?.type === 'TOOL_CALL_PENDING'
					|| eventRecord?.type === 'TOOL_RESULT')) {
			autoScrollActive.value = true
			autoScrollTargetId.value = ''
		}
		if (pendingScrollAfterAssistant.value
				&& !autoScrollTargetId.value
				&& eventRecord?.type !== 'ASSISTANT_MESSAGE') {
			autoScrollActive.value = true
			autoScrollTargetId.value = ''
		}
		if (eventRecord?.type === 'SESSION_TAG_CHANGE') {
			autoScrollActive.value = true
			autoScrollTargetId.value = eventRecord.id
		}
		if (pendingScrollAfterAssistant.value && eventRecord?.type === 'ASSISTANT_MESSAGE') {
			autoScrollActive.value = true
			autoScrollTargetId.value = latestAutoScrollTargetId()
		}
		nextTick(() => {
			attachMcpViews()
			maybeAutoScroll()
		})
	}
	if (event.type === 'voice.transcription' && event.session_id === selectedSessionId.value) {
		applyVoiceTranscript(event.payload?.text || '')
		return
	}
	if (event.type === 'session.worker_progress' && event.session_id === selectedSessionId.value) {
		updateWorkerProgress(event.payload)
		return
	}
	if (event.type === 'voice.transcription.error' && event.session_id === selectedSessionId.value) {
		notify(event.payload?.error || 'Transcription failed.')
		return
	}
	if (event.type === 'session.participants' && event.session_id === selectedSessionId.value) {
		sessionParticipants.value = Array.isArray(event.payload) ? event.payload : []
	}
	if (event.type === 'session.pruned' && event.session_id === selectedSessionId.value) {
		notifySessionPruned(event.payload || {})
		return
	}
	if (event.type === 'session.activity' && event.session_id === selectedSessionId.value) {
		upsertSessionActivityEvent(event.session_id, event.payload || null)
		if (selectedSessionSummary.value?.id === event.session_id) {
			selectedSessionSummary.value = { ...selectedSessionSummary.value, current_activity: event.payload || null }
		}
		nextTick(() => maybeAutoScroll())
		return
	}
	if (event.type === 'session.canvases' && event.session_id === selectedSessionId.value) {
		const updates = Array.isArray(event.payload) ? event.payload : []
		const next = [...sessionCanvases.value]
		updates.forEach((canvas) => {
				const index = next.findIndex((entry) => entry.logical_id === canvas.logical_id)
				if (index >= 0) {
					next.splice(index, 1, canvas)
				}
				else {
					next.push(canvas)
				}
			})
		sessionCanvases.value = next
		activeCanvasId.value = updates[updates.length - 1]?.logical_id || next[0]?.logical_id || ''
		return
	}
	if (event.type === 'session.typing' && event.session_id) {
		const sessionId = event.session_id
		const payload = event.payload || {}
		const userId = payload.user_id
		if (!userId) {
			return
		}
		if (!typingBySession.value[sessionId]) {
			typingBySession.value = { ...typingBySession.value, [sessionId]: {} }
		}
		if (payload.typing === false) {
			const next = { ...typingBySession.value[sessionId] }
			delete next[userId]
			typingBySession.value = { ...typingBySession.value, [sessionId]: next }
			return
		}
		const entry = {
			user_id: userId,
			display_name: payload.display_name || 'User',
			typed_at: payload.typed_at || new Date().toISOString()
		}
		typingBySession.value = { ...typingBySession.value, [sessionId]: { ...typingBySession.value[sessionId], [userId]: entry } }
		return
	}
	if (event.type === 'chat.rejected' && event.session_id === selectedSessionId.value) {
		const payload = lastSentPayloadBySession.value[event.session_id]
		if (payload) {
			queueMessage(event.session_id, payload)
		}
		notify(event.payload?.message || 'Message could not be processed.')
		return
	}
	if (event.type === 'session.updated' && event.payload?.id) {
		const updatedId = event.payload.id
		if (updatedId === selectedSessionId.value) {
			selectedSessionSummary.value = { ...(selectedSessionSummary.value || { id: updatedId }), ...event.payload }
		}
		if (event.payload.status && event.payload.status !== 'ACTIVE') {
			abortingSessions.value = { ...abortingSessions.value, [updatedId]: false }
			streamingMessages.value = { ...streamingMessages.value, [updatedId]: '' }
		}
		if (updatedId === selectedSessionId.value
				&& event.payload.status
				&& !activeStatuses.includes(event.payload.status)) {
			if (traceActive.value) {
				stopTrace(updatedId, true)
			}
		}
	}
	if (event.type === 'session.telemetry' && event.session_id && event.payload) {
		const updatedId = event.session_id
		sessions.value = sessions.value.map((item) => item.id === updatedId ? { ...item, model_telemetry: event.payload } : item)
		if (updatedId === selectedSessionId.value) {
			selectedSessionSummary.value = { ...(selectedSessionSummary.value || { id: updatedId }), model_telemetry: event.payload }
		}
		return
	}
	if (event.type === 'session.deleted' && event.payload?.id) {
		handleSessionExit(event.payload.id, 'Session deleted.')
	}
	if (event.type === 'session.status' && event.session_id) {
		const locked = !!event.payload?.locked
		const status = event.payload?.status
		const previousSession = sessions.value.find((item) => item.id === event.session_id)
		const previousLocked = previousSession?.locked
		if (status) {
			sessions.value = sessions.value
				.map((item) => item.id === event.session_id ? {
				...item,
				status,
				needs_input: status === 'PAUSED',
				locked
			} : item)
			if (selectedSessionSummary.value?.id === event.session_id) {
				selectedSessionSummary.value = {
					...selectedSessionSummary.value,
					status,
					needs_input: status === 'PAUSED',
					locked
				}
			}
		}
		abortingSessions.value = { ...abortingSessions.value, [event.session_id]: false }
		handleSessionStatusEvent({
			event,
			queuesRef: queuedMessagesBySession,
			pendingRef: dequeuePendingBySession,
			timersRef: dequeueTimersBySession,
			smartQueueing: smartQueueing.value,
			send: sendWorkspaceCommand,
			jitter: queueingJitter.value
		})
		if (!locked) {
			const transitioned = previousLocked === true || previousLocked === undefined
			if (transitioned) {
				streamingMessages.value = { ...streamingMessages.value, [event.session_id]: '' }
				resetSpeakState(event.session_id)
				resetThinkState(event.session_id)
				recentUnlockBySession.value = { ...recentUnlockBySession.value, [event.session_id]: Date.now() }
				if (!smartQueueing.value && event.session_id === selectedSessionId.value) {
					flushQueuedMessages(event.session_id)
				}
			}
		}
	}
	if (event.type === 'session.abort' && event.session_id) {
		abortingSessions.value = { ...abortingSessions.value, [event.session_id]: true }
	}
	if (event.type === 'chat.token' && event.session_id) {
		const token = event.payload?.text || ''
		if (!token) {
			return
		}
		const visible = processSpeakToken(event.session_id, token)
		streamingMessages.value = { ...streamingMessages.value, [event.session_id]: visible }
		if (pendingScrollAfterAssistant.value) {
			autoScrollActive.value = true
			autoScrollTargetId.value = latestAutoScrollTargetId()
		}
		nextTick(() => maybeAutoScroll())
	}
}
const loadEvents = async() => {
	if (!selectedSessionId.value) {
		sessionEvents.value = []
		sessionParticipants.value = []
		sessionTags.value = []
		sessionCanvases.value = []
		activeCanvasId.value = ''
		selectedSessionSummary.value = null
		sessionWindowOffset.value = 0
		hasMoreSessionEvents.value = false
		return
	}
	sessionWindowOffset.value = 0
	hasMoreSessionEvents.value = false
	recalibrateLogHeight(true)
	const requestOptions = { limit: sessionWindowSize, reverse: true }
	if (selectedEpoch.value !== 'current') {
		requestOptions.epoch = selectedEpochNumber.value
	}
	const sessionId = selectedSessionId.value
	const events = await getSessionEvents(tenantId.value, workspaceId, sessionId, requestOptions)
	sessionEvents.value = events
	workerProgressByToolCall.value = {}
	selectedWorkerToolCallId.value = ''
	events.forEach((event) => {
			if (isWorkerProgressEvent(event)) {
				const timestamp = Date.parse(event?.created_at || event?.createdAt || '')
				updateWorkerProgress(event.payload, Number.isFinite(timestamp) ? timestamp : undefined)
			}
		})
	hasMoreSessionEvents.value = events.length === sessionWindowSize
	resetSpeakState(sessionId)
	resetThinkState(sessionId)
	await loadSessionSummary(sessionId)
	if (selectedSessionId.value === sessionId && selectedSessionSummary.value?.current_activity) {
		upsertSessionActivityEvent(sessionId, selectedSessionSummary.value.current_activity)
	}
	await nextTick()
	attachMcpViews()
	recalibrateLogHeight(false)
	scrollToBottom()
}
const loadMoreSessionEvents = async() => {
	if (!selectedSessionId.value || loadingMoreSessionEvents.value || !hasMoreSessionEvents.value) {
		return
	}
	const previousHeight = sessionLogRef.value ? sessionLogRef.value.scrollHeight : 0
	loadingMoreSessionEvents.value = true
	try {
		const nextOffset = sessionWindowOffset.value + sessionWindowSize
		const events = await getSessionEvents(
			tenantId.value,
			workspaceId,
			selectedSessionId.value,
			{
				epoch: selectedEpochNumber.value,
				limit: sessionWindowSize,
				offset: nextOffset,
				reverse: true
			}
		)
		sessionWindowOffset.value = nextOffset
		hasMoreSessionEvents.value = events.length === sessionWindowSize
		if (events.length) {
			sessionEvents.value.unshift(...events)
			events.forEach((event) => syncDecisionEventState(event))
			await nextTick()
			attachMcpViews()
			if (sessionLogRef.value) {
				const nextHeight = sessionLogRef.value.scrollHeight
				sessionLogRef.value.scrollTop += nextHeight - previousHeight
			}
		}
	}
	finally {
		loadingMoreSessionEvents.value = false
	}
}
const sendWorkspaceCommand = (payload) => {
	const sent = workspaceSocketState.send(payload)
	if (!sent) {
		console.warn('Workspace socket not open', payload)
		return false
	}
	console.debug('WS send', payload)
	schedulePing()
	return true
}
const schedulePing = () => {
	if (pingTimer.value) {
		clearTimeout(pingTimer.value)
		pingTimer.value = null
	}
	if (!socketConnected.value) {
		return
	}
	if (!selectedSessionId.value) {
		return
	}
	pingTimer.value = setTimeout(
		() => {
			pingTimer.value = null
			if (!socketConnected.value || !selectedSessionId.value) {
				return
			}
			sendWorkspaceCommand({ type: 'ws.ping', session_id: selectedSessionId.value })
		},
		pingIntervalMs
	)
}
const isActiveSession = (session) => !!session && activeStatuses.includes(session.status)
const attachSession = async(sessionId) => {
	if (!sessionId) {
		return
	}
	if (isEmbedded.value) {
		pendingActiveSessionId.value = sessionId
		trySendActiveSession()
		sessionParticipants.value = []
		return
	}
	const session = sessions.value.find((item) => item.id === sessionId)
	if (!session) {
		pendingActiveSessionId.value = sessionId
		return
	}
	if (session && !isActiveSession(session)) {
		pendingActiveSessionId.value = sessionId
		return
	}
	pendingActiveSessionId.value = sessionId
	trySendActiveSession()
	sessionParticipants.value = []
}
const trySendActiveSession = () => {
	const sessionId = pendingActiveSessionId.value || selectedSessionId.value
	if (!sessionId) {
		return
	}
	if (!socketConnected.value) {
		scheduleActiveRetry()
		return
	}
	const sent = sendWorkspaceCommand({ type: 'session.active', session_id: sessionId })
	if (sent) {
		pendingActiveSessionId.value = ''
		clearActiveRetry()
	}
	else {
		scheduleActiveRetry()
	}
}
const clearActiveRetry = () => {
	if (activeRetryTimer.value) {
		clearTimeout(activeRetryTimer.value)
		activeRetryTimer.value = null
	}
}
const scheduleActiveRetry = () => {
	if (activeRetryTimer.value) {
		return
	}
	activeRetryTimer.value = setTimeout(
		() => {
			activeRetryTimer.value = null
			trySendActiveSession()
		},
		500
	)
}
const seedRecordingTranscript = async() => {
	if (recordingSeeded.value || !recordingSeedId.value) {
		return
	}
	if (!socketConnected.value || !selectedSessionId.value) {
		return
	}
	try {
		const detail = await getRecording(tenantId.value, workspaceId, recordingSeedId.value)
		const text = detail?.optimized_text || detail?.transcript_text || ''
		if (!text) {
			notify('Recording transcript is not available yet.')
			return
		}
		const sent = sendWorkspaceCommand({ type: 'chat.send', session_id: selectedSessionId.value, payload: { text } })
		if (sent) {
			recordingSeeded.value = true
		}
		else {
			notify('Unable to send recording transcript.')
		}
	}
	catch (seedError) {
		notify(seedError?.message || 'Unable to load recording transcript.')
	}
}
watch(
	recordingSeedId,
	() => {
		recordingSeeded.value = false
		seedRecordingTranscript()
	}
)
watch(
	[socketConnected, selectedSessionId],
	() => {
		seedRecordingTranscript()
	}
)
watch(
	selectedSessionId,
	() => {
		workerProgressByToolCall.value = {}
		selectedWorkerToolCallId.value = ''
		workerPaneOpen.value = false
	}
)
const detachSession = async(sessionId) => {
	if (!sessionId) {
		return
	}
	const session = sessions.value.find((item) => item.id === sessionId)
	if (!isActiveSession(session)) {
		return
	}
	stopTrace(sessionId, true)
	sendWorkspaceCommand({ type: 'session.inactive', session_id: sessionId })
}
const forceDetachSession = (sessionId) => {
	if (!sessionId) {
		return
	}
	if (didDetach.value) {
		return
	}
	didDetach.value = true
	sendWorkspaceCommand({ type: 'session.inactive', session_id: sessionId })
}
const isLocked = computed(() => {
	const session = selectedSession.value
	if (!session) {
		return false
	}
	return !!session.locked
})
const isDisconnected = computed(() => socketInitialized.value && !socketConnected.value)
const isReadOnly = computed(() => isArchived.value)
const isCurrentEpoch = computed(() => selectedEpoch.value === 'current')
const canSendMessages = computed(() => !isReadOnly.value && isCurrentEpoch.value)
const isThinking = computed(() => isLocked.value && !isDisconnected.value)
const isAborting = computed(() => abortingSessions.value[selectedSessionId.value])
const isActiveSessionState = computed(() => selectedSession.value?.status === 'ACTIVE')
const canPruneSession = computed(() => !!selectedSession.value && !isArchived.value)
const queuedMessages = computed(() => queuedMessagesBySession.value[selectedSessionId.value] || [])
const activeTypers = computed(() => {
	const sessionId = selectedSessionId.value
	if (!sessionId) {
		return []
	}
	const entries = typingBySession.value[sessionId] || {}
	const now = typingClock.value
	return Object.values(entries)
		.filter((entry) => entry && entry.typed_at && now - Date.parse(entry.typed_at) <= typingTimeoutMs)
		.sort((left, right) => Date.parse(right.typed_at) - Date.parse(left.typed_at))
})
const typingLabel = computed(() => {
	if (!activeTypers.value.length) {
		return ''
	}
	const names = activeTypers.value.map((entry) => entry.user_id === currentUserId.value ? 'You' : entry.display_name)
	if (names.length === 1) {
		return `${names[0]} ${names[0] === 'You' ? 'are' : 'is'} typing…`
	}
	if (names.length === 2) {
		return `${names[0]} and ${names[1]} are typing…`
	}
	const allButLast = names.slice(0, -1)
	const last = names[names.length - 1]
	return `${allButLast.join(', ')} and ${last} are typing…`
})
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
const pingTimer = ref(null)
const traceEvents = computed(() => traceEventsBySession.value[selectedSessionId.value] || [])
const epochIds = computed(() => {
	const ids = new Set()
	sessionEvents.value
		.forEach((event) => {
			if (typeof event?.epoch_id === 'number') {
				ids.add(event.epoch_id)
			}
		})
	return Array.from(ids).sort((a, b) => a - b)
})
const currentEpochId = computed(() => {
	const lastEvent = sessionEvents.value[sessionEvents.value.length - 1]
	if (typeof lastEvent?.epoch_id === 'number') {
		return lastEvent.epoch_id
	}
	return null
})
const selectedEpochNumber = computed(() => {
	if (selectedEpoch.value === 'current') {
		return currentEpochId.value
	}
	const value = Number(selectedEpoch.value)
	return Number.isFinite(value) && value > 0 ? value : null
})
const filteredSessionEvents = computed(() => sessionEvents.value)
const traceDropped = computed(() => traceDroppedBySession.value[selectedSessionId.value] || 0)
const traceActive = computed(() => !!traceEnabled.value[selectedSessionId.value])
const traceCount = computed(() => {
	const entries = traceEventsBySession.value[selectedSessionId.value] || []
	return entries.length
})
const formatTraceTime = (value) => {
	if (!value) {
		return ''
	}
	const date = new Date(value)
	if (Number.isNaN(date.getTime())) {
		return value
	}
	return date.toLocaleString()
}
const startTrace = (sessionId = selectedSessionId.value) => {
	if (!sessionId || isArchived.value) {
		return
	}
	traceEnabled.value = { ...traceEnabled.value, [sessionId]: true }
	traceOpen.value = false
	traceCollapsed.value = true
	sendWorkspaceCommand({ type: 'session.trace.start', session_id: sessionId })
}
const stopTrace = (sessionId = selectedSessionId.value, silent = false) => {
	if (sessionId && typeof sessionId !== 'string') {
		sessionId = selectedSessionId.value
	}
	if (!sessionId) {
		return
	}
	sendWorkspaceCommand({ type: 'session.trace.stop', session_id: sessionId })
	traceEnabled.value = { ...traceEnabled.value, [sessionId]: false }
	traceOpen.value = false
	traceCollapsed.value = false
	traceDetailsOpen.value = {}
	traceEventsBySession.value = { ...traceEventsBySession.value, [sessionId]: [] }
	traceDroppedBySession.value = { ...traceDroppedBySession.value, [sessionId]: 0 }
}
const toggleTrace = () => {
	if (isArchived.value) {
		return
	}
	if (traceActive.value) {
		stopTrace()
	}
	else {
		startTrace()
	}
}
const collapseTracePanel = () => {
	if (!traceActive.value) {
		return
	}
	traceOpen.value = false
	traceCollapsed.value = true
}
const openTracePanel = () => {
	if (!traceActive.value) {
		return
	}
	traceOpen.value = true
	traceCollapsed.value = false
}
const pingIntervalMs = 20000
const addAttachment = (file) => {
	if (!file) {
		return
	}
	const id = `${Date.now()}-${Math.random().toString(16).slice(2)}`
	const entry = {
		id,
		name: file.name,
		type: file.type,
		size: file.size,
		preview: file.type.startsWith('image/') ? URL.createObjectURL(file) : '',
		data: null,
		processing: true,
		error: ''
	}
	attachments.value = [...attachments.value, entry]
	updateComposerTyping(true)
	processAttachment(file, entry)
}
const processAttachment = async(file, entry) => {
	try {
		const dataUrl = file.type.startsWith('image/') ? await resizeImage(file) : await readFileAsDataUrl(file)
		entry.data = dataUrl
	}
	catch (error) {
		entry.error = error?.message || 'Unable to process image.'
	}
	finally {
		entry.processing = false
		attachments.value = [...attachments.value]
	}
}
const resizeImage = (file) => new Promise((resolve, reject) => {
	const reader = new FileReader()
	reader.onerror = () => reject(new Error('Unable to read image.'))
	reader.onload = () => {
		const img = new Image()
		img.onerror = () => reject(new Error('Unable to decode image.'))
		img.onload = () => {
			const maxSize = 1024
			const ratio = Math.min(maxSize / img.width, maxSize / img.height, 1)
			const width = Math.round(img.width * ratio)
			const height = Math.round(img.height * ratio)
			const canvas = document.createElement('canvas')
			canvas.width = width
			canvas.height = height
			const ctx = canvas.getContext('2d')
			if (!ctx) {
				reject(new Error('Unable to process image.'))
				return
			}
			ctx.drawImage(img, 0, 0, width, height)
			const dataUrl = canvas.toDataURL('image/jpeg', 0.85)
			resolve(dataUrl)
		}
		img.src = typeof reader.result === 'string' ? reader.result : ''
	}
	reader.readAsDataURL(file)
})
const readFileAsDataUrl = (file) => new Promise((resolve, reject) => {
	const reader = new FileReader()
	reader.onerror = () => reject(new Error('Unable to read file.'))
	reader.onload = () => {
		if (typeof reader.result === 'string') {
			resolve(reader.result)
		}
		else {
			reject(new Error('Unable to read file.'))
		}
	}
	reader.readAsDataURL(file)
})
const removeAttachment = (id) => {
	const entry = attachments.value.find((item) => item.id === id)
	if (entry?.preview) {
		URL.revokeObjectURL(entry.preview)
	}
	attachments.value = attachments.value.filter((item) => item.id !== id)
	if (!messageInput.value.trim() && attachments.value.length === 0) {
		updateComposerTyping(false)
	}
	if (attachments.value.length === 0) {
		attachmentsPopupOpen.value = false
	}
}
const clearAttachments = () => {
	attachments.value.forEach((entry) => {
			if (entry?.preview) {
				URL.revokeObjectURL(entry.preview)
			}
		})
	attachments.value = []
	updateComposerTyping(false)
	attachmentsPopupOpen.value = false
}
const downloadAttachment = (entry) => {
	const url = attachmentUrl(entry)
	if (!url) {
		return
	}
	const key = attachmentKey(entry)
	downloadStateByKey.value = { ...downloadStateByKey.value, [key]: { status: 'downloading', progress: 0, determinate: false } }
	fetchAttachmentBlob(
		url,
		(progress) => {
			if (!progress) {
				return
			}
			downloadStateByKey.value = {
				...downloadStateByKey.value,
				[key]: { status: 'downloading', progress: progress.percent, determinate: progress.determinate }
			}
		}
	)
		.then((blob) => {
			const blobUrl = URL.createObjectURL(blob)
			const link = document.createElement('a')
			link.href = blobUrl
			link.download = entry?.name || 'attachment'
			document.body.appendChild(link)
			link.click()
			document.body.removeChild(link)
			setTimeout(() => URL.revokeObjectURL(blobUrl), 4000)
			const next = { ...downloadStateByKey.value }
			delete next[key]
			downloadStateByKey.value = next
		})
		.catch(() => {
			const next = { ...downloadStateByKey.value }
			delete next[key]
			downloadStateByKey.value = next
			const link = document.createElement('a')
			link.href = url
			link.target = '_blank'
			link.rel = 'noopener'
			document.body.appendChild(link)
			link.click()
			document.body.removeChild(link)
		})
}
const attachmentKey = (attachment) => {
	if (!attachment) {
		return ''
	}
	return [
		attachment.blobHash,
		attachment.publicUrl,
		attachment.userUrl,
		attachment.data ? attachment.data.slice(0, 64) : '',
		attachment.name,
		attachment.size,
	].filter(Boolean)
		.join('|')
}
const downloadStateFor = (attachment) => {
	const key = attachmentKey(attachment)
	if (!key) {
		return null
	}
	return downloadStateByKey.value[key] || null
}
const fetchAttachmentBlob = (url, onProgress) => new Promise((resolve, reject) => {
	const request = new XMLHttpRequest()
	request.open('GET', url, true)
	request.responseType = 'blob'
	request.onprogress = (event) => {
		if (!event.lengthComputable) {
			onProgress?.({ determinate: false, percent: 0 })
			return
		}
		const percent = event.total > 0 ? Math.round((event.loaded / event.total) * 100) : 0
		onProgress?.({ determinate: true, percent })
	}
	request.onload = () => {
		if (request.status >= 200 && request.status < 300 && request.response) {
			resolve(request.response)
			return
		}
		reject(new Error('download_failed'))
	}
	request.onerror = () => reject(new Error('download_failed'))
	request.send()
})
const handlePaste = (event) => {
	const items = event.clipboardData?.items || []
	for (const item of items) {
		if (item.kind === 'file') {
			const file = item.getAsFile()
			if (file) {
				addAttachment(file)
			}
		}
	}
}
const handleFileInput = (event) => {
	const files = Array.from(event.target.files || [])
	files.forEach(addAttachment)
	event.target.value = ''
}
const openAttachmentPicker = () => {
	if (!canSendMessages.value) {
		return
	}
	composerFileInputRef.value?.click()
}
const updateComposerTyping = (active) => {
	if (active) {
		typingActive.value = true
		lastTypingSentAt.value = Date.now()
		sendTypingStatus(true)
		return
	}
	if (typingActive.value) {
		typingActive.value = false
		sendTypingStatus(false)
	}
}
const handleDropEnter = (event) => {
	event.preventDefault()
	if (!canSendMessages.value) {
		return
	}
	dropActive.value = true
}
const handleDropOver = (event) => {
	event.preventDefault()
	if (!canSendMessages.value) {
		return
	}
	if (event.dataTransfer) {
		event.dataTransfer.dropEffect = 'copy'
	}
	dropActive.value = true
}
const handleDropLeave = (event) => {
	if (!canSendMessages.value) {
		return
	}
	const current = event.currentTarget
	if (event.relatedTarget && current && current.contains(event.relatedTarget)) {
		return
	}
	dropActive.value = false
}
const handleDrop = (event) => {
	event.preventDefault()
	if (!canSendMessages.value) {
		return
	}
	dropActive.value = false
	const files = Array.from(event.dataTransfer?.files || [])
	files.forEach(addAttachment)
}
const queueMessage = (sessionId, payload) => {
	if (!sessionId) {
		return
	}
	const hasText = !!payload?.text?.trim()
	const hasAttachments = Array.isArray(payload?.attachments) && payload.attachments.length > 0
	if (!hasText && !hasAttachments) {
		return
	}
	const next = queuedMessagesBySession.value[sessionId] ? [...queuedMessagesBySession.value[sessionId]] : []
	next.push({ ...payload, queued_at: new Date().toISOString() })
	queuedMessagesBySession.value = { ...queuedMessagesBySession.value, [sessionId]: next }
}
const undoQueuedMessage = (index) => {
	const sessionId = selectedSessionId.value
	if (!sessionId) {
		return
	}
	const list = queuedMessagesBySession.value[sessionId] || []
	const entry = list[index]
	if (!entry) {
		return
	}
	const next = [...list]
	next.splice(index, 1)
	queuedMessagesBySession.value = { ...queuedMessagesBySession.value, [sessionId]: next }
	messageInput.value = entry.text
	attachments.value = Array.isArray(entry.attachments) ? entry.attachments : []
}
const flushQueuedMessages = (sessionId) => {
	const session = sessions.value.find((item) => item.id === sessionId)
	const isArchivedSession = session ? !activeStatuses.includes(session.status) : false
	const locked = session ? session.locked : sessionId === selectedSessionId.value && isLocked.value
	if (locked || isDisconnected.value || isArchivedSession) {
		return
	}
	const payload = flushQueuedMessage(
		queuedMessagesBySession,
		dequeuePendingBySession,
		sessionId,
		sendWorkspaceCommand
	)
	if (!payload) {
		return
	}
	lastSentPayloadBySession.value = { ...lastSentPayloadBySession.value, [sessionId]: payload }
	if (payload.text) {
		pendingScrollText.value = payload.text
		autoScrollActive.value = true
		autoScrollTargetId.value = ''
	}
}
const setLocalTyping = (typing) => {
	if (!selectedSessionId.value || !currentUserId.value) {
		return
	}
	const sessionId = selectedSessionId.value
	const userId = currentUserId.value
	const entries = typingBySession.value[sessionId] || {}
	if (!typing) {
		if (!entries[userId]) {
			return
		}
		const next = { ...entries }
		delete next[userId]
		typingBySession.value = { ...typingBySession.value, [sessionId]: next }
		return
	}
	const entry = { user_id: userId, display_name: 'You', typed_at: new Date().toISOString() }
	typingBySession.value = { ...typingBySession.value, [sessionId]: { ...entries, [userId]: entry } }
}
const sendTypingStatus = (typing) => {
	if (!selectedSessionId.value) {
		return
	}
	setLocalTyping(typing)
	sendWorkspaceCommand({ type: 'session.typing', session_id: selectedSessionId.value, payload: { typing } })
}
const cancelAutoSend = () => {
	allowAutoSend.value = false
	if (sendTimer.value) {
		clearTimeout(sendTimer.value)
		sendTimer.value = null
	}
	if (sendProgressFrame) {
		cancelAnimationFrame(sendProgressFrame)
		sendProgressFrame = null
	}
	sendProgress.value = 0
}
const scheduleAutoSend = () => {
	if (!autoSendEnabled.value || !allowAutoSend.value) {
		return
	}
	if (sendTimer.value) {
		clearTimeout(sendTimer.value)
	}
	if (sendProgressFrame) {
		cancelAnimationFrame(sendProgressFrame)
		sendProgressFrame = null
	}
	sendProgress.value = 0
	const startTime = Date.now()
	sendTimer.value = setTimeout(
		() => {
			sendTimer.value = null
			sendProgress.value = 0
			handleSendButton()
		},
		autoSendDelay.value
	)
	const updateProgress = () => {
		if (!sendTimer.value) {
			sendProgress.value = 0
			return
		}
		const elapsed = Date.now() - startTime
		sendProgress.value = Math.min(1, elapsed / autoSendDelay.value)
		sendProgressFrame = requestAnimationFrame(updateProgress)
	}
	sendProgressFrame = requestAnimationFrame(updateProgress)
}
const encodeVoiceWav = (audio, sampleRate = 16000) => {
	const pcm = new Int16Array(audio.length)
	for (let i = 0; i < audio.length; i += 1) {
		const clamped = Math.max(-1, Math.min(1, audio[i] || 0))
		pcm[i] = clamped < 0 ? Math.round(clamped * 32768) : Math.round(clamped * 32767)
	}
	const buffer = new ArrayBuffer(44 + pcm.length * 2)
	const view = new DataView(buffer)
	const writeText = (offset, text) => {
		for (let i = 0; i < text.length; i += 1) {
			view.setUint8(offset + i, text.charCodeAt(i))
		}
	}
	writeText(0, 'RIFF')
	view.setUint32(4, 36 + pcm.length * 2, true)
	writeText(8, 'WAVE')
	writeText(12, 'fmt ')
	view.setUint32(16, 16, true)
	view.setUint16(20, 1, true)
	view.setUint16(22, 1, true)
	view.setUint32(24, sampleRate, true)
	view.setUint32(28, sampleRate * 2, true)
	view.setUint16(32, 2, true)
	view.setUint16(34, 16, true)
	writeText(36, 'data')
	view.setUint32(40, pcm.length * 2, true)
	for (let i = 0; i < pcm.length; i += 1) {
		view.setInt16(44 + i * 2, pcm[i], true)
	}
	return new Blob([buffer], { type: 'audio/wav' })
}
const applyVoiceTranscript = (text) => {
	if (!text) {
		return
	}
	if (!tryHandleVoiceCommand(text)) {
		messageInput.value = `${messageInput.value}${messageInput.value ? ' ' : ''}${text}`
		nextTick(() => {
			composerInputRef.value?.focus()
		})
		if (!isSpeaking.value) {
			allowAutoSend.value = true
			scheduleAutoSend()
		}
	}
}
const queueVoiceAudio = (audio) => {
	cancelAutoSend()
	allowAutoSend.value = true
	if (!selectedSessionId.value || !audio || !tenantId.value) {
		return
	}
	const sessionId = selectedSessionId.value
	const tenant = tenantId.value
	const wav = encodeVoiceWav(audio, 16000)
	triggerVoiceFlyout()
	void uploadSessionVoiceRecording(tenant, workspaceId, sessionId, wav, wav.type)
		.then((response) => {
			if (selectedSessionId.value !== sessionId) {
				return
			}
			applyVoiceTranscript(response?.text || '')
		})
		.catch((error) => {
			console.error('Voice upload failed', error)
			notify('Voice upload failed.')
		})
}
const toggleVoice = () => {
	voiceEnabled.value = !voiceEnabled.value
	setWorkspaceSetting(workspaceId, 'polymr.voice.enabled', voiceEnabled.value ? 'true' : 'false')
}
const toggleTts = () => {
	ttsEnabled.value = !ttsEnabled.value
	setWorkspaceSetting(workspaceId, 'polymr.tts.enabled', ttsEnabled.value ? 'true' : 'false')
	setTtsEnabled(ttsEnabled.value)
	if (!ttsEnabled.value) {
		stopTts()
	}
}
const { isSpeaking, start: startVAD, stop: stopVAD } = useVAD({ onSpeechEnd: queueVoiceAudio, onSpeechStart: cancelAutoSend })
const shouldRecord = computed(() => voiceEnabled.value
	&& socketConnected.value
	&& canSendMessages.value
	&& !(ttsPauseVad.value && ttsIsPlaying.value))
watch(
	shouldRecord,
	(enabled) => {
		if (enabled) {
			startVAD()
		}
		else {
			stopVAD()
		}
	}
)
watch(
	isSpeaking,
	(speaking) => {
		if (speaking) {
			cancelAutoSend()
		}
	}
)
watch(
	ttsEnabled,
	(enabled) => {
		setTtsEnabled(enabled)
		if (!enabled) {
			stopTts()
		}
	}
)
watch(
	ttsPauseVad,
	(value) => {
		setWorkspaceSetting(workspaceId, 'polymr.tts.pause_vad', value ? 'true' : 'false')
	}
)
onTtsPlayStart(() => {
	ttsIsPlaying.value = true
})
onTtsPlayEnd(() => {
	ttsIsPlaying.value = false
})
const handleTtsVoiceChange = (event) => {
	if (event?.detail?.workspaceId !== workspaceId) {
		return
	}
	resetTtsSession()
}
const getSpeakState = (sessionId) => {
	if (!sessionId) {
		return null
	}
	if (!speakStates.value[sessionId]) {
		speakStates.value = {
			...speakStates.value,
			[sessionId]: {
				visible: '',
				inSpeak: false,
				speakBuffer: '',
				inThink: false,
				thinkBuffer: '',
				inCodeBlock: false,
				inInlineCode: false,
				pending: ''
			}
		}
	}
	return speakStates.value[sessionId]
}
const resetSpeakState = (sessionId) => {
	if (!sessionId) {
		return
	}
	const next = { ...speakStates.value }
	delete next[sessionId]
	speakStates.value = next
}
const getThinkText = (sessionId) => thinkingMessages.value[sessionId] || ''
const resetThinkState = (sessionId) => {
	if (!sessionId) {
		return
	}
	const next = { ...thinkingMessages.value }
	delete next[sessionId]
	thinkingMessages.value = next
}
const normalizeSpeakBlock = (text) => {
	if (!text) {
		return ''
	}
	return String(text).replace(/\s+/g, ' ').trim()
}
const shouldEnqueueSpeakBlock = (sessionId, text) => {
	const key = normalizeSpeakBlock(text)
	if (!key) {
		return false
	}
	const now = Date.now()
	const history = speakHistory.value[sessionId] || []
	const recentWindowMs = 120000
	const pruned = history.filter((entry) => now - entry.at < recentWindowMs)
	const duplicate = pruned.some((entry) => entry.key === key)
	const next = duplicate ? pruned : [...pruned, { key, at: now }]
	speakHistory.value = { ...speakHistory.value, [sessionId]: next.slice(-10) }
	return !duplicate
}
const processSpeakToken = (sessionId, token) => {
	const state = getSpeakState(sessionId)
	if (!state || !token) {
		return ''
	}
	state.pending += String(token)
	const text = state.pending
	const matchAt = (idx, value) => text.startsWith(value, idx)
	const isPrefix = (idx, value) => value.startsWith(text.slice(idx))
	let i = 0
	while (i < text.length) {
		if (!state.inInlineCode && matchAt(i, '```')) {
			state.inCodeBlock = !state.inCodeBlock
			state.visible += '```'
			i += 3
			continue
		}
		if (!state.inInlineCode && i + 1 >= text.length && isPrefix(i, '```')) {
			break
		}
		if (!state.inCodeBlock && matchAt(i, '`')) {
			state.inInlineCode = !state.inInlineCode
			state.visible += '`'
			i += 1
			continue
		}
		if (!state.inCodeBlock && !state.inInlineCode) {
			if (!state.inSpeak && matchAt(i, '<speak>')) {
				state.inSpeak = true
				i += 7
				continue
			}
			if (!state.inSpeak && i + 1 < text.length && '<speak>'.startsWith(text.slice(i))) {
				break
			}
			if (state.inSpeak && matchAt(i, '</speak>')) {
				state.inSpeak = false
				i += 8
				if (ttsEnabled.value && state.speakBuffer && shouldEnqueueSpeakBlock(sessionId, state.speakBuffer)) {
					console.log('[tts] speak block:', state.speakBuffer)
					enqueueTts(state.speakBuffer)
				}
				state.speakBuffer = ''
				continue
			}
			if (state.inSpeak && i + 1 < text.length && '</speak>'.startsWith(text.slice(i))) {
				break
			}
			if (!state.inThink && matchAt(i, '<scratchpad>')) {
				state.inThink = true
				i += 12
				continue
			}
			if (!state.inThink && matchAt(i, '<thinking>')) {
				state.inThink = true
				i += 10
				continue
			}
			if (!state.inThink && matchAt(i, '<thought>')) {
				state.inThink = true
				i += 9
				continue
			}
			if (!state.inThink && matchAt(i, '<think>')) {
				state.inThink = true
				i += 7
				continue
			}
			if (!state.inThink && i + 1 < text.length && '<scratchpad>'.startsWith(text.slice(i))) {
				break
			}
			if (!state.inThink && i + 1 < text.length && '<thinking>'.startsWith(text.slice(i))) {
				break
			}
			if (!state.inThink && i + 1 < text.length && '<thought>'.startsWith(text.slice(i))) {
				break
			}
			if (!state.inThink && i + 1 < text.length && '<think>'.startsWith(text.slice(i))) {
				break
			}
			if (state.inThink && matchAt(i, '</scratchpad>')) {
				state.inThink = false
				i += 13
				continue
			}
			if (state.inThink && matchAt(i, '</thinking>')) {
				state.inThink = false
				i += 11
				continue
			}
			if (state.inThink && matchAt(i, '</thought>')) {
				state.inThink = false
				i += 10
				continue
			}
			if (state.inThink && matchAt(i, '</think>')) {
				state.inThink = false
				i += 8
				continue
			}
			if (state.inThink && i + 1 < text.length && '</scratchpad>'.startsWith(text.slice(i))) {
				break
			}
			if (state.inThink && i + 1 < text.length && '</thinking>'.startsWith(text.slice(i))) {
				break
			}
			if (state.inThink && i + 1 < text.length && '</thought>'.startsWith(text.slice(i))) {
				break
			}
			if (state.inThink && i + 1 < text.length && '</think>'.startsWith(text.slice(i))) {
				break
			}
		}
		const ch = text[i]
		if (state.inSpeak && !state.inCodeBlock && !state.inInlineCode) {
			state.speakBuffer += ch
		}
		else if (state.inThink && !state.inCodeBlock && !state.inInlineCode) {
			state.thinkBuffer += ch
		}
		else {
			state.visible += ch
		}
		i += 1
	}
	state.pending = text.slice(i)
	if (state.thinkBuffer) {
		thinkingMessages.value = {
			...thinkingMessages.value,
			[sessionId]: (thinkingMessages.value[sessionId] || '') + state.thinkBuffer
		}
		state.thinkBuffer = ''
	}
	return state.visible
}
const triggerVoiceFlyout = () => {
	const container = sessionViewRef.value
	const button = voiceButtonRef.value
	if (!container || !button) {
		return
	}
	const containerRect = container.getBoundingClientRect()
	const buttonRect = button.getBoundingClientRect()
	const startX = buttonRect.left + buttonRect.width / 2 - containerRect.left
	const startY = buttonRect.top + buttonRect.height / 2 - containerRect.top
	const endX = containerRect.width - 40
	const endY = containerRect.height / 2
	const dx = endX - startX
	const dy = endY - startY
	const midX = dx * 0.6
	const midY = dy * 0.4 - 120
	const flyout = document.createElement('span')
	flyout.className = 'voice-flyout'
	flyout.style.left = `${startX}px`
	flyout.style.top = `${startY}px`
	flyout.style.setProperty('--flyout-dx', `${dx}px`)
	flyout.style.setProperty('--flyout-dy', `${dy}px`)
	flyout.style.setProperty('--flyout-mx', `${midX}px`)
	flyout.style.setProperty('--flyout-my', `${midY}px`)
	flyout.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">'
		+ '<path fill="currentColor" d="M20 4H4a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V6a2 2 0 0 '
		+ '0-2-2zm0 4-8 5-8-5V6l8 5 8-5v2z" />'
		+ '</svg>'
	flyout.addEventListener(
		'animationend',
		() => {
			flyout.remove()
		}
	)
	container.appendChild(flyout)
}
const triggerVoiceCommandFeedback = (command) => {
	const container = sessionViewRef.value
	const button = voiceButtonRef.value
	if (!container || !button) {
		return
	}
	const containerRect = container.getBoundingClientRect()
	const buttonRect = button.getBoundingClientRect()
	const centerX = buttonRect.left + buttonRect.width / 2 - containerRect.left
	const centerY = buttonRect.top - containerRect.top - 18
	const badge = document.createElement('span')
	badge.className = `voice-command-feedback ${command}`
	badge.style.left = `${centerX}px`
	badge.style.top = `${centerY}px`
	badge.style.setProperty('--command-dx', '120px')
	badge.style.setProperty('--command-dy', '-220px')
	badge.style.setProperty('--command-mx', '48px')
	badge.style.setProperty('--command-my', '-150px')
	const iconSvgOpen = '<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false" '
		+ 'style="display:block;width:20px;height:20px;min-width:20px;max-width:20px;min-height:20p'
		+ 'x;max-height:20px;flex:none;">'
	if (command === 'allow') {
		badge.innerHTML = iconSvgOpen
			+ '<path fill="currentColor" d="M2 '
			+ '21h4V9H2v12zm20-11c0-1.1-.9-2-2-2h-6.31l.95-4.57.03-.32c0-.41-.17-.79-.44-1.06L13.17 '
			+ '1 6.59 7.59C6.22 7.95 6 8.45 6 9v10c0 1.1.9 2 2 2h9c.83 0 1.54-.5 '
			+ '1.84-1.22l3.02-7.05c.09-.23.14-.47.14-.73v-2z" />'
			+ '</svg>'
	}
	else if (command === 'deny') {
		badge.innerHTML = iconSvgOpen
			+ '<path fill="currentColor" d="M22 3h-4v12h4V3zM2 14c0 1.1.9 2 2 2h6.31l-.95 4.57-.03.32c0 '
			+ '.41.17.79.44 1.06L10.83 23l6.58-6.59c.37-.36.59-.86.59-1.41V5c0-1.1-.9-2-2-2H7c-.83 '
			+ '0-1.54.5-1.84 1.22L2.14 11.27c-.09.23-.14.47-.14.73v2z" />'
			+ '</svg>'
	}
	else if (command === 'abort') {
		badge.innerHTML = iconSvgOpen
			+ '<path fill="currentColor" d="M7.86 2h8.28L22 7.86v8.28L16.14 22H7.86L2 16.14V7.86L7.86 '
			+ '2zM8 8v8h8V8H8z" />'
			+ '</svg>'
	}
	else {
		return
	}
	const removeBadge = () => {
		if (badge.parentNode) {
			badge.remove()
		}
	}
	badge.addEventListener('animationend', removeBadge, { once: true })
	window.setTimeout(removeBadge, 900)
	container.appendChild(badge)
}
const handleGlobalKeydown = (event) => {
	if (event.altKey && event.key.toLowerCase() === 'r') {
		event.preventDefault()
		toggleVoice()
	}
	if (event.altKey && event.key.toLowerCase() === 'v') {
		event.preventDefault()
		ttsEnabled.value = !ttsEnabled.value
		setWorkspaceSetting(workspaceId, 'polymr.tts.enabled', ttsEnabled.value ? 'true' : 'false')
		setTtsEnabled(ttsEnabled.value)
		if (!ttsEnabled.value) {
			stopTts()
		}
	}
}
const handleTypingInput = () => {
	cancelAutoSend()
	const hasText = !!messageInput.value.trim()
	if (!hasText) {
		if (typingActive.value) {
			typingActive.value = false
			sendTypingStatus(false)
		}
		return
	}
	const now = Date.now()
	if (!typingActive.value || now - lastTypingSentAt.value >= typingHeartbeatMs) {
		typingActive.value = true
		lastTypingSentAt.value = now
		sendTypingStatus(true)
	}
}
const handleTypingBlur = () => {
	if (typingActive.value) {
		typingActive.value = false
		sendTypingStatus(false)
	}
}
const pruneTyping = () => {
	const now = Date.now()
	const sessions = typingBySession.value
	const nextSessions = { ...sessions }
	Object.entries(sessions)
		.forEach(([sessionId, entries]) => {
			const nextEntries = { ...entries }
			Object.entries(entries || {})
				.forEach(([userId, entry]) => {
					const typedAt = entry?.typed_at ? Date.parse(entry.typed_at) : NaN
					if (!typedAt || Number.isNaN(typedAt) || now - typedAt > typingTimeoutMs) {
						delete nextEntries[userId]
					}
				})
			if (Object.keys(nextEntries).length === 0) {
				delete nextSessions[sessionId]
			}
			else {
				nextSessions[sessionId] = nextEntries
			}
		})
	typingBySession.value = nextSessions
}
const handleKeydown = (event) => {
	if (event.key === 'Escape') {
		cancelAutoSend()
		if (handleEscape(event)) {
			return
		}
	}
	if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
		event.preventDefault()
		sendMessage()
	}
}
const handleEscape = (event) => {
	if (queuedMessages.value.length) {
		event.preventDefault()
		undoQueuedMessage(0)
		return true
	}
	if (isLocked.value) {
		event.preventDefault()
		abortSession()
		return true
	}
	if (ttsEnabled.value) {
		stopTts()
	}
	return false
}
const attachLocationMeta = (payload) => {
	if (!payload || !isLocationTrackingEnabled()) {
		return
	}
	const location = getLastLocation()
	if (location && Number.isFinite(location.lat) && Number.isFinite(location.lng)) {
		payload.meta = { ...(payload.meta || {}), location: { lat: location.lat, lng: location.lng } }
	}
}
const sendMessage = () => {
	if (!selectedSessionId.value) {
		return
	}
	cancelAutoSend()
	if (!canSendMessages.value) {
		return
	}
	if (isDisconnected.value) {
		notify('Unable to send. Workspace socket is not connected.')
		return
	}
	if (attachments.value.some((entry) => entry.processing)) {
		notify('Please wait for attachments to finish processing.')
		return
	}
	const failed = attachments.value.find((entry) => entry.error)
	if (failed) {
		notify(failed.error || 'Attachment failed to process.')
		return
	}
	const payload = {
		text: messageInput.value.trim(),
		attachments: attachments.value.map((entry) => ({
			name: entry.name,
			type: entry.type,
			size: entry.size,
			data: entry.data
		}))
	}
	attachLocationMeta(payload)
	const hasText = !!payload.text
	const hasAttachments = payload.attachments.length > 0
	if (!hasText && !hasAttachments) {
		return
	}
	if (typingActive.value) {
		typingActive.value = false
		sendTypingStatus(false)
	}
	if (isLocked.value) {
		queueMessage(selectedSessionId.value, payload)
		messageInput.value = ''
		attachments.value = []
		attachmentsPopupOpen.value = false
		sendTypingStatus(false)
		return
	}
	if (hasText) {
		pendingScrollText.value = payload.text
		autoScrollActive.value = true
		autoScrollTargetId.value = ''
	}
	const sent = sendWorkspaceCommand({ type: 'chat.send', session_id: selectedSessionId.value, payload })
	if (!sent) {
		notify('Unable to send. Workspace socket is not connected.')
		return
	}
	lastSentPayloadBySession.value = { ...lastSentPayloadBySession.value, [selectedSessionId.value]: payload }
	messageInput.value = ''
	attachments.value = []
	attachmentsPopupOpen.value = false
	sendTypingStatus(false)
}
const handleSendButton = () => {
	if (queuedMessages.value.length) {
		undoQueuedMessage(0)
		return
	}
	sendMessage()
}
const abortSession = () => {
	if (!selectedSessionId.value || !canSendMessages.value) {
		return
	}
	sendWorkspaceCommand({ type: 'session.abort', session_id: selectedSessionId.value })
}
const updateSessionAssistant = async() => {
	if (!selectedSessionId.value || !sessionAssistantSelection.value || !canSendMessages.value) {
		return
	}
	try {
		const updated = await updateSession(
			tenantId.value,
			workspaceId,
			selectedSessionId.value,
			{ assistant_id: sessionAssistantSelection.value }
		)
		sessions.value = sessions.value.map((item) => (item.id === updated.id ? { ...item, ...updated } : item))
		if (selectedSessionSummary.value?.id === updated.id) {
			selectedSessionSummary.value = { ...selectedSessionSummary.value, ...updated }
		}
		if (sessionAssistantSelection.value) {
			localStorage.setItem(lastAssistantKey.value, sessionAssistantSelection.value)
		}
		notify('Assistant updated.')
	}
	catch (error) {
		notify(error?.message || 'Unable to update assistant.')
	}
}
const requestDeleteSession = (session) => {
	sessionToDelete.value = session
	deleteConfirmOpen.value = true
}
const confirmDeleteSession = async() => {
	if (!sessionToDelete.value) {
		return
	}
	const session = sessionToDelete.value
	try {
		await deleteSession(tenantId.value, workspaceId, session.id)
	}
	catch (error) {
		if (error?.status === 409) {
			notify('Session is locked. Cancel or wait for it to finish.', 'danger')
			return
		}
		notify(error?.message || 'Unable to delete session.', 'danger')
		return
	}
	sessionToDelete.value = null
	deleteConfirmOpen.value = false
	if (selectedSessionId.value === session.id) {
		handleSessionExit(session.id, 'Session deleted.')
		return
	}
	notify('Session deleted.')
}
const cancelDeleteSession = () => {
	sessionToDelete.value = null
	deleteConfirmOpen.value = false
}
const archiveCurrentSession = async() => {
	if (!selectedSessionId.value || isArchived.value) {
		return
	}
	try {
		await archiveSession(tenantId.value, workspaceId, selectedSessionId.value)
	}
	catch (error) {
		if (error?.status === 409) {
			notify('Session is locked. Cancel or wait for it to finish.', 'danger')
			return
		}
		notify(error?.message || 'Unable to archive session.', 'danger')
		return
	}
	notify('Session archived.')
	await loadSessionSummary(selectedSessionId.value)
}
const confirmCloseSession = async() => {
	closeConfirmOpen.value = false
	await archiveCurrentSession()
}
const renameSession = async() => {
	if (!selectedSessionId.value) {
		return
	}
	const next = renameValue.value
	try {
		const updated = await updateSession(tenantId.value, workspaceId, selectedSessionId.value, { title: next })
		sessions.value = sessions.value.map((item) => (item.id === updated.id ? { ...item, ...updated } : item))
		if (selectedSessionSummary.value?.id === updated.id) {
			selectedSessionSummary.value = { ...selectedSessionSummary.value, ...updated }
		}
		notify('Session renamed.')
		renameOpen.value = false
	}
	catch (error) {
		notify(error?.message || 'Unable to rename session.')
	}
}
const openRename = () => {
	renameValue.value = selectedSession.value?.title || ''
	renameOpen.value = true
}
const openTechnicalDetails = async() => {
	if (!selectedSessionId.value) {
		return
	}
	techDetailsOpen.value = true
	await loadTechnicalDetails()
}
const openPromptPreview = async() => {
	if (!selectedSessionId.value) {
		return
	}
	promptOpen.value = true
	await loadPromptPreview()
}
const startPruneSession = async() => {
	if (!selectedSessionId.value || !canPruneSession.value || pruningSession.value) {
		return
	}
	pruningSession.value = true
	try {
		const result = await pruneSession(tenantId.value, workspaceId, selectedSessionId.value)
		notifySessionPruned(result)
		await loadSessionSummary(selectedSessionId.value)
	}
	catch (error) {
		notify(error?.message || 'Unable to prune session.', 'danger')
	}
	finally {
		pruningSession.value = false
	}
}
const closePromptPreview = () => {
	promptOpen.value = false
	promptError.value = ''
}
const closeTechnicalDetails = () => {
	techDetailsOpen.value = false
	techDetailsError.value = ''
	expandedToolServers.value = new Set()
	techServerSelection.value = ''
}
const loadTechnicalDetails = async() => {
	if (!selectedSessionId.value) {
		return
	}
	techDetailsLoading.value = true
	techDetailsError.value = ''
	try {
		techDetails.value = await getSessionTechnicalDetails(tenantId.value, workspaceId, selectedSessionId.value)
		await loadTechServerOptions()
	}
	catch (error) {
		techDetailsError.value = error?.message || 'Unable to load technical details.'
		techDetails.value = null
	}
	finally {
		techDetailsLoading.value = false
	}
}
const loadTechServerOptions = async() => {
	if (!tenantId.value || !workspaceId) {
		return
	}
	techServerLoading.value = true
	try {
		const servers = await getMcpServers(tenantId.value, workspaceId)
		const included = new Set((techDetails.value?.mcp_servers || []).map((server) => server.id))
		techServerOptions.value = (servers || []).filter((server) => !included.has(server.id))
	}
	catch (error) {
		techServerOptions.value = []
	}
	finally {
		techServerLoading.value = false
	}
}
const addTechServer = async() => {
	if (!selectedSessionId.value || !techServerSelection.value) {
		return
	}
	if (isArchived.value) {
		notify('Archived sessions are read-only.', 'danger')
		return
	}
	try {
		techDetails.value = await addSessionMcpServer(tenantId.value, workspaceId, selectedSessionId.value, { server_id: techServerSelection.value })
		techServerSelection.value = ''
		await loadTechServerOptions()
		notify('MCP server added to session.')
	}
	catch (error) {
		notify(error?.message || 'Unable to add MCP server.', 'danger')
	}
}
const loadPromptPreview = async() => {
	if (!selectedSessionId.value) {
		return
	}
	promptLoading.value = true
	promptError.value = ''
	try {
		const response = await getSessionPrompt(tenantId.value, workspaceId, selectedSessionId.value)
		promptText.value = response?.prompt_text || ''
		promptNodeLabel.value = response?.node_type || ''
	}
	catch (error) {
		promptError.value = error?.message || 'Unable to load prompt preview.'
		promptText.value = ''
	}
	finally {
		promptLoading.value = false
	}
}
const toggleToolServer = (serverId) => {
	if (!serverId) {
		return
	}
	const next = new Set(expandedToolServers.value)
	if (next.has(serverId)) {
		next.delete(serverId)
	}
	else {
		next.add(serverId)
	}
	expandedToolServers.value = next
}
const toolServerOpen = (serverId) => expandedToolServers.value.has(serverId)
const handleScopeUpdate = async({ allow, deny }) => {
	if (!selectedSessionId.value) {
		return
	}
	if (isArchived.value) {
		notify('Archived sessions are read-only.', 'danger')
		return
	}
	const nextAllow = Array.isArray(allow) ? allow : []
	const nextDeny = Array.isArray(deny) ? deny : []
	techScopeSaving.value = true
	try {
		techDetails.value = await updateSessionScopes(
			tenantId.value,
			workspaceId,
			selectedSessionId.value,
			{ allow_scopes: nextAllow, deny_scopes: nextDeny }
		)
		notify('Session scopes updated.')
	}
	catch (error) {
		notify(error?.message || 'Unable to update scopes.')
	}
	finally {
		techScopeSaving.value = false
	}
}
const noopToolCaller = async() => ({})
const openWorkerSession = (sessionId) => {
	if (!sessionId) {
		return
	}
	const href = router.resolve({ name: 'workspace-session', params: { workspaceId, sessionId } }).href
	window.open(href, '_blank', 'noopener')
}
const previewResourceBase = computed(() => {
	if (!tenantId.value || !workspaceId) {
		return ''
	}
	return `/api/tenants/${tenantId.value}/workspaces/${workspaceId}/ui-assets/{path}`
})
const mcpViewTheme = ref(mcpThemeVars())
let stopThemeObserver = null
const scrollToBottom = () => {
	if (!sessionLogRef.value) {
		return
	}
	sessionLogRef.value.scrollTop = sessionLogRef.value.scrollHeight
}
const applyLogHeight = () => {
	if (!sessionViewRef.value) {
		return
	}
	const viewHeight = sessionViewRef.value.getBoundingClientRect().height
	const headerHeight = sessionHeaderRef.value?.getBoundingClientRect().height || 0
	const styles = getComputedStyle(sessionViewRef.value)
	const gapValue = parseFloat(styles.rowGap || styles.gap || '0') || 0
	const available = Math.max(120, Math.floor(viewHeight - headerHeight - gapValue))
	if (sessionLogRef.value) {
		sessionLogRef.value.style.height = `${available}px`
		sessionLogRef.value.style.maxHeight = `${available}px`
	}
	const canvasPaneElement = canvasPaneRef.value?.rootRef || canvasPaneRef.value?.$el || null
	if (canvasPaneElement?.style) {
		canvasPaneElement.style.height = `${available}px`
		canvasPaneElement.style.maxHeight = `${available}px`
	}
}
const recalibrateLogHeight = (hide) => {
	if (!isAlive.value) {
		return
	}
	if (!sessionLogRef.value) {
		return
	}
	if (!hide) {
		applyLogHeight()
		return
	}
	const container = sessionLogRef.value
	const previousTop = container.scrollTop
	const previousBottom = container.scrollTop + container.clientHeight >= container.scrollHeight - 12
	isResizing.value = true
	container.style.display = 'none'
	if (rafId) {
		cancelAnimationFrame(rafId)
	}
	rafId = requestAnimationFrame(() => {
		if (!isAlive.value) {
			return
		}
		applyLogHeight()
		container.style.display = ''
		if (previousBottom) {
			scrollToBottom()
		}
		else {
			const maxScroll = Math.max(0, container.scrollHeight - container.clientHeight)
			container.scrollTop = Math.min(previousTop, maxScroll)
		}
		isResizing.value = false
		rafId = null
	})
}
const latestAutoScrollTargetId = () => {
	if (streamingMessages.value[selectedSessionId.value]) {
		return 'assistant-stream'
	}
	const events = displayEvents.value
	if (!events.length) {
		return ''
	}
	return events[events.length - 1]?.renderId || events[events.length - 1]?.id || ''
}
const maybeAutoScroll = () => {
	if (!autoScrollActive.value || !sessionLogRef.value) {
		return
	}
	const container = sessionLogRef.value
	const targetId = latestAutoScrollTargetId()
	autoScrollTargetId.value = targetId
	if (!targetId) {
		container.scrollTop = container.scrollHeight
		return
	}
	const target = container.querySelector(`[data-event-id="${targetId}"]`)
	if (!target) {
		container.scrollTop = container.scrollHeight
		return
	}
	const events = displayEvents.value
	const lastEvent = streamingMessages.value[selectedSessionId.value]
		? { id: 'assistant-stream', renderId: 'assistant-stream', type: 'ASSISTANT_MESSAGE' }
		: (events[events.length - 1] || null)
	const maxScroll = Math.max(0, container.scrollHeight - container.clientHeight)
	if (lastEvent?.type !== 'ASSISTANT_MESSAGE' || (lastEvent.renderId || lastEvent.id) !== targetId) {
		container.scrollTop = maxScroll
		return
	}
	const containerRect = container.getBoundingClientRect()
	const targetRect = target.getBoundingClientRect()
	const desiredTop = container.scrollTop + (targetRect.top - containerRect.top)
	const nextTop = Math.min(desiredTop, maxScroll)
	const targetBottom = container.scrollTop + (targetRect.bottom - containerRect.top)
	const tailThreshold = 12
	const targetReachedTail = targetBottom >= container.scrollHeight - tailThreshold
	if (container.scrollTop >= desiredTop && targetReachedTail) {
		autoScrollActive.value = false
		return
	}
	container.scrollTop = nextTop
}
const attachMcpViews = (forceRender = false) => {
	const container = uiContainer.value || sessionLogRef.value || sessionViewRef.value
	if (!container) {
		return
	}
	const views = Array.from(container.querySelectorAll('mcp-view')).reverse()
	views.forEach((view) => {
			view.toolCaller = noopToolCaller
			view.hostStyleVariables = mcpViewTheme.value
			if (forceRender || !view.dataset.mcpViewAttached) {
				view.render()
				view.dataset.mcpViewAttached = 'true'
			}
		})
}
const uiEntriesForEvent = () => []
const extractSpeakContent = (value) => {
	if (!value) {
		return ''
	}
	const text = String(value)
	let output = ''
	let inSpeak = false
	let inCodeBlock = false
	let inInlineCode = false
	const matchAt = (idx, token) => text.startsWith(token, idx)
	for (let i = 0; i < text.length; i += 1) {
		if (!inInlineCode && matchAt(i, '```')) {
			inCodeBlock = !inCodeBlock
			i += 2
			continue
		}
		if (!inCodeBlock && matchAt(i, '`')) {
			inInlineCode = !inInlineCode
			continue
		}
		if (!inCodeBlock && !inInlineCode) {
			if (!inSpeak && matchAt(i, '<speak>')) {
				inSpeak = true
				i += 6
				continue
			}
			if (inSpeak && matchAt(i, '</speak>')) {
				inSpeak = false
				i += 7
				continue
			}
		}
		if (inSpeak) {
			output += text[i]
		}
	}
	return output
}
const extractThinkText = (value) => {
	if (!value) {
		return ''
	}
	const text = String(value)
	let output = ''
	let inThink = false
	let inCodeBlock = false
	let inInlineCode = false
	const matchAt = (idx, token) => text.startsWith(token, idx)
	for (let i = 0; i < text.length; i += 1) {
		if (!inInlineCode && matchAt(i, '```')) {
			inCodeBlock = !inCodeBlock
			i += 2
			continue
		}
		if (!inCodeBlock && matchAt(i, '`')) {
			inInlineCode = !inInlineCode
			continue
		}
		if (!inCodeBlock && !inInlineCode) {
			if (!inThink) {
				for (const tag of THINK_OPEN_TAGS) {
					if (matchAt(i, tag)) {
						inThink = true
						i += tag.length - 1
						break
					}
				}
				if (inThink) {
					continue
				}
			}
			else {
				for (const tag of THINK_CLOSE_TAGS) {
					if (matchAt(i, tag)) {
						inThink = false
						i += tag.length - 1
						break
					}
				}
				if (!inThink) {
					continue
				}
			}
		}
		if (inThink) {
			output += text[i]
		}
	}
	return output
}
const THINK_OPEN_TAGS = ['<scratchpad>', '<thinking>', '<thought>', '  thinking']
const THINK_CLOSE_TAGS = ['</scratchpad>', '</thinking>', '</thought>', '  end_thinking']
const stripThinkTags = (value) => {
	if (!value) {
		return ''
	}
	const text = String(value)
	let output = ''
	let inThink = false
	let inCodeBlock = false
	let inInlineCode = false
	const matchAt = (idx, token) => text.startsWith(token, idx)
	for (let i = 0; i < text.length; i += 1) {
		if (!inInlineCode && matchAt(i, '```')) {
			inCodeBlock = !inCodeBlock
			output += '```'
			i += 2
			continue
		}
		if (!inCodeBlock && matchAt(i, '`')) {
			inInlineCode = !inInlineCode
			output += '`'
			continue
		}
		if (!inCodeBlock && !inInlineCode) {
			if (!inThink) {
				for (const tag of THINK_OPEN_TAGS) {
					if (matchAt(i, tag)) {
						inThink = true
						i += tag.length - 1
						break
					}
				}
				if (inThink) {
					continue
				}
			}
			else {
				for (const tag of THINK_CLOSE_TAGS) {
					if (matchAt(i, tag)) {
						inThink = false
						i += tag.length - 1
						break
					}
				}
				if (!inThink) {
					continue
				}
			}
		}
		if (!inThink) {
			output += text[i]
		}
	}
	return output
}
const stripSpeakTags = (value) => {
	if (!value) {
		return ''
	}
	const text = String(value)
	let output = ''
	let inSpeak = false
	const matchAt = (idx, token) => text.startsWith(token, idx)
	for (let i = 0; i < text.length; i += 1) {
		if (!inSpeak && matchAt(i, '<speak>')) {
			inSpeak = true
			i += 6
			continue
		}
		if (inSpeak && matchAt(i, '</speak>')) {
			inSpeak = false
			i += 7
			continue
		}
		if (!inSpeak) {
			output += text[i]
		}
	}
	const visibleText = output.trim()
	if (visibleText) {
		return output
	}
	const speakOnly = extractSpeakContent(text)
	return speakOnly.trim() ? speakOnly : visibleText
}
const contentText = (payload) => {
	if (!payload) {
		return ''
	}
	const content = payload.content || payload.contents || []
	if (typeof content === 'string') {
		return stripThinkTags(stripSpeakTags(content))
	}
	if (Array.isArray(content)) {
		const directText = content.find((entry) => entry?.type === 'text' && typeof entry?.text === 'string')
		if (directText?.text) {
			return stripThinkTags(stripSpeakTags(directText.text))
		}
		const anyText = content.find((entry) => typeof entry?.text === 'string')
		if (anyText?.text) {
			return stripThinkTags(stripSpeakTags(anyText.text))
		}
	}
	if (typeof payload.text === 'string') {
		return stripThinkTags(stripSpeakTags(payload.text))
	}
	return ''
}
const toCloneable = (value) => {
	if (value === undefined) {
		return null
	}
	if (value === null) {
		return null
	}
	if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') {
		return value
	}
	try {
		return JSON.parse(JSON.stringify(value))
	}
	catch {
		return null
	}
}
const toolResultEnvelope = (value) => {
	if (value === undefined || value === null) {
		return null
	}
	if (typeof value !== 'object') {
		return toCloneable({ result: value })
	}
	if (value.content !== undefined
			|| value.structuredContent !== undefined
			|| value._meta !== undefined
			|| value.isError !== undefined) {
		return toCloneable(value)
	}
	const result = value.result
	if (result
			&& typeof result === 'object'
			&& !Array.isArray(result)
			&& (result.content !== undefined
				|| result.structuredContent !== undefined
				|| result._meta !== undefined
				|| result.isError !== undefined)) {
		return toCloneable(result)
	}
	return toCloneable({ result: value })
}
const normalizeCspDomains = (value) => {
	if (Array.isArray(value)) {
		return value.map((entry) => (entry == null ? '' : String(entry)).trim()).filter((entry) => entry)
	}
	if (typeof value === 'string') {
		const trimmed = value.trim()
		return trimmed ? [trimmed] : []
	}
	return []
}
const cspAllowedOrigins = (csp) => {
	if (!csp || typeof csp !== 'object') {
		return []
	}
	const domains = [
		...normalizeCspDomains(csp.resource_domains || csp.resourceDomains),
		...normalizeCspDomains(csp.connect_domains || csp.connectDomains),
	]
	return Array.from(new Set(domains))
}
const cspSourceList = (domains) => {
	const entries = Array.isArray(domains) ? domains : []
	const cleaned = entries.map((value) => (value == null ? '' : String(value)).trim()).filter((value) => value)
	return Array.from(new Set(cleaned))
}
const buildCspPolicy = (csp) => {
	if (!csp || typeof csp !== 'object') {
		return ''
	}
	const resourceDomains = cspSourceList(csp.resource_domains || csp.resourceDomains)
	const connectDomains = cspSourceList(csp.connect_domains || csp.connectDomains)
	const baseSources = resourceDomains.length ? resourceDomains : []
	const connectSources = connectDomains.length ? connectDomains : baseSources
	const join = (sources, extras = []) => {
		const values = [...extras, ...sources]
		return values.length ? values.join(' ') : "'none'"
	}
	return [
		"default-src 'none'",
		`script-src ${join(baseSources, ["'unsafe-inline'"])}`,
		`style-src ${join(baseSources, ["'unsafe-inline'"])}`,
		`img-src ${join(baseSources, ['data:', 'blob:'])}`,
		`font-src ${join(baseSources, ['data:'])}`,
		`connect-src ${join(connectSources)}`,
		`media-src ${join(baseSources, ['data:', 'blob:'])}`,
		`frame-src ${join(baseSources)}`,
		"base-uri 'none'",
		"object-src 'none'",
	].join('; ')
}
const extractUiCsp = (value) => {
	if (!value) {
		return null
	}
	const meta = value._meta || value.meta || null
	if (!meta || typeof meta !== 'object') {
		return null
	}
	const ui = meta.ui
	if (!ui || typeof ui !== 'object') {
		return null
	}
	const csp = ui.csp
	if (!csp || typeof csp !== 'object') {
		return null
	}
	return csp
}
const extractAllowedOrigins = (value) => cspAllowedOrigins(extractUiCsp(value))
const decisionToolData = (request) => {
	if (!request) {
		return null
	}
	const toolName = request?.tool_name || request?.tool || request?.name
	const args = request?.arguments
	const result = decisionToolResult(request)
	const meta = result?._meta || result?.meta || null
	if (!toolName && args === undefined && !result) {
		return null
	}
	const tool = toolName || args !== undefined ? { name: toolName, arguments: args } : null
	return toCloneable({ tool, result, meta })
}
const decisionToolCsp = (request) => extractUiCsp(request) || extractUiCsp(decisionToolResult(request)) || null
const getStableMcpViewState = (cache, key, next) => {
	const previous = cache.get(key)
	if (previous
			&& previous.src === next.src
			&& previous.resourceBase === next.resourceBase
			&& previous.css === next.css
			&& previous.dataJson === next.dataJson
			&& previous.toolResultJson === next.toolResultJson
			&& previous.allowedOriginsJson === next.allowedOriginsJson
			&& previous.csp === next.csp) {
		return previous
	}
	cache.set(key, next)
	return next
}
const decisionToolViewState = (request, index = 0) => {
	const key = request?.id || request?.tool_call_id || `${decisionReviewUri(request)}:${index}`
	const data = decisionToolData(request)
	const toolResult = decisionToolResult(request)
	const allowedOrigins = cspAllowedOrigins(decisionToolCsp(request))
	const csp = buildCspPolicy(decisionToolCsp(request))
	return getStableMcpViewState(
		decisionToolViewCache,
		key,
		{
			src: decisionReviewUri(request),
			resourceBase: previewResourceBase.value,
			data,
			toolResult,
			allowedOrigins,
			csp,
			dataJson: JSON.stringify(data),
			toolResultJson: JSON.stringify(toolResult),
			allowedOriginsJson: JSON.stringify(allowedOrigins)
		}
	)
}
const decisionToolAllowedOrigins = (request, index = 0) => decisionToolViewState(request, index).allowedOrigins
const decisionToolCspPolicy = (request, index = 0) => decisionToolViewState(request, index).csp
const toolUiData = (event) => {
	if (!event) {
		return null
	}
	const input = toolInspectInput(event)
	const result = toolUiResult(event)
	const meta = result?._meta || result?.meta || null
	if (!input && !result) {
		return null
	}
	const toolName = input?.tool_name || input?.tool || input?.name
	const args = input?.arguments
	const tool = toolName || args !== undefined ? { name: toolName, arguments: args } : null
	return toCloneable({ tool, result, meta })
}
const toolUiCsp = (event) => {
	const input = toolInspectInput(event)
	return extractUiCsp(input) || extractUiCsp(toolUiResult(event)) || null
}
const toolUiViewState = (event) => {
	const key = event?.id || toolReviewUri(event)
	const data = toolUiData(event)
	const toolResult = toolUiResult(event)
	const allowedOrigins = cspAllowedOrigins(toolUiCsp(event))
	const csp = buildCspPolicy(toolUiCsp(event))
	return getStableMcpViewState(
		toolUiViewCache,
		key,
		{
			src: toolReviewUri(event),
			resourceBase: previewResourceBase.value,
			data,
			toolResult,
			allowedOrigins,
			csp,
			dataJson: JSON.stringify(data),
			toolResultJson: JSON.stringify(toolResult),
			allowedOriginsJson: JSON.stringify(allowedOrigins)
		}
	)
}
const toolUiAllowedOrigins = (event) => toolUiViewState(event).allowedOrigins
const toolUiCspPolicy = (event) => toolUiViewState(event).csp
const eventLabel = (event) => {
	const type = event?.type
	if (type === 'USER_MESSAGE') {
		return 'User'
	}
	if (type === 'CONTEXT_MESSAGE') {
		return 'Context'
	}
	if (type === 'ASSISTANT_MESSAGE') {
		return 'Assistant'
	}
	if (type === 'TOOL_CALL'
			|| type === 'TOOL_RESULT'
			|| type === 'TOOL_COMBINED'
			|| type === 'TOOL_CALL_PENDING') {
		return toolRequestSummary(event)
	}
	if (type === 'SYSTEM') {
		return 'System'
	}
	if (type === 'AUDIT') {
		return 'Audit'
	}
	if (type === 'DECISION_REQUEST') {
		return 'Approval'
	}
	if (type === 'DECISION_RESULT') {
		return 'Approval result'
	}
	if (type === 'SESSION_TAG_CHANGE') {
		return 'Tag Update'
	}
	return type || 'Event'
}
const userAvatarPalette = [
	'#1E3A5F',
	'#1B4B4F',
	'#2A4B8D',
	'#355C7D',
	'#2D5C6E',
	'#2F4C65',
	'#1F4E4A',
	'#2C3E63',
	'#254E70',
	'#294458',
	'#204A6B',
	'#1D4D57',
]
const userHashSeed = (value) => {
	if (!value) {
		return 0
	}
	let hash = 0
	for (let i = 0; i < value.length; i += 1) {
		hash = (hash << 5) - hash + value.charCodeAt(i)
		hash |= 0
	}
	return Math.abs(hash)
}
const eventUserProfile = (event) => {
	const payload = event?.payload || {}
	const userId = payload.user_id || payload.userId
	if (!userId) {
		return null
	}
	const live = Array.isArray(sessionParticipants.value) ? sessionParticipants.value : []
	const stored = Array.isArray(selectedSession.value?.participants) ? selectedSession.value.participants : []
	return live.find((person) => person.user_id === userId)
		|| stored.find((person) => person.user_id === userId)
		|| null
}
const userLabelForEvent = (event) => {
	const payload = event?.payload || {}
	const profile = eventUserProfile(event)
	return profile?.user_name || payload.display_name || payload.user_name || eventLabel(event) || 'User'
}
const userAvatarUrl = (event) => eventUserProfile(event)?.avatar_url || ''
const userAvatarInitial = (event) => {
	const label = userLabelForEvent(event)
	return label ? label.trim().charAt(0).toUpperCase() : '?'
}
const userAvatarColor = (event) => {
	const payload = event?.payload || {}
	const seed = userHashSeed(payload.user_id || payload.userId || userLabelForEvent(event))
	return userAvatarPalette[seed % userAvatarPalette.length]
}
const participantAvatarUrl = (person) => person?.avatar_url || ''
const participantInitial = (person) => {
	const label = person?.user_name || ''
	return label ? label.trim().charAt(0).toUpperCase() : '?'
}
const participantAvatarColor = (person) => {
	const seed = userHashSeed(person?.user_id || person?.user_name || '')
	return userAvatarPalette[seed % userAvatarPalette.length]
}
const isPrivateSession = computed(() => {
	const visibility = (selectedSessionSummary.value?.visibility || '').toUpperCase()
	return visibility === 'PRIVATE'
})
const currentUserParticipant = computed(() => sessionParticipants.value.some((person) => person.user_id === currentUserId.value))
const showInviteButton = computed(() => isPrivateSession.value && currentUserParticipant.value)
const loadWorkspaceUsers = async() => {
	if (!tenantId.value) {
		return
	}
	try {
		const response = await getWorkspaceUsers(tenantId.value)
		workspaceUsers.value = Array.isArray(response?.users) ? response.users : []
	}
	catch {
		workspaceUsers.value = []
	}
}
const availableInviteUsers = computed(() => {
	const participants = new Set(sessionParticipants.value.map((person) => person.user_id))
	return workspaceUsers.value.filter((user) => !participants.has(user.id))
})
const openInviteModal = async() => {
	inviteModalOpen.value = true
	inviteUserId.value = ''
	await loadWorkspaceUsers()
}
const confirmInvite = async() => {
	if (!inviteUserId.value) {
		notify('Select a user to invite.')
		return
	}
	inviteLoading.value = true
	try {
		const updated = await addSessionParticipant(tenantId.value, workspaceId, selectedSessionId.value, { user_id: inviteUserId.value })
		sessionParticipants.value = Array.isArray(updated) ? updated : sessionParticipants.value
		if (selectedSessionSummary.value && Array.isArray(updated)) {
			selectedSessionSummary.value = { ...selectedSessionSummary.value, participants: updated }
		}
		inviteModalOpen.value = false
		notify('User invited.')
	}
	catch (error) {
		notify(error?.message || 'Unable to invite user.')
	}
	finally {
		inviteLoading.value = false
	}
}
const formatTimestamp = (value) => {
	if (!value) {
		return ''
	}
	const date = new Date(value)
	if (Number.isNaN(date.getTime())) {
		return String(value)
	}
	return new Intl.DateTimeFormat(undefined, {
		month: 'short',
		day: 'numeric',
		hour: '2-digit',
		minute: '2-digit'
	}).format(date)
}
const openDetails = ref({})
const inviteModalOpen = ref(false)
const inviteUserId = ref('')
const inviteLoading = ref(false)
const toggleDetails = (eventId) => {
	if (!eventId) {
		return
	}
	openDetails.value = { ...openDetails.value, [eventId]: !openDetails.value[eventId] }
}
const reviewOpenFor = (eventId) => {
	if (!eventId) {
		return true
	}
	return reviewDetailsOpen.value[eventId] ?? true
}
const toggleReview = (eventId) => {
	if (!eventId) {
		return
	}
	reviewDetailsOpen.value = { ...reviewDetailsOpen.value, [eventId]: !reviewOpenFor(eventId) }
}
const copyEventPayload = async(event) => {
	try {
		const text = eventText(event)
		if (text) {
			await navigator.clipboard.writeText(text)
			notify('Event copied.')
			return
		}
		const payload = event?.type === 'TOOL_COMBINED'
			? { tool_call: event.callEvent?.payload || {}, tool_result: event.resultEvent?.payload || {} }
			: event?.payload
		if (!payload) {
			return
		}
		await navigator.clipboard.writeText(JSON.stringify(payload, null, 2))
		notify('Event copied.')
	}
	catch {
		notify('Unable to copy event.')
	}
}
const copyPromptText = async() => {
	if (!promptText.value) {
		return
	}
	try {
		await navigator.clipboard.writeText(promptText.value)
		notify('Prompt copied.')
	}
	catch {
		notify('Unable to copy prompt.')
	}
}
const eventDetailPayload = (event) => {
	let basePayload = event?.type === 'TOOL_COMBINED'
		? { tool_call: event.callEvent?.payload || {}, tool_result: event.resultEvent?.payload || {} }
		: event?.payload
	if (event?.type === 'TOOL_COMBINED' && basePayload) {
		basePayload = {
			...basePayload,
			input_template: toolInputTemplate(event) || undefined,
			output_template: toolOutputTemplate(event) || undefined
		}
	}
	const telemetry = eventTelemetryDetails(event)
	if (!telemetry) {
		return basePayload
	}
	if (!basePayload) {
		return { telemetry }
	}
	return { payload: basePayload, telemetry }
}
const eventTelemetryDetails = (event) => {
	if (!event) {
		return null
	}
	const input = event.input_tokens
	const output = event.output_tokens
	const reasoning = event.reasoning_tokens
	const hasTokens = input != null || output != null || reasoning != null
	const hasCost = event.price_snapshot != null
	if (!hasTokens && !hasCost) {
		return null
	}
	return {
		input_tokens: input,
		output_tokens: output,
		reasoning_tokens: reasoning,
		tokenizer_model_id: event.tokenizer_model_id || null,
		price_snapshot: event.price_snapshot,
		price_currency: event.price_currency || null
	}
}
const eventKind = (event) => {
	const type = event?.type
	if (type === 'USER_MESSAGE') {
		return 'user'
	}
	if (type === 'CONTEXT_MESSAGE') {
		return 'context'
	}
	if (type === 'ASSISTANT_MESSAGE') {
		return 'assistant'
	}
	if (type === 'TOOL_CALL') {
		return 'tool-call'
	}
	if (type === 'TOOL_RESULT') {
		return 'tool-result'
	}
	if (type === 'TOOL_COMBINED') {
		return 'tool-result'
	}
	if (type === 'TOOL_CALL_PENDING') {
		return 'tool-call'
	}
	if (type === 'SYSTEM') {
		return 'system'
	}
	if (type === 'AUDIT') {
		return 'system'
	}
	if (type === 'SESSION_TAG_CHANGE') {
		return 'system'
	}
	if (type === 'DECISION_REQUEST' || type === 'DECISION_RESULT') {
		return 'decision'
	}
	return 'event'
}
const toolInfo = (event) => {
	if (!event) {
		return { name: '', id: '', args: null }
	}
	const payload = event.payload || event.callEvent?.payload || event.resultEvent?.payload || {}
	return {
		name: payload.tool_name || payload.tool || payload.name || '',
		id: payload.tool_call_id || payload.id || '',
		args: payload.arguments || null
	}
}
const toolResultText = (event) => {
	const payload = event?.type === 'TOOL_COMBINED' ? event.resultEvent?.payload || {} : event?.payload || {}
	const payloadDisplayMessage = payload?._meta?.displayMessage
	if (typeof payloadDisplayMessage === 'string' && payloadDisplayMessage.trim()) {
		return payloadDisplayMessage.trim()
	}
	const directText = contentText(payload)
	if (directText) {
		return directText
	}
	const result = payload.result || payload.output
	const resultDisplayMessage = result?._meta?.displayMessage
	if (typeof resultDisplayMessage === 'string' && resultDisplayMessage.trim()) {
		return resultDisplayMessage.trim()
	}
	if (result && typeof result === 'object') {
		const contentTextValue = contentText(result)
		if (contentTextValue) {
			return contentTextValue
		}
	}
	if (typeof result === 'string') {
		return result
	}
	if (result && typeof result === 'object') {
		return JSON.stringify(result)
	}
	return ''
}
const TOOL_RESULT_PREVIEW_LIMIT = 150
const approxTokenCount = (value) => {
	if (!value) {
		return 0
	}
	try {
		return Math.ceil(new TextEncoder().encode(value).length / 4)
	}
	catch {
		return Math.ceil(value.length / 4)
	}
}
const toolResultSummary = (event) => {
	const raw = toolResultText(event)
	if (!raw) {
		return { text: '', isError: false }
	}
	const trimmed = raw.trim()
	let parsed = null
	if ((trimmed.startsWith('{') && trimmed.endsWith('}'))
			|| (trimmed.startsWith('[') && trimmed.endsWith(']'))) {
		try {
			parsed = JSON.parse(trimmed)
		}
		catch {
			parsed = null
		}
	}
	if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
		const isError = parsed.isError === true || parsed.error === true
		const message = typeof parsed.message === 'string' ? parsed.message.trim() : ''
		if (message) {
			return { text: message, isError }
		}
		return { text: `Received ${approxTokenCount(raw)} tokens`, isError }
	}
	if (parsed) {
		return { text: `Received ${approxTokenCount(raw)} tokens`, isError: false }
	}
	if (raw.length > TOOL_RESULT_PREVIEW_LIMIT) {
		return { text: `${raw.slice(0, TOOL_RESULT_PREVIEW_LIMIT)}…`, isError: false }
	}
	return { text: raw, isError: false }
}
const toolResultSummaryText = (event) => toolResultSummary(event).text
const toolResultSummaryError = (event) => toolResultSummary(event).isError
const normalizeToolLine = (value) => {
	if (value == null) {
		return ''
	}
	const raw = String(value).replace(/\s+/g, ' ').trim().toLowerCase()
	if (!raw) {
		return ''
	}
	return raw.replace(/[\s\.!,:;?]+$/g, '').trim()
}
const shouldHideToolResponse = (event) => {
	if (!event) {
		return false
	}
	const status = toolStatus(event)
	if (status === 'error' || status === 'cancelled') {
		return false
	}
	if (isToolPending(event)) {
		return false
	}
	const response = toolResultText(event) || eventPreview(event)
	if (!response) {
		return false
	}
	const request = toolCallSummary(event?.type === 'TOOL_COMBINED' ? event.callEvent : event)
	if (!request) {
		return false
	}
	return normalizeToolLine(response) === normalizeToolLine(request)
}
const toolReviewUri = (event) => {
	const payload = event?.type === 'TOOL_COMBINED' ? event.resultEvent?.payload || {} : event?.payload || {}
	const review = payload.review_uri || payload.reviewUri
	return typeof review === 'string' ? review : ''
}
const tagValue = (tag) => tag?.name || tag?.type || ''
const tagTooltip = "You can't change the tags for an active session because dynamic policy change could invalidate "
	+ "the message history and confuse the llm."
const decisionReviewUri = (request) => {
	if (!request) {
		return ''
	}
	const uri = request.review_uri || request.reviewUri
	return typeof uri === 'string' ? uri : ''
}
function toolCallSummary(event) {
	if (!event) {
		return ''
	}
	if (event.type === 'TOOL_COMBINED') {
		const summary = event.callEvent?.payload?.summary
		return typeof summary === 'string' ? summary : ''
	}
	const summary = event?.payload?.summary
	return typeof summary === 'string' ? summary : ''
}
const toolRequestSummary = (event) => {
	if (!event) {
		return ''
	}
	const directSummary = toolCallSummary(event)
	if (directSummary) {
		return directSummary
	}
	const payload = event?.payload || event?.callEvent?.payload || event?.resultEvent?.payload || {}
	const id = payload.tool_call_id || payload.id
	const mappedSummary = id ? toolRequestSummaryById.value[id] : ''
	if (mappedSummary) {
		return mappedSummary
	}
	const name = payload.tool_name || payload.tool || payload.name
	return typeof name === 'string' ? name : ''
}
const toolNameFromPayload = (payload) => {
	if (!payload || typeof payload !== 'object') {
		return ''
	}
	return payload.tool_name || payload.tool || payload.name || ''
}
const toolCallIdFromPayload = (payload, fallbackId = '') => {
	if (!payload || typeof payload !== 'object') {
		return fallbackId
	}
	return payload.tool_call_id || payload.id || payload.tool_call_id || fallbackId
}
const isSpawnWorkersToolEvent = (event) => {
	if (!event) {
		return false
	}
	const payload = event?.payload || event?.callEvent?.payload || event?.resultEvent?.payload || {}
	const name = toolNameFromPayload(payload)
	if (name === 'spawn_workers') {
		return true
	}
	const toolCall = payload.tool_call || payload.toolCall || null
	const toolResult = payload.tool_result || payload.toolResult || null
	return toolNameFromPayload(toolCall) === 'spawn_workers'
		|| toolNameFromPayload(toolResult) === 'spawn_workers'
}
const spawnWorkersToolEventsByCallId = computed(() => {
	const map = {}
	const pending = new Map()
	filteredSessionEvents.value
		.forEach((event) => {
			if (!event) {
				return
			}
			if (event.type === 'TOOL_CALL' || event.type === 'TOOL_CALL_PENDING') {
				const payload = event.payload || {}
				const name = toolNameFromPayload(payload)
				if (name !== 'spawn_workers') {
					return
				}
				const callId = toolCallIdFromPayload(payload, event.id)
				pending.set(callId, event)
				map[callId] = {
					id: event.id,
					type: 'TOOL_COMBINED',
					created_at: event.created_at,
					callEvent: event,
					resultEvent: null
				}
				return
			}
			if (event.type === 'TOOL_RESULT') {
				const payload = event.payload || {}
				const name = toolNameFromPayload(payload)
					|| toolNameFromPayload(payload.tool_result || payload.toolResult || null)
				if (name !== 'spawn_workers') {
					return
				}
				const callId = toolCallIdFromPayload(payload, event.id)
				const callEvent = pending.get(callId)
				map[callId] = {
					id: event.id,
					type: 'TOOL_COMBINED',
					created_at: event.created_at,
					callEvent: callEvent || null,
					resultEvent: event
				}
				pending.delete(callId)
			}
		})
	return map
})
const isToolEvent = (event) => ['TOOL_CALL', 'TOOL_CALL_PENDING', 'TOOL_RESULT', 'TOOL_COMBINED'].includes(event?.type)
const INSPECT_TRUNCATE_LIMIT = 5000
const inspectInput = computed(() => inspectTarget.value?.input ?? null)
const inspectOutput = computed(() => inspectTarget.value?.output ?? null)
const inspectTitle = computed(() => inspectTarget.value?.title || 'Details')
const inspectHasOutput = computed(() => inspectOutput.value !== null && inspectOutput.value !== undefined)
const toRawText = (value) => {
	if (value === undefined) {
		return ''
	}
	if (value === null) {
		return 'null'
	}
	if (typeof value === 'string') {
		return value
	}
	return JSON.stringify(value, null, 2)
}
const inspectInputRaw = computed(() => toRawText(inspectInput.value))
const inspectOutputRaw = computed(() => toRawText(inspectOutput.value))
const inspectInputTruncated = computed(() => inspectInputRaw.value.length > INSPECT_TRUNCATE_LIMIT)
const inspectOutputTruncated = computed(() => inspectOutputRaw.value.length > INSPECT_TRUNCATE_LIMIT)
const inspectInputRawDisplay = computed(() => inspectInputExpanded.value
		|| !inspectInputTruncated.value
	? inspectInputRaw.value
	: inspectInputRaw.value.slice(0, INSPECT_TRUNCATE_LIMIT))
const inspectOutputRawDisplay = computed(() => inspectOutputExpanded.value
		|| !inspectOutputTruncated.value
	? inspectOutputRaw.value
	: inspectOutputRaw.value.slice(0, INSPECT_TRUNCATE_LIMIT))
const openInspect = ({ title, input, output }) => {
	inspectTarget.value = { title, input, output }
	inspectMode.value = 'pretty'
	inspectInputExpanded.value = false
	inspectOutputExpanded.value = false
	inspectTab.value = 'input'
	inspectOpen.value = true
}
const closeInspect = () => {
	inspectOpen.value = false
	inspectTarget.value = null
}
const toolInspectInput = (event) => {
	const callPayload = event?.payload?.tool_call || event?.callEvent?.payload || event?.payload
	const args = callPayload?.arguments
	const meta = callPayload?._meta || callPayload?.meta
	if (args !== undefined) {
		return {
			tool_name: callPayload?.tool_name || callPayload?.tool || callPayload?.name,
			arguments: args,
			tool_call_id: callPayload?.tool_call_id || callPayload?.id,
			_meta: meta
		}
	}
	return callPayload || null
}
const toolInspectOutput = (event) => {
	const resultPayload = event?.payload?.tool_result || event?.resultEvent?.payload || event?.payload
	return resultPayload || null
}
const openToolInspect = (event) => {
	openInspect({
		title: toolRequestSummary(event) || 'Tool details',
		input: toolInspectInput(event),
		output: toolInspectOutput(event)
	})
}
const openDecisionInspect = (request, summary) => {
	const input = {
		tool_name: request?.tool_name,
		scopes: request?.scopes,
		scope_options: request?.scope_options,
		input_template: request?.input_template,
		arguments: request?.arguments
	}
	const output = {
		preview: request?.preview,
		preview_text: request?.preview_text,
		review_uri: request?.review_uri || request?.reviewUri,
		diff_uri: request?.diff_uri || request?.diffUri,
		preview_failed: request?.preview_failed
	}
	openInspect({ title: summary || request?.tool_name || 'Approval details', input, output })
}
const canInspectEvent = (event) => isToolEvent(event)
const eventPreview = (event) => {
	const payload = event?.payload || {}
	if (payload.kind === 'worker_progress') {
		const done = payload.done ?? 0
		const total = payload.total ?? 0
		const failed = payload.failed ?? 0
		return `Workers: ${done}/${total} done${failed ? `, ${failed} failed` : ''}`
	}
	const text = contentText(payload)
	if (text) {
		return text
	}
	if (event?.type === 'TOOL_COMBINED') {
		return ''
	}
	if (event?.type === 'TOOL_CALL' || event?.type === 'TOOL_CALL_PENDING') {
		return ''
	}
	if (event?.type === 'TOOL_RESULT') {
		return ''
	}
	if (event?.type === 'SYSTEM') {
		return 'System event'
	}
	return ''
}
const eventText = (event) => contentText(event?.payload || {})
const isWorkerProgressEvent = (event) => event?.payload?.kind === 'worker_progress'
const eventThinkText = (event) => {
	const payload = event?.payload || {}
	const text = extractThinkText(payload.content || payload.text || '')
	if (!text) {
		return ''
	}
	return text.replace(/\s+/g, ' ').trim()
}
const eventAttachments = (event) => {
	const payload = event?.payload || {}
	const list = Array.isArray(payload.attachments) ? payload.attachments : []
	return list.filter((entry) => entry && (typeof entry.data === 'string' || typeof entry.blob_hash === 'string'))
		.map((entry) => ({
		name: entry.name || 'Attachment',
		type: entry.type || '',
		data: entry.data || '',
		blobHash: entry.blob_hash || '',
		publicUrl: entry.public_url || '',
		userUrl: entry.user_url || '',
		isImage: (entry.data && entry.data.startsWith('data:image'))
			|| entry.type?.startsWith('image/')
	}))
}
const attachmentUrl = (attachment) => {
	if (!attachment) {
		return ''
	}
	if (attachment.publicUrl) {
		return attachment.publicUrl
	}
	if (attachment.userUrl) {
		return attachment.userUrl
	}
	if (attachment.data) {
		return attachment.data
	}
	return ''
}
const openImagePreview = (url) => {
	if (!url) {
		return
	}
	imagePreviewUrl.value = url
	imagePreviewOpen.value = true
}
const closeImagePreview = () => {
	imagePreviewOpen.value = false
	imagePreviewUrl.value = ''
}
const toolStatus = (event) => {
	if (!event) {
		return ''
	}
	const payload = event?.type === 'TOOL_COMBINED' ? event.resultEvent?.payload || {} : event?.payload || {}
	if (event?.type !== 'TOOL_RESULT' && event?.type !== 'TOOL_COMBINED') {
		return ''
	}
	if (payload.status === 'cancelled') {
		return 'cancelled'
	}
	const result = payload.result || {}
	if (payload.isError === true
			|| payload.is_error === true
			|| payload.code
			|| payload.error
			|| result.error === true
			|| result.isError === true
			|| result.is_error === true) {
		return 'error'
	}
	if (payload.status) {
		return String(payload.status)
	}
	return 'success'
}
const toolStatusLabel = (event) => {
	const status = toolStatus(event)
	if (!status) {
		return ''
	}
	const payload = event?.type === 'TOOL_COMBINED' ? event.resultEvent?.payload || {} : event?.payload || {}
	if (status === 'cancelled') {
		return 'Cancelled'
	}
	if (status === 'error') {
		const code = payload.code || payload.error_code || payload.error
		if (code === 'permission_denied') {
			return 'Denied'
		}
		return 'Failed'
	}
	if (status === 'success') {
		return 'Executed'
	}
	return status
}
const toolInputTemplate = (event) => {
	if (!event) {
		return ''
	}
	if (event.type === 'TOOL_COMBINED') {
		const value = event.callEvent?.payload?.input_template
		return typeof value === 'string' ? value : ''
	}
	const value = event?.payload?.input_template
	return typeof value === 'string' ? value : ''
}
const toolOutputTemplate = (event) => {
	if (!event) {
		return ''
	}
	if (event.type === 'TOOL_COMBINED') {
		const value = event.resultEvent?.payload?.output_template
		return typeof value === 'string' ? value : ''
	}
	const value = event?.payload?.output_template
	return typeof value === 'string' ? value : ''
}
const TOOL_OUTPUT_TEMPLATE_LINE_LIMIT = 20
const toolOutputTemplateLines = (event) => {
	const value = toolOutputTemplate(event)
	return value ? value.split('\n') : []
}
const toolOutputTemplateTruncated = (event) => {
	return toolOutputTemplateLines(event).length > TOOL_OUTPUT_TEMPLATE_LINE_LIMIT
}
const toolOutputExpandedFor = (eventId) => {
	if (!eventId) {
		return false
	}
	return !!toolOutputExpanded.value[eventId]
}
const toggleToolOutputExpanded = (eventId) => {
	if (!eventId) {
		return
	}
	toolOutputExpanded.value = { ...toolOutputExpanded.value, [eventId]: !toolOutputExpandedFor(eventId) }
}
const visibleToolOutputTemplate = (event) => {
	const value = toolOutputTemplate(event)
	if (!value) {
		return ''
	}
	if (!toolOutputTemplateTruncated(event) || toolOutputExpandedFor(event?.id)) {
		return value
	}
	return toolOutputTemplateLines(event).slice(0, TOOL_OUTPUT_TEMPLATE_LINE_LIMIT).join('\n')
}
const shouldShowToolStatus = (event) => {
	if (!event) {
		return false
	}
	const label = toolStatusLabel(event)
	return label === 'Failed' || label === 'Denied' || label === 'Cancelled'
}
const decisionRequests = (event) => {
	const requests = event?.payload?.requests
	return Array.isArray(requests) ? requests : []
}
const toolRequestSummaryById = computed(() => {
	const map = {}
	filteredSessionEvents.value
		.forEach((event) => {
			if (event?.type !== 'DECISION_REQUEST') {
				return
			}
			decisionRequests(event)
				.forEach((request) => {
					const id = request?.id || request?.tool_call_id || request?.toolCallId
					if (!id || map[id]) {
						return
					}
					const summary = decisionRequestSummary(request)
					if (typeof summary === 'string' && summary.trim()) {
						map[id] = summary.trim()
						return
					}
					const name = request?.tool_name || request?.tool || request?.name
					if (typeof name === 'string' && name.trim()) {
						map[id] = name.trim()
					}
				})
		})
	return map
})
const decisionRequestId = (event) => event?.payload?.request_id || ''
const decisionRequestSummary = (request) => {
	const summary = request?.summary
	return typeof summary === 'string' ? summary : ''
}
const decisionInputTemplate = (request) => {
	const value = request?.input_template
	return typeof value === 'string' ? value : ''
}
const decisionHasPreview = (request) => {
	return !!decisionReviewUri(request) || !!request?.preview || !!request?.preview_text
}
const decisionToolResult = (request) => {
	const preview = request?.preview
	return toolResultEnvelope(preview?.result ?? preview)
}
const decisionResults = computed(() => {
	const resultMap = {}
	filteredSessionEvents.value
		.forEach((event) => {
			if (event?.type !== 'DECISION_RESULT') {
				return
			}
			const requestId = event?.payload?.request_id
			if (requestId) {
				resultMap[requestId] = event?.payload
			}
		})
	return resultMap
})
const decisionStatus = (event) => {
	const requestId = decisionRequestId(event)
	return requestId ? decisionResults.value[requestId] : null
}
const decisionRememberValue = (event) => {
	const status = decisionStatus(event)
	if (status) {
		return !!status.remember
	}
	return !!decisionRemember.value[event?.id]
}
const decisionApprovedBy = (event) => {
	const status = decisionStatus(event)
	if (!status) {
		return ''
	}
	const userId = status.approved_by_user_id
	return typeof userId === 'string' ? userId : ''
}
const isToolPending = (event) => {
	if (!event) {
		return false
	}
	if (event.type === 'TOOL_CALL' || event.type === 'TOOL_CALL_PENDING') {
		return true
	}
	if (event.type === 'TOOL_COMBINED') {
		return !event.resultEvent
	}
	return false
}
const eventRowProps = computed(() => ({
	formatTimestamp,
	eventTypeLabel: eventLabel,
	eventPreview,
	eventText,
	eventComment,
	eventThinkText,
	eventAborted,
	eventAttachments,
	attachmentUrl,
	downloadAttachment,
	downloadStateFor,
	openImagePreview,
	isToolEvent,
	toolInputTemplate,
	shouldHideToolResponse,
	toolResultSummaryText,
	toolResultSummaryError,
	toolStatus,
	isToolPending,
	toolReviewUri,
	reviewOpenFor,
	toolUiViewState,
	noopToolCaller,
	openDetails: openDetails.value,
	eventDetailPayload,
	toolOutputTemplate,
	visibleToolOutputTemplate,
	toolOutputTemplateTruncated,
	toggleToolOutputExpanded,
	toolOutputExpandedFor,
	toggleReview,
	toggleEventDetails: toggleDetails,
	copyEventPayload,
	inspectEvent: openToolInspect,
	decisionRequests,
	decisionRequestSummary,
	decisionStatus,
	decisionRequestResult,
	decisionApprovedBy,
	decisionRequestKey,
	toggleDecisionRequestDetails,
	openDecisionInspect,
	decisionRequestDetailsOpenFor,
	decisionHasPreview,
	decisionInputTemplate,
	decisionReviewUri,
	decisionToolViewState,
	decisionRequestedScopes,
	decisionScopeState: decisionScopeState.value,
	updateDecisionScopeState,
	sendDecision,
	decisionAllUndecided,
	decisionMissingScopes,
	decisionRememberValue,
	decisionRemember: decisionRemember.value,
	userLabelForEvent,
	userAvatarUrl,
	userAvatarColor,
	userAvatarInitial,
	canInspectEvent,
	eventKind,
	workerToolEventsByCallId: spawnWorkersToolEventsByCallId.value,
	openWorkerPane: (event) => {
		if (event?.payload?.kind === 'worker_progress') {
			const toolCallId = event.payload.tool_call_id || event.payload.toolCallId || ''
			if (toolCallId) {
				selectedWorkerToolCallId.value = toolCallId
			}
			const timestamp = Date.parse(event?.created_at || event?.createdAt || '')
			updateWorkerProgress(event.payload, Number.isFinite(timestamp) ? timestamp : undefined)
		}
		workerPaneOpen.value = true
	}
}))
const displayEvents = computed(() => {
	const output = []
	const pendingCalls = new Map()
	const pendingIndices = new Map()
	filteredSessionEvents.value
		.forEach((event) => {
			if (!event) {
				return
			}
			if (event.type === 'DECISION_REQUEST' && !showResolvedApprovals.value && decisionStatus(event)) {
				return
			}
			if (event.type === 'DECISION_RESULT') {
				return
			}
			if (event.type === 'TOOL_CALL' || event.type === 'TOOL_CALL_PENDING') {
				if (isSpawnWorkersToolEvent(event)) {
					return
				}
				const callId = event?.payload?.tool_call_id || event?.payload?.id || event.id
				pendingCalls.set(callId, event)
				const combined = {
					id: event.id,
					renderId: event.renderId || event.id,
					type: 'TOOL_COMBINED',
					created_at: event.created_at,
					callEvent: event,
					resultEvent: null
				}
				pendingIndices.set(callId, output.length)
				output.push(combined)
				return
			}
			if (event.type === 'TOOL_RESULT') {
				if (isSpawnWorkersToolEvent(event)) {
					const callId = event?.payload?.tool_call_id || event?.payload?.id
					if (callId && pendingIndices.has(callId)) {
						pendingCalls.delete(callId)
						pendingIndices.delete(callId)
					}
					return
				}
				const callId = event?.payload?.tool_call_id || event?.payload?.id
				if (callId && pendingIndices.has(callId)) {
					const callEvent = pendingCalls.get(callId)
					const index = pendingIndices.get(callId)
					output[index] = {
						id: event.id,
						renderId: callEvent?.renderId || event.renderId || event.id,
						type: 'TOOL_COMBINED',
						created_at: event.created_at,
						callEvent,
						resultEvent: event
					}
					pendingCalls.delete(callId)
					pendingIndices.delete(callId)
					return
				}
				output.push(event)
				return
			}
			output.push(event)
		})
	return output
})
const ensureDecisionDefaults = (event) => {
	const eventId = event?.id
	if (!eventId || decisionScopeState.value[eventId]) {
		return
	}
	decisionScopeState.value = { ...decisionScopeState.value, [eventId]: { allow: [], deny: [] } }
	decisionRemember.value = { ...decisionRemember.value, [eventId]: false }
}
const decisionRequestKey = (request, index) => request?.id || `idx-${index}`
const toggleDecisionRequestDetails = (eventId, requestKey) => {
	if (!eventId || !requestKey) {
		return
	}
	const key = `${eventId}:${requestKey}`
	decisionRequestDetailsOpen.value = { ...decisionRequestDetailsOpen.value, [key]: !decisionRequestDetailsOpen.value[key] }
}
const decisionRequestDetailsOpenFor = (eventId, requestKey) => {
	if (!eventId || !requestKey) {
		return false
	}
	return !!decisionRequestDetailsOpen.value[`${eventId}:${requestKey}`]
}
const updateDecisionScopeState = (eventId, payload) => {
	if (!eventId || !payload) {
		return
	}
	decisionScopeState.value = { ...decisionScopeState.value, [eventId]: payload }
}
const decisionRequestedScopes = (event) => {
	const scopes = new Set()
	decisionRequests(event)
		.forEach((request) => {
			const list = Array.isArray(request?.scopes)
				? request.scopes
				: Array.isArray(request?.scope_options) ? request.scope_options : []
			list.forEach((scope) => {
					if (scope) {
						scopes.add(scope)
					}
				})
		})
	return Array.from(scopes)
}
const bestSpecificity = (scope, candidates) => {
	if (!scope || !Array.isArray(candidates) || !candidates.length) {
		return 0
	}
	let best = 0
	candidates.forEach((candidate) => {
			if (!candidate) {
				return
			}
			if (scope === candidate || scope.startsWith(`${candidate}:`)) {
				const specificity = candidate.split(':').length
				if (specificity > best) {
					best = specificity
				}
			}
		})
	return best
}
const decisionForScope = (scope, allowScopes, denyScopes) => {
	const allowSpec = bestSpecificity(scope, allowScopes)
	const denySpec = bestSpecificity(scope, denyScopes)
	if (denySpec > 0 && denySpec >= allowSpec) {
		return 'deny'
	}
	if (allowSpec > 0) {
		return 'allow'
	}
	return 'undecided'
}
const decisionMissingScopes = (event) => {
	const eventId = event?.id
	const scopesState = eventId ? decisionScopeState.value[eventId] || { allow: [], deny: [] } : { allow: [], deny: [] }
	const required = decisionRequestedScopes(event)
	return required.filter((scope) => decisionForScope(scope, scopesState.allow, scopesState.deny) === 'undecided')
}
const decisionAllUndecided = (event) => {
	const required = decisionRequestedScopes(event)
	if (!required.length) {
		return true
	}
	return decisionMissingScopes(event).length === required.length
}
const decisionRequestResult = (event, request) => {
	const status = decisionStatus(event)
	if (!status) {
		return null
	}
	const allow = new Set()
	const deny = new Set()
	if (status.scope_decisions) {
		Object.entries(status.scope_decisions)
			.forEach(([scope, value]) => {
				if (value === 'allow') {
					allow.add(scope)
				}
				else if (value === 'deny') {
					deny.add(scope)
				}
			})
	}
	else if (Array.isArray(status.allow_scopes)) {
		status.allow_scopes.forEach((scope) => allow.add(scope))
	}
	else if (status.allow_scope) {
		allow.add(status.allow_scope)
	}
	const scopes = Array.isArray(request?.scopes) ? request.scopes : []
	let hasAllow = false
	let hasDeny = false
	scopes.forEach((scope) => {
			const result = decisionForScope(scope, Array.from(allow), Array.from(deny))
			if (result === 'deny') {
				hasDeny = true
			}
			else if (result === 'allow') {
				hasAllow = true
			}
		})
	if (hasDeny) {
		return 'deny'
	}
	if (hasAllow) {
		return 'allow'
	}
	return null
}
const sendDecision = (event, decision = 'allow') => {
	if (!selectedSessionId.value) {
		return
	}
	const eventId = event?.id
	const requestId = decisionRequestId(event)
	const scopesState = eventId ? decisionScopeState.value[eventId] || { allow: [], deny: [] } : { allow: [], deny: [] }
	const scopeDecisions = {}
	if (decisionAllUndecided(event)) {
		decisionRequestedScopes(event).forEach((scope) => {
				if (scope) {
					scopeDecisions[scope] = decision
				}
			})
	}
	else {
		scopesState.allow.forEach((scope) => {
				if (scope) {
					scopeDecisions[scope] = 'allow'
				}
			})
		scopesState.deny.forEach((scope) => {
				if (scope) {
					scopeDecisions[scope] = 'deny'
				}
			})
	}
	const payload = {
		decision,
		request_id: requestId,
		scope_decisions: scopeDecisions,
		remember: !!decisionRemember.value[eventId]
	}
	attachLocationMeta(payload)
	sendWorkspaceCommand({ type: 'chat.send', session_id: selectedSessionId.value, payload })
}
const eventAborted = (event) => event?.payload?.aborted === true
const eventComment = (event) => {
	const comment = event?.payload?.comment
	return typeof comment === 'string' && comment.trim() ? comment.trim() : ''
}
const tagLabel = (tag) => {
	if (!tag) {
		return ''
	}
	if (tag.category_name && tag.value_name) {
		return `${tag.category_name}:${tag.value_name}`
	}
	return tag.value_name || tag.category_name || ''
}
const serverLabel = (server) => server?.name || server?.id || ''
const hasUiEntries = (event) => !!toolReviewUri(event)
const toolUiResult = (event) => {
	const payload = event?.type === 'TOOL_COMBINED' ? event?.resultEvent?.payload || {} : event?.payload || {}
	return toolResultEnvelope(payload?.result ?? payload)
}
watch(
	selectedSessionId,
	(next, prev) => {
		if (next && next !== prev) {
			selectedEpoch.value = 'current'
			sessionCanvases.value = []
			activeCanvasId.value = ''
		}
		loadEvents()
	},
	{ immediate: true }
)
watch(selectedEpoch, loadEvents)
watch(
	() => epochIds.value.join(','),
	() => {
		if (!epochIds.value.length) {
			return
		}
		if (selectedEpoch.value === 'current') {
			return
		}
		const value = Number(selectedEpoch.value)
		if (!epochIds.value.includes(value)) {
			selectedEpoch.value = 'current'
		}
	}
)
watch(
	selectedSessionId,
	(next, prev) => {
		if (typingActive.value) {
			typingActive.value = false
			sendTypingStatus(false)
		}
		lastTypingSentAt.value = 0
		if (prev && prev !== next) {
			handleSessionLeave({ sessionId: prev, queuesRef: queuedMessagesBySession, smartQueueing: smartQueueing.value })
		}
	}
)
watch(
	selectedSessionId,
	(sessionId) => {
		if (!sessionId) {
			return
		}
		const session = sessions.value.find((item) => item.id === sessionId)
		if (session && session.status) {
			const locked = session.locked
			if (!locked) {
				abortingSessions.value = { ...abortingSessions.value, [sessionId]: false }
				streamingMessages.value = { ...streamingMessages.value, [sessionId]: '' }
			}
		}
	}
)
watch(
	selectedSessionId,
	async() => {
		if (techDetailsOpen.value) {
			await loadTechnicalDetails()
		}
	}
)
watch(
	selectedSessionId,
	(nextId, prevId) => {
		if (prevId && traceEnabled.value[prevId]) {
			stopTrace(prevId)
		}
		if (!nextId) {
			traceOpen.value = false
			traceCollapsed.value = false
		}
	}
)
watch(
	selectedSession,
	() => {
		sessionAssistantSelection.value = selectedSession.value?.default_assistant_id || ''
	}
)
watch(
	() => isLocked.value,
	(locked) => {
		if (locked || isArchived.value) {
			return
		}
		nextTick(() => {
			composerInputRef.value?.focus()
		})
	}
)
watch(
	() => socketConnected.value,
	(connected) => {
		if (!connected) {
			return
		}
		trySendActiveSession()
	}
)
watch(
	selectedSessionFromStore,
	(session) => {
		if (!session || !selectedSessionId.value) {
			return
		}
		if (pendingActiveSessionId.value === selectedSessionId.value) {
			attachSession(selectedSessionId.value)
		}
	}
)
watch(
	() => selectedSessionId.value,
	async(next, prev) => {
		if (prev) {
			await detachSession(prev)
		}
		activeSessionId.value = next || ''
		if (next) {
			await attachSession(next)
		}
	}
)
watch(
	sessionFromRoute,
	(next) => {
		if (next && next !== selectedSessionId.value) {
			selectedSessionId.value = next
		}
	}
)
const connectWorkspaceSocket = () => {
	if (!tenantId.value) {
		return
	}
	if (unregisterSocketHandler) {
		unregisterSocketHandler()
	}
	unregisterSocketHandler = workspaceSocketState.registerHandler(handleWorkspaceMessage)
}
let handleResize = null
onMounted(() => {
	window.addEventListener('tts.voice.change', handleTtsVoiceChange)
	if (sessionFromRoute.value) {
		selectedSessionId.value = sessionFromRoute.value
	}
	loadAssistants()
	if (!isEmbedded.value) {
		loadWorkspaceTags()
	}
	connectWorkspaceSocket()
	if (shouldRecord.value) {
		startVAD()
	}
	lastViewportHeight.value = window.innerHeight
	handleResize = () => {
		if (resizeTimer) {
			clearTimeout(resizeTimer)
		}
		resizeTimer = setTimeout(
			() => {
				resizeTimer = null
				if (window.innerHeight !== lastViewportHeight.value) {
					lastViewportHeight.value = window.innerHeight
					recalibrateLogHeight(true)
				}
			},
			200
		)
	}
	window.addEventListener('resize', handleResize)
	canvasPaneWidth.value = clampCanvasPaneWidth(canvasPaneWidth.value)
	persistCanvasPaneWidth(canvasPaneWidth.value)
	stopThemeObserver = observeMcpTheme((themeVars) => {
		mcpViewTheme.value = themeVars
	})
	nextTick(() => {
		recalibrateLogHeight(true)
		if (typeof ResizeObserver !== 'undefined') {
			if (sessionHeaderRef.value) {
				headerObserver = new ResizeObserver(() => recalibrateLogHeight(true))
				headerObserver.observe(sessionHeaderRef.value)
			}
			if (sessionLogRef.value) {
				logObserver = new ResizeObserver(() => {
					if (autoScrollActive.value) {
						requestAnimationFrame(() => maybeAutoScroll())
					}
				})
				logObserver.observe(sessionLogRef.value)
			}
		}
		if (typeof MutationObserver !== 'undefined' && sessionLogRef.value) {
			logMutationObserver = new MutationObserver(() => {
				if (autoScrollActive.value) {
					requestAnimationFrame(() => maybeAutoScroll())
				}
			})
			logMutationObserver.observe(sessionLogRef.value, {
				subtree: true,
				childList: true,
				characterData: true,
				attributes: true
			})
		}
	})
	typingInterval = setInterval(
		() => {
			typingClock.value = Date.now()
			pruneTyping()
		},
		1000
	)
})
onMounted(() => {
	document.addEventListener('keydown', imagePreviewListener)
	document.addEventListener('keydown', handleGlobalKeydown)
})
onBeforeUnmount(() => {
	clearActiveRetry()
	document.removeEventListener('keydown', imagePreviewListener)
	document.removeEventListener('keydown', handleGlobalKeydown)
})
onBeforeUnmount(() => {
	window.removeEventListener('tts.voice.change', handleTtsVoiceChange)
	isAlive.value = false
	stopVAD()
	stopTts()
	if (typingInterval) {
		clearInterval(typingInterval)
		typingInterval = null
	}
	if (selectedSessionId.value && traceEnabled.value[selectedSessionId.value]) {
		stopTrace(selectedSessionId.value)
	}
	forceDetachSession(activeSessionId.value || selectedSessionId.value)
	if (unregisterSocketHandler) {
		unregisterSocketHandler()
		unregisterSocketHandler = null
	}
	if (pingTimer.value) {
		clearTimeout(pingTimer.value)
		pingTimer.value = null
	}
	if (resizeTimer) {
		clearTimeout(resizeTimer)
		resizeTimer = null
	}
	if (rafId) {
		cancelAnimationFrame(rafId)
		rafId = null
	}
	if (headerObserver) {
		headerObserver.disconnect()
		headerObserver = null
	}
	if (logObserver) {
		logObserver.disconnect()
		logObserver = null
	}
	if (logMutationObserver) {
		logMutationObserver.disconnect()
		logMutationObserver = null
	}
	if (handleResize) {
		window.removeEventListener('resize', handleResize)
		handleResize = null
	}
	stopCanvasResizeInteraction()
	if (stopThemeObserver) {
		stopThemeObserver()
		stopThemeObserver = null
	}
})
</script>
<template>
	<main class="sessions-main">
		<div class="session-view" ref="sessionViewRef">
			<div class="session-header" ref="sessionHeaderRef">
				<div>
					<div class="title-row">
						<h2>{{ selectedSession?.title || 'Untitled session' }}</h2>
						<button
							v-if="selectedSession"
							class="control size-xs ghost icon-button icon-ghost tooltip"
							type="button"
							data-tip="Rename"
							@click="openRename">
							<svg
								viewBox="0 0 24 24"
								aria-hidden="true"
								focusable="false">
								<path
									fill="currentColor"
									d="M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zm2.92 2.33H5v-.92l8.06-8.06.92.92L5.92 19.58zM20.71 7.04a1.003 1.003 0 0 0 0-1.42l-2.34-2.34a1.003 1.003 0 0 0-1.42 0l-1.83 1.83 3.75 3.75 1.84-1.82z"/>
							</svg>
						</button>
					</div>
					<p v-if="selectedSession" class="subtle session-subtitle">{{ selectedSession.workflow_definition_name || 'Unknown workflow' }}</p>
					<p v-if="isReadOnly" class="subtle">Archived session · read-only</p>
					<div v-if="selectedWorkerProgress && selectedWorkerProgress.total > 0" class="worker-progress">
						<button
							class="control size-xs ghost"
							type="button"
							@click="workerPaneOpen = true">
							Workers: {{ selectedWorkerProgress.done || 0 }}/{{ selectedWorkerProgress.total }} done
							<span v-if="selectedWorkerProgress.failed">· {{ selectedWorkerProgress.failed }} failed</span>
						</button>
					</div>
				</div>
				<div class="header-actions">
					<div class="row-actions">
						<span v-if="selectedSession" class="control size-xs status-pill">{{ sessionStatusLabel }}</span>
						<label
							v-if="epochIds.length > 1"
							class="field inline-field">
							<span>Epoch</span>
							<select v-model="selectedEpoch">
								<option value="current">Current ({{ currentEpochId }})</option>
								<option
									v-for="epoch in epochIds"
									:key="epoch"
									:value="epoch">
									Epoch {{ epoch }}
								</option>
							</select>
						</label>
						<button
							v-if="selectedSession && !isArchived && isActiveSessionState"
							class="control size-xs ghost danger abort-button"
							type="button"
							:disabled="isReadOnly"
							@click="abortSession">Abort</button>
						<button
							v-if="selectedSession"
							class="control size-xs ghost icon-button icon-ghost tooltip tip-left"
							type="button"
							:disabled="!canPruneSession || pruningSession"
							:data-tip="pruningSession ? 'Pruning session' : 'Prune session'"
							@click="startPruneSession">
							<svg
								viewBox="0 0 24 24"
								aria-hidden="true"
								focusable="false">
								<path
									fill="currentColor"
									d="M6 7h12v2H6V7zm2 4h8v2H8v-2zm2 4h4v2h-4v-2z"/>
							</svg>
						</button>
						<button
							v-if="selectedSession"
							class="control size-xs ghost icon-button icon-ghost tooltip tip-left trace-toggle"
							type="button"
							:class="{ active: traceActive }"
							:disabled="isReadOnly"
							data-tip="Trace"
							@click="toggleTrace">
							<svg
								viewBox="0 0 24 24"
								aria-hidden="true"
								focusable="false">
								<path
									fill="currentColor"
									d="M4 4h16v2H4V4zm2 4h12v2H6V8zm-2 4h16v2H4v-2zm2 4h12v2H6v-2z"/>
							</svg>
						</button>
						<button
							v-if="selectedSession"
							class="control size-xs ghost icon-button icon-ghost tooltip tip-left"
							type="button"
							:disabled="isReadOnly"
							data-tip="Prompt preview"
							@click="openPromptPreview">
							<svg
								viewBox="0 0 24 24"
								aria-hidden="true"
								focusable="false">
								<path
									fill="currentColor"
									d="M5 4h10l4 4v12H5V4zm10 1.5V8h2.5L15 5.5zM7 11h10v2H7v-2zm0 4h10v2H7v-2z"/>
							</svg>
						</button>
						<button
							v-if="selectedSession"
							class="control size-xs ghost icon-button icon-ghost tooltip tip-left"
							type="button"
							data-tip="Session configuration"
							@click="openTechnicalDetails">
							<svg
								viewBox="0 0 24 24"
								aria-hidden="true"
								focusable="false">
								<path
									fill="currentColor"
									d="M11 17h2v-6h-2v6zm0-8h2V7h-2v2zm1-7C6.92 2 3 5.92 3 11s3.92 9 9 9 9-3.92 9-9-3.92-9-9-9zm0 16c-3.87 0-7-3.13-7-7s3.13-7 7-7 7 3.13 7 7-3.13 7-7 7z"/>
							</svg>
						</button>
						<button
							v-if="selectedSession && !isArchived && !isEmbedded"
							class="control size-xs ghost icon-button icon-ghost tooltip tip-left"
							type="button"
							:disabled="isLocked || isReadOnly"
							:data-tip="`Closes ${selectedSession?.workflow_definition_name || 'workflow'}. It stays in history but it is inactive.`"
							@click="closeConfirmOpen = true">
							<svg
								viewBox="0 0 24 24"
								aria-hidden="true"
								focusable="false">
								<path
									fill="currentColor"
									d="M20 6H4v2h16V6zm-2 3H6v9h12V9zm-6 2h2v5h-2v-5z"/>
							</svg>
						</button>
						<button
							v-if="selectedSession && !isEmbedded"
							class="control size-xs ghost icon-button icon-ghost danger tooltip tip-left"
							type="button"
							:disabled="isLocked"
							data-tip="Delete"
							@click="requestDeleteSession(selectedSession)">
							<svg
								viewBox="0 0 24 24"
								aria-hidden="true"
								focusable="false">
								<path
									fill="none"
									stroke="currentColor"
									stroke-width="2"
									stroke-linecap="round"
									d="M6 6l12 12M18 6L6 18"/>
							</svg>
						</button>
					</div>
					<div v-if="sessionTelemetry" class="telemetry-content">
						<div class="telemetry-summary">
							<div
								v-if="telemetryTokenLabel"
								class="telemetry-metric tooltip tip-left telemetry-token-tip"
								:data-tip="telemetryTokenTip">
								<span class="telemetry-label">Tokens</span>
								<span class="telemetry-value telemetry-muted">{{ telemetryTokenLabel }}</span>
							</div>
							<div
								v-if="telemetryContextUsage"
								class="telemetry-metric tooltip tip-left"
								:class="telemetryContextClass"
								:data-tip="telemetryContextTip">
								<span class="telemetry-value">{{ telemetryContextUsage }}</span>
							</div>
							<div
								v-if="telemetryCostLabel"
								class="telemetry-metric tooltip tip-left tip-down telemetry-info telemetry-cost telemetry-cost-tip"
								:data-tip="telemetryCostTip">
								<span class="telemetry-value telemetry-muted">{{ telemetryCostLabel }}</span>
							</div>
							<div
								v-if="telemetryCacheLabel"
								class="telemetry-metric tooltip tip-left"
								:data-tip="telemetryCacheTip">
								<span class="telemetry-label">Cache</span>
								<span class="telemetry-value telemetry-muted">{{ telemetryCacheLabel }}</span>
							</div>
							<span
								v-if="telemetryInfoTip"
								class="control size-xs ghost icon-button icon-ghost tooltip tip-left telemetry-info"
								:data-tip="telemetryInfoTip">
								<svg
									viewBox="0 0 24 24"
									aria-hidden="true"
									focusable="false">
									<path
										fill="currentColor"
										d="M11 17h2v-6h-2v6zm0-8h2V7h-2v2zm1-7C6.92 2 3 5.92 3 11s3.92 9 9 9 9-3.92 9-9-3.92-9-9-9zm0 16c-3.87 0-7-3.13-7-7s3.13-7 7-7 7 3.13 7 7-3.13 7-7 7z"/>
								</svg>
							</span>
						</div>
						<div v-if="sortedTagCategories.length" class="telemetry-tags">
							<select
								v-for="category in sortedTagCategories"
								:key="category.id"
								class="compact-select tooltip"
								:class="{ placeholder: !sessionTagByCategory(category.id)?.value_id }"
								:value="sessionTagByCategory(category.id)?.value_id || '__none__'"
								:data-tip="tagTooltip"
								:aria-label="tagCategoryLabel(category)"
								@change="handleSessionTagChange(category.id, $event)">
								<option value="" disabled>{{ tagCategoryLabel(category) }}</option>
								<option value="__none__">None</option>
								<option
									v-for="tag in category.values || []"
									:key="tag.id"
									:value="tag.id">{{ tag.name }}</option>
							</select>
						</div>
					</div>
				</div>
				<div class="participant-row in-header">
					<div class="participant-list">
						<button
							v-if="showInviteButton"
							class="control size-xs ghost icon-button tooltip participant-invite"
							type="button"
							data-tip="Invite a participant"
							@click="openInviteModal">
							<svg
								viewBox="0 0 24 24"
								aria-hidden="true"
								focusable="false">
								<path
									fill="currentColor"
									d="M11 11V6h2v5h5v2h-5v5h-2v-5H6v-2h5z"/>
							</svg>
						</button>
						<span
							v-for="person in sessionParticipants"
							:key="person.user_id"
							class="control size-xs pill tag"
							:class="{ inactive: !person.active }">
							<span
								class="participant-avatar"
								:style="{ backgroundColor: participantAvatarUrl(person) ? '' : participantAvatarColor(person) }">
								<img
									v-if="participantAvatarUrl(person)"
									:src="participantAvatarUrl(person)"
									alt=""/>
								<span v-else class="participant-initial">{{ participantInitial(person) }}</span>
							</span>
							{{ person.user_name }}
						</span>
						<span v-if="sessionParticipants.length === 0" class="control size-xs pill tag muted">Nobody</span>
					</div>
				</div>
			</div>
			<ConfirmModal
				v-model:open="inviteModalOpen"
				title="Invite participant"
				confirm-label="Invite"
				@confirm="confirmInvite">
				<div class="invite-modal-body">
					<label class="field">
						<span>Select user</span>
						<select v-model="inviteUserId" :disabled="inviteLoading || !availableInviteUsers.length">
							<option value="">Choose a user…</option>
							<option
								v-for="user in availableInviteUsers"
								:key="user.id"
								:value="user.id">{{ user.name || user.email }}</option>
						</select>
					</label>
					<p v-if="!availableInviteUsers.length" class="subtle">Everyone is already a participant.</p>
				</div>
			</ConfirmModal>
			<div v-if="isResizing" class="resize-banner">Resizing…</div>
			<div
				ref="sessionContentRef"
				class="session-content"
				:class="{ 'with-canvases': hasCanvases }">
				<div class="session-log" ref="sessionLogRef">
					<div v-if="hasMoreSessionEvents" class="session-log-more">
						<button
							class="control size-sm ghost"
							type="button"
							:disabled="loadingMoreSessionEvents"
							@click="loadMoreSessionEvents">{{ loadingMoreSessionEvents ? 'Loading…' : 'View older' }}</button>
					</div>
					<SessionEventList
						:events="displayEvents"
						:streaming-content="streamingMessages[selectedSessionId]"
						:streaming-think-text="getThinkText(selectedSessionId)"
						:selected-session-id="selectedSessionId"
						:row-props="eventRowProps"/>
				</div>
				<button
					v-if="hasCanvases"
					class="canvas-resize-handle"
					type="button"
					aria-label="Resize canvas panel"
					@pointerdown="startCanvasResize"></button>
				<SessionCanvasPane
					v-if="hasCanvases"
					ref="canvasPaneRef"
					:tabs="canvasTabs"
					v-model:active-canvas-id="activeCanvasId"
					:width="canvasPaneWidth"/>
			</div>
		</div>
		<div class="composer-panel" ref="composerPanelRef">
			<div class="composer-status" :class="{ active: isThinking }">
				<span
					v-if="isThinking"
					class="thinking-spinner"
					aria-hidden="true"></span>
				<span v-if="isThinking" class="subtle assistant-status-text">Assistant is processing…</span>
				<span v-if="typingLabel" class="subtle typing-indicator inline">{{ typingLabel }}</span>
				<span v-if="isReadOnly" class="subtle">Archived sessions are read-only.</span>
				<span v-else-if="!isCurrentEpoch" class="subtle">Viewing a past epoch · switch to current to send.</span>
			</div>
			<div class="composer">
				<textarea
					v-model="messageInput"
					class="composer-input"
					rows="3"
					placeholder="Write a message…"
					ref="composerInputRef"
					:disabled="isReadOnly"
					@paste="handlePaste"
					@keydown="handleKeydown"
					@input="handleTypingInput"
					@blur="handleTypingBlur"></textarea>
				<div class="composer-actions">
					<div class="composer-actions-left">
						<button
							class="control size-m secondary"
							type="button"
							:disabled="isDisconnected || !canSendMessages"
							:class="{ 'send-progress': !!sendTimer }"
							:style="{ '--send-progress': `${Math.round(sendProgress * 100)}%` }"
							@click="handleSendButton"
							:data-tip="queuedMessages.length ? 'Click to dequeue' : ''">
							{{ queuedMessages.length ? `Queued${queuedMessages.length > 1 ? ` (${queuedMessages.length})` : ''}` : 'Send' }}
						</button>
						<button
							ref="voiceButtonRef"
							class="control size-s ghost icon-button tooltip"
							type="button"
							:disabled="!canSendMessages || isDisconnected"
							:data-tip="voiceTooltip"
							:class="{ active: voiceEnabled, paused: voiceEnabled && ttsPauseVad && ttsIsPlaying, recording: shouldRecord && isSpeaking }"
							@click="toggleVoice">
							<svg
								viewBox="0 0 24 24"
								aria-hidden="true"
								focusable="false">
								<path
									fill="currentColor"
									d="M12 14a3 3 0 0 0 3-3V5a3 3 0 0 0-6 0v6a3 3 0 0 0 3 3zm5-3a5 5 0 0 1-10 0H5a7 7 0 0 0 6 6.92V21h2v-3.08A7 7 0 0 0 19 11h-2z"/>
							</svg>
						</button>
						<button
							class="control size-s ghost icon-button tooltip"
							type="button"
							:disabled="!canSendMessages || isDisconnected"
							:data-tip="ttsEnabled ? 'TTS on (Alt+V)' : 'TTS off (Alt+V)'"
							:class="{ active: ttsEnabled }"
							@click="toggleTts">
							<svg
								viewBox="0 0 24 24"
								aria-hidden="true"
								focusable="false">
								<path
									fill="currentColor"
									d="M3 9v6h4l5 4V5L7 9H3zm13.5 3a4.5 4.5 0 0 0-2.45-4.04v8.08A4.5 4.5 0 0 0 16.5 12zm0-9.5v2.06A8.5 8.5 0 0 1 21 12a8.5 8.5 0 0 1-4.5 7.44v2.06C20.7 19.95 23 16.2 23 12S20.7 4.05 16.5 2.5z"/>
							</svg>
						</button>
						<button
							v-if="isLocked && !isReadOnly"
							class="control size-s ghost danger"
							type="button"
							@click="abortSession">{{ isAborting ? 'Stopping…' : 'Stop reply' }}</button>
						<input
							ref="composerFileInputRef"
							class="file-input-hidden"
							type="file"
							multiple
							@change="handleFileInput"
							:disabled="isReadOnly"/>
						<button
							class="control size-s ghost icon-button attachment-clip tooltip"
							type="button"
							:disabled="isReadOnly"
							:data-tip="attachments.length ? 'Attachments' : 'Attach files'"
							@click="attachments.length ? (attachmentsPopupOpen = !attachmentsPopupOpen) : openAttachmentPicker()">
							<svg
								viewBox="0 0 24 24"
								aria-hidden="true"
								focusable="false">
								<path
									fill="none"
									stroke="currentColor"
									stroke-width="1.6"
									stroke-linecap="round"
									stroke-linejoin="round"
									d="M6 7v9a6 6 0 0 0 12 0V6a4 4 0 0 0-8 0v9a2 2 0 0 0 4 0V9"
									transform="translate(24 0) scale(-1 1)"/>
							</svg>
							<span v-if="attachments.length" class="attachment-badge">{{ attachments.length }}</span>
						</button>
						<div class="attachment-controls">
							<div
								class="drop-target"
								:class="{ active: dropActive, disabled: isReadOnly }"
								@dragenter="handleDropEnter"
								@dragover="handleDropOver"
								@dragleave="handleDropLeave"
								@drop="handleDrop">
								Drop files
							</div>
							<div v-if="attachments.length" class="attachment-counter-wrap">
								<button
									class="control size-xs ghost danger icon-button attachment-clear tooltip"
									type="button"
									data-tip="Clear attachments"
									@click="clearAttachments">
									<svg
										viewBox="0 0 24 24"
										aria-hidden="true"
										focusable="false">
										<path
											fill="currentColor"
											d="M9 3h6l1 2h4v2H4V5h4l1-2zm1 6h2v9h-2V9zm4 0h2v9h-2V9zM7 9h2v9H7V9z"/>
									</svg>
								</button>
								<div
									v-if="attachmentsPopupOpen"
									class="attachment-popup-backdrop"
									@click="attachmentsPopupOpen = false"></div>
								<div
									v-if="attachmentsPopupOpen"
									class="attachment-popup"
									@click.stop>
									<div class="attachment-popup-header">
										<span>{{ attachments.length }} attachment{{ attachments.length === 1 ? '' : 's' }}</span>
										<div class="attachment-popup-actions">
											<button
												class="control size-xs ghost danger icon-button tooltip"
												type="button"
												data-tip="Remove all attachments"
												@click="clearAttachments">
												<svg
													viewBox="0 0 24 24"
													aria-hidden="true"
													focusable="false">
													<path
														fill="currentColor"
														d="M9 3h6l1 2h4v2H4V5h4l1-2zm1 6h2v9h-2V9zm4 0h2v9h-2V9zM7 9h2v9H7V9z"/>
												</svg>
											</button>
											<button
												class="control size-xs ghost tooltip"
												type="button"
												data-tip="Close"
												@click="attachmentsPopupOpen = false">Close</button>
										</div>
									</div>
									<div class="attachment-popup-list">
										<div
											v-for="entry in attachments"
											:key="entry.id"
											class="attachment-popup-item">
											<span class="attachment-popup-thumb">
												<img
													v-if="entry.preview"
													:src="entry.preview"
													:alt="entry.name"/>
												<svg
													v-else
													viewBox="0 0 24 24"
													aria-hidden="true"
													focusable="false">
													<path
														fill="currentColor"
														d="M6 4h7l5 5v11a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2zm6 1.5V9h3.5L12 5.5z"/>
												</svg>
											</span>
											<span class="attachment-popup-name" :title="entry.name">{{ entry.name }}</span>
											<button
												class="control size-xs ghost"
												type="button"
												@click="removeAttachment(entry.id)">Remove</button>
										</div>
									</div>
								</div>
							</div>
						</div>
					</div>
					<div class="composer-actions-right">
						<select
							v-if="selectedSession"
							v-model="sessionAssistantSelection"
							class="compact-select"
							:disabled="isReadOnly"
							@change="updateSessionAssistant">
							<option value="">Default assistant</option>
							<optgroup v-if="workspaceAssistants.length" label="Workspace assistants">
								<option
									v-for="assistant in workspaceAssistants"
									:key="assistant.id"
									:value="assistant.id">{{ assistant.name }}·{{ assistant.model_name || 'Unassigned' }}</option>
							</optgroup>
							<optgroup v-if="tenantAssistants.length" label="Tenant assistants">
								<option
									v-for="assistant in tenantAssistants"
									:key="assistant.id"
									:value="assistant.id">{{ assistant.name }}·{{ assistant.model_name || 'Unassigned' }}</option>
							</optgroup>
						</select>
					</div>
				</div>
			</div>
		</div>
		<div
			v-if="status"
			class="toast"
			:class="statusTone">{{ status }}</div>
		<div
			v-if="sessionPruneToast"
			class="toast session-prune-toast">{{ sessionPruneToast }}</div>
	</main>
	<ConfirmModal
		v-model:open="deleteConfirmOpen"
		title="Delete session"
		message="Delete this session? This cannot be undone."
		confirm-label="Delete"
		:destructive="true"
		@confirm="confirmDeleteSession"
		@cancel="cancelDeleteSession"/>
	<ConfirmModal
		v-model:open="closeConfirmOpen"
		title="Close session"
		message="Close this session? It will stay in history but become inactive."
		confirm-label="Close"
		@confirm="confirmCloseSession"
		@cancel="closeConfirmOpen = false"/>
	<div
		v-if="imagePreviewOpen"
		class="image-modal"
		@click.self="closeImagePreview">
		<button
			class="control size-s ghost image-modal-close"
			type="button"
			@click="closeImagePreview">Close</button>
		<img
			v-if="imagePreviewUrl"
			:src="imagePreviewUrl"
			alt="Attachment preview"/>
	</div>
	<div
		v-if="workerPaneOpen"
		class="sheet-backdrop"
		@click.self="workerPaneOpen = false">
		<div class="sidepane">
			<button
				class="control size-s ghost icon-button icon-ghost sidepane-close"
				type="button"
				aria-label="Close"
				@click="workerPaneOpen = false">
				<svg
					viewBox="0 0 24 24"
					aria-hidden="true"
					focusable="false">
					<path
						fill="currentColor"
						d="M18.3 5.71a1 1 0 0 0-1.41 0L12 10.59 7.11 5.7a1 1 0 0 0-1.41 1.42L10.59 12l-4.89 4.89a1 1 0 1 0 1.41 1.41L12 13.41l4.89 4.89a1 1 0 0 0 1.41-1.41L13.41 12l4.89-4.89a1 1 0 0 0 0-1.4z"/>
				</svg>
			</button>
			<h2>Worker runs</h2>
			<p v-if="!selectedWorkerProgress" class="subtle">No worker runs yet.</p>
			<div v-else class="stack">
				<p class="subtle">
					{{ selectedWorkerProgress.done || 0 }}/{{ selectedWorkerProgress.total || 0 }} done
					<span v-if="selectedWorkerProgress.failed">· {{ selectedWorkerProgress.failed }} failed</span>
				</p>
				<div class="panel compact" v-if="Array.isArray(selectedWorkerProgress.children)">
					<div
						v-for="child in selectedWorkerProgress.children"
						:key="child.session_id"
						class="list-row worker-list">
						<div>
							<strong>{{ child.assistant_name || 'Worker' }}</strong>
							<p v-if="child.title" class="subtle">{{ child.title }}</p>
						</div>
						<span
							class="control size-2xs pill tag worker-state"
							:class="workerStatusPillClass(child.status)">{{ workerStatusLabel(child.status || 'running') }}</span>
						<button
							class="control size-xs ghost"
							type="button"
							@click="openWorkerSession(child.session_id)">View</button>
					</div>
				</div>
			</div>
		</div>
	</div>
	<div
		v-if="renameOpen"
		class="sheet-backdrop"
		@click.self="renameOpen = false">
		<div class="sidepane">
			<button
				class="control size-s ghost icon-button icon-ghost sidepane-close"
				type="button"
				aria-label="Close"
				@click="renameOpen = false">
				<svg
					viewBox="0 0 24 24"
					aria-hidden="true"
					focusable="false">
					<path
						fill="currentColor"
						d="M18.3 5.71a1 1 0 0 0-1.41 0L12 10.59 7.11 5.7a1 1 0 0 0-1.41 1.42L10.59 12l-4.89 4.89a1 1 0 1 0 1.41 1.41L12 13.41l4.89 4.89a1 1 0 0 0 1.41-1.41L13.41 12l4.89-4.89a1 1 0 0 0 0-1.4z"/>
				</svg>
			</button>
			<h2>Rename session</h2>
			<div class="stack">
				<label class="field">
					<span>Title</span>
					<input v-model="renameValue" type="text"/>
				</label>
				<div class="row-actions">
					<button
						class="control size-m secondary"
						type="button"
						@click="renameSession">Save</button>
					<button
						class="control size-m ghost"
						type="button"
						@click="renameOpen = false">Cancel</button>
				</div>
			</div>
		</div>
	</div>
	<div
		v-if="techDetailsOpen"
		class="sheet-backdrop"
		@click.self="closeTechnicalDetails">
		<div class="sidepane">
			<button
				class="control size-s ghost icon-button icon-ghost sidepane-close"
				type="button"
				aria-label="Close"
				@click="closeTechnicalDetails">
				<svg
					viewBox="0 0 24 24"
					aria-hidden="true"
					focusable="false">
					<path
						fill="currentColor"
						d="M18.3 5.71a1 1 0 0 0-1.41 0L12 10.59 7.11 5.7a1 1 0 0 0-1.41 1.42L10.59 12l-4.89 4.89a1 1 0 1 0 1.41 1.41L12 13.41l4.89 4.89a1 1 0 0 0 1.41-1.41L13.41 12l4.89-4.89a1 1 0 0 0 0-1.4z"/>
				</svg>
			</button>
			<div class="title-row">
				<h2>Configuration</h2>
				<span v-if="techScopeSaving" class="subtle">Saving…</span>
			</div>
			<p class="subtle">Current scopes and MCP servers for this workflow instance.</p>
			<div class="stack">
				<p v-if="techDetailsLoading" class="subtle">Loading technical details…</p>
				<p v-else-if="techDetailsError" class="subtle">{{ techDetailsError }}</p>
				<template v-else>
					<div class="field tech-field">
						<span class="tech-key">Scopes</span>
						<ScopeViewer
							:scopes="techDetails?.available_scopes || []"
							:allow-scopes="techDetails?.allow_scopes || []"
							:deny-scopes="techDetails?.deny_scopes || []"
							:editable="!techScopeSaving && !isArchived"
							:allow-dynamic="true"
							@update:scopes="handleScopeUpdate"/>
					</div>
					<div class="field tech-field">
						<div class="tech-key-row">
							<span class="tech-key">MCP tools</span>
							<span
								v-if="techDetails?.hide_denied_tools"
								class="control size-xs pill tag tooltip"
								data-tip="Denied tools are hidden from the LLM unless already used in this session.">Hide denied</span>
						</div>
						<div class="tool-permissions" v-if="techDetails?.server_tools?.length">
							<div
								v-for="server in techDetails.server_tools"
								:key="server.server_id"
								class="tool-server">
								<button
									class="tool-server-toggle"
									type="button"
									@click="toggleToolServer(server.server_id)">
									<span class="tool-server-title">{{ server.server_name || 'Server' }}</span>
									<span class="tool-server-icon" aria-hidden="true">
										<svg
											v-if="toolServerOpen(server.server_id)"
											viewBox="0 0 24 24"
											focusable="false">
											<path
												fill="currentColor"
												d="M7.4 14.6 12 10l4.6 4.6 1.4-1.4-6-6-6 6z"/>
										</svg>
										<svg
											v-else
											viewBox="0 0 24 24"
											focusable="false">
											<path
												fill="currentColor"
												d="M7.4 9.4 12 14l4.6-4.6 1.4 1.4-6 6-6-6z"/>
										</svg>
									</span>
								</button>
								<div v-if="toolServerOpen(server.server_id)" class="tool-list">
									<div
										v-for="tool in server.tools"
										:key="tool.id"
										class="tool-row">
										<div class="tool-name">
											<span class="tool-name-text">{{ tool.tool_alias || tool.tool_name }}</span>
											<span
												v-if="techDetails?.hide_denied_tools && tool.sticky"
												class="control size-xs pill tag tooltip"
												data-tip="These tools stay visible even when denied because they've been used already.">Sticky</span>
										</div>
										<div class="tool-meta">
											<span class="tool-scopes" v-if="tool.scopes && tool.scopes.length">{{ tool.scopes.join(', ') }}</span>
											<span class="tool-scopes" v-else>no scopes</span>
										</div>
										<span class="tool-decision" :class="tool.decision">{{ tool.decision }}</span>
									</div>
								</div>
							</div>
						</div>
						<span v-else class="subtle">No tools found.</span>
						<div class="row-actions tech-server-row" v-if="techServerOptions.length">
							<div class="field">
								<span class="tech-key">Add MCP server</span>
							</div>
							<div class="tech-server-controls">
								<select
									v-model="techServerSelection"
									class="tech-server-select"
									:disabled="techServerLoading">
									<option value="">Select server</option>
									<option
										v-for="server in techServerOptions"
										:key="server.id"
										:value="server.id">{{ server.name }}</option>
								</select>
								<button
									class="control size-s"
									type="button"
									:disabled="!techServerSelection || techServerLoading"
									@click="addTechServer">Add</button>
							</div>
						</div>
					</div>
				</template>
			</div>
		</div>
	</div>
	<div
		v-if="promptOpen"
		class="sheet-backdrop"
		@click.self="closePromptPreview">
		<div class="sidepane">
			<button
				class="control size-s ghost icon-button icon-ghost sidepane-close"
				type="button"
				aria-label="Close"
				@click="closePromptPreview">
				<svg
					viewBox="0 0 24 24"
					aria-hidden="true"
					focusable="false">
					<path
						fill="currentColor"
						d="M18.3 5.71a1 1 0 0 0-1.41 0L12 10.59 7.11 5.7a1 1 0 0 0-1.41 1.42L10.59 12l-4.89 4.89a1 1 0 1 0 1.41 1.41L12 13.41l4.89 4.89a1 1 0 0 0 1.41-1.41L13.41 12l4.89-4.89a1 1 0 0 0 0-1.4z"/>
				</svg>
			</button>
			<div class="title-row">
				<div>
					<h2>Prompt preview</h2>
					<p v-if="promptNodeLabel" class="subtle">Node: {{ promptNodeLabel }}</p>
				</div>
				<div class="row-actions">
					<button
						class="control size-xs ghost"
						type="button"
						:disabled="!promptText"
						@click="copyPromptText">Copy</button>
				</div>
			</div>
			<p v-if="promptLoading" class="subtle">Loading prompt…</p>
			<p v-else-if="promptError" class="subtle">{{ promptError }}</p>
			<pre v-else-if="promptText" class="code-block">{{ promptText }}</pre>
			<p v-else class="subtle">No prompt available.</p>
		</div>
	</div>
	<div
		v-if="inspectOpen"
		class="sheet-backdrop"
		@click.self="closeInspect">
		<div class="sidepane io-pane">
			<button
				class="control size-s ghost icon-button icon-ghost sidepane-close"
				type="button"
				aria-label="Close"
				@click="closeInspect">
				<svg
					viewBox="0 0 24 24"
					aria-hidden="true"
					focusable="false">
					<path
						fill="currentColor"
						d="M18.3 5.71a1 1 0 0 0-1.41 0L12 10.59 7.11 5.7a1 1 0 0 0-1.41 1.42L10.59 12l-4.89 4.89a1 1 0 1 0 1.41 1.41L12 13.41l4.89 4.89a1 1 0 0 0 1.41-1.41L13.41 12l4.89-4.89a1 1 0 0 0 0-1.4z"/>
				</svg>
			</button>
			<div class="title-row">
				<div>
					<h2>{{ inspectTitle }}</h2>
					<p class="subtle">Tool input/output details.</p>
				</div>
				<div class="row-actions">
					<button
						class="control size-xs ghost"
						type="button"
						@click="inspectMode = inspectMode === 'pretty' ? 'raw' : 'pretty'">{{ inspectMode === 'pretty' ? 'Raw JSON' : 'Pretty view' }}</button>
				</div>
			</div>
			<div class="inspect-tabs">
				<button
					class="control size-xs"
					:class="inspectTab === 'input' ? 'active' : 'secondary'"
					type="button"
					@click="inspectTab = 'input'">Input</button>
				<button
					class="control size-xs"
					:class="inspectTab === 'output' ? 'active' : 'secondary'"
					type="button"
					:disabled="!inspectHasOutput"
					@click="inspectTab = 'output'">Output</button>
			</div>
			<div v-if="inspectTab === 'input'" class="inspect-body">
				<div v-if="inspectMode === 'pretty'" class="inspect-tree">
					<JsonTree :value="inspectInput"/>
				</div>
				<pre v-else class="code-block">{{ inspectInputRawDisplay }}</pre>
				<button
					v-if="inspectMode === 'raw' && inspectInputTruncated"
					class="control size-xs ghost"
					type="button"
					@click="inspectInputExpanded = !inspectInputExpanded">{{ inspectInputExpanded ? 'Show less' : 'Show more' }}</button>
			</div>
			<div v-else class="inspect-body">
				<div v-if="!inspectHasOutput" class="subtle">No output available.</div>
				<template v-else>
					<div v-if="inspectMode === 'pretty'" class="inspect-tree">
						<JsonTree :value="inspectOutput"/>
					</div>
					<pre v-else class="code-block">{{ inspectOutputRawDisplay }}</pre>
					<button
						v-if="inspectMode === 'raw' && inspectOutputTruncated"
						class="control size-xs ghost"
						type="button"
						@click="inspectOutputExpanded = !inspectOutputExpanded">{{ inspectOutputExpanded ? 'Show less' : 'Show more' }}</button>
				</template>
			</div>
		</div>
	</div>
	<div
		v-if="traceOpen"
		class="sheet-backdrop trace-backdrop"
		@click.self="collapseTracePanel">
		<div class="sidepane trace-pane">
			<button
				class="control size-s ghost icon-button icon-ghost sidepane-close"
				type="button"
				aria-label="Close"
				@click="collapseTracePanel">
				<svg
					viewBox="0 0 24 24"
					aria-hidden="true"
					focusable="false">
					<path
						fill="currentColor"
						d="M18.3 5.71a1 1 0 0 0-1.41 0L12 10.59 7.11 5.7a1 1 0 0 0-1.41 1.42L10.59 12l-4.89 4.89a1 1 0 1 0 1.41 1.41L12 13.41l4.89 4.89a1 1 0 0 0 1.41-1.41L13.41 12l4.89-4.89a1 1 0 0 0 0-1.4z"/>
				</svg>
			</button>
			<button
				class="control size-s ghost danger icon-button icon-ghost sidepane-close sidepane-stop"
				type="button"
				aria-label="Stop trace"
				@click="stopTrace()">
				<svg
					viewBox="0 0 24 24"
					aria-hidden="true"
					focusable="false">
					<path
						fill="currentColor"
						d="M6 6h12v12H6z"/>
				</svg>
			</button>
			<div class="title-row">
				<div>
					<h2>Trace</h2>
					<p class="subtle">Raw runtime events for this session.</p>
				</div>
			</div>
			<div class="trace-list">
				<p v-if="traceDropped" class="subtle">Dropped {{ traceDropped }} older entries.</p>
				<p v-if="traceEvents.length === 0" class="subtle">No trace events yet.</p>
				<div
					v-for="trace in traceEvents"
					:key="trace._key"
					class="trace-row"
					:class="trace.severity || 'info'">
					<div class="trace-body">
						<div class="trace-head">
							<p class="trace-message">{{ trace.message }}</p>
							<button
								v-if="trace.details"
								class="control size-2xs ghost trace-details-toggle"
								type="button"
								@click="traceDetailsOpen[trace._key] = !traceDetailsOpen[trace._key]">{{ traceDetailsOpen[trace._key] ? 'Hide details' : 'Show details' }}</button>
						</div>
						<span class="subtle trace-time">{{ formatTraceTime(trace.timestamp) }}</span>
						<pre v-if="trace.details && traceDetailsOpen[trace._key]" class="code-block">{{ JSON.stringify(trace.details, null, 2) }}</pre>
					</div>
				</div>
			</div>
		</div>
	</div>
	<button
		v-if="traceCollapsed && traceActive"
		class="control size-s trace-tab"
		type="button"
		@click="openTracePanel">
		<span>Trace</span>
		<span v-if="traceCount" class="trace-count">{{ traceCount }}</span>
		<svg
			viewBox="0 0 24 24"
			aria-hidden="true"
			focusable="false">
			<path
				fill="currentColor"
				d="M14.71 6.71a1 1 0 0 0-1.42 0L9 11l4.29 4.29a1 1 0 1 0 1.42-1.42L11.83 11l2.88-2.88a1 1 0 0 0 0-1.41z"/>
		</svg>
	</button>
</template>
<style scoped>
.telemetry-content {
	display: flex;
	align-items: center;
	gap: 0.45rem;
	flex-wrap: wrap;
	flex-direction: column;
}

.header-actions {
	display: flex;
	flex-direction: column;
	align-items: flex-end;
	gap: 0.35rem;
}

.session-prune-toast {
	bottom: calc(var(--space-l) + 4.25rem);
	background: var(--bg-panel-strong);
	border-color: var(--accent);
	max-width: min(28rem, calc(100vw - 2 * var(--space-l)));
}

.telemetry-summary {
	display: flex;
	align-items: center;
	flex-wrap: wrap;
	gap: 0.85rem;
	margin-left: auto;
}

.telemetry-tags {
	display: inline-flex;
	flex-wrap: wrap;
	align-items: center;
	align-self: end;
	gap: 0.35rem;
	margin-top: 0.35rem;
}

.telemetry-tags .compact-select.placeholder {
	color: var(--text-muted);
}

.telemetry-metric {
	display: inline-flex;
	align-items: baseline;
	gap: 0.4rem;
	padding: 0.1rem 0;
}

.telemetry-label {
	font-size: 0.78rem;
	text-transform: uppercase;
	letter-spacing: 0.06em;
	opacity: 0.7;
}

.telemetry-value {
	font-size: 0.85rem;
	font-weight: 600;
}

.telemetry-muted {
	opacity: 0.7;
}

.telemetry-info svg {
	width: 16px;
	height: 16px;
}

.telemetry-warning {
	color: #9a5a00;
	border-color: rgba(154, 90, 0, 0.35);
}

.telemetry-user-warning {
	color: #1f6b5a;
	border-color: rgba(31, 107, 90, 0.35);
}

.telemetry-context-ok {
	color: #62c9a8;
}

.telemetry-context-system {
	color: #f2b563;
}

.telemetry-context-user {
	color: #f28b8b;
}

.telemetry-context-critical {
	color: #f28b8b;
}

.log-row.context {
	background: rgba(148, 163, 184, 0.08);
	border-color: rgba(148, 163, 184, 0.2);
}

.context-block {
	border: 1px dashed rgba(148, 163, 184, 0.4);
	border-radius: 10px;
	padding: 0.6rem 0.8rem;
	background: rgba(15, 23, 42, 0.2);
}

.context-block summary {
	cursor: pointer;
	font-weight: 600;
	color: var(--text-muted);
	list-style: none;
}

.context-block summary::-webkit-details-marker {
	display: none;
}

.context-body {
	margin-top: 0.6rem;
}

.tech-server-row {
	flex-direction: column;
	align-items: flex-start;
	gap: 0.75rem;
	margin-top: 0.75rem;
}

.tech-server-controls {
	display: flex;
	align-items: center;
	gap: 0.75rem;
}

.tech-server-select {
	min-height: 34px;
	line-height: 1.2;
	padding: 0.4rem 0.6rem;
}

.icon-button.recording {
	color: #ff8a8a;
	background: rgba(255, 80, 80, 0.12);
	box-shadow: 0 0 0 2px rgba(255, 120, 120, 0.2);
	animation: micPulse 1.2s ease-in-out infinite;
}

@keyframes micPulse {
	0% {
		box-shadow: 0 0 0 0 rgba(255, 120, 120, 0.35);
	}
	70% {
		box-shadow: 0 0 0 10px rgba(255, 120, 120, 0);
	}
	100% {
		box-shadow: 0 0 0 0 rgba(255, 120, 120, 0);
	}
}

.session-content {
	display: flex;
	min-height: 0;
	flex: 1;
}

.session-content.with-canvases {
	gap: 0;
}

.session-log {
	flex: 1 1 auto;
	min-width: 0;
}

.canvas-resize-handle {
	flex: 0 0 12px;
	align-self: stretch;
	margin: 0 0.25rem;
	border: 0;
	padding: 0;
	background: transparent;
	cursor: col-resize;
	position: relative;
}

.canvas-resize-handle::before {
	content: '';
	position: absolute;
	top: 0.75rem;
	bottom: 0.75rem;
	left: 50%;
	width: 2px;
	transform: translateX(-50%);
	border-radius: 999px;
	background: rgba(148, 163, 184, 0.24);
}

.canvas-resize-handle:hover::before, body.is-resizing-canvas-pane .canvas-resize-handle::before {
	background: rgba(96, 165, 250, 0.5);
}

.session-log-more {
	display: flex;
	justify-content: center;
	padding: 0.5rem 0 0.75rem;
}

.telemetry-context-approaching {
	color: #f5d07a;
}

.participant-list {
	display: flex;
	flex-wrap: wrap;
	gap: 0.4rem;
}

.participant-invite {
	width: 26px;
	height: 26px;
	padding: 0;
	border-radius: 999px;
}

.worker-progress {
	margin-top: 0.4rem;
}

.worker-progress .control {
	padding-left: 0;
	padding-right: 0;
}

.invite-modal-body {
	display: flex;
	flex-direction: column;
	gap: 0.75rem;
}

.inspect-tabs {
	display: flex;
	gap: 0.5rem;
	margin: 0.75rem 0 1rem;
}

.inspect-body {
	display: flex;
	flex-direction: column;
	gap: 0.75rem;
}

.inspect-tree {
	border-radius: 12px;
	border: 1px solid rgba(148, 163, 184, 0.2);
	padding: 0.75rem;
	background: rgba(15, 23, 42, 0.25);
}

.decision-template, .tool-output-template {
	margin-top: 0.5rem;
}

.tool-output-toggle {
	margin-top: 0.5rem;
}

.sidepane.io-pane {
	width: 90vw;
	max-width: 90vw;
}

@media (max-width: 720px) {
	.sidepane.io-pane {
		width: 100vw;
		max-width: 100vw;
	}
}

.participant-row {
	display: flex;
	justify-content: flex-end;
}

.participant-avatar {
	display: inline-flex;
	align-items: center;
	justify-content: center;
	width: 18px;
	height: 18px;
	border-radius: 50%;
	overflow: hidden;
	margin-right: 0.35rem;
	color: #fff;
	font-size: 0.65rem;
	font-weight: 600;
}

.participant-avatar img {
	width: 100%;
	height: 100%;
	object-fit: cover;
}

.telemetry-critical {
	color: #9b1c1c;
	border-color: rgba(155, 28, 28, 0.35);
}

.worker-state {
	margin-left: auto;
}
</style>
