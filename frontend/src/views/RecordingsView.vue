<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import {
	createRecording,
	deleteRecording,
	downloadRecordingAudio,
	getRecording,
	getRecordings,
	getRules,
	getWorkspaceRules,
	loadActiveTenant,
	updateRecording
} from '../api';
import MarkdownMessage from '../components/MarkdownMessage.vue';
const route = useRoute()
const tenantId = ref(loadActiveTenant())
const workspaceId = computed(() => String(route.params.workspaceId || ''))
const recordings = ref([])
const loading = ref(false)
const error = ref('')
const uploadError = ref('')
const rulesLoading = ref(false)
const tenantRules = ref([])
const workspaceRules = ref([])
const recordingRuleIds = ref([])
const deleteOpen = ref(false)
const pendingDelete = ref(null)
const editOpen = ref(false)
const editSaving = ref(false)
const editError = ref('')
const editForm = ref({ id: '', title: '', summary: '' })
const recordingState = ref('idle')
const recordingTitle = ref('')
const recordingBlob = ref(null)
const recordingMimeType = ref('')
const recordingDuration = ref(0)
const recordingStart = ref(0)
const recordingStartedAt = ref('')
const recordingSource = ref('record')
const recordingStartedAtInput = computed({
	get() {
		if (!recordingStartedAt.value) {
			return ''
		}
		const date = new Date(recordingStartedAt.value)
		if (Number.isNaN(date.getTime())) {
			return ''
		}
		const pad = (value) => String(value).padStart(2, '0')
		return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${
			pad(date.getHours())
		}:${pad(date.getMinutes())}`
	},
	set(value) {
		if (!value) {
			recordingStartedAt.value = ''
			return
		}
		const [datePart, timePart] = value.split('T')
		if (!datePart || !timePart) {
			return
		}
		const [year, month, day] = datePart.split('-').map(Number)
		const [hour, minute] = timePart.split(':').map(Number)
		if ([year, month, day, hour, minute].some((num) => Number.isNaN(num))) {
			return
		}
		const localDate = new Date(year, month - 1, day, hour, minute)
		if (!Number.isNaN(localDate.getTime())) {
			recordingStartedAt.value = localDate.toISOString()
		}
	}
})
const elapsedSeconds = ref(0)
const uploadFileRef = ref(null)
let mediaRecorder = null
let recordStream = null
let chunks = []
let timer = null
const detailCache = new Map()
const statusLabels = {
	UPLOADED: 'Queued',
	TRANSCRIBING: 'Transcribing',
	TRANSCRIBED: 'Transcribed',
	OPTIMIZING: 'Optimizing',
	OPTIMIZED: 'Optimized',
	SUMMARIZING: 'Summarizing',
	SUMMARIZED: 'Ready',
	ERROR: 'Error'
}
const ruleStorageKey = computed(() => `polymr.recordings.rules.${workspaceId.value}`)
const loadRecordings = async() => {
	if (!tenantId.value || !workspaceId.value) {
		return
	}
	if (recordings.value.length === 0) {
		loading.value = true
	}
	error.value = ''
	try {
		recordings.value = await getRecordings(tenantId.value, workspaceId.value)
	}
	catch (loadError) {
		error.value = loadError?.message || 'Unable to load recordings.'
		recordings.value = []
	}
	finally {
		loading.value = false
	}
}
const loadRules = async() => {
	if (!tenantId.value || !workspaceId.value) {
		return
	}
	rulesLoading.value = true
	try {
		const [tenantList, workspaceList] = await Promise.all([
			getRules(tenantId.value),
			getWorkspaceRules(tenantId.value, workspaceId.value),
		])
		tenantRules.value = Array.isArray(tenantList) ? tenantList : []
		workspaceRules.value = Array.isArray(workspaceList) ? workspaceList : []
	}
	catch (loadErr) {
		tenantRules.value = []
		workspaceRules.value = []
		console.warn('[recordings] failed to load rules', loadErr)
	}
	finally {
		rulesLoading.value = false
	}
}
const ruleOptions = computed(() => {
	const tenant = tenantRules.value.filter((rule) => rule?.enabled !== false).map((rule) => ({ ...rule, source: 'Tenant' }))
	const workspace = workspaceRules.value
		.filter((rule) => rule?.enabled !== false)
		.map((rule) => ({ ...rule, source: 'Workspace' }))
	return [...workspace, ...tenant].sort((a, b) => (a.name || '').localeCompare(b.name || ''))
})
const loadRuleSelection = () => {
	if (!ruleStorageKey.value) {
		recordingRuleIds.value = []
		return
	}
	try {
		const stored = localStorage.getItem(ruleStorageKey.value)
		const parsed = stored ? JSON.parse(stored) : []
		recordingRuleIds.value = Array.isArray(parsed) ? parsed : []
	}
	catch {
		recordingRuleIds.value = []
	}
}
const startTimer = () => {
	recordingStart.value = Date.now()
	elapsedSeconds.value = 0
	timer = window.setInterval(
		() => {
			elapsedSeconds.value = Math.floor((Date.now() - recordingStart.value) / 1000)
		},
		500
	)
}
const stopTimer = () => {
	if (timer) {
		clearInterval(timer)
		timer = null
	}
}
const pickMimeType = () => {
	if (typeof window === 'undefined' || !window.MediaRecorder) {
		return ''
	}
	const candidates = [
		'audio/webm;codecs=opus',
		'audio/ogg;codecs=opus',
		'audio/webm',
	]
	return candidates.find((type) => window.MediaRecorder.isTypeSupported(type)) || ''
}
const startRecording = async() => {
	uploadError.value = ''
	recordingTitle.value = ''
	recordingBlob.value = null
	recordingMimeType.value = ''
	recordingDuration.value = 0
	recordingStartedAt.value = ''
	recordingSource.value = 'record'
	chunks = []
	try {
		recordStream = await navigator.mediaDevices.getUserMedia({ audio: true })
		const mimeType = pickMimeType()
		mediaRecorder = new MediaRecorder(recordStream, mimeType ? { mimeType } : undefined)
		mediaRecorder.ondataavailable = (event) => {
			if (event.data && event.data.size > 0) {
				chunks.push(event.data)
			}
		}
		mediaRecorder.onstop = () => {
			const recordedType = mediaRecorder?.mimeType || mimeType || chunks[0]?.type || 'audio/webm'
			recordingMimeType.value = recordedType
			recordingBlob.value = new Blob(chunks, { type: recordedType })
			recordingDuration.value = elapsedSeconds.value
			recordingStartedAt.value = new Date(recordingStart.value).toISOString()
			if (recordStream) {
				recordStream.getTracks().forEach((track) => track.stop())
				recordStream = null
			}
			mediaRecorder = null
			recordingState.value = 'review'
		}
		mediaRecorder.start()
		recordingState.value = 'recording'
		startTimer()
	}
	catch (recordError) {
		uploadError.value = recordError?.message || 'Unable to start recording.'
		recordingState.value = 'idle'
	}
}
const stopRecording = () => {
	if (!mediaRecorder || mediaRecorder.state !== 'recording') {
		return
	}
	mediaRecorder.stop()
	stopTimer()
}
const discardRecording = () => {
	recordingBlob.value = null
	recordingTitle.value = ''
	recordingMimeType.value = ''
	recordingDuration.value = 0
	recordingStartedAt.value = ''
	recordingSource.value = 'record'
	elapsedSeconds.value = 0
	recordingState.value = 'idle'
}
const confirmUpload = async() => {
	if (!recordingBlob.value) {
		return
	}
	if (!recordingTitle.value.trim()) {
		uploadError.value = 'Title is required.'
		return
	}
	uploadError.value = ''
	recordingState.value = 'uploading'
	const payload = {
		title: recordingTitle.value.trim(),
		duration_seconds: recordingDuration.value || 0,
		started_at: recordingStartedAt.value || '',
		mime_type: recordingMimeType.value || 'audio/webm',
		blob: recordingBlob.value,
		rule_ids: recordingRuleIds.value
	}
	try {
		await createRecording(tenantId.value, workspaceId.value, payload)
		localStorage.setItem(ruleStorageKey.value, JSON.stringify(recordingRuleIds.value))
		discardRecording()
		loadRecordings()
	}
	catch (uploadErr) {
		uploadError.value = uploadErr?.message || 'Unable to upload recording.'
		recordingState.value = 'review'
	}
}
const handleUploadPick = async(event) => {
	const file = event?.target?.files?.[0]
	if (!file) {
		return
	}
	uploadError.value = ''
	recordingTitle.value = file.name.replace(/\.[^/.]+$/, '')
	recordingBlob.value = file
	recordingMimeType.value = file.type || 'audio/webm'
	recordingDuration.value = 0
	recordingStartedAt.value = ''
	recordingSource.value = 'upload'
	recordingState.value = 'review'
	if (uploadFileRef.value) {
		uploadFileRef.value.value = ''
	}
}
const formatTime = (seconds) => {
	const total = Math.max(0, seconds || 0)
	const mins = Math.floor(total / 60)
	const secs = total % 60
	return `${String(mins).padStart(2, '0')}:${String(secs).padStart(2, '0')}`
}
const ensureDetail = async(recordingId) => {
	if (detailCache.has(recordingId)) {
		return detailCache.get(recordingId)
	}
	const detail = await getRecording(tenantId.value, workspaceId.value, recordingId)
	detailCache.set(recordingId, detail)
	return detail
}
const copyText = async(text) => {
	if (!text) {
		return
	}
	await navigator.clipboard.writeText(text)
}
const copyTranscript = async(recording) => {
	const detail = await ensureDetail(recording.id)
	await copyText(detail.transcript_text || '')
}
const downloadAudio = async(recording) => {
	const blob = await downloadRecordingAudio(tenantId.value, workspaceId.value, recording.id)
	const url = URL.createObjectURL(blob)
	const extension = recording.audio_mime_type?.includes('ogg') ? 'ogg' : 'webm'
	const link = document.createElement('a')
	link.href = url
	link.download = `${recording.title || 'recording'}.${extension}`
	document.body.appendChild(link)
	link.click()
	link.remove()
	URL.revokeObjectURL(url)
}
const requestDelete = (recording) => {
	pendingDelete.value = recording
	deleteOpen.value = true
}
const confirmDelete = async() => {
	if (!pendingDelete.value) {
		deleteOpen.value = false
		return
	}
	try {
		await deleteRecording(tenantId.value, workspaceId.value, pendingDelete.value.id)
		deleteOpen.value = false
		pendingDelete.value = null
		loadRecordings()
	}
	catch (deleteError) {
		error.value = deleteError?.message || 'Unable to delete recording.'
		deleteOpen.value = false
	}
}
const cancelDelete = () => {
	deleteOpen.value = false
	pendingDelete.value = null
}
const openEdit = (recording) => {
	if (!recording?.summary_text) {
		return
	}
	editForm.value = { id: recording.id, title: recording.title || '', summary: recording.summary_text || '' }
	editError.value = ''
	editOpen.value = true
}
const cancelEdit = () => {
	editOpen.value = false
	editSaving.value = false
	editError.value = ''
}
const saveEdit = async() => {
	if (!editForm.value.title.trim()) {
		editError.value = 'Title is required.'
		return
	}
	if (!editForm.value.summary.trim()) {
		editError.value = 'Summary is required.'
		return
	}
	editSaving.value = true
	editError.value = ''
	try {
		const updated = await updateRecording(
			tenantId.value,
			workspaceId.value,
			editForm.value
					.id,
			{ title: editForm.value.title.trim(), summary_text: editForm.value.summary.trim() }
		)
		recordings.value = recordings.value.map((item) => item.id === updated.id ? { ...item, ...updated } : item)
		editOpen.value = false
	}
	catch (saveError) {
		editError.value = saveError?.message || 'Unable to update recording.'
	}
	finally {
		editSaving.value = false
	}
}
let refreshTimer = null
onMounted(() => {
	loadRecordings()
	loadRules()
	refreshTimer = window.setInterval(loadRecordings, 8000)
})
onBeforeUnmount(() => {
	stopTimer()
	if (refreshTimer) {
		clearInterval(refreshTimer)
	}
	if (recordStream) {
		recordStream.getTracks().forEach((track) => track.stop())
	}
})
watch(
	() => recordingState.value,
	(next) => {
		if (next === 'review') {
			loadRuleSelection()
			if (!rulesLoading.value && ruleOptions.value.length === 0) {
				loadRules()
			}
		}
	}
)
</script>
<template>
	<main class="recordings-main">
		<section class="recording-hero">
			<div>
				<h2>Recordings</h2>
				<p class="subtle">Capture long-form audio, then transcribe, optimize, and summarize it automatically.</p>
			</div>
			<div class="recording-controls">
				<button
					v-if="recordingState !== 'recording'"
					class="control primary size-m"
					type="button"
					@click="startRecording"
					:disabled="recordingState === 'uploading'">Start recording</button>
				<button
					v-else
					class="control danger size-m"
					type="button"
					@click="stopRecording">Stop recording</button>
				<div class="timer">
					<span class="timer-label">{{ recordingState === 'recording' ? 'Recording' : 'Ready' }}</span>
					<span class="timer-value">{{ formatTime(elapsedSeconds) }}</span>
				</div>
				<label class="control ghost size-m file-upload">
					Upload audio
					<input
						ref="uploadFileRef"
						type="file"
						accept="audio/webm,audio/ogg,audio/opus"
						@change="handleUploadPick"
						:disabled="recordingState === 'recording'"/>
				</label>
			</div>
		</section>
		<section class="recording-list">
			<div class="list-header">
				<h3>Recent recordings</h3>
				<p class="subtle">Workspace-scoped recordings and summaries.</p>
			</div>
			<p v-if="loading" class="subtle">Loading recordings…</p>
			<p v-else-if="error" class="subtle">{{ error }}</p>
			<div v-else class="cards">
				<article
					v-for="recording in recordings"
					:key="recording.id"
					class="recording-card">
					<header class="recording-card-header">
						<div>
							<div class="recording-title-row">
								<h4>{{ recording.title }}</h4>
								<button
									v-if="recording.summary_text"
									class="control size-xs ghost icon-button icon-ghost tooltip edit-button"
									type="button"
									data-tip="Edit"
									aria-label="Edit recording"
									@click="openEdit(recording)">
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
							<p class="subtle recording-meta">
								<span v-if="recording.started_at">
									Started {{ new Date(recording.started_at).toLocaleString() }}
								</span>
								<span v-if="recording.created_at">
									<span v-if="recording.started_at"> · </span>
									Created {{ new Date(recording.created_at).toLocaleString() }}
								</span>
							</p>
						</div>
						<span class="status" :class="recording.status?.toLowerCase()">{{ statusLabels[recording.status] || recording.status }}</span>
					</header>
					<MarkdownMessage
						v-if="recording.summary_text"
						class="summary"
						:content="recording.summary_text"/>
					<p v-else class="subtle">{{ recording.error_message || 'Processing in progress.' }}</p>
					<div class="recording-card-actions">
						<button
							class="control size-s secondary action-pill"
							type="button"
							@click="downloadAudio(recording)">Download audio</button>
						<button
							class="control size-s secondary action-pill"
							type="button"
							@click="copyTranscript(recording)"
							:disabled="!recording.has_transcript">Copy transcript</button>
						<button
							class="control size-s ghost danger action-pill"
							type="button"
							@click="requestDelete(recording)">Delete</button>
					</div>
				</article>
				<p v-if="recordings.length === 0" class="subtle">No recordings yet.</p>
			</div>
		</section>
		<div
			v-if="recordingState === 'review'"
			class="sheet-backdrop"
			@click.self="discardRecording">
			<div class="sidepane">
				<button
					class="control size-s ghost icon-button icon-ghost sidepane-close"
					type="button"
					aria-label="Close"
					@click="discardRecording">
					<svg
						viewBox="0 0 24 24"
						aria-hidden="true"
						focusable="false">
						<path
							fill="currentColor"
							d="M18.3 5.71a1 1 0 0 0-1.41 0L12 10.59 7.11 5.7a1 1 0 0 0-1.41 1.42L10.59 12l-4.89 4.89a1 1 0 1 0 1.41 1.41L12 13.41l4.89 4.89a1 1 0 0 0 1.41-1.41L13.41 12l4.89-4.89a1 1 0 0 0 0-1.4z"/>
					</svg>
				</button>
				<h2>Confirm recording</h2>
				<div class="stack">
					<label class="field">
						<span>Title</span>
						<input
							v-model="recordingTitle"
							type="text"
							placeholder="Recording title"/>
					</label>
					<label v-if="recordingSource === 'upload'" class="field">
						<span>Started at</span>
						<input v-model="recordingStartedAtInput" type="datetime-local"/>
						<p class="subtle">Optional. Leave empty if unknown.</p>
					</label>
					<div class="field">
						<span>Rules for transcription</span>
						<div class="option-list">
							<label
								v-for="rule in ruleOptions"
								:key="rule.id"
								class="option-item">
								<input
									v-model="recordingRuleIds"
									type="checkbox"
									:value="rule.id"/>
								<span>{{ rule.name }}</span>
								<span class="subtle inline">{{ rule.source }}</span>
							</label>
							<p v-if="rulesLoading" class="subtle">Loading rules…</p>
							<p v-else-if="ruleOptions.length === 0" class="subtle">No rules available.</p>
						</div>
					</div>
					<p v-if="recordingSource !== 'upload'" class="subtle">
						Duration: {{ formatTime(recordingDuration) }}
					</p>
					<p v-if="uploadError" class="form-error">{{ uploadError }}</p>
					<div class="row-actions">
						<button
							class="control size-m secondary"
							type="button"
							@click="confirmUpload">Start processing</button>
						<button
							class="control size-m ghost"
							type="button"
							@click="discardRecording">Discard</button>
					</div>
				</div>
			</div>
		</div>
		<div
			v-if="deleteOpen"
			class="sheet-backdrop"
			@click.self="cancelDelete">
			<div class="sidepane">
				<button
					class="control size-s ghost icon-button icon-ghost sidepane-close"
					type="button"
					aria-label="Close"
					@click="cancelDelete">
					<svg
						viewBox="0 0 24 24"
						aria-hidden="true"
						focusable="false">
						<path
							fill="currentColor"
							d="M18.3 5.71a1 1 0 0 0-1.41 0L12 10.59 7.11 5.7a1 1 0 0 0-1.41 1.42L10.59 12l-4.89 4.89a1 1 0 1 0 1.41 1.41L12 13.41l4.89 4.89a1 1 0 0 0 1.41-1.41L13.41 12l4.89-4.89a1 1 0 0 0 0-1.4z"/>
					</svg>
				</button>
				<h2>Delete recording</h2>
				<div class="stack">
					<p class="subtle">This action permanently removes the recording.</p>
					<div class="row-actions">
						<button
							class="control size-m secondary"
							type="button"
							@click="confirmDelete">Delete</button>
						<button
							class="control size-m ghost"
							type="button"
							@click="cancelDelete">Cancel</button>
					</div>
				</div>
			</div>
		</div>
		<div
			v-if="editOpen"
			class="sheet-backdrop"
			@click.self="cancelEdit">
			<div class="sidepane">
				<button
					class="control size-s ghost icon-button icon-ghost sidepane-close"
					type="button"
					aria-label="Close"
					@click="cancelEdit">
					<svg
						viewBox="0 0 24 24"
						aria-hidden="true"
						focusable="false">
						<path
							fill="currentColor"
							d="M18.3 5.71a1 1 0 0 0-1.41 0L12 10.59 7.11 5.7a1 1 0 0 0-1.41 1.42L10.59 12l-4.89 4.89a1 1 0 1 0 1.41 1.41L12 13.41l4.89 4.89a1 1 0 0 0 1.41-1.41L13.41 12l4.89-4.89a1 1 0 0 0 0-1.4z"/>
					</svg>
				</button>
				<h2>Edit recording</h2>
				<div class="stack">
					<label class="field">
						<span>Title</span>
						<input v-model="editForm.title" type="text"/>
					</label>
					<label class="field">
						<span>Summary (Markdown)</span>
						<textarea v-model="editForm.summary" rows="8"></textarea>
					</label>
					<p v-if="editError" class="form-error">{{ editError }}</p>
					<div class="row-actions">
						<button
							class="control size-m secondary"
							type="button"
							:disabled="editSaving"
							@click="saveEdit">Save</button>
						<button
							class="control size-m ghost"
							type="button"
							@click="cancelEdit">Cancel</button>
					</div>
				</div>
			</div>
		</div>
	</main>
</template>
<style scoped>
.recordings-main {
	display: flex;
	flex-direction: column;
	gap: 24px;
	padding: 32px;
}

.recording-hero {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 24px;
	background: linear-gradient(120deg, rgba(41, 61, 86, 0.15), rgba(24, 48, 63, 0.35));
	border-radius: 20px;
	padding: 24px;
	border: 1px solid rgba(255, 255, 255, 0.08);
}

.recording-controls {
	display: flex;
	align-items: center;
	gap: 16px;
}

.file-upload {
	position: relative;
	overflow: hidden;
}

.file-upload input[type='file'] {
	position: absolute;
	inset: 0;
	opacity: 0;
	cursor: pointer;
}

.timer {
	display: flex;
	flex-direction: column;
	align-items: flex-end;
	padding: 12px 16px;
	border-radius: 14px;
	background: rgba(12, 18, 24, 0.6);
	color: #f5f7fa;
	min-width: 100px;
}

.timer-label {
	font-size: 12px;
	text-transform: uppercase;
	letter-spacing: 0.12em;
	opacity: 0.75;
}

.timer-value {
	font-size: 24px;
	font-weight: 600;
}

.recording-list {
	display: flex;
	flex-direction: column;
	gap: 16px;
}

.cards {
	display: grid;
	grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
	gap: 16px;
}

.recording-card {
	display: flex;
	flex-direction: column;
	gap: 12px;
	padding: 16px;
	border-radius: 16px;
	background: rgba(18, 24, 34, 0.7);
	border: 1px solid rgba(255, 255, 255, 0.06);
}

.recording-meta {
	font-size: var(--font-size-xs);
}

.recording-card-header {
	display: flex;
	align-items: flex-start;
	justify-content: space-between;
	gap: 16px;
}

.recording-title-row {
	display: flex;
	align-items: center;
	gap: 8px;
}

.edit-button {
	opacity: 0;
	transform: translateY(-2px);
	transition: var(--transition-base);
}

.recording-card:hover .edit-button {
	opacity: 1;
	transform: translateY(0);
}

.recording-card h4 {
	margin: 0;
	font-size: 18px;
}

.summary {
	color: rgba(255, 255, 255, 0.9);
	font-size: 14px;
	line-height: 1.5;
	white-space: normal;
}

.recording-card-actions {
	display: flex;
	flex-wrap: wrap;
	gap: 8px;
}

.action-pill {
	border: 1px solid rgba(255, 255, 255, 0.12);
	background: rgba(18, 24, 34, 0.6);
	color: #f5f7fa;
}

.action-pill:hover:not(:disabled) {
	background: rgba(31, 42, 58, 0.7);
	transform: translateY(-1px);
	box-shadow: 0 10px 20px -16px rgba(0, 0, 0, 0.6);
}

.status {
	padding: 4px 10px;
	border-radius: 999px;
	font-size: 12px;
	background: rgba(255, 255, 255, 0.08);
	text-transform: uppercase;
	letter-spacing: 0.08em;
}

.status.error {
	background: rgba(176, 54, 54, 0.3);
	color: #ffb3b3;
}

.status.summarized {
	background: rgba(46, 139, 87, 0.3);
	color: #b8f3cc;
}

@media (max-width: 960px) {
	.recording-hero {
		flex-direction: column;
		align-items: flex-start;
	}
	.timer {
		align-items: flex-start;
	}
}
</style>
