<script setup>
import { computed, ref, watch } from 'vue';
const props = defineProps({
	scopes: { type: Array, default: () => [] },
	allowScopes: { type: Array, default: () => [] },
	denyScopes: { type: Array, default: () => [] },
	dynamicScopes: { type: Array, default: () => [] },
	editable: { type: Boolean, default: true },
	allowDynamic: { type: Boolean, default: true },
	allowLabel: { type: String, default: 'Allow' },
	denyLabel: { type: String, default: 'Deny' },
	dynamicLabel: { type: String, default: 'Dynamic' }
})
const emit = defineEmits(['update:scopes', 'update:allowScopes', 'update:denyScopes'])
const normalizedScopes = computed(() => {
	const items = new Set()
	const addValues = (values) => {
		if (!Array.isArray(values)) {
			return
		}
		values.forEach((scope) => {
				if (typeof scope === 'string' && scope.trim()) {
					items.add(scope.trim())
				}
			})
	}
	addValues(props.scopes)
	addValues(props.allowScopes)
	addValues(props.denyScopes)
	addValues(props.dynamicScopes)
	return Array.from(items).sort()
})
const allowSet = computed(() => new Set((props.allowScopes || []).filter(Boolean)))
const denySet = computed(() => new Set((props.denyScopes || []).filter(Boolean)))
const buildTree = (scopes) => {
	const nodeByPath = new Map()
	const roots = new Map()
	scopes.forEach((scope) => {
			const parts = String(scope).split(':').filter((part) => part.trim())
			let parentPath = ''
			parts.forEach((part) => {
					const path = parentPath ? `${parentPath}:${part}` : part
					let node = nodeByPath.get(path)
					if (!node) {
						node = { name: part, path, children: [] }
						nodeByPath.set(path, node)
						if (parentPath) {
							nodeByPath.get(parentPath).children.push(node)
						}
						else {
							roots.set(path, node)
						}
					}
					parentPath = path
				})
		})
	const sortNodes = (nodes) => {
		nodes.sort((a, b) => a.name.localeCompare(b.name))
		nodes.forEach((node) => {
				if (node.children.length) {
					sortNodes(node.children)
				}
			})
	}
	const rootNodes = Array.from(roots.values())
	sortNodes(rootNodes)
	return rootNodes
}
const tree = computed(() => buildTree(normalizedScopes.value))
const computeAutoExpanded = (nodes) => {
	const expanded = new Set()
	const hasExplicit = (path) => allowSet.value.has(path) || denySet.value.has(path)
	const walk = (node) => {
		let descendantExplicit = false
		node.children.forEach((child) => {
				if (walk(child)) {
					descendantExplicit = true
				}
			})
		if (descendantExplicit) {
			expanded.add(node.path)
		}
		return descendantExplicit || hasExplicit(node.path)
	}
	nodes.forEach((node) => walk(node))
	return expanded
}
const expanded = ref(new Set())
watch(
	[tree, allowSet, denySet],
	() => {
		expanded.value = computeAutoExpanded(tree.value)
	},
	{ immediate: true }
)
const toggleExpand = (path) => {
	if (!path) {
		return
	}
	const next = new Set(expanded.value)
	if (next.has(path)) {
		next.delete(path)
	}
	else {
		next.add(path)
	}
	expanded.value = next
}
const scopeState = (path) => {
	if (allowSet.value.has(path)) {
		return 'allow'
	}
	if (denySet.value.has(path)) {
		return 'deny'
	}
	return 'dynamic'
}
const setScopeState = (path, state) => {
	if (!props.editable || !path) {
		return
	}
	const allow = new Set(props.allowScopes || [])
	const deny = new Set(props.denyScopes || [])
	if (state === 'allow') {
		allow.add(path)
		deny.delete(path)
	}
	else if (state === 'deny') {
		deny.add(path)
		allow.delete(path)
	}
	else {
		allow.delete(path)
		deny.delete(path)
	}
	const nextAllow = Array.from(allow).sort()
	const nextDeny = Array.from(deny).sort()
	emit('update:scopes', { allow: nextAllow, deny: nextDeny })
	emit('update:allowScopes', nextAllow)
	emit('update:denyScopes', nextDeny)
}
const flattenedNodes = computed(() => {
	const output = []
	const walk = (nodes, depth) => {
		nodes.forEach((node) => {
				output.push({ node, depth })
				if (node.children.length && expanded.value.has(node.path)) {
					walk(node.children, depth + 1)
				}
			})
	}
	walk(tree.value, 0)
	return output
})
</script>
<template>
	<div class="scope-viewer">
		<p v-if="!normalizedScopes.length" class="subtle">No scopes available yet.</p>
		<div v-else class="scope-list">
			<div
				v-for="item in flattenedNodes"
				:key="item.node.path"
				class="scope-row">
				<div class="scope-meta">
					<span class="scope-indent" :style="{ width: `${item.depth * 18}px` }"></span>
					<button
						v-if="item.node.children.length"
						class="scope-expand"
						type="button"
						@click="toggleExpand(item.node.path)"
						:aria-label="expanded.has(item.node.path) ? 'Collapse scope' : 'Expand scope'">
						<svg
							v-if="expanded.has(item.node.path)"
							viewBox="0 0 24 24"
							aria-hidden="true"
							focusable="false">
							<path
								fill="none"
								stroke="currentColor"
								stroke-width="1.6"
								stroke-linecap="round"
								stroke-linejoin="round"
								d="M4 6a2 2 0 0 1 2-2h5l2 2h5a2 2 0 0 1 2 2v3H4V6zm0 5h17a1 1 0 0 1 .97 1.24l-1.6 6.4a2 2 0 0 1-1.94 1.52H6a2 2 0 0 1-2-2v-7z"/>
						</svg>
						<svg
							v-else
							viewBox="0 0 24 24"
							aria-hidden="true"
							focusable="false">
							<path
								fill="none"
								stroke="currentColor"
								stroke-width="1.6"
								stroke-linecap="round"
								stroke-linejoin="round"
								d="M4 6a2 2 0 0 1 2-2h6l2 2h4a2 2 0 0 1 2 2v2H4V6zm0 4h16v8a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-8z"/>
						</svg>
					</button>
					<span
						v-else
						class="scope-icon"
						aria-hidden="true">
						<svg
							viewBox="0 0 24 24"
							focusable="false"
							aria-hidden="true">
							<path
								fill="none"
								stroke="currentColor"
								stroke-width="1.6"
								stroke-linecap="round"
								stroke-linejoin="round"
								d="M6 4h7l5 5v11a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2zm6 1.5V9h3.5L12 5.5z"/>
						</svg>
					</span>
					<button
						v-if="item.node.children.length"
						class="scope-name scope-toggle-name"
						type="button"
						@click="toggleExpand(item.node.path)">{{ item.node.name }}</button>
					<span v-else class="scope-name">{{ item.node.name }}</span>
				</div>
				<div
					class="scope-toggle"
					role="group"
					aria-label="Scope decision">
					<button
						class="scope-choice deny"
						:class="{ active: scopeState(item.node.path) === 'deny' }"
						type="button"
						:disabled="!editable"
						@click="setScopeState(item.node.path, 'deny')">{{ denyLabel }}</button>
					<button
						class="scope-choice dynamic"
						:class="{ active: scopeState(item.node.path) === 'dynamic' }"
						type="button"
						:disabled="!editable || !allowDynamic"
						@click="setScopeState(item.node.path, 'dynamic')">{{ dynamicLabel }}</button>
					<button
						class="scope-choice allow"
						:class="{ active: scopeState(item.node.path) === 'allow' }"
						type="button"
						:disabled="!editable"
						@click="setScopeState(item.node.path, 'allow')">{{ allowLabel }}</button>
				</div>
			</div>
		</div>
	</div>
