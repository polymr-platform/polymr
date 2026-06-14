<script setup>
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import ConfirmModal from '../components/ConfirmModal.vue';
import TenantMenu from '../components/TenantMenu.vue';
import ScopeViewer from '../components/ScopeViewer.vue';
import {
	createAssistant,
	createModel,
	createPersona,
	createRule,
	createSkill,
	createTenant,
	createWorkspace,
	deleteAssistant,
	deleteModel,
	deleteRule,
	deleteSkill,
	deleteWorkspace,
	getAssistants,
	getAssistant,
	getModels,
	getModelProviders,
	getMcpServerTools,
	getMcpServers,
	getPersonas,
	getPromptTemplates,
	getRules,
	getSkills,
	getTenantAutomationTasks,
	getTenantAutomationDefaults,
	getTenants,
	getTenantMembers,
	getWorkspaces,
	loadActiveTenant,
	loadSessionTenants,
	inviteTenantMember,
	saveActiveTenant,
	updateAssistant,
	updateModel,
	updatePersona,
	updateRule,
	updateSessionTenants,
	updateSkill,
	updateTenantAutomationTask,
	updateTenant,
	updateWorkspace
} from '../api';
const tenants = ref([])
const selectedTenant = ref('')
const status = ref('')
let statusTimer = null
const route = useRoute()
const assistants = ref([])
const rules = ref([])
const personas = ref([])
const skills = ref([])
const workspaces = ref([])
const models = ref([])
const modelProviders = ref([])
const tenantMembers = ref([])
const inviteEmail = ref('')
const inviteError = ref('')
const inviteSaving = ref(false)
const inviteOpen = ref(false)
const promptTemplates = ref([])
const promptTemplateOpen = ref(false)
const promptTemplateSaving = ref(false)
const promptTemplateForm = ref({ section: '', content: '', enabled: true })
const promptTemplateFallback = ref('')
const workerScopeOptions = ref([])
const workerScopeLoading = ref(false)
const workerScopeError = ref('')
const automationTasks = ref([])
const automationOpen = ref(false)
const automationSaving = ref(false)
const automationError = ref('')
const automationForm = ref({
	taskType: 'TITLE',
	model_id: '',
	prompt_text: '',
	enabled: true
})
const automationDefaults = ref([])
const automationTaskModelType = (taskType) => (taskType === 'EMBEDDING' ? 'EMBEDDING' : 'CHAT')
const automationModels = computed(() => models.value.filter((model) => model.type === automationTaskModelType(automationForm.value.taskType)))
const renameOpen = ref(false)
const createOpen = ref(false)
const assistantModal = ref(false)
const ruleModal = ref(false)
const personaModal = ref(false)
const skillModal = ref(false)
const workspaceModal = ref(false)
const modelModal = ref(false)
const modelSecretInputs = ref({})
const modelCustomEnum = ref({})
const modelTypeOptions = ['CHAT', 'EMBEDDING']
const modelCreateType = ref('CHAT')
const promptSections = [
	{ key: 'PERSONALITY', label: 'Personality' },
	{ key: 'CORE_RULES', label: 'Core rules' },
	{ key: 'SKILLS', label: 'Skills' },
	{ key: 'FORMATTING', label: 'Formatting' },
	{ key: 'WORKER_AUTONOMY', label: 'Worker autonomy' },
]
const renameTenantName = ref('')
const newTenantName = ref('')
const tenantMaxOutputTokens = ref('')
const assistantForm = ref({
	name: '',
	description: '',
	prompt_text: '',
	persona_id: '',
	model_id: '',
	workspace_id: '',
	max_output_tokens: '',
	skill_ids: [],
	rule_ids: [],
	worker_enabled: false,
	worker_trigger: '',
	worker_allow_scopes: [],
	worker_deny_scopes: []
})
const ruleForm = ref({
	name: '',
	content: '',
	always_included: false,
	enabled: true,
	order: null
})
const personaForm = ref({ name: '', description: '', prompt_text: '' })
const skillForm = ref({
	name: '',
	trigger: '',
	description: '',
	always_included: false,
	prompt_text: ''
})
const workspaceForm = ref({ name: '', description: '' })
const modelForm = ref({
	name: '',
	provider: '',
	type: 'CHAT',
	enabled: true,
	config: {}
})
const modelSelection = ref('')
const modelCustomId = ref('')
const menuCollapsedKey = 'polymr.tenant.menu'
const menuCollapsed = ref(false)
const deleteWorkspaceOpen = ref(false)
const workspaceToDelete = ref(null)
const assistantEditId = ref('')
const ruleEditId = ref('')
const personaEditId = ref('')
const skillEditId = ref('')
const deleteSkillOpen = ref(false)
const skillToDelete = ref(null)
const workspaceEditId = ref('')
const modelEditId = ref('')
const activeTenant = computed(() => tenants.value.find((tenant) => tenant.id === selectedTenant.value))
const activeTenantName = computed(() => activeTenant.value?.name || 'No tenant')
const workspaceName = (id) => workspaces.value.find((ws) => ws.id === id)?.name || 'Tenant'
const optInRules = computed(() => rules.value.filter((rule) => !rule.always_included))
const providerName = (id) => modelProviders.value.find((provider) => provider.id === id)?.name || id
const selectedProvider = computed(() => availableModelProviders.value.find((provider) => provider.id === modelForm.value.provider))
const availableModelProviders = computed(() => modelProviders.value
	.filter((provider) => modelForm.value.type === 'EMBEDDING'
	? provider.supports_embedding_models
	: provider.supports_chat_models))
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
const loadWorkerScopes = async(workspaceId) => {
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
const loadTenantData = async() => {
	if (!selectedTenant.value) {
		assistants.value = []
		rules.value = []
		personas.value = []
		skills.value = []
		workspaces.value = []
		tenantMembers.value = []
		return
	}
	const tenantId = selectedTenant.value
	const [
    assistantsList,
    rulesList,
    personasList,
    skillsList,
    workspacesList,
    modelsList,
    membersList,
    templatesList,
    automationList,
    automationDefaultsList,
  ] = await Promise.all(
		[
			getAssistants(tenantId),
			getRules(tenantId),
			getPersonas(tenantId),
			getSkills(tenantId),
			getWorkspaces(tenantId),
			getModels(tenantId),
			getTenantMembers(tenantId),
			getPromptTemplates(tenantId),
			getTenantAutomationTasks(tenantId),
			getTenantAutomationDefaults(tenantId),
		]
	)
	assistants.value = assistantsList
	rules.value = rulesList
	personas.value = personasList
	skills.value = skillsList
	workspaces.value = workspacesList
	models.value = modelsList
	tenantMembers.value = membersList
	promptTemplates.value = Array.isArray(templatesList) ? templatesList : []
	automationTasks.value = Array.isArray(automationList) ? automationList : []
	automationDefaults.value = Array.isArray(automationDefaultsList) ? automationDefaultsList : []
	modelProviders.value = await getModelProviders(tenantId)
	if (modelForm.value.provider) {
		syncModelDefaults()
	}
}
watch(
	() => modelForm.value.type,
	() => {
		modelForm.value.provider = ''
		modelForm.value.config = { ...modelForm.value.config, model_id: '' }
		modelSelection.value = ''
		modelCustomId.value = ''
		modelCustomEnum.value = {}
		modelSecretInputs.value = {}
		syncModelDefaults()
	}
)
watch(
	() => [assistantForm.value.workspace_id, assistantForm.value.worker_enabled],
	([workspaceId, enabled]) => {
		if (!enabled) {
			workerScopeOptions.value = []
			workerScopeError.value = ''
			return
		}
		loadWorkerScopes(workspaceId)
	}
)
const applyTenantList = async(list, shouldLoadData = true) => {
	tenants.value = list
	updateSessionTenants(list)
	const activeTenantId = loadActiveTenant()
	const hasActive = activeTenantId && list.some((tenant) => tenant.id === activeTenantId)
	if (!selectedTenant.value) {
		selectedTenant.value = hasActive ? activeTenantId : list[0]?.id || ''
	}
	if (selectedTenant.value) {
		saveActiveTenant(selectedTenant.value)
		if (shouldLoadData) {
			await loadTenantData()
		}
	}
}
const loadTenants = async() => {
	const list = await getTenants()
	await applyTenantList(list)
}
const handleTenantChange = async() => {
	saveActiveTenant(selectedTenant.value)
	status.value = ''
	inviteError.value = ''
	inviteOpen.value = false
	promptTemplateOpen.value = false
	renameOpen.value = false
	createOpen.value = false
	await loadTenantData()
}
const openRename = () => {
	renameTenantName.value = activeTenant.value?.name || ''
	tenantMaxOutputTokens.value = activeTenant.value?.max_output_tokens ?? ''
	renameOpen.value = true
}
const handleRenameTenant = async() => {
	status.value = ''
	if (!activeTenant.value) {
		notify('No tenant selected.')
		return
	}
	if (!renameTenantName.value.trim()) {
		notify('New tenant name is required.')
		return
	}
	const updated = await updateTenant(
		activeTenant.value
				.id,
		{
			name: renameTenantName.value,
			max_output_tokens: tenantMaxOutputTokens.value ? Number(tenantMaxOutputTokens.value) : null
		}
	)
	tenants.value = tenants.value
		.map((tenant) => tenant.id === updated.id
		? {
			...tenant,
			name: updated.name,
			role: updated.role,
			max_output_tokens: updated.max_output_tokens
		}
		: tenant)
	renameOpen.value = false
	updateSessionTenants(tenants.value)
	notify('Tenant renamed.')
}
const openCreate = () => {
	newTenantName.value = ''
	tenantMaxOutputTokens.value = ''
	createOpen.value = true
}
const handleCreateTenant = async() => {
	status.value = ''
	inviteError.value = ''
	inviteOpen.value = false
	if (!newTenantName.value.trim()) {
		notify('Tenant name is required.')
		return
	}
	const created = await createTenant({
		name: newTenantName.value,
		max_output_tokens: tenantMaxOutputTokens.value ? Number(tenantMaxOutputTokens.value) : null
	})
	tenants.value = [...tenants.value, created]
	selectedTenant.value = created.id
	saveActiveTenant(selectedTenant.value)
	updateSessionTenants(tenants.value)
	createOpen.value = false
	await loadTenantData()
	notify('Tenant created.')
}
const inviteMember = async() => {
	inviteError.value = ''
	if (!selectedTenant.value) {
		inviteError.value = 'Select a tenant first.'
		return
	}
	if (!inviteEmail.value.trim()) {
		inviteError.value = 'Email is required.'
		return
	}
	inviteSaving.value = true
	try {
		const member = await inviteTenantMember(selectedTenant.value, { email: inviteEmail.value })
		const exists = tenantMembers.value.some((item) => item.user_id === member.user_id)
		if (!exists) {
			tenantMembers.value = [...tenantMembers.value, member]
		}
		inviteEmail.value = ''
		inviteOpen.value = false
		notify('Member added.')
	}
	catch (error) {
		if (error?.status === 404) {
			inviteError.value = 'No user found with that email.'
		}
		else if (error?.status === 403) {
			inviteError.value = 'You do not have permission to invite members.'
		}
		else {
			inviteError.value = error?.message || 'Unable to invite user.'
		}
	}
	finally {
		inviteSaving.value = false
	}
}
const openInvite = () => {
	inviteEmail.value = ''
	inviteError.value = ''
	inviteOpen.value = true
}
const memberDisplayName = (member) => member?.display_name || member?.email || 'Member'
const memberInitial = (member) => {
	const label = memberDisplayName(member)
	return label ? label.trim().charAt(0).toUpperCase() : '?'
}
const memberPalette = ['#1E3A5F', '#1B4B4F', '#2A4B8D', '#355C7D', '#2D5C6E', '#2F4C65']
const memberColor = (member) => {
	const value = member?.user_id || member?.email || ''
	let hash = 0
	for (let i = 0; i < value.length; i += 1) {
		hash = (hash << 5) - hash + value.charCodeAt(i)
		hash |= 0
	}
	return memberPalette[Math.abs(hash) % memberPalette.length]
}
const openAssistantCreate = () => {
	assistantEditId.value = ''
	assistantForm.value = {
		name: '',
		description: '',
		prompt_text: '',
		persona_id: '',
		model_id: '',
		workspace_id: '',
		max_output_tokens: '',
		skill_ids: [],
		rule_ids: [],
		worker_enabled: false,
		worker_trigger: '',
		worker_allow_scopes: [],
		worker_deny_scopes: []
	}
	assistantModal.value = true
}
const openAssistantEdit = async(assistant) => {
	assistantEditId.value = assistant.id
	const details = await getAssistant(selectedTenant.value, assistant.id)
	assistantForm.value = {
		name: details.name,
		description: details.description || '',
		prompt_text: details.prompt_text || '',
		persona_id: details.persona_id || '',
		model_id: details.model_id,
		workspace_id: details.workspace_id || '',
		max_output_tokens: details.max_output_tokens ?? '',
		skill_ids: details.skill_ids || [],
		rule_ids: details.rule_ids || [],
		worker_enabled: !!details.worker_enabled,
		worker_trigger: details.worker_trigger || '',
		worker_allow_scopes: details.worker_allow_scopes || [],
		worker_deny_scopes: details.worker_deny_scopes || []
	}
	assistantModal.value = true
}
const saveAssistant = async() => {
	if (!assistantForm.value.name.trim()) {
		notify('Assistant name is required.')
		return
	}
	if (!assistantForm.value.model_id) {
		notify('Model selection is required.')
		return
	}
	const payload = {
		name: assistantForm.value.name,
		description: assistantForm.value.description,
		prompt_text: assistantForm.value.prompt_text,
		persona_id: assistantForm.value.persona_id || null,
		model_id: assistantForm.value.model_id,
		workspace_id: null,
		max_output_tokens: assistantForm.value.max_output_tokens ? Number(assistantForm.value.max_output_tokens) : null,
		skill_ids: assistantForm.value.skill_ids,
		rule_ids: assistantForm.value.rule_ids,
		worker_enabled: assistantForm.value.worker_enabled,
		worker_trigger: assistantForm.value.worker_trigger,
		worker_allow_scopes: assistantForm.value.worker_allow_scopes,
		worker_deny_scopes: assistantForm.value.worker_deny_scopes
	}
	if (assistantEditId.value) {
		const updated = await updateAssistant(selectedTenant.value, assistantEditId.value, payload)
		assistants.value = assistants.value.map((item) => (item.id === updated.id ? updated : item))
		notify('Assistant updated.')
	}
	else {
		const created = await createAssistant(selectedTenant.value, payload)
		assistants.value = [...assistants.value, created]
		notify('Assistant created.')
	}
	assistantModal.value = false
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
		const updated = await updateRule(selectedTenant.value, ruleEditId.value, payload)
		rules.value = rules.value.map((item) => (item.id === updated.id ? updated : item))
		notify('Rule updated.')
	}
	else {
		const created = await createRule(selectedTenant.value, payload)
		rules.value = [...rules.value, created]
		notify('Rule created.')
	}
	ruleModal.value = false
}
const requestDeleteRule = async(rule) => {
	const confirmed = window.confirm(`Delete rule "${rule.name}"? This also removes it from assistants using it.`)
	if (!confirmed) {
		return
	}
	await deleteRule(selectedTenant.value, rule.id)
	rules.value = rules.value.filter((item) => item.id !== rule.id)
	if (ruleEditId.value === rule.id) {
		ruleModal.value = false
		ruleEditId.value = ''
	}
	notify('Rule deleted.')
}
const openPersonaCreate = () => {
	personaEditId.value = ''
	personaForm.value = { name: '', description: '', prompt_text: '' }
	personaModal.value = true
}
const openPersonaEdit = (persona) => {
	personaEditId.value = persona.id
	personaForm.value = { name: persona.name, description: persona.description || '', prompt_text: persona.prompt_text || '' }
	personaModal.value = true
}
const savePersona = async() => {
	if (!personaForm.value.name.trim()) {
		notify('Persona name is required.')
		return
	}
	if (personaEditId.value) {
		const updated = await updatePersona(selectedTenant.value, personaEditId.value, personaForm.value)
		personas.value = personas.value.map((item) => (item.id === updated.id ? updated : item))
		notify('Persona updated.')
	}
	else {
		const created = await createPersona(selectedTenant.value, personaForm.value)
		personas.value = [...personas.value, created]
		notify('Persona created.')
	}
	personaModal.value = false
}
const templateBySection = (section) => promptTemplates.value
	.find((template) => template.section === section && template.tenant_id && !template.workspace_id)
