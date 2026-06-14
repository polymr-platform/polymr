import * as Vue from 'vue';
import MarkdownMessage from '../components/MarkdownMessage.vue';
import ConfirmModal from '../components/ConfirmModal.vue';
import { getPageBySlug } from '../api';
import { normalizePageIdentifier } from './polymrPageSupport';
const externalFrontendImportLoads = new Map()
const pageModuleLoads = new Map()
const polymrPageImportPattern = /PolymrPages\[("|')([^"']+)\1\]/g
export const ensurePolymrRuntimeGlobals = (api = null) => {
	if (!window.Vue) {
		window.Vue = Vue
	}
	if (!window.PolymrComponents) {
		window.PolymrComponents = { MarkdownMessage, ConfirmModal }
	}
	if (!window.PolymrExternalImports) {
		window.PolymrExternalImports = {}
	}
	if (!window.PolymrPages) {
		window.PolymrPages = {}
	}
	if (api) {
		window.PolymrApi = api
	}
}
const ensureExternalFrontendStylesheets = async(specifier, cssUrls) => {
	const stylesheetUrls = Array.isArray(cssUrls)
		? cssUrls.map((value) => String(value || '').trim()).filter((value) => value.length > 0)
		: []
	for (const stylesheetUrl of stylesheetUrls) {
		await new Promise((resolve, reject) => {
			const existing = document.querySelector(`link[data-polymr-external-import="${specifier}"][href="${stylesheetUrl}"]`)
			if (existing) {
				resolve()
				return
			}
			const link = document.createElement('link')
			link.rel = 'stylesheet'
			link.href = stylesheetUrl
			link.dataset.polymrExternalImport = specifier
			link.onload = () => resolve()
			link.onerror = () => reject(new Error(`Unable to load external stylesheet: ${specifier}`))
			document.head.appendChild(link)
		})
	}
}
const resolveExternalFrontendImport = async({ specifier, sourceUrl, globalName }) => {
	if (window.PolymrExternalImports[specifier]) {
		return
	}
	const bindGlobal = () => {
		const resolved = window[globalName]
		if (typeof resolved === 'undefined') {
			return false
		}
		window.PolymrExternalImports[specifier] = resolved
		return true
	}
	if (bindGlobal()) {
		return
	}
	const existingLoad = externalFrontendImportLoads.get(specifier)
	if (existingLoad) {
		await existingLoad
		return
	}
	const loadPromise = new Promise((resolve, reject) => {
		const finishIfBound = () => {
			if (bindGlobal()) {
				resolve()
				return true
			}
			return false
		}
		const failMissingGlobal = () => reject(
			new Error(`External import did not expose global '${globalName}' for '${specifier}' from ${sourceUrl}`)
		)
		const existing = document.querySelector(`script[data-polymr-external-import="${specifier}"]`)
		if (existing) {
			const loaded = existing.dataset.polymrExternalImportLoaded === 'true'
			const failed = existing.dataset.polymrExternalImportFailed === 'true'
			if (finishIfBound()) {
				return
			}
			if (failed) {
				reject(new Error(`Unable to load external import: ${specifier} from ${sourceUrl}`))
				return
			}
			if (loaded) {
				failMissingGlobal()
				return
			}
			existing.addEventListener(
				'load',
				() => {
					if (!finishIfBound()) {
						failMissingGlobal()
					}
				},
				{ once: true }
			)
			existing.addEventListener(
				'error',
				() => reject(new Error(`Unable to load external import: ${specifier} from ${sourceUrl}`)),
				{ once: true }
			)
			return
		}
		const script = document.createElement('script')
		script.src = sourceUrl
		script.async = true
		script.dataset.polymrExternalImport = specifier
		script.onload = () => {
			script.dataset.polymrExternalImportLoaded = 'true'
			if (!finishIfBound()) {
				failMissingGlobal()
			}
		}
		script.onerror = () => {
			script.dataset.polymrExternalImportFailed = 'true'
			reject(new Error(`Unable to load external import: ${specifier} from ${sourceUrl}`))
		}
		document.head.appendChild(script)
	})
	externalFrontendImportLoads.set(specifier, loadPromise)
	try {
		await loadPromise
	}
	finally {
		externalFrontendImportLoads.delete(specifier)
	}
}
export const loadExternalFrontendImports = async(entries) => {
	ensurePolymrRuntimeGlobals()
	const imports = Array.isArray(entries) ? entries : []
	for (const entry of imports) {
		const specifier = String(entry?.specifier || '').trim()
		const sourceUrl = String(entry?.source_url || '').trim()
		const globalName = String(entry?.global_name || '').trim()
		const cssUrls = entry?.css_urls
		if (!specifier || !sourceUrl || !globalName) {
			continue
		}
		await ensureExternalFrontendStylesheets(specifier, cssUrls)
		await resolveExternalFrontendImport({ specifier, sourceUrl, globalName })
	}
}
const listReferencedPagePaths = (bundle) => {
	const source = String(bundle || '')
	const paths = new Set()
	for (const match of source.matchAll(polymrPageImportPattern)) {
		const path = String(match[2] || '').trim()
		if (path) {
			paths.add(path)
		}
	}
	return [...paths]
}
const loadReferencedPageModule = async({ tenantId, workspaceId, pagePath, api, pages }) => {
	if (!tenantId || !workspaceId || !pagePath) {
		return
	}
	if (pages[pagePath]) {
		return
	}
	const existingLoad = pageModuleLoads.get(pagePath)
	if (existingLoad) {
		await existingLoad
		return
	}
	const loadPromise = (async() => {
		const pageSlug = normalizePageIdentifier(pagePath)
		const runtime = await getPageBySlug(tenantId, workspaceId, pageSlug)
		await loadExternalFrontendImports(runtime?.external_frontend_imports)
		const nestedPages = { ...pages }
		await loadReferencedPageModules({
			tenantId,
			workspaceId,
			bundle: runtime?.compiled_bundle,
			api,
			pages: nestedPages
		})
		pages[pagePath] = instantiateCompiledBundle({ bundle: runtime?.compiled_bundle, api, pages: nestedPages })
	})()
	pageModuleLoads.set(pagePath, loadPromise)
	try {
		await loadPromise
	}
	finally {
		pageModuleLoads.delete(pagePath)
	}
}
export const loadReferencedPageModules = async({ tenantId, workspaceId, bundle, api = null, pages = null }) => {
	const targetPages = pages && typeof pages === 'object' ? pages : {}
	const pagePaths = listReferencedPagePaths(bundle)
	for (const pagePath of pagePaths) {
		await loadReferencedPageModule({
			tenantId,
			workspaceId,
			pagePath,
			api,
			pages: targetPages
		})
	}
	return targetPages
}
export const instantiateCompiledBundle = ({ bundle, api = null, pages = null }) => {
	ensurePolymrRuntimeGlobals(api)
	if (pages && typeof pages === 'object') {
		window.PolymrPages = pages
	}
	const factory = new Function('Vue', 'PolymrComponents', 'PolymrApi', 'PolymrPages', 'PolymrExternalImports', bundle || '')
	return factory(
		window.Vue,
		window.PolymrComponents,
		window.PolymrApi,
		window.PolymrPages,
		window.PolymrExternalImports
	)
}
