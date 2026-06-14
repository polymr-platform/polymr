<script setup>
import { computed, onMounted, ref } from 'vue';
import { createRule, deleteRule, getRules, getTenants, loadActiveTenant, saveActiveTenant, updateRule } from '../api';
const tenants = ref([])
const activeTenantId = ref(loadActiveTenant())
const rules = ref([])
const status = ref('')
const editId = ref('')
const form = ref({
	name: '',
	content: '',
	always_included: false,
	enabled: true,
	order: null
})
const loadData = async() => {
	tenants.value = await getTenants()
	if (!activeTenantId.value) {
		activeTenantId.value = tenants.value[0]?.id || ''
	}
	if (!activeTenantId.value) {
		return
	}
	saveActiveTenant(activeTenantId.value)
	rules.value = await getRules(activeTenantId.value)
}
const resetForm = () => {
	form.value = {
		name: '',
		content: '',
		always_included: false,
		enabled: true,
		order: null
	}
	editId.value = ''
}
const handleSubmit = async() => {
	status.value = ''
	if (!activeTenantId.value) {
		status.value = 'Select a tenant.'
		return
	}
	if (!form.value.name.trim() || !form.value.content.trim()) {
		status.value = 'Name and content are required.'
		return
	}
	const payload = { ...form.value, order: form.value.order === '' ? null : form.value.order }
	if (editId.value) {
		const updated = await updateRule(activeTenantId.value, editId.value, payload)
		rules.value = rules.value.map((rule) => (rule.id === updated.id ? updated : rule))
		status.value = 'Rule updated.'
	}
	else {
		const created = await createRule(activeTenantId.value, payload)
		rules.value = [...rules.value, created]
		status.value = 'Rule created.'
	}
	resetForm()
}
const startEdit = (rule) => {
	editId.value = rule.id
	form.value = {
		name: rule.name,
		content: rule.content || '',
		always_included: rule.always_included,
		enabled: rule.enabled,
		order: rule.order ?? null
	}
}
const handleDelete = async(rule) => {
	status.value = ''
	if (!activeTenantId.value) {
		status.value = 'Select a tenant.'
		return
	}
	const confirmed = window.confirm(`Delete rule "${rule.name}"? This also removes it from assistants using it.`)
	if (!confirmed) {
		return
	}
	await deleteRule(activeTenantId.value, rule.id)
	rules.value = rules.value.filter((entry) => entry.id !== rule.id)
	if (editId.value === rule.id) {
		resetForm()
	}
	status.value = 'Rule deleted.'
}
const handleTenantChange = async() => {
	saveActiveTenant(activeTenantId.value)
	await loadData()
	resetForm()
}
const activeTenant = computed(() => tenants.value.find((tenant) => tenant.id === activeTenantId.value))
onMounted(loadData)
</script>
<template>
	<section class="detail-view">
		<header class="section-header">
			<div>
				<p class="eyebrow">Rules</p>
				<h1>Rule catalog</h1>
				<p class="subtle">Define always-included and opt-in prompt fragments.</p>
			</div>
			<div class="tenant-switcher">
				<label for="tenant-select">Active tenant</label>
				<select
					id="tenant-select"
					v-model="activeTenantId"
					@change="handleTenantChange">
					<option
						v-for="tenant in tenants"
						:key="tenant.id"
						:value="tenant.id">{{ tenant.name }}</option>
				</select>
			</div>
		</header>
		<div class="tenant-actions">
			<article class="panel">
				<h2>{{ editId ? 'Edit rule' : 'Create rule' }}</h2>
				<div class="stack">
					<label class="field">
						<span>Name</span>
						<input
							v-model="form.name"
							type="text"
							placeholder="Rule name"/>
					</label>
					<label class="field">
						<span>Content</span>
						<textarea
							v-model="form.content"
							rows="15"
							placeholder="Rule content"></textarea>
					</label>
					<div class="inline-switches">
						<label class="switch">
							<input v-model="form.always_included" type="checkbox"/>
							<span>Always include</span>
						</label>
						<label class="switch">
							<input v-model="form.enabled" type="checkbox"/>
							<span>Enabled</span>
						</label>
					</div>
					<label class="field">
						<span>Order (optional)</span>
						<button
							class="control size-xs ghost icon-button icon-ghost tooltip"
							type="button"
							data-tip="Higher numbers run first. Defaults to 0."
							aria-label="Order help">
							<svg
								viewBox="0 0 24 24"
								aria-hidden="true"
								focusable="false">
								<path
									fill="currentColor"
									d="M11 10h2v7h-2v-7zm0-3h2v2h-2V7zm1-5C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18a8 8 0 1 1 0-16 8 8 0 0 1 0 16z"/>
							</svg>
						</button>
						<input
							v-model="form.order"
							type="number"
							placeholder="10"/>
					</label>
					<div class="row-actions">
						<button
							class="control size-m secondary"
							type="button"
							@click="handleSubmit">{{ editId ? 'Update rule' : 'Create rule' }}</button>
						<button
							v-if="editId"
							class="control size-m ghost"
							type="button"
							@click="resetForm">Cancel</button>
					</div>
					<p v-if="status" class="form-status">{{ status }}</p>
				</div>
			</article>
		</div>
		<div class="tenant-grid">
			<article
				v-for="rule in rules"
				:key="rule.id"
				class="panel">
				<h2>{{ rule.name }}</h2>
				<p class="subtle">{{ rule.always_included ? 'Always included' : 'Opt-in' }}</p>
				<p>{{ rule.content }}</p>
				<p class="subtle">
					{{ rule.always_included ? 'Always included' : 'Opt-in' }} · {{ rule.enabled ? 'Enabled' : 'Disabled' }}
				</p>
				<div class="row-actions">
					<button
						class="control size-xs icon-button tooltip"
						type="button"
						aria-label="Edit rule"
						data-tip="Edit"
						@click="startEdit(rule)">
						<svg
							viewBox="0 0 24 24"
							aria-hidden="true"
							focusable="false">
							<path
								fill="currentColor"
								d="M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zm2.92 2.33H5v-.92l8.06-8.06.92.92L5.92 19.58zM20.71 7.04a1.003 1.003 0 0 0 0-1.42l-2.34-2.34a1.003 1.003 0 0 0-1.42 0l-1.83 1.83 3.75 3.75 1.84-1.82z"/>
						</svg>
					</button>
					<button
						class="control size-xs icon-button tooltip"
						type="button"
						aria-label="Delete rule"
						data-tip="Delete"
						@click="handleDelete(rule)">
						<svg
							viewBox="0 0 24 24"
							aria-hidden="true"
							focusable="false">
							<path
								fill="currentColor"
								d="M6 7h12l-1 14H7L6 7zm4-3h4l1 2H9l1-2zm-5 2h14v2H5V6z"/>
						</svg>
					</button>
				</div>
			</article>
			<article v-if="rules.length === 0" class="panel">
				<h2>No rules yet</h2>
				<p>Create the first rule for {{ activeTenant?.name || 'this tenant' }}.</p>
			</article>
		</div>
	</section>
</template>