const standardTemplateBySection = (section) => promptTemplates.value.find((template) => template.section === section && template.standard)
const promptPlaceholder = (section) => {
	const standard = standardTemplateBySection(section)
	return standard?.content || ''
}
const openPromptTemplateEditor = (section) => {
	const existing = templateBySection(section)
	promptTemplateForm.value = { section, content: existing?.content || '', enabled: existing ? !!existing.enabled : true }
	promptTemplateFallback.value = promptPlaceholder(section)
	promptTemplateOpen.value = true
}
const applyPromptTemplateFallback = () => {
	promptTemplateForm.value = { ...promptTemplateForm.value, content: promptTemplateFallback.value || '' }
}
const savePromptTemplate = async() => {
	if (!selectedTenant.value) {
		return
	}
	const payload = {
		section: promptTemplateForm.value.section,
		content: promptTemplateForm.value.content || '',
		enabled: !!promptTemplateForm.value.enabled
	}
	promptTemplateSaving.value = true
	try {
		const updated = await updateTenantPromptTemplate(selectedTenant.value, payload)
		const next = promptTemplates.value
			.filter((template) => !(template.section === updated.section && template.tenant_id && !template.workspace_id))
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
const automationTaskByType = (type) => automationTasks.value.find((task) => task.task_type === type)
const automationDefaultByType = (type) => automationDefaults.value.find((entry) => entry.task_type === type)
const automationTaskLabel = (type) => {
	if (type === 'TRANSCRIPTION') {
		return 'Transcription'
	}
	if (type === 'SUMMARIZE') {
		return 'Summarize text'
	}
	if (type === 'EMBEDDING') {
		return 'Embedding generation'
	}
	return 'Title generation'
}
const openAutomationEditor = (type) => {
	const task = automationTaskByType(type)
	automationForm.value = {
		taskType: type,
		model_id: task?.model_id || '',
		prompt_text: task?.prompt_text || '',
		enabled: task?.enabled ?? true
	}
	automationError.value = ''
	automationOpen.value = true
}
watch(
	() => automationForm.value.taskType,
	() => {
		const selectedModel = models.value.find((model) => model.id === automationForm.value.model_id)
		if (selectedModel && selectedModel.type !== automationTaskModelType(automationForm.value.taskType)) {
			automationForm.value.model_id = ''
		}
	}
)
const saveAutomationTask = async() => {
	if (!selectedTenant.value) {
		return
	}
	if (!automationForm.value.model_id) {
		automationError.value = 'Model is required.'
		return
	}
	automationSaving.value = true
	automationError.value = ''
	try {
		const payload = {
			model_id: automationForm.value.model_id,
			prompt_text: automationForm.value.prompt_text || null,
			enabled: automationForm.value.enabled
		}
		const updated = await updateTenantAutomationTask(selectedTenant.value, automationForm.value.taskType, payload)
		const next = automationTasks.value.filter((task) => task.task_type !== updated.task_type)
		automationTasks.value = [...next, updated]
		automationOpen.value = false
		notify('Automation task saved.')
	}
	catch (error) {
		automationError.value = error?.message || 'Unable to save automation task.'
	}
	finally {
		automationSaving.value = false
	}
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
	if (skillEditId.value) {
		const updated = await updateSkill(selectedTenant.value, skillEditId.value, skillForm.value)
		skills.value = skills.value.map((item) => (item.id === updated.id ? updated : item))
		notify('Skill updated.')
	}
	else {
		const created = await createSkill(selectedTenant.value, skillForm.value)
		skills.value = [...skills.value, created]
		notify('Skill created.')
	}
	skillModal.value = false
}
const openWorkspaceCreate = () => {
	workspaceEditId.value = ''
	workspaceForm.value = { name: '', description: '' }
	workspaceModal.value = true
}
const openWorkspaceEdit = (workspace) => {
	workspaceEditId.value = workspace.id
	workspaceForm.value = { name: workspace.name, description: workspace.description || '' }
	workspaceModal.value = true
}
const saveWorkspace = async() => {
	if (!workspaceForm.value.name.trim()) {
		notify('Workspace name is required.')
		return
	}
	if (workspaceEditId.value) {
		const updated = await updateWorkspace(selectedTenant.value, workspaceEditId.value, workspaceForm.value)
		workspaces.value = workspaces.value.map((item) => (item.id === updated.id ? updated : item))
		notify('Workspace updated.')
	}
	else {
		const created = await createWorkspace(selectedTenant.value, workspaceForm.value)
		workspaces.value = [...workspaces.value, created]
		notify('Workspace created.')
	}
	workspaceModal.value = false
}
const handleDeleteWorkspace = async(workspace) => {
	workspaceToDelete.value = workspace
	deleteWorkspaceOpen.value = true
}
const confirmDeleteWorkspace = async() => {
	const workspace = workspaceToDelete.value
	if (!workspace) {
		return
	}
	await deleteWorkspace(selectedTenant.value, workspace.id)
	workspaces.value = workspaces.value.filter((item) => item.id !== workspace.id)
	workspaceToDelete.value = null
	notify('Workspace removed.')
}
const cancelDeleteWorkspace = () => {
	workspaceToDelete.value = null
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
	await deleteSkill(selectedTenant.value, skill.id)
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
	notify('Skill removed.')
}
const cancelDeleteSkill = () => {
	skillToDelete.value = null
}
const deleteAssistantOpen = ref(false)
const assistantToDelete = ref(null)
const deleteAssistantMessage = computed(() => {
	if (assistantToDelete.value?.name) {
		return `Delete assistant "${assistantToDelete.value.name}"? This cannot be undone.`
	}
	return 'Delete this assistant? This cannot be undone.'
})
const openDeleteAssistant = () => {
	if (!assistantEditId.value) {
		return
	}
	const match = assistants.value.find((item) => item.id === assistantEditId.value)
	if (!match) {
		return
	}
	assistantToDelete.value = match
	deleteAssistantOpen.value = true
}
const confirmDeleteAssistant = async() => {
	const assistant = assistantToDelete.value
	if (!assistant) {
		return
	}
	try {
		await deleteAssistant(selectedTenant.value, assistant.id)
		assistants.value = assistants.value.filter((item) => item.id !== assistant.id)
		assistantToDelete.value = null
		deleteAssistantOpen.value = false
		assistantModal.value = false
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
const cancelDeleteAssistant = () => {
	assistantToDelete.value = null
	deleteAssistantOpen.value = false
}
const confirmDeleteModel = async() => {
	const model = modelToDelete.value
	if (!model) {
		return
	}
	await deleteModel(selectedTenant.value, model.id)
	models.value = models.value.filter((item) => item.id !== model.id)
	modelToDelete.value = null
	deleteModelOpen.value = false
	modelModal.value = false
	notify('Model deleted.')
}
const cancelDeleteModel = () => {
	modelToDelete.value = null
	deleteModelOpen.value = false
}
const openDeleteModel = () => {
	if (!modelEditId.value) {
		return
	}
	const match = models.value.find((item) => item.id === modelEditId.value)
	if (!match) {
		return
	}
	modelToDelete.value = match
	deleteModelOpen.value = true
}
const openModelCreate = (type = 'CHAT') => {
	modelEditId.value = ''
	modelCreateType.value = type
	modelForm.value = {
		name: '',
		provider: '',
		type,
		enabled: true,
		config: {}
	}
	modelSecretInputs.value = {}
	modelCustomEnum.value = {}
	modelSelection.value = ''
	modelCustomId.value = ''
	modelModal.value = true
}
const openModelEdit = (model) => {
	modelEditId.value = model.id
	modelCreateType.value = model.type || 'CHAT'
	modelForm.value = {
		name: model.name,
		provider: model.provider,
		type: model.type || 'CHAT',
		enabled: model.enabled,
		config: { ...model.config }
	}
	modelSecretInputs.value = {}
	modelCustomEnum.value = {}
	syncModelDefaults()
	modelModal.value = true
}
const providerProperties = computed(() => (selectedProvider.value?.properties || []).filter((property) => property.key !== 'model_id'))
const providerModels = computed(() => modelForm.value.type === 'EMBEDDING'
	? selectedProvider.value?.embedding_models || []
	: selectedProvider.value?.models || [])
const providerTypeProperties = computed(() => {
	const firstModel = providerModels.value[0]
	if (!firstModel?.properties?.length) {
		return []
	}
	const providerKeys = new Set(providerProperties.value.map((property) => property.key))
	return firstModel.properties.filter((property) => !providerKeys.has(property.key) && property.key !== 'model_id')
})
const selectedModelId = computed(() => {
	if (modelCustomId.value) {
		return modelCustomId.value
	}
	if (modelSelection.value) {
		return modelSelection.value
	}
	return modelForm.value.config?.model_id || ''
})
const modelProperties = computed(() => {
	const match = providerModels.value.find((model) => model.id === selectedModelId.value)
	if (!match) {
		return providerTypeProperties.value
	}
	const baselineKeys = new Set(
		[
			...providerProperties.value.map((property) => property.key),
			...providerTypeProperties.value.map((property) => property.key),
		]
	)
	return match.properties.filter((property) => !baselineKeys.has(property.key) && property.key !== 'model_id')
})
const selectedModelDefinition = computed(() => providerModels.value.find((model) => model.id === selectedModelId.value) || null)
const selectedModelUseCase = computed(() => selectedModelDefinition.value?.use_case || '')
const selectedModelMaxSegmentSize = computed(() => selectedModelDefinition.value?.max_segment_size || null)
const isCustomEmbeddingModel = computed(() => modelForm.value.type === 'EMBEDDING' && !!modelCustomId.value && !selectedModelDefinition.value)
const allModelProperties = computed(() => {
	const combined = [...providerProperties.value, ...providerTypeProperties.value, ...modelProperties.value]
	const seen = new Set()
	return combined.filter((property) => {
			if (!property || !property.key) {
				return false
			}
			if (seen.has(property.key)) {
				return false
			}
			seen.add(property.key)
			return true
		})
})
const deleteWorkspaceMessage = computed(() => {
	if (workspaceToDelete.value) {
		return `Delete workspace "${workspaceToDelete.value.name}"? This cannot be undone.`
	}
	return 'Delete this workspace? This cannot be undone.'
})
const syncModelDefaults = () => {
	const next = { ...modelForm.value.config }
	if (modelForm.value.provider) {
		const providerSupported = availableModelProviders.value.some((provider) => provider.id === modelForm.value.provider)
		if (!providerSupported) {
			modelForm.value.provider = ''
		}
	}
	const supportedIds = providerModels.value.map((model) => model.id)
	const currentModelId = next.model_id || ''
	if (currentModelId && supportedIds.includes(currentModelId)) {
		modelSelection.value = currentModelId
		modelCustomId.value = ''
	}
	else {
		modelSelection.value = ''
		modelCustomId.value = currentModelId || ''
	}
	allModelProperties.value
		.forEach((property) => {
			if (next[property.key] === undefined && property.defaultValue != null) {
				next[property.key] = property.defaultValue
			}
			if (property.enumOptions && property.enumOptions.length && property.allowFreeValue) {
				const current = next[property.key]
				if (current && !property.enumOptions.includes(String(current))) {
					modelCustomEnum.value = { ...modelCustomEnum.value, [property.key]: true }
				}
			}
		})
	modelForm.value.config = next
}
const handleModelSelection = (value) => {
	modelSelection.value = value
	modelCustomId.value = ''
	modelForm.value.config = { ...modelForm.value.config, model_id: value }
	syncModelDefaults()
}
const handleCustomModelInput = (value) => {
	modelCustomId.value = value
	modelSelection.value = ''
	modelForm.value.config = { ...modelForm.value.config, model_id: value }
	syncModelDefaults()
}
const saveModel = async() => {
	if (!modelForm.value.name.trim() || !modelForm.value.provider.trim()) {
		notify('Model name and provider are required.')
		return
	}
	if (!selectedModelId.value) {
		notify('Model id is required.')
		return
	}
	const configPayload = { ...modelForm.value.config }
	allModelProperties.value
		.forEach((property) => {
			if (property.type === 'SECRET') {
				const input = modelSecretInputs.value[property.key]
				if (input && input.trim()) {
					configPayload[property.key] = input.trim()
				}
			}
		})
	const payload = {
		name: modelForm.value.name,
		provider: modelForm.value.provider,
		type: modelForm.value.type,
		config: configPayload,
		enabled: modelForm.value.enabled
	}
	if (modelEditId.value) {
		const updated = await updateModel(selectedTenant.value, modelEditId.value, payload)
		models.value = models.value.map((item) => (item.id === updated.id ? updated : item))
		notify('Model updated.')
	}
	else {
		const created = await createModel(selectedTenant.value, payload)
		models.value = [created, ...models.value]
		notify('Model created.')
	}
	modelModal.value = false
}
const getSecretHint = (key) => {
	const value = modelForm.value.config?.[key]
	if (value && typeof value === 'object' && value.hint) {
		return value.hint
	}
	return ''
}
const propertyPlaceholder = (property) => {
	if (!property) {
		return ''
	}
	if (property.key === 'warning_limit') {
		const contextLimit = modelForm.value.config?.context_limit || selectedModelDefinition.value?.context_limit
		if (contextLimit) {
			return `The max is ${contextLimit}`
		}
	}
	return property.placeholder || ''
}
const setSecretValue = (key, value) => {
	modelSecretInputs.value = { ...modelSecretInputs.value, [key]: value }
}
const isCustomEnum = (property) => {
	return modelCustomEnum.value[property.key] === true
}
const modelListExpanded = ref(false)
const modelListLimit = 5
const visibleModels = computed(() => modelListExpanded.value ? models.value : models.value.slice(0, modelListLimit))
const modelTypeLabel = (type) => {
	if (type === 'EMBEDDING') {
		return 'Embedding'
	}
	return 'Chat'
}
const hiddenModelCount = computed(() => Math.max(0, models.value.length - modelListLimit))
const toggleModelList = () => {
	modelListExpanded.value = !modelListExpanded.value
}
const assistantListExpanded = ref(false)
const ruleListExpanded = ref(false)
const personaListExpanded = ref(false)
const skillListExpanded = ref(false)
const listLimit = 3
const visibleAssistants = computed(() => assistantListExpanded.value ? assistants.value : assistants.value.slice(0, listLimit))
const visibleRules = computed(() => ruleListExpanded.value ? rules.value : rules.value.slice(0, listLimit))
const visiblePersonas = computed(() => personaListExpanded.value ? personas.value : personas.value.slice(0, listLimit))
const visibleSkills = computed(() => skillListExpanded.value ? skills.value : skills.value.slice(0, listLimit))
const hiddenAssistantCount = computed(() => Math.max(0, assistants.value.length - listLimit))
const hiddenRuleCount = computed(() => Math.max(0, rules.value.length - listLimit))
const hiddenPersonaCount = computed(() => Math.max(0, personas.value.length - listLimit))
const hiddenSkillCount = computed(() => Math.max(0, skills.value.length - listLimit))
const toggleAssistantList = () => {
	assistantListExpanded.value = !assistantListExpanded.value
}
const toggleRuleList = () => {
	ruleListExpanded.value = !ruleListExpanded.value
}
const togglePersonaList = () => {
	personaListExpanded.value = !personaListExpanded.value
}
const toggleSkillList = () => {
	skillListExpanded.value = !skillListExpanded.value
}
const deleteModelOpen = ref(false)
const modelToDelete = ref(null)
const deleteModelMessage = computed(() => {
	if (modelToDelete.value?.name) {
		return `Delete model "${modelToDelete.value.name}"? This cannot be undone.`
	}
	return 'Delete this model? This cannot be undone.'
})
const handleEnumChange = (property, value) => {
	if (value === '__custom__') {
		modelCustomEnum.value = { ...modelCustomEnum.value, [property.key]: true }
		modelForm.value.config = { ...modelForm.value.config, [property.key]: '' }
		return
	}
	modelCustomEnum.value = { ...modelCustomEnum.value, [property.key]: false }
	modelForm.value.config = { ...modelForm.value.config, [property.key]: value }
}
const loadMenuState = () => {
	const stored = localStorage.getItem(menuCollapsedKey)
	menuCollapsed.value = stored === null ? false : stored === 'true'
}
const toggleMenu = () => {
	menuCollapsed.value = !menuCollapsed.value
	localStorage.setItem(menuCollapsedKey, menuCollapsed.value ? 'true' : 'false')
}
onMounted(async() => {
	loadMenuState()
	const skipLoad = route?.state?.skipTenantLoad
	if (skipLoad) {
		const cached = loadSessionTenants()
		if (cached.length) {
			await applyTenantList(cached, true)
			return
		}
	}
	await loadTenants()
})
</script>
<template>
	<section class="workspace-shell tenant-shell">
		<TenantMenu
			:workspaces="workspaces"
			:collapsed="menuCollapsed"
			@create-workspace="openWorkspaceCreate"
			@toggle-collapse="toggleMenu"/>
		<div class="detail-view tenant-view">
			<header class="section-header">
				<div class="tenant-title">
					<p class="eyebrow">Tenant</p>
					<div class="title-row">
						<h1>{{ activeTenantName }}</h1>
						<button
							class="control size-xs icon-button"
							type="button"
							aria-label="Rename tenant"
							@click="openRename">
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
					<p class="subtle">Manage assistants, rules, personas, skills, models, and workspaces.</p>
				</div>
				<div class="tenant-switcher">
					<div class="tenant-select-row">
						<select
							id="tenant-select"
							v-model="selectedTenant"
							@change="handleTenantChange">
							<option
								v-for="tenant in tenants"
								:key="tenant.id"
								:value="tenant.id">{{ tenant.name }}</option>
						</select>
						<button
							class="control size-xs icon-button"
							type="button"
							@click="openCreate">+</button>
					</div>
				</div>
			</header>
			<section class="management-grid">
				<article class="panel compact">
					<div class="section-head">
						<h2>Members</h2>
						<button
							class="control size-xs icon-button tooltip"
							type="button"
							aria-label="Invite member"
							data-tip="Invite member"
							@click="openInvite">
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
							v-for="member in tenantMembers"
							:key="member.user_id"
							class="list-row">
							<div class="member-meta">
								<span
									class="member-avatar"
									:style="{ backgroundColor: member.avatar_url ? '' : memberColor(member) }">
									<img
										v-if="member.avatar_url"
										:src="member.avatar_url"
										alt=""/>
									<span v-else class="member-avatar-initial">{{ memberInitial(member) }}</span>
								</span>
								<div>
									<strong>{{ memberDisplayName(member) }}</strong>
									<p v-if="member.display_name && member.display_name !== member.email" class="subtle">{{ member.email }}</p>
								</div>
							</div>
							<span class="control size-xs pill tag">{{ member.role }}</span>
						</div>
						<p v-if="tenantMembers.length === 0" class="empty">No members yet.</p>
					</div>
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
							v-for="assistant in visibleAssistants"
							:key="assistant.id"
							class="list-row">
							<div>
								<strong>{{ assistant.name }}</strong>
								<p class="subtle">
									Model: {{ assistant.model_name || 'Unassigned' }}
								</p>
								<p class="subtle meta-line">
									Persona: {{ assistant.persona_name || 'Unassigned' }}
								</p>
							</div>
							<button
								class="control size-xs ghost"
								type="button"
								@click="openAssistantEdit(assistant)">Edit</button>
						</div>
						<button
							v-if="hiddenAssistantCount > 0"
							class="control size-xs ghost"
							type="button"
							@click="toggleAssistantList">{{ assistantListExpanded ? 'Show less' : `Show ${hiddenAssistantCount} more` }}</button>
						<p v-if="assistants.length === 0" class="empty">No assistants yet.</p>
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
							v-for="rule in visibleRules"
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
						<button
							v-if="hiddenRuleCount > 0"
							class="control size-xs ghost"
							type="button"
							@click="toggleRuleList">{{ ruleListExpanded ? 'Show less' : `Show ${hiddenRuleCount} more` }}</button>
						<p v-if="rules.length === 0" class="empty">No rules yet.</p>
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
								<p class="subtle">{{ templateBySection(section.key) ? 'Custom' : 'Default' }}</p>
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
						<h2>Automation tasks</h2>
					</div>
					<div class="tenant-list">
						<div class="list-row">
							<div>
								<strong>Title generation</strong>
								<p class="subtle">
									Model: {{ automationTaskByType('TITLE')?.model_name || 'Unassigned' }}
								</p>
								<p class="subtle meta-line">{{ automationTaskByType('TITLE')?.enabled === false ? 'Disabled' : 'Enabled' }}</p>
							</div>
							<button
								class="control size-xs ghost"
								type="button"
								@click="openAutomationEditor('TITLE')">Edit</button>
						</div>
						<div class="list-row">
							<div>
								<strong>Transcription</strong>
								<p class="subtle">
									Model: {{ automationTaskByType('TRANSCRIPTION')?.model_name || 'Unassigned' }}
								</p>
								<p class="subtle meta-line">{{ automationTaskByType('TRANSCRIPTION')?.enabled === false ? 'Disabled' : 'Enabled' }}</p>
							</div>
							<button
								class="control size-xs ghost"
								type="button"
								@click="openAutomationEditor('TRANSCRIPTION')">Edit</button>
						</div>
						<div class="list-row">
							<div>
								<strong>Summarize text</strong>
								<p class="subtle">
									Model: {{ automationTaskByType('SUMMARIZE')?.model_name || 'Unassigned' }}
								</p>
								<p class="subtle meta-line">{{ automationTaskByType('SUMMARIZE')?.enabled === false ? 'Disabled' : 'Enabled' }}</p>
							</div>
							<button
								class="control size-xs ghost"
								type="button"
								@click="openAutomationEditor('SUMMARIZE')">Edit</button>
						</div>
						<div class="list-row">
							<div>
								<strong>Embedding generation</strong>
								<p class="subtle">
									Model: {{ automationTaskByType('EMBEDDING')?.model_name || 'Unassigned' }}
								</p>
								<p class="subtle meta-line">{{ automationTaskByType('EMBEDDING')?.enabled === false ? 'Disabled' : 'Enabled' }}</p>
							</div>
							<button
								class="control size-xs ghost"
								type="button"
								@click="openAutomationEditor('EMBEDDING')">Edit</button>
						</div>
					</div>
				</article>
				<article class="panel compact">
					<div class="section-head">
						<h2>Personas</h2>
						<button
							class="control size-xs icon-button tooltip"
							type="button"
							aria-label="Create persona"
							data-tip="Create persona"
							@click="openPersonaCreate">
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
							v-for="persona in visiblePersonas"
							:key="persona.id"
							class="list-row">
							<div>
								<strong>{{ persona.name }}</strong>
								<p class="subtle">{{ persona.description || 'No description' }}</p>
							</div>
							<button
								class="control size-xs ghost"
								type="button"
								@click="openPersonaEdit(persona)">Edit</button>
						</div>
						<button
							v-if="hiddenPersonaCount > 0"
							class="control size-xs ghost"
							type="button"
							@click="togglePersonaList">{{ personaListExpanded ? 'Show less' : `Show ${hiddenPersonaCount} more` }}</button>
						<p v-if="personas.length === 0" class="empty">No personas yet.</p>
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
							v-for="skill in visibleSkills"
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
							v-if="hiddenSkillCount > 0"
							class="control size-xs ghost"
							type="button"
							@click="toggleSkillList">{{ skillListExpanded ? 'Show less' : `Show ${hiddenSkillCount} more` }}</button>
						<p v-if="skills.length === 0" class="empty">No skills yet.</p>
					</div>
				</article>
				<article class="panel compact">
					<div class="section-head">
						<h2>Models</h2>
						<div class="inline-actions">
							<button
								class="control size-xs ghost inline-icon"
								type="button"
								@click="openModelCreate('CHAT')">
								<svg
									viewBox="0 0 24 24"
									aria-hidden="true"
									focusable="false">
									<path fill="currentColor" d="M11 5h2v14h-2zM5 11h14v2H5z"/>
								</svg>
								<span>Chat</span>
							</button>
							<button
								class="control size-xs ghost inline-icon"
								type="button"
								@click="openModelCreate('EMBEDDING')">
								<svg
									viewBox="0 0 24 24"
									aria-hidden="true"
									focusable="false">
									<path fill="currentColor" d="M11 5h2v14h-2zM5 11h14v2H5z"/>
								</svg>
								<span>Embedded</span>
							</button>
						</div>
					</div>
					<div class="tenant-list">
						<div
							v-for="model in visibleModels"
							:key="model.id"
							class="list-row">
							<div>
								<div class="row-inline gap-s">
									<strong>{{ model.name }}</strong>
									<span class="control size-xs pill tag model-type-pill">{{ modelTypeLabel(model.type) }}</span>
								</div>
								<p class="subtle">{{ providerName(model.provider) }}</p>
							</div>
							<button
								class="control size-xs ghost"
								type="button"
								@click="openModelEdit(model)">Edit</button>
						</div>
						<button
							v-if="hiddenModelCount > 0"
							class="control size-xs ghost"
							type="button"
							@click="toggleModelList">{{ modelListExpanded ? 'Show less' : `Show ${hiddenModelCount} more` }}</button>
						<p v-if="models.length === 0" class="empty">No models yet.</p>
					</div>
				</article>
			</section>
			<div v-if="status" class="toast">{{ status }}</div>
		</div>
		<ConfirmModal
			v-model:open="deleteWorkspaceOpen"
			title="Delete workspace"
			:message="deleteWorkspaceMessage"
			confirm-label="Delete"
			:destructive="true"
			@confirm="confirmDeleteWorkspace"
			@cancel="cancelDeleteWorkspace"/>
		<ConfirmModal
			v-model:open="deleteModelOpen"
			title="Delete model"
			:message="deleteModelMessage"
			confirm-label="Delete"
			:destructive="true"
			@confirm="confirmDeleteModel"
			@cancel="cancelDeleteModel"/>
		<ConfirmModal
			v-model:open="deleteAssistantOpen"
			title="Delete assistant"
			:message="deleteAssistantMessage"
			confirm-label="Delete"
			:destructive="true"
			@confirm="confirmDeleteAssistant"
			@cancel="cancelDeleteAssistant"/>
		<ConfirmModal
			v-model:open="deleteSkillOpen"
			title="Delete skill"
			:message="deleteSkillMessage"
			confirm-label="Delete"
			:destructive="true"
			@confirm="confirmDeleteSkill"
			@cancel="cancelDeleteSkill"/>
		<div
			v-if="inviteOpen"
			class="sheet-backdrop"
			@click.self="inviteOpen = false">
			<div class="sidepane">
				<button
					class="control size-s ghost icon-button icon-ghost sidepane-close"
					type="button"
					aria-label="Close"
					@click="inviteOpen = false">
					<svg
						viewBox="0 0 24 24"
						aria-hidden="true"
						focusable="false">
						<path
							fill="currentColor"
							d="M18.3 5.71a1 1 0 0 0-1.41 0L12 10.59 7.11 5.7a1 1 0 0 0-1.41 1.42L10.59 12l-4.89 4.89a1 1 0 1 0 1.41 1.41L12 13.41l4.89 4.89a1 1 0 0 0 1.41-1.41L13.41 12l4.89-4.89a1 1 0 0 0 0-1.4z"/>
					</svg>
				</button>
				<h2>Invite member</h2>
				<div class="stack">
					<label class="field">
						<span>Email</span>
						<input
							v-model="inviteEmail"
							type="email"
							placeholder="name@company.com"
							:disabled="inviteSaving"/>
					</label>
					<p v-if="inviteError" class="form-error">{{ inviteError }}</p>
					<div class="row-actions">
						<button
							class="control size-m secondary"
							type="button"
							:disabled="inviteSaving"
							@click="inviteMember">Invite</button>
						<button
							class="control size-m ghost"
							type="button"
							@click="inviteOpen = false">Cancel</button>
					</div>
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
			v-if="automationOpen"
			class="sheet-backdrop"
			@click.self="automationOpen = false">
			<div class="sidepane">
				<button
					class="control size-s ghost icon-button icon-ghost sidepane-close"
					type="button"
					aria-label="Close"
					@click="automationOpen = false">
					<svg
						viewBox="0 0 24 24"
						aria-hidden="true"
						focusable="false">
						<path
							fill="currentColor"
							d="M18.3 5.71a1 1 0 0 0-1.41 0L12 10.59 7.11 5.7a1 1 0 0 0-1.41 1.42L10.59 12l-4.89 4.89a1 1 0 1 0 1.41 1.41L12 13.41l4.89 4.89a1 1 0 0 0 1.41-1.41L13.41 12l4.89-4.89a1 1 0 0 0 0-1.4z"/>
					</svg>
				</button>
				<h2>{{ automationTaskLabel(automationForm.taskType) }}</h2>
				<div class="stack">
					<label class="field">
						<span>Task type</span>
						<input
							:value="automationForm.taskType"
							type="text"
							disabled/>
					</label>
					<label class="field">
						<span>Model</span>
						<select v-model="automationForm.model_id">
							<option value="" disabled>Select a model</option>
							<option
								v-for="model in automationModels"
								:key="model.id"
								:value="model.id">{{ model.name }}</option>
						</select>
					</label>
					<label v-if="automationTaskModelType(automationForm.taskType) === 'CHAT'" class="field">
						<span>Prompt override (optional)</span>
						<textarea
							v-model="automationForm.prompt_text"
							rows="8"
							:placeholder="automationDefaultByType(automationForm.taskType)?.prompt_text || ''"></textarea>
					</label>
					<p v-else class="subtle model-usecase">This task stores the embedding model assignment only. Prompt overrides are not used.</p>
					<label class="switch">
						<input v-model="automationForm.enabled" type="checkbox"/>
						<span>Enabled</span>
					</label>
					<p v-if="automationError" class="form-error">{{ automationError }}</p>
					<div class="row-actions">
						<button
							class="control size-m secondary"
							type="button"
							:disabled="automationSaving"
							@click="saveAutomationTask">Save</button>
						<button
							class="control size-m ghost"
							type="button"
							@click="automationOpen = false">Cancel</button>
					</div>
				</div>
			</div>
		</div>
		<div
			v-if="renameOpen"
			class="sheet-backdrop"
			@click.self="renameOpen = false">
			<div class="sidepane">
				<button
					class="control size-s ghost icon-button icon-ghost sidepane-close"
					type="button"
					aria-label="Close"
					@click="renameOpen = false">
					<svg
						viewBox="0 0 24 24"
						aria-hidden="true"
						focusable="false">
						<path
							fill="currentColor"
							d="M18.3 5.71a1 1 0 0 0-1.41 0L12 10.59 7.11 5.7a1 1 0 0 0-1.41 1.42L10.59 12l-4.89 4.89a1 1 0 1 0 1.41 1.41L12 13.41l4.89 4.89a1 1 0 0 0 1.41-1.41L13.41 12l4.89-4.89a1 1 0 0 0 0-1.4z"/>
					</svg>
				</button>
				<h2>Rename tenant</h2>
				<label class="field">
					<span>New name</span>
					<input
						v-model="renameTenantName"
						type="text"
						placeholder="Updated tenant name"/>
				</label>
				<label class="field">
					<span>Max output tokens (optional)</span>
					<input
						v-model="tenantMaxOutputTokens"
						type="number"
						min="1"
						placeholder="No limit"/>
				</label>
				<div class="row-actions">
					<button
						class="control size-m secondary"
						type="button"
						@click="handleRenameTenant">Save</button>
					<button
						class="control size-m ghost"
						type="button"
						@click="renameOpen = false">Cancel</button>
				</div>
			</div>
		</div>
		<div
			v-if="createOpen"
			class="sheet-backdrop"
			@click.self="createOpen = false">
			<div class="sidepane">
				<button
					class="control size-s ghost icon-button icon-ghost sidepane-close"
					type="button"
					aria-label="Close"
					@click="createOpen = false">
					<svg
						viewBox="0 0 24 24"
						aria-hidden="true"
						focusable="false">
						<path
							fill="currentColor"
							d="M18.3 5.71a1 1 0 0 0-1.41 0L12 10.59 7.11 5.7a1 1 0 0 0-1.41 1.42L10.59 12l-4.89 4.89a1 1 0 1 0 1.41 1.41L12 13.41l4.89 4.89a1 1 0 0 0 1.41-1.41L13.41 12l4.89-4.89a1 1 0 0 0 0-1.4z"/>
					</svg>
				</button>
				<h2>Create tenant</h2>
				<label class="field">
					<span>Name</span>
					<input
						v-model="newTenantName"
						type="text"
						placeholder="New tenant name"/>
				</label>
				<label class="field">
					<span>Max output tokens (optional)</span>
					<input
						v-model="tenantMaxOutputTokens"
						type="number"
						min="1"
						placeholder="No limit"/>
				</label>
				<div class="row-actions">
					<button
						class="control size-m secondary"
						type="button"
						@click="handleCreateTenant">Create</button>
					<button
						class="control size-m ghost"
						type="button"
						@click="createOpen = false">Cancel</button>
				</div>
			</div>
		</div>
		<div
			v-if="assistantModal"
			class="sheet-backdrop"
			@click.self="assistantModal = false">
			<div class="sidepane">
				<button
					class="control size-s ghost icon-button icon-ghost sidepane-close"
					type="button"
					aria-label="Close"
					@click="assistantModal = false">
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
							rows="4"
							placeholder="Add instructions that will be included in the compiled prompt"></textarea>
					</label>
					<label class="field">
						<span>Persona</span>
						<select v-model="assistantForm.persona_id">
							<option value="">Unassigned</option>
							<option
								v-for="persona in personas"
								:key="persona.id"
								:value="persona.id">{{ persona.name }}</option>
						</select>
					</label>
					<label class="field">
						<span>Model</span>
						<select v-model="assistantForm.model_id">
							<option value="">Select a model</option>
							<option
								v-for="model in models"
								:key="model.id"
								:value="model.id">{{ model.name }}·{{ model.provider }}</option>
						</select>
					</label>
					<label class="field">
						<span>Max output tokens (optional)</span>
						<input
							v-model="assistantForm.max_output_tokens"
							type="number"
							min="1"
							placeholder="Use tenant default"/>
					</label>
					<div class="field">
						<span>Subassistant</span>
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
						<p v-if="!assistantForm.workspace_id" class="subtle">Select a workspace to configure worker scopes.</p>
						<p v-else-if="workerScopeError" class="form-error">{{ workerScopeError }}</p>
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
						<span>Skills</span>
						<div class="option-list">
							<label
								v-for="skill in skills"
								:key="skill.id"
								class="option-item">
								<input
									v-model="assistantForm.skill_ids"
									type="checkbox"
									:value="skill.id"/>
								<span>{{ skill.name }}</span>
							</label>
							<p v-if="skills.length === 0" class="subtle">No skills available.</p>
						</div>
					</div>
					<div class="field">
						<span>Optional rules</span>
						<div class="option-list">
							<label
								v-for="rule in optInRules"
								:key="rule.id"
								class="option-item">
								<input
									v-model="assistantForm.rule_ids"
									type="checkbox"
									:value="rule.id"/>
								<span>{{ rule.name }}</span>
							</label>
							<p v-if="optInRules.length === 0" class="subtle">No opt-in rules available.</p>
						</div>
					</div>
					<div class="row-actions">
						<button
							class="control size-m secondary"
							type="button"
							@click="saveAssistant">Save</button>
						<button
							v-if="assistantEditId"
							class="control size-m ghost danger"
							type="button"
							@click="openDeleteAssistant">Delete</button>
						<button
							class="control size-m ghost"
							type="button"
							@click="assistantModal = false">Cancel</button>
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
			v-if="personaModal"
			class="sheet-backdrop"
			@click.self="personaModal = false">
			<div class="sidepane">
				<button
					class="control size-s ghost icon-button icon-ghost sidepane-close"
					type="button"
					aria-label="Close"
					@click="personaModal = false">
					<svg
						viewBox="0 0 24 24"
						aria-hidden="true"
						focusable="false">
						<path
							fill="currentColor"
							d="M18.3 5.71a1 1 0 0 0-1.41 0L12 10.59 7.11 5.7a1 1 0 0 0-1.41 1.42L10.59 12l-4.89 4.89a1 1 0 1 0 1.41 1.41L12 13.41l4.89 4.89a1 1 0 0 0 1.41-1.41L13.41 12l4.89-4.89a1 1 0 0 0 0-1.4z"/>
					</svg>
				</button>
				<h2>{{ personaEditId ? 'Edit persona' : 'Create persona' }}</h2>
				<div class="stack">
					<label class="field">
						<span>Name</span>
						<input
							v-model="personaForm.name"
							type="text"
							placeholder="Persona name"/>
					</label>
					<label class="field">
						<span>Description</span>
						<textarea
							v-model="personaForm.description"
							rows="4"
							placeholder="Primary motivator"></textarea>
					</label>
					<label class="field">
						<span>Prompt</span>
						<textarea
							v-model="personaForm.prompt_text"
							rows="15"
							placeholder="Full persona prompt"></textarea>
					</label>
					<div class="row-actions">
						<button
							class="control size-m secondary"
							type="button"
							@click="savePersona">Save</button>
						<button
							class="control size-m ghost"
							type="button"
							@click="personaModal = false">Cancel</button>
					</div>
				</div>
			</div>
		</div>
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
			v-if="modelModal"
			class="sheet-backdrop"
			@click.self="modelModal = false">
			<div class="sidepane">
				<button
					class="control size-s ghost icon-button icon-ghost sidepane-close"
					type="button"
					aria-label="Close"
					@click="modelModal = false">
					<svg
						viewBox="0 0 24 24"
						aria-hidden="true"
						focusable="false">
						<path
							fill="currentColor"
							d="M18.3 5.71a1 1 0 0 0-1.41 0L12 10.59 7.11 5.7a1 1 0 0 0-1.41 1.42L10.59 12l-4.89 4.89a1 1 0 1 0 1.41 1.41L12 13.41l4.89 4.89a1 1 0 0 0 1.41-1.41L13.41 12l4.89-4.89a1 1 0 0 0 0-1.4z"/>
					</svg>
				</button>
				<h2>
					{{ modelEditId ? `Edit ${modelTypeLabel(modelForm.type).toLowerCase()} model` : `Create ${modelTypeLabel(modelForm.type).toLowerCase()} model` }}
				</h2>
				<div class="stack">
					<label class="field">
						<span>Name</span>
						<input
							v-model="modelForm.name"
							type="text"
							placeholder="Model name"/>
					</label>
					<label class="field">
						<span>Provider</span>
						<select v-model="modelForm.provider" @change="syncModelDefaults">
							<option value="">Select provider</option>
							<option
								v-for="provider in availableModelProviders"
								:key="provider.id"
								:value="provider.id">{{ provider.name }}</option>
						</select>
					</label>
					<label v-if="!modelCustomId" class="field">
						<span>Model</span>
						<select
							:value="modelSelection"
							:disabled="!modelForm.provider"
							@change="handleModelSelection($event.target.value)">
							<option value="">Select model</option>
							<option
								v-for="model in providerModels"
								:key="model.id"
								:value="model.id">{{ model.name || model.id }}</option>
						</select>
					</label>
					<label v-if="!modelSelection" class="field">
						<span>Advanced model id</span>
						<input
							:value="modelCustomId"
							type="text"
							:disabled="!modelForm.provider"
							:placeholder="modelForm.type === 'EMBEDDING' ? 'Custom embedding model id' : 'Custom model id'"
							@input="handleCustomModelInput($event.target.value)"/>
					</label>
					<p v-if="selectedModelUseCase" class="subtle model-usecase">{{ selectedModelUseCase }}</p>
					<p v-if="isCustomEmbeddingModel" class="subtle model-usecase">Enter the maximum input size per embedding segment for this model.</p>
					<p v-if="modelCustomId" class="subtle model-usecase">Custom model ids may not be supported. Token counting, costs, and thresholds can be unavailable.</p>
					<div
						v-for="property in allModelProperties"
						:key="property.key"
						class="field">
						<span>
							{{ property.label }}
							<span v-if="property.required" class="required">*</span>
							<span
								v-if="property.help"
								class="help-icon tooltip"
								:data-tip="property.help">?</span>
						</span>
						<p
							v-if="property.key === 'max_segment_size' && modelForm.type === 'EMBEDDING' && selectedModelMaxSegmentSize"
							class="subtle model-usecase">
							Known max segment size: {{ selectedModelMaxSegmentSize }} tokens.
						</p>
						<template v-if="property.type === 'SECRET'">
							<input
								:value="modelSecretInputs[property.key] || ''"
								type="text"
								:placeholder="getSecretHint(property.key) ? `Current: ${getSecretHint(property.key)}` : (property.placeholder || '')"
								@input="setSecretValue(property.key, $event.target.value)"/>
						</template>
						<template v-else-if="property.type === 'NUMBER'">
							<div v-if="property.min != null && property.max != null" class="range-field">
								<input
									v-model="modelForm.config[property.key]"
									type="range"
									:min="property.min"
									:max="property.max"
									:step="property.step ?? 0.1"/>
								<input
									v-model="modelForm.config[property.key]"
									type="number"
									class="range-input"
									:min="property.min"
									:max="property.max"
									:step="property.step ?? 0.1"/>
							</div>
							<input
								v-else
								v-model="modelForm.config[property.key]"
								type="number"
								:min="property.min ?? undefined"
								:max="property.max ?? undefined"
								:step="property.step ?? 'any'"
								:placeholder="propertyPlaceholder(property)"/>
						</template>
						<template v-else-if="property.type === 'BOOLEAN'">
							<label class="switch">
								<input v-model="modelForm.config[property.key]" type="checkbox"/>
								<span>{{ property.help || 'Enabled' }}</span>
							</label>
						</template>
						<template v-else>
							<select
								v-if="property.enumOptions && property.enumOptions.length"
								:value="isCustomEnum(property) ? '__custom__' : modelForm.config[property.key]"
								@change="handleEnumChange(property, $event.target.value)">
								<option value="">Select</option>
								<option
									v-for="option in property.enumOptions"
									:key="option"
									:value="option">{{ option }}</option>
								<option v-if="property.allowFreeValue" value="__custom__">Custom</option>
							</select>
							<input
								v-if="!property.enumOptions || !property.enumOptions.length"
								v-model="modelForm.config[property.key]"
								type="text"
								:placeholder="property.placeholder || ''"/>
							<input
								v-if="property.allowFreeValue && isCustomEnum(property)"
								v-model="modelForm.config[property.key]"
								type="text"
								:placeholder="property.placeholder || 'Custom value'"/>
						</template>
						<p v-if="property.help" class="subtle">{{ property.help }}</p>
					</div>
					<label class="switch">
						<input v-model="modelForm.enabled" type="checkbox"/>
						<span>Enabled</span>
					</label>
					<div class="row-actions">
						<button
							class="control size-m secondary"
							type="button"
							@click="saveModel">Save</button>
						<button
							v-if="modelEditId"
							class="control size-m ghost danger"
							type="button"
							@click="openDeleteModel">Delete</button>
						<button
							class="control size-m ghost"
							type="button"
							@click="modelModal = false">Cancel</button>
					</div>
				</div>
			</div>
		</div>
		<div
			v-if="workspaceModal"
			class="sheet-backdrop"
			@click.self="workspaceModal = false">
			<div class="sidepane">
				<button
					class="control size-s ghost icon-button icon-ghost sidepane-close"
					type="button"
					aria-label="Close"
					@click="workspaceModal = false">
					<svg
						viewBox="0 0 24 24"
						aria-hidden="true"
						focusable="false">
						<path
							fill="currentColor"
							d="M18.3 5.71a1 1 0 0 0-1.41 0L12 10.59 7.11 5.7a1 1 0 0 0-1.41 1.42L10.59 12l-4.89 4.89a1 1 0 1 0 1.41 1.41L12 13.41l4.89 4.89a1 1 0 0 0 1.41-1.41L13.41 12l4.89-4.89a1 1 0 0 0 0-1.4z"/>
					</svg>
				</button>
				<h2>{{ workspaceEditId ? 'Edit workspace' : 'Create workspace' }}</h2>
				<div class="stack">
					<label class="field">
						<span>Name</span>
						<input
							v-model="workspaceForm.name"
							type="text"
							placeholder="Workspace name"/>
					</label>
					<label class="field">
						<span>Description</span>
						<textarea
							v-model="workspaceForm.description"
							rows="4"
							placeholder="Workspace description"></textarea>
					</label>
					<div class="row-actions">
						<button
							class="control size-m secondary"
							type="button"
							@click="saveWorkspace">Save</button>
						<button
							class="control size-m ghost"
							type="button"
							@click="workspaceModal = false">Cancel</button>
					</div>
				</div>
			</div>
		</div>
	</section>
</template>
