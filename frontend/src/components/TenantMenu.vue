<script setup>
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
const props = defineProps({ workspaces: { type: Array, default: () => [] }, collapsed: { type: Boolean, default: false } })
const emit = defineEmits(['create-workspace', 'toggle-collapse'])
const route = useRoute()
const router = useRouter()
const maybeCollapse = () => {
	if (typeof window !== 'undefined' && window.innerWidth <= 900 && !props.collapsed) {
		emit('toggle-collapse')
	}
}
const createWorkspace = () => {
	emit('create-workspace')
	maybeCollapse()
}
const openDashboard = () => {
	router.push('/tenant')
	maybeCollapse()
}
const openWorkspace = (id) => {
	if (!id) {
		return
	}
	router.push(`/workspace/${id}`)
	maybeCollapse()
}
const handleWorkspaceClick = (event, id) => {
	if (!event || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) {
		return
	}
	event.preventDefault()
	openWorkspace(id)
}
const toggleCollapse = () => emit('toggle-collapse')
const workspaceLabel = (workspace) => workspace?.name || 'Workspace'
const workspaceInitial = (workspace) => {
	const label = workspaceLabel(workspace)
	return label ? label.trim().charAt(0).toUpperCase() : '#'
}
const sortedWorkspaces = computed(() => [...(props.workspaces || [])].sort((a, b) => workspaceLabel(a).localeCompare(workspaceLabel(b))))
const isDashboardActive = computed(() => route.path === '/tenant')
</script>
<template>
	<aside class="workspace-menu tenant-menu" :class="{ collapsed }">
		<div class="menu-top">
			<div v-if="!collapsed" class="menu-title">
				<span>Tenant</span>
			</div>
			<div v-else class="menu-status">
				<span class="control size-xs status-pill icon-only">T</span>
			</div>
		</div>
		<div class="menu-actions">
			<button
				class="control primary menu-action menu-button tooltip"
				type="button"
				@click="createWorkspace"
				data-tip="Add workspace">
				<span class="menu-icon">
					<svg
						viewBox="0 0 24 24"
						aria-hidden="true"
						focusable="false">
						<path fill="currentColor" d="M11 5h2v14h-2zM5 11h14v2H5z"/>
					</svg>
				</span>
				<span class="menu-text">Add workspace</span>
			</button>
			<button
				class="control ghost menu-action menu-button tooltip"
				:class="{ active: isDashboardActive }"
				type="button"
				@click="openDashboard"
				data-tip="Dashboard">
				<span class="menu-icon">
					<svg
						viewBox="0 0 24 24"
						aria-hidden="true"
						focusable="false">
						<path
							fill="currentColor"
							d="M3 13h8V3H3v10zm0 8h8v-6H3v6zm10 0h8V11h-8v10zm0-18v6h8V3h-8z"/>
					</svg>
				</span>
				<span class="menu-text">Dashboard</span>
			</button>
		</div>
		<div class="menu-divider" role="presentation"></div>
		<div class="menu-section">
			<div v-if="!collapsed" class="menu-section-title">Workspaces</div>
			<div class="menu-list">
				<a
					v-for="workspace in sortedWorkspaces"
					:key="workspace.id"
					class="control size-s menu-item menu-button"
					:href="`/workspace/${workspace.id}`"
					@click="handleWorkspaceClick($event, workspace.id)">
					<span class="menu-session-icon" aria-hidden="true">{{ workspaceInitial(workspace) }}</span>
					<span v-if="!collapsed" class="menu-text">{{ workspaceLabel(workspace) }}</span>
				</a>
				<p v-if="sortedWorkspaces.length === 0 && !collapsed" class="empty">No workspaces yet.</p>
			</div>
		</div>
		<div class="menu-bottom">
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
