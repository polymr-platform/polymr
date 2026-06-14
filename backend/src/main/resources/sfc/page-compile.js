function normalizeNamedImports(spec) {
	const normalized = spec.trim().replace(/[{}]/g, '').trim()
	if (!normalized) {
		return '{}'
	}
	const parts = normalized.split(',')
		.map((entry) => entry.trim())
		.filter((entry) => entry.length > 0)
		.map((entry) => (entry.includes(' as ') ? entry.replace(' as ', ': ') : entry))
	return `{ ${parts.join(', ')} }`
}
function renderPolymrComponentImport(spec) {
	if (spec.startsWith('{')) {
		return `const ${normalizeNamedImports(spec)} = PolymrComponents`
	}
	if (spec.includes(',')) {
		const parts = spec.split(',')
		const defaultImport = parts[0]
		const named = parts.slice(1).join(',')
		const lines = [`const ${defaultImport.trim()} = PolymrComponents`]
		if (named) {
			lines.push(`const ${normalizeNamedImports(named.trim())} = PolymrComponents`)
		}
		return lines.join('\n')
	}
	if (spec.startsWith('*')) {
		const name = spec.split('as')[1] ? spec.split('as')[1].trim() : 'PolymrComponents'
		return `const ${name} = PolymrComponents`
	}
	return `const ${spec} = PolymrComponents`
}
function renderPolymrApiImport(spec, mod) {
	if (spec.startsWith('{')) {
		return `const ${normalizeNamedImports(spec)} = PolymrApi`
	}
	if (spec.includes(',')) {
		const parts = spec.split(',')
		const defaultImport = parts[0]
		const named = parts.slice(1).join(',')
		const lines = [`const ${defaultImport.trim()} = PolymrApi`]
		if (named) {
			lines.push(`const ${normalizeNamedImports(named.trim())} = PolymrApi`)
		}
		return lines.join('\n')
	}
	if (spec.startsWith('*')) {
		const name = spec.split('as')[1] ? spec.split('as')[1].trim() : 'PolymrApi'
		return `const ${name} = PolymrApi`
	}
	if (mod.endsWith('/getUsers')) {
		return `const ${spec} = PolymrApi.getUsers`
	}
	if (mod.endsWith('/callTool')) {
		return `const ${spec} = PolymrApi.callTool`
	}
	if (mod.endsWith('/callScript')) {
		return `const ${spec} = PolymrApi.callScript`
	}
	if (mod.endsWith('/createAttachmentUrl')) {
		return `const ${spec} = PolymrApi.createAttachmentUrl`
	}
	if (mod.endsWith('/navigateTo')) {
		return `const ${spec} = PolymrApi.navigateTo`
	}
	if (mod.endsWith('/notify')) {
		return `const ${spec} = PolymrApi.notify`
	}
	if (mod.endsWith('/uploadAttachment')) {
		return `const ${spec} = PolymrApi.uploadAttachment`
	}
	return `const ${spec} = PolymrApi`
}
function normalizePageModulePath(mod) {
	if (!mod || !mod.startsWith('@polymr/pages/')) {
		return ''
	}
	return mod.substring('@polymr/pages/'.length).replace(/^\/+|\/+$/g, '')
}
function renderPageModuleImport(spec, mod, availablePageModules) {
	const normalizedPath = normalizePageModulePath(mod)
	if (!normalizedPath || !availablePageModules || !availablePageModules[normalizedPath]) {
		throw new Error(`Page import not available: ${mod}`)
	}
	const binding = `PolymrPages[${JSON.stringify(normalizedPath)}]`
	if (spec.startsWith('{')) {
		return `const ${normalizeNamedImports(spec)} = ${binding}`
	}
	if (spec.includes(',')) {
		const parts = spec.split(',')
		const defaultImport = parts[0]
		const named = parts.slice(1).join(',')
		const lines = [`const ${defaultImport.trim()} = ${binding}`]
		if (named) {
			lines.push(`const ${normalizeNamedImports(named.trim())} = ${binding}`)
		}
		return lines.join('\n')
	}
	if (spec.startsWith('*')) {
		const name = spec.split('as')[1] ? spec.split('as')[1].trim() : 'PolymrPageModule'
		return `const ${name} = ${binding}`
	}
	return `const ${spec} = ${binding}`
}
function renderExternalFrontendImport(spec, mod, externalFrontendImports) {
	const globalName = externalFrontendImports && externalFrontendImports[mod]
	if (!globalName) {
		throw new Error(`Import not supported in preview: ${mod}`)
	}
	const binding = `PolymrExternalImports[${JSON.stringify(mod)}]`
	if (spec.startsWith('{')) {
		return `const ${normalizeNamedImports(spec)} = ${binding}`
	}
	if (spec.includes(',')) {
		const parts = spec.split(',')
		const defaultImport = parts[0]
		const named = parts.slice(1).join(',')
		const lines = [`const ${defaultImport.trim()} = ${binding}`]
		if (named) {
			lines.push(`const ${normalizeNamedImports(named.trim())} = ${binding}`)
		}
		return lines.join('\n')
	}
	if (spec.startsWith('*')) {
		const name = spec.split('as')[1] ? spec.split('as')[1].trim() : globalName
		return `const ${name} = ${binding}`
	}
	return `const ${spec} = ${binding}`
}
function rewriteImports(code, allowlist, builtins, availablePageModules, externalFrontendImports) {
	if (!code) {
		return ''
	}
	const lines = code.split('\n')
	const output = []
	const importPattern = /^import\s+([^'\";]+)\s+from\s+['\"]([^'\"]+)['\"];?$/
	const importSideEffect = /^import\s+['\"]([^'\"]+)['\"];?$/
	lines.forEach((line) => {
			const match = line.match(importPattern)
			const sideEffect = line.match(importSideEffect)
			if (match) {
				const spec = match[1].trim()
				const mod = match[2].trim()
				if (mod !== 'vue') {
					if (mod.startsWith('@polymr/pages/')) {
						output.push(renderPageModuleImport(spec, mod, availablePageModules))
						return
					}
					if (!allowlist.includes(mod) && !builtins.includes(mod)) {
						throw new Error(`Import not allowed: ${mod}`)
					}
					if (mod.startsWith('@polymr/components')) {
						output.push(renderPolymrComponentImport(spec))
						return
					}
					if (mod.startsWith('@polymr/api')) {
						output.push(renderPolymrApiImport(spec, mod))
						return
					}
					if (externalFrontendImports && externalFrontendImports[mod]) {
						output.push(renderExternalFrontendImport(spec, mod, externalFrontendImports))
						return
					}
					throw new Error(`Import not supported in preview: ${mod}`)
				}
				if (spec.startsWith('{')) {
					output.push(`const ${normalizeNamedImports(spec)} = Vue`)
				}
				else if (spec.includes(',')) {
					const parts = spec.split(',')
					const defaultImport = parts[0]
					const named = parts.slice(1).join(',')
					output.push(`const ${defaultImport.trim()} = Vue`)
					if (named) {
						output.push(`const ${normalizeNamedImports(named.trim())} = Vue`)
					}
				}
				else if (spec.startsWith('*')) {
					const name = spec.split('as')[1] ? spec.split('as')[1].trim() : 'Vue'
					output.push(`const ${name} = Vue`)
				}
				else {
					output.push(`const ${spec} = Vue`)
				}
				return
			}
			if (sideEffect) {
				const mod = sideEffect[1].trim()
				if (mod.startsWith('@polymr/pages/')) {
					const normalizedPath = normalizePageModulePath(mod)
					if (!normalizedPath || !availablePageModules || !availablePageModules[normalizedPath]) {
						throw new Error(`Page import not available: ${mod}`)
					}
					return
				}
				if (!allowlist.includes(mod) && !builtins.includes(mod)) {
					throw new Error(`Import not allowed: ${mod}`)
				}
				return
			}
			output.push(line)
		})
	return output.join('\n')
}
function firstErrorMessage(error) {
	if (!error) {
		return ''
	}
	if (typeof error === 'string') {
		return error
	}
	if (error.message) {
		return error.message
	}
	return String(error)
}
function compilePageSource(input) {
	const source = input && typeof input.source === 'string' ? input.source : ''
	const allowlist = Array.isArray(input && input.allowlist) ? input.allowlist : []
	const externalFrontendImports = input
			&& input.externalFrontendImports
			&& typeof input.externalFrontendImports === 'object'
		? input.externalFrontendImports
		: {}
	const pageId = input && input.pageId ? String(input.pageId) : 'page'
	const availablePageModules = input
			&& input.availablePageModules
			&& typeof input.availablePageModules === 'object'
		? input.availablePageModules
		: {}
	if (!source) {
		return { compiledBundle: '', compileErrors: 'Draft source is empty.', compileErrorDetail: '' }
	}
	const parseResult = globalThis.__PolymrVueCompilerSfc.parse(source)
	const descriptor = parseResult.descriptor
	const parseErrors = parseResult.errors
	if (parseErrors && parseErrors.length) {
		return {
			compiledBundle: '',
			compileErrors: firstErrorMessage(parseErrors[0]) || 'Unable to parse SFC.',
			compileErrorDetail: JSON.stringify(parseErrors, null, 2)
		}
	}
	const builtins = [
		'@polymr/components',
		'@polymr/components/MarkdownMessage',
		'@polymr/api',
		'@polymr/api/getUsers',
		'@polymr/api/callTool',
		'@polymr/api/callScript',
		'@polymr/api/createAttachmentUrl',
		'@polymr/api/navigateTo',
		'@polymr/api/notify',
		'@polymr/api/uploadAttachment',
	]
	const baseId = `page-${pageId}`
	const scopeId = `data-v-${baseId}`
	const hasScoped = Array.isArray(descriptor.styles) && descriptor.styles.some((style) => style && style.scoped)
	let script = null
	let scriptCode = 'const __component__ = {}'
	let componentVar = '__component__'
	if (descriptor.script || descriptor.scriptSetup) {
		script = globalThis.__PolymrVueCompilerSfc.compileScript(descriptor, { id: scopeId })
		scriptCode = script && script.content ? script.content : 'const __default__ = {}'
		if (scriptCode.includes('export default')) {
			scriptCode = scriptCode.replace(/export default/g, 'const __component__ =')
			componentVar = '__component__'
		}
		else if (scriptCode.includes('__default__')) {
			componentVar = '__default__'
		}
		else {
			componentVar = '__component__'
			scriptCode = `const __component__ = {}\n${scriptCode}`
		}
	}
	try {
		scriptCode = rewriteImports(scriptCode, allowlist, builtins, availablePageModules, externalFrontendImports)
	}
	catch (error) {
		return {
			compiledBundle: '',
			compileErrors: firstErrorMessage(error),
			compileErrorDetail: error && error.stack ? String(error.stack) : ''
		}
	}
	let templateCode = ''
	let compileErrors = ''
	let compileErrorDetail = ''
	if (descriptor.template) {
		const compiled = globalThis.__PolymrVueCompilerSfc
			.compileTemplate({
			source: descriptor.template.content,
			filename: 'Page.vue',
			id: scopeId,
			scoped: hasScoped,
			compilerOptions: { bindingMetadata: script && script.bindings ? script.bindings : {} }
		})
		if (compiled.errors && compiled.errors.length) {
			compileErrors = firstErrorMessage(compiled.errors[0]) || 'Template compilation reported errors.'
			compileErrorDetail = JSON.stringify(compiled.errors, null, 2)
		}
		templateCode = compiled.code.replace(/export function render/, 'function render')
		try {
			templateCode = rewriteImports(templateCode, allowlist, builtins, availablePageModules, externalFrontendImports)
		}
		catch (error) {
			return {
				compiledBundle: '',
				compileErrors: firstErrorMessage(error),
				compileErrorDetail: error && error.stack ? String(error.stack) : ''
			}
		}
	}
	const styles = Array.isArray(descriptor.styles) ? descriptor.styles : []
	const compiledStyles = []
	for (let index = 0; index < styles.length; index += 1) {
		const style = styles[index]
		if (!style || !style.content) {
			continue
		}
		const result = globalThis.__PolymrVueCompilerSfc
			.compileStyle({
			source: style.content,
			filename: `Page.vue?style=${index}`,
			id: scopeId,
			scoped: !!style.scoped
		})
		if (result.errors && result.errors.length) {
			compileErrors = firstErrorMessage(result.errors[0]) || 'Style compilation reported errors.'
			compileErrorDetail = JSON.stringify(result.errors, null, 2)
		}
		compiledStyles.push(result.code || '')
	}
	const styleCode = compiledStyles.length
		? `const __styles = ${JSON.stringify(compiledStyles)};\nif (typeof document !== 'undefined') {\n  __styles.forEach((content, idx) => {\n    const id = \"${scopeId}-\" + idx;\n    let style = document.getElementById(id);\n    if (!style) {\n      style = document.createElement('style');\n      style.id = id;\n      document.head.appendChild(style);\n    }\n    if (style.textContent !== content) {\n      style.textContent = content;\n    }\n  });\n}`
		: ''
	const componentReturn = templateCode
		? `const __component = ${componentVar};\n${hasScoped ? `__component.__scopeId = \"${scopeId}\";` : ''}\nif (typeof __component === 'function') { __component.render = render; return __component; }\nif (typeof __component === 'object' && __component) { __component.render = render; return __component; }\nreturn { render };`
		: `return ${componentVar};`
	return {
		compiledBundle: `${scriptCode}\n${templateCode}\n${styleCode}\n${componentReturn}`,
		compileErrors,
		compileErrorDetail
	}
}
function compilePageSourceFromJson(json) {
	const input = json ? JSON.parse(String(json)) : {}
	return compilePageSource(input)
}
globalThis.__PolymrPageCompiler = { compilePageSource, compilePageSourceFromJson }
