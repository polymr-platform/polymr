<script setup>
import { computed, onMounted, ref, watch } from 'vue';
import { getPushStatus, getPushVapidKey, registerPushSubscription, updatePushPreference, loadActiveTenant } from '../api';
const props = defineProps({ workspaceId: { type: String, required: true } })
const pushStatus = ref({ configured: false, has_subscription: false, workspace_enabled: false })
const pushLoading = ref(false)
const pushError = ref('')
const tooltipText = computed(() => {
	if (pushError.value) {
		return `Notifications error: ${pushError.value}`
	}
	return 'Only affects you in this workspace and only on devices you have subscribed.'
})
const loadPushStatus = async() => {
	if (!props.workspaceId) {
		return
	}
	const tenantId = loadActiveTenant()
	if (!tenantId) {
		return
	}
	pushLoading.value = true
	pushError.value = ''
	try {
		pushStatus.value = await getPushStatus(tenantId, props.workspaceId)
	}
	catch (error) {
		pushError.value = error?.message || 'Unable to load notification settings.'
	}
	finally {
		pushLoading.value = false
	}
}
const registerPush = async() => {
	pushError.value = ''
	if (!('serviceWorker' in navigator) || !('PushManager' in window)) {
		pushError.value = 'Push notifications are not supported in this browser.'
		return
	}
	try {
		const { public_key: publicKey } = await getPushVapidKey()
		const registration = await navigator.serviceWorker.register('/sw.js')
		const applicationServerKey = urlBase64ToUint8Array(publicKey)
		let subscription = await registration.pushManager.getSubscription()
		if (subscription
				&& !hasMatchingApplicationServerKey(subscription.options?.applicationServerKey, applicationServerKey)) {
			await subscription.unsubscribe()
			subscription = null
		}
		if (!subscription) {
			subscription = await registration.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey })
		}
		const json = subscription.toJSON()
		await registerPushSubscription({
			endpoint: subscription.endpoint,
			p256dh: json.keys?.p256dh,
			auth: json.keys?.auth,
			user_agent: navigator.userAgent
		})
		await loadPushStatus()
	}
	catch (error) {
		pushError.value = error?.message || 'Unable to register for push notifications.'
	}
}
const updateWorkspaceNotifications = async(enabled) => {
	if (!props.workspaceId) {
		return
	}
	const tenantId = loadActiveTenant()
	if (!tenantId) {
		return
	}
	pushLoading.value = true
	pushError.value = ''
	try {
		pushStatus.value = await updatePushPreference(tenantId, props.workspaceId, { enabled })
	}
	catch (error) {
		pushError.value = error?.message || 'Unable to update notification settings.'
	}
	finally {
		pushLoading.value = false
	}
}
const toggleWorkspaceNotifications = async(enabled) => {
	if (!enabled) {
		await updateWorkspaceNotifications(false)
		return
	}
	if (!pushStatus.value.has_subscription) {
		await registerPush()
		if (!pushStatus.value.has_subscription) {
			return
		}
	}
	await updateWorkspaceNotifications(true)
}
const hasMatchingApplicationServerKey = (existingKey, expectedKey) => {
	if (!existingKey || existingKey.byteLength !== expectedKey.byteLength) {
		return false
	}
	const existingArray = new Uint8Array(existingKey)
	for (let i = 0; i < existingArray.length; ++i) {
		if (existingArray[i] !== expectedKey[i]) {
			return false
		}
	}
	return true
}
const urlBase64ToUint8Array = (base64String) => {
	const padding = '='.repeat((4 - (base64String.length % 4)) % 4)
	const base64 = (base64String + padding).replace(/-/g, '+').replace(/_/g, '/')
	const rawData = window.atob(base64)
	const outputArray = new Uint8Array(rawData.length)
	for (let i = 0; i < rawData.length; ++i) {
		outputArray[i] = rawData.charCodeAt(i)
	}
	return outputArray
}
onMounted(loadPushStatus)
watch(() => props.workspaceId, loadPushStatus)
</script>
<template>
	<div class="workspace-notifications tooltip tip-down" :data-tip="tooltipText">
		<span class="notif-label">Workspace notifications</span>
		<div
			class="notif-toggle"
			role="group"
			aria-label="Workspace notifications">
			<button
				class="notif-choice off"
				:class="{ active: !pushStatus.workspace_enabled }"
				type="button"
				:disabled="pushLoading || !pushStatus.configured"
				@click="toggleWorkspaceNotifications(false)">Off</button>
			<button
				class="notif-choice on"
				:class="{ active: pushStatus.workspace_enabled }"
				type="button"
				:disabled="pushLoading || !pushStatus.configured"
				@click="toggleWorkspaceNotifications(true)">On</button>
		</div>
	</div>
</template>
<style scoped>
.workspace-notifications {
	display: inline-flex;
	align-items: center;
	gap: var(--space-xs);
}

.notif-label {
	font-size: var(--font-size-xs);
	color: var(--text-soft);
}

.notif-toggle {
	display: inline-flex;
	align-items: center;
	gap: 0;
	background: color-mix(in srgb, var(--bg-panel-strong) 92%, transparent);
	padding: 1px;
	border-radius: 999px;
	border: 1px solid color-mix(in srgb, var(--border-strong, var(--text-soft)) 45%, transparent);
}

.notif-choice {
	border: 1px solid transparent;
	background: transparent;
	color: var(--text-muted);
	font-size: calc(var(--font-size-2xs) * 0.85);
	padding: 2px 8px;
	border-radius: 999px;
	cursor: pointer;
	text-transform: uppercase;
	letter-spacing: 0.08em;
}

.notif-choice:disabled {
	cursor: not-allowed;
	opacity: 0.5;
}

.notif-choice.active.off {
	background: color-mix(in srgb, var(--color-danger) 70%, transparent);
	border-color: color-mix(in srgb, var(--color-danger) 80%, transparent);
	color: var(--text-on-danger, #fff);
}

.notif-choice.off {
	border-color: color-mix(in srgb, var(--color-danger) 55%, transparent);
	color: color-mix(in srgb, var(--color-danger) 75%, var(--text-muted));
	border-top-right-radius: 0;
	border-bottom-right-radius: 0;
}

.notif-choice.active.on {
	background: color-mix(in srgb, var(--color-success) 70%, transparent);
	border-color: color-mix(in srgb, var(--color-success) 80%, transparent);
	color: var(--text-on-success, #fff);
}

.notif-choice.on {
	border-color: color-mix(in srgb, var(--color-success) 55%, transparent);
	color: color-mix(in srgb, var(--color-success) 75%, var(--text-muted));
	border-top-left-radius: 0;
	border-bottom-left-radius: 0;
}
</style>
