<script setup>
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { deleteSession, getHistorySessions, loadActiveTenant, reactivateSession } from '../api';
import ConfirmModal from '../components/ConfirmModal.vue';
const route = useRoute()
const router = useRouter()
const tenantId = ref(loadActiveTenant())
const workspaceId = computed(() => String(route.params.workspaceId || ''))
const historySessions = ref([])
const loading = ref(false)
const error = ref('')
const deleteOpen = ref(false)
const deleteTarget = ref(null)
const reactivatingId = ref(null)
const pageSize = 20
const offset = ref(0)
const total = ref(0)
const hasPreviousPage = computed(() => offset.value > 0)
const hasNextPage = computed(() => offset.value + historySessions.value.length < total.value)
const loadHistory = async() => {
	if (!tenantId.value || !workspaceId.value) {
		return
	}
	loading.value = true
	error.value = ''
	try {
		const page = await getHistorySessions(tenantId.value, workspaceId.value, { offset: offset.value, limit: pageSize })
		historySessions.value = Array.isArray(page?.sessions) ? page.sessions : []
		total.value = Number(page?.total || 0)
	}
	catch (loadError) {
		error.value = loadError?.message || 'Unable to load history sessions.'
		historySessions.value = []
		total.value = 0
	}
	finally {
		loading.value = false
	}
}
const openSession = (id) => {
	if (!id) {
		return
	}
	router.push({ name: 'workspace-session', params: { workspaceId: workspaceId.value, sessionId: id } })
}
const requestDelete = (session, event) => {
	event?.stopPropagation()
	deleteTarget.value = session
	deleteOpen.value = true
}
const confirmDelete = async() => {
	if (!deleteTarget.value) {
		deleteOpen.value = false
		return
	}
	try {
		await deleteSession(tenantId.value, workspaceId.value, deleteTarget.value.id)
		await loadHistory()
	}
	catch (deleteError) {
		error.value = deleteError?.message || 'Unable to delete session.'
	}
	finally {
		deleteOpen.value = false
		deleteTarget.value = null
	}
}
const isArchivedSession = (session) => session?.status === 'COMPLETED' && session?.workflow_run_status === 'PAUSED'
const statusLabel = (session) => {
	if (isArchivedSession(session)) {
		return 'Archived'
	}
	if (session?.status === 'COMPLETED') {
		return 'Finished'
	}
	if (session?.status === 'CANCELLED') {
		return 'Cancelled'
	}
	if (session?.status === 'ERROR') {
		return 'Error'
	}
	return session?.status || 'Unknown'
}
const statusClass = (session) => {
	if (isArchivedSession(session)) {
		return 'archived'
	}
	if (session?.status === 'COMPLETED') {
		return 'finished'
	}
	if (session?.status === 'CANCELLED') {
		return 'cancelled'
	}
	if (session?.status === 'ERROR') {
		return 'error'
	}
	return 'default'
}
const reactivate = async(session, event) => {
	event?.stopPropagation()
	if (!session?.id || !tenantId.value || !workspaceId.value) {
		return
	}
	reactivatingId.value = session.id
	error.value = ''
	try {
		await reactivateSession(tenantId.value, workspaceId.value, session.id)
		await loadHistory()
	}
	catch (reactivateError) {
		error.value = reactivateError?.message || 'Unable to reactivate session.'
	}
	finally {
		reactivatingId.value = null
	}
}
const loadPreviousPage = () => {
	if (!hasPreviousPage.value) {
		return
	}
	offset.value = Math.max(0, offset.value - pageSize)
	loadHistory()
}
const loadNextPage = () => {
	if (!hasNextPage.value) {
		return
	}
	offset.value += pageSize
	loadHistory()
}
watch(
	workspaceId,
	() => {
		historySessions.value = []
		offset.value = 0
		total.value = 0
		loadHistory()
	}
)
onMounted(() => {
	loadHistory()
})
</script>
<template>
	<main class="sessions-main">
		<div class="history-panel">
			<div class="history-header">
				<div>
					<h2>History</h2>
					<p class="subtle">Completed sessions for this workspace.</p>
				</div>
				<div class="history-pagination">
					<span class="subtle">
						{{ historySessions.length === 0 ? 0 : offset + 1 }}-{{ Math.min(offset + historySessions.length, total) }} of {{ total }}
					</span>
					<button
						class="control size-s secondary"
						type="button"
						:disabled="loading || !hasPreviousPage"
						@click="loadPreviousPage">Previous</button>
					<button
						class="control size-s secondary"
						type="button"
						:disabled="loading || !hasNextPage"
						@click="loadNextPage">Next</button>
				</div>
			</div>
			<div class="session-list">
				<p v-if="loading" class="subtle">Loading history…</p>
				<p v-else-if="error" class="subtle">{{ error }}</p>
				<template v-else>
					<div
						v-for="session in historySessions"
						:key="session.id"
						class="session-pill">
						<button
							class="session-open"
							type="button"
							@click="openSession(session.id)">
							<span class="session-pill-title">{{ session.title || 'Untitled session' }}</span>
							<span class="session-pill-tag" :class="statusClass(session)">{{ statusLabel(session) }}</span>
							<span class="subtle">{{ session.updated_at ? new Date(session.updated_at).toLocaleString() : '' }}</span>
						</button>
						<button
							v-if="isArchivedSession(session)"
							class="control size-xs secondary history-reactivate"
							type="button"
							:disabled="loading || reactivatingId === session.id"
							@click.stop="reactivate(session, $event)">{{ reactivatingId === session.id ? 'Reactivating…' : 'Reactivate' }}</button>
						<button
							class="control size-xs ghost icon-button icon-ghost tooltip history-delete"
							type="button"
							data-tip="Delete session"
							@click.stop="requestDelete(session, $event)">×</button>
					</div>
					<p v-if="historySessions.length === 0" class="subtle">No history sessions yet.</p>
				</template>
			</div>
		</div>
	</main>
	<ConfirmModal
		v-model:open="deleteOpen"
		title="Delete session"
		:message="deleteTarget ? `Delete '${deleteTarget.title || 'Untitled session'}'? This cannot be undone.` : 'Delete this session?'"
		confirm-label="Delete"
		:destructive="true"
		@confirm="confirmDelete"
		@cancel="deleteOpen = false"/>
