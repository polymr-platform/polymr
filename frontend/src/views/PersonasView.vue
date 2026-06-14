<script setup>
import { computed, onMounted, ref } from 'vue';
import { createPersona, getPersonas, getTenants, loadActiveTenant, saveActiveTenant, updatePersona } from '../api';
const tenants = ref([])
const activeTenantId = ref(loadActiveTenant())
const personas = ref([])
const status = ref('')
const editId = ref('')
const form = ref({ name: '', description: '', prompt_text: '' })
const loadData = async() => {
	tenants.value = await getTenants()
	if (!activeTenantId.value) {
		activeTenantId.value = tenants.value[0]?.id || ''
	}
	if (!activeTenantId.value) {
		return
	}
	saveActiveTenant(activeTenantId.value)
	personas.value = await getPersonas(activeTenantId.value)
}
const resetForm = () => {
	form.value = { name: '', description: '', prompt_text: '' }
	editId.value = ''
}
const handleSubmit = async() => {
	status.value = ''
	if (!activeTenantId.value) {
		status.value = 'Select a tenant.'
		return
	}
	if (!form.value.name.trim()) {
		status.value = 'Name is required.'
		return
	}
	if (editId.value) {
		const updated = await updatePersona(activeTenantId.value, editId.value, form.value)
		personas.value = personas.value.map((persona) => (persona.id === updated.id ? updated : persona))
		status.value = 'Persona updated.'
	}
	else {
		const created = await createPersona(activeTenantId.value, form.value)
		personas.value = [...personas.value, created]
		status.value = 'Persona created.'
	}
	resetForm()
}
const startEdit = (persona) => {
	editId.value = persona.id
	form.value = { name: persona.name, description: persona.description || '', prompt_text: persona.prompt_text || '' }
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
				<p class="eyebrow">Personas</p>
				<h1>Persona library</h1>
				<p class="subtle">Define the primary motivators behind each assistant.</p>
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
				<h2>{{ editId ? 'Edit persona' : 'Create persona' }}</h2>
				<div class="stack">
					<label class="field">
						<span>Name</span>
						<input
							v-model="form.name"
							type="text"
							placeholder="Persona name"/>
					</label>
					<label class="field">
						<span>Description</span>
						<textarea
							v-model="form.description"
							rows="4"
							placeholder="Primary motivator"></textarea>
					</label>
					<label class="field">
						<span>Prompt</span>
						<textarea
							v-model="form.prompt_text"
							rows="5"
							placeholder="Full persona prompt"></textarea>
					</label>
					<div class="row-actions">
						<button
							class="control size-m secondary"
							type="button"
							@click="handleSubmit">{{ editId ? 'Update persona' : 'Create persona' }}</button>
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
				v-for="persona in personas"
				:key="persona.id"
				class="panel">
				<h2>{{ persona.name }}</h2>
				<p>{{ persona.description || 'No description yet.' }}</p>
				<div class="row-actions">
					<button
						class="control size-m secondary"
						type="button"
						@click="startEdit(persona)">Edit</button>
				</div>
			</article>
			<article v-if="personas.length === 0" class="panel">
				<h2>No personas yet</h2>
				<p>Create a persona for {{ activeTenant?.name || 'this tenant' }}.</p>
			</article>
		</div>
	</section>
</template>
