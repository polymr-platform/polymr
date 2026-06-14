import { callPageScript, callWorkspaceTool, createAttachmentUrl, createSession, getPages, uploadAttachment } from '../api';
const toPartSlug = (value) => {
	return String(value || '')
		.trim()
		.toLowerCase()
		.replace(/[^a-z0-9]+/g, '_')
		.replace(/_+/g, '_')
		.replace(/^_+|_+$/g, '')
}
export const normalizePageIdentifier = (value) => {
	let normalized = String(value || '').trim()
	normalized = normalized.replace(/^\/+|\/+$/g, '')
	if (!normalized) {
		return ''
	}
	if (normalized.toLowerCase().endsWith('.vue')) {
		normalized = normalized.slice(0, -4)
	}
	if (!normalized.includes('/')) {
		return normalized.toLowerCase()
	}
	return normalized.split('/').map((segment) => toPartSlug(segment)).filter(Boolean).join('-')
}
const routeParamPattern = /\{([^}]+)\}|:([A-Za-z0-9_]+)/g
const fillRouteSuffix = (routeSuffix, params) => {
	const filledSuffix = String(routeSuffix || '')
		.replace(
			routeParamPattern,
			(_, braceKey, colonKey) => {
				const key = braceKey || colonKey
				const value = params?.[key]
				if (value === undefined || value === null || value === '') {
					throw new Error(`Missing route param: ${key}`)
				}
				return encodeURIComponent(String(value))
			}
		)
	if (!filledSuffix) {
		return ''
	}
	return filledSuffix.startsWith('/') ? filledSuffix : `/${filledSuffix}`
}
export const navigateToWorkspacePage = async({ tenantId, workspaceId, router, path, params = {}, installedPagesLoader = null }) => {
	const normalizedIdentifier = normalizePageIdentifier(path)
	if (!normalizedIdentifier) {
		throw new Error('Page path is required')
	}
	const pages = installedPagesLoader ? await installedPagesLoader() : await getPages(tenantId, workspaceId)
	const availablePages = Array.isArray(pages) ? pages : []
	const targetPage = availablePages.find((entry) => normalizePageIdentifier(entry?.slug) === normalizedIdentifier)
	if (!targetPage?.slug) {
		throw new Error(`Unknown page path: ${path}`)
	}
	const routeParamNames = [...String(targetPage.route_suffix || '').matchAll(routeParamPattern)].map((match) => match[1] || match[2])
		.filter(Boolean)
	const query = { ...(params || {}) }
	routeParamNames.forEach((name) => {
			delete query[name]
		})
	const routePath = `/workspace/${encodeURIComponent(String(workspaceId))}/pages/${encodeURIComponent(targetPage.slug)}${fillRouteSuffix(targetPage.route_suffix, params)}`
	return router.push({ path: routePath, query })
}
export const buildPolymrRuntimeApi = ({
	tenantId,
	workspaceId,
	router = null,
	notify,
	getUsers,
	callScriptEnabled = true,
	createSessionEnabled = true,
	navigateEnabled = true,
	attachmentTargetId = null,
	installedPagesLoader = null,
}) => {
	const unwrapToolResponse = (response, options = {}) => {
		if (response == null) {
			return null
		}
		if (options?.raw) {
			return response
		}
		return response?.structuredContent ?? null
	}
	return {
		getUsers: async() => getUsers(),
		callTool: async(tool, args = {}, options = {}) => {
			let toolName = tool
			let toolArgs = args
			let toolOptions = options
			if (tool && typeof tool === 'object' && !Array.isArray(tool)) {
				toolName = tool.tool || tool.tool_name || tool.service || tool.name
				toolArgs = tool.arguments || {}
				toolOptions = tool.options || args || {}
			}
			if (!toolName) {
				throw new Error('Tool is required')
			}
			const response = await callWorkspaceTool(tenantId, workspaceId, { service: toolName, arguments: toolArgs }, toolOptions)
			return unwrapToolResponse(response, toolOptions)
		},
		callScript: async(path, input = null) => {
			if (!callScriptEnabled) {
				throw new Error('callScript is not available in this runtime')
			}
			if (!path) {
				throw new Error('Script path is required')
			}
			return callPageScript(tenantId, workspaceId, path, input)
		},
		createAttachmentUrl: async(blobUri, options = {}) => {
			if (!attachmentTargetId || !attachmentTargetId()) {
				throw new Error('Attachment target is required')
			}
			return createAttachmentUrl(tenantId, workspaceId, attachmentTargetId(), blobUri, options)
		},
		createSession: async(payload = {}) => {
			if (!createSessionEnabled) {
				throw new Error('createSession is not available in this runtime')
			}
			return createSession(tenantId, workspaceId, payload)
		},
		navigateTo: async(path, params = {}) => {
			if (!navigateEnabled || !router) {
				throw new Error('navigateTo is not available in this runtime')
			}
			return navigateToWorkspacePage({
				tenantId,
				workspaceId,
				router,
				path,
				params,
				installedPagesLoader
			})
		},
		notify,
		uploadAttachment: async(fileOrBlob, options = {}) => {
			if (!attachmentTargetId || !attachmentTargetId()) {
				throw new Error('Attachment target is required')
			}
			return uploadAttachment(tenantId, workspaceId, attachmentTargetId(), fileOrBlob, options)
		}
	}
}