</template>
<style scoped>
.history-pagination {
	display: flex;
	align-items: center;
	gap: var(--space-s);
}

.session-pill {
	position: relative;
	display: flex;
	align-items: center;
	gap: var(--space-xs);
	padding: var(--space-s) var(--space-m);
	border-radius: var(--border-radius-m);
	border: var(--border-subtle);
	background: var(--bg-panel-strong);
	color: var(--text-primary);
}

.session-open {
	flex: 1;
	display: flex;
	align-items: center;
	gap: var(--space-xs);
	padding: 0;
	border: none;
	background: transparent;
	color: inherit;
	text-align: left;
}

.session-pill-title {
	flex: 1;
	text-align: left;
}

.session-pill-tag {
	font-size: var(--font-size-2xs);
	letter-spacing: 0.08em;
	text-transform: uppercase;
	padding: 2px 8px;
	border-radius: 999px;
	border: 1px solid transparent;
	color: var(--text-muted);
	background: color-mix(in srgb, var(--bg-panel) 80%, transparent);
}

.session-pill-tag.archived {
	color: color-mix(in srgb, var(--color-warning) 85%, var(--text-muted));
	border-color: color-mix(in srgb, var(--color-warning) 55%, transparent);
	background: color-mix(in srgb, var(--color-warning) 18%, transparent);
}

.session-pill-tag.finished {
	color: color-mix(in srgb, var(--text-muted) 85%, var(--text-muted));
	border-color: color-mix(in srgb, var(--text-muted) 45%, transparent);
	background: color-mix(in srgb, var(--text-muted) 12%, transparent);
}

.session-pill-tag.cancelled {
	color: color-mix(in srgb, var(--color-danger) 80%, var(--text-muted));
	border-color: color-mix(in srgb, var(--color-danger) 55%, transparent);
	background: color-mix(in srgb, var(--color-danger) 15%, transparent);
}

.session-pill-tag.error {
	color: color-mix(in srgb, var(--color-danger) 85%, var(--text-muted));
	border-color: color-mix(in srgb, var(--color-danger) 65%, transparent);
	background: color-mix(in srgb, var(--color-danger) 18%, transparent);
}

.history-reactivate {
	white-space: nowrap;
}

.history-delete {
	margin-left: auto;
	font-weight: 600;
	line-height: 1;
}
</style>