</template>
<style scoped>
.scope-viewer {
	display: grid;
	gap: var(--space-s);
}

.scope-list {
	display: grid;
	gap: var(--space-2xs);
}

.scope-row {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: var(--space-m);
	padding: var(--space-xs) var(--space-m);
	border-radius: var(--border-radius-s);
	background: color-mix(in srgb, var(--bg-panel) 90%, transparent);
}

.scope-meta {
	display: inline-flex;
	align-items: center;
	gap: var(--space-xs);
	min-width: 0;
}

.scope-indent {
	height: 100%;
	background-image: repeating-linear-gradient( 90deg, color-mix(in srgb, var(--text-muted) 35%, transparent) 0 1px, transparent 1px 18px);
}

.scope-name {
	color: var(--text-primary);
	font-size: var(--font-size-s);
	white-space: nowrap;
	overflow: hidden;
	text-overflow: ellipsis;
}

.scope-toggle-name {
	border: none;
	background: transparent;
	padding: 0;
	cursor: pointer;
	text-align: left;
}

.scope-expand, .scope-icon {
	width: 24px;
	height: 24px;
	display: inline-flex;
	align-items: center;
	justify-content: center;
	border: none;
	background: transparent;
	color: var(--text-muted);
	cursor: pointer;
	padding: 0;
	opacity: 0.65;
}

.scope-expand svg, .scope-icon svg {
	width: 18px;
	height: 18px;
}

.scope-toggle {
	display: inline-flex;
	align-items: center;
	gap: 0;
	background: color-mix(in srgb, var(--bg-panel-strong) 92%, transparent);
	padding: 2px;
	border-radius: 999px;
	border: 1px solid color-mix(in srgb, var(--border-strong, var(--text-soft)) 45%, transparent);
}

.scope-choice {
	border: 1px solid transparent;
	background: transparent;
	color: var(--text-muted);
	font-size: calc(var(--font-size-2xs) * 0.9);
	padding: 3px 10px;
	border-radius: 999px;
	cursor: pointer;
	text-transform: uppercase;
	letter-spacing: 0.08em;
}

.scope-choice:disabled {
	cursor: not-allowed;
	opacity: 0.5;
}

.scope-choice.active.deny {
	background: color-mix(in srgb, var(--color-danger) 70%, transparent);
	border-color: color-mix(in srgb, var(--color-danger) 80%, transparent);
	color: var(--text-on-danger, #fff);
}

.scope-choice.deny {
	border-color: color-mix(in srgb, var(--color-danger) 55%, transparent);
	color: color-mix(in srgb, var(--color-danger) 75%, var(--text-muted));
	border-top-right-radius: 0;
	border-bottom-right-radius: 0;
}

.scope-choice.active.dynamic {
	background: color-mix(in srgb, var(--text-muted) 45%, transparent);
	border-color: color-mix(in srgb, var(--text-muted) 55%, transparent);
	color: var(--text-on-muted, #fff);
}

.scope-choice.dynamic {
	border-color: color-mix(in srgb, var(--text-muted) 35%, transparent);
	color: var(--text-muted);
	border-radius: 0;
}

.scope-choice.active.allow {
	background: color-mix(in srgb, var(--color-success) 70%, transparent);
	border-color: color-mix(in srgb, var(--color-success) 80%, transparent);
	color: var(--text-on-success, #fff);
}

.scope-choice.allow {
	border-color: color-mix(in srgb, var(--color-success) 55%, transparent);
	color: color-mix(in srgb, var(--color-success) 75%, var(--text-muted));
	border-top-left-radius: 0;
	border-bottom-left-radius: 0;
}
</style>
