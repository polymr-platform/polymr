<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import ConfirmModal from '../components/ConfirmModal.vue';
import JsonSchemaEditor from '../components/JsonSchemaEditor.vue';
import ScopeViewer from '../components/ScopeViewer.vue';
import WorkspaceNotificationToggle from '../components/WorkspaceNotificationToggle.vue';
import {
	createChannel,
	createMcpServer,
	createMcpServerOverride,
	createMcpServerPolicy,
	createWorkspaceTag,
	createWorkspaceTagValue,
	createWorkspaceRule,
	createWorkspaceSkill,
	cloneMcpServerOAuthFallback,
	logoutMcpServerOAuth,
	createMcpOidcProvider,
	deleteWorkflow,
	deleteMcpServerPolicy,
	deleteMcpServerOverride,
	deleteMcpOidcProvider,
	deleteWorkspaceTag,
	deleteWorkspaceTagValue,
	deleteWorkspaceSkill,
	deleteMcpServer,
	deleteWorkspaceRule,
	deletePage,
	deleteScript,
	exportPage,
	importPages,
	compilePageDraft,
	getAssistant,
	getAssistants,
	getWorkspaceAssistants,
	createAssistant,
	getMcpServers,
	getMcpServerTools,
	getMcpServerApplications,
	getMcpServerOverrides,
	getMcpOidcProviders,
	getWorkspaceTags,
	getWorkspaceTagStates,
	getWorkspaceRules,
	getWorkflows,
	getWorkspaceScopes,
	getWorkspaces,
	getPages,
	getInstalledPages,
	getProfile,
	getScript,
	getScripts,
	getModels,
	getPersonas,
	getSkills,
	getWorkspaceSkills,
	getRules,
	installPage,
	uninstallPage,
	createPage,
	createScript,
	updateScript,
	loadActiveTenant,
	loadSession,
	probeMcpServer,
	startMcpServerOAuth,
	getChannels,
	getMcpServerDefinition,
	getPromptTemplates,
	updateAssistant,
	updateChannel,
	deleteAssistant,
	deleteChannel,
	updateMcpServerTools,
	refreshMcpServerTools,
	discoverMcpServerOAuth,
	updateMcpServerPolicy,
	updateMcpServerOverride,
	updateMcpServerApplication,
	updateMcpServer,
	updateMcpOidcProvider,
	updateWorkspaceTag,
	updateWorkspaceTagState,
	updateWorkspaceTagValue,
	updateWorkspaceRule,
	updateWorkspaceSkill,
	updateWorkspaceScopes,
	updateWorkspacePromptTemplate,
	updateWorkspace
} from '../api';
import { compilePageSource } from '../utils/pageCompile';
import { useWorkspaceSocket } from '../state/workspaceSocket';
const route = useRoute()
const workspaceId = route.params.workspaceId
const router = useRouter()
const tenantId = ref(loadActiveTenant())
const session = loadSession()
const userId = session?.userId || ''
const workspace = ref(null)
const workspaceNameOpen = ref(false)
const workspaceNameDraft = ref('')
const workspaceNameSaving = ref(false)
const workspaceNameError = ref('')
const skillModal = ref(false)
const skillForm = ref({
	name: '',
	trigger: '',
	description: '',
	always_included: false,
	prompt_text: ''
})
const skillEditId = ref('')
const deleteSkillOpen = ref(false)
const skillToDelete = ref(null)
const assistants = ref([])
const personas = ref([])
const models = ref([])
const skills = ref([])
const workspaceSkills = ref([])
const tenantRules = ref([])
const workspaceRules = ref([])
const mcpServers = ref([])
const mcpServerSearchQuery = ref('')
const mcpServerPage = ref(1)
const mcpServerPageSize = 8
const serverTools = ref([])
const serverToolsLoading = ref(false)
const serverToolsError = ref('')
const serverToolsBaseline = ref({})
const toolSavingAll = ref(false)
const serverApplications = ref([])
const serverApplicationsLoading = ref(false)
const serverApplicationsError = ref('')
const serverApplicationsBaseline = ref({})
const applicationSavingAll = ref(false)
const tags = ref([])
const tagStates = ref([])
const policiesByServer = ref({})
const overridesByServer = ref({})
const workflows = ref([])
const workflowsError = ref('')
const workflowsLoading = ref(false)
const pages = ref([])
const pageNamespaceChildren = ref({ '': [] })
const loadedPageNamespaces = ref(new Set())
const installedPages = ref([])
const userExecutionMode = ref('released')
const pagesLoading = ref(false)
const pagesError = ref('')
const manageImportsOpen = ref(false)
const externalFrontendImportsError = ref('')
const externalFrontendImportsSaving = ref(false)
const externalFrontendImportsDraft = ref([])
const scripts = ref([])
const scriptNamespaceChildren = ref({ '': [] })
const loadedScriptNamespaces = ref(new Set())
const scriptsLoading = ref(false)
const scriptsError = ref('')
const visibleScripts = computed(() => scripts.value.filter((script) => (script?.type || 'STANDALONE') !== 'WORKFLOW'))
const scriptSearchQuery = ref('')
const scriptSearchActive = computed(() => scriptSearchQuery.value.trim().length > 0)
const scriptSearchValue = computed(() => scriptSearchQuery.value.trim().toLowerCase())
const openPageFolders = ref(new Set(['']))
const openScriptFolders = ref(new Set(['']))
const matchesScriptSearch = (script, query) => {
	if (!query) {
		return false
	}
	const haystack = [script?.name, script?.namespace, script?.slug, script?.description].filter(Boolean)
		.join(' ')
		.toLowerCase()
	return haystack.includes(query)
}
const splitNamespace = (namespace) => String(namespace || '').split('/').map((segment) => segment.trim()).filter(Boolean)
const isValidNamespaceSegment = (value) => /^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/.test(String(value || '').trim())
const normalizeNamespacePath = (value) => splitNamespace(value).join('/')
const buildNamespaceNameSlug = (namespace, name) => {
	const normalizePart = (value) => String(value || '')
		.trim()
		.toLowerCase()
		.replace(/[^a-z0-9]+/g, '_')
		.replace(/_+/g, '_')
		.replace(/^_+|_+$/g, '')
	const parts = [
		...splitNamespace(namespace).map(normalizePart).filter(Boolean),
		normalizePart(name),
	].filter(Boolean)
	return parts.join('-')
}
const buildNamespaceTree = (items, options = {}) => {
	const query = (options.query || '').trim().toLowerCase()
	const root = {
		name: 'root',
		path: '',
		children: new Map(),
		items: [],
		count: 0,
		matchCount: 0,
		hasMatch: false,
		selfMatch: false
	}
	const ensureFolderPath = (path) => {
		const segments = splitNamespace(path)
		let node = root
		segments.forEach((segment) => {
				if (!node.children.has(segment)) {
					node.children
						.set(
							segment,
							{
								name: segment,
								path: node.path ? `${node.path}/${segment}` : segment,
								children: new Map(),
								items: [],
								count: 0,
								matchCount: 0,
								hasMatch: false,
								selfMatch: false
							}
						)
				}
				node = node.children.get(segment)
			})
		return node
	}
	items.forEach((item) => {
			const namespace = (item?.namespace || '').trim()
			const node = ensureFolderPath(namespace)
			const searchMatch = matchesScriptSearch(item, query)
			node.items.push({ ...item, searchMatch })
		})
	const folderPaths = Array.isArray(options.folderPaths) ? options.folderPaths : []
	folderPaths.forEach((path) => {
			ensureFolderPath(path)
		})
	const applyCounts = (node) => {
		let total = node.items.length
		let matchTotal = node.items.filter((item) => item.searchMatch).length
		node.children.forEach((child) => {
				total += applyCounts(child)
				matchTotal += child.matchCount
			})
		node.count = total
		node.matchCount = matchTotal
		node.selfMatch = query ? node.name.toLowerCase().includes(query) : false
		node.hasMatch = !query || node.selfMatch || matchTotal > 0
		return total
	}
	applyCounts(root)
	return root
}
const compareByLabel = (left, right) => {
	const leftLabel = (left?.name || left?.slug || '').toLowerCase()
	const rightLabel = (right?.name || right?.slug || '').toLowerCase()
	return leftLabel.localeCompare(rightLabel)
}
const flattenNamespaceTree = (root, openSet, options = {}) => {
	const rows = []
	const walk = (node, depth) => {
		if (options.matchesOnly && !node.hasMatch) {
			return
		}
		const open = options.autoExpandMatches ? node.hasMatch : openSet.has(node.path)
		rows.push({
			kind: 'folder',
			key: `folder:${node.path || 'root'}`,
			depth,
			name: node.name || 'root',
			path: node.path,
			open,
			count: node.count || 0,
			matchCount: node.matchCount || 0,
			searchMatch: node.selfMatch,
			hasMatch: node.hasMatch
		})
		if (!open) {
			return
		}
		const sortedChildren = [...node.children.values()].sort(compareByLabel)
		sortedChildren.forEach((child) => walk(child, depth + 1))
		const sortedItems = [...node.items].sort(compareByLabel)
		sortedItems.forEach((item) => {
				if (options.matchesOnly && !item.searchMatch) {
					return
				}
				rows.push({
					kind: 'item',
					key: `item:${item.id}`,
					depth: depth + 1,
					item,
					searchMatch: item.searchMatch
				})
			})
	}
	walk(root, 0)
	return rows
}
const scriptFolderPaths = computed(() => {
	const paths = new Set(scriptFolders.value)
	Object.entries(scriptNamespaceChildren.value || {})
		.forEach(([parentPath, namespaces]) => {
			const normalizedParent = normalizeNamespacePath(parentPath);
			(Array.isArray(namespaces) ? namespaces : []).forEach((entry) => {
					const childPath = normalizeNamespacePath(entry?.path || '')
					if (childPath) {
						paths.add(childPath)
					}
					else if (normalizedParent) {
						paths.add(normalizedParent)
					}
				})
		})
	visibleScripts.value
		.forEach((script) => {
			const namespace = normalizeNamespacePath(script?.namespace || '')
			if (namespace) {
				paths.add(namespace)
			}
		})
	return Array.from(paths)
})
const pageFolderPaths = computed(() => {
	const paths = new Set()
	Object.entries(pageNamespaceChildren.value || {})
		.forEach(([, namespaces]) => {
			;
			(Array.isArray(namespaces) ? namespaces : []).forEach((entry) => {
					const childPath = normalizeNamespacePath(entry?.path || '')
					if (childPath) {
						paths.add(childPath)
					}
				})
		})
	pages.value
		.forEach((page) => {
			const namespace = normalizeNamespacePath(page?.namespace || '')
			if (namespace) {
				paths.add(namespace)
			}
		})
	return Array.from(paths)
})
const pageTree = computed(() => buildNamespaceTree(
	pages.value,
	{ folderPaths: pageFolderPaths.value }
))
const scriptTree = computed(() => buildNamespaceTree(
	visibleScripts.value,
	{ query: scriptSearchValue.value, folderPaths: scriptFolderPaths.value }
))
const filteredScripts = computed(() => visibleScripts.value.filter((script) => matchesScriptSearch(
	script,
	scriptSearchValue.value
)))
const pageTreeRows = computed(() => flattenNamespaceTree(pageTree.value, openPageFolders.value))
const scriptTreeRows = computed(() => flattenNamespaceTree(
	scriptTree.value,
	openScriptFolders.value,
	{ matchesOnly: scriptSearchActive.value, autoExpandMatches: scriptSearchActive.value }
))
const toggleFolder = (target, path) => {
	const next = new Set(target.value)
	if (next.has(path)) {
		next.delete(path)
	}
	else {
		next.add(path)
	}
	target.value = next
}
const toggleScriptFolder = (path) => {
	toggleFolder(openScriptFolders, path)
}
const scriptModalOpen = ref(false)
const scriptEditId = ref('')
const selectedScriptFolderPath = ref('')
const scriptFolderModalOpen = ref(false)
const scriptFolderError = ref('')
const scriptFolderName = ref('')
const scriptFolders = ref(new Set())
const scriptForm = ref({
	name: '',
	description: '',
	namespace: '',
	type: 'STANDALONE',
	scheduled: false,
	schedule_rrule: '',
	schedule_timezone: 'UTC',
	schedule_start_at: '',
	schedule_end_at: '',
	tool_hook_enabled: false,
	tool_hook_phase: 'BEFORE',
	tool_hook_tool_names: []
})
const scriptSaving = ref(false)
const scriptError = ref('')
const scriptToolNamesInput = ref('')
const deleteScriptOpen = ref(false)
const scriptToDelete = ref(null)
const selectScriptFolder = (path) => {
	selectedScriptFolderPath.value = path || ''
}
const ensureScriptFolderOpen = (path) => {
	const next = new Set(openScriptFolders.value)
	let current = path || ''
	while (current) {
		next.add(current)
		const slash = current.lastIndexOf('/')
		current = slash >= 0 ? current.slice(0, slash) : ''
	}
	next.add('')
	openScriptFolders.value = next
}
const loadScriptNamespace = async(namespace = '') => {
	const normalizedNamespace = normalizeNamespacePath(namespace)
	if (!tenantId.value || loadedScriptNamespaces.value.has(normalizedNamespace)) {
		return
	}
	const response = await getScripts(tenantId.value, workspaceId, { namespace: normalizedNamespace || undefined, non_recursive: true })
	const namespaceEntries = Array.isArray(response?.namespaces) ? response.namespaces : []
	const namespaceScripts = Array.isArray(response?.scripts) ? response.scripts : []
	scriptNamespaceChildren.value = { ...scriptNamespaceChildren.value, [normalizedNamespace]: namespaceEntries }
	const nextScripts = scripts.value.filter((script) => normalizeNamespacePath(script?.namespace || '') !== normalizedNamespace)
	scripts.value = [...nextScripts, ...namespaceScripts]
	loadedScriptNamespaces.value = new Set([...loadedScriptNamespaces.value, normalizedNamespace])
}
const loadPageNamespace = async(namespace = '') => {
	const normalizedNamespace = normalizeNamespacePath(namespace)
	if (!tenantId.value || loadedPageNamespaces.value.has(normalizedNamespace)) {
		return
	}
	const response = await getPages(tenantId.value, workspaceId, { namespace: normalizedNamespace || undefined, non_recursive: true })
	const namespaceEntries = Array.isArray(response?.namespaces) ? response.namespaces : []
	const namespacePages = Array.isArray(response?.pages) ? response.pages : []
	pageNamespaceChildren.value = { ...pageNamespaceChildren.value, [normalizedNamespace]: namespaceEntries }
	const nextPages = pages.value.filter((page) => normalizeNamespacePath(page?.namespace || '') !== normalizedNamespace)
	pages.value = [...nextPages, ...namespacePages]
	loadedPageNamespaces.value = new Set([...loadedPageNamespaces.value, normalizedNamespace])
}
const togglePageFolder = (path) => {
	toggleFolder(openPageFolders, path)
}
watch(
	openPageFolders,
	(nextOpenFolders) => {
		nextOpenFolders.forEach((path) => {
				if (path) {
					loadPageNamespace(path).catch((error) => {
							pagesError.value = error?.message || 'Unable to load pages.'
						})
				}
			})
	}
)
const openScriptCreate = (namespace = selectedScriptFolderPath.value) => {
	scriptEditId.value = ''
	scriptForm.value = {
		name: '',
		description: '',
		namespace: normalizeNamespacePath(namespace),
		type: 'STANDALONE',
		scheduled: false,
		schedule_rrule: '',
		schedule_timezone: 'UTC',
		schedule_start_at: '',
		schedule_end_at: '',
		tool_hook_enabled: false,
		tool_hook_phase: 'BEFORE',
		tool_hook_tool_names: []
	}
	scriptError.value = ''
	scriptToolNamesInput.value = ''
	scriptModalOpen.value = true
}
const openScriptFolderCreate = (parentPath = selectedScriptFolderPath.value) => {
	selectedScriptFolderPath.value = normalizeNamespacePath(parentPath)
	scriptFolderName.value = ''
	scriptFolderError.value = ''
	scriptFolderModalOpen.value = true
}
const createScriptFolder = () => {
	const name = String(scriptFolderName.value || '').trim()
	if (!name) {
		scriptFolderError.value = 'Folder name is required.'
		return
	}
	if (!isValidNamespaceSegment(name)) {
		scriptFolderError.value = 'Folder names may only contain lowercase letters, numbers, and dashes.'
		return
	}
	const nextPath = normalizeNamespacePath(
		selectedScriptFolderPath.value ? `${selectedScriptFolderPath.value}/${name}` : name
	)
	scriptFolders.value = new Set([...scriptFolders.value, nextPath])
	ensureScriptFolderOpen(nextPath)
	selectedScriptFolderPath.value = nextPath
	scriptFolderModalOpen.value = false
}
watch(
	openScriptFolders,
	(nextOpenFolders) => {
		nextOpenFolders.forEach((path) => {
				if (path) {
					loadScriptNamespace(path)
						.catch((error) => {
							scriptsError.value = error?.message || 'Unable to load scripts.'
						})
				}
			})
	}
)
const pageModalOpen = ref(false)
const pageImportOpen = ref(false)
const pageForm = ref({
	name: '',
	description: '',
	namespace: '',
	slug: '',
	type: 'PAGE',
	menu_visible: true,
	route_suffix: ''
})
const pageSaving = ref(false)
const pageError = ref('')
const pageImportPayload = ref('')
const pageImportError = ref('')
const pageImportUpdateSlugs = ref(true)
const pageCopyOpen = ref(false)
const pageCopyIncludeDependencies = ref(true)
const pageToCopy = ref(null)
const deleteWorkflowOpen = ref(false)
const workflowToDelete = ref(null)
const deletePageOpen = ref(false)
const pageToDelete = ref(null)
const oidcProviders = ref([])
const providerForm = ref({
	issuer: '',
	resource_metadata_url: '',
	well_known_url: '',
	authorization_endpoint: '',
	token_endpoint: '',
	registration_endpoint: '',
	scope_category_ids: []
})
const providerEditId = ref('')
const providerModalOpen = ref(false)
const providerSaving = ref(false)
const providerError = ref('')
const providerToDelete = ref(null)
const deleteProviderOpen = ref(false)
const workspaceToolScopes = ref([])
const channels = ref([])
const channelModalOpen = ref(false)
const channelSaving = ref(false)
const channelError = ref('')
const channelEditId = ref('')
const deleteChannelOpen = ref(false)
const channelToDelete = ref(null)
const channelForm = ref({
	name: '',
	description: '',
	assistant_id: '',
	prompt: '',
	allow_scopes: [],
	deny_scopes: [],
	tag_ids: [],
	mcp_servers: []
})
const workspaceScopeAllow = ref([])
const workspaceScopeDeny = ref([])
const workspaceScopeLoading = ref(false)
const workspaceScopeSaving = ref(false)
const workspaceScopeError = ref('')
let workspaceScopeSaveTimer = null
const promptTemplates = ref([])
const promptTemplateOpen = ref(false)
const promptTemplateSaving = ref(false)
const promptTemplateForm = ref({ section: '', content: '', enabled: true })
const promptTemplateFallback = ref('')
const status = ref('')
let statusTimer = null
const ruleModal = ref(false)
const ruleEditId = ref('')
const ruleForm = ref({
	name: '',
	content: '',
	always_included: false,
	enabled: true,
	order: null
})
const serverModal = ref(false)
const serverEditId = ref('')
const serverImportOpen = ref(false)
const serverImportPayload = ref('')
const serverImportError = ref('')
const pendingImportTools = ref(null)
const pendingImportConfig = ref(null)
const providerImportOpen = ref(false)
const providerImportPayload = ref('')
const providerImportError = ref('')
const serverProbeState = ref({ status: 'idle', message: '' })
const largeEditorOpen = ref(false)
const largeEditorMode = ref('')
const definitionOpen = ref(false)
const definitionLoading = ref(false)
const definitionError = ref('')
const definitionPayload = ref('')
const toolConfigOpen = ref(false)
const applicationConfigOpen = ref(false)
const serverForm = ref({
	name: '',
	description: '',
	protocol: 'STDIO',
	framing: 'ndjson',
	command: '',
	cwd: '',
	http_url: '',
	virtual_type: 'polymr',
	headers: '',
	environment: '',
	ssh_enabled: false,
	ssh_tunnel: {
		server: '',
		port: 22,
		username: '',
		password: '',
		key_path: '',
		key: ''
	},
	allow_policy: false,
	visibility: 'VISIBLE',
	tool_name_prefix: '',
	instructions: '',
	custom_instructions: false,
	config_schema_json: '',
	configuration_value: {},
	oauth_enabled: false,
	auth: {
		global: true,
		issuer: '',
		well_known_url: '',
		resource_metadata_url: '',
		authorization_endpoint: '',
		token_endpoint: '',
		registration_endpoint: '',
		scopes: '',
		supported_scopes: [],
		selected_supported_scopes: [],
		custom_scopes: '',
		client_id: '',
		client_secret: '',
		client_secret_hint: '',
		status: '',
		refreshable: false,
		last_auth_at: ''
	}
})
const scriptPickerOpen = ref(false)
const scriptPickerQuery = ref('')
const scriptPickerResults = ref([])
const scriptPickerLoading = ref(false)
const scriptPickerError = ref('')
const assistantModalOpen = ref(false)
const assistantEditId = ref('')
const assistantForm = ref({
	name: '',
	description: '',
	prompt_text: '',
	persona_id: '',
	model_id: '',
	max_output_tokens: '',
	skill_ids: [],
	rule_ids: [],
	worker_enabled: false,
	worker_trigger: '',
	worker_allow_scopes: [],
	worker_deny_scopes: []
})
const assistantSaving = ref(false)
const assistantError = ref('')
const deleteAssistantOpen = ref(false)
const assistantToDelete = ref(null)
const workerScopeOptions = ref([])
const workerScopeLoading = ref(false)
const workerScopeError = ref('')
const tagModal = ref(false)
const tagForm = ref({ name: '', slug: '', priority: 0 })
const tagValueForm = ref({
	category_id: '',
	name: '',
	slug: '',
	priority: 0
})
const toSlug = (value) => {
	if (!value) {
		return ''
	}
	return value.trim().toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '')
}
const tagCategorySlugPlaceholder = computed(() => toSlug(tagForm.value.name))
const tagValueSlugPlaceholder = computed(() => toSlug(tagValueForm.value.name))
const deleteTagOpen = ref(false)
const tagToDelete = ref(null)
const tagEditOpen = ref(false)
const tagEditForm = ref({
	id: '',
	name: '',
	slug: '',
	priority: 0
})
const tagValueFormOpen = ref(false)
const tagValueFormMode = ref('create')
const tagValueEditForm = ref({
	category_id: '',
	id: '',
	name: '',
	slug: '',
	priority: 0
})
const policyEditId = ref('')
const policyServer = ref(null)
const policyForm = ref({ tag_category_id: '', tag_id: '', policy_value: {} })
const deletePolicyOpen = ref(false)
const policyToDelete = ref(null)
const deleteOverrideOpen = ref(false)
const overrideEditId = ref('')
const overrideServer = ref(null)
const overrideToDelete = ref(null)
const overrideForm = ref({
	tag_category_id: '',
	tag_id: '',
	logical_name: '',
	http_url: '',
	headers: '',
	ssh_enabled: '',
	ssh_tunnel: {
		server: '',
		port: 22,
		username: '',
		password: '',
		key_path: '',
		key: ''
	},
	auth: {
		global: true,
		issuer: '',
		well_known_url: '',
		resource_metadata_url: '',
		authorization_endpoint: '',
		token_endpoint: '',
		registration_endpoint: '',
		scopes: '',
		supported_scopes: [],
		selected_supported_scopes: [],
		custom_scopes: '',
		client_id: '',
		client_secret: ''
	}
})
const workspaceSocketState = useWorkspaceSocket(tenantId.value, workspaceId)
let unregisterSocketHandler = null
const deleteServerOpen = ref(false)
const serverToDelete = ref(null)
const deleteRuleOpen = ref(false)
const ruleToDelete = ref(null)
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
const loadWorkerScopes = async() => {
	if (!tenantId.value || !workspaceId) {
		workerScopeOptions.value = []
		workerScopeError.value = ''
		return
	}
	workerScopeLoading.value = true
	workerScopeError.value = ''
	try {
		const servers = await getMcpServers(tenantId.value, workspaceId)
		if (!Array.isArray(servers) || servers.length === 0) {
			workerScopeOptions.value = []
			return
		}
		const results = await Promise.allSettled(servers.map((server) => getMcpServerTools(tenantId.value, workspaceId, server.id)))
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
		workerScopeOptions.value = Array.from(scopes).sort()
	}
	catch (error) {
		workerScopeError.value = error?.message || 'Unable to load worker scopes.'
		workerScopeOptions.value = []
	}
	finally {
		workerScopeLoading.value = false
	}
}
const openWorkspaceNameEditor = () => {
	workspaceNameDraft.value = workspace.value?.name || ''
	workspaceNameError.value = ''
	workspaceNameOpen.value = true
}
const closeWorkspaceNameEditor = () => {
	workspaceNameOpen.value = false
	workspaceNameError.value = ''
}
const saveWorkspaceName = async() => {
	if (!workspaceNameDraft.value.trim()) {
		workspaceNameError.value = 'Workspace name is required.'
		return
	}
	workspaceNameSaving.value = true
	workspaceNameError.value = ''
	try {
		const updated = await updateWorkspace(
			tenantId.value,
			workspaceId,
			{
				name: workspaceNameDraft.value.trim(),
				description: workspace.value?.description || '',
				external_frontend_imports: workspace.value?.external_frontend_imports || []
			}
		)
		workspace.value = updated
		workspaceNameOpen.value = false
		notify('Workspace name updated.')
	}
	catch (error) {
		workspaceNameError.value = error?.message || 'Unable to update workspace name.'
	}
	finally {
		workspaceNameSaving.value = false
	}
}
const defaultExternalFrontendImport = () => ({
	specifier: '',
	version: '',
	global_name: '',
	source_url: '',
	css_urls_text: ''
})
const openManageImports = () => {
	externalFrontendImportsError.value = ''
	const entries = Array.isArray(workspace.value?.external_frontend_imports)
		? workspace.value.external_frontend_imports
		: []
	externalFrontendImportsDraft.value = entries.map((entry) => ({
		specifier: entry?.specifier || '',
		version: entry?.version || '',
		global_name: entry?.global_name || '',
		source_url: entry?.source_url || '',
		css_urls_text: Array.isArray(entry?.css_urls) ? entry.css_urls.join('\n') : ''
	}))
	manageImportsOpen.value = true
}
const closeManageImports = () => {
	manageImportsOpen.value = false
	externalFrontendImportsError.value = ''
	externalFrontendImportsSaving.value = false
}
const addExternalFrontendImport = () => {
	externalFrontendImportsDraft.value = [...externalFrontendImportsDraft.value, defaultExternalFrontendImport()]
}
const removeExternalFrontendImport = (index) => {
	externalFrontendImportsDraft.value = externalFrontendImportsDraft.value.filter((_, entryIndex) => entryIndex !== index)
}
const saveExternalFrontendImports = async() => {
	externalFrontendImportsError.value = ''
	const cleaned = externalFrontendImportsDraft.value
		.map((entry) => {
			const cssUrls = String(entry?.css_urls_text || '')
				.split('\n')
				.map((value) => value.trim())
				.filter((value) => value.length > 0)
			return {
				specifier: String(entry?.specifier || '').trim(),
				version: String(entry?.version || '').trim(),
				global_name: String(entry?.global_name || '').trim(),
				source_url: String(entry?.source_url || '').trim(),
				css_urls: cssUrls
			}
		})
		.filter((entry) => entry.specifier
		|| entry.version
		|| entry.global_name
		|| entry.source_url
		|| entry.css_urls.length > 0)
	for (const entry of cleaned) {
		if (!entry.specifier || !entry.global_name || !entry.source_url) {
			externalFrontendImportsError.value = 'Each import needs a specifier, global name, and source URL.'
			return
		}
	}
	externalFrontendImportsSaving.value = true
	try {
		const updated = await updateWorkspace(
			tenantId.value,
			workspaceId,
			{
				name: workspace.value?.name || '',
				description: workspace.value?.description || '',
				external_frontend_imports: cleaned
			}
		)
		workspace.value = updated
		pages.value = pages.value
			.map((page) => ({ ...page, workspace_external_frontend_imports: updated.external_frontend_imports || [] }))
		manageImportsOpen.value = false
		notify('External frontend imports updated.')
	}
	catch (error) {
		externalFrontendImportsError.value = error?.message || 'Unable to update external frontend imports.'
	}
	finally {
		externalFrontendImportsSaving.value = false
	}
}
const emitPagesUpdated = () => {
	if (typeof window !== 'undefined') {
		window.dispatchEvent(new CustomEvent('polymr.pages.updated'))
	}
}
const promptSections = [
	{ key: 'PERSONALITY', label: 'Personality' },
	{ key: 'CORE_RULES', label: 'Core rules' },
	{ key: 'SKILLS', label: 'Skills' },
	{ key: 'FORMATTING', label: 'Formatting' },
	{ key: 'WORKER_AUTONOMY', label: 'Worker autonomy' },
]
const workspaceAssistants = computed(() => assistants.value.filter((assistant) => assistant.workspace_id === workspaceId))
const serverPolicies = (serverId) => policiesByServer.value[serverId] || []
const parseServerAuth = (auth) => {
	const parseSelectedScopes = (value) => {
		if (!value || typeof value !== 'string') {
			return []
		}
		return value.split(/\s+/).map((entry) => entry.trim()).filter(Boolean)
	}
	if (!auth) {
		return {
			global: true,
			scope_category_ids: [],
			issuer: '',
			well_known_url: '',
			resource_metadata_url: '',
			authorization_endpoint: '',
			token_endpoint: '',
			registration_endpoint: '',
			scopes: '',
			supported_scopes: [],
			selected_supported_scopes: [],
			custom_scopes: '',
			client_id: '',
			client_secret: '',
			client_secret_hint: '',
			status: '',
			refreshable: false,
			last_auth_at: '',
			access_expires_at: '',
			refresh_failed_at: '',
			error_message: ''
		}
	}
	const secretHint = auth?.client_secret?.hint || (auth?.client_secret?.present ? 'Stored' : '')
	const supportedScopes = Array.isArray(auth?.supported_scopes) ? auth.supported_scopes.filter(Boolean) : []
	const selectedScopes = parseSelectedScopes(auth?.scopes || '')
	const selectedSupportedScopes = selectedScopes.filter((scope) => supportedScopes.includes(scope))
	const customScopes = selectedScopes.filter((scope) => !supportedScopes.includes(scope)).join(' ')
	return {
		global: auth?.global !== false,
		scope_category_ids: Array.isArray(auth?.scope_category_ids) ? auth.scope_category_ids : [],
		issuer: auth?.issuer || '',
		well_known_url: auth?.well_known_url || '',
		resource_metadata_url: auth?.resource_metadata_url || '',
		authorization_endpoint: auth?.authorization_endpoint || '',
		token_endpoint: auth?.token_endpoint || '',
		registration_endpoint: auth?.registration_endpoint || '',
		scopes: auth?.scopes || '',
		supported_scopes: supportedScopes,
		selected_supported_scopes: selectedSupportedScopes,
		custom_scopes: customScopes,
		client_id: auth?.client_id || '',
		client_secret: '',
		client_secret_hint: secretHint,
		status: auth?.status || '',
		refreshable: !!auth?.refreshable,
		last_auth_at: auth?.last_auth_at || '',
		access_expires_at: auth?.access_expires_at || '',
		refresh_failed_at: auth?.refresh_failed_at || '',
		error_message: auth?.error_message || ''
	}
}
const buildAuthPayload = (auth) => {
	if (!auth) {
		return null
	}
	const selectedSupportedScopes = Array.isArray(auth.selected_supported_scopes)
		? auth.selected_supported_scopes.map((entry) => `${entry || ''}`.trim()).filter(Boolean)
		: []
	const customScopes = `${auth.custom_scopes || ''}`.split(/\s+/).map((entry) => entry.trim()).filter(Boolean)
	const scopes = [...new Set([...selectedSupportedScopes, ...customScopes])].join(' ')
	const payload = {
		global: auth.global !== false,
		scope_category_ids: Array.isArray(auth.scope_category_ids) ? auth.scope_category_ids : [],
		issuer: auth.issuer?.trim() || '',
		well_known_url: auth.well_known_url?.trim() || '',
		resource_metadata_url: auth.resource_metadata_url?.trim() || '',
		authorization_endpoint: auth.authorization_endpoint?.trim() || '',
		token_endpoint: auth.token_endpoint?.trim() || '',
		registration_endpoint: auth.registration_endpoint?.trim() || '',
		scopes,
		client_id: auth.client_id?.trim() || ''
	}
	if (auth.client_secret && auth.client_secret.trim()) {
		payload.client_secret = auth.client_secret.trim()
	}
	const hasAny = Object.values(payload).some((value) => value && `${value}`.trim() !== '')
	return hasAny || payload.client_secret ? payload : null
}
const buildSshTunnelPayload = () => {
	const ssh = serverForm.value.ssh_tunnel || {}
	const hasAnyString = [ssh.server, ssh.username, ssh.password, ssh.key_path, ssh.key].some((value) => typeof value === 'string' && value.trim())
	const port = Number(ssh.port)
	if (!hasAnyString && !Number.isFinite(port)) {
		return null
	}
	return {
		server: ssh.server?.trim() || '',
		port: Number.isFinite(port) && port > 0 ? port : 22,
		username: ssh.username?.trim() || '',
		password: ssh.password || '',
		key_path: ssh.key_path?.trim() || '',
		key: ssh.key || ''
	}
}
const buildServerPayload = (authOverride) => ({
	name: serverForm.value.name,
	description: serverForm.value.description,
	protocol: serverForm.value.protocol,
	framing: serverForm.value.framing,
	command: serverForm.value.command,
	cwd: serverForm.value.cwd,
	http_url: serverForm.value.http_url,
	virtual_type: serverForm.value.virtual_type,
	headers: serverForm.value.headers,
	environment: serverForm.value.environment,
	ssh_enabled: !!serverForm.value.ssh_enabled,
	ssh_tunnel: buildSshTunnelPayload(),
	oauth_enabled: !!serverForm.value.oauth_enabled,
	visibility: serverForm.value.visibility || 'VISIBLE',
	tool_name_prefix: serverForm.value.tool_name_prefix,
	instructions: serverForm.value.instructions,
	custom_instructions: serverForm.value.custom_instructions,
	configuration_json: configurationSchema.value
			|| pendingImportConfig.value != null
			|| serverForm.value.virtual_type === 'script'
		? serverForm.value.configuration_value
		: null,
	auth: authOverride ?? buildAuthPayload(serverForm.value.auth),
	tools: Array.isArray(pendingImportTools.value) ? pendingImportTools.value : null
})
const requestAuthRefresh = async() => {
	if (!serverEditId.value) {
		notify('Save the server before refreshing auth.')
		return
	}
	const authPayload = buildAuthPayload(serverForm.value.auth) || {}
	authPayload.refresh_requested = true
	const payload = buildServerPayload(authPayload)
	const updated = await updateMcpServer(tenantId.value, workspaceId, serverEditId.value, payload)
	mcpServers.value = mcpServers.value.map((item) => (item.id === updated.id ? updated : item))
	serverForm.value.auth = parseServerAuth(updated.auth)
	notify('Auth refresh requested.')
}
const redirectUri = computed(() => {
	if (!serverEditId.value) {
		return ''
	}
	return `${window.location.origin}/api/tenants/${tenantId.value}/workspaces/${workspaceId}/mcp-servers/${serverEditId.value}/oauth/callback`
})
const copyRedirectUri = async() => {
	if (!redirectUri.value) {
		return
	}
	try {
		await navigator.clipboard.writeText(redirectUri.value)
		notify('Redirect URI copied.')
	}
	catch {
		notify('Unable to copy redirect URI.')
	}
}
const applyDiscoveredAuthDetails = (auth, response) => {
	if (!auth || !response) {
		return
	}
	auth.issuer = response.issuer || auth.issuer || ''
	auth.resource_metadata_url = response.resource_metadata_url || auth.resource_metadata_url || ''
	auth.well_known_url = response.well_known_url || auth.well_known_url || ''
	auth.authorization_endpoint = response.authorization_endpoint || auth.authorization_endpoint || ''
	auth.token_endpoint = response.token_endpoint || auth.token_endpoint || ''
	auth.registration_endpoint = response.registration_endpoint || auth.registration_endpoint || ''
	const supportedScopes = Array.isArray(response.supported_scopes) ? response.supported_scopes.filter(Boolean) : []
	const currentlySelected = Array.isArray(auth.selected_supported_scopes) ? auth.selected_supported_scopes : []
	auth.supported_scopes = supportedScopes
	auth.selected_supported_scopes = currentlySelected.filter((scope) => supportedScopes.includes(scope))
	if (!auth.scopes) {
		auth.custom_scopes = auth.custom_scopes || ''
	}
}
const fetchAuthDetails = async() => {
	const auth = serverForm.value.auth
	try {
		const payload = {
			resource_metadata_url: auth.resource_metadata_url?.trim() || undefined,
			well_known_url: auth.well_known_url?.trim() || undefined,
			issuer: auth.issuer?.trim() || undefined
		}
		const response = await discoverMcpServerOAuth(tenantId.value, workspaceId, serverEditId.value, payload)
		applyDiscoveredAuthDetails(auth, response)
		notify('OAuth details fetched.')
	}
	catch (error) {
		notify(error?.message || 'Unable to fetch OAuth details.')
	}
}
const discoverServerOAuth = async() => {
	if (!serverEditId.value) {
		notify('Save the server before discovering OAuth settings.')
		return
	}
	try {
		const response = await discoverMcpServerOAuth(tenantId.value, workspaceId, serverEditId.value, {})
		applyDiscoveredAuthDetails(serverForm.value.auth, response)
		notify('OAuth discovery completed.')
	}
	catch (error) {
		notify(error?.message || 'Unable to discover OAuth settings.')
	}
}
const serverNeedsAuthentication = (server) => {
	if (server?.oauth_enabled !== true) {
		return false
	}
	const status = server?.auth?.status
	return !status || status === 'auth_required' || status === 'refresh_failed'
}
const authStatusLabel = (server) => {
	const enabled = server?.oauth_enabled === true
	const status = server?.auth?.status
	if (!enabled) {
		return ''
	}
	if (status === 'refresh_failed') {
		return 'Authentication failed'
	}
	if (serverNeedsAuthentication(server)) {
		return 'Unauthenticated'
	}
	if (status === 'fallback_available') {
		return 'Auth: fallback'
	}
	if (status === 'manual_client_required') {
		return 'Auth: manual setup'
	}
	if (status === 'connected') {
		return 'Authenticated'
	}
	return `Auth: ${status.replace(/_/g, ' ')}`
}
const authStatusClass = (server) => {
	const enabled = server?.oauth_enabled === true
	const status = server?.auth?.status
	if (!enabled) {
		return ''
	}
	if (serverNeedsAuthentication(server)) {
		return 'warning'
	}
	if (status === 'refresh_failed') {
		return 'error'
	}
	if (status === 'connected') {
		return 'success'
	}
	if (status === 'fallback_available') {
		return 'warning'
	}
	if (status === 'manual_client_required') {
		return 'error'
	}
	return 'warning'
}
const workspaceScopes = computed(() => {
	const set = new Set()
	workspaceToolScopes.value.forEach((scope) => set.add(scope))
	workspaceScopeAllow.value.forEach((scope) => set.add(scope))
	workspaceScopeDeny.value.forEach((scope) => set.add(scope))
	channels.value
		.forEach((channel) => {
			;
			(channel?.allow_scopes || []).forEach((scope) => set.add(scope));
			(channel?.deny_scopes || []).forEach((scope) => set.add(scope))
		})
	return Array.from(set).sort()
})
const tenantAssistants = computed(() => assistants.value.filter((assistant) => !assistant.workspace_id))
const channelTagNames = (channel) => {
	if (!channel || !Array.isArray(channel.tag_ids)) {
		return []
	}
	const names = []
	channel.tag_ids
		.forEach((tagId) => {
			tags.value
				.forEach((category) => {
					;
					(category.values || []).forEach((value) => {
							if (value.id === tagId) {
								names.push(`${category.name}: ${value.name}`)
							}
						})
				})
		})
	return names
}
const channelServerSummary = (channel) => {
	if (!channel || !Array.isArray(channel.mcp_servers)) {
		return ''
	}
	const names = channel.mcp_servers
		.map((item) => {
			const server = mcpServers.value.find((entry) => entry.id === item.mcp_server_id)
			if (!server) {
				return null
			}
			if (item.visibility === 'VISIBLE') {
				return `${server.name} (default)`
			}
			if (item.visibility === 'AVAILABLE') {
				return `${server.name} (on request)`
			}
			return `${server.name} (hidden)`
		})
		.filter(Boolean)
	return names.join(', ')
}
const mcpServerSearchValue = computed(() => mcpServerSearchQuery.value.trim().toLowerCase())
const filteredMcpServers = computed(() => {
	const query = mcpServerSearchValue.value
	const servers = [...mcpServers.value].sort((a, b) => String(a?.name || '').localeCompare(String(b?.name || '')))
	if (!query) {
		return servers
	}
	return servers.filter((server) => {
			const haystack = [server?.name, server?.description, server?.protocol, server?.command, server?.http_url].filter(Boolean)
				.join(' ')
				.toLowerCase()
			return haystack.includes(query)
		})
})
const mcpServerPageCount = computed(() => Math.max(1, Math.ceil(filteredMcpServers.value.length / mcpServerPageSize)))
const pagedMcpServers = computed(() => {
	const page = Math.min(mcpServerPage.value, mcpServerPageCount.value)
	const start = (page - 1) * mcpServerPageSize
	return filteredMcpServers.value.slice(start, start + mcpServerPageSize)
})
const mcpServerRangeLabel = computed(() => {
	if (!filteredMcpServers.value.length) {
		return '0 of 0'
	}
	const page = Math.min(mcpServerPage.value, mcpServerPageCount.value)
	const start = (page - 1) * mcpServerPageSize + 1
	const end = Math.min(start + mcpServerPageSize - 1, filteredMcpServers.value.length)
	return `${start}-${end} of ${filteredMcpServers.value.length}`
})
const setMcpServerPage = (page) => {
	mcpServerPage.value = Math.min(Math.max(page, 1), mcpServerPageCount.value)
}
watch(
	mcpServerSearchQuery,
	() => {
		mcpServerPage.value = 1
	}
)
watch(
	filteredMcpServers,
	(servers) => {
		if (!servers.length) {
			mcpServerPage.value = 1
			return
		}
		if (mcpServerPage.value > mcpServerPageCount.value) {
			mcpServerPage.value = mcpServerPageCount.value
		}
	}
)
const isInternalWorkflow = (workflow) => {
	const name = workflow?.name || ''
	return String(name).toLowerCase() === 'conversation'
}
const userWorkflows = computed(() => workflows.value.filter((workflow) => !isInternalWorkflow(workflow)))
const openWorkflow = (workflowId) => {
	if (!workflowId) {
		return
	}
	router.push({ name: 'workspace-workflow', params: { workspaceId, workflowId } })
}
const createNewWorkflow = () => {
	router.push({ name: 'workspace-workflow', params: { workspaceId, workflowId: 'new' } })
}
const sortedTagValues = (values) => {
	return [...(values || [])].sort((a, b) => {
			const priorityDelta = Number(b?.priority || 0) - Number(a?.priority || 0)
			if (priorityDelta !== 0) {
				return priorityDelta
			}
			return String(a?.name || '').localeCompare(String(b?.name || ''))
		})
}
const sortedTagCategories = computed(() => {
	return [...(tags.value || [])].map((category) => ({ ...category, values: sortedTagValues(category?.values || []) }))
		.sort((a, b) => {
			const priorityDelta = Number(b?.priority || 0) - Number(a?.priority || 0)
			if (priorityDelta !== 0) {
				return priorityDelta
			}
			return String(a?.name || '').localeCompare(String(b?.name || ''))
		})
})
const activeServerForOverrides = computed(() => {
	if (!serverEditId.value) {
		return null
	}
	return mcpServers.value.find((item) => item.id === serverEditId.value)
		|| { id: serverEditId.value, oauth_enabled: !!serverForm.value.oauth_enabled }
})
const activeTagByCategory = computed(() => {
	return (tagStates.value || []).reduce(
		(acc, state) => {
			if (state?.category_id) {
				acc[state.category_id] = state.value_id || null
			}
			return acc
		},
		{}
	)
})
const resolveAuthScopeValueIds = (categoryIds) => {
	return (categoryIds || []).map((categoryId) => activeTagByCategory.value[categoryId] || null).filter(Boolean)
}
const serverDetail = (server) => {
	if (!server) {
		return ''
	}
	if (server.protocol === 'STDIO') {
		return server.command || 'Command required'
	}
	return server.http_url || 'HTTP URL required'
}
const workflowTriggerLabel = (trigger) => {
	if (!trigger) {
		return ''
	}
	if (trigger === 'USER_PROMPT') {
		return 'Trigger: manual'
	}
	return `Trigger: ${String(trigger).toLowerCase().replace(/_/g, ' ')}`
}
const parseSchemaJson = (text) => {
	if (!text || !text.trim()) {
		return { value: null, error: '' }
	}
	try {
		return { value: JSON.parse(text), error: '' }
	}
	catch (error) {
		return { value: null, error: `Config schema must be valid JSON: ${error?.message || 'Invalid JSON'}` }
	}
}
const configSchemaResult = computed(() => parseSchemaJson(serverForm.value.config_schema_json))
const configSchemaValue = computed(() => configSchemaResult.value.value)
const configSchemaError = computed(() => configSchemaResult.value.error)
const configurationSchema = computed(() => {
	if (!configSchemaValue.value) {
		return null
	}
	return filterSchemaForScope(configSchemaValue.value, 'configuration')
})
const serverUsesManagedInstance = ref(false)
const scriptIdsForServer = computed(() => {
	const config = serverForm.value.configuration_value || {}
	return Array.isArray(config.scripts) ? config.scripts : []
})
const selectedServerScripts = computed(() => {
	const ids = new Set(scriptIdsForServer.value)
	return visibleScripts.value.filter((script) => ids.has(script.id))
})
const availableServerScripts = computed(() => {
	const ids = new Set(scriptIdsForServer.value)
	return scriptPickerResults.value.filter((script) => !ids.has(script.id))
})
const serverProtocolLabel = (protocol) => {
	if (protocol === 'STDIO') {
		return 'Stdio'
	}
	if (protocol === 'SSE') {
		return 'SSE'
	}
	if (protocol === 'VIRTUAL') {
		return 'Virtual'
	}
	return 'Streamable HTTP'
}
const canInstallPage = (page) => {
	if (!page) {
		return false
	}
	if (page.released) {
		return true
	}
	return userExecutionMode.value === 'latest' && page.draft_present
}
const pageActionTooltip = (page) => {
	if (!canInstallPage(page)) {
		return 'Not available in released mode'
	}
	return isPageInstalled(page.id) ? 'Uninstall page' : 'Install page'
}
const skillListExpanded = ref(false)
const skillListLimit = 3
const visibleWorkspaceSkills = computed(() => skillListExpanded.value ? workspaceSkills.value : workspaceSkills.value.slice(0, skillListLimit))
const hiddenWorkspaceSkillCount = computed(() => Math.max(0, workspaceSkills.value.length - skillListLimit))
const toggleSkillList = () => {
	skillListExpanded.value = !skillListExpanded.value
}
const openSkillCreate = () => {
	skillEditId.value = ''
	skillForm.value = {
		name: '',
		trigger: '',
		description: '',
		always_included: false,
		prompt_text: ''
	}
	skillModal.value = true
}
const openSkillEdit = (skill) => {
	skillEditId.value = skill.id
	skillForm.value = {
		name: skill.name,
		trigger: skill.trigger,
		description: skill.description,
		always_included: !!skill.always_included,
		prompt_text: skill.prompt_text || ''
	}
	skillModal.value = true
}
const saveSkill = async() => {
	if (!skillForm.value.name.trim()
			|| !skillForm.value.trigger.trim()
			|| !skillForm.value.description.trim()
			|| !skillForm.value.prompt_text.trim()) {
		notify('Skill name, trigger, description, and prompt are required.')
		return
	}
	const payload = { ...skillForm.value, workspace_id: workspaceId }
	if (skillEditId.value) {
		const updated = await updateWorkspaceSkill(tenantId.value, workspaceId, skillEditId.value, payload)
		workspaceSkills.value = workspaceSkills.value.map((item) => (item.id === updated.id ? updated : item))
		skills.value = skills.value.map((item) => (item.id === updated.id ? updated : item))
		notify('Skill updated.')
	}
	else {
		const created = await createWorkspaceSkill(tenantId.value, workspaceId, payload)
		workspaceSkills.value = [...workspaceSkills.value, created]
		skills.value = [...skills.value, created]
		notify('Skill created.')
	}
	skillModal.value = false
}
const deleteSkillMessage = computed(() => {
	if (skillToDelete.value?.name) {
		return `Delete skill "${skillToDelete.value.name}"? It will also be removed from assistants that use it.`
	}
	return 'Delete this skill? It will also be removed from assistants that use it.'
})
const openDeleteSkill = (skill) => {
	skillToDelete.value = skill
	deleteSkillOpen.value = true
}
const confirmDeleteSkill = async() => {
	const skill = skillToDelete.value
	if (!skill) {
		return
	}
	await deleteWorkspaceSkill(tenantId.value, workspaceId, skill.id)
	workspaceSkills.value = workspaceSkills.value.filter((item) => item.id !== skill.id)
	skills.value = skills.value.filter((item) => item.id !== skill.id)
	assistantForm.value = {
		...assistantForm.value,
		skill_ids: assistantForm.value.skill_ids.filter((skillId) => skillId !== skill.id)
	}
	if (skillEditId.value === skill.id) {
		skillEditId.value = ''
		skillModal.value = false
	}
	skillToDelete.value = null
	deleteSkillOpen.value = false
	notify('Skill removed.')
}
const cancelDeleteSkill = () => {
	skillToDelete.value = null
	deleteSkillOpen.value = false
}
const loadWorkspace = async() => {
	if (!tenantId.value) {
		return
	}
	workflowsLoading.value = true
	workflowsError.value = ''
	scriptsLoading.value = true
	try {
		const [
      workspaceList,
      assistantsList,
      personaList,
      modelList,
      skillList,
      ruleList,
      rulesList,
      serversList,
      tagsList,
      statesList,
      workflowList,
      templatesList,
      providersList,
      rootPageResponse,
      installedList,
      profileData,
      rootScriptResponse,
      channelList,
    ] = await Promise.all(
			[
				getWorkspaces(tenantId.value),
				Promise.all([
					getAssistants(tenantId.value),
					getWorkspaceAssistants(tenantId.value, workspaceId),
				]),
				getPersonas(tenantId.value),
				getModels(tenantId.value),
				Promise.all([
					getSkills(tenantId.value),
					getWorkspaceSkills(tenantId.value, workspaceId),
				]),
				getRules(tenantId.value),
				getWorkspaceRules(tenantId.value, workspaceId),
				getMcpServers(tenantId.value, workspaceId),
				getWorkspaceTags(tenantId.value, workspaceId),
				getWorkspaceTagStates(tenantId.value, workspaceId),
				getWorkflows(tenantId.value, workspaceId),
				getPromptTemplates(tenantId.value, workspaceId),
				getMcpOidcProviders(tenantId.value, workspaceId),
				getPages(tenantId.value, workspaceId, { non_recursive: true }),
				getInstalledPages(tenantId.value, workspaceId),
				userId ? getProfile(userId) : Promise.resolve(null),
				getScripts(tenantId.value, workspaceId, { non_recursive: true }),
				getChannels(tenantId.value, workspaceId),
			]
		)
		workspace.value = workspaceList.find((item) => item.id === workspaceId) || null
		assistants.value = [...assistantsList[0], ...assistantsList[1]]
		personas.value = Array.isArray(personaList) ? personaList : []
		models.value = Array.isArray(modelList) ? modelList : []
		workspaceSkills.value = Array.isArray(skillList?.[1]) ? skillList[1] : []
		skills.value = [
			...(Array.isArray(skillList?.[0]) ? skillList[0] : []),
			...workspaceSkills.value,
		]
		tenantRules.value = Array.isArray(ruleList) ? ruleList : []
		workspaceRules.value = rulesList
		mcpServers.value = serversList
		tags.value = Array.isArray(tagsList) ? tagsList : []
		tagStates.value = statesList
		workflows.value = workflowList
		promptTemplates.value = Array.isArray(templatesList) ? templatesList : []
		oidcProviders.value = Array.isArray(providersList) ? providersList : []
		pages.value = Array.isArray(rootPageResponse?.pages) ? rootPageResponse.pages : []
		pageNamespaceChildren.value = { '': Array.isArray(rootPageResponse?.namespaces) ? rootPageResponse.namespaces : [] }
		loadedPageNamespaces.value = new Set([''])
		installedPages.value = Array.isArray(installedList) ? installedList : []
		userExecutionMode.value = profileData?.execution_mode || 'released'
		scripts.value = Array.isArray(rootScriptResponse?.scripts) ? rootScriptResponse.scripts : []
		scriptNamespaceChildren.value = { '': Array.isArray(rootScriptResponse?.namespaces) ? rootScriptResponse.namespaces : [] }
		loadedScriptNamespaces.value = new Set([''])
		channels.value = Array.isArray(channelList) ? channelList : []
		policiesByServer.value = serversList.reduce(
			(acc, server) => {
				acc[server.id] = server.policies || []
				return acc
			},
			{}
		)
		await loadWorkspaceScopes()
		await loadWorkspaceToolScopes(serversList)
	}
	catch (error) {
		workflowsError.value = error?.message || 'Unable to load workflows.'
	}
	finally {
		workflowsLoading.value = false
		scriptsLoading.value = false
	}
}
watch(
	() => assistantForm.value.worker_enabled,
	(enabled) => {
		if (!enabled) {
			workerScopeOptions.value = []
			workerScopeError.value = ''
			return
		}
		loadWorkerScopes()
	}
)
const refreshPages = async() => {
	if (!tenantId.value) {
		return
	}
	try {
		const installedList = await getInstalledPages(tenantId.value, workspaceId)
		pageNamespaceChildren.value = { '': [] }
		loadedPageNamespaces.value = new Set()
		pages.value = []
		await loadPageNamespace('')
		for (const namespace of Array.from(openPageFolders.value)) {
			if (!namespace) {
				continue
			}
			await loadPageNamespace(namespace)
		}
		installedPages.value = Array.isArray(installedList) ? installedList : []
		emitPagesUpdated()
	}
	catch (error) {
		notify(error?.message || 'Unable to reload pages.')
	}
}
const refreshScripts = async() => {
	if (!tenantId.value) {
		return
	}
	scriptsLoading.value = true
	scriptsError.value = ''
	try {
		const namespacesToReload = Array.from(openScriptFolders.value)
		scriptNamespaceChildren.value = { '': [] }
		loadedScriptNamespaces.value = new Set()
		scripts.value = []
		await loadScriptNamespace('')
		for (const namespace of namespacesToReload) {
			if (!namespace) {
				continue
			}
			await loadScriptNamespace(namespace)
		}
	}
	catch (error) {
		scriptsError.value = error?.message || 'Unable to reload scripts.'
	}
	finally {
		scriptsLoading.value = false
	}
}
const ensureScriptServerConfig = () => {
	if (serverForm.value.virtual_type !== 'script') {
		return
	}
	if (!serverForm.value.configuration_value || typeof serverForm.value.configuration_value !== 'object') {
		serverForm.value.configuration_value = { scripts: [] }
		return
	}
	if (!Array.isArray(serverForm.value.configuration_value.scripts)) {
		serverForm.value.configuration_value.scripts = []
	}
}
const addScriptToServer = (script) => {
	if (!script || !script.id) {
		return
	}
	ensureScriptServerConfig()
	const list = serverForm.value.configuration_value.scripts
	if (!list.includes(script.id)) {
		list.push(script.id)
	}
	if (!scripts.value.some((item) => item.id === script.id)) {
		scripts.value = [...scripts.value, script]
	}
	scriptPickerQuery.value = ''
	scriptPickerResults.value = []
	scriptPickerError.value = ''
}
const removeScriptFromServer = (scriptId) => {
	ensureScriptServerConfig()
	serverForm.value.configuration_value.scripts = scriptIdsForServer.value.filter((id) => id !== scriptId)
}
const searchScriptsForPicker = async() => {
	if (!tenantId.value || !workspaceId) {
		scriptPickerResults.value = []
		scriptPickerError.value = ''
		scriptPickerLoading.value = false
		return
	}
	const query = scriptPickerQuery.value.trim()
	if (!query) {
		scriptPickerResults.value = []
		scriptPickerError.value = ''
		scriptPickerLoading.value = false
		return
	}
	scriptPickerLoading.value = true
	scriptPickerError.value = ''
	try {
		const response = await getScripts(tenantId.value, workspaceId, { search: query })
		scriptPickerResults.value = Array.isArray(response)
			? response.filter((script) => (script?.type || 'STANDALONE') !== 'WORKFLOW')
			: []
	}
	catch (error) {
		scriptPickerResults.value = []
		scriptPickerError.value = error?.message || 'Unable to search scripts.'
	}
	finally {
		scriptPickerLoading.value = false
	}
}
watch(
	scriptPickerQuery,
	() => {
		searchScriptsForPicker()
	}
)
watch(
	scriptPickerOpen,
	(open) => {
		if (open) {
			searchScriptsForPicker()
			return
		}
		scriptPickerQuery.value = ''
		scriptPickerResults.value = []
		scriptPickerError.value = ''
		scriptPickerLoading.value = false
	}
)
const isPageInstalled = (pageId) => installedPages.value.includes(pageId)
const togglePageInstall = async(page) => {
	if (!tenantId.value || !page || !canInstallPage(page)) {
		return
	}
	try {
		if (isPageInstalled(page.id)) {
			await uninstallPage(tenantId.value, workspaceId, page.id)
			installedPages.value = installedPages.value.filter((id) => id !== page.id)
		}
		else {
			await installPage(tenantId.value, workspaceId, page.id)
			installedPages.value = [...installedPages.value, page.id]
		}
		emitPagesUpdated()
	}
	catch (error) {
		notify(error?.message || 'Unable to update page install.')
	}
}
const requestPageDelete = (page) => {
	pageToDelete.value = page
	deletePageOpen.value = true
}
const requestScriptDelete = (item) => {
	scriptToDelete.value = item
	deleteScriptOpen.value = true
}
const requestPageCopy = (page) => {
	pageToCopy.value = page
	pageCopyIncludeDependencies.value = true
	pageCopyOpen.value = true
}
const confirmPageCopy = async() => {
	if (!pageToCopy.value || !tenantId.value) {
		return
	}
	try {
		const payload = await exportPage(tenantId.value, workspaceId, pageToCopy.value.id, pageCopyIncludeDependencies.value)
		await navigator.clipboard.writeText(JSON.stringify(payload, null, 2))
		notify('Page configuration copied.')
		pageCopyOpen.value = false
	}
	catch (error) {
		notify(error?.message || 'Unable to copy page configuration.')
	}
}
const confirmPageDelete = async() => {
	if (!pageToDelete.value) {
		return
	}
	try {
		await deletePage(tenantId.value, workspaceId, pageToDelete.value.id)
		deletePageOpen.value = false
		pageToDelete.value = null
		await refreshPages()
	}
	catch (error) {
		notify(error?.message || 'Unable to delete page.')
	}
}
const confirmScriptDelete = async() => {
	if (!scriptToDelete.value) {
		return
	}
	try {
		await deleteScript(tenantId.value, workspaceId, scriptToDelete.value.id)
		deleteScriptOpen.value = false
		scriptToDelete.value = null
		await refreshScripts()
	}
	catch (error) {
		notify(error?.message || 'Unable to delete script.')
	}
}
const openPageCreate = () => {
	pageForm.value = {
		name: '',
		label: '',
		description: '',
		namespace: '',
		type: 'PAGE',
		menu_visible: true,
		route_suffix: ''
	}
	pageError.value = ''
	pageModalOpen.value = true
}
const savePage = async() => {
	if (!tenantId.value) {
		return
	}
	if (!pageForm.value.name.trim()) {
		pageError.value = 'Name is required.'
		return
	}
	pageSaving.value = true
	pageError.value = ''
	try {
		await createPage(
			tenantId.value,
			workspaceId,
			{
				...pageForm.value,
				namespace: pageForm.value.namespace || null,
				route_suffix: pageForm.value.route_suffix || null
			}
		)
		pageModalOpen.value = false
		await refreshPages()
	}
	catch (error) {
		pageError.value = error?.message || 'Unable to create page.'
	}
	finally {
		pageSaving.value = false
	}
}
const toLocalDateTime = (value) => {
	if (!value) {
		return ''
	}
	const date = new Date(value)
	if (Number.isNaN(date.getTime())) {
		return ''
	}
	const pad = (num) => String(num).padStart(2, '0')
	return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}
