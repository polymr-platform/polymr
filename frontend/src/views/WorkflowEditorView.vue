<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { VueFlow, Handle, Position } from '@vue-flow/core';
import JsonSchemaEditor from '../components/JsonSchemaEditor.vue';
import ConditionGroupEditor from '../components/ConditionGroupEditor.vue';
import ConfirmModal from '../components/ConfirmModal.vue';
import ScopeViewer from '../components/ScopeViewer.vue';
import WorkflowStateSchemaModal from '../components/WorkflowStateSchemaModal.vue';
import { jsonSchemaMeta } from '../data/jsonSchemaMeta';
import {
	createWorkflow,
	deleteWorkflow,
	getAssistants,
	getWorkspaceAssistants,
	getSkills,
	getWorkspaceUsers,
	getMcpServers,
	getMcpServerTools,
	getWorkflows,
	updateWorkflow,
	getWorkflowDraft,
	updateWorkflowDraft,
	approveWorkflow,
	deleteWorkflowDraft,
	loadActiveTenant,
	getScript,
	createScript
} from '../api';
const route = useRoute()
const router = useRouter()
const tenantId = ref(loadActiveTenant())
const workspaceId = computed(() => String(route.params.workspaceId || ''))
const workflowId = computed(() => String(route.params.workflowId || ''))
const isNew = computed(() => workflowId.value === 'new')
const project = (point) => point
const nodes = ref([])
const edges = ref([])
const selectedNodeId = ref('')
const selectedEdgeId = ref('')
const workflowName = ref('')
const workflowDescription = ref('')
const workflowDisabled = ref(false)
const workflowVisibility = ref('WORKSPACE')
const workflowParticipantIds = ref([])
const startNodeId = ref('')
const endNodeIds = ref([])
const returnNodeIds = ref([])
const loading = ref(false)
const error = ref('')
const saving = ref(false)
const approving = ref(false)
const approveStatus = ref('')
const deleteDraftOpen = ref(false)
const deleteNodeOpen = ref(false)
const pendingNodeDeleteId = ref('')
const deleteOpen = ref(false)
const connectMode = ref(false)
const ctrlPressed = ref(false)
const assistants = ref([])
const skills = ref([])
const workspaceToolScopes = ref([])
const workspaceTools = ref([])
const workspaceUsers = ref([])
const toolFilter = ref('')
const expandedToolServers = ref(new Set())
const showHandles = computed(() => connectMode.value || ctrlPressed.value)
const schemaEditorSchema = jsonSchemaMeta
const workflowStateSchema = ref({ type: 'object' })
const workflowStateModalOpen = ref(false)
const workflowStateSchemaExpanded = ref(false)
const selectedNode = computed(() => nodes.value.find((node) => node.id === selectedNodeId.value))
const selectedEdge = computed(() => edges.value.find((edge) => edge.id === selectedEdgeId.value))
const selectedEdgeSourceNode = computed(() => nodes.value.find((node) => node.id === selectedEdge.value?.source))
const assistantsById = computed(() => assistants.value.reduce(
	(map, assistant) => {
		map[assistant.id] = assistant
		return map
	},
	{}
))
const skillsById = computed(() => skills.value.reduce(
	(map, skill) => {
		map[skill.id] = skill
		return map
	},
	{}
))
const assistantSkills = (assistantId) => {
	const assistant = assistantsById.value[assistantId]
	if (!assistant || !Array.isArray(assistant.skill_ids)) {
		return []
	}
	return assistant.skill_ids.map((id) => skillsById.value[id]).filter(Boolean)
}
const selectedAssistantSkills = computed(() => {
	const assistantId = selectedNode.value?.data?.assistant_id
	return assistantId ? assistantSkills(assistantId) : []
})
const edgeRouteOptions = (sourceType) => {
	if (sourceType === 'for_each') {
		return [
			{ value: 'loop', label: 'Loop', help: 'Run this branch for the current item in the loop.' },
			{ value: 'done', label: 'Done', help: 'Continue here after the loop has processed all items.' },
		]
	}
	if (sourceType === 'router') {
		return [
			{
				value: 'action',
				label: 'Action',
				help: 'Eligible action branches are queued and executed by the router.'
			},
			{
				value: 'done',
				label: 'Done',
				help: 'Continue here after the router has finished all queued actions.'
			},
		]
	}
	return [
		{ value: 'default', label: 'Default', help: 'Standard workflow transition when this node completes.' },
	]
}
const selectedEdgeRouteOptions = computed(() => edgeRouteOptions(
	selectedEdgeSourceNode.value?.data?.type
))
const selectedEdgeRouteHelp = computed(() => {
	const route = selectedEdge.value?.data?.route || 'default'
	const option = selectedEdgeRouteOptions.value.find((entry) => entry.value === route)
	return option?.help || 'Select how this edge should behave when the source node finishes.'
})
const ensureNodeDefaults = (node) => {
	if (!node || !node.data) {
		return
	}
	if (!node.data.output_schema) {
		node.data.output_schema = {}
	}
	if (!node.data.scopes) {
		node.data.scopes = { allow_scopes: [], deny_scopes: [] }
	}
	if (node.data.type === 'for_each') {
		if (node.data.items_field === undefined) {
			node.data.items_field = ''
		}
		if (!node.data.item_field) {
			node.data.item_field = 'item'
		}
		if (!node.data.index_field) {
			node.data.index_field = 'item_index'
		}
	}
	if (node.data.type === 'script') {
		if (!node.data.script_id) {
			node.data.script_id = ''
		}
		if (!node.data.script_name) {
			node.data.script_name = ''
		}
	}
	if (!node.data.mcp) {
		node.data.mcp = { allowed_tools: [] }
	}
	if (!Array.isArray(node.data.mcp.allowed_tools)) {
		node.data.mcp.allowed_tools = []
	}
}
watch(
	[selectedNode, assistants, skills],
	() => {
		if (!selectedNode.value) {
			return
		}
		ensureNodeDefaults(selectedNode.value)
		if (selectedNode.value.data?.type !== 'ai') {
			return
		}
		const pinned = selectedNode.value.data?.pinned_skill_id
		if (!pinned) {
			return
		}
		const available = selectedAssistantSkills.value
		const exists = available.some((skill) => skill.id === pinned)
		if (!exists) {
			selectedNode.value.data.pinned_skill_id = ''
		}
	}
)
const exampleValueFromSchema = (schema) => {
	if (!schema || typeof schema !== 'object') {
		return ''
	}
	if (schema.type === 'object' || schema.properties) {
		const value = {}
		Object.entries(schema.properties || {})
			.forEach(([name, child]) => {
				value[name] = exampleValueFromSchema(child)
			})
		return value
	}
	if (schema.type === 'array') {
		return [exampleValueFromSchema(schema.items || { type: 'string' })]
	}
	if (schema.type === 'integer' || schema.type === 'number') {
		return 0
	}
	if (schema.type === 'boolean') {
		return false
	}
	return ''
}
const getSchemaAtPath = (schema, path) => {
	if (!schema || typeof schema !== 'object') {
		return null
	}
	const parts = String(path || '').split('.').map((part) => part.trim()).filter(Boolean)
	let current = schema
	for (const part of parts) {
		if (!current || typeof current !== 'object') {
			return null
		}
		if (current.type === 'array') {
			current = current.items || null
		}
		if (!current || typeof current !== 'object') {
			return null
		}
		const properties = current.properties && typeof current.properties === 'object' ? current.properties : null
		if (!properties || !properties[part]) {
			return null
		}
		current = properties[part]
	}
	return current
}
const pruneDerivedForEachFields = (schema, definitionNodes) => {
	const source = schema && typeof schema === 'object' ? JSON.parse(JSON.stringify(schema)) : { type: 'object' }
	if (source.type !== 'object') {
		source.type = 'object'
	}
	if (!source.properties || typeof source.properties !== 'object' || Array.isArray(source.properties)) {
		source.properties = {}
	}
	const required = Array.isArray(source.required) ? new Set(source.required) : new Set()
	Object.values(definitionNodes || {})
		.forEach((node) => {
			if (node?.type !== 'for_each') {
				return
			}
			const itemField = String(node.item_field || '').trim()
			const itemsField = String(node.items_field || '').trim()
			if (!itemField || !itemsField || itemField.includes('.')) {
				return
			}
			const itemSchema = getSchemaAtPath(source, itemsField)
			if (!itemSchema || itemSchema.type !== 'array') {
				return
			}
			delete source.properties[itemField]
			required.delete(itemField)
		})
	if (required.size) {
		source.required = Array.from(required)
	}
	else {
		delete source.required
	}
	return source
}
const collectVariableOptions = (schema, prefix = '') => {
	if (!schema || typeof schema !== 'object') {
		return []
	}
	const output = []
	if (prefix) {
		output.push({ value: prefix, label: prefix })
	}
	const target = schema.type === 'array' ? schema.items : schema
	const properties = target
			&& typeof target === 'object'
			&& target.properties
			&& typeof target.properties === 'object'
		? target.properties
		: null
	if (!properties) {
		return output
	}
	Object.entries(properties)
		.forEach(([name, child]) => {
			const path = prefix ? `${prefix}.${name}` : name
			output.push(...collectVariableOptions(child, path))
		})
	return output
}
const collectArrayFieldOptions = (schema, prefix = '') => {
	if (!schema || typeof schema !== 'object') {
		return []
	}
	const output = []
	if (schema.type === 'array' && prefix) {
		output.push({ value: prefix, label: prefix })
	}
	const target = schema.type === 'array' ? schema.items : schema
	const properties = target
			&& typeof target === 'object'
			&& target.properties
			&& typeof target.properties === 'object'
		? target.properties
		: null
	if (!properties) {
		return output
	}
	Object.entries(properties)
		.forEach(([name, child]) => {
			const path = prefix ? `${prefix}.${name}` : name
			output.push(...collectArrayFieldOptions(child, path))
		})
	return output
}
const collectReachableNodeIds = (startId, allowedRoutes = null) => {
	if (!startId) {
		return new Set()
	}
	const visited = new Set()
	const queue = [startId]
	while (queue.length) {
		const currentId = queue.shift()
		if (!currentId || visited.has(currentId)) {
			continue
		}
		visited.add(currentId)
		edges.value
			.forEach((edge) => {
				if (edge.source !== currentId) {
					return
				}
				if (currentId === startId
						&& Array.isArray(allowedRoutes)
						&& !allowedRoutes.includes(edge.data?.route || 'default')) {
					return
				}
				if (!visited.has(edge.target)) {
					queue.push(edge.target)
				}
			})
	}
	return visited
}
const foreachScopes = computed(() => {
	const scopes = []
	nodes.value
		.forEach((node) => {
			if (node?.data?.type !== 'for_each') {
				return
			}
			const itemAlias = String(node.data.item_field || '').trim()
			const itemsField = String(node.data.items_field || '').trim()
			if (!itemAlias || !itemsField) {
				return
			}
			const arraySchema = getSchemaAtPath(workflowStateSchema.value || { type: 'object' }, itemsField)
			const itemSchema = arraySchema?.type === 'array' ? arraySchema.items || null : null
			if (!itemSchema) {
				return
			}
			scopes.push({
				nodeId: node.id,
				itemAlias,
				indexAlias: String(node.data.index_field || '').trim(),
				itemsField,
				itemSchema,
				reachableNodeIds: collectReachableNodeIds(node.id, ['loop'])
			})
		})
	return scopes
})
const scopedSchemaForNode = (nodeId) => {
	const base = workflowStateSchema.value
			&& typeof workflowStateSchema.value === 'object'
		? JSON.parse(JSON.stringify(workflowStateSchema.value))
		: { type: 'object' }
	if (base.type !== 'object') {
		base.type = 'object'
	}
	if (!base.properties || typeof base.properties !== 'object' || Array.isArray(base.properties)) {
		base.properties = {}
	}
	foreachScopes.value
		.forEach((scope) => {
			if (!scope.reachableNodeIds.has(nodeId)) {
				return
			}
			base.properties[scope.itemAlias] = scope.itemSchema
			if (scope.indexAlias) {
				base.properties[scope.indexAlias] = { type: 'integer' }
			}
		})
	return base
}
const conditionSchemaForSelectedEdge = computed(() => {
	if (!selectedEdgeSourceNode.value?.id) {
		return { type: 'object', properties: {} }
	}
	return scopedSchemaForNode(selectedEdgeSourceNode.value.id)
})
const conditionVariableOptions = computed(() => collectVariableOptions(
	conditionSchemaForSelectedEdge.value
))
const foreachItemsFieldOptions = computed(() => {
	if (!selectedNode.value || selectedNode.value.data?.type !== 'for_each') {
		return []
	}
	const schema = scopedSchemaForNode(selectedNode.value.id)
	const options = collectArrayFieldOptions(schema)
	const current = String(selectedNode.value.data.items_field || '').trim()
	if (current && !options.some((option) => option.value === current)) {
		options.unshift({ value: current, label: `${current} (current)` })
	}
	return options
})
const workflowStateExampleJson = computed(() => JSON.stringify(exampleValueFromSchema(workflowStateSchema.value || { type: 'object' }), null, 2))
const buildDefaultDefinition = () => ({
	type: 'graph',
	name: 'New workflow',
	start: 'node-1',
	end_nodes: [],
	return_nodes: [],
	session_visibility: 'WORKSPACE',
	participant_ids: [],
	state_schema: { type: 'object' },
	nodes: {
		'node-1': {
			type: 'ai',
			name: '',
			goal: '',
			assistant_id: '',
			pinned_skill_id: '',
			output_schema: {},
			scopes: { allow_scopes: [], deny_scopes: [] },
			position: { x: 120, y: 120 }
		}
	},
	edges: {}
})
const toFlow = (definition) => {
	const nodeEntries = Object.entries(definition.nodes || {})
	nodes.value = nodeEntries.map(([id, config]) => ({
		id,
		type: 'step',
		position: config.position || { x: 100, y: 100 },
		data: {
			...config,
			output_schema: config.output_schema || {},
			scopes: config.scopes || { allow_scopes: [], deny_scopes: [] }
		}
	}))
	nodes.value.forEach((node) => ensureNodeDefaults(node))
	edges.value = []
	Object.entries(definition.edges || {})
		.forEach(([source, edgeDef]) => {
			if (Array.isArray(edgeDef)) {
				edgeDef.forEach((target, index) => {
						edges.value.push(buildEdge(source, target, index))
					})
				return
			}
			if (!edgeDef || typeof edgeDef !== 'object') {
				return
			}
			if (Array.isArray(edgeDef.conditions)) {
				const groupedConditions = {}
				edgeDef.conditions
					.forEach((entry) => {
						const target = entry?.target
						if (!target) {
							return
						}
						const route = entry?.route || 'default'
						const group = normalizeConditionGroup(entry.when, entry.mode)
						if (!group) {
							return
						}
						const key = `${route}::${target}`
						if (!groupedConditions[key]) {
							groupedConditions[key] = []
						}
						groupedConditions[key].push(group)
					})
				Object.entries(groupedConditions)
					.forEach(([key, groups]) => {
						const [route, target] = key.split('::')
						const edgeKey = `${source}__${route}__${target}`
						const group = groups.length === 1 ? groups[0] : { kind: 'group', mode: 'or', conditions: groups }
						edges.value.push(buildEdge(source, target, edgeKey, group, route))
					})
			}
			Object.entries(edgeDef.routes || {})
				.forEach(([route, targets]) => {
					if (!Array.isArray(targets)) {
						return
					}
					targets.forEach((target, index) => {
							edges.value.push(buildEdge(source, target, `${source}-${route}-${index}`, null, route))
						})
				})
			if (Array.isArray(edgeDef.default)) {
				edgeDef.default
					.forEach((target, index) => {
						edges.value.push(buildEdge(source, target, `${source}-default-${index}`, null, 'default'))
					})
			}
		})
	edges.value = normalizeEdges(edges.value)
	workflowName.value = definition.name || ''
	workflowDescription.value = definition.description || ''
	workflowVisibility.value = (definition.session_visibility || 'WORKSPACE').toUpperCase()
	workflowParticipantIds.value = Array.isArray(definition.participant_ids) ? definition.participant_ids : []
	workflowStateSchema.value = definition.state_schema || { type: 'object' }
	startNodeId.value = definition.start || nodeEntries[0]?.[0] || ''
	endNodeIds.value = Array.isArray(definition.end_nodes) ? definition.end_nodes.filter(Boolean) : []
	returnNodeIds.value = Array.isArray(definition.return_nodes) ? definition.return_nodes.filter(Boolean) : []
}
const toDefinition = () => {
	const nodeMap = {}
	nodes.value
		.forEach((node) => {
			nodeMap[node.id] = { ...node.data, position: node.position }
			if (!nodeMap[node.id].scopes) {
				nodeMap[node.id].scopes = { allow_scopes: [], deny_scopes: [] }
			}
		})
	const edgeMap = {}
	const grouped = {}
	edges.value
		.forEach((edge) => {
			if (!grouped[edge.source]) {
				grouped[edge.source] = []
			}
			grouped[edge.source].push(edge)
		})
	Object.entries(grouped)
		.forEach(([source, list]) => {
			const routes = {}
			const conditionList = []
			list.forEach((edge) => {
					const route = edge.data?.route || 'default'
					if (edge.data?.conditionGroup) {
						const entry = buildConditionEntry(edge)
						if (entry) {
							entry.route = route
							conditionList.push(entry)
							return
						}
					}
					if (!routes[route]) {
						routes[route] = []
					}
					routes[route].push(edge.target)
				})
			const routeKeys = Object.keys(routes)
			if (conditionList.length === 0 && routeKeys.length === 1 && routeKeys[0] === 'default') {
				edgeMap[source] = routes.default
				return
			}
			const edgeDef = {}
			if (conditionList.length) {
				edgeDef.conditions = conditionList
			}
			if (routes.default) {
				edgeDef.default = routes.default
			}
			Object.entries(routes)
				.forEach(([route, targets]) => {
					if (route === 'default') {
						return
					}
					edgeDef.routes = edgeDef.routes || {}
					edgeDef.routes[route] = targets
				})
			edgeMap[source] = edgeDef
		})
	return {
		type: 'graph',
		name: workflowName.value || 'Untitled workflow',
		description: workflowDescription.value || '',
		start: startNodeId.value || nodes.value[0]?.id || '',
		end_nodes: endNodeIds.value.filter(Boolean),
		return_nodes: returnNodeIds.value.filter(Boolean),
		session_visibility: workflowVisibility.value || 'WORKSPACE',
		participant_ids: workflowVisibility.value === 'PRIVATE' ? workflowParticipantIds.value : [],
		state_schema: pruneDerivedForEachFields(workflowStateSchema.value || { type: 'object' }, nodeMap),
		nodes: nodeMap,
		edges: edgeMap
	}
}
const loadWorkflow = async() => {
	if (!tenantId.value || !workspaceId.value) {
		return
	}
	loading.value = true
	error.value = ''
	try {
		if (isNew.value) {
			toFlow(buildDefaultDefinition())
			workflowDisabled.value = false
			return
		}
		const list = await getWorkflows(tenantId.value, workspaceId.value)
		const workflow = list.find((item) => String(item.id) === workflowId.value)
		if (!workflow) {
			error.value = 'Workflow not found.'
			return
		}
		const draft = await getWorkflowDraft(tenantId.value, workspaceId.value, workflowId.value)
		const definition = draft?.definition_json || buildDefaultDefinition()
		toFlow(definition)
		workflowDisabled.value = !!workflow.disabled
		workflowName.value = workflow.name || ''
		workflowDescription.value = workflow.description || ''
		await loadWorkflowNodeScripts()
	}
	catch (loadError) {
		error.value = loadError?.message || 'Unable to load workflow.'
	}
	finally {
		loading.value = false
	}
}
const loadCatalogs = async() => {
	if (!tenantId.value) {
		return
	}
	try {
		const [tenantAssistantsList, workspaceAssistantsList] = await Promise.all([
			getAssistants(tenantId.value),
			getWorkspaceAssistants(tenantId.value, workspaceId.value),
		])
		assistants.value = [...tenantAssistantsList, ...workspaceAssistantsList]
		skills.value = await getSkills(tenantId.value)
		await loadWorkspaceToolScopes()
		await loadWorkspaceTools()
		await loadWorkspaceUsers()
	}
	catch (catalogError) {
		error.value = catalogError?.message || 'Unable to load assistants or skills.'
	}
}
const loadNodeScript = async(node) => {
	if (!tenantId.value || !workspaceId.value || !node?.data?.script_id) {
		return
	}
	try {
		const script = await getScript(tenantId.value, workspaceId.value, node.data.script_id)
		node.data.script_name = script?.name || node.data.script_name || ''
	}
	catch {}
}
const loadWorkflowNodeScripts = async() => {
	const scriptNodes = nodes.value.filter((node) => node?.data?.type === 'script' && node.data?.script_id)
	await Promise.allSettled(scriptNodes.map((node) => loadNodeScript(node)))
}
const loadWorkspaceUsers = async() => {
	try {
		const response = await getWorkspaceUsers(tenantId.value)
		workspaceUsers.value = Array.isArray(response?.users) ? response.users : []
	}
	catch {
		workspaceUsers.value = []
	}
}
const loadWorkspaceToolScopes = async() => {
	try {
		const servers = await getMcpServers(tenantId.value, workspaceId.value)
		if (!Array.isArray(servers) || !servers.length) {
			workspaceToolScopes.value = []
			return
		}
		const results = await Promise.allSettled(servers.map((server) => getMcpServerTools(tenantId.value, workspaceId.value, server.id)))
		const scopes = new Set()
		results.forEach((result) => {
				if (result.status !== 'fulfilled' || !Array.isArray(result.value)) {
					return
				}
				result.value
					.forEach((tool) => {
						if (Array.isArray(tool.custom_scopes)) {
							tool.custom_scopes.forEach((scope) => scope && scopes.add(scope))
						}
						if (Array.isArray(tool.scopes)) {
							tool.scopes.forEach((scope) => scope && scopes.add(scope))
						}
					})
			})
		workspaceToolScopes.value = Array.from(scopes).sort()
	}
	catch {
		workspaceToolScopes.value = []
	}
}
const loadWorkspaceTools = async() => {
	try {
		const servers = await getMcpServers(tenantId.value, workspaceId.value)
		if (!Array.isArray(servers) || !servers.length) {
			workspaceTools.value = []
			return
		}
		const results = await Promise.allSettled(servers.map((server) => getMcpServerTools(tenantId.value, workspaceId.value, server.id)))
		const tools = []
		results.forEach((result, index) => {
				if (result.status !== 'fulfilled' || !Array.isArray(result.value)) {
					return
				}
				const server = servers[index]
				result.value
					.forEach((tool) => {
						const name = (tool?.tool_alias || tool?.tool_name || '').trim()
						if (!name) {
							return
						}
						tools.push({
							name,
							serverId: server?.id,
							serverName: server?.name || 'Server',
							label: `${name} (${server?.name || 'Server'})`
						})
					})
			})
		tools.sort((a, b) => a.label.localeCompare(b.label, undefined, { sensitivity: 'base' }))
		workspaceTools.value = tools
	}
	catch {
		workspaceTools.value = []
	}
}
const toolGroupsForNode = computed(() => {
	const filter = toolFilter.value.trim().toLowerCase()
	const groups = new Map()
	workspaceTools.value
		.forEach((tool) => {
			if (filter && !tool.label.toLowerCase().includes(filter)) {
				return
			}
			const key = tool.serverId || tool.serverName
			if (!groups.has(key)) {
				groups.set(key, { id: key, name: tool.serverName, tools: [] })
			}
			groups.get(key).tools.push(tool)
		})
	const list = Array.from(groups.values())
	list.forEach((group) => {
			group.tools.sort((a, b) => a.label.localeCompare(b.label, undefined, { sensitivity: 'base' }))
		})
	list.sort((a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: 'base' }))
	return list
})
const isToolSelected = (name) => Array.isArray(selectedNode.value?.data?.mcp?.allowed_tools)
	&& selectedNode.value.data.mcp.allowed_tools.includes(name)
