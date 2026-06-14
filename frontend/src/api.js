const devOrigin = `${window.location.protocol}//${window.location.hostname}:5050`
const currentOrigin = window.location.origin
const API_URL = import.meta.env.PROD ? currentOrigin : devOrigin
let accessToken = null
let refreshPromise = null
const fetchWithAuth = async(path, options = {}, retry = true) => {
	const headers = { ...(options.headers || {}) }
	if (accessToken) {
		headers.Authorization = `Bearer ${accessToken}`
	}
	const response = await fetch(`${API_URL}${path}`, { credentials: 'include', ...options, headers })
	if (response.status === 401 && retry) {
		const refreshed = await refreshAccessToken()
		if (refreshed) {
			return fetchWithAuth(path, options, false)
		}
	}
	return response
}
const request = async(path, options = {}, retry = true) => {
	const response = await fetchWithAuth(
		path,
		{ ...options, headers: { 'Content-Type': 'application/json', ...(options.headers || {}) } },
		retry
	)
	if (!response.ok) {
		const message = await response.text()
		const error = new Error(message || 'Request failed')
		error.status = response.status
		throw error
	}
	if (response.status === 204) {
		return null
	}
	const contentType = response.headers.get('content-type') || ''
	if (contentType.includes('application/json')) {
		return response.json()
	}
	return response.text()
}
const requestBody = async(path, body, options = {}, retry = true) => {
	const response = await fetchWithAuth(path, { ...options, method: options.method || 'POST', body }, retry)
	if (!response.ok) {
		const message = await response.text()
		const error = new Error(message || 'Request failed')
		error.status = response.status
		throw error
	}
	if (response.status === 204) {
		return null
	}
	const contentType = response.headers.get('content-type') || ''
	if (contentType.includes('application/json')) {
		return response.json()
	}
	return response.text()
}
const requestBlob = async(path, options = {}, retry = true) => {
	const response = await fetchWithAuth(path, options, retry)
	if (!response.ok) {
		const message = await response.text()
		const error = new Error(message || 'Request failed')
		error.status = response.status
		throw error
	}
	return response.blob()
}
export const refreshAccessToken = async() => {
	if (!refreshPromise) {
		refreshPromise = fetch(`${API_URL}/api/auth/refresh`, { method: 'POST', credentials: 'include' })
			.then(async(response) => {
				if (!response.ok) {
					accessToken = null
					return null
				}
				const data = await response.json()
				accessToken = data.accessToken
				saveSession(data)
				return data.accessToken
			})
			.finally(() => {
				refreshPromise = null
			})
	}
	return refreshPromise
}
export const register = (payload) => request(
	'/api/auth/register',
	{ method: 'POST', body: JSON.stringify(payload) }
)
export const login = (payload) => request(
	'/api/auth/login',
	{ method: 'POST', body: JSON.stringify(payload) }
)
export const getProfile = (userId) => request(`/api/users/${userId}`)
export const updateProfile = (userId, payload) => request(
	`/api/users/${userId}`,
	{ method: 'PUT', body: JSON.stringify(payload) }
)
export const getUserRules = (userId) => request(`/api/users/${userId}/rules`)
export const createUserRule = (userId, payload) => request(
	`/api/users/${userId}/rules`,
	{ method: 'POST', body: JSON.stringify(payload) }
)
export const updateUserRule = (userId, ruleId, payload) => request(
	`/api/users/${userId}/rules/${ruleId}`,
	{ method: 'PUT', body: JSON.stringify(payload) }
)
export const deleteUserRule = (userId, ruleId) => request(
	`/api/users/${userId}/rules/${ruleId}`,
	{ method: 'DELETE' }
)
export const getTenants = () => request('/api/tenants')
export const createTenant = (payload) => request(
	'/api/tenants',
	{ method: 'POST', body: JSON.stringify(payload) }
)
export const updateTenant = (tenantId, payload) => request(
	`/api/tenants/${tenantId}`,
	{ method: 'PUT', body: JSON.stringify(payload) }
)
export const getTenantMembers = (tenantId) => request(`/api/tenants/${tenantId}/members`)
export const getTenantAutomationTasks = (tenantId) => request(
	`/api/tenants/${tenantId}/automation-tasks`
)
export const updateTenantAutomationTask = (tenantId, taskType, payload) => request(
	`/api/tenants/${tenantId}/automation-tasks/${taskType}`,
	{ method: 'PUT', body: JSON.stringify(payload) }
)
export const getTenantAutomationDefaults = (tenantId) => request(
	`/api/tenants/${tenantId}/automation-tasks/defaults`
)
export const inviteTenantMember = (tenantId, payload) => request(
	`/api/tenants/${tenantId}/members`,
	{ method: 'POST', body: JSON.stringify(payload) }
)
export const getPersonas = (tenantId) => request(`/api/tenants/${tenantId}/personas`)
export const createPersona = (tenantId, payload) => request(
	`/api/tenants/${tenantId}/personas`,
	{ method: 'POST', body: JSON.stringify(payload) }
)
export const updatePersona = (tenantId, personaId, payload) => request(
	`/api/tenants/${tenantId}/personas/${personaId}`,
	{ method: 'PUT', body: JSON.stringify(payload) }
)
export const getRules = (tenantId) => request(`/api/tenants/${tenantId}/rules`)
export const createRule = (tenantId, payload) => request(
	`/api/tenants/${tenantId}/rules`,
	{ method: 'POST', body: JSON.stringify(payload) }
)
export const updateRule = (tenantId, ruleId, payload) => request(
	`/api/tenants/${tenantId}/rules/${ruleId}`,
	{ method: 'PUT', body: JSON.stringify(payload) }
)
export const deleteRule = (tenantId, ruleId) => request(
	`/api/tenants/${tenantId}/rules/${ruleId}`,
	{ method: 'DELETE' }
)
export const getWorkspaces = (tenantId) => request(`/api/tenants/${tenantId}/workspaces`)
export const createWorkspace = (tenantId, payload) => request(
	`/api/tenants/${tenantId}/workspaces`,
	{ method: 'POST', body: JSON.stringify(payload) }
)
export const updateWorkspace = (tenantId, workspaceId, payload) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}`,
	{ method: 'PUT', body: JSON.stringify(payload) }
)
export const deleteWorkspace = (tenantId, workspaceId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}`,
	{ method: 'DELETE' }
)
export const getWorkspaceScopes = (tenantId, workspaceId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/scopes`
)
export const updateWorkspaceScopes = (tenantId, workspaceId, payload) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/scopes`,
	{ method: 'PUT', body: JSON.stringify(payload) }
)
export const getChannels = (tenantId, workspaceId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/channels`
)
export const createChannel = (tenantId, workspaceId, payload) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/channels`,
	{ method: 'POST', body: JSON.stringify(payload) }
)
export const updateChannel = (tenantId, workspaceId, channelId, payload) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/channels/${channelId}`,
	{ method: 'PUT', body: JSON.stringify(payload) }
)
export const deleteChannel = (tenantId, workspaceId, channelId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/channels/${channelId}`,
	{ method: 'DELETE' }
)
export const getWorkspaceRules = (tenantId, workspaceId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/rules`
)
export const createWorkspaceRule = (tenantId, workspaceId, payload) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/rules`,
	{ method: 'POST', body: JSON.stringify(payload) }
)
export const updateWorkspaceRule = (tenantId, workspaceId, ruleId, payload) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/rules/${ruleId}`,
	{ method: 'PUT', body: JSON.stringify(payload) }
)
export const deleteWorkspaceRule = (tenantId, workspaceId, ruleId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/rules/${ruleId}`,
	{ method: 'DELETE' }
)
export const startMcpServerOAuth = (tenantId, workspaceId, serverId, payload = {}) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/mcp-servers/${serverId}/oauth/start`,
	{ method: 'POST', body: JSON.stringify(payload) }
)
export const discoverMcpServerOAuth = (tenantId, workspaceId, serverId, payload = {}) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/mcp-servers/${serverId}/oauth/discover`,
	{ method: 'POST', body: JSON.stringify(payload) }
)
export const cloneMcpServerOAuthFallback = (tenantId, workspaceId, serverId, payload = {}) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/mcp-servers/${serverId}/oauth/clone-fallback`,
	{ method: 'POST', body: JSON.stringify(payload) }
)
export const logoutMcpServerOAuth = (tenantId, workspaceId, serverId, payload = {}) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/mcp-servers/${serverId}/oauth/logout`,
	{ method: 'POST', body: JSON.stringify(payload) }
)
export const getMcpOidcProviders = (tenantId, workspaceId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/mcp-oauth-providers`
)
export const createMcpOidcProvider = (tenantId, workspaceId, payload) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/mcp-oauth-providers`,
	{ method: 'POST', body: JSON.stringify(payload) }
)
export const updateMcpOidcProvider = (tenantId, workspaceId, providerId, payload) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/mcp-oauth-providers/${providerId}`,
	{ method: 'PUT', body: JSON.stringify(payload) }
)
export const deleteMcpOidcProvider = (tenantId, workspaceId, providerId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/mcp-oauth-providers/${providerId}`,
	{ method: 'DELETE' }
)
export const getMcpServers = (tenantId, workspaceId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/mcp-servers`
)
export const createMcpServer = (tenantId, workspaceId, payload) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/mcp-servers`,
	{ method: 'POST', body: JSON.stringify(payload) }
)
export const updateMcpServer = (tenantId, workspaceId, serverId, payload) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/mcp-servers/${serverId}`,
	{ method: 'PUT', body: JSON.stringify(payload) }
)
export const deleteMcpServer = (tenantId, workspaceId, serverId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/mcp-servers/${serverId}`,
	{ method: 'DELETE' }
)
export const getRecordings = (tenantId, workspaceId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/recordings`
)
export const getRecording = (tenantId, workspaceId, recordingId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/recordings/${recordingId}`
)
export const createRecording = (tenantId, workspaceId, payload) => {
	const title = encodeURIComponent(payload?.title || '')
	const duration = payload?.duration_seconds || 0
	const startedAt = payload?.started_at ? `&started_at=${encodeURIComponent(payload.started_at)}` : ''
	const ruleIds = Array.isArray(payload?.rule_ids) ? JSON.stringify(payload.rule_ids) : ''
	const rulesParam = ruleIds ? `&rule_ids=${encodeURIComponent(ruleIds)}` : ''
	const query = `title=${title}&duration_seconds=${duration}${startedAt}${rulesParam}`
	return requestBody(
		`/api/tenants/${tenantId}/workspaces/${workspaceId}/recordings?${query}`,
		payload?.blob,
		{ headers: { 'Content-Type': payload?.mime_type || 'application/octet-stream' } }
	)
}
export const downloadRecordingAudio = (tenantId, workspaceId, recordingId) => requestBlob(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/recordings/${recordingId}/audio`
)
export const deleteRecording = (tenantId, workspaceId, recordingId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/recordings/${recordingId}`,
	{ method: 'DELETE' }
)
export const updateRecording = (tenantId, workspaceId, recordingId, payload) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/recordings/${recordingId}`,
	{ method: 'PUT', body: JSON.stringify(payload) }
)
export const probeMcpServer = (tenantId, workspaceId, serverId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/mcp-servers/${serverId}/probe`,
	{ method: 'POST' }
)
export const getMcpServerTools = (tenantId, workspaceId, serverId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/mcp-servers/${serverId}/tools`
)
export const callMcpTool = (tenantId, workspaceId, serverId, payload) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/mcp-servers/${serverId}/tools/call`,
	{ method: 'POST', body: JSON.stringify(payload) }
)
export const updateMcpServerTool = (tenantId, workspaceId, serverId, toolId, payload) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/mcp-servers/${serverId}/tools/${toolId}`,
	{ method: 'PUT', body: JSON.stringify(payload) }
)
export const updateMcpServerTools = (tenantId, workspaceId, serverId, payload) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/mcp-servers/${serverId}/tools`,
	{ method: 'PUT', body: JSON.stringify(payload) }
)
export const refreshMcpServerTools = (tenantId, workspaceId, serverId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/mcp-servers/${serverId}/tools/refresh`,
	{ method: 'POST' }
)
export const readMcpResource = (tenantId, workspaceId, serverId, payload) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/mcp-servers/${serverId}/resources/read`,
	{ method: 'POST', body: JSON.stringify(payload) }
)
export const getMcpServerApplications = (tenantId, workspaceId, serverId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/mcp-servers/${serverId}/applications`
)
export const getMcpServerDefinition = (tenantId, workspaceId, serverId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/mcp-servers/${serverId}/definition`
)
export const updateMcpServerApplication = (tenantId, workspaceId, serverId, applicationId, payload) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/mcp-servers/${serverId}/applications/${applicationId}`,
	{ method: 'PUT', body: JSON.stringify(payload) }
)
export const getMcpServerPolicies = (tenantId, workspaceId, serverId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/mcp-servers/${serverId}/policies`
)
export const getMcpServerOverrides = (tenantId, workspaceId, serverId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/mcp-servers/${serverId}/overrides`
)
export const getMcpCallLogs = (tenantId, workspaceId, query = {}) => {
	const params = new URLSearchParams()
	Object.entries(query || {})
		.forEach(([key, value]) => {
			if (value !== undefined && value !== null && value !== '') {
				params.set(key, value)
			}
		})
	const suffix = params.toString() ? `?${params.toString()}` : ''
	return request(`/api/tenants/${tenantId}/workspaces/${workspaceId}/mcp-call-logs${suffix}`)
}
export const getScriptCallLogs = (tenantId, workspaceId, query = {}) => {
	const params = new URLSearchParams()
	Object.entries(query || {})
		.forEach(([key, value]) => {
			if (value !== undefined && value !== null && value !== '') {
				params.set(key, value)
			}
		})
	const suffix = params.toString() ? `?${params.toString()}` : ''
	return request(`/api/tenants/${tenantId}/workspaces/${workspaceId}/script-logs${suffix}`)
}
export const getNotificationLogs = (tenantId, workspaceId, query = {}) => {
	const params = new URLSearchParams()
	if (query.before) {
		params.set('before', query.before)
	}
	if (query.limit) {
		params.set('limit', query.limit)
	}
	return request(`/api/tenants/${tenantId}/workspaces/${workspaceId}/notification-logs?${params.toString()}`)
}
export const getPushStatus = (tenantId, workspaceId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/push`
)
export const updatePushPreference = (tenantId, workspaceId, payload) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/push`,
	{ method: 'PUT', body: JSON.stringify(payload || {}) }
)
export const registerPushSubscription = (payload) => request(
	`/api/push/subscriptions`,
	{ method: 'POST', body: JSON.stringify(payload || {}) }
)
export const getPushVapidKey = () => request(`/api/push/vapid`)
export const createMcpServerPolicy = (tenantId, workspaceId, serverId, payload) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/mcp-servers/${serverId}/policies`,
	{ method: 'POST', body: JSON.stringify(payload) }
)
export const createMcpServerOverride = (tenantId, workspaceId, serverId, payload) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/mcp-servers/${serverId}/overrides`,
	{ method: 'POST', body: JSON.stringify(payload) }
)
export const updateMcpServerPolicy = (tenantId, workspaceId, serverId, policyId, payload) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/mcp-servers/${serverId}/policies/${policyId}`,
	{ method: 'PUT', body: JSON.stringify(payload) }
)
export const updateMcpServerOverride = (tenantId, workspaceId, serverId, overrideId, payload) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/mcp-servers/${serverId}/overrides/${overrideId}`,
	{ method: 'PUT', body: JSON.stringify(payload) }
)
export const deleteMcpServerPolicy = (tenantId, workspaceId, serverId, policyId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/mcp-servers/${serverId}/policies/${policyId}`,
	{ method: 'DELETE' }
)
export const deleteMcpServerOverride = (tenantId, workspaceId, serverId, overrideId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/mcp-servers/${serverId}/overrides/${overrideId}`,
	{ method: 'DELETE' }
)
export const getWorkspaceTags = (tenantId, workspaceId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/tags`
)
export const getWorkspaceMcpApplications = (tenantId, workspaceId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/mcp-applications`
)
export const apiBaseUrl = () => API_URL
export const createWorkspaceTag = (tenantId, workspaceId, payload) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/tags`,
	{ method: 'POST', body: JSON.stringify(payload) }
)
export const updateWorkspaceTag = (tenantId, workspaceId, categoryId, payload) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/tags/${categoryId}`,
	{ method: 'PUT', body: JSON.stringify(payload) }
)
export const deleteWorkspaceTag = (tenantId, workspaceId, categoryId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/tags/${categoryId}`,
	{ method: 'DELETE' }
)
export const createWorkspaceTagValue = (tenantId, workspaceId, categoryId, payload) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/tags/${categoryId}/values`,
	{ method: 'POST', body: JSON.stringify(payload) }
)
export const updateWorkspaceTagValue = (tenantId, workspaceId, categoryId, valueId, payload) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/tags/${categoryId}/values/${valueId}`,
	{ method: 'PUT', body: JSON.stringify(payload) }
)
export const deleteWorkspaceTagValue = (tenantId, workspaceId, categoryId, valueId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/tags/${categoryId}/values/${valueId}`,
	{ method: 'DELETE' }
)
export const getWorkspaceTagStates = (tenantId, workspaceId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/tag-states`
)
export const updateWorkspaceTagState = (tenantId, workspaceId, categoryId, payload) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/tag-states/${categoryId}`,
	{ method: 'PUT', body: JSON.stringify(payload) }
)
export const getWorkflows = (tenantId, workspaceId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/workflows`
)
export const createWorkflow = (tenantId, workspaceId, payload) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/workflows`,
	{ method: 'POST', body: JSON.stringify(payload) }
)
export const updateWorkflow = (tenantId, workspaceId, workflowId, payload) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/workflows/${workflowId}`,
	{ method: 'PUT', body: JSON.stringify(payload) }
)
export const getWorkflowDraft = (tenantId, workspaceId, workflowId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/workflows/${workflowId}/draft`
)
export const updateWorkflowDraft = (tenantId, workspaceId, workflowId, payload) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/workflows/${workflowId}/draft`,
	{ method: 'PUT', body: JSON.stringify(payload) }
)
export const approveWorkflow = (tenantId, workspaceId, workflowId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/workflows/${workflowId}/approve`,
	{ method: 'POST' }
)
export const deleteWorkflowDraft = (tenantId, workspaceId, workflowId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/workflows/${workflowId}/draft`,
	{ method: 'DELETE' }
)
export const deleteWorkflow = (tenantId, workspaceId, workflowId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/workflows/${workflowId}`,
	{ method: 'DELETE' }
)
export const getSessions = (tenantId, workspaceId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/sessions`
)
export const getHistorySessions = (tenantId, workspaceId, query = {}) => {
	const params = new URLSearchParams()
	if (query.offset !== undefined && query.offset !== null) {
		params.set('offset', String(query.offset))
	}
	if (query.limit !== undefined && query.limit !== null) {
		params.set('limit', String(query.limit))
	}
	const suffix = params.toString() ? `?${params.toString()}` : ''
	return request(`/api/tenants/${tenantId}/workspaces/${workspaceId}/sessions/history${suffix}`)
}
export const createSession = (tenantId, workspaceId, payload) => {
	const body = payload || {}
	if (body.participants && !body.participant_ids) {
		body.participant_ids = body.participants
	}
	return request(
		`/api/tenants/${tenantId}/workspaces/${workspaceId}/sessions`,
		{ method: 'POST', body: JSON.stringify(body) }
	)
}
export const updateSession = (tenantId, workspaceId, sessionId, payload) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/sessions/${sessionId}`,
	{ method: 'PUT', body: JSON.stringify(payload) }
)
export const archiveSession = (tenantId, workspaceId, sessionId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/sessions/${sessionId}/archive`,
	{ method: 'POST' }
)
export const reactivateSession = (tenantId, workspaceId, sessionId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/sessions/${sessionId}/reactivate`,
	{ method: 'POST' }
)
export const deleteSession = (tenantId, workspaceId, sessionId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/sessions/${sessionId}`,
	{ method: 'DELETE' }
)
export const getSessionEvents = (tenantId, workspaceId, sessionId, options = {}) => {
	const params = new URLSearchParams()
	if (options.epoch !== undefined && options.epoch !== null && options.epoch !== '') {
		params.set('epoch', String(options.epoch))
	}
	if (options.limit !== undefined && options.limit !== null) {
		params.set('limit', String(options.limit))
	}
	if (options.offset !== undefined && options.offset !== null) {
		params.set('offset', String(options.offset))
	}
	if (options.reverse !== undefined && options.reverse !== null) {
		params.set('reverse', options.reverse ? 'true' : 'false')
	}
	const query = params.toString()
	const suffix = query ? `?${query}` : ''
	return request(`/api/tenants/${tenantId}/workspaces/${workspaceId}/sessions/${sessionId}/events${suffix}`)
}
export const getSessionSummary = (tenantId, workspaceId, sessionId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/sessions/${sessionId}/summary`
)
export const getSessionTechnicalDetails = (tenantId, workspaceId, sessionId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/sessions/${sessionId}/technical`
)
export const getSessionPrompt = (tenantId, workspaceId, sessionId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/sessions/${sessionId}/prompt`
)
export const pruneSession = (tenantId, workspaceId, sessionId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/sessions/${sessionId}/prune`,
	{ method: 'POST' }
)
export const getPagesCatalog = (tenantId, workspaceId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/pages/catalog`
)
export const getPages = (tenantId, workspaceId, params = {}) => {
	const query = new URLSearchParams()
	if (params.namespace) {
		query.set('namespace', params.namespace)
	}
	if (params.non_recursive) {
		query.set('non_recursive', 'true')
	}
	const suffix = query.toString()
	return request(`/api/tenants/${tenantId}/workspaces/${workspaceId}/pages${suffix ? `?${suffix}` : ''}`)
}
export const getInstalledPages = (tenantId, workspaceId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/pages/installed`
)
export const getInstalledPageDetails = (tenantId, workspaceId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/pages/installed/details`
)
export const validatePageSlug = (tenantId, workspaceId, params = {}) => {
	const query = new URLSearchParams()
	if (params.namespace) {
		query.set('namespace', params.namespace)
	}
	if (params.slug) {
		query.set('slug', params.slug)
	}
	if (params.exclude_page_id) {
		query.set('exclude_page_id', params.exclude_page_id)
	}
	const suffix = query.toString()
	return request(
		`/api/tenants/${tenantId}/workspaces/${workspaceId}/pages/slug-validation${suffix ? `?${suffix}` : ''}`
	)
}
export const getPage = (tenantId, workspaceId, pageId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/pages/${pageId}`
)
export const getPageBySlug = (tenantId, workspaceId, slug) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/pages/slug/${slug}`
)
export const getPageBundle = (tenantId, workspaceId, pageId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/pages/${pageId}/bundle`
)
export const uploadAttachment = async(tenantId, workspaceId, pageId, fileOrBlob, options = {}) => {
	if (!fileOrBlob) {
		throw new Error('Attachment file is required')
	}
	const filename = options.filename || fileOrBlob.name || ''
	const query = filename ? `?filename=${encodeURIComponent(filename)}` : ''
	return requestBody(
		`/api/tenants/${tenantId}/workspaces/${workspaceId}/pages/${pageId}/attachments${query}`,
		fileOrBlob,
		{ headers: fileOrBlob.type ? { 'Content-Type': fileOrBlob.type } : {} }
	)
}
export const createAttachmentUrl = (tenantId, workspaceId, pageId, blobUri, options = {}) => {
	if (!blobUri) {
		throw new Error('blobUri is required')
	}
	const query = new URLSearchParams()
	query.set('blob_uri', blobUri)
	if (options.filename) {
		query.set('filename', options.filename)
	}
	return request(
		`/api/tenants/${tenantId}/workspaces/${workspaceId}/pages/${pageId}/attachments/public-link?${query.toString()}`
	)
}
export const callWorkspaceTool = (tenantId, workspaceId, payload, options = {}) => {
	const tags = Array.isArray(options.tags) ? options.tags.filter((tag) => tag) : []
	const query = tags.length ? `?${tags.map((tag) => `tag=${encodeURIComponent(tag)}`).join('&')}` : ''
	return request(
		`/api/tenants/${tenantId}/workspaces/${workspaceId}/tools/call${query}`,
		{ method: 'POST', body: JSON.stringify(payload || {}) }
	)
}
export const callPageScript = (tenantId, workspaceId, path, input = null) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/pages/scripts/call`,
	{ method: 'POST', body: JSON.stringify({ path, input }) }
)
export const getWorkspaceUsers = async(tenantId) => {
	const members = await getTenantMembers(tenantId)
	const list = Array.isArray(members) ? members : []
	return {
		users: list.map((member) => ({
			id: member.user_id,
			email: member.email,
			name: member.display_name || member.email,
			avatar_url: member.avatar_url || null
		}))
	}
}
export const createPage = (tenantId, workspaceId, payload) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/pages`,
	{ method: 'POST', body: JSON.stringify(payload) }
)
export const exportPage = (tenantId, workspaceId, pageId, includeDependencies) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/pages/${pageId}/export?include_dependencies=${includeDependencies ? 'true' : 'false'}`
)
export const importPages = (tenantId, workspaceId, payload) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/pages/import`,
	{ method: 'POST', body: JSON.stringify(payload || {}) }
)
export const updatePage = (tenantId, workspaceId, pageId, payload) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/pages/${pageId}`,
	{ method: 'PUT', body: JSON.stringify(payload) }
)
export const getPageVersions = (tenantId, workspaceId, pageId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/pages/${pageId}/versions`
)
export const getPageDraft = (tenantId, workspaceId, pageId, params = {}) => {
	const query = new URLSearchParams()
	if (params.assistant_id) {
		query.set('assistant_id', params.assistant_id)
	}
	const suffix = query.toString()
	return request(
		`/api/tenants/${tenantId}/workspaces/${workspaceId}/pages/${pageId}/draft${suffix ? `?${suffix}` : ''}`
	)
}
export const updatePageDraft = (tenantId, workspaceId, pageId, payload) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/pages/${pageId}/draft`,
	{ method: 'PUT', body: JSON.stringify(payload) }
)
export const compilePageDraft = (tenantId, workspaceId, pageId, payload) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/pages/${pageId}/compile`,
	{ method: 'POST', body: JSON.stringify(payload) }
)
export const approvePage = (tenantId, workspaceId, pageId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/pages/${pageId}/approve`,
	{ method: 'POST' }
)
export const deletePageDraft = (tenantId, workspaceId, pageId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/pages/${pageId}/draft`,
	{ method: 'DELETE' }
)
export const deletePage = (tenantId, workspaceId, pageId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/pages/${pageId}`,
	{ method: 'DELETE' }
)
export const installPage = (tenantId, workspaceId, pageId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/pages/${pageId}/install`,
	{ method: 'POST' }
)
export const uninstallPage = (tenantId, workspaceId, pageId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/pages/${pageId}/uninstall`,
	{ method: 'POST' }
)
export const getScriptsCatalog = (tenantId, workspaceId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/scripts/catalog`
)
export const getScripts = (tenantId, workspaceId, params = {}) => {
	const query = new URLSearchParams()
	if (params.include_workflow) {
		query.set('include_workflow', 'true')
	}
	if (params.namespace) {
		query.set('namespace', params.namespace)
	}
	if (params.non_recursive) {
		query.set('non_recursive', 'true')
	}
	if (params.search) {
		query.set('search', params.search)
	}
	const suffix = query.toString()
	return request(`/api/tenants/${tenantId}/workspaces/${workspaceId}/scripts${suffix ? `?${suffix}` : ''}`)
}
export const getScript = (tenantId, workspaceId, scriptId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/scripts/${scriptId}`
)
export const createScript = (tenantId, workspaceId, payload) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/scripts`,
	{ method: 'POST', body: JSON.stringify(payload) }
)
export const updateScript = (tenantId, workspaceId, scriptId, payload) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/scripts/${scriptId}`,
	{ method: 'PUT', body: JSON.stringify(payload) }
)
export const getScriptVersions = (tenantId, workspaceId, scriptId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/scripts/${scriptId}/versions`
)
export const getScriptDraft = (tenantId, workspaceId, scriptId, params = {}) => {
	const query = new URLSearchParams()
	if (params.assistant_id) {
		query.set('assistant_id', params.assistant_id)
	}
	const suffix = query.toString()
	return request(
		`/api/tenants/${tenantId}/workspaces/${workspaceId}/scripts/${scriptId}/draft${suffix ? `?${suffix}` : ''}`
	)
}
export const updateScriptDraft = (tenantId, workspaceId, scriptId, payload) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/scripts/${scriptId}/draft`,
	{ method: 'PUT', body: JSON.stringify(payload) }
)
export const approveScript = (tenantId, workspaceId, scriptId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/scripts/${scriptId}/approve`,
	{ method: 'POST' }
)
export const deleteScriptDraft = (tenantId, workspaceId, scriptId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/scripts/${scriptId}/draft`,
	{ method: 'DELETE' }
)
export const deleteScript = (tenantId, workspaceId, scriptId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/scripts/${scriptId}`,
	{ method: 'DELETE' }
)
export const runScript = (tenantId, workspaceId, scriptId, payload) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/scripts/${scriptId}/run`,
	{ method: 'POST', body: JSON.stringify(payload || {}) }
)
export const getPromptTemplates = (tenantId, workspaceId) => {
	const query = workspaceId ? `?workspaceId=${workspaceId}` : ''
	return request(`/api/tenants/${tenantId}/prompt-templates${query}`)
}
export const updateTenantPromptTemplate = (tenantId, payload) => request(
	`/api/tenants/${tenantId}/prompt-templates`,
	{ method: 'PUT', body: JSON.stringify(payload) }
)
export const updateWorkspacePromptTemplate = (tenantId, workspaceId, payload) => request(
	`/api/tenants/${tenantId}/prompt-templates/workspaces/${workspaceId}`,
	{ method: 'PUT', body: JSON.stringify(payload) }
)
export const updateSessionScopes = (tenantId, workspaceId, sessionId, payload) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/sessions/${sessionId}/scopes`,
	{ method: 'PUT', body: JSON.stringify(payload) }
)
export const uploadSessionVoiceRecording = (tenantId, workspaceId, sessionId, body, mimeType) => requestBody(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/sessions/${sessionId}/voice-transcription`,
	body,
	{ method: 'POST', headers: { 'Content-Type': mimeType || 'audio/wav' } }
)
export const getSessionCanvases = (tenantId, workspaceId, sessionId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/sessions/${sessionId}/canvases`
)
export const addSessionParticipant = (tenantId, workspaceId, sessionId, payload) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/sessions/${sessionId}/participants`,
	{ method: 'POST', body: JSON.stringify(payload || {}) }
)
export const updateSessionTags = (tenantId, workspaceId, sessionId, payload) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/sessions/${sessionId}/tags`,
	{ method: 'PUT', body: JSON.stringify(payload) }
)
export const addSessionMcpServer = (tenantId, workspaceId, sessionId, payload) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/sessions/${sessionId}/mcp-servers`,
	{ method: 'PUT', body: JSON.stringify(payload) }
)
export const openWorkspaceSocket = async(tenantId, workspaceId, onMessage) => {
	const refreshed = await refreshAccessToken()
	const token = refreshed || (await ensureAccessToken())
	if (!token) {
		throw new Error('No access token')
	}
	const base = new URL(API_URL)
	base.protocol = base.protocol === 'https:' ? 'wss:' : 'ws:'
	base.pathname = `/api/tenants/${tenantId}/workspaces/${workspaceId}/ws`
	base.searchParams.set('token', token)
	const socket = new WebSocket(base.toString())
	socket.onmessage = (event) => {
		try {
			const payload = JSON.parse(event.data)
			onMessage?.(payload)
		}
		catch {}
	}
	return socket
}
export const getSkills = (tenantId) => request(`/api/tenants/${tenantId}/skills`)
export const getWorkspaceSkills = (tenantId, workspaceId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/skills`
)
export const createSkill = (tenantId, payload) => request(
	`/api/tenants/${tenantId}/skills`,
	{ method: 'POST', body: JSON.stringify(payload) }
)
export const createWorkspaceSkill = (tenantId, workspaceId, payload) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/skills`,
	{ method: 'POST', body: JSON.stringify(payload) }
)
export const updateSkill = (tenantId, skillId, payload) => request(
	`/api/tenants/${tenantId}/skills/${skillId}`,
	{ method: 'PUT', body: JSON.stringify(payload) }
)
export const updateWorkspaceSkill = (tenantId, workspaceId, skillId, payload) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/skills/${skillId}`,
	{ method: 'PUT', body: JSON.stringify(payload) }
)
export const deleteSkill = (tenantId, skillId) => request(
	`/api/tenants/${tenantId}/skills/${skillId}`,
	{ method: 'DELETE' }
)
export const deleteWorkspaceSkill = (tenantId, workspaceId, skillId) => request(
	`/api/tenants/${tenantId}/workspaces/${workspaceId}/skills/${skillId}`,
	{ method: 'DELETE' }
)
export const getModels = (tenantId) => request(`/api/tenants/${tenantId}/models`)
export const getModelProviders = (tenantId) => request(`/api/tenants/${tenantId}/models/providers`)
export const createModel = (tenantId, payload) => request(
	`/api/tenants/${tenantId}/models`,
	{ method: 'POST', body: JSON.stringify(payload) }
)
export const updateModel = (tenantId, modelId, payload) => request(
	`/api/tenants/${tenantId}/models/${modelId}`,
	{ method: 'PUT', body: JSON.stringify(payload) }
)
export const deleteModel = (tenantId, modelId) => request(
	`/api/tenants/${tenantId}/models/${modelId}`,
	{ method: 'DELETE' }
)
export const getAssistants = (tenantId) => request(`/api/tenants/${tenantId}/assistants`)
export const getWorkspaceAssistants = (tenantId, workspaceId) => request(
	`/api/tenants/${tenantId}/assistants/workspaces/${workspaceId}`
)
export const getAssistant = (tenantId, assistantId) => request(
	`/api/tenants/${tenantId}/assistants/${assistantId}`
)
export const createAssistant = (tenantId, payload) => request(
	`/api/tenants/${tenantId}/assistants`,
	{ method: 'POST', body: JSON.stringify(payload) }
)
export const updateAssistant = (tenantId, assistantId, payload) => request(
	`/api/tenants/${tenantId}/assistants/${assistantId}`,
	{ method: 'PUT', body: JSON.stringify(payload) }
)
export const deleteAssistant = (tenantId, assistantId) => request(
	`/api/tenants/${tenantId}/assistants/${assistantId}`,
	{ method: 'DELETE' }
)
export const loadSession = () => {
	const raw = localStorage.getItem('polymr.session')
	return raw ? JSON.parse(raw) : null
}
export const saveSession = (session) => {
	const { accessToken: token, ...rest } = session
	accessToken = token || accessToken
	localStorage.setItem('polymr.session', JSON.stringify(rest))
}
export const updateSessionTenants = (tenants) => {
	localStorage.setItem('polymr.tenants', JSON.stringify(tenants || []))
}
export const loadSessionTenants = () => {
	const raw = localStorage.getItem('polymr.tenants')
	return raw ? JSON.parse(raw) : []
}
export const loadActiveTenant = () => localStorage.getItem('polymr.activeTenant')
export const saveActiveTenant = (tenantId) => {
	if (tenantId) {
		localStorage.setItem('polymr.activeTenant', tenantId)
	}
}
export const clearSession = () => {
	accessToken = null
	localStorage.removeItem('polymr.session')
	localStorage.removeItem('polymr.tenants')
}
export const setAccessToken = (token) => {
	accessToken = token
}
export const ensureAccessToken = async() => {
	if (accessToken) {
		return accessToken
	}
	return refreshAccessToken()
}
export const logout = () => request('/api/auth/logout', { method: 'POST' })