const fromLocalDateTime = (value) => {
	if (!value) {
		return null
	}
	const date = new Date(value)
	return Number.isNaN(date.getTime()) ? null : date.toISOString()
}
const openScriptEdit = async(entry) => {
	if (!tenantId.value || !entry?.id) {
		return
	}
	scriptEditId.value = entry.id
	scriptError.value = ''
	try {
		const current = await getScript(tenantId.value, workspaceId, entry.id)
		scriptForm.value = {
			name: current?.name || '',
			description: current?.description || '',
			namespace: normalizeNamespacePath(current?.namespace || ''),
			type: current?.type || 'STANDALONE',
			scheduled: current?.scheduled || false,
			schedule_rrule: current?.schedule_rrule || '',
			schedule_timezone: current?.schedule_timezone || 'UTC',
			schedule_start_at: toLocalDateTime(current?.schedule_start_at),
			schedule_end_at: toLocalDateTime(current?.schedule_end_at),
			tool_hook_enabled: current?.tool_hook_enabled || false,
			tool_hook_phase: current?.tool_hook_phase || 'BEFORE',
			tool_hook_tool_names: current?.tool_hook_tool_names || []
		}
		scriptToolNamesInput.value = Array.isArray(current?.tool_hook_tool_names) ? current.tool_hook_tool_names.join('\n') : ''
		scriptModalOpen.value = true
	}
	catch (error) {
		notify(error?.message || 'Unable to load script.')
	}
}
const saveScript = async() => {
	if (!tenantId.value) {
		return
	}
	const name = scriptForm.value.name.trim()
	if (!name) {
		scriptError.value = 'Name is required.'
		return
	}
	if (!isValidNamespaceSegment(name)) {
		scriptError.value = 'Script name may only contain letters, numbers, spaces, dots, dashes, and parentheses.'
		return
	}
	const namespace = normalizeNamespacePath(scriptForm.value.namespace)
	if (namespace && splitNamespace(namespace).some((segment) => !isValidNamespaceSegment(segment))) {
		scriptError.value = 'Namespace contains an invalid folder name.'
		return
	}
	scriptSaving.value = true
	scriptError.value = ''
	try {
		const toolHookToolNames = scriptToolNamesInput.value.split('\n').map((value) => value.trim()).filter(Boolean)
		const payload = {
			...scriptForm.value,
			namespace: namespace || null,
			scheduled: scriptForm.value.type === 'STANDALONE' && scriptForm.value.scheduled,
			schedule_rrule: scriptForm.value.type === 'STANDALONE'
					&& scriptForm.value.scheduled
				? scriptForm.value.schedule_rrule
				: null,
			schedule_timezone: scriptForm.value.type === 'STANDALONE'
					&& scriptForm.value.scheduled
				? scriptForm.value.schedule_timezone
				: null,
			schedule_start_at: scriptForm.value.type === 'STANDALONE'
					&& scriptForm.value.scheduled
				? fromLocalDateTime(scriptForm.value.schedule_start_at)
				: null,
			schedule_end_at: scriptForm.value.type === 'STANDALONE'
					&& scriptForm.value.scheduled
				? fromLocalDateTime(scriptForm.value.schedule_end_at)
				: null,
			tool_hook_enabled: scriptForm.value.type === 'STANDALONE' && scriptForm.value.tool_hook_enabled,
			tool_hook_phase: scriptForm.value.type === 'STANDALONE'
					&& scriptForm.value.tool_hook_enabled
				? scriptForm.value.tool_hook_phase
				: null,
			tool_hook_tool_names: scriptForm.value.type === 'STANDALONE'
					&& scriptForm.value.tool_hook_enabled
				? toolHookToolNames
				: []
		}
		if (scriptEditId.value) {
			await updateScript(tenantId.value, workspaceId, scriptEditId.value, payload)
		}
		else {
			await createScript(tenantId.value, workspaceId, payload)
		}
		scriptModalOpen.value = false
		scriptEditId.value = ''
		await refreshScripts()
	}
	catch (error) {
		scriptError.value = error?.message || `Unable to ${scriptEditId.value ? 'update' : 'create'} script.`
	}
	finally {
		scriptSaving.value = false
	}
}
const openPageImport = () => {
	pageImportPayload.value = ''
	pageImportError.value = ''
	pageImportUpdateSlugs.value = true
	pageImportOpen.value = true
}
const applyPageImportPayload = async() => {
	pageImportError.value = ''
	if (!tenantId.value) {
		return
	}
	try {
		const payload = JSON.parse(pageImportPayload.value || '{}')
		const pagesPayload = Array.isArray(payload?.pages) ? payload : { pages: [payload] }
		const importResult = await importPages(tenantId.value, workspaceId, { ...pagesPayload, update_slugs: pageImportUpdateSlugs.value })
		const slugMapping = importResult?.slug_mapping || {}
		const importedPages = Array.isArray(importResult?.pages) ? importResult.pages : []
		const pagesBySlug = new Map(importedPages.map((page) => [page.slug, page]))
		const entries = Array.isArray(pagesPayload.pages) ? pagesPayload.pages : []
		for (const entry of entries) {
			if (!entry || !entry.source_sfc) {
				continue
			}
			const originalSlug = entry.slug?.trim() || buildNamespaceNameSlug(null, entry.name || '')
			const mappedSlug = slugMapping[originalSlug] || originalSlug
			const page = pagesBySlug.get(mappedSlug)
			if (!page) {
				continue
			}
			const allowlist = Array.isArray(entry.import_allowlist) ? entry.import_allowlist : []
			const compileResult = await compilePageSource({ source: entry.source_sfc, allowlist, pageId: page.id })
			await compilePageDraft(
				tenantId.value,
				workspaceId,
				page.id,
				{
					source_sfc: entry.source_sfc,
					compiled_bundle: compileResult.compiledBundle || '',
					compile_errors: compileResult.compileErrors || ''
				}
			)
		}
		pageImportOpen.value = false
		await refreshPages()
	}
	catch (error) {
		pageImportError.value = error?.message || 'Invalid page JSON.'
	}
}
watch(
	() => serverForm.value.virtual_type,
	() => {
		ensureScriptServerConfig()
	}
)
const loadWorkspaceScopes = async() => {
	if (!tenantId.value) {
		return
	}
	workspaceScopeLoading.value = true
	workspaceScopeError.value = ''
	try {
		const response = await getWorkspaceScopes(tenantId.value, workspaceId)
		workspaceScopeAllow.value = response?.allow_scopes || []
		workspaceScopeDeny.value = response?.deny_scopes || []
	}
	catch (error) {
		workspaceScopeAllow.value = []
		workspaceScopeDeny.value = []
		workspaceScopeError.value = error?.message || 'Unable to load workspace scopes.'
	}
	finally {
		workspaceScopeLoading.value = false
	}
}
const loadWorkspaceToolScopes = async(serversList = mcpServers.value) => {
	if (!tenantId.value) {
		return
	}
	if (!Array.isArray(serversList) || serversList.length === 0) {
		workspaceToolScopes.value = []
		return
	}
	try {
		const results = await Promise.allSettled(serversList.map((server) => getMcpServerTools(tenantId.value, workspaceId, server.id)))
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
const updateWorkspaceScopesState = async(allowScopes, denyScopes) => {
	if (!tenantId.value) {
		return
	}
	const previousAllow = workspaceScopeAllow.value
	const previousDeny = workspaceScopeDeny.value
	workspaceScopeAllow.value = allowScopes
	workspaceScopeDeny.value = denyScopes
	workspaceScopeSaving.value = true
	workspaceScopeError.value = ''
	try {
		const response = await updateWorkspaceScopes(tenantId.value, workspaceId, { allow_scopes: allowScopes, deny_scopes: denyScopes })
		workspaceScopeAllow.value = response?.allow_scopes || allowScopes
		workspaceScopeDeny.value = response?.deny_scopes || denyScopes
		notify('Workspace scopes updated.')
	}
	catch (error) {
		workspaceScopeError.value = error?.message || 'Unable to update workspace scopes.'
		workspaceScopeAllow.value = previousAllow
		workspaceScopeDeny.value = previousDeny
	}
	finally {
		workspaceScopeSaving.value = false
	}
}
const handleScopeUpdate = (payload) => {
	if (!payload) {
		return
	}
	const allow = payload.allow || []
	const deny = payload.deny || []
	workspaceScopeAllow.value = allow
	workspaceScopeDeny.value = deny
	if (workspaceScopeSaveTimer) {
		clearTimeout(workspaceScopeSaveTimer)
	}
	workspaceScopeSaveTimer = setTimeout(
		() => {
			workspaceScopeSaveTimer = null
			updateWorkspaceScopesState(allow, deny)
		},
		300
	)
}
const handleWorkspaceMessage = (event) => {
	if (!event || typeof event !== 'object') {
		return
	}
	if (event.type === 'mcp.update') {
		getMcpServers(tenantId.value, workspaceId)
			.then((serversList) => {
				mcpServers.value = serversList
				policiesByServer.value = serversList.reduce(
					(acc, server) => {
						acc[server.id] = server.policies || []
						return acc
					},
					{}
				)
				loadWorkspaceToolScopes(serversList)
			})
			.catch(() => {})
	}
}
const openRuleCreate = () => {
	ruleEditId.value = ''
	ruleForm.value = {
		name: '',
		content: '',
		always_included: false,
		enabled: true,
		order: null
	}
	ruleModal.value = true
}
const openRuleEdit = (rule) => {
	ruleEditId.value = rule.id
	ruleForm.value = {
		name: rule.name,
		content: rule.content || '',
		always_included: rule.always_included,
		enabled: rule.enabled,
		order: rule.order ?? null
	}
	ruleModal.value = true
}
const saveRule = async() => {
	if (!ruleForm.value.name.trim() || !ruleForm.value.content.trim()) {
		notify('Rule name and content are required.')
		return
	}
	const payload = { ...ruleForm.value, order: ruleForm.value.order === '' ? null : ruleForm.value.order }
	if (ruleEditId.value) {
		const updated = await updateWorkspaceRule(tenantId.value, workspaceId, ruleEditId.value, payload)
		workspaceRules.value = workspaceRules.value.map((item) => (item.id === updated.id ? updated : item))
		notify('Rule updated.')
	}
	else {
		const created = await createWorkspaceRule(tenantId.value, workspaceId, payload)
		workspaceRules.value = [...workspaceRules.value, created]
		notify('Rule created.')
	}
	ruleModal.value = false
}
const requestDeleteRule = (rule) => {
	ruleToDelete.value = rule
	deleteRuleOpen.value = true
}
const confirmDeleteRule = async() => {
	const rule = ruleToDelete.value
	if (!rule) {
		return
	}
	await deleteWorkspaceRule(tenantId.value, workspaceId, rule.id)
	workspaceRules.value = workspaceRules.value.filter((item) => item.id !== rule.id)
	ruleToDelete.value = null
	notify('Rule removed.')
}
const cancelDeleteRule = () => {
	ruleToDelete.value = null
}
const openServerCreate = () => {
	serverEditId.value = ''
	serverProbeState.value = { status: 'idle', message: '' }
	serverUsesManagedInstance.value = false
	toolConfigOpen.value = false
	applicationConfigOpen.value = false
	serverTools.value = []
	serverToolsError.value = ''
	serverToolsBaseline.value = {}
	toolSavingAll.value = false
	serverApplications.value = []
	serverApplicationsError.value = ''
	serverApplicationsBaseline.value = {}
	applicationSavingAll.value = false
	serverForm.value = {
		name: '',
		description: '',
		protocol: 'STDIO',
		framing: 'ndjson',
		command: '',
		cwd: '',
		http_url: '',
		virtual_type: 'polymr',
		headers: '',
		environment: '',
		ssh_enabled: false,
		ssh_tunnel: {
			server: '',
			port: 22,
			username: '',
			password: '',
			key_path: '',
			key: ''
		},
		allow_policy: false,
		visibility: 'VISIBLE',
		tool_name_prefix: '',
		instructions: '',
		custom_instructions: false,
		config_schema_json: '',
		configuration_value: {},
		oauth_enabled: false,
		auth: {
			enabled: false,
			global: true,
			issuer: '',
			well_known_url: '',
			resource_metadata_url: '',
			authorization_endpoint: '',
			token_endpoint: '',
			registration_endpoint: '',
			scopes: '',
			supported_scopes: [],
			selected_supported_scopes: [],
			custom_scopes: '',
			client_id: '',
			client_secret: '',
			client_secret_hint: '',
			status: '',
			refreshable: false,
			last_auth_at: ''
		}
	}
	serverModal.value = true
}
const openServerEdit = (server) => {
	serverEditId.value = server.id
	serverProbeState.value = { status: 'idle', message: '' }
	toolConfigOpen.value = false
	applicationConfigOpen.value = false
	serverUsesManagedInstance.value = !!server.command
	serverForm.value = {
		name: server.name,
		description: server.description || '',
		protocol: server.protocol,
		framing: server.framing || 'ndjson',
		command: server.command || '',
		cwd: server.cwd || '',
		http_url: server.http_url || '',
		virtual_type: server.virtual_type || 'polymr',
		headers: server.headers || '',
		environment: server.environment || '',
		ssh_enabled: !!server.ssh_enabled,
		ssh_tunnel: {
			server: server.ssh_tunnel?.server || '',
			port: server.ssh_tunnel?.port || 22,
			username: server.ssh_tunnel?.username || '',
			password: '',
			key_path: server.ssh_tunnel?.key_path || '',
			key: ''
		},
		allow_policy: !!server.allow_policy,
		visibility: server.visibility || 'VISIBLE',
		tool_name_prefix: server.tool_name_prefix || '',
		instructions: server.instructions || '',
		custom_instructions: !!server.custom_instructions,
		config_schema_json: server.config_schema ? JSON.stringify(server.config_schema, null, 2) : '',
		configuration_value: server.configuration_json || {},
		oauth_enabled: !!server.oauth_enabled,
		auth: parseServerAuth(server.auth)
	}
	scriptPickerQuery.value = ''
	scriptPickerOpen.value = false
	ensureScriptServerConfig()
	loadServerTools()
	loadServerApplications()
	if (server.protocol === 'SSE' || server.protocol === 'STREAMABLE_HTTP') {
		loadServerOverrides(server.id)
	}
	else {
		overridesByServer.value = { ...overridesByServer.value, [server.id]: [] }
	}
	serverModal.value = true
}
const loadServerTools = async() => {
	if (!serverEditId.value) {
		serverTools.value = []
		return
	}
	serverToolsLoading.value = true
	serverToolsError.value = ''
	try {
		const tools = await getMcpServerTools(tenantId.value, workspaceId, serverEditId.value)
		serverTools.value = tools.slice()
			.sort((a, b) => (a.tool_name || '').localeCompare(b.tool_name || '', undefined, { sensitivity: 'base' }))
			.map((tool) => ({
			...tool,
			disabled: !!tool.disabled,
			custom_scopes_text: Array.isArray(tool.custom_scopes) ? tool.custom_scopes.join(', ') : ''
		}))
		serverToolsBaseline.value = tools.reduce(
			(acc, tool) => {
				acc[tool.id] = {
					tool_alias: tool.tool_alias || '',
					custom_scopes: Array.isArray(tool.custom_scopes) ? tool.custom_scopes : [],
					disabled: !!tool.disabled
				}
				return acc
			},
			{}
		)
	}
	catch (error) {
		serverToolsError.value = error?.message || 'Unable to load tools.'
		serverTools.value = []
		serverToolsBaseline.value = {}
	}
	finally {
		serverToolsLoading.value = false
	}
	scriptPickerQuery.value = ''
	scriptPickerOpen.value = false
}
const refreshServerTools = async() => {
	if (!serverEditId.value) {
		return
	}
	serverToolsLoading.value = true
	serverToolsError.value = ''
	try {
		const tools = await refreshMcpServerTools(tenantId.value, workspaceId, serverEditId.value)
		serverTools.value = tools.slice()
			.sort((a, b) => (a.tool_name || '').localeCompare(b.tool_name || '', undefined, { sensitivity: 'base' }))
			.map((tool) => ({
			...tool,
			disabled: !!tool.disabled,
			custom_scopes_text: Array.isArray(tool.custom_scopes) ? tool.custom_scopes.join(', ') : ''
		}))
		serverToolsBaseline.value = tools.reduce(
			(acc, tool) => {
				acc[tool.id] = {
					tool_alias: tool.tool_alias || '',
					custom_scopes: Array.isArray(tool.custom_scopes) ? tool.custom_scopes : [],
					disabled: !!tool.disabled
				}
				return acc
			},
			{}
		)
	}
	catch (error) {
		serverToolsError.value = error?.message || 'Unable to refresh tools.'
	}
	finally {
		serverToolsLoading.value = false
	}
}
const loadServerApplications = async() => {
	if (!serverEditId.value) {
		serverApplications.value = []
		return
	}
	serverApplicationsLoading.value = true
	serverApplicationsError.value = ''
	try {
		const apps = await getMcpServerApplications(tenantId.value, workspaceId, serverEditId.value)
		serverApplications.value = apps.map((app) => ({ ...app, display_name: app.display_name || '', icon_svg: app.icon_svg || '' }))
		serverApplicationsBaseline.value = apps.reduce(
			(acc, app) => {
				acc[app.id] = { display_name: app.display_name || '', icon_svg: app.icon_svg || '', disabled: !!app.disabled }
				return acc
			},
			{}
		)
	}
	catch (error) {
		serverApplicationsError.value = error?.message || 'Unable to load applications.'
		serverApplications.value = []
		serverApplicationsBaseline.value = {}
	}
	finally {
		serverApplicationsLoading.value = false
	}
}
const refreshServerApplications = async() => {
	if (!serverEditId.value) {
		return
	}
	serverApplicationsLoading.value = true
	serverApplicationsError.value = ''
	try {
		const updated = await probeMcpServer(tenantId.value, workspaceId, serverEditId.value)
		mcpServers.value = mcpServers.value.map((item) => (item.id === updated.id ? updated : item))
		serverForm.value.config_schema_json = updated.config_schema ? JSON.stringify(updated.config_schema, null, 2) : ''
		serverForm.value.allow_policy = !!updated.allow_policy
		const apps = await getMcpServerApplications(tenantId.value, workspaceId, serverEditId.value)
		serverApplications.value = apps.map((app) => ({ ...app, display_name: app.display_name || '', icon_svg: app.icon_svg || '' }))
		serverApplicationsBaseline.value = apps.reduce(
			(acc, app) => {
				acc[app.id] = { display_name: app.display_name || '', icon_svg: app.icon_svg || '', disabled: !!app.disabled }
				return acc
			},
			{}
		)
	}
	catch (error) {
		serverApplicationsError.value = error?.message || 'Unable to refresh applications.'
	}
	finally {
		serverApplicationsLoading.value = false
	}
}
const normalizeText = (value) => (value || '').trim()
const applicationConfigDirty = computed(() => {
	if (!serverEditId.value) {
		return false
	}
	return serverApplications.value
		.some((app) => {
			const baseline = serverApplicationsBaseline.value[app.id]
			if (!baseline) {
				return true
			}
			const name = normalizeText(app.display_name)
			const baseName = normalizeText(baseline.display_name)
			if (name !== baseName) {
				return true
			}
			const icon = normalizeText(app.icon_svg)
			const baseIcon = normalizeText(baseline.icon_svg)
			if (icon !== baseIcon) {
				return true
			}
			return !!app.disabled !== !!baseline.disabled
		})
})
const saveApplicationConfigs = async() => {
	if (!serverEditId.value || !applicationConfigDirty.value) {
		return
	}
	applicationSavingAll.value = true
	try {
		const updates = serverApplications.value
			.filter((app) => {
				const baseline = serverApplicationsBaseline.value[app.id]
				if (!baseline) {
					return true
				}
				const name = normalizeText(app.display_name)
				const baseName = normalizeText(baseline.display_name)
				if (name !== baseName) {
					return true
				}
				const icon = normalizeText(app.icon_svg)
				const baseIcon = normalizeText(baseline.icon_svg)
				if (icon !== baseIcon) {
					return true
				}
				return !!app.disabled !== !!baseline.disabled
			})
		if (!updates.length) {
			return
		}
		const results = await Promise.all(
			updates.map((app) => updateMcpServerApplication(
				tenantId.value,
				workspaceId,
				serverEditId.value,
				app.id,
				{
					display_name: normalizeText(app.display_name) || null,
					icon_svg: normalizeText(app.icon_svg) || null,
					disabled: !!app.disabled
				}
			))
		)
		serverApplications.value = serverApplications.value
			.map((app) => {
				const updated = results.find((entry) => entry.id === app.id)
				if (!updated) {
					return app
				}
				return { ...updated, display_name: updated.display_name || '', icon_svg: updated.icon_svg || '' }
			})
		serverApplicationsBaseline.value = serverApplications.value
			.reduce(
				(acc, app) => {
					acc[app.id] = { display_name: app.display_name || '', icon_svg: app.icon_svg || '', disabled: !!app.disabled }
					return acc
				},
				{}
			)
		notify('Application settings updated.')
	}
	catch (error) {
		notify(error?.message || 'Unable to update applications.')
	}
	finally {
		applicationSavingAll.value = false
	}
}
const handleAppIconUpload = (app, event) => {
	const file = event?.target?.files?.[0]
	if (!file) {
		return
	}
	const reader = new FileReader()
	reader.onload = () => {
		app.icon_svg = typeof reader.result === 'string' ? reader.result : ''
	}
	reader.readAsText(file)
	event.target.value = ''
}
const clearAppIcon = (app) => {
	app.icon_svg = ''
}
const parseScopeText = (value) => {
	if (!value || typeof value !== 'string') {
		return []
	}
	return value.split(',').map((entry) => entry.trim()).filter((entry) => entry)
}
const normalizeScopes = (value) => {
	if (Array.isArray(value)) {
		return value.map((entry) => String(entry).trim()).filter(Boolean)
	}
	return parseScopeText(value)
}
const toolConfigDirty = computed(() => {
	if (!serverEditId.value) {
		return false
	}
	return serverTools.value
		.some((tool) => {
			const baseline = serverToolsBaseline.value[tool.id]
			if (!baseline) {
				return true
			}
			const alias = (tool.tool_alias || '').trim()
			const baselineAlias = (baseline.tool_alias || '').trim()
			if (alias !== baselineAlias) {
				return true
			}
			const currentScopes = normalizeScopes(tool.custom_scopes_text).sort().join('|')
			const baselineScopes = normalizeScopes(baseline.custom_scopes || []).sort().join('|')
			if (currentScopes !== baselineScopes) {
				return true
			}
			return !!tool.disabled !== !!baseline.disabled
		})
})
const saveToolConfigs = async() => {
	if (!serverEditId.value || !toolConfigDirty.value) {
		return
	}
	toolSavingAll.value = true
	try {
		const updates = serverTools.value
			.filter((tool) => {
				const baseline = serverToolsBaseline.value[tool.id]
				if (!baseline) {
					return true
				}
				const alias = (tool.tool_alias || '').trim()
				const baselineAlias = (baseline.tool_alias || '').trim()
				if (alias !== baselineAlias) {
					return true
				}
				const currentScopes = normalizeScopes(tool.custom_scopes_text).sort().join('|')
				const baselineScopes = normalizeScopes(baseline.custom_scopes || []).sort().join('|')
				if (currentScopes !== baselineScopes) {
					return true
				}
				return !!tool.disabled !== !!baseline.disabled
			})
			.map((tool) => ({
			tool_id: tool.id,
			tool_alias: tool.tool_alias || null,
			custom_scopes: normalizeScopes(tool.custom_scopes_text),
			disabled: !!tool.disabled
		}))
		if (!updates.length) {
			return
		}
		const updatedTools = await updateMcpServerTools(tenantId.value, workspaceId, serverEditId.value, updates)
		serverTools.value = serverTools.value
			.map((item) => {
				const updated = updatedTools.find((tool) => tool.id === item.id)
				if (!updated) {
					return item
				}
				return {
					...updated,
					disabled: !!updated.disabled,
					custom_scopes_text: Array.isArray(updated.custom_scopes) ? updated.custom_scopes.join(', ') : ''
				}
			})
			.sort((a, b) => (a.tool_name || '').localeCompare(b.tool_name || '', undefined, { sensitivity: 'base' }))
		serverToolsBaseline.value = serverTools.value
			.reduce(
				(acc, tool) => {
					acc[tool.id] = {
						tool_alias: tool.tool_alias || '',
						custom_scopes: normalizeScopes(tool.custom_scopes_text),
						disabled: !!tool.disabled
					}
					return acc
				},
				{}
			)
	}
	catch (error) {
		notify(error?.message || 'Unable to update tool configuration.')
	}
	finally {
		toolSavingAll.value = false
	}
}
const runServerProbe = async() => {
	if (!serverEditId.value) {
		notify('Save the server before probing.')
		return
	}
	serverProbeState.value = { status: 'loading', message: 'Checking server…' }
	try {
		const updated = await probeMcpServer(tenantId.value, workspaceId, serverEditId.value)
		mcpServers.value = mcpServers.value.map((item) => (item.id === updated.id ? updated : item))
		serverForm.value.config_schema_json = updated.config_schema ? JSON.stringify(updated.config_schema, null, 2) : ''
		serverForm.value.allow_policy = !!updated.allow_policy
		await loadServerTools()
		await loadServerApplications()
		serverProbeState.value = { status: 'success', message: 'Schema detected.' }
	}
	catch (error) {
		serverProbeState.value = { status: 'error', message: error?.message || 'Probe failed.' }
	}
}
const openServerDefinition = async() => {
	if (!serverEditId.value) {
		notify('Save the server before opening the definition.')
		return
	}
	definitionOpen.value = true
	definitionLoading.value = true
	definitionError.value = ''
	definitionPayload.value = ''
	try {
		const definition = await getMcpServerDefinition(tenantId.value, workspaceId, serverEditId.value)
		definitionPayload.value = JSON.stringify(definition ?? {}, null, 2)
	}
	catch (error) {
		definitionError.value = error?.message || 'Unable to load MCP definition.'
	}
	finally {
		definitionLoading.value = false
	}
}
const copyServerDefinition = async() => {
	if (!definitionPayload.value) {
		return
	}
	try {
		if (navigator?.clipboard?.writeText) {
			await navigator.clipboard.writeText(definitionPayload.value)
		}
		else {
			const textarea = document.createElement('textarea')
			textarea.value = definitionPayload.value
			textarea.setAttribute('readonly', 'true')
			textarea.style.position = 'absolute'
			textarea.style.left = '-9999px'
			document.body.appendChild(textarea)
			textarea.select()
			document.execCommand('copy')
			document.body.removeChild(textarea)
		}
		notify('Definition copied to clipboard.')
	}
	catch (error) {
		notify(error?.message || 'Unable to copy definition.')
	}
}
const copyText = async(value, successMessage, errorMessage) => {
	if (!value) {
		return
	}
	try {
		if (navigator?.clipboard?.writeText) {
			await navigator.clipboard.writeText(value)
		}
		else {
			const textarea = document.createElement('textarea')
			textarea.value = value
			textarea.setAttribute('readonly', 'true')
			textarea.style.position = 'absolute'
			textarea.style.left = '-9999px'
			document.body.appendChild(textarea)
			textarea.select()
			document.execCommand('copy')
			document.body.removeChild(textarea)
		}
		notify(successMessage)
	}
	catch (error) {
		notify(error?.message || errorMessage)
	}
}
const buildServerConfigExport = (server, tools) => {
	const auth = server?.auth ? { ...server.auth } : null
	if (auth) {
		delete auth.status
	}
	return {
		version: 1,
		server: {
			name: server?.name || '',
			description: server?.description || '',
			protocol: server?.protocol || 'STDIO',
			framing: server?.framing || 'ndjson',
			command: server?.command || '',
			cwd: server?.cwd || '',
			http_url: server?.http_url || '',
			headers: server?.headers || '',
			environment: server?.environment || '',
			ssh_enabled: !!server?.ssh_enabled,
			ssh_tunnel: server?.ssh_tunnel || null,
			visibility: server?.visibility || 'VISIBLE',
			supports_dynamic_config: !!server?.supports_dynamic_config,
			config_schema: server?.config_schema || null,
			configuration_json: server?.configuration_json || {},
			oauth_enabled: !!server?.oauth_enabled,
			auth
		},
		tools: (tools || []).map((tool) => ({
			tool_name: tool.tool_name,
			description: tool.description || null,
			scopes: Array.isArray(tool.scopes) ? tool.scopes : [],
			dynamic_scopes: !!tool.dynamic_scopes,
			tool_alias: tool.tool_alias || null,
			custom_scopes: Array.isArray(tool.custom_scopes) ? tool.custom_scopes : [],
			disabled: !!tool.disabled
		}))
	}
}
const exportServerConfig = async(server) => {
	if (!server) {
		return
	}
	try {
		const tools = await getMcpServerTools(tenantId.value, workspaceId, server.id)
		const payload = buildServerConfigExport(server, tools)
		await copyText(
			JSON.stringify(payload, null, 2),
			'Server configuration copied to clipboard.',
			'Unable to copy server configuration.'
		)
	}
	catch (error) {
		notify(error?.message || 'Unable to export server configuration.')
	}
}
const openImportServer = () => {
	serverImportPayload.value = ''
	serverImportError.value = ''
	pendingImportTools.value = null
	pendingImportConfig.value = null
	serverImportOpen.value = true
}
const applyImportPayload = () => {
	serverImportError.value = ''
	pendingImportTools.value = null
	let parsed
	try {
		parsed = JSON.parse(serverImportPayload.value)
	}
	catch (error) {
		serverImportError.value = 'Invalid JSON payload.'
		return
	}
	if (!parsed || typeof parsed !== 'object' || !parsed.server) {
		serverImportError.value = 'Missing server configuration.'
		return
	}
	const server = parsed.server
	serverForm.value = {
		name: server.name || '',
		description: server.description || '',
		protocol: server.protocol || 'STDIO',
		framing: server.framing || 'ndjson',
		command: server.command || '',
		cwd: server.cwd || '',
		http_url: server.http_url || '',
		headers: server.headers || '',
		environment: server.environment || '',
		ssh_enabled: !!server.ssh_enabled,
		ssh_tunnel: {
			server: server.ssh_tunnel?.server || '',
			port: server.ssh_tunnel?.port || 22,
			username: server.ssh_tunnel?.username || '',
			password: '',
			key_path: server.ssh_tunnel?.key_path || '',
			key: ''
		},
		allow_policy: false,
		visibility: server.visibility || 'VISIBLE',
		tool_name_prefix: server.tool_name_prefix || '',
		instructions: server.instructions || '',
		custom_instructions: !!server.custom_instructions,
		config_schema_json: server.config_schema ? JSON.stringify(server.config_schema, null, 2) : '',
		configuration_value: server.configuration_json || {},
		oauth_enabled: !!server.oauth_enabled,
		auth: {
			...parseServerAuth(server.auth || null),
			client_id: '',
			client_secret: '',
			client_secret_hint: ''
		}
	}
	serverEditId.value = ''
	pendingImportTools.value = Array.isArray(parsed.tools) ? parsed.tools : []
	pendingImportConfig.value = server.configuration_json || null
	serverImportOpen.value = false
	serverModal.value = true
}
const saveServer = async() => {
	if (!serverForm.value.name.trim()) {
		notify('Server name is required.')
		return
	}
	if (serverForm.value.protocol === 'STDIO' && !serverForm.value.command.trim()) {
		notify('Command is required for stdio servers.')
		return
	}
	if (serverForm.value.protocol === 'VIRTUAL' && !serverForm.value.virtual_type) {
		notify('Virtual server type is required.')
		return
	}
	if ((serverForm.value.protocol === 'SSE' || serverForm.value.protocol === 'STREAMABLE_HTTP')
			&& !serverForm.value.http_url.trim()) {
		notify('HTTP URL is required for HTTP servers.')
		return
	}
	if (configSchemaError.value) {
		notify(configSchemaError.value)
		return
	}
	const payload = buildServerPayload()
	let saved = null
	if (serverEditId.value) {
		saved = await updateMcpServer(tenantId.value, workspaceId, serverEditId.value, payload)
		mcpServers.value = mcpServers.value.map((item) => (item.id === saved.id ? saved : item))
		if (toolConfigDirty.value) {
			await saveToolConfigs()
		}
		if (applicationConfigDirty.value) {
			await saveApplicationConfigs()
		}
		notify('MCP server updated.')
	}
	else {
		saved = await createMcpServer(tenantId.value, workspaceId, payload)
		mcpServers.value = [...mcpServers.value, saved]
		pendingImportTools.value = null
		notify('MCP server created.')
	}
	if (saved && pendingImportConfig.value != null && !configSchemaValue.value) {
		try {
			const updated = await probeMcpServer(tenantId.value, workspaceId, saved.id)
			mcpServers.value = mcpServers.value.map((item) => (item.id === updated.id ? updated : item))
			serverForm.value.config_schema_json = updated.config_schema ? JSON.stringify(updated.config_schema, null, 2) : ''
			serverForm.value.allow_policy = !!updated.allow_policy
		}
		catch (error) {
			notify(error?.message || 'Unable to probe server configuration.')
		}
	}
	pendingImportConfig.value = null
	serverModal.value = false
}
const requestDeleteServer = (server) => {
	serverToDelete.value = server
	deleteServerOpen.value = true
}
const confirmDeleteServer = async() => {
	const server = serverToDelete.value
	if (!server) {
		return
	}
	await deleteMcpServer(tenantId.value, workspaceId, server.id)
	mcpServers.value = mcpServers.value.filter((item) => item.id !== server.id)
	serverToDelete.value = null
	notify('MCP server removed.')
}
const requestDeleteWorkflow = (workflow) => {
	workflowToDelete.value = workflow
	deleteWorkflowOpen.value = true
}
const confirmDeleteWorkflow = async() => {
	const workflow = workflowToDelete.value
	if (!workflow) {
		return
	}
	await deleteWorkflow(tenantId.value, workspaceId, workflow.id)
	workflows.value = workflows.value.filter((item) => item.id !== workflow.id)
	workflowToDelete.value = null
	deleteWorkflowOpen.value = false
	notify('Workflow removed.')
}
const cancelDeleteWorkflow = () => {
	workflowToDelete.value = null
	deleteWorkflowOpen.value = false
}
const cancelDeleteServer = () => {
	serverToDelete.value = null
}
const resetAssistantForm = () => {
	assistantForm.value = {
		name: '',
		description: '',
		prompt_text: '',
		persona_id: '',
		model_id: '',
		max_output_tokens: '',
		skill_ids: [],
		rule_ids: [],
		worker_enabled: false,
		worker_trigger: '',
		worker_allow_scopes: [],
		worker_deny_scopes: []
	}
	assistantError.value = ''
	assistantEditId.value = ''
}
const openAssistantCreate = () => {
	resetAssistantForm()
	assistantModalOpen.value = true
}
const openAssistantEdit = async(assistant) => {
	if (!assistant?.id) {
		return
	}
	assistantError.value = ''
	try {
		const details = await getAssistant(tenantId.value, assistant.id)
		assistantEditId.value = details.id
		assistantForm.value = {
			name: details.name || '',
			description: details.description || '',
			prompt_text: details.prompt_text || '',
			persona_id: details.persona_id || '',
			model_id: details.model_id || '',
			max_output_tokens: details.max_output_tokens || '',
			skill_ids: details.skill_ids || [],
			rule_ids: details.rule_ids || [],
			worker_enabled: !!details.worker_enabled,
			worker_trigger: details.worker_trigger || '',
			worker_allow_scopes: details.worker_allow_scopes || [],
			worker_deny_scopes: details.worker_deny_scopes || []
		}
		assistantModalOpen.value = true
	}
	catch (error) {
		notify(error?.message || 'Unable to load assistant.')
	}
}
const saveAssistant = async() => {
	if (!assistantForm.value.name.trim()) {
		assistantError.value = 'Name is required.'
		return
	}
	if (!assistantForm.value.model_id) {
		assistantError.value = 'Model is required.'
		return
	}
	assistantSaving.value = true
	assistantError.value = ''
	try {
		const payload = {
			name: assistantForm.value.name.trim(),
			description: assistantForm.value.description,
			prompt_text: assistantForm.value.prompt_text,
			persona_id: assistantForm.value.persona_id || null,
			model_id: assistantForm.value.model_id,
			workspace_id: workspaceId,
			max_output_tokens: assistantForm.value.max_output_tokens ? Number(assistantForm.value.max_output_tokens) : null,
			skill_ids: assistantForm.value.skill_ids || [],
			rule_ids: assistantForm.value.rule_ids || [],
			worker_enabled: assistantForm.value.worker_enabled,
			worker_trigger: assistantForm.value.worker_trigger,
			worker_allow_scopes: assistantForm.value.worker_allow_scopes,
			worker_deny_scopes: assistantForm.value.worker_deny_scopes
		}
		if (assistantEditId.value) {
			const updated = await updateAssistant(tenantId.value, assistantEditId.value, payload)
			assistants.value = assistants.value.map((item) => (item.id === updated.id ? updated : item))
		}
		else {
			const created = await createAssistant(tenantId.value, payload)
			assistants.value = [...assistants.value, created]
		}
		assistantModalOpen.value = false
		resetAssistantForm()
		notify('Assistant saved.')
	}
	catch (error) {
		assistantError.value = error?.message || 'Unable to save assistant.'
	}
	finally {
		assistantSaving.value = false
	}
}
const requestDeleteAssistant = (assistant) => {
	assistantToDelete.value = assistant
	deleteAssistantOpen.value = true
}
const confirmDeleteAssistant = async() => {
	const assistant = assistantToDelete.value
	if (!assistant) {
		return
	}
	try {
		await deleteAssistant(tenantId.value, assistant.id)
		assistants.value = assistants.value.filter((item) => item.id !== assistant.id)
		if (assistantEditId.value === assistant.id) {
			assistantModalOpen.value = false
			resetAssistantForm()
		}
		assistantToDelete.value = null
		deleteAssistantOpen.value = false
		notify('Assistant deleted.')
	}
	catch (error) {
		if (error?.status === 409) {
			notify(error?.message || 'Assistant is still used by active sessions.')
			return
		}
		notify(error?.message || 'Unable to delete assistant.')
	}
}
const requestDeleteAssistantFromPane = () => {
	if (!assistantEditId.value) {
		return
	}
	const assistant = assistants.value.find((item) => item.id === assistantEditId.value)
		|| { id: assistantEditId.value, name: assistantForm.value.name }
	requestDeleteAssistant(assistant)
}
const cancelDeleteAssistant = () => {
	assistantToDelete.value = null
	deleteAssistantOpen.value = false
}
const openTagCreate = () => {
	tagForm.value = { name: '', slug: '', priority: 0 }
	tagModal.value = true
}
const closeTagValueForm = () => {
	tagValueFormOpen.value = false
	tagValueFormMode.value = 'create'
	tagValueForm.value = {
		category_id: '',
		name: '',
		slug: '',
		priority: 0
	}
	tagValueEditForm.value = {
		category_id: '',
		id: '',
		name: '',
		slug: '',
		priority: 0
	}
}
const openTagValueCreate = () => {
	if (!tagEditForm.value.id) {
		notify('Select a tag category first.')
		return
	}
	tagValueForm.value = {
		category_id: tagEditForm.value.id,
		name: '',
		slug: '',
		priority: 0
	}
	tagValueEditForm.value = {
		category_id: '',
		id: '',
		name: '',
		slug: '',
		priority: 0
	}
	tagValueFormMode.value = 'create'
	tagValueFormOpen.value = true
}
const openTagValueEdit = (value) => {
	if (!tagEditForm.value.id || !value) {
		return
	}
	tagValueForm.value = {
		category_id: '',
		name: '',
		slug: '',
		priority: 0
	}
	tagValueEditForm.value = {
		category_id: tagEditForm.value.id,
		id: value.id,
		name: value.name,
		slug: value.slug,
		priority: value.priority || 0
	}
	tagValueFormMode.value = 'edit'
	tagValueFormOpen.value = true
}
const openTagEdit = (category) => {
	tagEditForm.value = {
		id: category?.id || '',
		name: category?.name || '',
		slug: category?.slug || '',
		priority: Number(category?.priority || 0)
	}
	closeTagValueForm()
	tagEditOpen.value = true
}
const saveTag = async() => {
	if (!tagForm.value.name.trim()) {
		notify('Category name is required.')
		return
	}
	const created = await createWorkspaceTag(
		tenantId.value,
		workspaceId,
		{ name: tagForm.value.name, slug: tagForm.value.slug, priority: Number(tagForm.value.priority || 0) }
	)
	tags.value = [...tags.value, created]
	tagForm.value = { name: '', slug: '', priority: 0 }
	tagModal.value = false
	openTagEdit(created)
	notify('Tag category added.')
}
const saveTagValue = async() => {
	if (!tagValueForm.value.category_id) {
		notify('Select a category.')
		return
	}
	if (!tagValueForm.value.name.trim()) {
		notify('Value name is required.')
		return
	}
	const created = await createWorkspaceTagValue(
		tenantId.value,
		workspaceId,
		tagValueForm.value
				.category_id,
		{
			name: tagValueForm.value.name,
			slug: tagValueForm.value.slug || tagValueSlugPlaceholder.value,
			priority: Number(tagValueForm.value.priority || 0)
		}
	)
	tags.value = tags.value
		.map((category) => category.id === tagValueForm.value.category_id
		? { ...category, values: [...(category.values || []), created] }
		: category)
	closeTagValueForm()
	notify('Tag value added.')
}
const confirmDeleteTag = async() => {
	const tag = tagToDelete.value
	if (!tag) {
		return
	}
	if (tag.category_id) {
		await deleteWorkspaceTagValue(tenantId.value, workspaceId, tag.category_id, tag.id)
		tags.value = tags.value
			.map((category) => category.id === tag.category_id
			? { ...category, values: (category.values || []).filter((value) => value.id !== tag.id) }
			: category)
		policiesByServer.value = Object.fromEntries(
			Object.entries(policiesByServer.value)
					.map(([serverId, list]) => [
				serverId,
				list.filter((policy) => policy.tag_id !== tag.id),
			])
		)
		notify('Tag value removed.')
	}
	else {
		await deleteWorkspaceTag(tenantId.value, workspaceId, tag.id)
		tags.value = tags.value.filter((item) => item.id !== tag.id)
		policiesByServer.value = Object.fromEntries(
			Object.entries(policiesByServer.value)
					.map(([serverId, list]) => [
				serverId,
				list.filter((policy) => policy.tag_category_id !== tag.id),
			])
		)
		notify('Tag category removed.')
	}
	tagToDelete.value = null
	deleteTagOpen.value = false
}
const cancelDeleteTag = () => {
	tagToDelete.value = null
}
const setActiveTag = async(categoryId, valueId) => {
	const updated = await updateWorkspaceTagState(tenantId.value, workspaceId, categoryId, { value_id: valueId || null })
	tagStates.value = [
		...tagStates.value.filter((state) => state.category_id !== updated.category_id),
		updated,
	]
	await reloadMcpServers()
}
const handleTagSelect = (categoryId, event) => {
	const value = event.target.value
	setActiveTag(categoryId, value === '__none__' ? null : value)
}
const reloadMcpServers = async() => {
	if (!tenantId.value) {
		return
	}
	try {
		const serversList = await getMcpServers(tenantId.value, workspaceId)
		mcpServers.value = serversList
		policiesByServer.value = serversList.reduce(
			(acc, server) => {
				acc[server.id] = server.policies || []
				return acc
			},
			{}
		)
	}
	catch (error) {
		notify(error?.message || 'Unable to refresh MCP servers.')
	}
}
const openOAuthWindow = (authorizationUrl) => {
	if (!authorizationUrl) {
		return
	}
	const popup = window.open(authorizationUrl, '_blank', 'noopener')
	if (!popup) {
		notify('Popup blocked. Allow popups and try again.')
	}
}
const startServerOAuth = async(server) => {
	if (!server) {
		return
	}
	try {
		const authScopeValueIds = resolveAuthScopeValueIds(server?.auth?.scope_category_ids)
		const payload = authScopeValueIds[0] ? { auth_scope_value_id: authScopeValueIds[0] } : {}
		const response = await startMcpServerOAuth(tenantId.value, workspaceId, server.id, payload)
		openOAuthWindow(response?.authorization_url)
		mcpServers.value = mcpServers.value.map((item) => (item.id === server.id ? { ...item, auth: response } : item))
	}
	catch (error) {
		notify(error?.message || 'Unable to start OAuth flow.')
	}
}
const reauthenticateServerOAuth = async(server) => {
	if (!server) {
		return
	}
	notify('Starting OAuth login again...')
	await startServerOAuth(server)
}
const startServerOAuthForEdit = async() => {
	if (!serverEditId.value) {
		return
	}
	try {
		const authScopeValueIds = resolveAuthScopeValueIds(serverForm.value.auth?.scope_category_ids)
		const payload = authScopeValueIds[0] ? { auth_scope_value_id: authScopeValueIds[0] } : {}
		const response = await startMcpServerOAuth(tenantId.value, workspaceId, serverEditId.value, payload)
		openOAuthWindow(response?.authorization_url)
		serverForm.value.auth = parseServerAuth(response)
	}
	catch (error) {
		notify(error?.message || 'Unable to start OAuth flow.')
	}
}
const reauthenticateServerOAuthForEdit = async() => {
	notify('Starting OAuth login again...')
	await startServerOAuthForEdit()
}
const logoutServerOAuth = async(server) => {
	if (!server) {
		return
	}
	try {
		const authScopeValueIds = resolveAuthScopeValueIds(server?.auth?.scope_category_ids)
		const payload = authScopeValueIds[0] ? { auth_scope_value_id: authScopeValueIds[0] } : {}
		const response = await logoutMcpServerOAuth(tenantId.value, workspaceId, server.id, payload)
		mcpServers.value = mcpServers.value.map((item) => (item.id === server.id ? { ...item, auth: response } : item))
		notify('OAuth login removed.')
	}
	catch (error) {
		notify(error?.message || 'Unable to log out OAuth login.')
	}
}
const logoutServerOAuthForEdit = async() => {
	if (!serverEditId.value) {
		return
	}
	try {
		const authScopeValueIds = resolveAuthScopeValueIds(serverForm.value.auth?.scope_category_ids)
		const payload = authScopeValueIds[0] ? { auth_scope_value_id: authScopeValueIds[0] } : {}
		const response = await logoutMcpServerOAuth(tenantId.value, workspaceId, serverEditId.value, payload)
		serverForm.value.auth = parseServerAuth(response)
		notify('OAuth login removed.')
	}
	catch (error) {
		notify(error?.message || 'Unable to log out OAuth login.')
	}
}
const reuseFallbackToken = async(server) => {
	if (!server) {
		return
	}
	try {
		const authScopeValueIds = resolveAuthScopeValueIds(server?.auth?.scope_category_ids)
		const payload = authScopeValueIds[0] ? { auth_scope_value_id: authScopeValueIds[0] } : {}
		const response = await cloneMcpServerOAuthFallback(tenantId.value, workspaceId, server.id, payload)
		mcpServers.value = mcpServers.value.map((item) => (item.id === server.id ? { ...item, auth: response } : item))
		notify('Fallback token reused for this environment.')
	}
	catch (error) {
		notify(error?.message || 'Unable to reuse fallback token.')
	}
}
const openProviderCreate = () => {
	providerEditId.value = ''
	providerForm.value = {
		issuer: '',
		resource_metadata_url: '',
		well_known_url: '',
		authorization_endpoint: '',
		token_endpoint: '',
		registration_endpoint: '',
		client_id: '',
		client_secret: '',
		client_secret_hint: '',
		scope_category_ids: []
	}
	providerError.value = ''
	providerModalOpen.value = true
}
const openProviderEdit = (provider) => {
	if (!provider) {
		return
	}
	providerEditId.value = provider.id
	providerForm.value = {
		issuer: provider.issuer || '',
		resource_metadata_url: provider.resource_metadata_url || '',
		well_known_url: provider.well_known_url || '',
		authorization_endpoint: provider.authorization_endpoint || '',
		token_endpoint: provider.token_endpoint || '',
		registration_endpoint: provider.registration_endpoint || '',
		client_id: provider.client_id || '',
		client_secret: '',
		client_secret_hint: provider.client_secret_hint || '',
		scope_category_ids: Array.isArray(provider.scope_category_ids) ? provider.scope_category_ids : []
	}
	providerError.value = ''
	providerModalOpen.value = true
}
const buildProviderConfigExport = (provider) => ({
	version: 1,
	provider: {
		issuer: provider?.issuer || '',
		resource_metadata_url: provider?.resource_metadata_url || '',
		well_known_url: provider?.well_known_url || '',
		authorization_endpoint: provider?.authorization_endpoint || '',
		token_endpoint: provider?.token_endpoint || '',
		registration_endpoint: provider?.registration_endpoint || '',
		client_id: provider?.client_id || '',
		client_secret: '',
		scope_category_ids: Array.isArray(provider?.scope_category_ids) ? provider.scope_category_ids : []
	}
})
const exportProviderConfig = async(provider) => {
	if (!provider) {
		return
	}
	await copyText(
		JSON.stringify(buildProviderConfigExport(provider), null, 2),
		'Provider configuration copied to clipboard.',
		'Unable to copy provider configuration.'
	)
}
const openImportProvider = () => {
	providerImportPayload.value = ''
	providerImportError.value = ''
	providerImportOpen.value = true
}
const applyProviderImportPayload = () => {
	providerImportError.value = ''
	let parsed
	try {
		parsed = JSON.parse(providerImportPayload.value)
	}
	catch (error) {
		providerImportError.value = 'Invalid JSON payload.'
		return
	}
	if (!parsed || typeof parsed !== 'object' || !parsed.provider) {
		providerImportError.value = 'Missing provider configuration.'
		return
	}
	const provider = parsed.provider
	providerForm.value = {
		issuer: provider.issuer || '',
		resource_metadata_url: provider.resource_metadata_url || '',
		well_known_url: provider.well_known_url || '',
		authorization_endpoint: provider.authorization_endpoint || '',
		token_endpoint: provider.token_endpoint || '',
		registration_endpoint: provider.registration_endpoint || '',
		client_id: provider.client_id || '',
		client_secret: provider.client_secret || '',
		client_secret_hint: '',
		scope_category_ids: Array.isArray(provider.scope_category_ids) ? provider.scope_category_ids : []
	}
	providerEditId.value = ''
	providerImportOpen.value = false
	providerModalOpen.value = true
}
const saveProvider = async() => {
	if (!providerForm.value.issuer.trim()) {
		providerError.value = 'Issuer is required.'
		return
	}
	providerSaving.value = true
	providerError.value = ''
	try {
		let updated = null
		if (providerEditId.value) {
			updated = await updateMcpOidcProvider(tenantId.value, workspaceId, providerEditId.value, providerForm.value)
			oidcProviders.value = oidcProviders.value.map((item) => (item.id === updated.id ? updated : item))
			notify('Provider updated.')
		}
		else {
			updated = await createMcpOidcProvider(tenantId.value, workspaceId, providerForm.value)
			oidcProviders.value = [...oidcProviders.value, updated]
			notify('Provider created.')
		}
		providerModalOpen.value = false
	}
	catch (error) {
		providerError.value = error?.message || 'Unable to save provider.'
	}
	finally {
		providerSaving.value = false
	}
}
const requestDeleteProvider = (provider) => {
	providerToDelete.value = provider
	deleteProviderOpen.value = true
}
const cancelDeleteProvider = () => {
	providerToDelete.value = null
	deleteProviderOpen.value = false
}
const confirmDeleteProvider = async() => {
	const provider = providerToDelete.value
	if (!provider) {
		return
	}
	try {
		await deleteMcpOidcProvider(tenantId.value, workspaceId, provider.id)
		oidcProviders.value = oidcProviders.value.filter((item) => item.id !== provider.id)
		notify('Provider removed.')
	}
	catch (error) {
		notify(error?.message || 'Unable to remove provider.')
	}
	finally {
		cancelDeleteProvider()
	}
}
const saveTagEdit = async() => {
	if (!tagEditForm.value.id) {
		notify('Select a tag category to edit.')
		return
	}
	if (!tagEditForm.value.name.trim()) {
		notify('Category name is required.')
		return
	}
	const updated = await updateWorkspaceTag(
		tenantId.value,
		workspaceId,
		tagEditForm.value
				.id,
		{ name: tagEditForm.value.name, priority: Number(tagEditForm.value.priority || 0) }
	)
	tags.value = tags.value.map((item) => (item.id === updated.id ? { ...item, ...updated } : item))
	policiesByServer.value = Object.fromEntries(
		Object.entries(policiesByServer.value)
				.map(([serverId, list]) => [
			serverId,
			list.map((policy) => policy.tag_category_id === updated.id ? { ...policy, tag_category_name: updated.name } : policy),
		])
	)
	notify('Tag category updated.')
}
const saveTagValueEdit = async() => {
	if (!tagValueEditForm.value.category_id || !tagValueEditForm.value.id) {
		notify('Select a tag value to edit.')
		return
	}
	if (!tagValueEditForm.value.name.trim()) {
		notify('Value name is required.')
		return
	}
	const updated = await updateWorkspaceTagValue(
		tenantId.value,
		workspaceId,
		tagValueEditForm.value
				.category_id,
		tagValueEditForm.value
				.id,
		{ name: tagValueEditForm.value.name, priority: Number(tagValueEditForm.value.priority || 0) }
	)
	tags.value = tags.value
		.map((category) => category.id === tagValueEditForm.value.category_id
		? {
			...category,
			values: (category.values || []).map((value) => (value.id === updated.id ? { ...value, ...updated } : value))
		}
		: category)
	policiesByServer.value = Object.fromEntries(
		Object.entries(policiesByServer.value)
				.map(([serverId, list]) => [
			serverId,
			list.map((policy) => policy.tag_id === updated.id ? { ...policy, tag_name: updated.name } : policy),
		])
	)
	closeTagValueForm()
	notify('Tag value updated.')
}
const requestDeleteTagFromEdit = () => {
	const tag = tags.value.find((item) => item.id === tagEditForm.value.id)
	if (!tag) {
		return
	}
	tagToDelete.value = tag
	deleteTagOpen.value = true
}
const requestDeleteTagValue = (category, value) => {
	if (!category || !value) {
		return
	}
	tagToDelete.value = { ...value, category_id: category.id }
	deleteTagOpen.value = true
}
const openPolicyCreate = (server) => {
	policyServer.value = server
	policyEditId.value = ''
	policyToDelete.value = null
	policyForm.value = { tag_category_id: '', tag_id: '', policy_value: {} }
}
const openPolicyEdit = (server, policy) => {
	policyServer.value = server
	policyEditId.value = policy.id
	policyToDelete.value = policy
	policyForm.value = {
		tag_category_id: policy.tag_category_id || '',
		tag_id: policy.tag_id,
		policy_value: policy.policy_json || {}
	}
}
const savePolicy = async() => {
	if (!policyServer.value) {
		return
	}
	if (!policySchema.value) {
		notify('No policy schema available for this server.')
		return
	}
	if (!policyForm.value.tag_id) {
		notify('Select a tag value.')
		return
	}
	const payload = { tag_id: policyForm.value.tag_id, policy_json: policyForm.value.policy_value }
	if (policyEditId.value) {
		const updated = await updateMcpServerPolicy(tenantId.value, workspaceId, policyServer.value.id, policyEditId.value, payload)
		policiesByServer.value = {
			...policiesByServer.value,
			[policyServer.value.id]: serverPolicies(policyServer.value.id).map((item) => item.id === updated.id ? updated : item)
		}
		notify('Policy updated.')
	}
	else {
		const created = await createMcpServerPolicy(tenantId.value, workspaceId, policyServer.value.id, payload)
		policiesByServer.value = {
			...policiesByServer.value,
			[policyServer.value.id]: [...serverPolicies(policyServer.value.id), created]
		}
		notify('Policy created.')
	}
	policyServer.value = null
	policyEditId.value = ''
	policyForm.value = { tag_category_id: policyForm.value.tag_category_id, tag_id: '', policy_value: {} }
}
const requestDeletePolicy = (policy) => {
	if (!policy) {
		return
	}
	policyToDelete.value = policy
	deletePolicyOpen.value = true
}
const confirmDeletePolicy = async() => {
	if (!policyServer.value || !policyToDelete.value) {
		return
	}
	await deleteMcpServerPolicy(tenantId.value, workspaceId, policyServer.value.id, policyToDelete.value.id)
	policiesByServer.value = {
		...policiesByServer.value,
		[policyServer.value.id]: serverPolicies(policyServer.value.id).filter((policy) => policy.id !== policyToDelete.value.id)
	}
	policyToDelete.value = null
	deletePolicyOpen.value = false
	notify('Policy removed.')
}
const serverOverrides = (serverId) => overridesByServer.value[serverId] || []
const loadServerOverrides = async(serverId) => {
	if (!serverId) {
		return
	}
	try {
		const overrides = await getMcpServerOverrides(tenantId.value, workspaceId, serverId)
		overridesByServer.value = { ...overridesByServer.value, [serverId]: Array.isArray(overrides) ? overrides : [] }
	}
	catch {
		overridesByServer.value = { ...overridesByServer.value, [serverId]: [] }
	}
}
const resetOverrideForm = () => {
	overrideForm.value = {
		tag_category_id: '',
		tag_id: '',
		logical_name: '',
		http_url: '',
		headers: '',
		ssh_enabled: '',
		ssh_tunnel: {
			server: '',
			port: 22,
			username: '',
			password: '',
			key_path: '',
			key: ''
		},
		auth: {
			global: true,
			issuer: '',
			well_known_url: '',
			resource_metadata_url: '',
			authorization_endpoint: '',
			token_endpoint: '',
			registration_endpoint: '',
			scopes: '',
			supported_scopes: [],
			selected_supported_scopes: [],
			custom_scopes: '',
			client_id: '',
			client_secret: ''
		}
	}
}
const openOverrideCreate = (server) => {
	if (!server?.id) {
		notify('Save the server before adding a variant.')
		return
	}
	overrideServer.value = server
	overrideEditId.value = ''
	overrideToDelete.value = null
	resetOverrideForm()
	overrideForm.value.auth.global = !!serverForm.value.auth?.global
}
const openOverrideEdit = (server, override) => {
	if (!server || !override) {
		return
	}
	overrideServer.value = server
	overrideEditId.value = override.id
	overrideToDelete.value = override
	overrideForm.value = {
		tag_category_id: override.tag_category_id || '',
		tag_id: override.tag_id || '',
		logical_name: override.logical_name || '',
		http_url: override.http_url || '',
		headers: override.headers || '',
		ssh_enabled: override.ssh_enabled === true ? 'true' : override.ssh_enabled === false ? 'false' : '',
		ssh_tunnel: {
			server: override.ssh_tunnel?.server || '',
			port: override.ssh_tunnel?.port || 22,
			username: override.ssh_tunnel?.username || '',
			password: '',
			key_path: override.ssh_tunnel?.key_path || '',
			key: ''
		},
		auth: parseServerAuth(override.auth)
	}
}
const buildOverrideAuthPayload = (auth, baseGlobal) => {
	if (!auth) {
		return null
	}
	const payload = {
		global: !!auth.global,
		issuer: auth.issuer?.trim() || '',
		well_known_url: auth.well_known_url?.trim() || '',
		resource_metadata_url: auth.resource_metadata_url?.trim() || '',
		authorization_endpoint: auth.authorization_endpoint?.trim() || '',
		token_endpoint: auth.token_endpoint?.trim() || '',
		registration_endpoint: auth.registration_endpoint?.trim() || '',
		scopes: auth.scopes?.trim() || '',
		client_id: auth.client_id?.trim() || ''
	}
	if (auth.client_secret && auth.client_secret.trim()) {
		payload.client_secret = auth.client_secret.trim()
	}
	const entries = { ...payload }
	delete entries.global
	const hasAny = Object.values(entries).some((value) => value && `${value}`.trim() !== '')
	const globalChanged = typeof baseGlobal === 'boolean' && payload.global !== baseGlobal
	return hasAny || payload.client_secret || globalChanged ? payload : null
}
const buildOverrideSshTunnelPayload = () => {
	const ssh = overrideForm.value.ssh_tunnel || {}
	const hasAnyString = [ssh.server, ssh.username, ssh.password, ssh.key_path, ssh.key].some((value) => typeof value === 'string' && value.trim())
	const port = Number(ssh.port)
	if (!hasAnyString && !Number.isFinite(port)) {
		return null
	}
	return {
		server: ssh.server?.trim() || '',
		port: Number.isFinite(port) && port > 0 ? port : 22,
		username: ssh.username?.trim() || '',
		password: ssh.password || '',
		key_path: ssh.key_path?.trim() || '',
		key: ssh.key || ''
	}
}
const saveOverride = async() => {
	if (!overrideServer.value) {
		return
	}
	if (!overrideForm.value.tag_id) {
		notify('Select a tag value.')
		return
	}
	if (!overrideForm.value.logical_name.trim()) {
		notify('Logical name is required.')
		return
	}
	const sshEnabledValue = overrideForm.value.ssh_enabled === 'true'
		? true
		: overrideForm.value.ssh_enabled === 'false' ? false : null
	const payload = {
		tag_id: overrideForm.value.tag_id,
		logical_name: overrideForm.value.logical_name.trim(),
		http_url: overrideForm.value.http_url?.trim() || null,
		headers: overrideForm.value.headers && overrideForm.value.headers.trim() ? overrideForm.value.headers : null,
		ssh_enabled: sshEnabledValue,
		ssh_tunnel: buildOverrideSshTunnelPayload(),
		auth: overrideServer.value.oauth_enabled
			? buildOverrideAuthPayload(overrideForm.value.auth, serverForm.value.auth?.global)
			: null
	}
	let updatedList = []
	if (overrideEditId.value) {
		const updated = await updateMcpServerOverride(tenantId.value, workspaceId, overrideServer.value
				.id, overrideEditId.value, payload)
		updatedList = serverOverrides(overrideServer.value.id).map((item) => item.id === updated.id ? updated : item)
		notify('Variant updated.')
	}
	else {
		const created = await createMcpServerOverride(tenantId.value, workspaceId, overrideServer.value.id, payload)
		updatedList = [...serverOverrides(overrideServer.value.id), created]
		notify('Variant created.')
	}
	overridesByServer.value = { ...overridesByServer.value, [overrideServer.value.id]: updatedList }
	overrideServer.value = null
	overrideEditId.value = ''
	overrideToDelete.value = null
	resetOverrideForm()
}
const requestDeleteOverride = (override) => {
	if (!override) {
		return
	}
	overrideToDelete.value = { ...override, server_id: overrideServer.value?.id || activeServerForOverrides.value?.id || '' }
	deleteOverrideOpen.value = true
}
const confirmDeleteOverride = async() => {
	const serverId = overrideToDelete.value?.server_id || overrideServer.value?.id || ''
	if (!serverId || !overrideToDelete.value) {
		return
	}
	await deleteMcpServerOverride(tenantId.value, workspaceId, serverId, overrideToDelete.value.id)
	overridesByServer.value = {
		...overridesByServer.value,
		[serverId]: serverOverrides(serverId).filter((item) => item.id !== overrideToDelete.value.id)
	}
	overrideToDelete.value = null
	deleteOverrideOpen.value = false
	notify('Variant removed.')
}
const cancelDeleteOverride = () => {
	overrideToDelete.value = null
	deleteOverrideOpen.value = false
}
const channelTagCategories = computed(() => sortedTagCategories.value
	.map((category) => ({ ...category, values: sortedTagValues((category.values || []).filter((value) => !value.deleted)) }))
	.filter((category) => category.values.length))
const updateChannelTag = (categoryId, tagId) => {
	const next = []
	const current = new Set(channelForm.value.tag_ids || []);
	(channelTagCategories.value || []).forEach((category) => {
			if (category.id === categoryId) {
				if (tagId) {
					next.push(tagId)
				}
				return
			}
			const selected = (category.values || []).find((value) => current.has(value.id))
			if (selected) {
				next.push(selected.id)
			}
		})
	channelForm.value.tag_ids = next
}
const channelTagValue = (categoryId) => {
	const current = new Set(channelForm.value.tag_ids || [])
	const category = (channelTagCategories.value || []).find((item) => item.id === categoryId)
	const selected = (category?.values || []).find((value) => current.has(value.id))
	return selected?.id || ''
}
const updateChannelServer = (serverId, visibility) => {
	if (!serverId) {
		return
	}
	const list = Array.isArray(channelForm.value.mcp_servers) ? [...channelForm.value.mcp_servers] : []
	const index = list.findIndex((item) => item.mcp_server_id === serverId)
	const current = { mcp_server_id: serverId, visibility: visibility || 'HIDDEN' }
	if (index >= 0) {
		list[index] = current
	}
	else {
		list.push(current)
	}
	channelForm.value.mcp_servers = list
}
const channelServerValue = (serverId) => {
	const item = (channelForm.value.mcp_servers || []).find((entry) => entry.mcp_server_id === serverId)
	return item?.visibility || 'HIDDEN'
}
const openChannelCreate = () => {
	channelEditId.value = ''
	channelError.value = ''
	channelForm.value = {
		name: '',
		description: '',
		assistant_id: '',
		prompt: '',
		allow_scopes: [],
		deny_scopes: [],
		tag_ids: [],
		mcp_servers: mcpServers.value.map((server) => ({ mcp_server_id: server.id, visibility: 'HIDDEN' }))
	}
	channelModalOpen.value = true
}
const openChannelEdit = (channel) => {
	if (!channel) {
		return
	}
	channelEditId.value = channel.id
	channelError.value = ''
	channelForm.value = {
		name: channel.name || '',
		description: channel.description || '',
		assistant_id: channel.assistant_id || '',
		prompt: channel.prompt || '',
		allow_scopes: Array.isArray(channel.allow_scopes) ? [...channel.allow_scopes] : [],
		deny_scopes: Array.isArray(channel.deny_scopes) ? [...channel.deny_scopes] : [],
		tag_ids: Array.isArray(channel.tag_ids) ? [...channel.tag_ids] : [],
		mcp_servers: mcpServers.value
			.map((server) => {
				const item = (channel.mcp_servers || []).find((entry) => entry.mcp_server_id === server.id)
				return { mcp_server_id: server.id, visibility: item?.visibility || 'HIDDEN' }
			})
	}
	channelModalOpen.value = true
}
const closeChannelModal = () => {
	channelModalOpen.value = false
	channelSaving.value = false
	channelError.value = ''
	channelEditId.value = ''
}
const updateChannelScopes = ({ allow, deny }) => {
	channelForm.value.allow_scopes = Array.isArray(allow) ? [...allow] : []
	channelForm.value.deny_scopes = Array.isArray(deny) ? [...deny] : []
}
const saveChannel = async() => {
	if (!channelForm.value.name.trim()) {
		channelError.value = 'Channel name is required.'
		return
	}
	channelSaving.value = true
	channelError.value = ''
	const payload = {
		name: channelForm.value.name.trim(),
		description: channelForm.value.description || '',
		assistant_id: channelForm.value.assistant_id || null,
		prompt: channelForm.value.prompt || '',
		allow_scopes: channelForm.value.allow_scopes || [],
		deny_scopes: channelForm.value.deny_scopes || [],
		tag_ids: channelForm.value.tag_ids || [],
		mcp_servers: (channelForm.value.mcp_servers || []).map((item) => ({ mcp_server_id: item.mcp_server_id, visibility: item.visibility || 'HIDDEN' }))
	}
	try {
		let saved
		if (channelEditId.value) {
			saved = await updateChannel(tenantId.value, workspaceId, channelEditId.value, payload)
			channels.value = channels.value.map((item) => (item.id === saved.id ? saved : item))
			notify('Channel updated.')
		}
		else {
			saved = await createChannel(tenantId.value, workspaceId, payload)
			channels.value = [...channels.value, saved]
			notify('Channel created.')
		}
		closeChannelModal()
	}
	catch (error) {
		channelError.value = error?.message || 'Unable to save channel.'
	}
	finally {
		channelSaving.value = false
	}
}
const requestDeleteChannel = (channel) => {
	channelToDelete.value = channel
	deleteChannelOpen.value = true
}
const confirmDeleteChannel = async() => {
	if (!channelToDelete.value) {
		return
	}
	try {
		await deleteChannel(tenantId.value, workspaceId, channelToDelete.value.id)
		channels.value = channels.value.filter((item) => item.id !== channelToDelete.value.id)
		notify('Channel deleted.')
	}
	catch (error) {
		notify(error?.message || 'Unable to delete channel.')
	}
	finally {
		channelToDelete.value = null
		deleteChannelOpen.value = false
	}
}
const cancelDeletePolicy = () => {
	policyToDelete.value = null
}
const normalizeScope = (value) => (value || 'any').toLowerCase()
const normalizeAudience = (value) => (value || 'any').toLowerCase()
const filterSchemaForScope = (schema, scope) => {
	if (!schema || typeof schema !== 'object') {
		return schema
	}
	if (Array.isArray(schema)) {
		return schema.map((entry) => filterSchemaForScope(entry, scope)).filter((entry) => entry != null)
	}
	const schemaScope = normalizeScope(schema.scope)
	if (scope === 'configuration' && schemaScope === 'policy') {
		return null
	}
	if (scope === 'policy' && schemaScope === 'configuration') {
		return null
	}
	if (scope === 'policy') {
		const audience = normalizeAudience(schema.audience)
		if (audience === 'llm') {
			return null
		}
	}
	const result = { ...schema }
	if (result.scope) {
		delete result.scope
	}
	if (result.audience) {
		delete result.audience
	}
	if (result.properties && typeof result.properties === 'object') {
		const filtered = {}
		for (const [key, value] of Object.entries(result.properties)) {
			const next = filterSchemaForScope(value, scope)
			if (next != null) {
				filtered[key] = next
			}
		}
		result.properties = filtered
	}
	if (result.items) {
		const items = filterSchemaForScope(result.items, scope)
		if (items != null) {
			result.items = items
		}
	}
	if (Array.isArray(result.oneOf)) {
		const filtered = result.oneOf.map((entry) => filterSchemaForScope(entry, scope)).filter(Boolean)
		if (filtered.length) {
			result.oneOf = filtered
		}
		else {
			delete result.oneOf
		}
	}
	if (Array.isArray(result.anyOf)) {
		const filtered = result.anyOf.map((entry) => filterSchemaForScope(entry, scope)).filter(Boolean)
		if (filtered.length) {
			result.anyOf = filtered
		}
		else {
			delete result.anyOf
		}
	}
	if (Array.isArray(result.allOf)) {
		const filtered = result.allOf.map((entry) => filterSchemaForScope(entry, scope)).filter(Boolean)
		if (filtered.length) {
			result.allOf = filtered
		}
		else {
			delete result.allOf
		}
	}
	return result
}
const policySchema = computed(() => {
	if (!policyServer.value || !policyServer.value.allow_policy || !policyServer.value.config_schema) {
		return null
	}
	return filterSchemaForScope(policyServer.value.config_schema, 'policy')
})
const largeEditorSchema = computed(() => {
	if (largeEditorMode.value === 'policy') {
		return policySchema.value
	}
	if (largeEditorMode.value === 'config') {
		return configurationSchema.value
	}
	return null
})
const largeEditorValue = computed({
	get() {
		if (largeEditorMode.value === 'policy') {
			return policyForm.value.policy_value
		}
		if (largeEditorMode.value === 'config') {
			return serverForm.value.configuration_value
		}
		return {}
	},
	set(next) {
		if (largeEditorMode.value === 'policy') {
			policyForm.value.policy_value = next
		}
		if (largeEditorMode.value === 'config') {
			serverForm.value.configuration_value = next
		}
	}
})
const openLargeEditor = (mode) => {
	largeEditorMode.value = mode
	largeEditorOpen.value = true
}
const deleteRuleMessage = computed(() => {
	if (ruleToDelete.value) {
		return `Delete rule "${ruleToDelete.value.name}"? This cannot be undone.`
	}
	return 'Delete this rule? This cannot be undone.'
})
const deleteServerMessage = computed(() => {
	if (serverToDelete.value) {
		return `Delete MCP server "${serverToDelete.value.name}"? This cannot be undone.`
	}
	return 'Delete this MCP server? This cannot be undone.'
})
const deleteTagMessage = computed(() => {
	if (!tagToDelete.value) {
		return 'Delete this tag? This may impact policies and historical references.'
	}
	if (tagToDelete.value.category_id) {
		return `Delete tag value "${tagToDelete.value.name}"? This may impact policies and historical references.`
	}
	return `Delete tag category "${tagToDelete.value.name}"? This may impact policies and historical references.`
})
const deleteProviderMessage = computed(() => {
	if (providerToDelete.value) {
		return `Delete OpenID provider "${providerToDelete.value.issuer}"? This cannot be undone.`
	}
	return 'Delete this OpenID provider? This cannot be undone.'
})
const deletePolicyMessage = computed(() => {
	if (policyToDelete.value) {
		return `Delete policy for ${policyToDelete.value.tag_category_slug || 'tag'}:${policyToDelete.value.tag_slug || policyToDelete.value.tag_name}?`
	}
	return 'Delete this policy?'
})
const deleteOverrideMessage = computed(() => {
	if (overrideToDelete.value) {
		const tagLabel = overrideToDelete.value.tag_name || 'tag'
		const nameLabel = overrideToDelete.value.logical_name || 'variant'
		return `Delete variant "${nameLabel}" for ${tagLabel}?`
	}
	return 'Delete this variant?'
})
const deleteWorkflowMessage = computed(() => {
	if (workflowToDelete.value) {
		return `Delete workflow "${workflowToDelete.value.name}"? This cannot be undone.`
	}
	return 'Delete this workflow? This cannot be undone.'
})
const deletePageMessage = computed(() => {
	if (pageToDelete.value) {
		return `Delete page "${pageToDelete.value.name}"? This will remove it from everyone’s workspace menu.`
	}
	return 'Delete this page? This will remove it from everyone’s workspace menu.'
})
const deleteScriptMessage = computed(() => {
	if (scriptToDelete.value) {
		return `Delete script "${scriptToDelete.value.name}"? This cannot be undone.`
	}
	return 'Delete this script? This cannot be undone.'
})
const workspaceTemplateBySection = (section) => promptTemplates.value.find((template) => template.section === section && template.workspace_id)
const tenantTemplateBySection = (section) => promptTemplates.value
	.find((template) => template.section === section && template.tenant_id && !template.workspace_id)
const standardTemplateBySection = (section) => promptTemplates.value.find((template) => template.section === section && template.standard)
const promptPlaceholder = (section) => {
	const tenantTemplate = tenantTemplateBySection(section)
	if (tenantTemplate && tenantTemplate.enabled) {
		return tenantTemplate.content || ''
	}
	const standard = standardTemplateBySection(section)
	return standard?.content || ''
}
const openPromptTemplateEditor = (section) => {
	const existing = workspaceTemplateBySection(section)
	promptTemplateForm.value = { section, content: existing?.content || '', enabled: existing ? !!existing.enabled : true }
	promptTemplateFallback.value = promptPlaceholder(section)
	promptTemplateOpen.value = true
}
const applyPromptTemplateFallback = () => {
	promptTemplateForm.value = { ...promptTemplateForm.value, content: promptTemplateFallback.value || '' }
}
const savePromptTemplate = async() => {
	if (!tenantId.value) {
		return
	}
	const payload = {
		section: promptTemplateForm.value.section,
		content: promptTemplateForm.value.content || '',
		enabled: !!promptTemplateForm.value.enabled
	}
	promptTemplateSaving.value = true
	try {
		const updated = await updateWorkspacePromptTemplate(tenantId.value, workspaceId, payload)
		const next = promptTemplates.value
			.filter((template) => !(template.section === updated.section && template.workspace_id))
		promptTemplates.value = [...next, updated]
		notify('Prompt template updated.')
		promptTemplateOpen.value = false
	}
	catch (error) {
		notify(error?.message || 'Unable to update prompt template.')
	}
	finally {
		promptTemplateSaving.value = false
	}
}
watch(
	() => policyForm.value.tag_category_id,
	(next, previous) => {
		if (previous && next !== previous) {
			policyForm.value.tag_id = ''
		}
	}
)
watch(
	() => overrideForm.value.tag_category_id,
	(next, previous) => {
		if (previous && next !== previous) {
			overrideForm.value.tag_id = ''
		}
	}
)
watch(
	() => tagEditForm.value.id,
	(next) => {
		const tag = tags.value.find((item) => item.id === next)
		if (!tag) {
			closeTagValueForm()
			return
		}
		tagEditForm.value.name = tag.name || ''
		tagEditForm.value.slug = tag.slug || ''
		tagEditForm.value.priority = Number(tag.priority || 0)
		closeTagValueForm()
	}
)
onMounted(() => {
	loadWorkspace()
	if (tenantId.value) {
		if (unregisterSocketHandler) {
			unregisterSocketHandler()
		}
		unregisterSocketHandler = workspaceSocketState.registerHandler(handleWorkspaceMessage)
	}
})
onBeforeUnmount(() => {
	if (unregisterSocketHandler) {
		unregisterSocketHandler()
		unregisterSocketHandler = null
	}
})
</script>
<template>
	<main class="detail-view">
		<header class="section-header">
			<div>
				<p class="eyebrow">Workspace</p>
				<h1 class="workspace-title">
					<span>{{ workspace?.name || 'Workspace' }}</span>
					<button
						class="icon-button tooltip workspace-edit"
						type="button"
						aria-label="Edit workspace name"
						data-tip="Edit name"
						@click="openWorkspaceNameEditor">
						<svg
							viewBox="0 0 24 24"
							aria-hidden="true"
							focusable="false">
							<path
								fill="currentColor"
								d="M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zm2.92 2.33H5v-.92l8.06-8.06.92.92L5.92 19.58zM20.71 7.04a1.003 1.003 0 0 0 0-1.42l-2.34-2.34a1.003 1.003 0 0 0-1.42 0l-1.83 1.83 3.75 3.75 1.84-1.82z"/>
						</svg>
					</button>
				</h1>
				<WorkspaceNotificationToggle v-if="workspaceId" :workspace-id="workspaceId"/>
			</div>
			<div class="tag-controls">
				<select
					v-for="category in sortedTagCategories"
					:key="category.id"
					class="compact-select"
					:value="activeTagByCategory[category.id] || '__none__'"
					@change="handleTagSelect(category.id, $event)">
					<option value="" disabled>{{ category.name }}</option>
					<option value="__none__">None</option>
					<option
						v-for="tagValue in (category.values || []).filter((value) => !value.deleted)"
						:key="tagValue.id"
						:value="tagValue.id">{{ tagValue.name }}</option>
				</select>
				<button
					class="control size-xs icon-button tooltip"
					type="button"
					aria-label="Add tag"
					data-tip="Add tag category"
					@click="openTagCreate">
					<svg
						viewBox="0 0 24 24"
						aria-hidden="true"
						focusable="false">
						<path fill="currentColor" d="M11 5h2v14h-2zM5 11h14v2H5z"/>
					</svg>
				</button>
				<button
					class="control size-xs icon-button tooltip"
					type="button"
					aria-label="Edit tags"
					data-tip="Edit tag categories"
					@click="openTagEdit(sortedTagCategories[0])">
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
		</header>
		<section class="management-grid">
			<article class="panel compact">
				<div class="section-head">
					<h2>Pages</h2>
					<div class="row-actions">
						<button
							class="control size-xs secondary"
							type="button"
							@click="openManageImports">Manage imports</button>
						<button
							class="control size-xs icon-button tooltip"
							type="button"
							aria-label="Create page"
							data-tip="Create page"
							@click="openPageCreate">
							<svg
								viewBox="0 0 24 24"
								aria-hidden="true"
								focusable="false">
								<path fill="currentColor" d="M11 5h2v14h-2zM5 11h14v2H5z"/>
							</svg>
						</button>
						<button
							class="control size-xs icon-button tooltip"
							type="button"
							aria-label="Import page"
							data-tip="Import page"
							@click="openPageImport">
							<svg
								viewBox="0 0 24 24"
								aria-hidden="true"
								focusable="false">
								<path
									fill="currentColor"
									d="M12 3v10.17l3.59-3.58L17 11l-5 5-5-5 1.41-1.41L11 13.17V3h1zm-7 16h14v2H5v-2z"/>
							</svg>
						</button>
					</div>
				</div>
				<p class="subtle">Install pages to add them to your menu. Components are available for reuse.</p>
				<p v-if="pagesLoading" class="subtle">Loading pages…</p>
				<p v-else-if="pagesError" class="form-error">{{ pagesError }}</p>
				<div v-else-if="pageTreeRows.length > 0" class="script-explorer">
					<div
						v-for="row in pageTreeRows"
						:key="row.key"
						class="script-explorer-row"
						:class="`script-explorer-row-${row.kind}`"
						:style="{ '--depth': row.depth }">
						<template v-if="row.kind === 'folder'">
							<button
								class="script-explorer-toggle"
								type="button"
								:aria-label="row.open ? `Collapse ${row.name}` : `Expand ${row.name}`"
								@click.stop="togglePageFolder(row.path)">
								<svg
									viewBox="0 0 24 24"
									aria-hidden="true"
									focusable="false"
									:class="{ open: row.open }">
									<path fill="currentColor" d="M8.59 16.59 13.17 12 8.59 7.41 10 6l6 6-6 6z"/>
								</svg>
							</button>
							<button
								class="script-explorer-main"
								type="button"
								@dblclick.stop="togglePageFolder(row.path)">
								<svg
									class="script-explorer-icon"
									viewBox="0 0 24 24"
									aria-hidden="true"
									focusable="false">
									<path fill="currentColor" d="M10 4 12 6h8v12H4V4h6zm0 2H6v10h12V8h-7.17L9.59 6H10z"/>
								</svg>
								<span class="script-explorer-name">{{ row.name }}</span>
								<span class="script-explorer-count">{{ row.count }}</span>
							</button>
						</template>
						<template v-else>
							<div class="script-explorer-main script-explorer-file-main">
								<svg
									class="script-explorer-icon"
									viewBox="0 0 24 24"
									aria-hidden="true"
									focusable="false">
									<path fill="currentColor" d="M7 3h7l5 5v13H7V3zm2 2v14h8V9h-4V5H9zm6 1.41V8h1.59L15 6.41z"/>
								</svg>
								<span class="script-explorer-name">{{ row.item.name }}</span>
								<span v-if="row.item.draft_present" class="script-explorer-badge">Draft</span>
							</div>
							<div class="script-explorer-actions">
								<button
									class="control size-xs icon-button tooltip"
									type="button"
									:disabled="!canInstallPage(row.item)"
									:aria-label="pageActionTooltip(row.item)"
									:data-tip="pageActionTooltip(row.item)"
									@click.stop="togglePageInstall(row.item)">
									<svg
										viewBox="0 0 24 24"
										aria-hidden="true"
										focusable="false">
										<path
											v-if="isPageInstalled(row.item.id)"
											fill="currentColor"
											d="M6 19h12v2H6zm6-16 5 5h-3v6h-4V8H7l5-5z"/>
										<path
											v-else
											fill="currentColor"
											d="M6 19h12v2H6zm6-12v6h3l-5 5-5-5h3V7h4z"/>
									</svg>
								</button>
								<router-link
									class="control size-xs icon-button tooltip"
									aria-label="Edit page"
									data-tip="Edit page"
									:to="{ name: 'workspace-page-editor', params: { workspaceId, pageId: row.item.id } }">
									<svg
										viewBox="0 0 24 24"
										aria-hidden="true"
										focusable="false">
										<path
											fill="currentColor"
											d="M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zm2.92 2.33H5v-.92l8.06-8.06.92.92L5.92 19.58zM20.71 7.04a1.003 1.003 0 0 0 0-1.42l-2.34-2.34a1.003 1.003 0 0 0-1.42 0l-1.83 1.83 3.75 3.75 1.84-1.82z"/>
									</svg>
								</router-link>
								<button
									class="control size-xs icon-button tooltip"
									type="button"
									aria-label="Copy page"
									data-tip="Copy page"
									@click.stop="requestPageCopy(row.item)">
									<svg
										viewBox="0 0 24 24"
										aria-hidden="true"
										focusable="false">
										<path fill="currentColor" d="M16 1H4v14h2V3h10V1zm4 4H8v18h12V5zm-2 16H10V7h8v14z"/>
									</svg>
								</button>
								<button
									class="control size-xs icon-button tooltip danger"
									type="button"
									aria-label="Delete page"
									data-tip="Delete page"
									@click.stop="requestPageDelete(row.item)">
									<svg
										viewBox="0 0 24 24"
										aria-hidden="true"
										focusable="false">
										<path fill="currentColor" d="M6 7h12v2H6V7zm2 3h2v8H8v-8zm6 0h2v8h-2v-8z"/>
										<path fill="currentColor" d="M9 4h6l1 1h4v2H4V5h4l1-1z"/>
									</svg>
								</button>
							</div>
						</template>
					</div>
				</div>
				<p v-else class="empty">No pages yet.</p>
			</article>
			<article class="panel compact">
				<div class="section-head">
					<h2>Scripts</h2>
					<div class="row-actions">
						<button
							class="control size-xs icon-button tooltip"
							type="button"
							aria-label="Add root folder"
							data-tip="Add root folder"
							@click="openScriptFolderCreate('')">
							<svg
								viewBox="0 0 24 24"
								aria-hidden="true"
								focusable="false">
								<path fill="currentColor" d="M10 4 12 6h8v12H4V4h6zm1 6V7H9V5H7v2H5v2h2v2h2V9h2z"/>
							</svg>
						</button>
						<button
							class="control size-xs icon-button tooltip"
							type="button"
							aria-label="Add script"
							data-tip="Add script"
							@click="openScriptCreate()">
							<svg
								viewBox="0 0 24 24"
								aria-hidden="true"
								focusable="false">
								<path fill="currentColor" d="M11 5h2v14h-2zM5 11h14v2H5z"/>
							</svg>
						</button>
					</div>
				</div>
				<p class="subtle">Groovy scripts with versioned releases and MCP tool access.</p>
				<div class="tree-search">
					<input
						v-model="scriptSearchQuery"
						class="control size-s"
						type="search"
						placeholder="Search scripts"/>
					<button
						v-if="scriptSearchActive"
						class="control size-xs ghost"
						type="button"
						@click="scriptSearchQuery = ''">Clear</button>
				</div>
				<p v-if="scriptSearchActive" class="subtle">
					Showing {{ filteredScripts.length }} of {{ visibleScripts.length }} scripts
				</p>
				<p v-if="scriptsLoading" class="subtle">Loading scripts…</p>
				<p v-else-if="scriptsError" class="form-error">{{ scriptsError }}</p>
				<div v-else-if="scriptTreeRows.length > 0" class="script-explorer">
					<div
						v-for="row in scriptTreeRows"
						:key="row.key"
						class="script-explorer-row"
						:class="[
              `script-explorer-row-${row.kind}`,
              {
                'script-explorer-row-match': row.searchMatch && scriptSearchActive,
                'script-explorer-row-selected': row.kind === 'folder' && selectedScriptFolderPath === row.path,
              },
            ]"
						:style="{ '--depth': row.depth }">
						<template v-if="row.kind === 'folder'">
							<button
								class="script-explorer-toggle"
								type="button"
								:aria-label="row.open ? `Collapse ${row.name}` : `Expand ${row.name}`"
								@click.stop="toggleScriptFolder(row.path)">
								<svg
									viewBox="0 0 24 24"
									aria-hidden="true"
									focusable="false"
									:class="{ open: row.open }">
									<path fill="currentColor" d="M8.59 16.59 13.17 12 8.59 7.41 10 6l6 6-6 6z"/>
								</svg>
							</button>
							<button
								class="script-explorer-main"
								type="button"
								@click.stop="selectScriptFolder(row.path)"
								@dblclick.stop="toggleScriptFolder(row.path)">
								<svg
									class="script-explorer-icon"
									viewBox="0 0 24 24"
									aria-hidden="true"
									focusable="false">
									<path fill="currentColor" d="M10 4 12 6h8v12H4V4h6zm0 2H6v10h12V8h-7.17L9.59 6H10z"/>
								</svg>
								<span class="script-explorer-name">{{ row.name }}</span>
								<span class="script-explorer-count">{{ scriptSearchActive ? row.matchCount : row.count }}</span>
							</button>
							<div class="script-explorer-actions">
								<button
									class="control size-xs icon-button tooltip"
									type="button"
									aria-label="Add folder"
									data-tip="Add folder"
									@click.stop="openScriptFolderCreate(row.path)">
									<svg
										viewBox="0 0 24 24"
										aria-hidden="true"
										focusable="false">
										<path fill="currentColor" d="M10 4 12 6h8v12H4V4h6zm1 6V7H9V5H7v2H5v2h2v2h2V9h2z"/>
									</svg>
								</button>
								<button
									class="control size-xs icon-button tooltip"
									type="button"
									aria-label="Add script"
									data-tip="Add script"
									@click.stop="openScriptCreate(row.path)">
									<svg
										viewBox="0 0 24 24"
										aria-hidden="true"
										focusable="false">
										<path fill="currentColor" d="M11 5h2v14h-2zM5 11h14v2H5z"/>
									</svg>
								</button>
							</div>
						</template>
						<template v-else>
							<div class="script-explorer-main script-explorer-file-main">
								<svg
									class="script-explorer-icon"
									viewBox="0 0 24 24"
									aria-hidden="true"
									focusable="false">
									<path fill="currentColor" d="M7 3h7l5 5v13H7V3zm2 2v14h8V9h-4V5H9zm6 1.41V8h1.59L15 6.41z"/>
								</svg>
								<span class="script-explorer-name">{{ row.item.name }}</span>
								<span v-if="row.item.draft_present" class="script-explorer-badge">Draft</span>
								<span v-else-if="!row.item.released" class="script-explorer-badge">Unreleased</span>
							</div>
							<div class="script-explorer-actions">
								<button
									class="control size-xs icon-button tooltip"
									type="button"
									aria-label="Edit script metadata"
									data-tip="Edit metadata"
									@click="openScriptEdit(row.item)">
									<svg
										viewBox="0 0 24 24"
										aria-hidden="true"
										focusable="false">
										<path
											fill="currentColor"
											d="M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zm2.92 2.33H5v-.92l8.06-8.06.92.92L5.92 19.58zM20.71 7.04a1.003 1.003 0 0 0 0-1.42l-2.34-2.34a1.003 1.003 0 0 0-1.42 0l-1.83 1.83 3.75 3.75 1.84-1.82z"/>
									</svg>
								</button>
								<router-link
									class="control size-xs icon-button tooltip"
									aria-label="Open script draft"
									data-tip="Open draft"
									:to="{ name: 'workspace-script-editor', params: { workspaceId, scriptId: row.item.id } }">
									<svg
										viewBox="0 0 24 24"
										aria-hidden="true"
										focusable="false">
										<path fill="currentColor" d="M14 3h7v7h-2V6.41l-9.29 9.3-1.42-1.42 9.3-9.29H14V3z"/>
										<path fill="currentColor" d="M5 5h6v2H7v10h10v-4h2v6H5V5z"/>
									</svg>
								</router-link>
								<button
									class="control size-xs icon-button tooltip danger"
									type="button"
									aria-label="Delete script"
									data-tip="Delete"
									@click="requestScriptDelete(row.item)">
									<svg
										viewBox="0 0 24 24"
										aria-hidden="true"
										focusable="false">
										<path fill="currentColor" d="M6 7h12v2H6V7zm2 3h2v8H8v-8zm6 0h2v8h-2v-8z"/>
										<path fill="currentColor" d="M9 4h6l1 1h4v2H4V5h4l1-1z"/>
									</svg>
								</button>
							</div>
						</template>
					</div>
				</div>
				<p v-else class="empty">{{ scriptSearchActive ? 'No scripts match this search.' : 'No scripts yet.' }}</p>
			</article>
			<article class="panel compact">
				<div class="section-head">
					<h2>MCP servers</h2>
					<div class="row-actions">
						<button
							class="control size-xs icon-button tooltip"
							type="button"
							aria-label="Create MCP server"
							data-tip="Create MCP server"
							@click="openServerCreate">
							<svg
								viewBox="0 0 24 24"
								aria-hidden="true"
								focusable="false">
								<path fill="currentColor" d="M11 5h2v14h-2zM5 11h14v2H5z"/>
							</svg>
						</button>
						<button
							class="control size-xs icon-button tooltip"
							type="button"
							aria-label="Import MCP server"
							data-tip="Import MCP server"
							@click="openImportServer">
							<svg
								viewBox="0 0 24 24"
								aria-hidden="true"
								focusable="false">
								<path
									fill="currentColor"
									d="M12 3v10.17l3.59-3.58L17 11l-5 5-5-5 1.41-1.41L11 13.17V3h1zm-7 16h14v2H5v-2z"/>
							</svg>
						</button>
					</div>
				</div>
				<div class="tree-search mcp-server-toolbar">
					<input
						v-model="mcpServerSearchQuery"
						class="control search-input"
						type="search"
						placeholder="Search servers"/>
					<span class="subtle mcp-server-count">{{ mcpServerRangeLabel }}</span>
					<div v-if="mcpServerPageCount > 1" class="row-actions mcp-server-pager">
						<button
							class="control size-xs ghost"
							type="button"
							:disabled="mcpServerPage <= 1"
							@click="setMcpServerPage(mcpServerPage - 1)">Prev</button>
						<span class="subtle">{{ mcpServerPage }}/{{ mcpServerPageCount }}</span>
						<button
							class="control size-xs ghost"
							type="button"
							:disabled="mcpServerPage >= mcpServerPageCount"
							@click="setMcpServerPage(mcpServerPage + 1)">Next</button>
					</div>
				</div>
				<div class="tenant-list">
					<div
						v-for="server in pagedMcpServers"
						:key="server.id"
						class="list-row">
						<div>
							<strong>{{ server.name }}</strong>
							<div class="tag-row">
								<span class="control size-xs pill">{{ serverProtocolLabel(server.protocol) }}</span>
								<span
									v-if="server.visibility === 'AVAILABLE'"
									class="control size-xs pill warning tooltip"
									data-tip="Not included by default in new sessions.">Available</span>
								<span
									v-else-if="server.visibility === 'HIDDEN'"
									class="control size-xs pill danger tooltip"
									data-tip="Hidden from assistant auto-discovery.">Hidden</span>
								<span
									v-if="authStatusLabel(server)"
									class="control size-xs pill"
									:class="authStatusClass(server)">{{ authStatusLabel(server) }}</span>
								<button
									v-for="policy in serverPolicies(server.id)"
									:key="policy.id"
									class="control size-xs pill policy"
									type="button"
									@click="openPolicyEdit(server, policy)">{{ policy.tag_name }}</button>
							</div>
							<p v-if="server.description" class="subtle">{{ server.description }}</p>
							<div v-if="serverNeedsAuthentication(server)" class="subtle">
								<p>
									{{ server.auth?.status === 'refresh_failed' ? (server.auth?.error_message || 'Stored OAuth login could not be refreshed. Please authenticate again.') : 'This MCP server is unauthenticated.' }}
								</p>
								<div class="row-actions">
									<button
										class="control size-xs"
										type="button"
										@click="startServerOAuth(server)">Authenticate</button>
								</div>
							</div>
							<div v-else-if="server.auth?.status === 'fallback_available'" class="subtle">
								<p>Fallback token available for this environment.</p>
								<div class="row-actions">
									<button
										class="control size-xs"
										type="button"
										@click="startServerOAuth(server)">Authenticate for environment</button>
									<button
										v-if="server.auth?.can_reuse_fallback"
										class="control size-xs ghost"
										type="button"
										@click="reuseFallbackToken(server)">Reuse fallback token</button>
								</div>
							</div>
							<div v-else-if="server.auth?.status === 'manual_client_required'" class="subtle">
								<p>Manual OAuth client configuration required.</p>
								<div class="row-actions">
									<button
										class="control size-xs"
										type="button"
										@click="openServerEdit(server)">Configure provider</button>
								</div>
							</div>
							<div v-else-if="server.auth?.status === 'connected'" class="subtle">
								<div class="row-actions">
									<button
										class="control size-xs"
										type="button"
										@click="logoutServerOAuth(server)">Log out</button>
								</div>
							</div>
						</div>
						<div class="row-actions">
							<button
								class="control size-xs icon-button tooltip"
								type="button"
								aria-label="Copy configuration"
								data-tip="Copy configuration"
								@click="exportServerConfig(server)">
								<svg
									viewBox="0 0 24 24"
									aria-hidden="true"
									focusable="false">
									<path
										fill="currentColor"
										d="M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z"/>
								</svg>
							</button>
							<button
								v-if="server.allow_policy"
								class="control size-xs icon-button tooltip"
								type="button"
								aria-label="Manage policies"
								data-tip="Add policy"
								@click="openPolicyCreate(server)">
								<svg
									viewBox="0 0 24 24"
									aria-hidden="true"
									focusable="false">
									<path
										fill="currentColor"
										d="M12 2l8 4v6c0 5-3.6 9.8-8 10-4.4-.2-8-5-8-10V6l8-4zm0 2.2L6 6.6V12c0 3.9 2.7 7.5 6 7.8 3.3-.3 6-3.9 6-7.8V6.6l-6-2.4z"/>
								</svg>
							</button>
							<button
								class="control size-xs icon-button tooltip"
								type="button"
								aria-label="Edit MCP server"
								data-tip="Edit"
								@click="openServerEdit(server)">
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
								aria-label="Delete MCP server"
								data-tip="Delete"
								@click="requestDeleteServer(server)">
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
					</div>
					<p v-if="mcpServers.length === 0" class="empty">No MCP servers yet.</p>
					<p v-else-if="filteredMcpServers.length === 0" class="empty">No MCP servers match this search.</p>
				</div>
			</article>
			<article class="panel compact">
				<div class="section-head">
					<h2>Channels</h2>
					<button
						class="control size-xs icon-button tooltip"
						type="button"
						aria-label="Create channel"
						data-tip="Create channel"
						@click="openChannelCreate">
						<svg
							viewBox="0 0 24 24"
							aria-hidden="true"
							focusable="false">
							<path fill="currentColor" d="M11 5h2v14h-2zM5 11h14v2H5z"/>
						</svg>
					</button>
				</div>
				<div class="tenant-list">
					<div
						v-for="channel in channels"
						:key="channel.id"
						class="list-row">
						<div>
							<strong>{{ channel.name }}</strong>
							<p v-if="channel.description" class="subtle">{{ channel.description }}</p>
							<p class="subtle">
								Assistant: {{ assistants.find((item) => item.id === channel.assistant_id)?.name || 'Default' }}
							</p>
							<p v-if="channelTagNames(channel).length" class="subtle">
								Tags: {{ channelTagNames(channel).join(', ') }}
							</p>
							<p v-if="channelServerSummary(channel)" class="subtle">
								MCP: {{ channelServerSummary(channel) }}
							</p>
						</div>
						<div class="row-actions">
							<button
								class="control size-xs ghost"
								type="button"
								@click="openChannelEdit(channel)">Edit</button>
							<button
								class="control size-xs ghost danger"
								type="button"
								@click="requestDeleteChannel(channel)">Delete</button>
						</div>
					</div>
					<p v-if="channels.length === 0" class="empty">No channels configured.</p>
				</div>
			</article>
			<article class="panel compact">
				<div class="section-head">
					<h2>Workspace scopes</h2>
					<span v-if="workspaceScopeSaving" class="subtle">Saving…</span>
				</div>
				<p class="subtle">Default scope decisions for this workspace. Set to Dynamic to decide per session.</p>
				<p v-if="workspaceScopeError" class="form-error">{{ workspaceScopeError }}</p>
				<p v-else-if="workspaceScopeLoading" class="subtle">Loading scopes…</p>
				<ScopeViewer
					v-else
					:scopes="workspaceScopes"
					:allow-scopes="workspaceScopeAllow"
					:deny-scopes="workspaceScopeDeny"
					:editable="true"
					:allow-dynamic="true"
					@update:scopes="handleScopeUpdate"/>
			</article>
			<article class="panel compact">
				<div class="section-head">
					<h2>Assistants</h2>
					<button
						class="control size-xs icon-button tooltip"
						type="button"
						aria-label="Create assistant"
						data-tip="Create assistant"
						@click="openAssistantCreate">
						<svg
							viewBox="0 0 24 24"
							aria-hidden="true"
							focusable="false">
							<path fill="currentColor" d="M11 5h2v14h-2zM5 11h14v2H5z"/>
						</svg>
					</button>
				</div>
				<div class="tenant-list">
					<div
						v-for="assistant in workspaceAssistants"
						:key="assistant.id"
						class="list-row">
						<div>
							<strong>{{ assistant.name }}</strong>
							<p class="subtle">
								Model: {{ assistant.model_name || 'Unassigned' }} · Persona: {{ assistant.persona_name || 'Unassigned' }}
							</p>
						</div>
						<div class="row-actions">
							<button
								class="control size-xs icon-button tooltip"
								type="button"
								aria-label="Edit assistant"
								data-tip="Edit"
								@click="openAssistantEdit(assistant)">
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
								aria-label="Delete assistant"
								data-tip="Delete"
								@click="requestDeleteAssistant(assistant)">
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
					</div>
					<p v-if="workspaceAssistants.length === 0" class="empty">No assistants assigned.</p>
				</div>
			</article>
			<article class="panel compact">
				<div class="section-head">
					<h2>Rules</h2>
					<button
						class="control size-xs icon-button tooltip"
						type="button"
						aria-label="Create rule"
						data-tip="Create rule"
						@click="openRuleCreate">
						<svg
							viewBox="0 0 24 24"
							aria-hidden="true"
							focusable="false">
							<path fill="currentColor" d="M11 5h2v14h-2zM5 11h14v2H5z"/>
						</svg>
					</button>
				</div>
				<div class="tenant-list">
					<div
						v-for="rule in workspaceRules"
						:key="rule.id"
						class="list-row">
						<div>
							<strong>{{ rule.name }}</strong>
							<p class="subtle">{{ rule.always_included ? 'Always' : 'Opt-in' }}</p>
						</div>
						<div class="row-actions">
							<button
								class="control size-xs icon-button tooltip"
								type="button"
								aria-label="Edit rule"
								data-tip="Edit"
								@click="openRuleEdit(rule)">
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
								@click="requestDeleteRule(rule)">
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
					</div>
					<p v-if="workspaceRules.length === 0" class="empty">No rules yet.</p>
				</div>
			</article>
			<article class="panel compact">
				<div class="section-head">
					<h2>Skills</h2>
					<button
						class="control size-xs icon-button tooltip"
						type="button"
						aria-label="Create skill"
						data-tip="Create skill"
						@click="openSkillCreate">
						<svg
							viewBox="0 0 24 24"
							aria-hidden="true"
							focusable="false">
							<path fill="currentColor" d="M11 5h2v14h-2zM5 11h14v2H5z"/>
						</svg>
					</button>
				</div>
				<div class="tenant-list">
					<div
						v-for="skill in visibleWorkspaceSkills"
						:key="skill.id"
						class="list-row">
						<div>
							<strong>{{ skill.name }}</strong>
						</div>
						<div class="row-actions">
							<button
								class="control size-xs ghost"
								type="button"
								@click="openSkillEdit(skill)">Edit</button>
							<button
								class="control size-xs ghost danger"
								type="button"
								@click="openDeleteSkill(skill)">Delete</button>
						</div>
					</div>
					<button
						v-if="hiddenWorkspaceSkillCount > 0"
						class="control size-xs ghost"
						type="button"
						@click="toggleSkillList">{{ skillListExpanded ? 'Show less' : `Show ${hiddenWorkspaceSkillCount} more` }}</button>
					<p v-if="workspaceSkills.length === 0" class="empty">No workspace skills yet.</p>
				</div>
			</article>
			<article class="panel compact">
				<div class="section-head">
					<h2>Prompt templates</h2>
				</div>
				<div class="tenant-list">
					<div
						v-for="section in promptSections"
						:key="section.key"
						class="list-row">
						<div>
							<strong>{{ section.label }}</strong>
							<p class="subtle">{{ workspaceTemplateBySection(section.key) ? 'Custom' : 'Default' }}</p>
						</div>
						<button
							class="control size-xs ghost"
							type="button"
							@click="openPromptTemplateEditor(section.key)">Edit</button>
					</div>
				</div>
			</article>
			<article class="panel compact">
				<div class="section-head">
					<h2>Workflows</h2>
					<button
						class="control size-xs secondary"
						type="button"
						@click="createNewWorkflow">New workflow</button>
				</div>
				<p class="subtle">User-managed workflow definitions for this workspace.</p>
				<p v-if="workflowsLoading" class="subtle">Loading workflows…</p>
				<p v-else-if="workflowsError" class="form-error">{{ workflowsError }}</p>
				<div v-else class="tenant-list">
					<div
						v-for="workflow in userWorkflows"
						:key="workflow.id"
						class="list-row">
						<div>
							<div class="row-actions">
								<strong>{{ workflow.name }}</strong>
								<span v-if="workflow.disabled" class="control size-xs pill">Disabled</span>
							</div>
							<p v-if="workflow.description" class="subtle">{{ workflow.description }}</p>
							<p v-else class="subtle">{{ workflowTriggerLabel(workflow.start_trigger) }}</p>
						</div>
						<div class="row-actions">
							<button
								class="control size-xs ghost"
								type="button"
								@click="openWorkflow(workflow.id)">Edit</button>
							<button
								class="control size-xs ghost danger"
								type="button"
								@click="requestDeleteWorkflow(workflow)">Delete</button>
						</div>
					</div>
					<p v-if="userWorkflows.length === 0" class="empty">No workflows yet.</p>
				</div>
			</article>
		</section>
		<div v-if="status" class="toast">{{ status }}</div>
		<div v-if="channelModalOpen" class="modal-backdrop">
			<div class="modal-card wide-modal channel-modal-card">
				<div class="modal-header">
					<h3>{{ channelEditId ? 'Edit channel' : 'Create channel' }}</h3>
					<button
						class="control size-xs ghost"
						type="button"
						@click="closeChannelModal">Close</button>
				</div>
				<div class="modal-body channel-form-layout">
					<div class="channel-form-column">
						<label class="field">
							<span>Name</span>
							<input
								v-model="channelForm.name"
								type="text"
								class="control"/>
						</label>
						<label class="field">
							<span>Description</span>
							<input
								v-model="channelForm.description"
								type="text"
								class="control"/>
						</label>
						<label class="field">
							<span>Assistant</span>
							<select v-model="channelForm.assistant_id" class="control">
								<option value="">Workspace default</option>
								<optgroup v-if="workspaceAssistants.length" label="Workspace assistants">
									<option
										v-for="assistant in workspaceAssistants"
										:key="assistant.id"
										:value="assistant.id">{{ assistant.name }}·{{ assistant.model_name || 'Unassigned' }}</option>
								</optgroup>
								<optgroup v-if="tenantAssistants.length" label="Tenant assistants">
									<option
										v-for="assistant in tenantAssistants"
										:key="assistant.id"
										:value="assistant.id">{{ assistant.name }}·{{ assistant.model_name || 'Unassigned' }}</option>
								</optgroup>
							</select>
						</label>
						<label class="field channel-prompt-field">
							<span>Channel prompt</span>
							<textarea
								v-model="channelForm.prompt"
								class="control"
								rows="16"></textarea>
						</label>
						<div class="field">
							<span>Default scopes</span>
							<ScopeViewer
								:scopes="workspaceScopes"
								:allow-scopes="channelForm.allow_scopes"
								:deny-scopes="channelForm.deny_scopes"
								:editable="true"
								:allow-dynamic="true"
								@update:scopes="updateChannelScopes"/>
						</div>
					</div>
					<div class="channel-form-column">
						<div class="field">
							<span>Tags</span>
							<div class="channel-grid tag-grid">
								<div
									v-for="category in channelTagCategories"
									:key="category.id"
									class="channel-card">
									<strong class="channel-card-title">{{ category.name }}</strong>
									<select
										:value="channelTagValue(category.id)"
										class="control"
										@change="updateChannelTag(category.id, $event.target.value)">
										<option value="">Not set</option>
										<option
											v-for="value in category.values || []"
											:key="value.id"
											:value="value.id">{{ value.name }}</option>
									</select>
								</div>
							</div>
						</div>
						<div class="field">
							<span>MCP servers</span>
							<div class="channel-grid mcp-grid">
								<div
									v-for="server in mcpServers"
									:key="server.id"
									class="channel-card">
									<strong class="channel-card-title">{{ server.name }}</strong>
									<p v-if="server.description" class="subtle channel-card-copy">{{ server.description }}</p>
									<select
										:value="channelServerValue(server.id)"
										class="control"
										@change="updateChannelServer(server.id, $event.target.value)">
										<option value="VISIBLE">Enabled by default</option>
										<option value="AVAILABLE">Available on request</option>
										<option value="HIDDEN">Hidden</option>
									</select>
								</div>
							</div>
						</div>
					</div>
					<p v-if="channelError" class="form-error channel-form-error">{{ channelError }}</p>
				</div>
				<div class="modal-actions">
					<button
						class="control ghost"
						type="button"
						@click="closeChannelModal">Cancel</button>
					<button
						class="control primary"
						type="button"
						:disabled="channelSaving"
						@click="saveChannel">{{ channelSaving ? 'Saving…' : 'Save channel' }}</button>
				</div>
			</div>
		</div>
		<ConfirmModal
			v-model:open="deleteChannelOpen"
			title="Delete channel"
			:message="channelToDelete ? `Delete ${channelToDelete.name}?` : 'Delete this channel?'"
			confirm-label="Delete"
			:destructive="true"
			@confirm="confirmDeleteChannel"
			@cancel="deleteChannelOpen = false"/>
		<ConfirmModal
			v-model:open="deletePageOpen"
			title="Delete page"
			:message="deletePageMessage"
			confirm-label="Delete"
			:destructive="true"
			@confirm="confirmPageDelete"
			@cancel="deletePageOpen = false"/>
		<ConfirmModal
			v-model:open="deleteScriptOpen"
			title="Delete script"
			:message="deleteScriptMessage"
			confirm-label="Delete"
			:destructive="true"
			@confirm="confirmScriptDelete"
			@cancel="deleteScriptOpen = false"/>
		<ConfirmModal
			v-model:open="pageCopyOpen"
			title="Copy page configuration"
			confirm-label="Copy"
			@confirm="confirmPageCopy"
			@cancel="pageCopyOpen = false">
			<p class="subtle">Include page dependencies in the copied payload?</p>
			<label class="field checkbox">
				<input v-model="pageCopyIncludeDependencies" type="checkbox"/>
				<span>Include dependencies</span>
			</label>
		</ConfirmModal>
		<ConfirmModal
			v-model:open="deleteServerOpen"
			title="Delete MCP server"
			:message="deleteServerMessage"
			confirm-label="Delete"
			:destructive="true"
			@confirm="confirmDeleteServer"
			@cancel="cancelDeleteServer"/>
		<ConfirmModal
			v-model:open="deleteRuleOpen"
			title="Delete rule"
			:message="deleteRuleMessage"
			confirm-label="Delete"
			:destructive="true"
			@confirm="confirmDeleteRule"
			@cancel="cancelDeleteRule"/>
		<ConfirmModal
			v-model:open="deleteTagOpen"
			title="Delete tag"
			:message="deleteTagMessage"
			confirm-label="Delete"
			:destructive="true"
			@confirm="confirmDeleteTag"
			@cancel="cancelDeleteTag"/>
		<ConfirmModal
			v-model:open="deleteProviderOpen"
			title="Delete provider"
			:message="deleteProviderMessage"
			confirm-label="Delete"
			:destructive="true"
			@confirm="confirmDeleteProvider"
			@cancel="cancelDeleteProvider"/>
		<ConfirmModal
			v-model:open="deletePolicyOpen"
			title="Delete policy"
			:message="deletePolicyMessage"
			confirm-label="Delete"
			:destructive="true"
			@confirm="confirmDeletePolicy"
			@cancel="cancelDeletePolicy"/>
		<ConfirmModal
			v-model:open="deleteOverrideOpen"
			title="Delete variant"
			:message="deleteOverrideMessage"
			confirm-label="Delete"
			:destructive="true"
			@confirm="confirmDeleteOverride"
			@cancel="cancelDeleteOverride"/>
		<ConfirmModal
			v-model:open="deleteWorkflowOpen"
			title="Delete workflow"
			:message="deleteWorkflowMessage"
			confirm-label="Delete"
			:destructive="true"
			@confirm="confirmDeleteWorkflow"
			@cancel="cancelDeleteWorkflow"/>
		<div
			v-if="providerModalOpen"
			class="modal-backdrop"
			@click.self="providerModalOpen = false">
			<div class="modal-card">
				<div class="section-head">
					<h3>{{ providerEditId ? 'Edit OpenID provider' : 'Add OpenID provider' }}</h3>
				</div>
				<div class="stack">
					<label class="field">
						<span>Issuer</span>
						<input
							v-model="providerForm.issuer"
							type="text"
							placeholder="https://issuer"/>
					</label>
					<div class="field">
						<div class="section-head">
							<span>Scope categories</span>
						</div>
						<div class="stack">
							<label
								v-for="category in sortedTagCategories"
								:key="category.id"
								class="row">
								<input
									v-model="providerForm.scope_category_ids"
									type="checkbox"
									:value="category.id"/>
								<span>{{ category.name }}</span>
							</label>
							<p v-if="sortedTagCategories.length === 0" class="empty">No tag categories yet.</p>
							<p class="subtle">Only tag values in these categories will scope OAuth tokens.</p>
						</div>
					</div>
					<label class="field">
						<span>Resource metadata URL</span>
						<input
							v-model="providerForm.resource_metadata_url"
							type="text"
							placeholder="https://server/.well-known/oauth-protected-resource"/>
					</label>
					<label class="field">
						<span>Well-known URL</span>
						<input
							v-model="providerForm.well_known_url"
							type="text"
							placeholder="https://issuer/.well-known/openid-configuration"/>
					</label>
					<label class="field">
						<span>Authorization endpoint</span>
						<input
							v-model="providerForm.authorization_endpoint"
							type="text"
							placeholder="https://issuer/auth"/>
					</label>
					<label class="field">
						<span>Token endpoint</span>
						<input
							v-model="providerForm.token_endpoint"
							type="text"
							placeholder="https://issuer/token"/>
					</label>
					<label class="field">
						<span>Registration endpoint</span>
						<input
							v-model="providerForm.registration_endpoint"
							type="text"
							placeholder="https://issuer/register"/>
					</label>
					<label class="field">
						<span>Client ID</span>
						<input
							v-model="providerForm.client_id"
							type="text"
							placeholder="Client ID"/>
					</label>
					<label class="field">
						<span>Client secret</span>
						<input
							v-model="providerForm.client_secret"
							type="password"
							:placeholder="providerForm.client_secret_hint || 'Not set'"/>
						<p v-if="providerForm.client_secret_hint" class="subtle">
							Stored secret: {{ providerForm.client_secret_hint }}
						</p>
					</label>
					<p v-if="providerError" class="form-error">{{ providerError }}</p>
					<div class="row-actions">
						<button
							class="control size-m secondary"
							type="button"
							:disabled="providerSaving"
							@click="saveProvider">Save</button>
						<button
							class="control size-m ghost"
							type="button"
							@click="providerModalOpen = false">Cancel</button>
					</div>
				</div>
			</div>
		</div>
		<div
			v-if="providerImportOpen"
			class="modal-backdrop"
			@click.self="providerImportOpen = false">
			<div class="modal-card wide">
				<div class="section-head">
					<h3>Import OpenID provider</h3>
					<div class="row-actions">
						<button
							class="control size-m ghost"
							type="button"
							@click="providerImportOpen = false">Close</button>
					</div>
				</div>
				<p class="subtle">Paste a provider configuration JSON blob to create a new OpenID provider.</p>
				<label class="field">
					<span>Configuration JSON</span>
					<textarea
						v-model="providerImportPayload"
						rows="12"
						placeholder="{...}"></textarea>
				</label>
				<p v-if="providerImportError" class="form-error">{{ providerImportError }}</p>
				<div class="row-actions">
					<button
						class="control size-m secondary"
						type="button"
						@click="applyProviderImportPayload">Use configuration</button>
					<button
						class="control size-m ghost"
						type="button"
						@click="providerImportOpen = false">Cancel</button>
				</div>
			</div>
		</div>
		<div
			v-if="serverModal"
			class="sheet-backdrop"
			@click.self="serverModal = false">
			<div class="sidepane">
				<button
					class="control size-s ghost icon-button icon-ghost sidepane-close"
					type="button"
					aria-label="Close"
					@click="serverModal = false">
					<svg
						viewBox="0 0 24 24"
						aria-hidden="true"
						focusable="false">
						<path
							fill="currentColor"
							d="M18.3 5.71a1 1 0 0 0-1.41 0L12 10.59 7.11 5.7a1 1 0 0 0-1.41 1.42L10.59 12l-4.89 4.89a1 1 0 1 0 1.41 1.41L12 13.41l4.89 4.89a1 1 0 0 0 1.41-1.41L13.41 12l4.89-4.89a1 1 0 0 0 0-1.4z"/>
					</svg>
				</button>
				<h2>{{ serverEditId ? 'Edit MCP server' : 'Create MCP server' }}</h2>
				<div class="stack">
					<label class="field">
						<span>Name</span>
						<input
							v-model="serverForm.name"
							type="text"
							placeholder="Server name"/>
					</label>
					<label class="field">
						<span>Description</span>
						<input
							v-model="serverForm.description"
							type="text"
							placeholder="Optional summary"/>
					</label>
					<label class="field">
						<span>Protocol</span>
						<select v-model="serverForm.protocol">
							<option value="STDIO">Stdio</option>
							<option value="SSE">SSE</option>
							<option value="STREAMABLE_HTTP">Streamable HTTP</option>
							<option value="VIRTUAL">Virtual</option>
						</select>
					</label>
					<label class="field">
						<span>Tool name prefix</span>
						<input
							v-model="serverForm.tool_name_prefix"
							type="text"
							placeholder="Optional prefix, e.g. github_"/>
						<p class="subtle">Prepended to each tool alias or native name for this server.</p>
					</label>
					<template v-if="serverForm.protocol === 'STDIO'">
						<label class="field">
							<span>Command</span>
							<input
								v-model="serverForm.command"
								type="text"
								placeholder="node server.js"/>
						</label>
						<label class="field">
							<span>CWD</span>
							<input
								v-model="serverForm.cwd"
								type="text"
								placeholder="/path/to/server"/>
						</label>
						<label class="field">
							<span>Framing</span>
							<select v-model="serverForm.framing">
								<option value="ndjson">NDJSON</option>
								<option value="content-length">Content-Length</option>
							</select>
						</label>
					</template>
					<template v-else-if="serverForm.protocol === 'VIRTUAL'">
						<label class="field">
							<span>Virtual server</span>
							<select v-model="serverForm.virtual_type">
								<option value="polymr">Polymr</option>
								<option value="polymr_scripts">Polymr Script Editing</option>
								<option value="polymr_pages">Polymr Page Editing</option>
								<option value="polymr_canvas">Polymr Canvas</option>
								<option value="script">Dynamic Script Server</option>
							</select>
						</label>
						<div v-if="serverForm.virtual_type === 'script'" class="field">
							<div class="section-head">
								<span>Scripts</span>
								<button
									class="control size-s ghost"
									type="button"
									@click="scriptPickerOpen = !scriptPickerOpen">{{ scriptPickerOpen ? 'Close' : 'Add script' }}</button>
							</div>
							<p class="subtle">Selected scripts become the MCP tools for this server.</p>
							<div v-if="selectedServerScripts.length" class="script-pill-row">
								<div
									v-for="script in selectedServerScripts"
									:key="script.id"
									class="script-pill">
									<div>
										<strong>{{ script.name }}</strong>
										<span class="subtle">{{ script.slug }}</span>
									</div>
									<button
										class="control size-xs ghost"
										type="button"
										@click="removeScriptFromServer(script.id)">Remove</button>
								</div>
							</div>
							<p v-else class="subtle">No scripts selected yet.</p>
							<div v-if="scriptPickerOpen" class="script-picker">
								<input
									v-model="scriptPickerQuery"
									type="text"
									placeholder="Filter scripts by name or slug"/>
								<div class="script-picker-list">
									<p v-if="scriptPickerError" class="subtle">{{ scriptPickerError }}</p>
									<p v-else-if="scriptPickerLoading" class="subtle">Searching scripts...</p>
									<button
										v-for="script in availableServerScripts"
										:key="script.id"
										type="button"
										class="script-picker-option"
										@click="addScriptToServer(script)">
										<strong>{{ script.name }}</strong>
										<span class="subtle">{{ script.slug }}</span>
									</button>
									<p
										v-if="!scriptPickerLoading && !scriptPickerError && scriptPickerQuery.trim() && availableServerScripts.length === 0"
										class="subtle">No scripts match.</p>
								</div>
							</div>
						</div>
					</template>
					<template v-else>
						<label class="field">
							<span>HTTP URL</span>
							<input
								v-model="serverForm.http_url"
								type="text"
								placeholder="https://host/mcp"/>
						</label>
						<label
							v-if="serverForm.protocol === 'SSE' || serverForm.protocol === 'STREAMABLE_HTTP'"
							class="field">
							<span>Manage server instance</span>
							<label class="switch">
								<input
									:checked="serverUsesManagedInstance"
									type="checkbox"
									@change="serverUsesManagedInstance = $event.target.checked; if (!serverUsesManagedInstance) { serverForm.command = ''; serverForm.cwd = ''; serverForm.environment = '' }"/>
								<span class="tooltip" data-tip="Starts a local instance of the server">Enabled</span>
							</label>
						</label>
						<template v-if="serverUsesManagedInstance">
							<label class="field">
								<span>Command</span>
								<input
									v-model="serverForm.command"
									type="text"
									placeholder="node server.js"/>
							</label>
							<label class="field">
								<span>CWD</span>
								<input
									v-model="serverForm.cwd"
									type="text"
									placeholder="/path/to/server"/>
							</label>
						</template>
						<label
							v-if="serverForm.protocol === 'SSE' || serverForm.protocol === 'STREAMABLE_HTTP'"
							class="field">
							<span>Enable SSH tunnel</span>
							<label class="switch">
								<input
									v-model="serverForm.ssh_enabled"
									type="checkbox"
									:disabled="serverForm.oauth_enabled"/>
								<span
									:class="{ tooltip: serverForm.oauth_enabled }"
									:data-tip="serverForm.oauth_enabled ? 'SSH tunnel and OAuth do not work together yet.' : null">{{ serverForm.ssh_enabled ? 'Enabled' : 'Disabled' }}</span>
							</label>
						</label>
						<template
							v-if="(serverForm.protocol === 'SSE' || serverForm.protocol === 'STREAMABLE_HTTP') && serverForm.ssh_enabled">
							<label class="field">
								<span>SSH server</span>
								<input
									v-model="serverForm.ssh_tunnel.server"
									type="text"
									placeholder="bastion.example.com"/>
							</label>
							<label class="field">
								<span>SSH port</span>
								<input
									v-model="serverForm.ssh_tunnel.port"
									type="number"
									min="1"
									placeholder="22"/>
							</label>
							<label class="field">
								<span>SSH username</span>
								<input
									v-model="serverForm.ssh_tunnel.username"
									type="text"
									placeholder="ubuntu"/>
							</label>
							<label class="field">
								<span>Password</span>
								<input
									v-model="serverForm.ssh_tunnel.password"
									type="password"
									placeholder="Optional"/>
							</label>
							<label class="field">
								<span>Key path</span>
								<input
									v-model="serverForm.ssh_tunnel.key_path"
									type="text"
									placeholder="/home/user/.ssh/id_rsa"/>
							</label>
							<label class="field">
								<span>Private key</span>
								<textarea
									v-model="serverForm.ssh_tunnel.key"
									rows="6"
									placeholder="Paste private key"></textarea>
							</label>
						</template>
						<label class="field">
							<span>Headers</span>
							<textarea
								v-model="serverForm.headers"
								rows="4"
								placeholder="Header=Value"></textarea>
						</label>
						<div
							v-if="serverEditId && (serverForm.protocol === 'SSE' || serverForm.protocol === 'STREAMABLE_HTTP')"
							class="field">
							<div class="section-head">
								<span>Variants</span>
								<button
									class="control size-s ghost"
									type="button"
									@click="openOverrideCreate(activeServerForOverrides)">Add variant</button>
							</div>
							<p class="subtle">Variants override HTTP config for a specific tag value.</p>
							<div v-if="serverOverrides(serverEditId).length" class="variant-list">
								<div
									v-for="override in serverOverrides(serverEditId)"
									:key="override.id"
									class="variant-card">
									<div class="variant-card-copy">
										<div class="variant-card-head">
											<strong>{{ override.logical_name || 'Untitled variant' }}</strong>
											<span class="control size-xs pill">{{ override.tag_name || 'Tag' }}</span>
										</div>
										<p class="subtle variant-card-meta">
											Applies when
											<strong>{{ override.tag_name || 'tag' }}</strong>
											matches this variant.
										</p>
									</div>
									<div class="row-actions variant-card-actions">
										<button
											class="control size-xs ghost"
											type="button"
											@click="openOverrideEdit(activeServerForOverrides, override)">Edit</button>
									</div>
								</div>
							</div>
							<p v-else class="subtle">No variants configured.</p>
						</div>
					</template>
					<label v-if="serverForm.protocol === 'STDIO' || serverUsesManagedInstance" class="field">
						<span>Environment</span>
						<textarea
							v-model="serverForm.environment"
							rows="4"
							placeholder="KEY=VALUE"></textarea>
					</label>
					<label class="field">
						<span>Visibility</span>
						<select v-model="serverForm.visibility">
							<option value="VISIBLE">Visible (auto-include)</option>
							<option value="AVAILABLE">Available (manual)</option>
							<option value="HIDDEN">Hidden</option>
						</select>
					</label>
					<div v-if="serverForm.visibility === 'AVAILABLE'" class="field">
						<span>Server instructions</span>
						<p class="subtle">These instructions are sent to the LLM when activating this server.</p>
						<label class="switch">
							<input v-model="serverForm.custom_instructions" type="checkbox"/>
							<span
								class="tooltip"
								data-tip="When enabled, Polymr will stop updating these instructions from the MCP server.">Custom instructions</span>
						</label>
						<textarea
							v-model="serverForm.instructions"
							rows="6"
							:disabled="!serverForm.custom_instructions"
							placeholder="No instructions provided by the MCP server."></textarea>
					</div>
					<label
						v-if="serverForm.protocol === 'SSE' || serverForm.protocol === 'STREAMABLE_HTTP'"
						class="field">
						<span>OAuth2 required</span>
						<label class="switch">
							<input
								v-model="serverForm.oauth_enabled"
								type="checkbox"
								:disabled="serverForm.ssh_enabled"/>
							<span
								:class="{ tooltip: serverForm.ssh_enabled }"
								:data-tip="serverForm.ssh_enabled ? 'OAuth and SSH tunnel do not work together yet.' : null">{{ serverForm.oauth_enabled ? 'Enabled' : 'Disabled' }}</span>
						</label>
					</label>
					<label
						v-if="(serverForm.protocol === 'SSE' || serverForm.protocol === 'STREAMABLE_HTTP') && serverForm.oauth_enabled"
						class="field">
						<span>Authentication mode</span>
						<label class="switch">
							<input v-model="serverForm.auth.global" type="checkbox"/>
							<span>{{ serverForm.auth.global ? 'Global' : 'Personalized' }}</span>
						</label>
						<p class="subtle">Global uses shared credentials for all users. Personalized uses each user's OAuth token.</p>
					</label>
					<details
						v-if="
              (serverForm.protocol === 'SSE' || serverForm.protocol === 'STREAMABLE_HTTP') &&
              (serverForm.oauth_enabled || serverForm.auth?.status)
            "
						class="card tool-collapse">
						<summary class="section-head">
							<h3>OpenID / OAuth</h3>
							<div class="row-actions">
								<span class="tool-collapse-icon" aria-hidden="true">
									<svg viewBox="0 0 24 24" focusable="false">
										<path
											fill="currentColor"
											d="M7.4 9.4 12 14l4.6-4.6 1.4 1.4-6 6-6-6z"/>
									</svg>
								</span>
							</div>
						</summary>
						<div class="row-actions">
							<button
								class="control size-s ghost"
								type="button"
								:disabled="!serverEditId || !serverForm.auth.refreshable"
								@click="requestAuthRefresh">Request refresh</button>
							<button
								class="control size-s"
								type="button"
								:disabled="!serverEditId || !serverForm.auth.status || serverForm.auth.status === 'auth_required'"
								@click="logoutServerOAuthForEdit">Log out</button>
							<button
								class="control size-s ghost"
								type="button"
								:disabled="!serverEditId"
								@click="discoverServerOAuth">Discover</button>
							<button
								class="control size-s"
								type="button"
								:disabled="!serverEditId"
								@click="startServerOAuthForEdit">Authenticate</button>
						</div>
						<p class="subtle">MCP OAuth metadata and client configuration. These values are stored per MCP server.</p>
						<div class="field">
							<span>Status</span>
							<p class="subtle">
								{{ serverForm.auth.status ? serverForm.auth.status.replace(/_/g, ' ') : 'Not connected' }}
								<span v-if="serverForm.oauth_enabled">· {{ serverForm.auth.global ? 'Global' : 'Personalized' }}</span>
								<span v-if="serverForm.auth.refreshable">· Refreshable</span>
								<span v-if="serverForm.auth.last_auth_at">· Last auth: {{ serverForm.auth.last_auth_at }}</span>
							</p>
							<p v-if="serverForm.auth.status === 'manual_client_required'" class="form-error">Manual client setup required. Provide client id/secret and issuer metadata.</p>
							<p v-else-if="serverForm.auth.error_message" class="form-error">{{ serverForm.auth.error_message }}</p>
						</div>
						<div v-if="serverForm.oauth_enabled" class="field">
							<div class="section-head">
								<span>Redirect URI</span>
								<button
									class="control size-xs ghost tooltip"
									type="button"
									data-tip="Copy redirect URI"
									@click="copyRedirectUri">Copy</button>
							</div>
							<input
								:value="redirectUri"
								type="text"
								readonly/>
						</div>
						<label class="field">
							<span>Resource metadata URL</span>
							<p class="subtle">
								OAuth Protected Resource metadata for the MCP server. Use this when the server exposes
                a resource metadata document (often points to the authorization server).
							</p>
							<input
								v-model="serverForm.auth.resource_metadata_url"
								type="text"
								placeholder="https://mcp-server/.well-known/oauth-protected-resource"/>
							<button
								class="control size-xs ghost"
								type="button"
								@click="fetchAuthDetails">Fetch details</button>
						</label>
						<label class="field">
							<span>Well-known URL</span>
							<p class="subtle">
								OpenID configuration URL for the authorization server. Use this when you already know
                the issuer and want to pull endpoint details.
							</p>
							<input
								v-model="serverForm.auth.well_known_url"
								type="text"
								placeholder="https://issuer/.well-known/openid-configuration"/>
							<button
								class="control size-xs ghost"
								type="button"
								@click="fetchAuthDetails">Fetch details</button>
						</label>
						<label class="field">
							<span>Issuer</span>
							<input
								v-model="serverForm.auth.issuer"
								type="text"
								placeholder="https://issuer"/>
						</label>
						<label class="field">
							<span>Authorization endpoint</span>
							<input
								v-model="serverForm.auth.authorization_endpoint"
								type="text"
								placeholder="https://issuer/oauth2/authorize"/>
						</label>
						<label class="field">
							<span>Token endpoint</span>
							<input
								v-model="serverForm.auth.token_endpoint"
								type="text"
								placeholder="https://issuer/oauth2/token"/>
						</label>
						<label class="field">
							<span>Registration endpoint</span>
							<input
								v-model="serverForm.auth.registration_endpoint"
								type="text"
								placeholder="https://issuer/oauth2/register"/>
						</label>
						<div class="field">
							<span>Scopes</span>
							<p class="subtle">Select discovered scopes and add any custom scopes manually.</p>
							<div v-if="serverForm.auth.supported_scopes.length" class="stack">
								<label
									v-for="scope in serverForm.auth.supported_scopes"
									:key="scope"
									class="row">
									<input
										v-model="serverForm.auth.selected_supported_scopes"
										type="checkbox"
										:value="scope"/>
									<span>{{ scope }}</span>
								</label>
							</div>
							<p v-else class="subtle">No supported scopes discovered yet.</p>
							<input
								v-model="serverForm.auth.custom_scopes"
								type="text"
								placeholder="Custom scopes separated by spaces"/>
						</div>
						<label class="field">
							<span>Client ID</span>
							<input
								v-model="serverForm.auth.client_id"
								type="text"
								placeholder="Client ID"/>
						</label>
						<label class="field">
							<span>Client secret</span>
							<input
								v-model="serverForm.auth.client_secret"
								type="password"
								:placeholder="serverForm.auth.client_secret_hint || 'Not set'"/>
							<p v-if="serverForm.auth.client_secret_hint" class="subtle">
								Stored secret: {{ serverForm.auth.client_secret_hint }}
							</p>
						</label>
					</details>
					<div class="card">
						<div class="section-head">
							<h3>Configuration</h3>
							<div class="row-actions">
								<button
									class="control size-s ghost"
									type="button"
									@click="runServerProbe">Check configuration definition</button>
								<button
									class="control size-s ghost"
									type="button"
									@click="openServerDefinition">View full definition</button>
							</div>
						</div>
						<p class="subtle">This checks the initialize response to detect configuration schema and policy support.</p>
						<p v-if="serverProbeState.status === 'loading'" class="subtle">{{ serverProbeState.message }}</p>
						<p v-else-if="serverProbeState.status === 'success'" class="subtle">{{ serverProbeState.message }}</p>
						<p v-else-if="serverProbeState.status === 'error'" class="form-error">{{ serverProbeState.message }}</p>
					</div>
					<div v-if="configurationSchema" class="field">
						<div class="section-head">
							<span>Configuration</span>
							<button
								class="control size-s ghost"
								type="button"
								@click="openLargeEditor('config')">Open large editor</button>
						</div>
						<JsonSchemaEditor
							class="compact-editor"
							v-model="serverForm.configuration_value"
							:schema="configurationSchema"/>
						<p class="subtle">Sent during initialize; overrides headers/env values when supported.</p>
					</div>
					<details
						v-if="serverEditId"
						class="card tool-collapse"
						:open="toolConfigOpen"
						@toggle="toolConfigOpen = $event.target.open">
						<summary class="section-head">
							<h3>Tool configuration</h3>
							<div class="row-actions">
								<span class="tool-collapse-icon" aria-hidden="true">
									<svg viewBox="0 0 24 24" focusable="false">
										<path
											fill="currentColor"
											d="M7.4 9.4 12 14l4.6-4.6 1.4 1.4-6 6-6-6z"/>
									</svg>
								</span>
							</div>
						</summary>
						<div class="row-actions">
							<button
								class="control size-s ghost"
								type="button"
								@click.stop.prevent="loadServerTools">Refresh list</button>
							<button
								class="control size-s"
								type="button"
								@click.stop.prevent="refreshServerTools">Recheck server</button>
						</div>
						<p v-if="serverToolsLoading" class="subtle">Loading tools…</p>
						<p v-else-if="serverToolsError" class="form-error">{{ serverToolsError }}</p>
						<div v-else class="tool-list">
							<div
								v-for="tool in serverTools"
								:key="tool.id"
								class="tool-row">
								<div class="tool-meta">
									<strong>{{ tool.tool_name }}</strong>
									<span class="subtle" v-if="tool.description">{{ tool.description }}</span>
									<span class="subtle" v-if="tool.scopes && tool.scopes.length">
										Default scopes: {{ tool.scopes.join(', ') }}
									</span>
								</div>
								<div class="tool-config">
									<label class="field">
										<label class="switch">
											<input
												type="checkbox"
												:checked="!tool.disabled"
												@change="tool.disabled = !($event.target && $event.target.checked)"/>
											<span>{{ tool.disabled ? 'Disabled' : 'Enabled' }}</span>
										</label>
									</label>
									<label class="field">
										<span>Alias</span>
										<input
											v-model="tool.tool_alias"
											type="text"
											placeholder="Alias (optional)"
											:disabled="toolSavingAll"/>
									</label>
									<label class="field">
										<span>Custom scopes</span>
										<input
											v-model="tool.custom_scopes_text"
											type="text"
											placeholder="read:file, write:file"
											:disabled="toolSavingAll"/>
									</label>
								</div>
							</div>
							<p v-if="serverTools.length === 0" class="subtle">No tools detected yet.</p>
						</div>
					</details>
					<details
						v-if="serverEditId"
						class="card tool-collapse"
						:open="applicationConfigOpen"
						@toggle="applicationConfigOpen = $event.target.open">
						<summary class="section-head">
							<h3>Applications</h3>
							<div class="row-actions">
								<span class="tool-collapse-icon" aria-hidden="true">
									<svg viewBox="0 0 24 24" focusable="false">
										<path
											fill="currentColor"
											d="M7.4 9.4 12 14l4.6-4.6 1.4 1.4-6 6-6-6z"/>
									</svg>
								</span>
							</div>
						</summary>
						<div class="row-actions">
							<button
								class="control size-s ghost"
								type="button"
								@click.stop.prevent="refreshServerApplications">Refresh</button>
						</div>
						<p v-if="serverApplicationsLoading" class="subtle">Loading applications…</p>
						<p v-else-if="serverApplicationsError" class="form-error">{{ serverApplicationsError }}</p>
						<div v-else class="tool-list app-list">
							<div
								v-for="app in serverApplications"
								:key="app.id"
								class="tool-row app-row">
								<div class="tool-meta">
									<div class="app-meta">
										<span
											v-if="app.icon_svg"
											class="app-icon"
											v-html="app.icon_svg"></span>
										<span v-else class="app-icon">
											<svg
												viewBox="0 0 24 24"
												aria-hidden="true"
												focusable="false">
												<path
													fill="currentColor"
													d="M4 5h6v6H4V5zm10 0h6v6h-6V5zM4 13h6v6H4v-6zm10 0h6v6h-6v-6z"/>
											</svg>
										</span>
										<div>
											<strong>{{ app.app_name || app.app_uri }}</strong>
											<span class="subtle" v-if="app.app_uri">{{ app.app_uri }}</span>
										</div>
									</div>
								</div>
								<div class="tool-config app-config">
									<label class="field">
										<span>Display name</span>
										<input
											v-model="app.display_name"
											type="text"
											placeholder="Name in menu"
											:disabled="applicationSavingAll"/>
									</label>
									<label class="field">
										<span>Icon (SVG)</span>
										<textarea
											v-model="app.icon_svg"
											rows="3"
											placeholder="<svg>...</svg>"
											:disabled="applicationSavingAll"></textarea>
										<div class="row-actions">
											<label class="control size-xs ghost upload">
												<input
													type="file"
													accept="image/svg+xml,.svg"
													:disabled="applicationSavingAll"
													@change="handleAppIconUpload(app, $event)"/>
												Upload SVG
											</label>
											<button
												class="control size-xs ghost"
												type="button"
												:disabled="applicationSavingAll"
												@click="clearAppIcon(app)">Clear</button>
										</div>
									</label>
									<label class="switch">
										<input
											v-model="app.disabled"
											type="checkbox"
											:disabled="applicationSavingAll"/>
										<span>Disabled</span>
									</label>
								</div>
							</div>
							<p v-if="serverApplications.length === 0" class="subtle">No applications detected yet.</p>
						</div>
					</details>
					<div class="row-actions">
						<button
							class="control size-m secondary"
							type="button"
							@click="saveServer">Save</button>
						<button
							class="control size-m ghost"
							type="button"
							@click="serverModal = false">Cancel</button>
					</div>
				</div>
			</div>
		</div>
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
							rows="15"
							placeholder="Rule content"></textarea>
					</label>
					<div class="inline-switches">
						<label class="switch">
							<input v-model="ruleForm.always_included" type="checkbox"/>
							<span>Always include</span>
						</label>
						<label class="switch">
							<input v-model="ruleForm.enabled" type="checkbox"/>
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
							v-model="ruleForm.order"
							type="number"
							placeholder="10"/>
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
		<div
			v-if="assistantModalOpen"
			class="sheet-backdrop"
			@click.self="assistantModalOpen = false">
			<div class="sidepane">
				<button
					class="control size-s ghost icon-button icon-ghost sidepane-close"
					type="button"
					aria-label="Close"
					@click="assistantModalOpen = false">
					<svg
						viewBox="0 0 24 24"
						aria-hidden="true"
						focusable="false">
						<path
							fill="currentColor"
							d="M18.3 5.71a1 1 0 0 0-1.41 0L12 10.59 7.11 5.7a1 1 0 0 0-1.41 1.42L10.59 12l-4.89 4.89a1 1 0 1 0 1.41 1.41L12 13.41l4.89 4.89a1 1 0 0 0 1.41-1.41L13.41 12l4.89-4.89a1 1 0 0 0 0-1.4z"/>
					</svg>
				</button>
				<h2>{{ assistantEditId ? 'Edit assistant' : 'Create assistant' }}</h2>
				<div class="stack">
					<label class="field">
						<span>Name</span>
						<input
							v-model="assistantForm.name"
							type="text"
							placeholder="Assistant name"/>
					</label>
					<label class="field">
						<span>Description</span>
						<input
							v-model="assistantForm.description"
							type="text"
							placeholder="Short summary"/>
					</label>
					<label class="field">
						<span>Prompt</span>
						<textarea
							v-model="assistantForm.prompt_text"
							rows="5"
							placeholder="System prompt"></textarea>
					</label>
					<label class="field">
						<span>Persona</span>
						<select v-model="assistantForm.persona_id">
							<option value="">None</option>
							<option
								v-for="persona in personas"
								:key="persona.id"
								:value="persona.id">{{ persona.name }}</option>
						</select>
					</label>
					<label class="field">
						<span>Model</span>
						<select v-model="assistantForm.model_id">
							<option value="">Select model</option>
							<option
								v-for="model in models"
								:key="model.id"
								:value="model.id">{{ model.name }}</option>
						</select>
					</label>
					<label class="field">
						<span>Max output tokens</span>
						<input
							v-model="assistantForm.max_output_tokens"
							type="number"
							min="1"
							placeholder="Use tenant default"/>
					</label>
					<div class="field">
						<span>Worker</span>
						<label class="switch">
							<input v-model="assistantForm.worker_enabled" type="checkbox"/>
							<span>Eligible for worker use</span>
						</label>
					</div>
					<label v-if="assistantForm.worker_enabled" class="field">
						<span>Worker trigger</span>
						<textarea
							v-model="assistantForm.worker_trigger"
							rows="3"
							placeholder="Describe when this worker is a good choice"></textarea>
					</label>
					<div v-if="assistantForm.worker_enabled" class="field">
						<span>Worker scopes</span>
						<p v-if="workerScopeError" class="form-error">{{ workerScopeError }}</p>
						<p v-else-if="workerScopeLoading" class="subtle">Loading scopes...</p>
						<ScopeViewer
							v-else
							:scopes="workerScopeOptions"
							:allow-scopes="assistantForm.worker_allow_scopes"
							:deny-scopes="assistantForm.worker_deny_scopes"
							:editable="true"
							:allow-dynamic="true"
							allow-label="Allow"
							deny-label="Deny"
							dynamic-label="Dynamic"
							@update:scopes="(payload) => {
                assistantForm.worker_allow_scopes = payload.allow || []
                assistantForm.worker_deny_scopes = payload.deny || []
              }"/>
					</div>
					<div class="field">
						<div class="section-head">
							<span>Skills</span>
						</div>
						<div class="stack">
							<label
								v-for="skill in skills"
								:key="skill.id"
								class="row">
								<input
									v-model="assistantForm.skill_ids"
									type="checkbox"
									:value="skill.id"/>
								<span>{{ skill.name }}</span>
							</label>
							<p v-if="skills.length === 0" class="empty">No skills yet.</p>
						</div>
					</div>
					<div class="field">
						<div class="section-head">
							<span>Rules</span>
						</div>
						<div class="stack">
							<label
								v-for="rule in tenantRules"
								:key="rule.id"
								class="row">
								<input
									v-model="assistantForm.rule_ids"
									type="checkbox"
									:value="rule.id"/>
								<span>{{ rule.name }}</span>
							</label>
							<p v-if="tenantRules.length === 0" class="empty">No rules yet.</p>
						</div>
					</div>
					<p v-if="assistantError" class="form-error">{{ assistantError }}</p>
					<div class="row-actions">
						<button
							v-if="assistantEditId"
							class="control size-m ghost danger"
							type="button"
							@click="requestDeleteAssistantFromPane">Delete</button>
						<button
							class="control size-m secondary"
							type="button"
							:disabled="assistantSaving"
							@click="saveAssistant">{{ assistantSaving ? 'Saving…' : 'Save' }}</button>
						<button
							class="control size-m ghost"
							type="button"
							@click="assistantModalOpen = false">Cancel</button>
					</div>
				</div>
			</div>
		</div>
		<ConfirmModal
			v-model:open="deleteAssistantOpen"
			title="Delete assistant"
			:message="assistantToDelete?.name ? `Delete assistant '${assistantToDelete.name}'? This cannot be undone.` : 'Delete this assistant? This cannot be undone.'"
			confirm-label="Delete"
			:destructive="true"
			@confirm="confirmDeleteAssistant"
			@cancel="cancelDeleteAssistant"/>
		<div
			v-if="manageImportsOpen"
			class="sheet-backdrop"
			@click.self="closeManageImports">
			<div class="sidepane">
				<button
					class="control size-s ghost icon-button icon-ghost sidepane-close"
					type="button"
					aria-label="Close"
					@click="closeManageImports">
					<svg
						viewBox="0 0 24 24"
						aria-hidden="true"
						focusable="false">
						<path
							fill="currentColor"
							d="M18.3 5.71a1 1 0 0 0-1.41 0L12 10.59 7.11 5.7a1 1 0 0 0-1.41 1.42L10.59 12l-4.89 4.89a1 1 0 1 0 1.41 1.41L12 13.41l4.89 4.89a1 1 0 0 0 1.41-1.41L13.41 12l4.89-4.89a1 1 0 0 0 0-1.4z"/>
					</svg>
				</button>
				<h2>Manage external frontend imports</h2>
				<div class="stack import-manager-stack">
					<p class="subtle">Configure shared browser libraries for page previews and runtime.</p>
					<p v-if="externalFrontendImportsDraft.length === 0" class="empty import-manager-empty">
						No external frontend imports configured yet. Add one to make a shared browser library available to pages.
					</p>
					<div
						v-for="(entry, index) in externalFrontendImportsDraft"
						:key="index"
						class="import-manager-row">
						<div class="import-manager-grid">
							<label class="field">
								<span>Specifier</span>
								<input
									v-model="entry.specifier"
									type="text"
									placeholder="echarts"/>
							</label>
							<label class="field">
								<span>Version</span>
								<input
									v-model="entry.version"
									type="text"
									placeholder="5.6.0"/>
							</label>
							<label class="field">
								<span>Global name</span>
								<input
									v-model="entry.global_name"
									type="text"
									placeholder="echarts"/>
							</label>
							<label class="field import-manager-source-field">
								<span>Source URL</span>
								<input
									v-model="entry.source_url"
									type="url"
									placeholder="https://cdn.example.com/echarts.min.js"/>
							</label>
							<label class="field import-manager-source-field">
								<span>CSS URLs</span>
								<textarea
									v-model="entry.css_urls_text"
									rows="3"
									placeholder="https://cdn.example.com/theme.css&#10;https://cdn.example.com/extra.css"/>
							</label>
						</div>
						<div class="row-actions import-manager-actions">
							<button
								class="control size-xs ghost danger"
								type="button"
								@click="removeExternalFrontendImport(index)">Remove</button>
						</div>
					</div>
					<p v-if="externalFrontendImportsError" class="form-error">{{ externalFrontendImportsError }}</p>
					<div class="row-actions">
						<button
							class="control size-m ghost"
							type="button"
							@click="addExternalFrontendImport">Add import</button>
						<button
							class="control size-m secondary"
							type="button"
							:disabled="externalFrontendImportsSaving"
							@click="saveExternalFrontendImports">{{ externalFrontendImportsSaving ? 'Saving…' : 'Save imports' }}</button>
						<button
							class="control size-m ghost"
							type="button"
							@click="closeManageImports">Cancel</button>
					</div>
				</div>
			</div>
		</div>
		<div
			v-if="workspaceNameOpen"
			class="sheet-backdrop"
			@click.self="closeWorkspaceNameEditor">
			<div class="sidepane">
				<button
					class="control size-s ghost icon-button icon-ghost sidepane-close"
					type="button"
					aria-label="Close"
					@click="closeWorkspaceNameEditor">
					<svg
						viewBox="0 0 24 24"
						aria-hidden="true"
						focusable="false">
						<path
							fill="currentColor"
							d="M18.3 5.71a1 1 0 0 0-1.41 0L12 10.59 7.11 5.7a1 1 0 0 0-1.41 1.42L10.59 12l-4.89 4.89a1 1 0 1 0 1.41 1.41L12 13.41l4.89 4.89a1 1 0 0 0 1.41-1.41L13.41 12l4.89-4.89a1 1 0 0 0 0-1.4z"/>
					</svg>
				</button>
				<h2>Edit workspace name</h2>
				<div class="stack">
					<label class="field">
						<span>Name</span>
						<input
							v-model="workspaceNameDraft"
							type="text"
							placeholder="Workspace name"/>
					</label>
					<p v-if="workspaceNameError" class="form-error">{{ workspaceNameError }}</p>
					<div class="row-actions">
						<button
							class="control size-m secondary"
							type="button"
							:disabled="workspaceNameSaving"
							@click="saveWorkspaceName">{{ workspaceNameSaving ? 'Saving…' : 'Save' }}</button>
						<button
							class="control size-m ghost"
							type="button"
							@click="closeWorkspaceNameEditor">Cancel</button>
					</div>
				</div>
			</div>
		</div>
		<ConfirmModal
			v-model:open="deleteSkillOpen"
			title="Delete skill"
			:message="deleteSkillMessage"
			confirm-label="Delete"
			cancel-label="Cancel"
			@confirm="confirmDeleteSkill"
			@cancel="cancelDeleteSkill"/>
		<div
			v-if="skillModal"
			class="sheet-backdrop"
			@click.self="skillModal = false">
			<div class="sidepane">
				<button
					class="control size-s ghost icon-button icon-ghost sidepane-close"
					type="button"
					aria-label="Close"
					@click="skillModal = false">
					<svg
						viewBox="0 0 24 24"
						aria-hidden="true"
						focusable="false">
						<path
							fill="currentColor"
							d="M18.3 5.71a1 1 0 0 0-1.41 0L12 10.59 7.11 5.7a1 1 0 0 0-1.41 1.42L10.59 12l-4.89 4.89a1 1 0 1 0 1.41 1.41L12 13.41l4.89 4.89a1 1 0 0 0 1.41-1.41L13.41 12l4.89-4.89a1 1 0 0 0 0-1.4z"/>
					</svg>
				</button>
				<h2>{{ skillEditId ? 'Edit skill' : 'Create skill' }}</h2>
				<div class="stack">
					<label class="field">
						<span>Name</span>
						<input
							v-model="skillForm.name"
							type="text"
							placeholder="Skill name"/>
					</label>
					<label class="field">
						<span>Trigger</span>
						<input
							v-model="skillForm.trigger"
							type="text"
							placeholder="When should it activate?"/>
					</label>
					<label class="field">
						<span>Description</span>
						<textarea
							v-model="skillForm.description"
							rows="4"
							placeholder="What does it do?"/>
					</label>
					<div class="inline-switches">
						<label class="switch">
							<input v-model="skillForm.always_included" type="checkbox"/>
							<span>Always available</span>
						</label>
					</div>
					<label class="field">
						<span>Prompt</span>
						<textarea
							v-model="skillForm.prompt_text"
							rows="15"
							placeholder="Full instructions for this skill"/>
					</label>
					<div class="row-actions">
						<button
							class="control size-m secondary"
							type="button"
							@click="saveSkill">Save</button>
						<button
							class="control size-m ghost"
							type="button"
							@click="skillModal = false">Cancel</button>
					</div>
				</div>
			</div>
		</div>
		<div
			v-if="tagModal"
			class="sheet-backdrop"
			@click.self="tagModal = false">
			<div class="sidepane">
				<button
					class="control size-s ghost icon-button icon-ghost sidepane-close"
					type="button"
					aria-label="Close"
					@click="tagModal = false">
					<svg
						viewBox="0 0 24 24"
						aria-hidden="true"
						focusable="false">
						<path
							fill="currentColor"
							d="M18.3 5.71a1 1 0 0 0-1.41 0L12 10.59 7.11 5.7a1 1 0 0 0-1.41 1.42L10.59 12l-4.89 4.89a1 1 0 1 0 1.41 1.41L12 13.41l4.89 4.89a1 1 0 0 0 1.41-1.41L13.41 12l4.89-4.89a1 1 0 0 0 0-1.4z"/>
					</svg>
				</button>
				<h2>Add tag category</h2>
				<div class="stack">
					<label class="field">
						<span>Name</span>
						<input
							v-model="tagForm.name"
							type="text"
							placeholder="Environment"/>
					</label>
					<label class="field">
						<span>Slug</span>
						<input
							v-model="tagForm.slug"
							type="text"
							:placeholder="tagCategorySlugPlaceholder || 'environment'"/>
					</label>
					<label class="field">
						<span>Priority</span>
						<input v-model="tagForm.priority" type="number"/>
					</label>
					<div class="row-actions">
						<button
							class="control size-m secondary"
							type="button"
							@click="saveTag">Save category</button>
						<button
							class="control size-m ghost"
							type="button"
							@click="tagModal = false">Cancel</button>
					</div>
				</div>
			</div>
		</div>
		<div
			v-if="tagEditOpen"
			class="sheet-backdrop"
			@click.self="tagEditOpen = false">
			<div class="sidepane tag-editor-pane">
				<button
					class="control size-s ghost icon-button icon-ghost sidepane-close"
					type="button"
					aria-label="Close"
					@click="tagEditOpen = false">
					<svg
						viewBox="0 0 24 24"
						aria-hidden="true"
						focusable="false">
						<path
							fill="currentColor"
							d="M18.3 5.71a1 1 0 0 0-1.41 0L12 10.59 7.11 5.7a1 1 0 0 0-1.41 1.42L10.59 12l-4.89 4.89a1 1 0 1 0 1.41 1.41L12 13.41l4.89 4.89a1 1 0 0 0 1.41-1.41L13.41 12l4.89-4.89a1 1 0 0 0 0-1.4z"/>
					</svg>
				</button>
				<h2>Edit tag category</h2>
				<div class="stack">
					<label class="field">
						<span>Category</span>
						<select v-model="tagEditForm.id">
							<option value="">Select category</option>
							<option
								v-for="tag in sortedTagCategories"
								:key="tag.id"
								:value="tag.id">{{ tag.name }}</option>
						</select>
					</label>
					<template v-if="tagEditForm.id">
						<label class="field">
							<span>Name</span>
							<input v-model="tagEditForm.name" type="text"/>
						</label>
						<label class="field">
							<span>Slug</span>
							<input
								v-model="tagEditForm.slug"
								type="text"
								disabled/>
						</label>
						<label class="field">
							<span>Priority</span>
							<input v-model="tagEditForm.priority" type="number"/>
						</label>
						<div class="row-actions">
							<button
								class="control size-m secondary"
								type="button"
								@click="saveTagEdit">Save category</button>
							<button
								class="control size-m ghost danger"
								type="button"
								@click="requestDeleteTagFromEdit">Delete category</button>
						</div>
						<hr/>
						<div class="section-head">
							<h3>Values</h3>
							<button
								class="control size-s secondary"
								type="button"
								@click="openTagValueCreate">Add entry</button>
						</div>
						<div class="tenant-list">
							<div
								v-for="value in (sortedTagCategories.find((item) => item.id === tagEditForm.id)?.values || [])"
								:key="value.id"
								class="list-row">
								<div>
									<strong>{{ value.name }}</strong>
									<div class="tag-row">
										<span class="control size-xs pill">{{ value.slug }}</span>
										<span v-if="value.deleted" class="control size-xs pill warning">Deleted</span>
									</div>
								</div>
								<div class="row-actions">
									<button
										class="control size-xs ghost"
										type="button"
										@click="openTagValueEdit(value)">Edit</button>
									<button
										class="control size-xs ghost danger"
										type="button"
										@click="requestDeleteTagValue(tags.find((item) => item.id === tagEditForm.id), value)">Delete</button>
								</div>
							</div>
						</div>
						<div class="row-actions">
							<button
								class="control size-m ghost"
								type="button"
								@click="tagEditOpen = false">Close</button>
						</div>
						<div v-if="tagValueFormOpen" class="sidepane-layer">
							<div class="sidepane-layer-header">
								<h3>{{ tagValueFormMode === 'edit' ? 'Edit entry' : 'Add entry' }}</h3>
								<button
									class="control size-s ghost"
									type="button"
									@click="closeTagValueForm">Back</button>
							</div>
							<div class="stack">
								<label class="field">
									<span>Name</span>
									<input
										v-if="tagValueFormMode === 'edit'"
										v-model="tagValueEditForm.name"
										type="text"
										placeholder="Staging"/>
									<input
										v-else
										v-model="tagValueForm.name"
										type="text"
										placeholder="Staging"/>
								</label>
								<label class="field">
									<span>Slug</span>
									<input
										v-if="tagValueFormMode === 'edit'"
										v-model="tagValueEditForm.slug"
										type="text"
										disabled/>
									<input
										v-else
										v-model="tagValueForm.slug"
										type="text"
										:placeholder="tagValueSlugPlaceholder || 'staging'"/>
								</label>
								<label class="field">
									<span>Priority</span>
									<input
										v-if="tagValueFormMode === 'edit'"
										v-model="tagValueEditForm.priority"
										type="number"/>
									<input
										v-else
										v-model="tagValueForm.priority"
										type="number"/>
								</label>
								<div class="row-actions">
									<button
										v-if="tagValueFormMode === 'edit'"
										class="control size-m secondary"
										type="button"
										@click="saveTagValueEdit">Save entry</button>
									<button
										v-else
										class="control size-m secondary"
										type="button"
										@click="saveTagValue">Add entry</button>
									<button
										class="control size-m ghost"
										type="button"
										@click="closeTagValueForm">Cancel</button>
								</div>
							</div>
						</div>
					</template>
				</div>
			</div>
		</div>
		<div
			v-if="promptTemplateOpen"
			class="sheet-backdrop"
			@click.self="promptTemplateOpen = false">
			<div class="sidepane">
				<button
					class="control size-s ghost icon-button icon-ghost sidepane-close"
					type="button"
					aria-label="Close"
					@click="promptTemplateOpen = false">
					<svg
						viewBox="0 0 24 24"
						aria-hidden="true"
						focusable="false">
						<path
							fill="currentColor"
							d="M18.3 5.71a1 1 0 0 0-1.41 0L12 10.59 7.11 5.7a1 1 0 0 0-1.41 1.42L10.59 12l-4.89 4.89a1 1 0 1 0 1.41 1.41L12 13.41l4.89 4.89a1 1 0 0 0 1.41-1.41L13.41 12l4.89-4.89a1 1 0 0 0 0-1.4z"/>
					</svg>
				</button>
				<h2>Edit prompt template</h2>
				<div class="stack">
					<label class="field">
						<span>Section</span>
						<input
							:value="promptTemplateForm.section"
							type="text"
							disabled/>
					</label>
					<label class="field">
						<span>Template</span>
						<textarea
							v-model="promptTemplateForm.content"
							rows="12"
							:placeholder="promptTemplateFallback"></textarea>
					</label>
					<div class="field">
						<span>Available variables</span>
						<div class="subtle" v-pre>
							<p><strong>{{ persona_prompt }}</strong>Persona prompt text.</p>
							<p><strong>{{ assistant_prompt }}</strong>Assistant prompt text.</p>
							<p><strong>{{ core_rules }}</strong>Compiled rules, already separated with "--".</p>
							<p><strong>{{ task }}</strong>AI node goal (only for AI nodes).</p>
							<p><strong>{{ context_json }}</strong>AI node state inputs as JSON (only for AI nodes).</p>
							<p><strong>{{ output_schema }}</strong>JSON schema (only when provided).</p>
							<p><strong>{{ skills_tools_enabled }}</strong>True when skill tools are allowed.</p>
							<p>
								<strong>{{ available_skills }}</strong>
								Array of skills with fields:
								<strong>name</strong>
								,
								<strong>description</strong>
								,
								<strong>trigger</strong>
								,
								<strong>prompt</strong>
								.
							</p>
							<p>
								<strong>{{ active_skill }}</strong>
								Active skill object with fields:
								<strong>name</strong>
								,
								<strong>description</strong>
								,
								<strong>trigger</strong>
								,
								<strong>prompt</strong>
								.
							</p>
							<p>
								<strong>{{ activate_mcp_tool_name }}</strong>
								Tool name used to activate an MCP server for the session.
							</p>
							<p>
								<strong>{{ available_mcp_servers }}</strong>
								Array of available MCP servers with fields:
								<strong>name</strong>
								,
								<strong>instructions</strong>
								.
							</p>
							<p>
								<strong>{{ active_mcp_servers }}</strong>
								Array of active MCP servers with fields:
								<strong>name</strong>
								,
								<strong>prompt</strong>
								.
							</p>
						</div>
					</div>
					<label class="switch">
						<input v-model="promptTemplateForm.enabled" type="checkbox"/>
						<span>Enabled</span>
					</label>
					<div class="row-actions">
						<button
							class="control size-m ghost"
							type="button"
							:disabled="promptTemplateSaving || !promptTemplateFallback"
							@click="applyPromptTemplateFallback">Start from default</button>
						<button
							class="control size-m secondary"
							type="button"
							:disabled="promptTemplateSaving"
							@click="savePromptTemplate">Save</button>
						<button
							class="control size-m ghost"
							type="button"
							@click="promptTemplateOpen = false">Cancel</button>
					</div>
				</div>
			</div>
		</div>
		<div
			v-if="policyServer"
			class="sheet-backdrop"
			@click.self="policyServer = null">
			<div class="sidepane">
				<button
					class="control size-s ghost icon-button icon-ghost sidepane-close"
					type="button"
					aria-label="Close"
					@click="policyServer = null">
					<svg
						viewBox="0 0 24 24"
						aria-hidden="true"
						focusable="false">
						<path
							fill="currentColor"
							d="M18.3 5.71a1 1 0 0 0-1.41 0L12 10.59 7.11 5.7a1 1 0 0 0-1.41 1.42L10.59 12l-4.89 4.89a1 1 0 1 0 1.41 1.41L12 13.41l4.89 4.89a1 1 0 0 0 1.41-1.41L13.41 12l4.89-4.89a1 1 0 0 0 0-1.4z"/>
					</svg>
				</button>
				<h2>{{ policyEditId ? 'Edit policy' : 'Add policy' }}</h2>
				<div class="stack">
					<label class="field">
						<span>Tag category</span>
						<select v-model="policyForm.tag_category_id">
							<option value="">Select category</option>
							<option
								v-for="category in sortedTagCategories"
								:key="category.id"
								:value="category.id">{{ category.name }}</option>
						</select>
					</label>
					<label v-if="policyForm.tag_category_id" class="field">
						<span>Tag value</span>
						<select v-model="policyForm.tag_id">
							<option value="">Select value</option>
							<option
								v-for="tagValue in ((tags.find((category) => category.id === policyForm.tag_category_id)?.values) || []).filter((value) => !value.deleted)"
								:key="tagValue.id"
								:value="tagValue.id">{{ tagValue.name }}</option>
						</select>
					</label>
					<div class="field">
						<div class="section-head">
							<span>Policy</span>
							<button
								v-if="policySchema"
								class="control size-s ghost"
								type="button"
								@click="openLargeEditor('policy')">Open large editor</button>
						</div>
						<JsonSchemaEditor
							v-if="policySchema"
							class="compact-editor"
							v-model="policyForm.policy_value"
							:schema="policySchema"/>
						<p v-else class="subtle">No policy schema available for this server.</p>
					</div>
					<div class="row-actions">
						<button
							class="control size-m secondary"
							type="button"
							@click="savePolicy">Save policy</button>
						<button
							class="control size-m ghost"
							type="button"
							@click="policyServer = null">Close</button>
						<button
							v-if="policyEditId"
							class="control size-m ghost danger"
							type="button"
							@click="requestDeletePolicy(policyToDelete)">Delete</button>
					</div>
				</div>
			</div>
		</div>
		<div
			v-if="overrideServer"
			class="sheet-backdrop"
			@click.self="overrideServer = null">
			<div class="sidepane">
				<button
					class="control size-s ghost icon-button icon-ghost sidepane-close"
					type="button"
					aria-label="Close"
					@click="overrideServer = null">
					<svg
						viewBox="0 0 24 24"
						aria-hidden="true"
						focusable="false">
						<path
							fill="currentColor"
							d="M18.3 5.71a1 1 0 0 0-1.41 0L12 10.59 7.11 5.7a1 1 0 0 0-1.41 1.42L10.59 12l-4.89 4.89a1 1 0 1 0 1.41 1.41L12 13.41l4.89 4.89a1 1 0 0 0 1.41-1.41L13.41 12l4.89-4.89a1 1 0 0 0 0-1.4z"/>
					</svg>
				</button>
				<h2>{{ overrideEditId ? 'Edit variant' : 'Add variant' }}</h2>
				<div class="stack">
					<label class="field">
						<span>Tag category</span>
						<select v-model="overrideForm.tag_category_id">
							<option value="">Select category</option>
							<option
								v-for="category in sortedTagCategories"
								:key="category.id"
								:value="category.id">{{ category.name }}</option>
						</select>
					</label>
					<label v-if="overrideForm.tag_category_id" class="field">
						<span>Tag value</span>
						<select v-model="overrideForm.tag_id">
							<option value="">Select value</option>
							<option
								v-for="tagValue in ((tags.find((category) => category.id === overrideForm.tag_category_id)?.values) || []).filter((value) => !value.deleted)"
								:key="tagValue.id"
								:value="tagValue.id">{{ tagValue.name }}</option>
						</select>
					</label>
					<label class="field">
						<span>Logical name</span>
						<input
							v-model="overrideForm.logical_name"
							type="text"
							placeholder="Prod endpoint"/>
					</label>
					<label class="field">
						<span>HTTP URL</span>
						<input
							v-model="overrideForm.http_url"
							type="text"
							placeholder="https://host/mcp"/>
					</label>
					<label class="field">
						<span>Headers</span>
						<textarea
							v-model="overrideForm.headers"
							rows="4"
							placeholder="Header=Value"></textarea>
					</label>
					<label class="field">
						<span>Override SSH enabled</span>
						<select v-model="overrideForm.ssh_enabled">
							<option value="">Inherit</option>
							<option value="true">Enable</option>
							<option value="false">Disable</option>
						</select>
					</label>
					<template v-if="overrideForm.ssh_enabled === 'true'">
						<label class="field">
							<span>SSH server</span>
							<input
								v-model="overrideForm.ssh_tunnel.server"
								type="text"
								placeholder="bastion.example.com"/>
						</label>
						<label class="field">
							<span>SSH port</span>
							<input
								v-model="overrideForm.ssh_tunnel.port"
								type="number"
								min="1"
								placeholder="22"/>
						</label>
						<label class="field">
							<span>SSH username</span>
							<input
								v-model="overrideForm.ssh_tunnel.username"
								type="text"
								placeholder="ubuntu"/>
						</label>
						<label class="field">
							<span>Password</span>
							<input
								v-model="overrideForm.ssh_tunnel.password"
								type="password"
								placeholder="Optional"/>
						</label>
						<label class="field">
							<span>Key path</span>
							<input
								v-model="overrideForm.ssh_tunnel.key_path"
								type="text"
								placeholder="/home/user/.ssh/id_rsa"/>
						</label>
						<label class="field">
							<span>Private key</span>
							<textarea
								v-model="overrideForm.ssh_tunnel.key"
								rows="6"
								placeholder="Paste private key"></textarea>
						</label>
					</template>
					<div v-if="overrideServer?.oauth_enabled" class="field">
						<div class="section-head">
							<span>OAuth override</span>
						</div>
						<label class="field">
							<span>Authentication mode</span>
							<label class="switch">
								<input v-model="overrideForm.auth.global" type="checkbox"/>
								<span>{{ overrideForm.auth.global ? 'Global' : 'Personalized' }}</span>
							</label>
						</label>
						<label class="field">
							<span>Resource metadata URL</span>
							<input
								v-model="overrideForm.auth.resource_metadata_url"
								type="text"
								placeholder="https://server/.well-known/oauth-protected-resource"/>
						</label>
						<label class="field">
							<span>Well-known URL</span>
							<input
								v-model="overrideForm.auth.well_known_url"
								type="text"
								placeholder="https://issuer/.well-known/openid-configuration"/>
						</label>
						<label class="field">
							<span>Issuer</span>
							<input
								v-model="overrideForm.auth.issuer"
								type="text"
								placeholder="https://issuer"/>
						</label>
						<label class="field">
							<span>Authorization endpoint</span>
							<input
								v-model="overrideForm.auth.authorization_endpoint"
								type="text"
								placeholder="https://issuer/oauth/authorize"/>
						</label>
						<label class="field">
							<span>Token endpoint</span>
							<input
								v-model="overrideForm.auth.token_endpoint"
								type="text"
								placeholder="https://issuer/oauth/token"/>
						</label>
						<label class="field">
							<span>Registration endpoint</span>
							<input
								v-model="overrideForm.auth.registration_endpoint"
								type="text"
								placeholder="https://issuer/oauth/register"/>
						</label>
						<label class="field">
							<span>Scopes</span>
							<input
								v-model="overrideForm.auth.scopes"
								type="text"
								placeholder="openid profile"/>
						</label>
						<label class="field">
							<span>Client ID</span>
							<input
								v-model="overrideForm.auth.client_id"
								type="text"
								placeholder="Client ID"/>
						</label>
						<label class="field">
							<span>Client secret</span>
							<input
								v-model="overrideForm.auth.client_secret"
								type="password"
								placeholder="Client secret"/>
						</label>
					</div>
					<div class="row-actions">
						<button
							class="control size-m secondary"
							type="button"
							@click="saveOverride">Save variant</button>
						<button
							class="control size-m ghost"
							type="button"
							@click="overrideServer = null">Close</button>
						<button
							v-if="overrideEditId"
							class="control size-m ghost danger"
							type="button"
							@click="requestDeleteOverride(overrideToDelete)">Delete</button>
					</div>
				</div>
			</div>
		</div>
		<div
			v-if="largeEditorOpen"
			class="modal-backdrop"
			@click.self="largeEditorOpen = false">
			<div class="modal-card wide">
				<div class="section-head">
					<h3>{{ largeEditorMode === 'policy' ? 'Policy editor' : 'Configuration editor' }}</h3>
					<button
						class="control size-m ghost"
						type="button"
						@click="largeEditorOpen = false">Close</button>
				</div>
				<JsonSchemaEditor
					v-if="largeEditorSchema"
					class="large-editor"
					v-model="largeEditorValue"
					:schema="largeEditorSchema"
					:default-collapsed="false"/>
				<p v-else class="subtle">No schema available.</p>
			</div>
		</div>
		<div
			v-if="definitionOpen"
			class="modal-backdrop"
			@click.self="definitionOpen = false">
			<div class="modal-card wide">
				<div class="section-head">
					<h3>MCP definition</h3>
					<div class="row-actions">
						<button
							class="control size-m ghost"
							type="button"
							:disabled="!definitionPayload"
							@click="copyServerDefinition">Copy</button>
						<button
							class="control size-m ghost"
							type="button"
							@click="definitionOpen = false">Close</button>
					</div>
				</div>
				<p v-if="definitionLoading" class="subtle">Loading definition…</p>
				<p v-else-if="definitionError" class="form-error">{{ definitionError }}</p>
				<pre v-else class="code-block">{{ definitionPayload }}</pre>
			</div>
		</div>
		<div
			v-if="serverImportOpen"
			class="modal-backdrop"
			@click.self="serverImportOpen = false">
			<div class="modal-card wide">
				<div class="section-head">
					<h3>Import MCP server</h3>
					<div class="row-actions">
						<button
							class="control size-m ghost"
							type="button"
							@click="serverImportOpen = false">Close</button>
					</div>
				</div>
				<p class="subtle">Paste a server configuration JSON blob to create a new MCP server.</p>
				<label class="field">
					<span>Configuration JSON</span>
					<textarea
						v-model="serverImportPayload"
						rows="12"
						placeholder="{...}"></textarea>
				</label>
				<p v-if="serverImportError" class="form-error">{{ serverImportError }}</p>
				<div class="row-actions">
					<button
						class="control size-m secondary"
						type="button"
						@click="applyImportPayload">Use configuration</button>
					<button
						class="control size-m ghost"
						type="button"
						@click="serverImportOpen = false">Cancel</button>
				</div>
			</div>
		</div>
		<div
			v-if="pageModalOpen"
			class="modal-backdrop"
			@click.self="pageModalOpen = false">
			<div class="modal-card">
				<div class="section-head">
					<h3>Create page</h3>
				</div>
				<div class="stack">
					<label class="field">
						<span>Name</span>
						<input v-model="pageForm.name" type="text"/>
					</label>
					<label class="field">
						<span>Description</span>
						<input v-model="pageForm.description" type="text"/>
					</label>
					<label class="field">
						<span>Namespace</span>
						<input
							v-model="pageForm.namespace"
							type="text"
							placeholder="root"/>
					</label>
					<label class="field">
						<span>Type</span>
						<select v-model="pageForm.type">
							<option value="PAGE">Page</option>
							<option value="COMPONENT">Component</option>
						</select>
					</label>
					<label v-if="pageForm.type === 'PAGE'" class="field">
						<span>Menu label</span>
						<input
							v-model="pageForm.label"
							type="text"
							placeholder="Optional nicer menu name"/>
					</label>
					<label v-if="pageForm.type === 'PAGE'" class="field">
						<span>Show in menu</span>
						<label class="switch">
							<input v-model="pageForm.menu_visible" type="checkbox"/>
							<span>{{ pageForm.menu_visible ? 'Visible' : 'Hidden' }}</span>
						</label>
					</label>
					<label v-if="pageForm.type === 'PAGE'" class="field">
						<span>Route suffix</span>
						<input
							v-model="pageForm.route_suffix"
							type="text"
							placeholder="/{id}"/>
					</label>
					<p v-if="pageError" class="form-error">{{ pageError }}</p>
					<div class="row-actions">
						<button
							class="control size-m secondary"
							type="button"
							:disabled="pageSaving"
							@click="savePage">{{ pageSaving ? 'Saving…' : 'Create' }}</button>
						<button
							class="control size-m ghost"
							type="button"
							@click="pageModalOpen = false">Cancel</button>
					</div>
				</div>
			</div>
		</div>
		<div
			v-if="scriptModalOpen"
			class="sheet-backdrop"
			@click.self="scriptModalOpen = false">
			<div class="sidepane">
				<button
					class="control size-s ghost icon-button icon-ghost sidepane-close"
					type="button"
					aria-label="Close"
					@click="scriptModalOpen = false">
					<svg
						viewBox="0 0 24 24"
						aria-hidden="true"
						focusable="false">
						<path
							fill="currentColor"
							d="M18.3 5.71 12 12l6.3 6.29-1.41 1.41L10.59 13.41 4.29 19.7 2.88 18.29 9.17 12 2.88 5.71 4.29 4.3l6.3 6.29 6.29-6.3z"/>
					</svg>
				</button>
				<div class="section-head">
					<h3>{{ scriptEditId ? 'Edit script metadata' : 'Create script' }}</h3>
				</div>
				<div class="stack">
					<label class="field">
						<span>Name</span>
						<input
							v-model="scriptForm.name"
							type="text"
							pattern="[A-Za-z0-9 .()\-]+"/>
					</label>
					<label class="field">
						<span>Description</span>
						<textarea v-model="scriptForm.description" rows="4"></textarea>
					</label>
					<label class="field">
						<span>Folder</span>
						<input
							:value="scriptForm.namespace || 'root'"
							type="text"
							disabled/>
					</label>
					<p class="subtle">Use filesystem-safe characters only. Slug will be generated from folder and name.</p>
					<label v-if="scriptForm.type !== 'WORKFLOW'" class="field">
						<span>Type</span>
						<select v-model="scriptForm.type">
							<option value="STANDALONE">Standalone</option>
							<option value="UTILITY">Utility</option>
							<option value="TEST">Test</option>
						</select>
					</label>
					<div v-if="scriptForm.type === 'STANDALONE'" class="field">
						<div class="section-head">
							<span>Schedule</span>
						</div>
						<label class="switch">
							<input v-model="scriptForm.scheduled" type="checkbox"/>
							<span>Scheduled</span>
						</label>
						<div v-if="scriptForm.scheduled" class="stack">
							<label class="field">
								<span>Timezone</span>
								<input
									v-model="scriptForm.schedule_timezone"
									type="text"
									placeholder="UTC"/>
							</label>
							<label class="field">
								<span>Start</span>
								<input v-model="scriptForm.schedule_start_at" type="datetime-local"/>
							</label>
							<label class="field">
								<span>End</span>
								<input v-model="scriptForm.schedule_end_at" type="datetime-local"/>
							</label>
							<label class="field">
								<span>RRULE</span>
								<input
									v-model="scriptForm.schedule_rrule"
									type="text"
									placeholder="FREQ=DAILY;INTERVAL=1"/>
							</label>
						</div>
					</div>
					<div v-if="scriptForm.type === 'STANDALONE'" class="field">
						<div class="section-head">
							<span>Tool hook</span>
						</div>
						<label class="switch">
							<input v-model="scriptForm.tool_hook_enabled" type="checkbox"/>
							<span>Enable tool hook</span>
						</label>
						<div v-if="scriptForm.tool_hook_enabled" class="stack">
							<label class="field">
								<span>Phase</span>
								<select v-model="scriptForm.tool_hook_phase">
									<option value="BEFORE">Before</option>
									<option value="AFTER">After</option>
								</select>
							</label>
							<label class="field">
								<span>Tool names</span>
								<textarea
									v-model="scriptToolNamesInput"
									rows="4"
									placeholder="one.tool.name per line"></textarea>
							</label>
						</div>
					</div>
					<p v-if="scriptError" class="form-error">{{ scriptError }}</p>
					<div class="row-actions">
						<button
							class="control size-m secondary"
							type="button"
							:disabled="scriptSaving"
							@click="saveScript">{{ scriptSaving ? 'Saving…' : (scriptEditId ? 'Save' : 'Create') }}</button>
						<button
							class="control size-m ghost"
							type="button"
							@click="scriptModalOpen = false">Cancel</button>
					</div>
				</div>
			</div>
		</div>
		<div
			v-if="scriptFolderModalOpen"
			class="modal-backdrop"
			@click.self="scriptFolderModalOpen = false">
			<div class="modal-card">
				<div class="section-head">
					<h3>{{ selectedScriptFolderPath ? 'Add folder' : 'Add root folder' }}</h3>
				</div>
				<div class="stack">
					<p class="subtle">Parent: {{ selectedScriptFolderPath || 'root' }}</p>
					<label class="field">
						<span>Folder name</span>
						<input
							v-model="scriptFolderName"
							type="text"
							placeholder="new-folder"/>
					</label>
					<p v-if="scriptFolderError" class="form-error">{{ scriptFolderError }}</p>
					<div class="row-actions">
						<button
							class="control size-m secondary"
							type="button"
							@click="createScriptFolder">Create folder</button>
						<button
							class="control size-m ghost"
							type="button"
							@click="scriptFolderModalOpen = false">Cancel</button>
					</div>
				</div>
			</div>
		</div>
		<div
			v-if="pageImportOpen"
			class="modal-backdrop"
			@click.self="pageImportOpen = false">
			<div class="modal-card wide">
				<div class="section-head">
					<h3>Import page</h3>
					<div class="row-actions">
						<button
							class="control size-m ghost"
							type="button"
							@click="pageImportOpen = false">Close</button>
					</div>
				</div>
				<p class="subtle">Paste a page JSON payload to create a new page.</p>
				<label class="field checkbox">
					<input v-model="pageImportUpdateSlugs" type="checkbox"/>
					<span>Update slugs to keep them unique</span>
				</label>
				<label class="field">
					<span>Page JSON</span>
					<textarea
						v-model="pageImportPayload"
						rows="12"
						placeholder="{...}"></textarea>
				</label>
				<p v-if="pageImportError" class="form-error">{{ pageImportError }}</p>
				<div class="row-actions">
					<button
						class="control size-m secondary"
						type="button"
						@click="applyPageImportPayload">Import page</button>
					<button
						class="control size-m ghost"
						type="button"
						@click="pageImportOpen = false">Cancel</button>
				</div>
			</div>
		</div>
	</main>