const toggleToolSelection = (name) => {
	if (!selectedNode.value?.data?.mcp) {
		return
	}
	const current = Array.isArray(selectedNode.value.data.mcp.allowed_tools)
		? [...selectedNode.value.data.mcp.allowed_tools]
		: []
	const next = current.includes(name) ? current.filter((tool) => tool !== name) : [...current, name]
	selectedNode.value.data.mcp.allowed_tools = next
}
const isServerExpanded = (id) => expandedToolServers.value.has(id)
const toggleServerExpanded = (id) => {
	const next = new Set(expandedToolServers.value)
	if (next.has(id)) {
		next.delete(id)
	}
	else {
		next.add(id)
	}
	expandedToolServers.value = next
}
const serverSelectionState = (group) => {
	const tools = group?.tools || []
	if (!tools.length) {
		return { all: false, some: false }
	}
	let selectedCount = 0
	tools.forEach((tool) => {
			if (isToolSelected(tool.name)) {
				selectedCount += 1
			}
		})
	return { all: selectedCount === tools.length, some: selectedCount > 0 && selectedCount < tools.length }
}
const toggleServerSelection = (group) => {
	if (!selectedNode.value?.data?.mcp) {
		return
	}
	const tools = group?.tools || []
	if (!tools.length) {
		return
	}
	const state = serverSelectionState(group)
	const current = Array.isArray(selectedNode.value.data.mcp.allowed_tools)
		? [...selectedNode.value.data.mcp.allowed_tools]
		: []
	let next
	if (state.all) {
		const toRemove = new Set(tools.map((tool) => tool.name))
		next = current.filter((name) => !toRemove.has(name))
	}
	else {
		const merged = new Set(current)
		tools.forEach((tool) => merged.add(tool.name))
		next = Array.from(merged)
	}
	selectedNode.value.data.mcp.allowed_tools = next
}
const saveWorkflow = async() => {
	if (!tenantId.value || !workspaceId.value) {
		return
	}
	if (workflowVisibility.value === 'PRIVATE' && workflowParticipantIds.value.length === 0) {
		error.value = 'Private workflows must include at least one participant.'
		return
	}
	const definition = toDefinition()
	saving.value = true
	error.value = ''
	try {
		if (isNew.value) {
			const created = await createWorkflow(
				tenantId.value,
				workspaceId.value,
				{
					name: workflowName.value || 'Untitled workflow',
					description: workflowDescription.value || '',
					definition_json: definition,
					disabled: workflowDisabled.value
				}
			)
			router.replace({ name: 'workspace-workflow', params: { workspaceId: workspaceId.value, workflowId: created.id } })
		}
		else {
			await updateWorkflow(
				tenantId.value,
				workspaceId.value,
				workflowId.value,
				{
					name: workflowName.value || 'Untitled workflow',
					description: workflowDescription.value || '',
					disabled: workflowDisabled.value
				}
			)
			await updateWorkflowDraft(tenantId.value, workspaceId.value, workflowId.value, { definition_json: definition })
		}
	}
	catch (saveError) {
		error.value = saveError?.message || 'Unable to save workflow.'
	}
	finally {
		saving.value = false
	}
}
const confirmDeleteWorkflow = async() => {
	if (!tenantId.value || !workspaceId.value || !workflowId.value || isNew.value) {
		return
	}
	try {
		await deleteWorkflow(tenantId.value, workspaceId.value, workflowId.value)
		router.push({ name: 'workspace-detail', params: { workspaceId: workspaceId.value } })
	}
	catch (deleteError) {
		error.value = deleteError?.message || 'Unable to delete workflow.'
	}
	finally {
		deleteOpen.value = false
	}
}
const releaseWorkflow = async() => {
	if (!tenantId.value || !workspaceId.value || !workflowId.value || isNew.value) {
		return
	}
	approving.value = true
	approveStatus.value = ''
	try {
		await approveWorkflow(tenantId.value, workspaceId.value, workflowId.value)
		approveStatus.value = 'Released.'
	}
	catch (approveError) {
		approveStatus.value = approveError?.message || 'Unable to release workflow.'
	}
	finally {
		approving.value = false
	}
}
const removeDraft = async() => {
	if (!tenantId.value || !workspaceId.value || !workflowId.value || isNew.value) {
		return
	}
	try {
		await deleteWorkflowDraft(tenantId.value, workspaceId.value, workflowId.value)
		router.push({ name: 'workspace', params: { workspaceId: workspaceId.value } })
	}
	catch (draftError) {
		error.value = draftError?.message || 'Unable to delete draft.'
	}
	finally {
		deleteDraftOpen.value = false
	}
}
const isSelectedNodeStart = computed(() => !!selectedNode.value && selectedNode.value.id === startNodeId.value)
const isSelectedNodeEnd = computed(() => !!selectedNode.value && endNodeIds.value.includes(selectedNode.value.id))
const isSelectedNodeReturn = computed(() => !!selectedNode.value && returnNodeIds.value.includes(selectedNode.value.id))
const toggleSelectedNodeStart = (checked) => {
	if (!selectedNode.value) {
		return
	}
	if (checked) {
		startNodeId.value = selectedNode.value.id
		return
	}
	if (startNodeId.value === selectedNode.value.id) {
		startNodeId.value = ''
	}
}
const toggleSelectedNodeEnd = (checked) => {
	if (!selectedNode.value) {
		return
	}
	if (checked) {
		if (!endNodeIds.value.includes(selectedNode.value.id)) {
			endNodeIds.value = [...endNodeIds.value, selectedNode.value.id]
		}
		return
	}
	endNodeIds.value = endNodeIds.value.filter((nodeId) => nodeId !== selectedNode.value.id)
}
const toggleSelectedNodeReturn = (checked) => {
	if (!selectedNode.value) {
		return
	}
	if (checked) {
		if (!returnNodeIds.value.includes(selectedNode.value.id)) {
			returnNodeIds.value = [...returnNodeIds.value, selectedNode.value.id]
		}
		return
	}
	returnNodeIds.value = returnNodeIds.value.filter((nodeId) => nodeId !== selectedNode.value.id)
}
const addNode = (type) => {
	const id = `node-${Date.now()}`
	const position = project({ x: 200, y: 200 })
	nodes.value
		.push({
		id,
		type: 'step',
		position,
		data: {
			type,
			name: '',
			goal: '',
			output_schema: {},
			max_tool_calls: ['for_each', 'router', 'step'].includes(type) ? 0 : -1,
			items_field: type === 'for_each' ? '' : undefined,
			item_field: type === 'for_each' ? 'item' : undefined,
			index_field: type === 'for_each' ? 'item_index' : undefined,
			scopes: { allow_scopes: [], deny_scopes: [] },
			mcp: { allowed_tools: [] },
			script_id: type === 'script' ? '' : undefined,
			script_name: type === 'script' ? '' : undefined
		}
	})
	if (!startNodeId.value) {
		startNodeId.value = id
	}
	selectedNodeId.value = id
	selectedEdgeId.value = ''
}
const ensureWorkflowSaved = async() => {
	if (!isNew.value) {
		return workflowId.value
	}
	const definition = toDefinition()
	saving.value = true
	error.value = ''
	try {
		const created = await createWorkflow(
			tenantId.value,
			workspaceId.value,
			{
				name: workflowName.value || 'Untitled workflow',
				description: workflowDescription.value || '',
				definition_json: definition,
				disabled: workflowDisabled.value
			}
		)
		router.replace({ name: 'workspace-workflow', params: { workspaceId: workspaceId.value, workflowId: created.id } })
		return created.id
	}
	catch (saveError) {
		error.value = saveError?.message || 'Unable to save workflow.'
		return null
	}
	finally {
		saving.value = false
	}
}
const createScriptForNode = async() => {
	if (!selectedNode.value || selectedNode.value.data?.type !== 'script') {
		return
	}
	const workflowDefinitionId = await ensureWorkflowSaved()
	if (!workflowDefinitionId) {
		return
	}
	const name = selectedNode.value.data.name?.trim() || 'Workflow script'
	try {
		const script = await createScript(
			tenantId.value,
			workspaceId.value,
			{
				name,
				description: '',
				slug: `${workflowDefinitionId}-${selectedNode.value.id}`.toLowerCase(),
				input_schema: { type: 'object' },
				output_schema: { type: 'object' },
				type: 'WORKFLOW',
				workflow_definition_id: workflowDefinitionId
			}
		)
		selectedNode.value.data.script_id = script.id
		selectedNode.value.data.script_name = script.name
		await updateWorkflowDraft(tenantId.value, workspaceId.value, workflowId.value, { definition_json: toDefinition() })
	}
	catch (createError) {
		error.value = createError?.message || 'Unable to create workflow script.'
	}
}
const openScriptEditor = () => {
	if (!selectedNode.value || selectedNode.value.data?.type !== 'script') {
		return
	}
	const scriptId = selectedNode.value.data.script_id
	if (!scriptId) {
		return
	}
	router.push({
		name: 'workspace-script-editor',
		params: { workspaceId: workspaceId.value, scriptId },
		query: { workflow: '1' }
	})
}
const onConnect = (params) => {
	if (!params?.source || !params?.target) {
		return
	}
	const edge = buildEdge(params.source, params.target, Date.now(), null, defaultEdgeRoute(params.source))
	edges.value.push(edge)
	selectedEdgeId.value = edge.id
	selectedNodeId.value = ''
}
const clearSelection = () => {
	selectedNodeId.value = ''
	selectedEdgeId.value = ''
}
watch(
	() => edges.value.map((edge) => ({ id: edge.id, selected: !!edge.selected })),
	() => {
		const selected = edges.value.find((edge) => edge.selected)
		if (selected) {
			selectedEdgeId.value = selected.id
			selectedNodeId.value = ''
			if (!selected.data?.conditionGroup) {
				selected.data = { ...selected.data, conditionGroup: { kind: 'group', mode: 'and', conditions: [] } }
			}
		}
	}
)
watch(
	() => nodes.value.map((node) => ({ id: node.id, selected: !!node.selected })),
	() => {
		const selected = nodes.value.find((node) => node.selected)
		if (selected) {
			selectedNodeId.value = selected.id
			selectedEdgeId.value = ''
		}
	}
)
const deleteSelectedNode = () => {
	if (!selectedNode.value) {
		return
	}
	const node = selectedNode.value
	if (node.data?.type === 'script' && node.data?.script_id) {
		pendingNodeDeleteId.value = node.id
		deleteNodeOpen.value = true
		return
	}
	removeNode(node.id)
}
const removeNode = (id) => {
	nodes.value = nodes.value.filter((node) => node.id !== id)
	edges.value = edges.value.filter((edge) => edge.source !== id && edge.target !== id)
	if (startNodeId.value === id) {
		startNodeId.value = nodes.value[0]?.id || ''
	}
	endNodeIds.value = endNodeIds.value.filter((nodeId) => nodeId !== id)
	returnNodeIds.value = returnNodeIds.value.filter((nodeId) => nodeId !== id)
	selectedNodeId.value = ''
	selectedEdgeId.value = ''
}
const confirmDeleteNode = () => {
	if (!pendingNodeDeleteId.value) {
		deleteNodeOpen.value = false
		return
	}
	removeNode(pendingNodeDeleteId.value)
	pendingNodeDeleteId.value = ''
	deleteNodeOpen.value = false
}
const deleteSelectedEdge = () => {
	if (!selectedEdge.value) {
		return
	}
	edges.value = edges.value.filter((edge) => edge.id !== selectedEdge.value.id)
	selectedEdgeId.value = ''
}
const addEdgeCondition = () => {
	if (!selectedEdge.value) {
		return
	}
	const edgeId = selectedEdge.value.id
	edges.value = edges.value
		.map((edge) => {
			if (edge.id !== edgeId) {
				return edge
			}
			const group = edge.data?.conditionGroup || { kind: 'group', mode: 'and', conditions: [] }
			const updated = {
				...edge,
				data: {
					...edge.data,
					conditionGroup: {
						...group,
						conditions: [
							...(group.conditions || []),
							{
								kind: 'condition',
								path: '',
								operator: 'equals',
								value: ''
							},
						]
					}
				}
			}
			updated.label = edgeLabel(updated)
			return updated
		})
}
const handleKeydown = (event) => {
	const target = event.target
	const tag = target?.tagName?.toLowerCase()
	if (tag === 'input' || tag === 'textarea' || target?.isContentEditable) {
		return
	}
	if (event.key === 'Delete' || event.key === 'Backspace') {
		if (selectedEdge.value || selectedNode.value) {
			event.preventDefault()
		}
		if (selectedEdge.value) {
			deleteSelectedEdge()
			return
		}
		if (selectedNode.value) {
			deleteSelectedNode()
		}
	}
	if (event.key === 'Control') {
		ctrlPressed.value = true
	}
}
const handleKeyup = (event) => {
	if (event.key === 'Control') {
		ctrlPressed.value = false
	}
}
onMounted(() => {
	loadCatalogs()
	loadWorkflow()
	window.addEventListener('keydown', handleKeydown)
	window.addEventListener('keyup', handleKeyup)
})
onBeforeUnmount(() => {
	window.removeEventListener('keydown', handleKeydown)
	window.removeEventListener('keyup', handleKeyup)
})
const defaultEdgeRoute = (source) => {
	const node = nodes.value.find((entry) => entry.id === source)
	return edgeRouteOptions(node?.data?.type)[0]?.value || 'default'
}
const buildEdge = (source, target, suffix, group, route = 'default') => {
	const edge = {
		id: `${source}-${target}-${suffix}`,
		source,
		target,
		type: 'smoothstep',
		data: { ...(group ? { conditionGroup: group } : {}), route: route || 'default' }
	}
	edge.label = edgeLabel(edge)
	edge.labelStyle = { fill: 'var(--text-muted)' }
	edge.labelBgStyle = { fill: 'rgba(10, 16, 22, 0.8)' }
	edge.labelBgPadding = [6, 4]
	edge.labelBgBorderRadius = 6
	return edge
}
const mergeConditionGroups = (left, right) => {
	if (left && right) {
		return { kind: 'group', mode: 'or', conditions: [left, right] }
	}
	return left || right || null
}
const normalizeEdgeRoute = (edge) => {
	const sourceNode = nodes.value.find((entry) => entry.id === edge.source)
	const options = edgeRouteOptions(sourceNode?.data?.type)
	const requestedRoute = edge.data?.route || 'default'
	return options.some((option) => option.value === requestedRoute)
		? requestedRoute
		: options[0]?.value || 'default'
}
const normalizeEdges = (list) => {
	const byPair = new Map()
	list.forEach((edge) => {
			const route = normalizeEdgeRoute(edge)
			const key = `${edge.source}::${edge.target}::${route}`
			if (!byPair.has(key)) {
				const normalized = { ...edge, data: { ...(edge.data || {}), route } }
				normalized.label = edgeLabel(normalized)
				byPair.set(key, normalized)
				return
			}
			const existing = byPair.get(key)
			const mergedGroup = mergeConditionGroups(existing.data?.conditionGroup, edge.data?.conditionGroup)
			const updated = { ...existing, data: { ...(mergedGroup ? { conditionGroup: mergedGroup } : {}), route } }
			updated.label = edgeLabel(updated)
			byPair.set(key, updated)
		})
	return Array.from(byPair.values())
}
const normalizeConditionGroup = (when, mode) => {
	if (!when || typeof when !== 'object') {
		return null
	}
	if (Array.isArray(when.conditions)) {
		return {
			kind: 'group',
			mode: when.mode || mode || 'and',
			conditions: when.conditions.map((entry) => normalizeConditionGroup(entry)).filter(Boolean)
		}
	}
	const path = when.path || ''
	if (!path) {
		return null
	}
	const valueType = when.value_type || 'string'
	if (when.equals !== undefined) {
		return {
			kind: 'condition',
			path,
			operator: 'equals',
			value: String(when.equals),
			value_type: valueType
		}
	}
	if (when.not_equals !== undefined) {
		return {
			kind: 'condition',
			path,
			operator: 'not_equals',
			value: String(when.not_equals),
			value_type: valueType
		}
	}
	if (Array.isArray(when.in)) {
		return {
			kind: 'condition',
			path,
			operator: 'in',
			value: when.in.join(', '),
			value_type: valueType
		}
	}
	if (when.contains !== undefined) {
		return {
			kind: 'condition',
			path,
			operator: 'contains',
			value: String(when.contains),
			value_type: valueType
		}
	}
	if (when.is_empty === true) {
		return {
			kind: 'condition',
			path,
			operator: 'is_empty',
			value: '',
			value_type: valueType
		}
	}
	if (when.is_not_empty === true) {
		return {
			kind: 'condition',
			path,
			operator: 'is_not_empty',
			value: '',
			value_type: valueType
		}
	}
	if (when.has_value === true) {
		return {
			kind: 'condition',
			path,
			operator: 'has_value',
			value: '',
			value_type: valueType
		}
	}
	if (when.has_no_value === true) {
		return {
			kind: 'condition',
			path,
			operator: 'has_no_value',
			value: '',
			value_type: valueType
		}
	}
	if (when.exists !== undefined) {
		return {
			kind: 'condition',
			path,
			operator: when.exists ? 'has_value' : 'has_no_value',
			value: '',
			value_type: valueType
		}
	}
	return null
}
const cleanConditionGroup = (group) => {
	if (!group) {
		return null
	}
	if (group.kind === 'condition') {
		if (!group.path) {
			return null
		}
		return group
	}
	const cleaned = {
		kind: 'group',
		mode: group.mode || 'and',
		conditions: (group.conditions || []).map(cleanConditionGroup).filter(Boolean)
	}
	return cleaned.conditions.length ? cleaned : null
}
const buildConditionEntry = (edge) => {
	const group = cleanConditionGroup(edge.data?.conditionGroup)
	if (!group) {
		return null
	}
	const when = buildConditionWhen(group)
	if (!when) {
		return null
	}
	return {
		when,
		target: edge.target,
		mode: group.mode,
		route: edge.data?.route || 'default'
	}
}
const buildConditionWhen = (group) => {
	if (!group) {
		return null
	}
	if (group.kind === 'condition') {
		const when = { path: group.path }
		if (group.value_type) {
			when.value_type = group.value_type
		}
		if (group.operator === 'equals') {
			when.equals = group.value
			when.value = group.value
		}
		else if (group.operator === 'not_equals') {
			when.not_equals = group.value
			when.value = group.value
		}
		else if (group.operator === 'in') {
			if (group.value_type === 'variable') {
				when.value = group.value
			}
			else {
				when.in = group.value ? group.value.split(',').map((value) => value.trim()).filter(Boolean) : []
			}
		}
		else if (group.operator === 'contains') {
			when.contains = group.value
			when.value = group.value
		}
		else if (group.operator === 'is_empty') {
			when.is_empty = true
		}
		else if (group.operator === 'is_not_empty') {
			when.is_not_empty = true
		}
		else if (group.operator === 'has_value') {
			when.has_value = true
		}
		else if (group.operator === 'has_no_value') {
			when.has_no_value = true
		}
		return when
	}
	return {
		mode: group.mode || 'and',
		conditions: (group.conditions || []).map(buildConditionWhen).filter(Boolean)
	}
}
const conditionGroupLabel = (group, root = false) => {
	if (group.kind === 'condition') {
		if (group.operator === 'is_empty') {
			return `${group.path} is empty`
		}
		if (group.operator === 'is_not_empty') {
			return `${group.path} is not empty`
		}
		if (group.operator === 'has_value') {
			return `${group.path} has value`
		}
		if (group.operator === 'has_no_value') {
			return `${group.path} has no value`
		}
		if (!group.path || !group.value) {
			return group.path || ''
		}
		const operatorText = group.operator === 'not_equals'
			? '!='
			: group.operator === 'in' ? 'in' : group.operator === 'contains' ? 'contains' : '='
		const valueText = group.value_type === 'variable' ? group.value : `"${group.value}"`
		return `${group.path} ${operatorText} ${valueText}`
	}
	const joiner = group.mode === 'or' ? ' OR ' : ' AND '
	const inner = (group.conditions || []).map((entry) => conditionGroupLabel(entry)).filter(Boolean)
	if (!inner.length) {
		return ''
	}
	if (inner.length === 1) {
		return inner[0]
	}
	const joined = inner.join(joiner)
	return root ? joined : `(${joined})`
}
const edgeLabel = (edge) => {
	const route = edge?.data?.route || 'default'
	const group = cleanConditionGroup(edge?.data?.conditionGroup)
	if (!group) {
		edge.data = { ...edge.data, fullLabel: route }
		return route
	}
	const conditionText = conditionGroupLabel(group, true)
	const full = route === 'default' ? conditionText : `${route}: ${conditionText}`
	const truncated = full.length > 46 ? `${full.slice(0, 46)}…` : full
	edge.data = { ...edge.data, fullLabel: full }
	return truncated
}
</script>
<template>
	<main class="detail-view">
		<header class="section-header">
			<div>
				<h1>Workflow editor</h1>
				<p class="subtle">Design steps and connect them with a simple flow.</p>
			</div>
			<div class="row-actions">
				<button
					v-if="!isNew"
					class="control size-s ghost danger"
					type="button"
					@click="deleteOpen = true">Delete workflow</button>
				<button
					v-if="!isNew"
					class="control size-s ghost danger"
					type="button"
					@click="deleteDraftOpen = true">Delete draft</button>
				<button
					v-if="!isNew"
					class="control size-s"
					type="button"
					:disabled="approving"
					@click="releaseWorkflow">{{ approving ? 'Releasing…' : 'Release workflow' }}</button>
				<button
					class="control size-s secondary"
					type="button"
					:disabled="saving"
					@click="saveWorkflow">{{ saving ? 'Saving…' : 'Save workflow' }}</button>
				<RouterLink class="control size-s ghost" :to="`/workspace/${workspaceId}`">Back</RouterLink>
			</div>
		</header>
		<div class="workflow-toolbar">
			<button
				class="control size-s ghost"
				type="button"
				@click="addNode('ai')">Add AI step</button>
			<button
				class="control size-s ghost"
				type="button"
				@click="addNode('user')">Add user step</button>
			<button
				class="control size-s ghost"
				type="button"
				@click="addNode('script')">Add script step</button>
			<button
				class="control size-s ghost"
				type="button"
				@click="addNode('for_each')">Add for each</button>
			<button
				class="control size-s ghost"
				type="button"
				@click="addNode('router')">Add router</button>
			<button
				class="control size-s ghost"
				type="button"
				@click="addNode('step')">Add step</button>
			<button
				class="control size-s ghost"
				type="button"
				@click="connectMode = !connectMode">{{ connectMode ? 'Drag mode' : 'Connect mode' }}</button>
			<span class="subtle">Hold Ctrl to connect</span>
		</div>
		<p v-if="error" class="form-error">{{ error }}</p>
		<p v-else-if="approveStatus" class="subtle">{{ approveStatus }}</p>
		<div class="workflow-shell">
			<div class="workflow-canvas" :class="{ 'connect-mode': showHandles }">
				<VueFlow
					v-model:nodes="nodes"
					v-model:edges="edges"
					:node-draggable="!connectMode"
					:nodes-connectable="connectMode || ctrlPressed"
					:nodes-selectable="true"
					:elements-selectable="true"
					:select-nodes-on-drag="false"
					:node-types="{ step: 'step' }"
					@connect="onConnect"
					@pane-click="clearSelection">
					<template #node-step="{ data, id }">
						<div
							class="workflow-node-card"
							:class="{ active: id === selectedNodeId }"
							@pointerdown.stop="selectedNodeId = id"
							@click.stop="selectedNodeId = id">
							<Handle
								type="target"
								:position="Position.Left"
								class="workflow-handle"/>
							<div class="workflow-node-type">{{ data.type || 'step' }}</div>
							<div class="workflow-node-name">{{ data.name || 'Untitled' }}</div>
							<div v-if="id === startNodeId" class="workflow-start-badge">Start</div>
							<div v-if="endNodeIds.includes(id)" class="workflow-start-badge">End</div>
							<div v-if="returnNodeIds.includes(id)" class="workflow-start-badge">Return</div>
							<Handle
								v-if="!endNodeIds.includes(id) && !returnNodeIds.includes(id)"
								type="source"
								:position="Position.Right"
								class="workflow-handle"/>
						</div>
					</template>
					<template #edge-label="{ edge }">
						<div class="workflow-edge-label" :title="edge.data?.fullLabel || edge.label">{{ edge.label }}</div>
					</template>
				</VueFlow>
			</div>
			<aside class="workflow-pane">
				<div v-if="selectedNode" class="card stack">
					<label class="field">
						<span>Name</span>
						<input
							v-model="selectedNode.data.name"
							type="text"
							placeholder="Step name"/>
					</label>
					<label class="switch">
						<input
							:checked="isSelectedNodeStart"
							type="checkbox"
							@change="toggleSelectedNodeStart($event.target.checked)"/>
						<span>Start node</span>
					</label>
					<label class="switch">
						<input
							:checked="isSelectedNodeEnd"
							type="checkbox"
							@change="toggleSelectedNodeEnd($event.target.checked)"/>
						<span>End node</span>
					</label>
					<label class="switch">
						<input
							:checked="isSelectedNodeReturn"
							type="checkbox"
							@change="toggleSelectedNodeReturn($event.target.checked)"/>
						<span>Return node</span>
					</label>
					<label v-if="['ai', 'user', 'script'].includes(selectedNode.data.type)" class="field">
						<span>Goal</span>
						<textarea
							v-model="selectedNode.data.goal"
							rows="3"
							placeholder="Describe the task"></textarea>
					</label>
					<label v-if="selectedNode.data.type === 'ai'" class="field">
						<span>Assistant</span>
						<select v-model="selectedNode.data.assistant_id">
							<option value="">Use session assistant</option>
							<option
								v-for="assistant in assistants"
								:key="assistant.id"
								:value="assistant.id">{{ assistant.name || 'Assistant' }}</option>
						</select>
					</label>
					<label
						v-if="selectedNode.data.type === 'ai' && selectedAssistantSkills.length"
						class="field">
						<span>Pinned skill</span>
						<select v-model="selectedNode.data.pinned_skill_id">
							<option value="">None</option>
							<option
								v-for="skill in selectedAssistantSkills"
								:key="skill.id"
								:value="skill.id">{{ skill.name || 'Skill' }}</option>
						</select>
					</label>
					<div v-if="selectedNode.data.type === 'script'" class="field">
						<span>Script</span>
						<div class="script-node-row">
							<div>
								<strong>{{ selectedNode.data.script_name || 'Untitled script' }}</strong>
								<p class="subtle" v-if="selectedNode.data.script_id">Attached</p>
								<p class="subtle" v-else>Not created yet</p>
							</div>
							<div class="row-actions">
								<button
									v-if="!selectedNode.data.script_id"
									class="control size-xs"
									type="button"
									@click="createScriptForNode">Create script</button>
								<button
									v-else
									class="control size-xs"
									type="button"
									@click="openScriptEditor">Open script editor</button>
							</div>
						</div>
						<p class="subtle">This script is dedicated to the selected node.</p>
					</div>
					<label
						v-if="selectedNode.data.type !== 'script' && selectedNode.data.type !== 'for_each' && selectedNode.data.type !== 'router' && selectedNode.data.type !== 'step'"
						class="field">
						<span title="Use -1 for no limit.">Max tool calls</span>
						<input
							v-model.number="selectedNode.data.max_tool_calls"
							type="number"
							placeholder="-1"/>
					</label>
					<div v-if="selectedNode.data.type === 'ai' || selectedNode.data.type === 'user'" class="field">
						<div class="section-head">
							<span>Allowed tools</span>
						</div>
						<input
							v-model="toolFilter"
							type="text"
							placeholder="Filter tools"/>
						<div class="tool-picker">
							<p v-if="!toolGroupsForNode.length" class="subtle">No tools available.</p>
							<div
								v-for="group in toolGroupsForNode"
								:key="group.id"
								class="tool-group">
								<div class="tool-group-header">
									<button
										class="tool-group-toggle"
										type="button"
										@click="() => toggleServerExpanded(group.id)"
										:aria-label="isServerExpanded(group.id) ? 'Collapse tools' : 'Expand tools'"><span class="tool-group-icon">{{ isServerExpanded(group.id) ? '▾' : '▸' }}</span></button>
									<label class="tool-group-check">
										<input
											type="checkbox"
											:checked="serverSelectionState(group).all"
											:indeterminate="serverSelectionState(group).some"
											@change="() => toggleServerSelection(group)"/>
										<span>{{ group.name }}</span>
									</label>
								</div>
								<div v-if="isServerExpanded(group.id)" class="tool-group-list">
									<label
										v-for="tool in group.tools"
										:key="tool.label"
										class="tool-option">
										<input
											type="checkbox"
											:checked="isToolSelected(tool.name)"
											@change="() => toggleToolSelection(tool.name)"/>
										<span>{{ tool.label }}</span>
									</label>
								</div>
							</div>
						</div>
					</div>
					<div v-if="selectedNode.data.type === 'ai'" class="field">
						<div class="section-head">
							<span>Tool scopes</span>
						</div>
						<ScopeViewer
							:scopes="workspaceToolScopes"
							:allow-scopes="selectedNode.data.scopes?.allow_scopes || []"
							:deny-scopes="selectedNode.data.scopes?.deny_scopes || []"
							@update:scopes="(next) => {
                selectedNode.data.scopes = {
                  allow_scopes: next.allow || [],
                  deny_scopes: next.deny || [],
                }
              }"/>
					</div>
					<label v-if="selectedNode.data.type === 'for_each'" class="field">
						<span>Items field</span>
						<select v-model="selectedNode.data.items_field">
							<option value="" disabled>Select an array field</option>
							<option
								v-for="option in foreachItemsFieldOptions"
								:key="option.value"
								:value="option.value">{{ option.label }}</option>
						</select>
						<p class="subtle">Array fields from workflow state and any active parent foreach item aliases.</p>
					</label>
					<label v-if="selectedNode.data.type === 'for_each'" class="field">
						<span>Item alias</span>
						<input
							v-model="selectedNode.data.item_field"
							type="text"
							placeholder="item"/>
						<p class="subtle">Derived from the array item schema at the items field while inside this loop.</p>
					</label>
					<label v-if="selectedNode.data.type === 'for_each'" class="field">
						<span>Index field</span>
						<input
							v-model="selectedNode.data.index_field"
							type="text"
							placeholder="item_index"/>
					</label>
					<div v-if="['ai', 'user', 'script'].includes(selectedNode.data.type)" class="field">
						<div class="section-head">
							<span>Output schema</span>
						</div>
						<JsonSchemaEditor
							class="compact-editor"
							v-model="selectedNode.data.output_schema"
							:schema="schemaEditorSchema"/>
					</div>
					<p v-if="selectedNode.data.type === 'step'" class="subtle">Step node for no-op modeling, layout, or branch convergence.</p>
					<button
						class="control size-xs ghost danger"
						type="button"
						@click="deleteSelectedNode">Delete step</button>
				</div>
				<div v-else-if="selectedEdge" class="card stack">
					<label class="field">
						<span>Route</span>
						<select v-model="selectedEdge.data.route" @change="selectedEdge.label = edgeLabel(selectedEdge)">
							<option
								v-for="option in selectedEdgeRouteOptions"
								:key="option.value"
								:value="option.value">{{ option.label }}</option>
						</select>
						<p class="subtle">{{ selectedEdgeRouteHelp }}</p>
					</label>
					<ConditionGroupEditor
						v-if="selectedEdge.data?.conditionGroup"
						:group="selectedEdge.data.conditionGroup"
						:variable-options="conditionVariableOptions"
						:variable-schema="conditionSchemaForSelectedEdge"
						@change="() => (selectedEdge.label = edgeLabel(selectedEdge))"/>
					<p v-else class="subtle">No conditions yet. This edge is treated as a route-only edge.</p>
					<button
						class="control size-xs ghost danger"
						type="button"
						@click="deleteSelectedEdge">Delete edge</button>
				</div>
				<div v-else class="card">
					<label class="field">
						<span>Workflow name</span>
						<input
							v-model="workflowName"
							type="text"
							placeholder="Workflow name"/>
					</label>
					<label class="field">
						<span>Description</span>
						<textarea
							v-model="workflowDescription"
							rows="3"
							placeholder="Optional summary"></textarea>
					</label>
					<label class="field">
						<span>Session visibility</span>
						<select v-model="workflowVisibility">
							<option value="WORKSPACE">Workspace (default)</option>
							<option value="PRIVATE">Private</option>
						</select>
					</label>
					<div class="field">
						<div class="section-head">
							<span>Workflow state</span>
							<button
								class="control size-xs ghost"
								type="button"
								@click="workflowStateModalOpen = true">Edit state</button>
						</div>
						<p class="subtle">Edit the workflow state in a dedicated popup. Example JSON is shown below by default.</p>
						<div class="section-head">
							<span>Example JSON</span>
						</div>
						<pre class="code-block workflow-state-summary">{{ workflowStateExampleJson }}</pre>
						<div class="section-head">
							<span>JSON Schema</span>
							<button
								class="control size-xs ghost"
								type="button"
								@click="workflowStateSchemaExpanded = !workflowStateSchemaExpanded">{{ workflowStateSchemaExpanded ? 'Hide schema' : 'Show schema' }}</button>
						</div>
						<pre v-if="workflowStateSchemaExpanded" class="code-block workflow-state-summary">{{ JSON.stringify(workflowStateSchema || { type: 'object' }, null, 2) }}</pre>
					</div>
					<label v-if="workflowVisibility === 'PRIVATE'" class="field">
						<span>Participants</span>
						<select v-model="workflowParticipantIds" multiple>
							<option
								v-for="user in workspaceUsers"
								:key="user.id"
								:value="user.id">{{ user.name || user.email }}</option>
						</select>
						<p class="subtle">Select at least one participant.</p>
					</label>
					<label class="switch">
						<input v-model="workflowDisabled" type="checkbox"/>
						<span>Disabled</span>
					</label>
				</div>
			</aside>
		</div>
	</main>
	<ConfirmModal
		v-model:open="deleteOpen"
		title="Delete workflow"
		message="Delete this workflow? This cannot be undone."
		confirm-label="Delete"
		:destructive="true"
		@confirm="confirmDeleteWorkflow"
		@cancel="deleteOpen = false"/>
	<ConfirmModal
		v-model:open="deleteDraftOpen"
		title="Delete draft"
		message="Delete the current draft? This cannot be undone."
		confirm-label="Delete"
		:destructive="true"
		@confirm="removeDraft"
		@cancel="deleteDraftOpen = false"/>
	<ConfirmModal
		v-model:open="deleteNodeOpen"
		title="Remove script step"
		message="Remove this script step? The script stays linked to the workflow history but will be disconnected from this node."
		confirm-label="Remove"
		:destructive="true"
		@confirm="confirmDeleteNode"
		@cancel="deleteNodeOpen = false"/>
	<WorkflowStateSchemaModal
		v-model:open="workflowStateModalOpen"
		v-model="workflowStateSchema"/>
</template>
