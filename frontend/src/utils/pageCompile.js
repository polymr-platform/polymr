export const compilePageSource = async({
  source,
  allowlist = [],
  externalFrontendImports = {},
  pageId = 'page',
}) => {
	if (!source) {
		return { compiledBundle: '', compileErrors: 'Draft source is empty.', compileErrorDetail: '' }
	}
	const { parse, compileTemplate, compileScript, compileStyle } = await import('@vue/compiler-sfc')
	const { descriptor, errors } = parse(source)
	if (errors?.length) {
		const firstError = errors[0]
		const message = typeof firstError === 'string' ? firstError : firstError?.message
		return {
			compiledBundle: '',
			compileErrors: message || 'Unable to parse SFC.',
			compileErrorDetail: JSON.stringify(errors, null, 2)
		}
	}
	const builtins = [
		'@polymr/components',
		'@polymr/components/MarkdownMessage',
		'@polymr/api',
		'@polymr/api/getUsers',
		'@polymr/api/callTool',
		'@polymr/api/notify',
	]
	const baseId = `page-${pageId}`
	const scopeId = `data-v-${baseId}`
	const hasScoped = descriptor.styles?.some((style) => style.scoped)
	let script = null
	let scriptCode = 'const __component__ = {}'
	let componentVar = '__component__'
	if (descriptor.script || descriptor.scriptSetup) {
		script = compileScript(descriptor, { id: scopeId })
		scriptCode = script?.content || 'const __default__ = {}'
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
	scriptCode = rewriteImports(scriptCode, allowlist, builtins, externalFrontendImports)
	let templateCode = ''
	let compileErrors = ''
	let compileErrorDetail = ''
	if (descriptor.template) {
		const compiled = compileTemplate({
			source: descriptor.template.content,
			filename: 'Page.vue',
			id: scopeId,
			scoped: hasScoped,
			compilerOptions: { bindingMetadata: script?.bindings || {} }
		})
		if (compiled.errors?.length) {
			const firstError = compiled.errors[0]
			const message = typeof firstError === 'string' ? firstError : firstError?.message
			compileErrors = message || 'Template compilation reported errors.'
			compileErrorDetail = JSON.stringify(compiled.errors, null, 2)
		}
		templateCode = compiled.code.replace(/export function render/, 'function render')
		templateCode = rewriteImports(templateCode, allowlist, builtins, externalFrontendImports)
	}
	const styles = Array.isArray(descriptor.styles) ? descriptor.styles : []
	const compiledStyles = []
	styles.forEach((style, index) => {
			if (!style?.content) {
				return
			}
			const result = compileStyle({
				source: style.content,
				filename: `Page.vue?style=${index}`,
				id: scopeId,
				scoped: style.scoped
			})
			if (result.errors?.length) {
				const firstError = result.errors[0]
				const message = typeof firstError === 'string' ? firstError : firstError?.message
				compileErrors = message || 'Style compilation reported errors.'
				compileErrorDetail = JSON.stringify(result.errors, null, 2)
			}
			compiledStyles.push(result.code || '')
		})
	const styleCode = compiledStyles.length
		? `const __styles = ${JSON.stringify(compiledStyles)};\nif (typeof document !== 'undefined') {\n  __styles.forEach((content, idx) => {\n    const id = "${scopeId}-" + idx;\n    let style = document.getElementById(id);\n    if (!style) {\n      style = document.createElement('style');\n      style.id = id;\n      document.head.appendChild(style);\n    }\n    if (style.textContent !== content) {\n      style.textContent = content;\n    }\n  });\n}`
		: ''
	const componentReturn = templateCode
		? `const __component = ${componentVar};\n${hasScoped ? `__component.__scopeId = "${scopeId}";` : ''}\nif (typeof __component === 'function') { __component.render = render; return __component; }\nif (typeof __component === 'object' && __component) { __component.render = render; return __component; }\nreturn { render };`
		: `return ${componentVar};`
	const compiledBundle = `${scriptCode}\n${templateCode}\n${styleCode}\n${componentReturn}`
	return { compiledBundle, compileErrors, compileErrorDetail }
}
const normalizeNamedImports = (spec) => {
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
const renderPolymrComponentImport = (spec, mod) => {
	if (spec.startsWith('{')) {
		return `const ${normalizeNamedImports(spec)} = PolymrComponents`
	}
	if (spec.includes(',')) {
		const [defaultImport, named] = spec.split(',')
		const lines = [`const ${defaultImport.trim()} = PolymrComponents`]
		if (named) {
			lines.push(`const ${normalizeNamedImports(named.trim())} = PolymrComponents`)
		}
		return lines.join('\n')
	}
	if (spec.startsWith('*')) {
		const name = spec.split('as')[1]?.trim() || 'PolymrComponents'
		return `const ${name} = PolymrComponents`
	}
	return `const ${spec} = PolymrComponents`
}
const renderPolymrApiImport = (spec, mod) => {
	if (spec.startsWith('{')) {
		return `const ${normalizeNamedImports(spec)} = PolymrApi`
	}
	if (spec.includes(',')) {
		const [defaultImport, named] = spec.split(',')
		const lines = [`const ${defaultImport.trim()} = PolymrApi`]
		if (named) {
			lines.push(`const ${normalizeNamedImports(named.trim())} = PolymrApi`)
		}
		return lines.join('\n')
	}
	if (spec.startsWith('*')) {
		const name = spec.split('as')[1]?.trim() || 'PolymrApi'
		return `const ${name} = PolymrApi`
	}
	if (mod.endsWith('/getUsers')) {
		return `const ${spec} = PolymrApi.getUsers`
	}
	if (mod.endsWith('/callTool')) {
		return `const ${spec} = PolymrApi.callTool`
	}
	if (mod.endsWith('/notify')) {
		return `const ${spec} = PolymrApi.notify`
	}
	return `const ${spec} = PolymrApi`
}
const renderExternalFrontendImport = (spec, mod, externalFrontendImports) => {
	const binding = `PolymrExternalImports[${JSON.stringify(mod)}]`
	if (spec.startsWith('{')) {
		return `const ${normalizeNamedImports(spec)} = ${binding}`
	}
	if (spec.includes(',')) {
		const [defaultImport, named] = spec.split(',')
		const lines = [`const ${defaultImport.trim()} = ${binding}`]
		if (named) {
			lines.push(`const ${normalizeNamedImports(named.trim())} = ${binding}`)
		}
		return lines.join('\n')
	}
	if (spec.startsWith('*')) {
		const name = spec.split('as')[1]?.trim() || mod
		return `const ${name} = ${binding}`
	}
	return `const ${spec} = ${binding}`
}
const rewriteImports = (code, allowlist, builtins, externalFrontendImports = {}) => {
	if (!code) {
		return ''
	}
	const lines = code.split('\n')
	const output = []
	const importPattern = /^import\s+([^'";]+)\s+from\s+['"]([^'"]+)['"];?$/
	const importSideEffect = /^import\s+['"]([^'"]+)['"];?$/
	lines.forEach((line) => {
			const match = line.match(importPattern)
			const sideEffect = line.match(importSideEffect)
			if (match) {
				const spec = match[1].trim()
				const mod = match[2].trim()
				if (mod !== 'vue') {
					if (!allowlist.includes(mod) && !builtins.includes(mod)) {
						throw new Error(`Import not allowed: ${mod}`)
					}
					if (mod.startsWith('@polymr/components')) {
						output.push(renderPolymrComponentImport(spec, mod))
						return
					}
					if (mod.startsWith('@polymr/api')) {
						output.push(renderPolymrApiImport(spec, mod))
						return
					}
					if (externalFrontendImports[mod]) {
						output.push(renderExternalFrontendImport(spec, mod, externalFrontendImports))
						return
					}
					throw new Error(`Import not supported in preview: ${mod}`)
				}
				if (spec.startsWith('{')) {
					output.push(`const ${normalizeNamedImports(spec)} = Vue`)
				}
				else if (spec.includes(',')) {
					const [defaultImport, named] = spec.split(',')
					output.push(`const ${defaultImport.trim()} = Vue`)
					if (named) {
						output.push(`const ${normalizeNamedImports(named.trim())} = Vue`)
					}
				}
				else if (spec.startsWith('*')) {
					const name = spec.split('as')[1]?.trim() || 'Vue'
					output.push(`const ${name} = Vue`)
				}
				else {
					output.push(`const ${spec} = Vue`)
				}
				return
			}
			if (sideEffect) {
				const mod = sideEffect[1].trim()
				if (!allowlist.includes(mod) && !builtins.includes(mod)) {
					throw new Error(`Import not allowed: ${mod}`)
				}
				return
			}
			output.push(line)
		})
	return output.join('\n')
}