</template>
<style scoped>
.channel-modal-card {
	width: min(95vw, 1280px);
	max-width: min(95vw, 1280px);
}

.channel-form-layout {
	display: grid;
	grid-template-columns: minmax(0, 1fr) minmax(0, 1.2fr);
	gap: 1.25rem;
	align-items: start;
}

.channel-form-column {
	display: flex;
	flex-direction: column;
	gap: 1rem;
	min-width: 0;
}

.channel-prompt-field textarea {
	min-height: 14rem;
}

.channel-grid {
	display: grid;
	gap: 0.875rem;
}

.tag-grid {
	grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
}

.mcp-grid {
	grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
}

.channel-card {
	display: flex;
	flex-direction: column;
	gap: 0.75rem;
	padding: 0.875rem;
	border: 1px solid var(--color-border-muted, rgba(255, 255, 255, 0.08));
	border-radius: 0.85rem;
	background: var(--color-surface-soft, rgba(255, 255, 255, 0.02));
	min-width: 0;
}

.channel-card-title {
	line-height: 1.25;
}

.channel-card-copy {
	margin: 0;
}

.channel-card-options {
	display: flex;
	flex-direction: column;
	gap: 0.55rem;
}

.channel-check-row {
	display: grid;
	grid-template-columns: auto minmax(0, 1fr);
	align-items: start;
	gap: 0.625rem;
}

