<script setup>
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import {
	createUserRule,
	deleteUserRule,
	getProfile,
	getUserRules,
	loadSession,
	updateProfile,
	updateUserRule
} from '../api';
import { setLocationTrackingEnabled } from '../state/locationTracking';
import { loadSmartQueueing, saveSmartQueueing } from '../state/queueingSettings';
import { getWorkspaceSetting, setWorkspaceSetting } from '../state/workspaceSettings';
import ConfirmModal from '../components/ConfirmModal.vue';
const route = useRoute()
const workspaceId = computed(() => String(route.params.workspaceId || ''))
const session = loadSession()
const userId = session?.userId
const profile = ref({
	email: '',
	nickname: '',
	notifications_snoozed_until: '',
	execution_mode: 'released'
})
const avatarState = ref({
	url: '',
	base64: null,
	contentType: null,
	dirty: false
})
const smartQueueing = ref(loadSmartQueueing())
const ttsPauseVad = ref(getWorkspaceSetting(workspaceId.value, 'polymr.tts.pause_vad') !== 'false')
const ttsVoice = ref(
	getWorkspaceSetting(workspaceId.value, 'polymr.tts.voice') || 'en_US-libritts_r-medium'
)
const ttsVoices = ref([])
const ttsVoicesLoading = ref(false)
const ttsVoicesError = ref('')
const ttsVoicesLoaded = ref(false)
const ttsCustomVoices = ref([])
const ttsUploadName = ref('')
const ttsUploadOnnx = ref(null)
const ttsUploadJson = ref(null)
const ttsUploadError = ref('')
const ttsUploadSaving = ref(false)
const useExactLocation = ref(localStorage.getItem('polymr.location.exact') === 'true')
const userRules = ref([])
const status = ref('')
let statusTimer = null
const ruleModal = ref(false)
const ruleEditId = ref('')
const ruleForm = ref({ name: '', content: '', enabled: true })
const deleteRuleOpen = ref(false)
const ruleToDelete = ref(null)
const deleteRuleMessage = computed(() => {
	if (ruleToDelete.value) {
		const name = ruleToDelete.value.name || 'Untitled rule'
		return `Delete rule "${name}"? This cannot be undone.`
	}
	return 'Delete this rule? This cannot be undone.'
})
const notify = (message) => {
	status.value = message
	if (statusTimer) {
		clearTimeout(statusTimer)
	}
	statusTimer = setTimeout(
		() => {
			status.value = ''
		},
		4000
	)
}
const loadData = async() => {
	if (!userId) {
		return
	}
	const data = await getProfile(userId)
	profile.value = {
		email: data.email || '',
		nickname: data.nickname || '',
		notifications_snoozed_until: data.notifications_snoozed_until || '',
		execution_mode: data.execution_mode || 'released'
	}
	avatarState.value = {
		url: data.avatar_url || '',
		base64: null,
		contentType: null,
		dirty: false
	}
	const rules = await getUserRules(userId)
	userRules.value = rules
	loadTtsVoices()
	loadCustomTtsVoices()
}
const saveProfile = async() => {
	status.value = ''
	if (!userId) {
		notify('No active session.')
		return
	}
	const payload = { ...profile.value }
	if (avatarState.value.dirty) {
		payload.avatar_base64 = avatarState.value.base64 || ''
		payload.avatar_content_type = avatarState.value.contentType || ''
	}
	const updated = await updateProfile(userId, payload)
	if (updated?.avatar_url !== undefined) {
		avatarState.value = {
			url: updated.avatar_url || '',
			base64: null,
			contentType: null,
			dirty: false
		}
	}
	notify('Profile saved.')
}
const snoozeInput = computed({
	get() {
		if (!profile.value.notifications_snoozed_until) {
			return ''
		}
		const date = new Date(profile.value.notifications_snoozed_until)
		if (Number.isNaN(date.getTime())) {
			return ''
		}
		const pad = (value) => String(value).padStart(2, '0')
		const local = new Date(date.getTime() - date.getTimezoneOffset() * 60000)
		return `${local.getFullYear()}-${pad(local.getMonth() + 1)}-${pad(local.getDate())}T${
			pad(local.getHours())
		}:${pad(local.getMinutes())}`
	},
	set(value) {
		if (!value) {
			profile.value.notifications_snoozed_until = ''
			return
		}
		const date = new Date(value)
		if (!Number.isNaN(date.getTime())) {
			profile.value.notifications_snoozed_until = date.toISOString()
		}
	}
})
const updateTtsVoice = () => {
	if (!ttsVoice.value) {
		return
	}
	setWorkspaceSetting(workspaceId.value, 'polymr.tts.voice', ttsVoice.value)
	window.dispatchEvent(new CustomEvent('tts.voice.change', { detail: { workspaceId: workspaceId.value } }))
}
const updateLocationPreference = () => {
	localStorage.setItem('polymr.location.exact', useExactLocation.value ? 'true' : 'false')
	setLocationTrackingEnabled(useExactLocation.value)
}
const loadTtsVoices = async() => {
	ttsVoicesLoading.value = true
	ttsVoicesError.value = ''
	ttsVoicesLoaded.value = false
	try {
		let attempts = 0
		while (!window.PiperTTS?.voices && attempts < 60) {
			await new Promise((resolve) => setTimeout(resolve, 100))
			attempts += 1
		}
		if (!window.PiperTTS?.voices) {
			throw new Error('Piper voices not available')
		}
		const voices = await window.PiperTTS.voices()
		ttsVoices.value = Array.isArray(voices) ? voices : []
	}
	catch (error) {
		ttsVoicesError.value = error?.message || 'Unable to load voices.'
	}
	finally {
		ttsVoicesLoading.value = false
		ttsVoicesLoaded.value = true
	}
}
const slugify = (value) => value.toLowerCase().trim().replace(/[^a-z0-9]+/g, '-').replace(/(^-|-$)+/g, '')
const loadCustomTtsVoices = () => {
	try {
		const raw = getWorkspaceSetting(workspaceId.value, 'polymr.tts.custom_voices')
		const list = raw ? JSON.parse(raw) : []
		ttsCustomVoices.value = Array.isArray(list) ? list : []
	}
	catch {
		ttsCustomVoices.value = []
	}
}
const saveCustomTtsVoices = () => {
	setWorkspaceSetting(
		workspaceId.value,
		'polymr.tts.custom_voices',
		JSON.stringify(ttsCustomVoices.value)
	)
}
const storeCustomFile = async(name, file) => {
	const root = await navigator.storage.getDirectory()
	const dir = await root.getDirectoryHandle('piper', { create: true })
	const handle = await dir.getFileHandle(name, { create: true })
	const writable = await handle.createWritable()
	await writable.write(file)
	await writable.close()
}
const uploadCustomVoice = async() => {
	ttsUploadError.value = ''
	const name = ttsUploadName.value.trim()
	if (!name) {
		ttsUploadError.value = 'Voice name is required.'
		return
	}
	if (!ttsUploadOnnx.value || !ttsUploadJson.value) {
		ttsUploadError.value = 'Both .onnx and .json files are required.'
		return
	}
	const slug = slugify(name)
	if (!slug) {
		ttsUploadError.value = 'Voice name is invalid.'
		return
	}
	if (ttsCustomVoices.value.some((voice) => voice.slug === slug)) {
		ttsUploadError.value = 'A voice with this name already exists.'
		return
	}
	ttsUploadSaving.value = true
	try {
		await storeCustomFile(`${slug}.onnx`, ttsUploadOnnx.value)
		await storeCustomFile(`${slug}.json`, ttsUploadJson.value)
		const entry = { id: `custom:${slug}`, name, slug }
		ttsCustomVoices.value = [...ttsCustomVoices.value, entry]
		saveCustomTtsVoices()
		ttsVoice.value = entry.id
		updateTtsVoice()
		ttsUploadName.value = ''
		ttsUploadOnnx.value = null
		ttsUploadJson.value = null
	}
	catch (error) {
		ttsUploadError.value = error?.message || 'Unable to upload voice.'
	}
	finally {
		ttsUploadSaving.value = false
	}
}
const removeCustomVoice = async(voice) => {
	if (!voice) {
		return
	}
	try {
		const root = await navigator.storage.getDirectory()
		const dir = await root.getDirectoryHandle('piper')
		await dir.removeEntry(`${voice.slug}.onnx`)
		await dir.removeEntry(`${voice.slug}.json`)
	}
	catch {
		// ignore
	}
	ttsCustomVoices.value = ttsCustomVoices.value.filter((item) => item.slug !== voice.slug)
	saveCustomTtsVoices()
	if (ttsVoice.value === voice.id) {
		ttsVoice.value = 'en_US-hfc_female-medium'
		updateTtsVoice()
	}
}
const downloadCustomVoice = async(voice, ext) => {
	try {
		const root = await navigator.storage.getDirectory()
		const dir = await root.getDirectoryHandle('piper')
		const handle = await dir.getFileHandle(`${voice.slug}.${ext}`)
		const file = await handle.getFile()
		const url = URL.createObjectURL(file)
		const link = document.createElement('a')
		link.href = url
		link.download = `${voice.slug}.${ext}`
		link.click()
		URL.revokeObjectURL(url)
	}
	catch {
		ttsUploadError.value = 'Unable to download voice file.'
	}
}
const extractVoiceString = (value) => {
	if (!value) {
		return ''
	}
	if (typeof value === 'string') {
		return value.trim()
	}
	if (typeof value === 'number') {
		return String(value)
	}
	if (typeof value === 'object') {
		const nested = value.key || value.id || value.voice_id || value.name || value.voice
		if (nested && typeof nested === 'string') {
			return nested.trim()
		}
	}
	return ''
}
const buildPiperVoiceId = (voice) => {
	if (!voice) {
		return ''
	}
	const direct = extractVoiceString(voice.key)
		|| extractVoiceString(voice.voice_id)
		|| extractVoiceString(voice.id)
		|| extractVoiceString(voice.name)
	if (direct) {
		return direct
	}
	const language = extractVoiceString(voice.language)
	const name = extractVoiceString(voice.name || voice.voice)
	const quality = extractVoiceString(voice.quality)
	const suffix = quality && !quality.includes('r-') ? `r-${quality}` : quality
	if (language && name && suffix) {
		return `${language}-${name}_${suffix}`
	}
	if (language && name) {
		return `${language}-${name}`
	}
	return ''
}
const allTtsVoices = computed(() => {
	const remote = ttsVoices.value
		.map((voice) => {
			const id = buildPiperVoiceId(voice)
			if (!id) {
				return null
			}
			const language = extractVoiceString(voice.language?.code)
				|| extractVoiceString(voice.language?.name_english)
				|| extractVoiceString(voice.language)
			const label = [
				extractVoiceString(voice.name) || id,
				language,
				extractVoiceString(voice.quality),
			].filter(Boolean)
				.join(' · ')
			return { id, label, group: 'Piper voices' }
		})
		.filter(Boolean)
		.sort((a, b) => a.label.localeCompare(b.label))
	const custom = ttsCustomVoices.value
		.map((voice) => {
			const id = typeof voice.id === 'string' ? voice.id : ''
			if (!id) {
				return null
			}
			return { id, label: voice.name || id, group: 'Custom voices' }
		})
		.filter(Boolean)
	return [...custom, ...remote]
})
watch(
	allTtsVoices,
	(voices) => {
		if (!ttsVoicesLoaded.value || !Array.isArray(voices) || voices.length === 0) {
			return
		}
		const current = ttsVoice.value
		if (current && voices.find((voice) => voice.id === current)) {
			return
		}
		if (!current) {
			ttsVoice.value = voices[0].id
			updateTtsVoice()
		}
	}
)
const updateTtsPause = () => {
	setWorkspaceSetting(workspaceId.value, 'polymr.tts.pause_vad', ttsPauseVad.value ? 'true' : 'false')
}
const pickAvatar = async(event) => {
	const file = event.target.files?.[0]
	event.target.value = ''
	if (!file) {
		return
	}
	const resized = await resizeAvatar(file, 256)
	if (!resized) {
		notify('Unable to load avatar image.')
		return
	}
	avatarState.value = {
		url: resized.preview,
		base64: resized.base64,
		contentType: resized.contentType,
		dirty: true
	}
}
const removeAvatar = () => {
	avatarState.value = {
		url: '',
		base64: '',
		contentType: '',
		dirty: true
	}
}
const resizeAvatar = (file, size) => new Promise((resolve) => {
	const reader = new FileReader()
	reader.onload = () => {
		const img = new Image()
		img.onload = () => {
			const canvas = document.createElement('canvas')
			canvas.width = size
			canvas.height = size
			const ctx = canvas.getContext('2d')
			if (!ctx) {
				resolve(null)
				return
			}
			const minSide = Math.min(img.width, img.height)
			const sx = (img.width - minSide) / 2
			const sy = (img.height - minSide) / 2
			ctx.drawImage(img, sx, sy, minSide, minSide, 0, 0, size, size)
			const dataUrl = canvas.toDataURL('image/png')
			const base64 = dataUrl.split(',')[1] || ''
			resolve({ preview: dataUrl, base64, contentType: 'image/png' })
		}
		img.onerror = () => resolve(null)
		img.src = reader.result
	}
	reader.onerror = () => resolve(null)
	reader.readAsDataURL(file)
})
const updateQueueing = () => {
	saveSmartQueueing(smartQueueing.value)
}
const openRuleCreate = () => {
	ruleEditId.value = ''
	ruleForm.value = { name: '', content: '', enabled: true }
	ruleModal.value = true
}
const openRuleEdit = (rule) => {
	ruleEditId.value = rule.id
	ruleForm.value = { name: rule.name || '', content: rule.content || '', enabled: rule.enabled ?? true }
	ruleModal.value = true
}
const saveRule = async() => {
	status.value = ''
	if (!userId) {
		notify('No active session.')
		return
	}
	if (!ruleForm.value.content.trim()) {
		notify('Rule content is required.')
		return
	}
	const payload = { name: ruleForm.value.name, content: ruleForm.value.content, enabled: ruleForm.value.enabled }
	if (ruleEditId.value) {
		const updated = await updateUserRule(userId, ruleEditId.value, payload)
		userRules.value = userRules.value.map((rule) => (rule.id === updated.id ? updated : rule))
		notify('Rule updated.')
	}
	else {
		const created = await createUserRule(userId, payload)
		userRules.value = [...userRules.value, created]
		notify('Rule created.')
	}
	ruleModal.value = false
}
const requestRemoveRule = (rule) => {
	status.value = ''
	if (!userId) {
		notify('No active session.')
		return
	}
	ruleToDelete.value = rule
	deleteRuleOpen.value = true
}
const confirmRemoveRule = async() => {
	if (!userId) {
		notify('No active session.')
		return
	}
	const rule = ruleToDelete.value
	if (!rule) {
		return
	}
	await deleteUserRule(userId, rule.id)
	userRules.value = userRules.value.filter((item) => item.id !== rule.id)
	ruleToDelete.value = null
	notify('Rule deleted.')
}
const cancelRemoveRule = () => {
	ruleToDelete.value = null
}
onMounted(loadData)
</script>
<template>
	<section class="profile-view">
		<header class="section-header">
			<div>
				<p class="eyebrow">Profile</p>
				<h1>Your account details</h1>
				<p class="subtle">Update your OpenID profile fields and personal rules.</p>
			</div>
		</header>
		<div class="profile-grid">
			<article class="panel">
				<h2>Identity</h2>
				<form class="stack" @submit.prevent="saveProfile">
					<label class="field">
						<span>Email</span>
						<input
							v-model="profile.email"
							type="email"
							autocomplete="email"/>
					</label>
					<label class="field">
						<span>Nickname</span>
						<input
							v-model="profile.nickname"
							type="text"
							autocomplete="nickname"/>
					</label>
					<div class="avatar-field">
						<div class="avatar-preview">
							<img
								v-if="avatarState.url"
								:src="avatarState.url"
								alt="Avatar"/>
							<span v-else class="avatar-initial">
								{{ profile.nickname || profile.email ? (profile.nickname || profile.email).charAt(0).toUpperCase() : '?' }}
							</span>
						</div>
						<div class="avatar-actions">
							<label class="control size-s ghost upload">
								<input
									type="file"
									accept="image/*"
									@change="pickAvatar"/>
								Upload avatar
							</label>
							<button
								class="control size-s ghost"
								type="button"
								:disabled="!avatarState.url"
								@click="removeAvatar">Remove</button>
						</div>
					</div>
					<label class="switch">
						<input
							v-model="smartQueueing"
							type="checkbox"
							@change="updateQueueing"/>
						<span>Smart queueing</span>
						<span
							class="control size-xs ghost icon-button icon-ghost tooltip"
							data-tip="When enabled, queued messages keep sending even if you switch sessions. When disabled, queued messages are dropped when you leave the session.">
							<svg
								viewBox="0 0 24 24"
								aria-hidden="true"
								focusable="false">
								<path
									fill="currentColor"
									d="M11 10h2v7h-2v-7zm0-3h2v2h-2V7zm1-5C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18a8 8 0 1 1 0-16 8 8 0 0 1 0 16z"/>
							</svg>
						</span>
					</label>
					<label class="field">
						<span>Snooze notifications until</span>
						<input v-model="snoozeInput" type="datetime-local"/>
						<p class="subtle">Leave empty to receive notifications as normal.</p>
					</label>
					<label class="field">
						<span>Script and page execution mode</span>
						<select v-model="profile.execution_mode">
							<option value="released">Released</option>
							<option value="latest">Latest draft</option>
						</select>
						<p class="subtle">
							Released is the default. Latest draft runs your newest draft version when one exists, otherwise the released version.
						</p>
					</label>
					<label class="switch">
						<input
							v-model="ttsPauseVad"
							type="checkbox"
							@change="updateTtsPause"/>
						<span>Pause microphone during TTS playback</span>
						<span
							class="control size-xs ghost icon-button icon-ghost tooltip"
							data-tip="Disable this if you use a headset and want to keep speaking while audio plays. When enabled, the mic stops listening during TTS to prevent feedback loops.">
							<svg
								viewBox="0 0 24 24"
								aria-hidden="true"
								focusable="false">
								<path
									fill="currentColor"
									d="M11 10h2v7h-2v-7zm0-3h2v2h-2V7zm1-5C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18a8 8 0 1 1 0-16 8 8 0 0 1 0 16z"/>
							</svg>
						</span>
					</label>
					<label class="switch">
						<input
							v-model="useExactLocation"
							type="checkbox"
							@change="updateLocationPreference"/>
						<span>Use exact location</span>
						<span
							class="control size-xs ghost icon-button icon-ghost tooltip"
							data-tip="When enabled, Polymr uses your device location in prompts so location-based queries work better.">
							<svg
								viewBox="0 0 24 24"
								aria-hidden="true"
								focusable="false">
								<path
									fill="currentColor"
									d="M11 10h2v7h-2v-7zm0-3h2v2h-2V7zm1-5C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18a8 8 0 1 1 0-16 8 8 0 0 1 0 16z"/>
							</svg>
						</span>
					</label>
					<label class="field">
						<span>TTS voice</span>
						<select
							v-model="ttsVoice"
							@change="updateTtsVoice"
							:disabled="ttsVoicesLoading">
							<option v-if="ttsVoicesLoading" value="">Loading voices…</option>
							<template v-else>
								<optgroup
									v-for="group in ['Custom voices', 'Piper voices']"
									:key="group"
									:label="group">
									<option
										v-for="voice in allTtsVoices.filter((entry) => entry.group === group)"
										:key="voice.id"
										:value="voice.id">{{ voice.label }}</option>
								</optgroup>
							</template>
						</select>
						<p v-if="ttsVoicesError" class="form-error">{{ ttsVoicesError }}</p>
						<p v-else class="subtle">Voices download on first use.</p>
					</label>
					<button class="control size-m secondary" type="submit">Save profile</button>
				</form>
			</article>
			<article class="panel">
				<div class="section-head">
					<h2>Your rules</h2>
					<button
						class="control size-m secondary"
						type="button"
						@click="openRuleCreate">Add rule</button>
				</div>
				<p class="subtle">These preferences are injected into your system prompt for interactive sessions.</p>
				<div class="tenant-list">
					<div
						v-for="rule in userRules"
						:key="rule.id"
						class="list-row">
						<div>
							<strong>{{ rule.name || 'Untitled rule' }}</strong>
							<p class="subtle">{{ rule.enabled ? 'Enabled' : 'Disabled' }}</p>
						</div>
						<div class="row-actions">
							<button
								class="control size-xs ghost"
								type="button"
								@click="openRuleEdit(rule)">Edit</button>
							<button
								class="control size-xs ghost"
								type="button"
								@click="requestRemoveRule(rule)">Delete</button>
						</div>
					</div>
					<p v-if="userRules.length === 0" class="empty">No rules yet.</p>
				</div>
				<div v-if="status" class="toast">{{ status }}</div>
			</article>
			<article class="panel">
				<div class="section-head">
					<h2>Custom voices</h2>
				</div>
				<p class="subtle">Stored on this device only. Upload a voice model pair to use it in TTS.</p>
				<div class="stack">
					<label class="field">
						<span>Voice name</span>
						<input
							v-model="ttsUploadName"
							type="text"
							placeholder="My voice"/>
					</label>
					<label class="field">
						<span>.onnx model</span>
						<input
							type="file"
							accept=".onnx"
							@change="(event) => (ttsUploadOnnx = event.target.files[0] || null)"/>
					</label>
					<label class="field">
						<span>.json config</span>
						<input
							type="file"
							accept=".json"
							@change="(event) => (ttsUploadJson = event.target.files[0] || null)"/>
					</label>
					<p v-if="ttsUploadError" class="form-error">{{ ttsUploadError }}</p>
					<button
						class="control size-m secondary"
						type="button"
						:disabled="ttsUploadSaving"
						@click="uploadCustomVoice">{{ ttsUploadSaving ? 'Uploading…' : 'Upload voice' }}</button>
				</div>
				<div class="tenant-list" style="margin-top: var(--space-m);">
					<div
						v-for="voice in ttsCustomVoices"
						:key="voice.slug"
						class="list-row">
						<div>
							<strong>{{ voice.name }}</strong>
							<p class="subtle">{{ voice.slug }}</p>
						</div>
						<div class="row-actions">
							<button
								class="control size-xs ghost"
								type="button"
								@click="downloadCustomVoice(voice, 'onnx')">Download .onnx</button>
							<button
								class="control size-xs ghost"
								type="button"
								@click="downloadCustomVoice(voice, 'json')">Download .json</button>
							<button
								class="control size-xs ghost danger"
								type="button"
								@click="removeCustomVoice(voice)">Remove</button>
						</div>
					</div>
					<p v-if="ttsCustomVoices.length === 0" class="empty">No custom voices yet.</p>
				</div>
			</article>
		</div>
		<ConfirmModal
			v-model:open="deleteRuleOpen"
			title="Delete rule"
			:message="deleteRuleMessage"
			confirm-label="Delete"
			:destructive="true"
			@confirm="confirmRemoveRule"
			@cancel="cancelRemoveRule"/>
		<div
			v-if="ruleModal"
			class="sheet-backdrop"
			@click.self="ruleModal = false">
			<div class="sidepane">
				<button
					class="control size-s ghost icon-button icon-ghost sidepane-close"
					type="button"
					aria-label="Close"
					@click="ruleModal = false">
					<svg
						viewBox="0 0 24 24"
						aria-hidden="true"
						focusable="false">
						<path
							fill="currentColor"
							d="M18.3 5.71a1 1 0 0 0-1.41 0L12 10.59 7.11 5.7a1 1 0 0 0-1.41 1.42L10.59 12l-4.89 4.89a1 1 0 1 0 1.41 1.41L12 13.41l4.89 4.89a1 1 0 0 0 1.41-1.41L13.41 12l4.89-4.89a1 1 0 0 0 0-1.4z"/>
					</svg>
				</button>
				<h2>{{ ruleEditId ? 'Edit rule' : 'Create rule' }}</h2>
				<div class="stack">
					<label class="field">
						<span>Name</span>
						<input
							v-model="ruleForm.name"
							type="text"
							placeholder="Rule name"/>
					</label>
					<label class="field">
						<span>Content</span>
						<textarea
							v-model="ruleForm.content"
							rows="6"
							placeholder="Rule content"></textarea>
					</label>
					<label class="switch">
						<input v-model="ruleForm.enabled" type="checkbox"/>
						<span>Enabled</span>
					</label>
					<div class="row-actions">
						<button
							class="control size-m secondary"
							type="button"
							@click="saveRule">Save</button>
						<button
							class="control size-m ghost"
							type="button"
							@click="ruleModal = false">Cancel</button>
					</div>
				</div>
			</div>
		</div>
	</section>
</template>
