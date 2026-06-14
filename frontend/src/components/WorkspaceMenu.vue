<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
const props = defineProps({
	workspaceId: { type: String, required: true },
	workspaceName: { type: String, default: '' },
	sessions: { type: Array, default: () => [] },
	applications: { type: Array, default: () => [] },
	pages: { type: Array, default: () => [] },
	selectedSessionId: { type: String, default: '' },
	selectedAppId: { type: String, default: '' },
	selectedPageSlug: { type: String, default: '' },
	sessionListPending: { type: Boolean, default: false },
	collapsed: { type: Boolean, default: false },
	connectionStatus: { type: Object, default: () => ({ code: 'connecting', label: 'Connecting' }) },
	channels: { type: Array, default: () => [] }
})
const emit = defineEmits(
	['select', 'create', 'toggleHistory', 'toggleCollapse', 'delete', 'open-app']
)
const route = useRoute()
const router = useRouter()
const maybeCollapse = () => {
	if (typeof window !== 'undefined' && window.innerWidth <= 900 && !props.collapsed) {
		emit('toggleCollapse')
	}
}
const channelMenuOpen = ref(false)
const channelMenuRef = ref(null)
const startSession = (channel = null) => {
	emit('create', channel)
	channelMenuOpen.value = false
	maybeCollapse()
}
const toggleChannelMenu = () => {
	if (!props.channels.length) {
		return
	}
	channelMenuOpen.value = !channelMenuOpen.value
}
const closeChannelMenu = () => {
	channelMenuOpen.value = false
}
const availableChannels = computed(() => props.channels || [])
const hasAvailableChannels = computed(() => availableChannels.value.length > 0)
const handleDocumentClick = (event) => {
	if (!channelMenuOpen.value) {
		return
	}
	if (channelMenuRef.value?.contains(event.target)) {
		return
	}
	closeChannelMenu()
}
const handleDocumentKeydown = (event) => {
	if (event.key === 'Escape') {
		closeChannelMenu()
	}
}
const toggleHistory = () => {
	emit('toggleHistory')
	maybeCollapse()
}
const openApp = (appId) => {
	emit('open-app', appId)
	maybeCollapse()
}
const openPage = (slug) => {
	if (!slug) {
		return
	}
	router.push(`/workspace/${props.workspaceId}/pages/${slug}`)
	maybeCollapse()
}
const toggleCollapse = () => emit('toggleCollapse')
const handleSelect = (id) => {
	emit('select', id)
	maybeCollapse()
}
const openSettings = () => {
	router.push(`/workspace/${props.workspaceId}`)
	maybeCollapse()
}
const openRecordings = () => {
	router.push(`/workspace/${props.workspaceId}/recordings`)
	maybeCollapse()
}
const openLogs = () => {
	router.push(`/workspace/${props.workspaceId}/logs`)
	maybeCollapse()
}
const goToTenant = () => {
	router.push('/tenant')
	maybeCollapse()
}
const attentionState = (session) => {
	const needsAction = session?.status && session.status !== 'ACTIVE'
	if (!needsAction) {
		return ''
	}
	const participants = Array.isArray(session.participants) ? session.participants : []
	const activeCount = participants.filter((person) => person.active).length
	return activeCount > 0 ? 'attention-warn' : 'attention-urgent'
}
const sessionLabel = (session) => session.title || session.workflow_definition_name || 'Session'
const sessionChannelLabel = (session) => {
	const channelName = String(session?.channel_name || '').trim()
	return channelName || ''
}
const maxAvatars = 4
const participantStack = (session) => {
	const participants = Array.isArray(session?.participants) ? session.participants : []
	const activeParticipants = participants.filter((participant) => participant.active)
	const visible = activeParticipants.slice(0, maxAvatars)
	const extra = activeParticipants.length - visible.length
	return { visible, extra }
}
const participantName = (participant) => participant?.user_name || 'User'
const participantInitial = (participant) => {
	const name = participantName(participant)
	return name ? name.trim().charAt(0).toUpperCase() : '?'
}
const avatarPalette = [
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
const hashSeed = (value) => {
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
const avatarColor = (participant) => {
	const seed = hashSeed(participant?.user_id || '')
	return avatarPalette[seed % avatarPalette.length]
}
const sessionInitial = (session) => {
	const label = sessionLabel(session)
	return label ? label.trim().charAt(0).toUpperCase() : '#'
}
const applicationLabel = (app) => app?.display_name || app?.app_name || app?.app_uri || 'Application'
const pageLabel = (page) => page?.label || page?.name || page?.slug || 'Page'
const pageInitial = (page) => {
	const label = pageLabel(page)
	return label ? label.trim().charAt(0).toUpperCase() : 'P'
}
const pageIcon = (page) => page?.icon_svg || ''
const isSessionVisible = (session) => {
	if (!session) {
		return false
	}
	const visibility = (session.visibility || '').toUpperCase()
	if (visibility === 'HIDDEN') {
		return false
	}
	if (visibility === 'FLEXIBLE') {
		return !session.locked
	}
	return true
}
const sortedSessions = computed(() => (props.sessions || []).filter(isSessionVisible))
const sortedApplications = computed(() => [...(props.applications || [])].sort((a, b) => applicationLabel(a).localeCompare(applicationLabel(b))))
const sortedPages = computed(() => (props.pages || []).filter((page) => page?.menu_visible && page?.released)
	.sort((a, b) => pageLabel(a).localeCompare(pageLabel(b))))
const isSettingsActive = computed(() => route.name === 'workspace-detail')
const isHistoryActive = computed(() => route.name === 'workspace-history')
const isRecordingsActive = computed(() => route.name === 'workspace-recordings')
const isLogsActive = computed(() => route.name === 'workspace-logs')
onMounted(() => {
	if (typeof document === 'undefined') {
		return
	}
	document.addEventListener('click', handleDocumentClick)
	document.addEventListener('keydown', handleDocumentKeydown)
})
onBeforeUnmount(() => {
	if (typeof document === 'undefined') {
		return
	}
	document.removeEventListener('click', handleDocumentClick)
	document.removeEventListener('keydown', handleDocumentKeydown)
})
</script>
<template>
	<aside class="workspace-menu" :class="{ collapsed }">
		<div class="menu-top">
			<div v-if="!collapsed" class="menu-title">
				<span>{{ workspaceName || 'Workspace' }}</span>
				<span class="control size-xs status-pill" :class="connectionStatus.code">{{ connectionStatus.label }}</span>
			</div>
			<div v-else class="menu-status">
				<span
					class="control size-xs status-pill icon-only tooltip"
					:class="connectionStatus.code"
					:data-tip="connectionStatus.label"></span>
			</div>
		</div>
		<div class="menu-actions">
			<div class="menu-channel-group" ref="channelMenuRef">
				<button
					class="control primary menu-action menu-button tooltip"
					:class="{ 'split-main': hasAvailableChannels }"
					type="button"
					@click="startSession()"
					data-tip="Start conversation">
					<span class="menu-icon">
						<svg
							viewBox="0 0 24 24"
							aria-hidden="true"
							focusable="false">
							<path fill="currentColor" d="M11 5h2v14h-2zM5 11h14v2H5z"/>
						</svg>
					</span>
					<span class="menu-text">Start conversation</span>
				</button>
				<button
					v-if="!collapsed && hasAvailableChannels"
					class="control primary menu-action menu-button channel-toggle"
					:class="{ active: channelMenuOpen }"
					type="button"
					aria-label="Show channel options"
					:aria-expanded="channelMenuOpen ? 'true' : 'false'"
					@click.stop="toggleChannelMenu">
					<span class="menu-icon">
						<svg
							viewBox="0 0 24 24"
							aria-hidden="true"
							focusable="false">
							<path fill="currentColor" d="M7 10l5 5 5-5z"/>
						</svg>
					</span>
				</button>
				<div v-if="!collapsed && channelMenuOpen && hasAvailableChannels" class="menu-channel-list">
					<button
						v-for="channel in availableChannels"
						:key="channel.id"
						class="control ghost menu-channel-item"
						type="button"
						@click="startSession(channel)"><span class="menu-channel-name">{{ channel.name }}</span></button>
				</div>
			</div>
			<button
				class="control ghost menu-action menu-button tooltip"
				:class="{ active: isSettingsActive }"
				type="button"
				@click="openSettings"
				data-tip="Dashboard">
				<span class="menu-icon">
					<svg
						viewBox="0 0 24 24"
						aria-hidden="true"
						focusable="false">
						<path
							fill="currentColor"
							d="M12 8.5a3.5 3.5 0 1 0 0 7 3.5 3.5 0 0 0 0-7zm8.94 3.5c0-.5-.05-1-.14-1.48l2.04-1.6-2-3.46-2.45.96a7.8 7.8 0 0 0-2.56-1.48L15.5 2h-4l-.33 2.44c-.9.28-1.76.73-2.56 1.48l-2.45-.96-2 3.46 2.04 1.6A7.2 7.2 0 0 0 3.06 12c0 .5.05 1 .14 1.48l-2.04 1.6 2 3.46 2.45-.96c.8.75 1.66 1.2 2.56 1.48L11.5 22h4l.33-2.44c.9-.28 1.76-.73 2.56-1.48l2.45.96 2-3.46-2.04-1.6c.09-.48.14-.98.14-1.48z"/>
					</svg>
				</span>
				<span class="menu-text">Dashboard</span>
			</button>
			<button
				class="control ghost menu-action menu-button tooltip"
				:class="{ active: isRecordingsActive }"
				type="button"
				@click="openRecordings"
				data-tip="Recordings">
				<span class="menu-icon">
					<svg
						viewBox="0 0 24 24"
						aria-hidden="true"
						focusable="false">
						<path
							fill="currentColor"
							d="M12 3a5 5 0 0 0-1 9.9V19a1 1 0 0 0 2 0v-6.1A5 5 0 0 0 12 3zm0 2a3 3 0 0 1 0 6 3 3 0 0 1 0-6zm6 6h2a8 8 0 0 1-6.4 7.84l-.4-1.96A6 6 0 0 0 18 11zm-16 0a6 6 0 0 0 4.8 5.88l-.4 1.96A8 8 0 0 1 2 11h2z"/>
					</svg>
				</span>
				<span class="menu-text">Recordings</span>
			</button>
			<button
				class="control ghost menu-action menu-button tooltip"
				:class="{ active: isLogsActive }"
				type="button"
				@click="openLogs"
				data-tip="MCP logs">
				<span class="menu-icon">
					<svg
						viewBox="0 0 24 24"
						aria-hidden="true"
						focusable="false">
						<path
							fill="currentColor"
							d="M4 4h16v4H4V4zm0 6h16v4H4v-4zm0 6h10v4H4v-4z"/>
					</svg>
				</span>
				<span class="menu-text">Logs</span>
			</button>
			<a
				v-for="app in sortedApplications"
				:key="app.id"
				class="control ghost menu-action menu-button tooltip"
				:class="{ active: app.id === selectedAppId }"
				:href="`/workspace/${workspaceId}/apps/${app.id}`"
				@click.prevent="openApp(app.id)"
				:data-tip="applicationLabel(app)">
				<span
					class="menu-icon app-icon"
					v-if="app.icon_svg"
					v-html="app.icon_svg"></span>
				<span class="menu-icon app-icon" v-else>
					<svg
						viewBox="0 0 24 24"
						aria-hidden="true"
						focusable="false">
						<path
							fill="currentColor"
							d="M4 5h6v6H4V5zm10 0h6v6h-6V5zM4 13h6v6H4v-6zm10 0h6v6h-6v-6z"/>
					</svg>
				</span>
				<span class="menu-text">{{ applicationLabel(app) }}</span>
			</a>
			<button
				class="control ghost menu-action menu-button tooltip"
				:class="{ active: isHistoryActive }"
				type="button"
				@click="toggleHistory"
				data-tip="Session history">
				<span class="menu-icon">
					<svg
						viewBox="0 0 24 24"
						aria-hidden="true"
						focusable="false">
						<path
							fill="currentColor"
							d="M13 3a9 9 0 1 0 8.95 10h-2.06a7 7 0 1 1-2.08-6.38L15 9h7V2l-2.64 2.64A8.96 8.96 0 0 0 13 3zm-1 4h2v6l4 2-1 1.7-5-2.7V7z"/>
					</svg>
				</span>
				<span class="menu-text">Session history</span>
			</button>
		</div>
		<div class="menu-divider" role="presentation"></div>
		<div v-if="sortedPages.length" class="menu-section installed-pages">
			<div v-if="!collapsed" class="menu-section-title">Installed pages</div>
			<div class="menu-list">
				<button
					v-for="page in sortedPages"
					:key="page.id"
					class="control ghost menu-action menu-button"
					:class="{ active: page.slug === selectedPageSlug }"
					type="button"
					@click="openPage(page.slug)">
					<span class="menu-icon" aria-hidden="true">
						<span
							v-if="pageIcon(page)"
							class="menu-page-icon"
							v-html="pageIcon(page)"></span>
						<span v-else>{{ pageInitial(page) }}</span>
					</span>
					<span class="menu-text">{{ pageLabel(page) }}</span>
				</button>
			</div>
		</div>
		<div class="menu-section">
			<div v-if="!collapsed" class="menu-section-title">Active sessions</div>
			<div class="menu-list">
				<button
					v-for="session in sortedSessions"
					:key="session.id"
					class="control size-s menu-item menu-button"
					:class="[
            session.id === selectedSessionId ? 'active' : '',
            attentionState(session),
            session?.status ? `status-${session.status.toLowerCase().replace('_', '-')}` : '',
          ]"
					type="button"
					@click="handleSelect(session.id)">
					<span class="menu-session-icon" aria-hidden="true">{{ sessionInitial(session) }}</span>
					<span v-if="!collapsed" class="menu-session-copy">
						<span class="menu-text">{{ sessionLabel(session) }}</span>
						<span v-if="sessionChannelLabel(session)" class="menu-session-channel">{{ sessionChannelLabel(session) }}</span>
					</span>
					<span v-if="!collapsed" class="menu-avatars">
						<span
							v-for="(participant, index) in participantStack(session).visible"
							:key="participant.user_id"
							class="menu-avatar-wrap tooltip"
							:style="{
                zIndex: participantStack(session).visible.length - index,
              }"
							:data-tip="participantName(participant)">
							<span
								class="menu-avatar"
								:style="{ backgroundColor: participant.avatar_url ? '' : avatarColor(participant) }">
								<img
									v-if="participant.avatar_url"
									:src="participant.avatar_url"
									alt=""/>
								<span v-else class="menu-avatar-initial">{{ participantInitial(participant) }}</span>
							</span>
						</span>
						<span
							v-if="participantStack(session).extra > 0"
							class="menu-avatar extra"
							:data-tip="`${participantStack(session).extra} more`">
							+{{ participantStack(session).extra }}
						</span>
					</span>
				</button>
				<div v-if="sessionListPending && !collapsed" class="menu-loading">
					<span class="thinking-spinner" aria-hidden="true"></span>
					<span>Loading</span>
				</div>
				<p v-else-if="sortedSessions.length === 0 && !collapsed" class="empty">No sessions yet.</p>
			</div>
		</div>
		<div class="menu-bottom">
			<button
				v-if="!collapsed"
				class="control size-xs ghost menu-button tooltip"
				type="button"
				data-tip="Back to tenant"
				@click="goToTenant">
				<span class="menu-icon" aria-hidden="true">
					<svg viewBox="0 0 24 24" focusable="false">
						<path
							fill="currentColor"
							d="M15.5 5 9 11.5 15.5 18l-1.5 1.5L6 11.5 14 3.5z"/>
					</svg>
				</span>
				<span class="menu-text">Tenant</span>
			</button>
			<button
				class="control size-xs icon-button menu-collapse menu-button tooltip"
				type="button"
				@click="toggleCollapse"
				:data-tip="collapsed ? 'Expand menu' : 'Collapse menu'">
				<svg viewBox="0 0 24 24" aria-hidden="true">
					<path
						fill="currentColor"
						d="M4 7h16v2H4V7zm0 4h10v2H4v-2zm0 4h16v2H4v-2z"/>
				</svg>
			</button>
		</div>
	</aside>