.channel-check-row input {
	margin-top: 0.2rem;
}

.channel-form-error {
	grid-column: 1 / -1;
	margin: 0;
}

.tree-search {
	display: flex;
	align-items: center;
	gap: 0.5rem;
	margin: 0.5rem 0 0.75rem;
}

.mcp-server-toolbar {
	flex-wrap: wrap;
	justify-content: space-between;
}

.mcp-server-count {
	margin-left: auto;
}

.mcp-server-pager {
	align-items: center;
}

.tree-list {
	display: flex;
	flex-direction: column;
	gap: 0.4rem;
}

.tree-row {
	--depth: 0;
}

.tree-row.list-row {
	padding-left: calc(var(--depth) * 1.1rem);
}

.tree-row.list-row > :first-child {
	min-width: 0;
	flex: 1;
}

.folder-row {
	display: flex;
	align-items: center;
	gap: 0.5rem;
	padding: 0.45rem 0.6rem;
	padding-left: calc((var(--depth) * 1.1rem) + 0.1rem);
	border-radius: 0.65rem;
	background: var(--color-surface-soft, rgba(255, 255, 255, 0.03));
	border: 1px solid var(--color-border-muted, rgba(255, 255, 255, 0.08));
}

.tree-toggle {
	width: 1.6rem;
	height: 1.6rem;
	display: inline-flex;
	align-items: center;
	justify-content: center;
	border: none;
	border-radius: 0.5rem;
	background: transparent;
	color: inherit;
	padding: 0;
	cursor: pointer;
}

