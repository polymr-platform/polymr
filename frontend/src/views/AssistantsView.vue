<script setup>
import { computed, onMounted, ref } from 'vue';
import {
	createAssistant,
	getAssistants,
	getPersonas,
	getTenants,
	getWorkspaces,
	loadActiveTenant,
	saveActiveTenant,
	updateAssistant
} from '../api';
const tenants = ref([])
const activeTenantId = ref(loadActiveTenant())
const assistants = ref([])
const personas = ref([])
const workspaces = ref([])
const status = ref('')
const form = ref({
	name: '',
	description: '',
	prompt_text: '',
	persona_id: '',
	workspace_id: '',
	max_output_tokens: '',
	max_turns: ''
})
const editId = ref('')
const activeTenant = computed(() => tenants.value.find((tenant) => tenant.id === activeTenantId.value))
const loadData = async() => {
	tenants.value = await getTenants()
	if (!activeTenantId.value) {
		activeTenantId.value = tenants.value[0]?.id || ''
	}
	if (!activeTenantId.value) {
		return
	}
	saveActiveTenant(activeTenantId.value)
	assistants.value = await getAssistants(activeTenantId.value)
	personas.value = await getPersonas(activeTenantId.value)
	workspaces.value = await getWorkspaces(activeTenantId.value)
}
const resetForm = () => {
	form.value = {
		name: '',
		description: '',
		prompt_text: '',
		persona_id: '',
		workspace_id: '',
		max_output_tokens: '',
		max_turns: ''
	}
	editId.value = ''
}
const handleSubmit = async() => {
	status.value = ''
	if (!activeTenantId.value) {
		status.value = 'Select a tenant.'
		return
	}
	if (!form.value.name.trim() || !form.value.persona_id) {
		status.value = 'Name and persona are required.'
		return
	}
	const payload = {
		name: form.value.name,
		description: form.value.description,
		prompt_text: form.value.prompt_text,
		persona_id: form.value.persona_id,
		workspace_id: form.value.workspace_id || null,
		max_output_tokens: form.value.max_output_tokens ? Number(form.value.max_output_tokens) : null,
		max_turns: form.value.max_turns ? Number(form.value.max_turns) : null
	}
	if (editId.value) {
		const updated = await updateAssistant(activeTenantId.value, editId.value, payload)
		assistants.value = assistants.value.map((assistant) => assistant.id === updated.id ? updated : assistant)
		status.value = 'Assistant updated.'
	}
	else {
		const created = await createAssistant(activeTenantId.value, payload)
		assistants.value = [...assistants.value, created]
		status.value = 'Assistant created.'
	}
	resetForm()
}
const startEdit = (assistant) => {
	editId.value = assistant.id
	form.value = {
		name: assistant.name,
		description: assistant.description || '',
		prompt_text: assistant.prompt_text || '',
		persona_id: assistant.persona_id,
		workspace_id: assistant.workspace_id || '',
		max_output_tokens: assistant.max_output_tokens ?? '',
		max_turns: assistant.max_turns ?? ''
	}
}
const handleTenantChange = async() => {
	saveActiveTenant(activeTenantId.value)
	await loadData()
	resetForm()
}
onMounted(loadData)
</script>
<template>
	<section class="detail-view">
		<header class="section-header">
			<div>
				<p class="eyebrow">Assistants</p>
				<h1>Assistant library</h1>
				<p class="subtle">Create and tune assistant profiles for the active tenant.</p>
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
				<h2>{{ editId ? 'Edit assistant' : 'Create assistant' }}</h2>
				<div class="stack">
					<label class="field">
						<span>Name</span>
						<input
							v-model="form.name"
							type="text"
							placeholder="Assistant name"/>
					</label>
					<label class="field">
						<span>Description</span>
						<input
							v-model="form.description"
							type="text"
							placeholder="Short summary"/>
					</label>
					<label class="field">
						<span>Prompt</span>
						<textarea
							v-model="form.prompt_text"
							rows="4"
							placeholder="Assistant prompt text"></textarea>
					</label>
					<label class="field">
						<span>Persona</span>
						<select v-model="form.persona_id">
							<option value="">Select a persona</option>
							<option
								v-for="persona in personas"
								:key="persona.id"
								:value="persona.id">{{ persona.name }}</option>
						</select>
					</label>
					<label class="field">
						<span>Workspace (optional)</span>
						<select v-model="form.workspace_id">
							<option value="">Tenant-level assistant</option>
							<option
								v-for="workspace in workspaces"
								:key="workspace.id"
								:value="workspace.id">{{ workspace.name }}</option>
						</select>
					</label>
					<label class="field">
						<span>Max output tokens (optional)</span>
						<input
							v-model="form.max_output_tokens"
							type="number"
							min="1"
							placeholder="Use tenant default"/>
					</label>
					<label class="field">
						<span>Max autonomous turns (optional)</span>
						<input
							v-model="form.max_turns"
							type="number"
							min="1"
							placeholder="Unlimited until user feedback"/>
					</label>
					<div class="row-actions">
						<button
							class="control size-m secondary"
							type="button"
							@click="handleSubmit">{{ editId ? 'Update assistant' : 'Create assistant' }}</button>
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
				v-for="assistant in assistants"
				:key="assistant.id"
				class="panel">
				<h2>{{ assistant.name }}</h2>
				<p class="subtle">Persona: {{ assistant.persona_name }}</p>
				<p class="subtle">Max autonomous turns: {{ assistant.max_turns ?? 'Unlimited' }}</p>
				<p>{{ assistant.description || 'No description yet.' }}</p>
				<div class="row-actions">
					<button
						class="control size-m secondary"
						type="button"
						@click="startEdit(assistant)">Edit</button>
				</div>
			</article>
			<article v-if="assistants.length === 0" class="panel">
				<h2>No assistants yet</h2>
				<p>Create your first assistant for {{ activeTenant?.name || 'this tenant' }}.</p>
			</article>
		</div>
	</section>
</template>
