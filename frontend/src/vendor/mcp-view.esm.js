// src/bridge.ts
async function normalizeResolverResult(result, kind, uri) {
	try {
		if (result instanceof Response) {
			if (!result.ok) {
				return {
					ok: false,
					mime: "text/plain",
					body: new Uint8Array(),
					error: `HTTP ${result.status}`
				};
			}
			const body = new Uint8Array(await result.arrayBuffer());
			const mime = result.headers.get("content-type") || inferMime(kind, uri);
			return { ok: true, mime, body };
		}
		if (result instanceof Blob) {
			const body = new Uint8Array(await result.arrayBuffer());
			const mime = result.type || inferMime(kind, uri);
			return { ok: true, mime, body };
		}
		if (typeof result === "string") {
			const encoder = new TextEncoder();
			const body = encoder.encode(result);
			const mime = inferMime(kind, uri);
			return { ok: true, mime, body };
		}
		if (result instanceof ArrayBuffer) {
			const body = new Uint8Array(result);
			const mime = inferMime(kind, uri);
			return { ok: true, mime, body };
		}
		if (result instanceof Uint8Array) {
			const mime = inferMime(kind, uri);
			return { ok: true, mime, body: result };
		}
	}
	catch (error) {
		return {
			ok: false,
			mime: "text/plain",
			body: new Uint8Array(),
			error: error instanceof Error ? error.message : "Unknown error"
		};
	}
	return {
		ok: false,
		mime: "text/plain",
		body: new Uint8Array(),
		error: "Unsupported resolver result"
	};
}
function inferMime(kind, uri) {
	if (kind === "document") {
		return "text/html";
	}
	if (kind === "style") {
		return "text/css";
	}
	if (kind === "script") {
		return "text/javascript";
	}
	const ext = getExtension(uri);
	if (ext) {
		const lookup = {
			css: "text/css",
			js: "text/javascript",
			mjs: "text/javascript",
			cjs: "text/javascript",
			json: "application/json",
			svg: "image/svg+xml",
			png: "image/png",
			jpg: "image/jpeg",
			jpeg: "image/jpeg",
			gif: "image/gif",
			webp: "image/webp",
			avif: "image/avif",
			woff: "font/woff",
			woff2: "font/woff2",
			ttf: "font/ttf",
			otf: "font/otf",
			mp3: "audio/mpeg",
			wav: "audio/wav",
			mp4: "video/mp4",
			webm: "video/webm"
		};
		if (lookup[ext]) {
			return lookup[ext];
		}
	}
	if (kind === "img") {
		return "image/png";
	}
	return "application/octet-stream";
}
function getExtension(uri) {
	try {
		const url = new URL(uri);
		const path = url.pathname;
		const last = path.split("/").pop();
		if (!last || !last.includes(".")) {
			return null;
		}
		return last.split(".").pop()?.toLowerCase() || null;
	}
	catch {
		return null;
	}
}
// src/iframe-bootstrap.ts
function getIframeBootstrapScript(initialData, options = {}) {
	const serialized = safeSerialize(initialData);
	const autoHeightEnabled = options.autoHeight ? "true" : "false";
	return `(() => {
  const initialData = ${serialized};
  const autoHeightEnabled = ${autoHeightEnabled};
  window.mcpData = initialData;
  const pending = new Map();
  const toolPending = new Map();
  const rpcPending = new Map();
  let nextId = 1;
  let nextToolId = 1;
  let nextRpcId = 1;
  let connected = false;
  let hostContext = null;
  const decoder = new TextDecoder();
  const deferredScripts = [];
  let deferredReady = document.readyState !== "loading";
  let deferredListenerAttached = false;

  function sanitizeLayerName(name) {
    if (!name) return null;
    const trimmed = String(name).trim();
    if (!trimmed) return null;
    if (!/^[a-zA-Z0-9_-]+$/.test(trimmed)) return null;
    return trimmed;
  }

  function requestResource(uri, kind) {
    return new Promise((resolve) => {
      const id = nextId++;
      pending.set(id, resolve);
      window.parent.postMessage({ type: "mcp:request", id, uri, kind }, "*");
    });
  }

  function enqueueDeferredScript(run) {
    if (!deferredReady && document.readyState !== "loading") {
      deferredReady = true;
    }
    deferredScripts.push(run);
    if (!deferredReady && !deferredListenerAttached) {
      deferredListenerAttached = true;
      document.addEventListener(
        "DOMContentLoaded",
        () => {
          deferredReady = true;
          flushDeferredScripts();
        },
        { once: true }
      );
    }
    if (deferredReady) {
      flushDeferredScripts();
    }
  }

  function flushDeferredScripts() {
    while (deferredScripts.length) {
      const run = deferredScripts.shift();
      if (typeof run === "function") run();
    }
  }

  function callTool(params) {
    return new Promise((resolve, reject) => {
      const id = nextToolId++;
      toolPending.set(id, { resolve, reject });
      window.parent.postMessage({ type: "mcp:tool-call", id, params, method: "tools/call" }, "*");
    });
  }

  function connectApp() {
    if (connected) {
      return Promise.resolve({});
    }
    return new Promise((resolve, reject) => {
      const id = nextRpcId++;
      console.info("[mcp] connect request", { id });
      rpcPending.set(id, { type: "connect", resolve, reject });
      window.parent.postMessage({ type: "mcp:connect", id }, "*");
    });
  }

  function sendMessage(params) {
    return new Promise((resolve, reject) => {
      const id = nextRpcId++;
      rpcPending.set(id, { type: "send-message", resolve, reject });
      window.parent.postMessage({ type: "mcp:send-message", id, params }, "*");
    });
  }

  function applyDocumentTheme(theme, root = document.documentElement) {
    if (!root) return;
    if (theme !== "light" && theme !== "dark") return;
    root.setAttribute("data-theme", theme);
    root.style.colorScheme = theme;
  }

  function applyHostStyleVariables(vars, root = document.documentElement) {
    if (!vars || !root || !root.style) return;
    for (const [key, value] of Object.entries(vars)) {
      if (!key.startsWith("--") || typeof value !== "string") continue;
      root.style.setProperty(key, value);
    }
  }

  function applyHostFonts(fontCss, doc = document) {
    if (!fontCss || !doc) return;
    const styleId = "mcp-host-fonts";
    let style = doc.getElementById(styleId);
    if (!style) {
      style = doc.createElement("style");
      style.id = styleId;
      (doc.head || doc.documentElement).appendChild(style);
    }
    if (style.textContent !== fontCss) {
      style.textContent = fontCss;
    }
  }

  function applyHostContext(ctx) {
    if (!ctx || typeof ctx !== "object") return;
    hostContext = { ...(hostContext || {}), ...ctx };
    if (hostContext.theme) {
      applyDocumentTheme(hostContext.theme);
    }
    if (hostContext.styles?.variables) {
      applyHostStyleVariables(hostContext.styles.variables);
    }
    if (hostContext.styles?.css?.fonts) {
      applyHostFonts(hostContext.styles.css.fonts);
    }
    if (window.mcp && typeof window.mcp.onhostcontextchanged === "function") {
      window.mcp.onhostcontextchanged(hostContext);
    }
    if (window.App && typeof window.App.__deliverHostContextChanged === "function") {
      window.App.__deliverHostContextChanged(hostContext);
    }
  }

  function deliverToolInput(input) {
    window.mcpData = input;
    window.dispatchEvent(new CustomEvent("mcp-data", { detail: input }));
    if (window.mcp && typeof window.mcp.ontoolinput === "function") {
      window.mcp.ontoolinput({ arguments: input });
    }
    if (window.App && typeof window.App.__deliverToolInput === "function") {
      window.App.__deliverToolInput(input);
    }
  }

  function deliverToolResult(result) {
    if (window.mcp && typeof window.mcp.ontoolresult === "function") {
      window.mcp.ontoolresult(result);
    }
    if (window.App && typeof window.App.__deliverToolResult === "function") {
      window.App.__deliverToolResult(result);
    }
  }

  window.addEventListener("message", (event) => {
    if (event.source !== window.parent) return;
    const data = event.data;
    if (!data) return;
    if (data.type === "mcp:response") {
      const resolve = pending.get(data.id);
      if (!resolve) return;
      pending.delete(data.id);
      resolve(data);
      return;
    }
    if (data.type === "mcp:tool-result") {
      console.info("[mcp] tool result received", { ok: data.ok, hasId: Boolean(data.id) });
      const entry = toolPending.get(data.id);
      const result = (
        Object.prototype.hasOwnProperty.call(data, "structuredContent") ||
        Object.prototype.hasOwnProperty.call(data, "content") ||
        Object.prototype.hasOwnProperty.call(data, "_meta") ||
        Object.prototype.hasOwnProperty.call(data, "isError")
      )
        ? {
            structuredContent: data.structuredContent,
            content: data.content,
            _meta: data._meta,
            isError: data.isError === true,
          }
        : data.result;
      if (data.ok) {
        if (entry) {
          toolPending.delete(data.id);
          entry.resolve(result);
        }
        console.info("[mcp] deliver tool result", { hasEntry: Boolean(entry) });
        deliverToolResult(result);
      } else if (entry) {
        toolPending.delete(data.id);
        const message = data.error || "Tool call failed";
        entry.reject(new Error(message));
      }
      return;
    }
    if (data.type === "mcp:connect-result") {
      const entry = rpcPending.get(data.id);
      if (!entry) return;
      rpcPending.delete(data.id);
      if (!data.ok) {
        entry.reject(new Error(data.error || "Connect failed"));
        return;
      }
      connected = true;
      console.info("[mcp] connected", { ok: data.ok, hostCapabilities: data.hostCapabilities || {} });
      applyHostContext(data.hostContext || {});
      entry.resolve({ hostCapabilities: data.hostCapabilities || {}, hostContext: hostContext || {} });
      if (window.parent) {
        window.parent.postMessage({ type: "mcp:initialized" }, "*");
      }
      return;
    }
    if (data.type === "mcp:send-message-result") {
      const entry = rpcPending.get(data.id);
      if (!entry) return;
      rpcPending.delete(data.id);
      if (!data.ok) {
        entry.reject(new Error(data.error || "Send message failed"));
        return;
      }
      entry.resolve({});
      return;
    }
    if (data.type === "mcp:tool-input") {
      deliverToolInput(data.input);
      return;
    }
    if (data.type === "mcp:host-context-changed") {
      applyHostContext(data.context || {});
      return;
    }
    if (data.type === "mcp-data:update") {
      deliverToolInput(data.payload);
    }
  });

  function initAutoHeight() {
    if (!autoHeightEnabled) return;
    let scheduled = false;
    let lastHeight = 0;
    const doc = document.documentElement;
    const report = () => {
      scheduled = false;
      const body = document.body;
      if (!doc) return;
      const height = Math.ceil(
        Math.max(
          doc.scrollHeight,
          doc.offsetHeight,
          body ? body.scrollHeight : 0,
          body ? body.offsetHeight : 0
        )
      );
      if (!height || height === lastHeight) return;
      lastHeight = height;
      window.parent.postMessage({ type: "mcp:height", height }, "*");
    };
    const schedule = () => {
      if (scheduled) return;
      scheduled = true;
      window.requestAnimationFrame(report);
    };
    if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", schedule, { once: true });
    }
    window.addEventListener("load", schedule, { once: true });
    window.addEventListener("resize", schedule);
    if (typeof ResizeObserver !== "undefined") {
      const observer = new ResizeObserver(schedule);
      if (doc) observer.observe(doc);
      const body = document.body;
      if (body) observer.observe(body);
    }
    schedule();
  }

  initAutoHeight();

  window.mcp = window.mcp || {};
  window.mcp.callTool = callTool;
  window.mcp.connect = connectApp;
  window.mcp.callServerTool = callTool;
  window.mcp.sendMessage = sendMessage;
  window.mcp.applyDocumentTheme = applyDocumentTheme;
  window.mcp.applyHostStyleVariables = applyHostStyleVariables;
  window.mcp.applyHostFonts = applyHostFonts;
  window.mcp.getHostContext = () => hostContext;
  window.mcp.ontoolinput = null;
  window.mcp.ontoolresult = null;
  window.mcp.onhostcontextchanged = null;

  class App {
    constructor() {
      this.ontoolinput = null;
      this.ontoolresult = null;
      this.onhostcontextchanged = null;
      App.__instances.push(this);
    }

    async connect() {
      return connectApp();
    }

    async callServerTool(params) {
      return callTool(params);
    }

    async sendMessage(params) {
      return sendMessage(params);
    }

    getHostContext() {
      return hostContext;
    }

    static __deliverToolInput(input) {
      for (const app of App.__instances) {
        if (typeof app.ontoolinput === "function") {
          app.ontoolinput({ arguments: input });
        }
      }
    }

    static __deliverToolResult(result) {
      for (const app of App.__instances) {
        if (typeof app.ontoolresult === "function") {
          app.ontoolresult(result);
        }
      }
    }

    static __deliverHostContextChanged(context) {
      for (const app of App.__instances) {
        if (typeof app.onhostcontextchanged === "function") {
          app.onhostcontextchanged(context);
        }
      }
    }
  }

  App.__instances = [];
  window.App = App;
  window.applyDocumentTheme = applyDocumentTheme;
  window.applyHostStyleVariables = applyHostStyleVariables;
  window.applyHostFonts = applyHostFonts;

  function toText(body) {
    const buffer = body || new ArrayBuffer(0);
    return decoder.decode(new Uint8Array(buffer));
  }

  function toBlobUrl(body, mime) {
    const buffer = body || new ArrayBuffer(0);
    const blob = new Blob([buffer], { type: mime || "application/octet-stream" });
    return URL.createObjectURL(blob);
  }

  function copyAttributes(from, to, skip) {
    const skipSet = new Set(skip || []);
    for (const attr of Array.from(from.attributes)) {
      if (skipSet.has(attr.name)) continue;
      to.setAttribute(attr.name, attr.value);
    }
  }

  class McpLink extends HTMLElement {
    connectedCallback() {
      if (this.hasAttribute("data-mcp-blocked")) return;
      const href = this.getAttribute("href");
      if (!href) return;
      const rel = (this.getAttribute("rel") || "").toLowerCase();
      if (rel && rel !== "stylesheet") return;
      requestResource(href, "style").then((res) => {
        if (!res || !res.ok) return;
        const style = document.createElement("style");
        const media = this.getAttribute("media");
        if (media) style.setAttribute("media", media);
        const layer = sanitizeLayerName(this.getAttribute("layer")) || "mcp-content";
        style.textContent = "@layer " + layer + " {\\n" + toText(res.body) + "\\n}";
        if (this.hasAttribute("data-mcp-head")) {
          (document.head || document.documentElement).appendChild(style);
          this.remove();
          return;
        }
        this.replaceWith(style);
      });
    }
  }

  class McpScript extends HTMLElement {
    connectedCallback() {
      if (this.hasAttribute("data-mcp-blocked")) {
        console.error("[mcp] script blocked", this.getAttribute("src"));
        return;
      }
      const src = this.getAttribute("src");
      if (!src) {
        console.error("[mcp] script missing src", this);
        return;
      }
      const isDefer = this.hasAttribute("defer");
      requestResource(src, "script").then((res) => {
        if (!res || !res.ok) {
          console.error("[mcp] script resolve failed", src, res?.error || "unknown");
          return;
        }
        const script = document.createElement("script");
        const type = this.getAttribute("type");
        if (type) script.setAttribute("type", type);
        script.textContent = toText(res.body);
        console.debug("[mcp] script injected", src);
        const placeScript = () => {
          if (this.hasAttribute("data-mcp-head")) {
            (document.head || document.documentElement).appendChild(script);
            this.remove();
            return;
          }
          this.replaceWith(script);
        };
        if (isDefer) {
          enqueueDeferredScript(placeScript);
          return;
        }
        placeScript();
      });
    }
  }

  class McpImg extends HTMLElement {
    connectedCallback() {
      if (this.hasAttribute("data-mcp-blocked")) return;
      const src = this.getAttribute("src");
      if (!src) return;
      requestResource(src, "img").then((res) => {
        if (!res || !res.ok) return;
        const img = document.createElement("img");
        copyAttributes(this, img, ["src"]);
        const url = toBlobUrl(res.body, res.mime);
        img.addEventListener("load", () => URL.revokeObjectURL(url), { once: true });
        img.addEventListener("error", () => URL.revokeObjectURL(url), { once: true });
        img.setAttribute("src", url);
        this.replaceWith(img);
      });
    }
  }

  class McpMedia extends HTMLElement {
    connectedCallback() {
      if (this.hasAttribute("data-mcp-blocked")) return;
      const src = this.getAttribute("src");
      if (!src) return;
      const tag = this.tagName.toLowerCase().replace("mcp-", "");
      requestResource(src, "media").then((res) => {
        if (!res || !res.ok) return;
        const node = document.createElement(tag);
        copyAttributes(this, node, ["src"]);
        const url = toBlobUrl(res.body, res.mime);
        if (tag === "source") {
          node.setAttribute("src", url);
        } else {
          node.setAttribute("src", url);
          node.addEventListener("loadeddata", () => URL.revokeObjectURL(url), { once: true });
          node.addEventListener("error", () => URL.revokeObjectURL(url), { once: true });
        }
        this.replaceWith(node);
      });
    }
  }

  class McpAudio extends McpMedia {}
  class McpVideo extends McpMedia {}
  class McpSource extends McpMedia {}

  customElements.define("mcp-link", McpLink);
  customElements.define("mcp-script", McpScript);
  customElements.define("mcp-img", McpImg);
  customElements.define("mcp-audio", McpAudio);
  customElements.define("mcp-video", McpVideo);
  customElements.define("mcp-source", McpSource);
})();`;
}
function safeSerialize(value) {
	try {
		return JSON.stringify(value ?? null)
			.replace(/</g, "\\u003c")
			.replace(/>/g, "\\u003e")
			.replace(/\u2028/g, "\\u2028")
			.replace(/\u2029/g, "\\u2029");
	}
	catch {
		return "null";
	}
}
// src/rewrite.ts
var BLOCKED_ATTR = "data-mcp-blocked";
function rewriteHtml(options) {
	const parser = new DOMParser();
	const doc = parser.parseFromString(options.html, "text/html");
	addRootClass(doc.documentElement, "mcp-root");
	if (doc.body) {
		addRootClass(doc.body, "mcp-root");
	}
	if (options.resourceBase) {
		rewriteElementUrls(doc, "link", "href", options);
		rewriteElementUrls(doc, "script", "src", options);
		rewriteElementUrls(doc, "img", "src", options);
		rewriteElementUrls(doc, "source", "src", options);
		rewriteElementUrls(doc, "audio", "src", options);
		rewriteElementUrls(doc, "video", "src", options);
	}
	else {
		rewriteElement(doc, "link", "href", "mcp-link", options);
		rewriteElement(doc, "script", "src", "mcp-script", options);
		rewriteElement(doc, "img", "src", "mcp-img", options);
		rewriteElement(doc, "source", "src", "mcp-source", options);
		rewriteElement(doc, "audio", "src", "mcp-audio", options);
		rewriteElement(doc, "video", "src", "mcp-video", options);
	}
	const head = ensureHead(doc);
	removeCspMeta(head);
	if (options.csp) {
		const meta = doc.createElement("meta");
		meta.setAttribute("http-equiv", "Content-Security-Policy");
		meta.setAttribute("content", options.csp);
		head.insertBefore(meta, head.firstChild);
	}
	const themeStyle = doc.createElement("style");
	themeStyle.setAttribute("data-mcp", "theme");
	themeStyle.textContent = options.themeCss;
	head.insertBefore(themeStyle, head.firstChild);
	let bootstrapAnchor = themeStyle.nextSibling;
	if (options.themeLink) {
		const linkNode = buildThemeLink(doc, options.themeLink, options);
		if (linkNode) {
			head.insertBefore(linkNode, themeStyle.nextSibling);
			bootstrapAnchor = linkNode.nextSibling;
		}
	}
	const bootstrap = doc.createElement("script");
	bootstrap.setAttribute("data-mcp", "bootstrap");
	bootstrap.textContent = options.bootstrapScript;
	head.insertBefore(bootstrap, bootstrapAnchor);
	const doctype = doc.doctype ? `<!doctype ${doc.doctype.name}>` : "<!doctype html>";
	return `${doctype}
${doc.documentElement.outerHTML}`;
}
function rewriteElement(doc, tagName, attrName, replacementTag, options) {
	const elements = Array.from(doc.getElementsByTagName(tagName));
	for (const el of elements) {
		const raw = el.getAttribute(attrName);
		if (!raw) {
			continue;
		}
		const info = resolveUrl(raw, options.rootUri || void 0);
		if (!info) {
			continue;
		}
		if (info.scheme !== "ui" && info.scheme !== "http" && info.scheme !== "https") {
			continue;
		}
		const replacement = doc.createElement(replacementTag);
		copyAttributes(el, replacement);
		replacement.setAttribute(attrName, info.url);
		if (el.ownerDocument?.head && el.ownerDocument.head.contains(el)) {
			replacement.setAttribute("data-mcp-head", "true");
		}
		if (info.scheme === "http" || info.scheme === "https") {
			if (!options.allowRemote(info.url)) {
				replacement.setAttribute(BLOCKED_ATTR, "true");
			}
		}
		el.replaceWith(replacement);
	}
}
function rewriteElementUrls(doc, tagName, attrName, options) {
	const elements = Array.from(doc.getElementsByTagName(tagName));
	for (const el of elements) {
		const raw = el.getAttribute(attrName);
		if (!raw) {
			continue;
		}
		const info = resolveUrl(raw, options.rootUri || void 0);
		if (!info) {
			continue;
		}
		if (info.scheme === "ui") {
			el.setAttribute(attrName, buildResourceUrl(options.resourceBase || "", info.url));
			continue;
		}
		if ((info.scheme === "http" || info.scheme === "https") && !options.allowRemote(info.url)) {
			el.setAttribute(BLOCKED_ATTR, "true");
			if (tagName === "script") {
				el.removeAttribute(attrName);
			}
		}
	}
}
function resolveUrl(raw, base) {
	const value = raw.trim();
	if (!value) {
		return null;
	}
	if (value.startsWith("#")) {
		return null;
	}
	const schemeMatch = value.match(/^[a-zA-Z][a-zA-Z0-9+.-]*:/);
	if (schemeMatch) {
		const scheme = schemeMatch[0].slice(0, -1).toLowerCase();
		if (isIgnoredScheme(scheme)) {
			return null;
		}
		return { url: value, scheme };
	}
	if (!base) {
		return null;
	}
	try {
		const resolved = new URL(value, base).toString();
		const scheme = new URL(resolved).protocol.replace(":", "").toLowerCase();
		if (isIgnoredScheme(scheme)) {
			return null;
		}
		return { url: resolved, scheme };
	}
	catch {
		return null;
	}
}
function isIgnoredScheme(scheme) {
	return ["data", "blob", "mailto", "tel", "javascript", "about"].includes(scheme);
}
function copyAttributes(from, to) {
	for (const attr of Array.from(from.attributes)) {
		to.setAttribute(attr.name, attr.value);
	}
}
function addRootClass(el, className) {
	if (!el) {
		return;
	}
	const existing = el.getAttribute("class");
	if (!existing) {
		el.setAttribute("class", className);
		return;
	}
	const classes = new Set(existing.split(/\s+/).filter(Boolean));
	classes.add(className);
	el.setAttribute("class", Array.from(classes).join(" "));
}
function ensureHead(doc) {
	if (doc.head) {
		return doc.head;
	}
	const head = doc.createElement("head");
	const html = doc.documentElement;
	if (html.firstChild) {
		html.insertBefore(head, html.firstChild);
	}
	else {
		html.appendChild(head);
	}
	return head;
}
function removeCspMeta(head) {
	const metas = Array.from(head.querySelectorAll("meta[http-equiv]"));
	for (const meta of metas) {
		const value = meta.getAttribute("http-equiv");
		if (!value) {
			continue;
		}
		if (value.toLowerCase() === "content-security-policy") {
			meta.remove();
		}
	}
}
function buildResourceUrl(base, uri) {
	if (!base) {
		return uri;
	}
	const stripped = uri.startsWith("ui://") ? uri.slice("ui://".length) : uri;
	if (/\{uri\}/i.test(base)) {
		return base.replace(/\{uri\}/gi, encodeURIComponent(uri));
	}
	if (/\{path\}/i.test(base)) {
		return base.replace(/\{path\}/gi, encodeURI(stripped));
	}
	if (base.includes("?")) {
		const joiner = base.endsWith("?") || base.endsWith("&") ? "" : "&";
		return `${base}${joiner}uri=${encodeURIComponent(uri)}`;
	}
	if (base.endsWith("/")) {
		return `${base}${encodeURI(stripped)}`;
	}
	return `${base}/${encodeURI(stripped)}`;
}
function buildThemeLink(doc, url, options) {
	const trimmed = url.trim();
	const info = trimmed.startsWith("ui://")
		? resolveUrl(trimmed, options.rootUri || void 0)
		: resolveUrlWithWindow(trimmed);
	if (!info) {
		return null;
	}
	if (info.scheme === "ui") {
		if (options.resourceBase) {
			const node2 = doc.createElement("link");
			node2.setAttribute("rel", "stylesheet");
			node2.setAttribute("href", buildResourceUrl(options.resourceBase, info.url));
			node2.setAttribute("layer", "mcp-user");
			return node2;
		}
		const node = doc.createElement("mcp-link");
		node.setAttribute("rel", "stylesheet");
		node.setAttribute("href", info.url);
		node.setAttribute("layer", "mcp-user");
		return node;
	}
	if (info.scheme === "http" || info.scheme === "https") {
		const node = doc.createElement("link");
		node.setAttribute("rel", "stylesheet");
		node.setAttribute("href", info.url);
		node.setAttribute("layer", "mcp-user");
		return node;
	}
	return null;
}
function resolveUrlWithWindow(raw) {
	if (typeof window === "undefined" || !window.location) {
		return null;
	}
	try {
		const resolved = new URL(raw, window.location.href).toString();
		const scheme = new URL(resolved).protocol.replace(":", "").toLowerCase();
		if (isIgnoredScheme(scheme)) {
			return null;
		}
		return { url: resolved, scheme };
	}
	catch {
		return null;
	}
}
// src/theme.ts
var DEFAULT_LAYERS = ["mcp-default", "mcp-content", "mcp-user"];
var defaultVariables = {
	"--mcp-color-bg": "#f7f7f5",
	"--mcp-color-fg": "#1f1f1b",
	"--mcp-color-muted": "#e7e6e2",
	"--mcp-color-muted-fg": "#4b4a44",
	"--mcp-color-border": "#d1d0ca",
	"--mcp-color-border-strong": "#b5b3ab",
	"--mcp-surface": "#ffffff",
	"--mcp-surface-alt": "#f0f0ec",
	"--mcp-color-primary": "#3b5ccc",
	"--mcp-color-primary-fg": "#f8f9ff",
	"--mcp-color-secondary": "#5b6478",
	"--mcp-color-secondary-fg": "#f5f7fb",
	"--mcp-color-accent": "#2f7f6b",
	"--mcp-color-accent-fg": "#f1fffb",
	"--mcp-color-success": "#2f7f6b",
	"--mcp-color-success-fg": "#f1fffb",
	"--mcp-color-warning": "#b26a1f",
	"--mcp-color-warning-fg": "#fff6ea",
	"--mcp-color-danger": "#b3343a",
	"--mcp-color-danger-fg": "#fff1f2",
	"--mcp-syntax-comment": "color-mix(in srgb, var(--mcp-color-muted-fg) 75%, var(--mcp-color-fg))",
	"--mcp-syntax-constant": "color-mix(in srgb, var(--mcp-color-accent) 70%, var(--mcp-color-fg))",
	"--mcp-syntax-keyword": "color-mix(in srgb, var(--mcp-color-primary) 75%, var(--mcp-color-fg))",
	"--mcp-syntax-entity": "color-mix(in srgb, var(--mcp-color-secondary) 70%, var(--mcp-color-fg))",
	"--mcp-syntax-tag": "color-mix(in srgb, var(--mcp-color-accent) 75%, var(--mcp-color-fg))",
	"--mcp-syntax-variable": "color-mix(in srgb, var(--mcp-color-fg) 85%, var(--mcp-color-muted-fg))",
	"--mcp-syntax-string": "color-mix(in srgb, var(--mcp-color-success) 75%, var(--mcp-color-fg))",
	"--mcp-syntax-number": "color-mix(in srgb, var(--mcp-color-warning) 75%, var(--mcp-color-fg))",
	"--mcp-syntax-operator": "color-mix(in srgb, var(--mcp-color-fg) 80%, var(--mcp-color-muted-fg))",
	"--mcp-syntax-punctuation": "color-mix(in srgb, var(--mcp-color-fg) 70%, var(--mcp-color-muted-fg))",
	"--mcp-font-sans": '"IBM Plex Sans", "Source Sans 3", "Assistant", "Noto Sans", "Apple Color '
		+ 'Emoji", "Segoe UI Emoji", "Noto Color Emoji", sans-serif',
	"--mcp-font-serif": '"IBM Plex Serif", "Source Serif 4", "Noto Serif", serif',
	"--mcp-font-mono": '"IBM Plex Mono", "Source Code Pro", "Fira Mono", monospace',
	"--mcp-text-2xs": "0.625rem",
	"--mcp-text-xs": "0.75rem",
	"--mcp-text-s": "0.875rem",
	"--mcp-text-m": "1rem",
	"--mcp-text-l": "1.125rem",
	"--mcp-text-xl": "1.25rem",
	"--mcp-text-2xl": "1.5rem",
	"--mcp-leading-tight": "1.2",
	"--mcp-leading-normal": "1.5",
	"--mcp-leading-relaxed": "1.75",
	"--mcp-weight-regular": "400",
	"--mcp-weight-medium": "500",
	"--mcp-weight-bold": "700",
	"--mcp-space-3xs": "0.125rem",
	"--mcp-space-2xs": "0.25rem",
	"--mcp-space-xs": "0.375rem",
	"--mcp-space-s": "0.5rem",
	"--mcp-space-m": "0.75rem",
	"--mcp-space-l": "1rem",
	"--mcp-space-xl": "1.5rem",
	"--mcp-space-2xl": "2rem",
	"--mcp-radius-0": "0",
	"--mcp-radius-xs": "0.125rem",
	"--mcp-radius-s": "0.25rem",
	"--mcp-radius-m": "0.5rem",
	"--mcp-radius-l": "0.75rem",
	"--mcp-radius-xl": "1rem",
	"--mcp-radius-round": "9999px",
	"--mcp-shadow-none": "none",
	"--mcp-shadow-xs": "0 1px 2px rgba(0, 0, 0, 0.08)",
	"--mcp-shadow-s": "0 2px 6px rgba(0, 0, 0, 0.12)",
	"--mcp-shadow-m": "0 6px 16px rgba(0, 0, 0, 0.16)",
	"--mcp-shadow-l": "0 12px 32px rgba(0, 0, 0, 0.2)",
	"--mcp-layer-0": "0",
	"--mcp-layer-1": "10",
	"--mcp-layer-2": "20",
	"--mcp-layer-3": "30",
	"--mcp-layer-4": "40",
	"--mcp-duration-instant": "0ms",
	"--mcp-duration-fast": "120ms",
	"--mcp-duration-normal": "200ms",
	"--mcp-duration-slow": "320ms",
	"--mcp-ease-standard": "cubic-bezier(0.2, 0, 0, 1)",
	"--mcp-ease-emphasized": "cubic-bezier(0.2, 0, 0, 1.4)",
	"--mcp-ring": "0 0 0 2px",
	"--mcp-ring-color": "rgba(59, 92, 204, 0.2)"
};
var mcpAppsDefaultVariables = {
	"--color-background-primary": "light-dark(rgba(255, 255, 255, 1), rgba(48, 48, 46, 1))",
	"--color-background-secondary": "light-dark(rgba(245, 244, 237, 1), rgba(38, 38, 36, 1))",
	"--color-background-tertiary": "light-dark(rgba(250, 249, 245, 1), rgba(20, 20, 19, 1))",
	"--color-background-inverse": "light-dark(rgba(20, 20, 19, 1), rgba(250, 249, 245, 1))",
	"--color-background-ghost": "light-dark(rgba(255, 255, 255, 0), rgba(48, 48, 46, 0))",
	"--color-background-info": "light-dark(rgba(214, 228, 246, 1), rgba(37, 62, 95, 1))",
	"--color-background-danger": "light-dark(rgba(247, 236, 236, 1), rgba(96, 42, 40, 1))",
	"--color-background-success": "light-dark(rgba(233, 241, 220, 1), rgba(27, 70, 20, 1))",
	"--color-background-warning": "light-dark(rgba(246, 238, 223, 1), rgba(72, 58, 15, 1))",
	"--color-background-disabled": "light-dark(rgba(255, 255, 255, 0.5), rgba(48, 48, 46, 0.5))",
	"--color-text-primary": "light-dark(rgba(20, 20, 19, 1), rgba(250, 249, 245, 1))",
	"--color-text-secondary": "light-dark(rgba(61, 61, 58, 1), rgba(194, 192, 182, 1))",
	"--color-text-tertiary": "light-dark(rgba(115, 114, 108, 1), rgba(156, 154, 146, 1))",
	"--color-text-inverse": "light-dark(rgba(255, 255, 255, 1), rgba(20, 20, 19, 1))",
	"--color-text-ghost": "light-dark(rgba(115, 114, 108, 0.5), rgba(156, 154, 146, 0.5))",
	"--color-text-info": "light-dark(rgba(50, 102, 173, 1), rgba(128, 170, 221, 1))",
	"--color-text-danger": "light-dark(rgba(127, 44, 40, 1), rgba(238, 136, 132, 1))",
	"--color-text-success": "light-dark(rgba(38, 91, 25, 1), rgba(122, 185, 72, 1))",
	"--color-text-warning": "light-dark(rgba(90, 72, 21, 1), rgba(209, 160, 65, 1))",
	"--color-text-disabled": "light-dark(rgba(20, 20, 19, 0.5), rgba(250, 249, 245, 0.5))",
	"--color-border-primary": "light-dark(rgba(31, 30, 29, 0.4), rgba(222, 220, 209, 0.4))",
	"--color-border-secondary": "light-dark(rgba(31, 30, 29, 0.3), rgba(222, 220, 209, 0.3))",
	"--color-border-tertiary": "light-dark(rgba(31, 30, 29, 0.15), rgba(222, 220, 209, 0.15))",
	"--color-border-inverse": "light-dark(rgba(255, 255, 255, 0.3), rgba(20, 20, 19, 0.15))",
	"--color-border-ghost": "light-dark(rgba(31, 30, 29, 0), rgba(222, 220, 209, 0))",
	"--color-border-info": "light-dark(rgba(70, 130, 213, 1), rgba(70, 130, 213, 1))",
	"--color-border-danger": "light-dark(rgba(167, 61, 57, 1), rgba(205, 92, 88, 1))",
	"--color-border-success": "light-dark(rgba(67, 116, 38, 1), rgba(89, 145, 48, 1))",
	"--color-border-warning": "light-dark(rgba(128, 92, 31, 1), rgba(168, 120, 41, 1))",
	"--color-border-disabled": "light-dark(rgba(31, 30, 29, 0.1), rgba(222, 220, 209, 0.1))",
	"--color-ring-primary": "light-dark(rgba(20, 20, 19, 0.7), rgba(250, 249, 245, 0.7))",
	"--color-ring-secondary": "light-dark(rgba(61, 61, 58, 0.7), rgba(194, 192, 182, 0.7))",
	"--color-ring-inverse": "light-dark(rgba(255, 255, 255, 0.7), rgba(20, 20, 19, 0.7))",
	"--color-ring-info": "light-dark(rgba(50, 102, 173, 0.5), rgba(128, 170, 221, 0.5))",
	"--color-ring-danger": "light-dark(rgba(167, 61, 57, 0.5), rgba(205, 92, 88, 0.5))",
	"--color-ring-success": "light-dark(rgba(67, 116, 38, 0.5), rgba(89, 145, 48, 0.5))",
	"--color-ring-warning": "light-dark(rgba(128, 92, 31, 0.5), rgba(168, 120, 41, 0.5))",
	"--font-sans": "Anthropic Sans, sans-serif",
	"--font-mono": "ui-monospace, monospace",
	"--font-weight-normal": "400",
	"--font-weight-medium": "500",
	"--font-weight-semibold": "600",
	"--font-weight-bold": "700",
	"--font-text-xs-size": "12px",
	"--font-text-sm-size": "14px",
	"--font-text-md-size": "16px",
	"--font-text-lg-size": "20px",
	"--font-heading-xs-size": "12px",
	"--font-heading-sm-size": "14px",
	"--font-heading-md-size": "16px",
	"--font-heading-lg-size": "20px",
	"--font-heading-xl-size": "24px",
	"--font-heading-2xl-size": "28px",
	"--font-heading-3xl-size": "36px",
	"--font-text-xs-line-height": "1.4",
	"--font-text-sm-line-height": "1.4",
	"--font-text-md-line-height": "1.4",
	"--font-text-lg-line-height": "1.25",
	"--font-heading-xs-line-height": "1.4",
	"--font-heading-sm-line-height": "1.4",
	"--font-heading-md-line-height": "1.4",
	"--font-heading-lg-line-height": "1.25",
	"--font-heading-xl-line-height": "1.25",
	"--font-heading-2xl-line-height": "1.1",
	"--font-heading-3xl-line-height": "1",
	"--border-radius-xs": "4px",
	"--border-radius-sm": "6px",
	"--border-radius-md": "8px",
	"--border-radius-lg": "10px",
	"--border-radius-xl": "12px",
	"--border-radius-full": "9999px",
	"--border-width-regular": "0.5px",
	"--shadow-hairline": "0 1px 2px 0 rgba(0, 0, 0, 0.05)",
	"--shadow-sm": "0 1px 3px 0 rgba(0, 0, 0, 0.1), 0 1px 2px -1px rgba(0, 0, 0, 0.1)",
	"--shadow-md": "0 4px 6px -1px rgba(0, 0, 0, 0.1), 0 2px 4px -2px rgba(0, 0, 0, 0.1)",
	"--shadow-lg": "0 10px 15px -3px rgba(0, 0, 0, 0.1), 0 4px 6px -4px rgba(0, 0, 0, 0.1)"
};
var mcpAppsAliasVariables = {
	"--mcp-color-bg": "var(--color-background-primary, #f7f7f5)",
	"--mcp-surface": "var(--color-background-primary, #ffffff)",
	"--mcp-surface-alt": "var(--color-background-secondary, #f0f0ec)",
	"--mcp-color-muted": "var(--color-background-secondary, #e7e6e2)",
	"--mcp-color-fg": "var(--color-text-primary, #1f1f1b)",
	"--mcp-color-muted-fg": "var(--color-text-secondary, #4b4a44)",
	"--mcp-color-border": "var(--color-border-primary, #d1d0ca)",
	"--mcp-color-border-strong": "var(--color-border-secondary, #b5b3ab)",
	"--mcp-color-primary": "var(--color-background-accent, #3b5ccc)",
	"--mcp-color-primary-fg": "var(--color-text-accent, #f8f9ff)",
	"--mcp-color-secondary": "var(--color-background-secondary, #5b6478)",
	"--mcp-color-secondary-fg": "var(--color-text-secondary, #f5f7fb)",
	"--mcp-color-accent": "var(--color-background-accent, #2f7f6b)",
	"--mcp-color-accent-fg": "var(--color-text-accent, #f1fffb)",
	"--mcp-color-success": "var(--color-background-success, #2f7f6b)",
	"--mcp-color-success-fg": "var(--color-text-success, #f1fffb)",
	"--mcp-color-warning": "var(--color-background-warning, #b26a1f)",
	"--mcp-color-warning-fg": "var(--color-text-warning, #fff6ea)",
	"--mcp-color-danger": "var(--color-background-danger, #b3343a)",
	"--mcp-color-danger-fg": "var(--color-text-danger, #fff1f2)",
	"--mcp-font-sans": 'var(--font-sans, "IBM Plex Sans", "Source Sans 3", sans-serif)',
	"--mcp-font-serif": 'var(--font-serif, "IBM Plex Serif", "Source Serif 4", serif)',
	"--mcp-font-mono": 'var(--font-mono, "IBM Plex Mono", "Source Code Pro", monospace)',
	"--mcp-radius-xs": "var(--border-radius-xs, 0.125rem)",
	"--mcp-radius-s": "var(--border-radius-sm, 0.25rem)",
	"--mcp-radius-m": "var(--border-radius-md, 0.5rem)",
	"--mcp-radius-l": "var(--border-radius-lg, 0.75rem)",
	"--mcp-radius-xl": "var(--border-radius-xl, 1rem)",
	"--mcp-radius-round": "var(--border-radius-full, 9999px)",
	"--mcp-shadow-xs": "var(--shadow-xs, 0 1px 2px rgba(0, 0, 0, 0.08))",
	"--mcp-shadow-s": "var(--shadow-sm, 0 2px 6px rgba(0, 0, 0, 0.12))",
	"--mcp-shadow-m": "var(--shadow-md, 0 6px 16px rgba(0, 0, 0, 0.16))",
	"--mcp-shadow-l": "var(--shadow-lg, 0 12px 32px rgba(0, 0, 0, 0.2))"
};
var utilityCss = [
	".mcp-root{",
	`  ${serializeVariables(defaultVariables)}`,
	`  ${serializeVariables(mcpAppsAliasVariables)}`,
	"  color: var(--mcp-color-fg);",
	"  background: var(--mcp-color-bg);",
	"  font-family: var(--mcp-font-sans);",
	"  font-size: var(--mcp-text-m);",
	"  line-height: var(--mcp-leading-normal);",
	"  font-weight: var(--mcp-weight-regular);",
	"  margin: 0;",
	"}",
	".mcp-button{",
	"  display: inline-flex;",
	"  align-items: center;",
	"  justify-content: center;",
	"  gap: var(--mcp-space-2xs);",
	"  padding: var(--mcp-space-xs) var(--mcp-space-m);",
	"  border: 1px solid var(--mcp-color-border);",
	"  border-radius: var(--mcp-radius-m);",
	"  background: var(--mcp-color-bg);",
	"  color: var(--mcp-color-fg);",
	"  font-size: var(--mcp-text-s);",
	"  line-height: var(--mcp-leading-normal);",
	"  text-decoration: none;",
	"  cursor: pointer;",
	"  transition: background var(--mcp-duration-fast) var(--mcp-ease-standard), border-color var(--mcp-duration-fast) var(--mcp-ease-standard), transform var(--mcp-duration-fast) var(--mcp-ease-standard);",
	"}",
	".mcp-button:where(:hover){",
	"  background: var(--mcp-color-muted);",
	"}",
	".mcp-button:where(:active){",
	"  transform: translateY(1px);",
	"}",
	".mcp-button:where(.active, [aria-pressed='true']){",
	"  filter: brightness(0.98);",
	"}",
	".mcp-button-primary{",
	"  background: var(--mcp-color-primary);",
	"  color: var(--mcp-color-primary-fg);",
	"  border-color: var(--mcp-color-primary);",
	"}",
	".mcp-button-primary:where(:hover){",
	"  filter: brightness(0.98);",
	"}",
	".mcp-button-secondary{",
	"  background: var(--mcp-color-secondary);",
	"  color: var(--mcp-color-secondary-fg);",
	"  border-color: var(--mcp-color-secondary);",
	"}",
	".mcp-button-ghost{",
	"  background: transparent;",
	"  border-color: transparent;",
	"}",
	".mcp-button-danger{",
	"  background: var(--mcp-color-danger);",
	"  color: var(--mcp-color-danger-fg);",
	"  border-color: var(--mcp-color-danger);",
	"}",
	".mcp-toolbar{",
	"  display: inline-flex;",
	"  align-items: center;",
	"  border: 1px solid var(--mcp-color-border);",
	"  border-radius: var(--mcp-radius-m);",
	"}",
	".mcp-toolbar .mcp-button{",
	"  border: 0;",
	"  border-radius: 0;",
	"}",
	".mcp-toolbar .mcp-button + .mcp-button{",
	"  border-left: 1px solid var(--mcp-color-border);",
	"}",
	".mcp-toolbar .mcp-button:first-child{",
	"  border-top-left-radius: var(--mcp-radius-m);",
	"  border-bottom-left-radius: var(--mcp-radius-m);",
	"}",
	".mcp-toolbar .mcp-button:last-child{",
	"  border-top-right-radius: var(--mcp-radius-m);",
	"  border-bottom-right-radius: var(--mcp-radius-m);",
	"}",
	".mcp-toolbar .mcp-button:where(.active, [aria-pressed='true']){",
	"  position: relative;",
	"  z-index: 1;",
	"}",
	".mcp-input, .mcp-textarea, .mcp-select{",
	"  width: 100%;",
	"  padding: var(--mcp-space-xs) var(--mcp-space-s);",
	"  border: 1px solid var(--mcp-color-border);",
	"  border-radius: var(--mcp-radius-s);",
	"  background: var(--mcp-color-bg);",
	"  color: var(--mcp-color-fg);",
	"  font-size: var(--mcp-text-s);",
	"  line-height: var(--mcp-leading-normal);",
	"}",
	".mcp-input:where(:focus), .mcp-textarea:where(:focus), .mcp-select:where(:focus){",
	"  outline: none;",
	"  border-color: var(--mcp-color-primary);",
	"  box-shadow: var(--mcp-ring) var(--mcp-ring-color);",
	"}",
	".mcp-label{",
	"  display: block;",
	"  margin-bottom: var(--mcp-space-3xs);",
	"  font-size: var(--mcp-text-xs);",
	"  color: var(--mcp-color-muted-fg);",
	"}",
	".mcp-card{",
	"  background: var(--mcp-surface);",
	"  border: 1px solid var(--mcp-color-border);",
	"  border-radius: var(--mcp-radius-l);",
	"  padding: var(--mcp-space-l);",
	"  box-shadow: var(--mcp-shadow-xs);",
	"}",
	".mcp-panel{",
	"  background: var(--mcp-surface);",
	"  border: 1px solid var(--mcp-color-border);",
	"  border-radius: var(--mcp-radius-l);",
	"  padding: var(--mcp-space-l);",
	"  box-shadow: var(--mcp-shadow-xs);",
	"}",
	".mcp-field{",
	"  display: flex;",
	"  flex-direction: column;",
	"  gap: var(--mcp-space-3xs);",
	"}",
	".mcp-divider{",
	"  height: 1px;",
	"  background: var(--mcp-color-border);",
	"  border: 0;",
	"}",
	".mcp-surface{",
	"  background: var(--mcp-color-muted);",
	"  border-radius: var(--mcp-radius-m);",
	"  padding: var(--mcp-space-m);",
	"}",
	".mcp-badge{",
	"  display: inline-flex;",
	"  align-items: center;",
	"  padding: 0 var(--mcp-space-xs);",
	"  border-radius: var(--mcp-radius-round);",
	"  background: var(--mcp-color-secondary);",
	"  color: var(--mcp-color-secondary-fg);",
	"  font-size: var(--mcp-text-2xs);",
	"  line-height: 1.6;",
	"}",
	".mcp-tag{",
	"  display: inline-flex;",
	"  align-items: center;",
	"  padding: 0 var(--mcp-space-xs);",
	"  border-radius: var(--mcp-radius-s);",
	"  background: var(--mcp-color-muted);",
	"  color: var(--mcp-color-muted-fg);",
	"  font-size: var(--mcp-text-2xs);",
	"  line-height: 1.6;",
	"}",
	".mcp-divider{",
	"  height: 1px;",
	"  background: var(--mcp-color-border);",
	"}",
	".mcp-text-muted{",
	"  color: var(--mcp-color-muted-fg);",
	"}",
	".mcp-title{",
	"  font-size: var(--mcp-text-xl);",
	"  line-height: var(--mcp-leading-tight);",
	"  font-weight: var(--mcp-weight-bold);",
	"}",
	".mcp-subtitle{",
	"  font-size: var(--mcp-text-l);",
	"  line-height: var(--mcp-leading-normal);",
	"  font-weight: var(--mcp-weight-medium);",
	"  color: var(--mcp-color-muted-fg);",
	"}",
].join("\n");
function buildThemeCss(inputs) {
	const parts = [];
	const order = buildLayerOrder(inputs.layers);
	parts.push(`@layer ${order.join(", ")};`);
	parts.push(wrapLayer("mcp-default", utilityCss));
	const hostOverrides = normalizeCssInput(inputs.hostVariables);
	const overrides = normalizeCssInput(inputs.css);
	const combined = [hostOverrides, overrides].filter(Boolean).join("\n");
	if (combined) {
		parts.push(wrapLayer("mcp-user", combined));
	}
	return parts.join("\n\n");
}
function buildLayerOrder(layers) {
	const order = [];
	if (layers && layers.length > 0) {
		for (const layer of layers) {
			const trimmed = layer.trim();
			if (!trimmed) {
				continue;
			}
			if (!order.includes(trimmed)) {
				order.push(trimmed);
			}
		}
	}
	for (let i = DEFAULT_LAYERS.length - 1; i >= 0; i -= 1) {
		const layer = DEFAULT_LAYERS[i];
		if (!order.includes(layer)) {
			order.unshift(layer);
		}
	}
	return order;
}
function wrapLayer(name, css) {
	return `@layer ${name} {
${css}
}`;
}
function serializeVariables(vars) {
	return Object.entries(vars).map(([key, value]) => `${key}: ${value};`).join("\n  ");
}
function applyHostStyleVariables(vars, root = document.documentElement) {
	if (!vars || typeof vars !== "object" || !root?.style) {
		return;
	}
	for (const [key, value] of Object.entries(vars)) {
		if (!key.startsWith("--") || typeof value !== "string") {
			continue;
		}
		root.style.setProperty(key, value);
	}
}
function applyHostFonts(fontCss, doc = document) {
	if (!fontCss || !doc) {
		return;
	}
	const styleId = "mcp-host-fonts";
	let style = doc.getElementById(styleId);
	if (!style) {
		style = doc.createElement("style");
		style.id = styleId;
		(doc.head || doc.documentElement).appendChild(style);
	}
	if (style.textContent !== fontCss) {
		style.textContent = fontCss;
	}
}
function applyDocumentTheme(theme, root = document.documentElement) {
	if (!root) {
		return;
	}
	if (theme !== "light" && theme !== "dark") {
		return;
	}
	root.setAttribute("data-theme", theme);
	root.style.colorScheme = theme;
}
function normalizeCssInput(input) {
	if (!input) {
		return null;
	}
	const value = input;
	if (!value) {
		return null;
	}
	if (typeof value === "string") {
		return value;
	}
	if (typeof value === "object") {
		const filtered = filterVariables(value);
		if (Object.keys(filtered).length === 0) {
			return null;
		}
		return `.mcp-root{${serializeVariables(filtered)}}`;
	}
	return null;
}
function filterVariables(vars) {
	const filtered = {};
	for (const [key, value] of Object.entries(vars)) {
		if (key.startsWith("--mcp-")
				|| key.startsWith("--color-")
				|| key.startsWith("--font-")
				|| key.startsWith("--border-")
				|| key.startsWith("--shadow-")) {
			filtered[key] = value;
		}
	}
	return filtered;
}
// src/mcp-view.ts
var McpViewElement = class extends HTMLElement {
	constructor() {
		super();
		this._resolver = null;
		this._toolCaller = null;
		this._css = null;
		this._allowedOrigins = [];
		this._allowedRemote = null;
		this._data = null;
		this._toolResult = null;
		this._connected = false;
		this._hostContext = { theme: "light", styles: { variables: { ...mcpAppsDefaultVariables }, css: {} } };
		this._csp = null;
		this._layers = null;
		this._iframe = null;
		this._loadToken = 0;
		this._renderScheduled = false;
		this._boundMessageHandler = (event) => this.handleMessage(event);
		const root = this.attachShadow({ mode: "closed" });
		const style = document.createElement("style");
		style.textContent = [":host{display:block;height:100%;width:100%;}", "iframe{width:100%;height:100%;border:0;}"].join("\n");
		root.appendChild(style);
		const iframe = document.createElement("iframe");
		iframe.setAttribute("sandbox", "allow-scripts");
		iframe.setAttribute("referrerpolicy", "no-referrer");
		iframe.style.width = "100%";
		iframe.style.height = "100%";
		iframe.style.border = "0";
		iframe.addEventListener(
			"load",
			() => {
				this._connected = false;
				this.sendDataUpdate();
			}
		);
		root.appendChild(iframe);
		this._iframe = iframe;
	}
	static get observedAttributes() {
		return ["src", "theme", "base", "resource-base", "auto-height", "max-height", "defer"];
	}
	connectedCallback() {
		window.addEventListener("message", this._boundMessageHandler);
		console.info("[mcp] view connected", { src: this.getAttribute("src"), resourceBase: this.resourceBase || null });
		this.scheduleRender();
	}
	disconnectedCallback() {
		window.removeEventListener("message", this._boundMessageHandler);
	}
	attributeChangedCallback(name) {
		if (name === "src" || name === "theme" || name === "base" || name === "resource-base") {
			this.scheduleRender();
			return;
		}
		if (name === "auto-height") {
			this.scheduleRender();
		}
	}
	get resolver() {
		return this._resolver;
	}
	set resolver(value) {
		this._resolver = value;
		this.scheduleRender();
	}
	get themeUrl() {
		return this.getAttribute("theme") || "";
	}
	get toolCaller() {
		return this._toolCaller;
	}
	set toolCaller(value) {
		this._toolCaller = value;
	}
	get base() {
		return this.getAttribute("base") || "";
	}
	set base(value) {
		if (value) {
			this.setAttribute("base", value);
		}
		else {
			this.removeAttribute("base");
		}
	}
	get resourceBase() {
		return this.getAttribute("resource-base") || "";
	}
	set resourceBase(value) {
		if (value) {
			this.setAttribute("resource-base", value);
		}
		else {
			this.removeAttribute("resource-base");
		}
	}
	get src() {
		return this.getAttribute("src") || "";
	}
	set src(value) {
		if (value) {
			this.setAttribute("src", value);
		}
		else {
			this.removeAttribute("src");
		}
	}
	get allowedOrigins() {
		return this._allowedOrigins;
	}
	set allowedOrigins(value) {
		this._allowedOrigins = Array.isArray(value) ? value : [];
	}
	get allowedRemote() {
		return this._allowedRemote;
	}
	set allowedRemote(value) {
		this._allowedRemote = value;
	}
	get layers() {
		return this._layers;
	}
	set layers(value) {
		this._layers = Array.isArray(value) ? value : null;
		this.scheduleRender();
	}
	get css() {
		return this._css;
	}
	set css(value) {
		this._css = value;
		this.scheduleRender();
	}
	get data() {
		return this._data;
	}
	set data(value) {
		this._data = value;
		this.sendDataUpdate();
	}
	get hostTheme() {
		return typeof this._hostContext?.theme === "string" ? String(this._hostContext.theme) : null;
	}
	set hostTheme(value) {
		const next = value === "light" || value === "dark" ? value : null;
		this.updateHostContext({ theme: next });
	}
	get hostStyleVariables() {
		const styles = this._hostContext?.styles;
		return styles?.variables || null;
	}
	set hostStyleVariables(value) {
		this.updateHostContext({ styles: { variables: value || null } });
	}
	get hostFonts() {
		const styles = this._hostContext?.styles;
		return styles?.css?.fonts || null;
	}
	set hostFonts(value) {
		this.updateHostContext({ styles: { css: { fonts: value || null } } });
	}
	get csp() {
		return this._csp;
	}
	set csp(value) {
		this._csp = value;
		this.scheduleRender();
	}
	get deferRender() {
		return this.readBooleanAttribute("defer");
	}
	set deferRender(value) {
		if (value) {
			this.setAttribute("defer", "true");
		}
		else {
			this.removeAttribute("defer");
		}
	}
	get toolResult() {
		return this._toolResult;
	}
	set toolResult(value) {
		this._toolResult = value;
		this.sendToolResult();
	}
	get autoHeight() {
		return this.readBooleanAttribute("auto-height");
	}
	set autoHeight(value) {
		if (value) {
			this.setAttribute("auto-height", "true");
		}
		else {
			this.removeAttribute("auto-height");
		}
	}
	get maxHeight() {
		const raw = this.getAttribute("max-height");
		if (!raw) {
			return null;
		}
		const parsed = Number(raw);
		if (!Number.isFinite(parsed) || parsed <= 0) {
			return null;
		}
		return parsed;
	}
	scheduleRender() {
		if (!this.isConnected) {
			return;
		}
		if (this.deferRender) {
			return;
		}
		if (this._renderScheduled) {
			return;
		}
		this._renderScheduled = true;
		window.requestAnimationFrame(() => {
				this._renderScheduled = false;
				this.render();
			});
	}
	render() {
		if (!this.isConnected) {
			return;
		}
		const uri = this.getRootUri();
		if (!uri) {
			return;
		}
		console.info("[mcp] render", { uri });
		void this.load(uri);
	}
	async load(uri) {
		const token = ++this._loadToken;
		const root = await this.resolveResource(uri, "document");
		if (!root.ok) {
			return;
		}
		console.info("[mcp] load", { uri, mime: root.mime, size: root.body?.length ?? 0 });
		const html = new TextDecoder().decode(root.body);
		const hostVariables = this._hostContext?.styles?.variables || null;
		const themeCss = buildThemeCss({ css: this._css, layers: this._layers, hostVariables });
		const bootstrapScript = getIframeBootstrapScript(this._data, { autoHeight: this.autoHeight });
		const rewritten = rewriteHtml({
			html,
			rootUri: uri,
			themeCss,
			themeLink: this.themeUrl || null,
			bootstrapScript,
			allowRemote: (url) => this.isRemoteAllowed(url),
			resourceBase: this.resourceBase || null,
			csp: this._csp
		});
		if (token !== this._loadToken) {
			return;
		}
		if (this._iframe) {
			this._iframe.srcdoc = rewritten;
		}
	}
	getRootUri() {
		return this.getAttribute("src") || "";
	}
	handleMessage(event) {
		if (!this._iframe || event.source !== this._iframe.contentWindow) {
			return;
		}
		const data = event.data;
		if (!data) {
			return;
		}
		if (data.type === "mcp:request") {
			const uri = typeof data.uri === "string" ? data.uri : "";
			const kind = data.kind || "document";
			const id = typeof data.id === "number" ? data.id : 0;
			if (!uri || !id) {
				console.error("[mcp] invalid request", { uri, id, kind });
				return;
			}
			const request = {
				id,
				uri,
				kind,
				source: event.source
			};
			void this.fulfillRequest(request);
			return;
		}
		if (data.type === "mcp:tool-call") {
			const id = typeof data.id === "number" ? data.id : 0;
			const params = data.params;
			const method = typeof data.method === "string" ? data.method : "tools/call";
			if (!id) {
				console.error("[mcp] invalid tool call", { id });
				return;
			}
			if (method !== "tools/call") {
				console.error("[mcp] unsupported tool method", { method });
				const message = {
					type: "mcp:tool-result",
					id,
					ok: false,
					error: `Unsupported tool method: ${method}`
				};
				this.postMessageToSource(event.source, message, []);
				return;
			}
			const request = { id, params, source: event.source };
			void this.fulfillToolCall(request);
			return;
		}
		if (data.type === "mcp:height") {
			if (!this.autoHeight) {
				return;
			}
			const height = typeof data.height === "number" ? data.height : 0;
			if (!Number.isFinite(height) || height <= 0) {
				return;
			}
			const maxHeight = this.maxHeight;
			const nextHeight = maxHeight ? Math.min(height, maxHeight) : height;
			this.style.height = `${Math.ceil(nextHeight)}px`;
			return;
		}
		if (data.type === "mcp:connect") {
			const id = typeof data.id === "number" ? data.id : 0;
			if (!id) {
				console.error("[mcp] invalid connect", { id });
				return;
			}
			this._connected = true;
			console.info("[mcp] connect", { id });
			this.postMessageToSource(
				event.source,
				{
					type: "mcp:connect-result",
					id,
					ok: true,
					hostCapabilities: {
						callServerTool: true,
						sendMessage: true,
						toolInput: true,
						toolResult: true,
						data: true,
						hostContext: true,
						styles: true
					},
					hostContext: this._hostContext
				},
				[]
			);
			this.sendHostContext();
			this.sendToolInput();
			this.sendToolResult();
			return;
		}
		if (data.type === "mcp:send-message") {
			const id = typeof data.id === "number" ? data.id : 0;
			if (!id) {
				console.error("[mcp] invalid send message", { id });
				return;
			}
			this.postMessageToSource(event.source, { type: "mcp:send-message-result", id, ok: true }, []);
			return;
		}
	}
	async fulfillRequest(request) {
		let response;
		try {
			response = await this.resolveResource(request.uri, request.kind);
		}
		catch (error) {
			response = {
				ok: false,
				mime: "text/plain",
				body: new Uint8Array(),
				error: error instanceof Error ? error.message : "Resolver error"
			};
		}
		if (!response.ok) {
			console.error(
				"[mcp] resolve failed",
				{ uri: request.uri, kind: request.kind, error: response.error || "Unknown error" }
			);
		}
		const body = response.body || new Uint8Array();
		const buffer = body.buffer.slice(body.byteOffset, body.byteOffset + body.byteLength);
		const message = {
			type: "mcp:response",
			id: request.id,
			ok: response.ok,
			mime: response.mime,
			body: buffer,
			error: response.error
		};
		this.postMessageToSource(request.source, message, [buffer]);
	}
	async fulfillToolCall(request) {
		let response;
		const caller = this._toolCaller;
		if (!caller) {
			response = { ok: false, error: "No tool caller" };
		}
		else {
			try {
				const result = await caller(request.params);
				if (result && typeof result === "object" && !Array.isArray(result)) {
					response = { ok: true, ...result };
				}
				else {
					response = { ok: true, result };
				}
			}
			catch (error) {
				response = { ok: false, error: error instanceof Error ? error.message : "Tool call failed" };
			}
		}
		if (!response.ok) {
			console.error("[mcp] tool call failed", { error: response.error || "Unknown error" });
		}
		const envelope = {
			result: response.result,
			structuredContent: response.structuredContent,
			content: response.content,
			_meta: response._meta,
			isError: response.isError
		};
		const message = {
			type: "mcp:tool-result",
			id: request.id,
			ok: response.ok,
			...envelope,
			error: response.error
		};
		this.postMessageToSource(request.source, message, []);
	}
	async resolveResource(uri, kind) {
		if (uri.startsWith("ui://")) {
			const resolver = this.getActiveResolver();
			if (!resolver) {
				return {
					ok: false,
					mime: "text/plain",
					body: new Uint8Array(),
					error: "No resolver"
				};
			}
			const result = await normalizeResolverResult(await resolver(uri), kind, uri);
			return result;
		}
		if (uri.startsWith("http://") || uri.startsWith("https://")) {
			if (!this.isRemoteAllowed(uri)) {
				return {
					ok: false,
					mime: "text/plain",
					body: new Uint8Array(),
					error: "Remote blocked"
				};
			}
			try {
				const res = await fetch(uri);
				const normalized = await normalizeResolverResult(res, kind, uri);
				return normalized;
			}
			catch (error) {
				return {
					ok: false,
					mime: "text/plain",
					body: new Uint8Array(),
					error: error instanceof Error ? error.message : "Remote fetch failed"
				};
			}
		}
		return {
			ok: false,
			mime: inferMime(kind, uri),
			body: new Uint8Array(),
			error: "Unsupported URI"
		};
	}
	isRemoteAllowed(url) {
		if (this._allowedRemote) {
			return this._allowedRemote(url);
		}
		if (!this._allowedOrigins.length) {
			return false;
		}
		try {
			const parsed = new URL(url);
			return this._allowedOrigins
				.some((origin) => {
					if (origin.includes("://")) {
						return parsed.origin === origin;
					}
					return parsed.hostname === origin;
				});
		}
		catch {
			return false;
		}
	}
	postMessageToSource(source, message, transfer = []) {
		if (!source) {
			console.error("[mcp] missing message source", message);
			return;
		}
		const postMessage = source.postMessage;
		if (typeof postMessage !== "function") {
			console.error("[mcp] invalid message source", source);
			return;
		}
		try {
			source.postMessage(message, "*", transfer);
			return;
		}
		catch (error) {
			try {
				source.postMessage(message, transfer);
			}
			catch (fallbackError) {
				console.error("[mcp] postMessage failed", { error, fallbackError });
			}
		}
	}
	sendDataUpdate() {
		if (!this._iframe || !this._iframe.contentWindow) {
			return;
		}
		console.info("[mcp] data update", { connected: this._connected });
		this._iframe.contentWindow.postMessage({ type: "mcp-data:update", payload: this._data }, "*");
		this.sendHostContext();
		this.sendToolInput();
		this.sendToolResult();
	}
	sendHostContext() {
		if (!this._connected || !this._iframe || !this._iframe.contentWindow) {
			return;
		}
		this._iframe
			.contentWindow
			.postMessage({ type: "mcp:host-context-changed", context: this._hostContext }, "*");
	}
	updateHostContext(update) {
		const next = { ...this._hostContext || {} };
		if (Object.prototype.hasOwnProperty.call(update, "theme")) {
			if (update.theme) {
				next.theme = update.theme;
			}
			else {
				delete next.theme;
			}
		}
		if (Object.prototype.hasOwnProperty.call(update, "styles")) {
			const currentStyles = next.styles || {};
			const nextStyles = { ...currentStyles };
			const styles = update.styles;
			if (styles && Object.prototype.hasOwnProperty.call(styles, "variables")) {
				const mergedVariables = { ...mcpAppsDefaultVariables, ...styles.variables || {} };
				nextStyles.variables = mergedVariables;
			}
			if (styles && Object.prototype.hasOwnProperty.call(styles, "css")) {
				const currentCss = nextStyles.css || {};
				const nextCss = { ...currentCss };
				if (styles.css && Object.prototype.hasOwnProperty.call(styles.css, "fonts")) {
					if (styles.css.fonts) {
						nextCss.fonts = styles.css.fonts;
					}
					else {
						delete nextCss.fonts;
					}
				}
				if (Object.keys(nextCss).length > 0) {
					nextStyles.css = nextCss;
				}
				else {
					delete nextStyles.css;
				}
			}
			if (Object.keys(nextStyles).length > 0) {
				next.styles = nextStyles;
			}
			else {
				delete next.styles;
			}
		}
		this._hostContext = next;
		this.sendHostContext();
	}
	sendToolInput() {
		if (!this._connected || !this._iframe || !this._iframe.contentWindow) {
			return;
		}
		console.info("[mcp] tool input", { hasInput: this._data != null });
		this._iframe.contentWindow.postMessage({ type: "mcp:tool-input", input: this._data }, "*");
	}
	sendToolResult() {
		if (!this._connected || !this._iframe || !this._iframe.contentWindow) {
			return;
		}
		const result = this._toolResult;
		if (result === void 0 || result === null) {
			return;
		}
		console.info("[mcp] send tool result", { resultType: typeof result });
		if (result && typeof result === "object" && !Array.isArray(result)) {
			this._iframe.contentWindow.postMessage({
				type: "mcp:tool-result",
				id: 0,
				ok: true,
				...result
			}, "*");
			return;
		}
		this._iframe.contentWindow.postMessage({
			type: "mcp:tool-result",
			id: 0,
			ok: true,
			result
		}, "*");
	}
	readBooleanAttribute(name) {
		const raw = this.getAttribute(name);
		if (raw === null) {
			return false;
		}
		if (raw === "") {
			return true;
		}
		const normalized = raw.toLowerCase().trim();
		if (normalized === "true" || normalized === "1" || normalized === "yes" || normalized === "on") {
			return true;
		}
		return false;
	}
	getActiveResolver() {
		if (this._resolver) {
			return this._resolver;
		}
		const base = this.resourceBase || this.base || DEFAULT_BASE;
		return this.buildDefaultResolver(base);
	}
	buildDefaultResolver(base) {
		return async(uri) => {
			const url = resolveBaseUrl(buildResolverUrl(base, uri));
			return fetch(url);
		};
	}
};
var DEFAULT_BASE = "/mcp/resources/";
function resolveBaseUrl(base) {
	if (!base) {
		return base;
	}
	if (typeof window === "undefined" || !window.location) {
		return base;
	}
	try {
		return new URL(base, window.location.href).toString();
	}
	catch {
		return base;
	}
}
function buildResolverUrl(base, uri) {
	const path = stripUiScheme(uri);
	if (base.includes("{path}")) {
		return base.split("{path}").join(encodeURI(path));
	}
	if (base.includes("{uri}")) {
		return base.split("{uri}").join(encodeURIComponent(uri));
	}
	if (base.includes("?")) {
		return `${base}&uri=${encodeURIComponent(uri)}`;
	}
	if (base.endsWith("/")) {
		return `${base}${encodeURI(path)}`;
	}
	return `${base}/${encodeURI(path)}`;
}
function stripUiScheme(uri) {
	if (!uri.startsWith("ui://")) {
		return uri;
	}
	return uri.slice("ui://".length);
}
function defineMcpView() {
	if (!customElements.get("mcp-view")) {
		customElements.define("mcp-view", McpViewElement);
	}
}
// src/index.ts
if (typeof window !== "undefined" && "customElements" in window) {
	defineMcpView();
}
export {
  McpViewElement,
  applyDocumentTheme,
  applyHostFonts,
  applyHostStyleVariables,
  defineMcpView
};