</template>
<style scoped>
.menu-channel-group {
	position: relative;
	display: flex;
	width: 100%;
}

.menu-channel-group .menu-action {
	margin: 0;
}

.split-main {
	flex: 1;
	border-top-right-radius: 0;
	border-bottom-right-radius: 0;
}

.channel-toggle {
	width: 2.75rem;
	min-width: 2.75rem;
	justify-content: center;
	padding: 0;
	border-top-left-radius: 0 !important;
	border-bottom-left-radius: 0 !important;
	border-left: 1px solid rgba(255, 255, 255, 0.18);
}

.channel-toggle .menu-icon {
	margin: 0;
}

.channel-toggle.active {
	filter: brightness(1.08);
}

.menu-channel-group:has(.channel-toggle) .split-main {
	border-top-right-radius: 0;
	border-bottom-right-radius: 0;
}

.menu-channel-list {
	position: absolute;
	top: calc(100% + 0.35rem);
	right: 0;
	left: 0;
	z-index: 20;
	display: flex;
	flex-direction: column;
	gap: 0.35rem;
	padding: 0.4rem;
	border: 1px solid color-mix(in srgb, var(--accent) 26%, var(--color-border-primary));
	border-radius: 0.9rem;
	background: linear-gradient( 180deg, color-mix(in srgb, var(--accent) 12%, var(--bg-panel-strong)) 0%, color-mix(in srgb, var(--accent) 6%, var(--bg-panel-strong)) 100%);
	box-shadow: 0 18px 40px -28px color-mix(in srgb, var(--accent) 45%, transparent);
	backdrop-filter: blur(16px);
}

.menu-channel-item {
	width: 100%;
	justify-content: flex-start;
}

.menu-channel-name {
	overflow: hidden;
	text-overflow: ellipsis;
	white-space: nowrap;
}

.menu-session-copy {
	min-width: 0;
	display: flex;
	flex: 1;
	flex-direction: column;
	align-items: flex-start;
}

.menu-session-copy .menu-text {
	width: 100%;
}

.menu-session-channel {
	max-width: 100%;
	color: var(--color-text-ghost);
	font-size: 0.72rem;
	line-height: 1.2;
	overflow: hidden;
	text-overflow: ellipsis;
	white-space: nowrap;
}
</style>
