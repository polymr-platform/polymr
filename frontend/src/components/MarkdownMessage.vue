<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue';
import MarkdownIt from 'markdown-it';
import markdownItKatex from 'markdown-it-katex';
import AnsiToHtml from 'ansi-to-html';
import Prism from 'prismjs';
const renderedCache = new Map()
const renderedCacheLimit = 400
const props = defineProps({
	content: { type: String, default: '' },
	customRenderers: { type: Object, default: () => ({}) },
	allowHtml: { type: Boolean, default: false },
	rendererOverrides: { type: Object, default: () => ({}) }
})
const ansiConverter = new AnsiToHtml({ escapeXML: true })
const ansiLanguages = new Set(['ansi', 'console', 'terminal'])
const normalizeAnsi = (value) => value.replaceAll('\\u001b', '\u001b').replaceAll('\\x1b', '\u001b').replaceAll('\\033', '\u001b')
const cacheKeyFor = () => JSON.stringify({
	content: props.content || '',
	allowHtml: props.allowHtml,
	overrideKeys: Object.keys(props.rendererOverrides || {}).sort(),
	customRendererKeys: Object.keys(props.customRenderers || {}).sort()
})
const rememberRendered = (key, html) => {
	if (renderedCache.has(key)) {
		renderedCache.delete(key)
	}
	renderedCache.set(key, html)
	if (renderedCache.size > renderedCacheLimit) {
		const oldestKey = renderedCache.keys().next().value
		renderedCache.delete(oldestKey)
	}
	return html
}
const createMarkdown = () => {
	const instance = new MarkdownIt({
		html: props.allowHtml,
		linkify: true,
		breaks: true,
		highlight: (code, lang) => {
			const normalized = lang === 'rhai' ? 'rust' : lang === 'glue' ? 'python' : lang
			if (normalized && props.customRenderers[normalized]) {
				return props.customRenderers[normalized](code)
			}
			if (normalized && ansiLanguages.has(normalized)) {
				return `<pre class="language-${normalized}"><code class="ansi-content language-${normalized}">${
					ansiConverter.toHtml(normalizeAnsi(code))
				}</code></pre>`
			}
			if (normalized && Prism.languages[normalized]) {
				return `<pre class="language-${normalized}"><code class="language-${normalized}">${
					Prism.highlight(code, Prism.languages[normalized], normalized)
				}</code></pre>`
			}
			return `<pre class="language-none"><code>${instance.utils.escapeHtml(code)}</code></pre>`
		}
	}).use(markdownItKatex, { throwOnError: false })
	instance.inline
		.ruler
		.before(
			'math_inline',
			'skip_env_vars',
			(state, silent) => {
				if (state.src[state.pos] !== '$') {
					return false
				}
				if (state.src[state.pos + 1] !== '{') {
					return false
				}
				if (!silent) {
					state.pending += '$'
				}
				state.pos += 1
				return true
			}
		)
	if (props.rendererOverrides && typeof props.rendererOverrides === 'object') {
		Object.entries(props.rendererOverrides)
			.forEach(([ruleName, rule]) => {
				if (typeof rule === 'function') {
					instance.renderer.rules[ruleName] = rule
				}
			})
	}
	return instance
}
const md = createMarkdown()
const rendered = computed(() => {
	if (!props.content) {
		return ''
	}
	const key = cacheKeyFor()
	if (renderedCache.has(key)) {
		return rememberRendered(key, renderedCache.get(key))
	}
	return rememberRendered(key, md.render(props.content))
})
const containerRef = ref(null)
const copyIcon = '<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">'
	+ '<path fill="currentColor" d="M16 1H6a2 2 0 0 0-2 2v12h2V3h10V1zm3 4H10a2 2 0 0 0-2 2v14a2 2 0 0 0 '
	+ '2 2h9a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2zm0 16H10V7h9v14z" />'
	+ '</svg>'
const attachCopyButtons = async() => {
	await nextTick()
	const container = containerRef.value
	if (!container) {
		return
	}
	const blocks = container.querySelectorAll('pre > code')
	blocks.forEach((code) => {
			const pre = code.parentElement
			if (!pre || pre.dataset.copyReady === 'true') {
				return
			}
			pre.dataset.copyReady = 'true'
			pre.classList.add('markdown-code-block')
			const button = document.createElement('button')
			button.type = 'button'
			button.className = 'markdown-code-copy'
			button.innerHTML = copyIcon
			button.setAttribute('aria-label', 'Copy code')
			button.setAttribute('title', 'Copy code')
			button.addEventListener(
				'click',
				async() => {
					const text = code.textContent || ''
					if (!text) {
						return
					}
					try {
						await navigator.clipboard.writeText(text)
						button.dataset.state = 'copied'
						button.setAttribute('title', 'Copied')
						window.setTimeout(
							() => {
								button.dataset.state = ''
								button.setAttribute('title', 'Copy code')
							},
							1400
						)
					}
					catch {
						button.dataset.state = 'failed'
						button.setAttribute('title', 'Copy failed')
						window.setTimeout(
							() => {
								button.dataset.state = ''
								button.setAttribute('title', 'Copy code')
							},
							1400
						)
					}
				}
			)
			pre.appendChild(button)
		})
}
onMounted(() => {
	attachCopyButtons()
})
watch(
	rendered,
	() => {
		attachCopyButtons()
	}
)
</script>
<template>
	<div
		ref="containerRef"
		class="markdown-body"
		v-html="rendered"></div>
</template>