.tree-toggle svg {
	width: 1.1rem;
	height: 1.1rem;
	transition: transform 0.2s ease;
}

.tree-toggle svg.open {
	transform: rotate(90deg);
}

.tree-label {
	display: flex;
	align-items: center;
	gap: 0.5rem;
}

.tree-folder-button {
	flex: 1;
	display: flex;
	align-items: center;
	padding: 0;
	border: none;
	background: transparent;
	color: inherit;
	text-align: left;
	cursor: pointer;
}

.tree-folder-button.active {
	color: var(--color-accent, #6aa9ff);
}

.tree-count {
	font-size: 0.75rem;
	padding: 0.1rem 0.45rem;
	border-radius: 999px;
	background: var(--color-surface, rgba(255, 255, 255, 0.08));
	color: var(--color-text-muted, rgba(255, 255, 255, 0.7));
}

.tree-row-match {
	border-radius: 0.65rem;
	background: color-mix(in srgb, var(--color-accent, #6aa9ff) 10%, transparent);
	box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--color-accent, #6aa9ff) 35%, transparent);
}

.script-row-actions {
	opacity: 0;
	pointer-events: none;
	transition: opacity 0.16s ease;
}

.list-row:hover .script-row-actions, .list-row:focus-within .script-row-actions {
	opacity: 1;
	pointer-events: auto;
}

.script-explorer {
	display: flex;
	flex-direction: column;
	gap: 0.1rem;
	padding: 0.25rem 0;
}

.script-explorer-row {
	--depth-step: 1rem;
	display: flex;
	align-items: center;
	gap: 0.2rem;
	min-height: 2rem;
	padding-left: calc(var(--depth) * var(--depth-step));
	border-radius: 0.45rem;
}

.script-explorer-row-folder, .script-explorer-row-item {
	background: transparent;
	border: none;
}

.script-explorer-row-selected, .script-explorer-row:hover {
	background: rgba(255, 255, 255, 0.04);
}

.script-explorer-row-match {
	background: color-mix(in srgb, var(--color-accent, #6aa9ff) 10%, transparent);
}

.script-explorer-toggle {
	width: 1rem;
	min-width: 1rem;
	height: 1rem;
	display: inline-flex;
	align-items: center;
	justify-content: center;
	padding: 0;
	border: none;
	background: transparent;
	color: var(--color-text-muted, rgba(255, 255, 255, 0.72));
	cursor: pointer;
}

.script-explorer-toggle svg {
	width: 0.95rem;
	height: 0.95rem;
	transition: transform 0.16s ease;
}

.script-explorer-toggle svg.open {
	transform: rotate(90deg);
}

.script-explorer-main {
	min-width: 0;
	flex: 1;
	display: inline-flex;
	align-items: center;
	gap: 0.45rem;
	padding: 0.2rem 0;
	border: none;
	background: transparent;
	color: inherit;
	text-align: left;
}

button.script-explorer-main {
	cursor: pointer;
}

.script-explorer-file-main {
	padding-left: 1.2rem;
}

.script-explorer-icon {
	width: 1rem;
	height: 1rem;
	color: var(--color-text-muted, rgba(255, 255, 255, 0.75));
	flex: 0 0 auto;
}

.script-explorer-name {
	min-width: 0;
	overflow: hidden;
	text-overflow: ellipsis;
	white-space: nowrap;
}

.script-explorer-count, .script-explorer-badge {
	font-size: 0.72rem;
	line-height: 1;
	padding: 0.15rem 0.35rem;
	border-radius: 999px;
	background: rgba(255, 255, 255, 0.08);
	color: var(--color-text-muted, rgba(255, 255, 255, 0.7));
}

.script-explorer-actions {
	display: inline-flex;
	align-items: center;
	gap: 0.2rem;
	opacity: 0;
	pointer-events: none;
	transition: opacity 0.16s ease;
}

.script-explorer-row:hover .script-explorer-actions,
		.script-explorer-row:focus-within .script-explorer-actions {
	opacity: 1;
	pointer-events: auto;
}

@media (max-width: 1100px) {
	.channel-form-layout {
		grid-template-columns: 1fr;
	}
}

.tag-editor-pane {
	position: relative;
	left: auto;
	right: auto;
	margin-left: auto;
}

.sidepane-layer {
	position: absolute;
	inset: 0;
	z-index: 2;
	display: flex;
	flex-direction: column;
	gap: 1rem;
	padding: 4rem 1.25rem 1.25rem;
	background: var(--color-surface, #111822);
	border-radius: inherit;
	overflow: auto;
}

.sidepane-layer-header {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 1rem;
}

.import-manager-stack {
	gap: 1rem;
}

.import-manager-empty {
	padding: 1rem;
	border: 1px dashed rgba(148, 163, 184, 0.3);
	border-radius: 0.75rem;
	background: rgba(15, 23, 42, 0.2);
}

.variant-list {
	display: grid;
	gap: 0.75rem;
	margin-top: 0.5rem;
}

.variant-card {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 0.875rem;
	padding: 0.9rem 1rem;
	border: 1px solid var(--color-border-muted, rgba(255, 255, 255, 0.08));
	border-radius: 0.85rem;
	background: linear-gradient( 180deg, var(--color-surface-soft, rgba(255, 255, 255, 0.03)), rgba(255, 255, 255, 0.01));
}

.variant-card-copy {
	min-width: 0;
	display: flex;
	flex-direction: column;
	gap: 0.35rem;
}

.variant-card-head {
	display: flex;
	align-items: center;
	gap: 0.5rem;
	flex-wrap: wrap;
}

.variant-card-meta {
	margin: 0;
}

.variant-card-actions {
	flex-shrink: 0;
}

.import-manager-row {
	padding: 1rem;
	border: 1px solid rgba(148, 163, 184, 0.2);
	border-radius: 0.75rem;
	background: rgba(15, 23, 42, 0.35);
}

.import-manager-grid {
	display: grid;
	grid-template-columns: repeat(2, minmax(0, 1fr));
	gap: 0.75rem;
}

.import-manager-source-field {
	grid-column: 1 / -1;
}

.import-manager-actions {
	justify-content: flex-end;
	margin-top: 0.75rem;
}

@media (max-width: 720px) {
	.import-manager-grid {
		grid-template-columns: 1fr;
	}
}
</style>
