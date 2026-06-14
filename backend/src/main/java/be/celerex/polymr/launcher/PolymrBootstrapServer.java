/*
* Copyright (C) 2026 Alexander Verbruggen
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Lesser General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Lesser General Public License for more details.
*
* You should have received a copy of the GNU Lesser General Public License
* along with this program. If not, see <https://www.gnu.org/licenses/>.
*/

package be.celerex.polymr.launcher;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.jboss.logging.Logger;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

final class PolymrBootstrapServer {
	private static final Logger LOGGER = Logger.getLogger(PolymrBootstrapServer.class);

	private PolymrBootstrapServer() {}

	static PolymrBootstrapConfig.AppConfig collect(String port, String listenScope, boolean headless) {
		CountDownLatch completion = new CountDownLatch(1);
		AppConfigHolder holder = new AppConfigHolder();
		HttpServer server = createServer(port, listenScope, headless, completion, holder);
		server.start();
		String host = "local".equals(PolymrBootstrapConfig.normalizeListenScope(listenScope)) ? "127.0.0.1" : "localhost";
		String url = "http://" + host + ":" + port + "/";
		if (headless) {
			System.out.println("Configuration required, please open " + url);
		}
		else {
			PolymrDesktopSupport.openBrowser(url);
		}
		awaitCompletion(completion);
		server.stop(0);
		return holder.config();
	}

	private static HttpServer createServer(String port, String listenScope, boolean headless, CountDownLatch completion, AppConfigHolder holder) {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress(PolymrBootstrapConfig.hostForListenScope(listenScope), Integer.parseInt(port)), 0);
			server.setExecutor(Executors.newCachedThreadPool());
			server.createContext("/", new FormHandler(headless));
			server.createContext("/submit", new SubmitHandler(completion, holder));
			return server;
		}
		catch (IOException e) {
			throw new IllegalStateException("Could not start Polymr bootstrap server on port " + port, e);
		}
	}

	private static void awaitCompletion(CountDownLatch completion) {
		try {
			completion.await();
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while waiting for Polymr setup completion", e);
		}
	}

	private static final class FormHandler implements HttpHandler {
		private final boolean headless;

		private FormHandler(boolean headless) {
			this.headless = headless;
		}

		@Override
		public void handle(HttpExchange exchange) throws IOException {
			try {
				Properties properties = PolymrBootstrapConfig.load();
				boolean embeddedVariant = PolymrBootstrapConfig.isEmbeddedVariant(properties);
				boolean sqliteVariant = PolymrBootstrapConfig.isSqliteVariant(properties);
				boolean hsqldbVariant = PolymrBootstrapConfig.isHsqldbVariant(properties);
				boolean sshEnabled = Boolean.parseBoolean(properties.getProperty("polymr.bootstrap.ssh.enabled", "false"));
				boolean databasePasswordConfigured = PolymrBootstrapConfig.hasDatabasePassword(properties);
				String selectedListenScope = defaultListenScope(properties, headless);
				String response = htmlPage("Polymr setup", """
						<form method=\"post\" action=\"/submit\" class=\"setup-form\">
							%s
							<label>Who can connect<select name=\"listenScope\"><option value=\"local\" %s>Local only</option><option value=\"network\" %s>Network</option></select></label>
							<p class=\"field-help\">Local only means only this computer can connect. Network means other devices on your network can connect too.</p>
							<button type=\"submit\">Save and start Polymr</button>
						</form>
						<script>
							const sshEnabled = document.getElementById('sshEnabled')
							const sshFields = document.getElementById('sshFields')
							if (sshEnabled && sshFields) {
								const toggleSsh = () => {
									sshFields.style.display = sshEnabled.checked ? 'block' : 'none'
								}
								sshEnabled.addEventListener('change', toggleSsh)
								toggleSsh()
							}
						</script>
					""".formatted(
						embeddedVariant
							? "<p class=\"field-help\">This build uses the embedded DuckDB database. No external database configuration is required.</p>"
							: sqliteVariant
								? "<p class=\"field-help\">This build uses the embedded SQLite database. No external database configuration is required.</p>"
								: hsqldbVariant
									? "<p class=\"field-help\">This build uses the embedded HSQLDB database. No external database configuration is required.</p>"
									: """
								<div class=\"database-fields\">
									<label>PostgreSQL URL<input name=\"dbUrl\" value=\"%s\"></label>
									<label>PostgreSQL user<input name=\"dbUser\" value=\"%s\"></label>
									<label>PostgreSQL password<input type=\"password\" name=\"dbPassword\" placeholder=\"%s\" %s></label>
									<label class=\"checkbox-row\"><input type=\"checkbox\" id=\"sshEnabled\" name=\"sshEnabled\" %s> Use SSH tunnel</label>
									<div id=\"sshFields\" class=\"ssh-fields\" %s>
										<label>SSH server<input name=\"sshServer\" value=\"%s\"></label>
										<label>SSH port<input name=\"sshPort\" value=\"%s\"></label>
										<label>SSH username<input name=\"sshUsername\" value=\"%s\"></label>
										<label>SSH password<input type=\"password\" name=\"sshPassword\"></label>
										<label>SSH key path<input name=\"sshKeyPath\" value=\"%s\"></label>
									</div>
								</div>
							""".formatted(
								escapeHtml(properties.getProperty("polymr.bootstrap.datasource.url", "jdbc:postgresql://localhost:5432/postgres?sslmode=disable")),
								escapeHtml(properties.getProperty("polymr.bootstrap.datasource.username", "polymr")),
								databasePasswordConfigured ? "Password configured" : "",
								databasePasswordConfigured ? "" : "required",
								sshEnabled ? "checked" : "",
								sshEnabled ? "" : "style=\"display:none\"",
								escapeHtml(properties.getProperty("polymr.bootstrap.ssh.server", "")),
								escapeHtml(properties.getProperty("polymr.bootstrap.ssh.port", "22")),
								escapeHtml(properties.getProperty("polymr.bootstrap.ssh.username", "")),
								escapeHtml(properties.getProperty("polymr.bootstrap.ssh.key-path", ""))
							),
						"local".equalsIgnoreCase(selectedListenScope) ? "selected" : "",
						"network".equalsIgnoreCase(selectedListenScope) ? "selected" : ""
					));
				respond(exchange, 200, response);
			}
			catch (Exception e) {
				LOGGER.error("Failed to render Polymr bootstrap form", e);
				e.printStackTrace(System.err);
				respondServerError(exchange);
			}
		}
	}

	private static final class SubmitHandler implements HttpHandler {
		private final CountDownLatch completion;
		private final AppConfigHolder holder;

		private SubmitHandler(CountDownLatch completion, AppConfigHolder holder) {
			this.completion = completion;
			this.holder = holder;
		}

		@Override
		public void handle(HttpExchange exchange) throws IOException {
			try {
				Map<String, String> form = parseForm(exchange);
				Properties properties = PolymrBootstrapConfig.load();
				boolean useEmbeddedDatabase = PolymrBootstrapConfig.isEmbeddedVariant(properties)
					|| PolymrBootstrapConfig.isSqliteVariant(properties)
					|| PolymrBootstrapConfig.isHsqldbVariant(properties);
				boolean sshEnabled = !useEmbeddedDatabase && form.containsKey("sshEnabled");
				PolymrBootstrapConfig.SshTunnelConfig sshTunnel = sshEnabled
					? new PolymrBootstrapConfig.SshTunnelConfig(
						true,
						requireValue(form, "sshServer"),
						parsePort(form.get("sshPort")),
						requireValue(form, "sshUsername"),
						nullIfBlank(form.get("sshPassword")),
						nullIfBlank(form.get("sshKeyPath")),
						null
					)
					: null;
				String databaseUrl = useEmbeddedDatabase ? null : requireValue(form, "dbUrl");
				String databaseUser = useEmbeddedDatabase ? null : requireValue(form, "dbUser");
				String databasePassword = useEmbeddedDatabase ? null : requiredPassword(form.get("dbPassword"), properties);
				holder.set(
					new PolymrBootstrapConfig.AppConfig(
						useEmbeddedDatabase,
						databaseUrl,
						databaseUser,
						databasePassword,
						requireListenScope(form.get("listenScope")),
						sshTunnel
					)
				);
				String response = htmlPage("Starting Polymr", """
					<div class="loading-state">
						<div class="spinner" aria-hidden="true"></div>
						<p class="loading-title">Configuration saved. Starting Polymr...</p>
						<p id="loadingStatus">Waiting for the application to come online.</p>
					</div>
					<script>
						const loadingStatus = document.getElementById('loadingStatus')
						const pollUntilReady = async() => {
							try {
								const response = await fetch('/', {
									cache: 'no-store',
									headers: { 'X-Polymr-Bootstrap-Poll': '1' }
								})
								if (response.ok) {
									loadingStatus.textContent = 'Polymr is ready. Opening the application...'
									window.location.assign('/')
									return
								}
							}
							catch (error) {
								console.debug('Polymr is still starting', error)
							}
							setTimeout(pollUntilReady, 1000)
						}
						pollUntilReady()
					</script>
					""");
				respond(exchange, 200, response);
				completion.countDown();
			}
			catch (Exception e) {
				LOGGER.error("Failed to submit Polymr bootstrap configuration", e);
				e.printStackTrace(System.err);
				respondServerError(exchange);
			}
		}
	}

	private static Map<String, String> parseForm(HttpExchange exchange) throws IOException {
		String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		Map<String, String> form = new HashMap<>();
		for (String pair : body.split("&")) {
			if (pair.isBlank()) {
				continue;
			}
			String[] parts = pair.split("=", 2);
			String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
			String value = parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
			form.put(key, value);
		}
		return form;
	}

	private static int parsePort(String portText) {
		if (portText == null || portText.isBlank()) {
			return 22;
		}
		return Integer.parseInt(portText.trim());
	}

	private static String nullIfBlank(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	private static String requireValue(Map<String, String> form, String key) {
		String value = form.get(key);
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("Missing required form field: " + key);
		}
		return value;
	}

	private static String requiredPassword(String value, Properties properties) {
		if (value != null && !value.isBlank()) {
			return value;
		}
		if (PolymrBootstrapConfig.hasDatabasePassword(properties)) {
			return PolymrBootstrapConfig.buildRuntimeConfig(properties, null).databasePassword();
		}
		throw new IllegalArgumentException("Missing required form field: dbPassword");
	}

	private static void respond(HttpExchange exchange, int status, String body) throws IOException {
		exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream outputStream = exchange.getResponseBody()) {
			outputStream.write(bytes);
		}
	}

	private static void respondServerError(HttpExchange exchange) throws IOException {
		respond(
			exchange,
			500,
			htmlPage("Polymr setup error", "<p>Bootstrap failed. Check the application log for details.</p>")
		);
	}

	private static String htmlPage(String title, String body) {
		return """
			<!doctype html>
			<html>
			<head>
				<meta charset=\"utf-8\">
				<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">
				<title>%s</title>
				<style>
					body { font-family: Inter, system-ui, sans-serif; background: #0d131b; color: #e5edf7; margin: 0; padding: 2rem; }
					main { max-width: 42rem; margin: 4rem auto; background: #1a2230; padding: 2rem; border-radius: 0.75rem; border: 1px solid #263244; box-shadow: 0 18px 50px rgba(0, 0, 0, 0.35); }
					h1 { margin-top: 0; margin-bottom: 1.5rem; font-size: 1.75rem; }
					p { color: #b7c3d4; line-height: 1.5; }
					.loading-state { display: flex; flex-direction: column; align-items: center; text-align: center; gap: 1rem; padding: 1rem 0; }
					.loading-title { margin: 0; font-size: 1.05rem; color: #e5edf7; }
					.spinner { width: 2.5rem; height: 2.5rem; border-radius: 50%%; border: 0.3rem solid #2d3a4f; border-top-color: #8ab4ff; animation: spin 0.9s linear infinite; }
					@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
					label { display: block; margin-bottom: 1rem; color: #cdd7e5; font-size: 0.95rem; }
					.field-help { margin-top: -0.5rem; margin-bottom: 1rem; font-size: 0.9rem; color: #9fb0c6; }
					input, select { width: 100%%; margin-top: 0.5rem; padding: 0.75rem 0.85rem; border-radius: 0.5rem; border: 1px solid #2d3a4f; background: #111924; color: inherit; box-sizing: border-box; }
					input:focus, select:focus { outline: none; border-color: #5d7290; box-shadow: 0 0 0 3px rgba(93, 114, 144, 0.22); }
					button { padding: 0.75rem 1rem; border: 0; border-radius: 0.5rem; background: #2f455d; color: white; cursor: pointer; font-weight: 600; }
					button:hover { background: #3a5470; }
					.checkbox-row { display: flex; align-items: center; gap: 0.75rem; }
					.checkbox-row input { width: auto; margin: 0; }
					.database-fields, .ssh-fields { margin: 1rem 0 1.5rem; padding: 1rem; border: 1px solid #2d3a4f; border-radius: 0.75rem; background: #131c27; }
				</style>
			</head>
			<body>
				<main>
					<h1>%s</h1>
					%s
				</main>
			</body>
			</html>
		""".formatted(escapeHtml(title), escapeHtml(title), body);
	}

	private static String escapeHtml(String value) {
		return value.replace("&", "&amp;")
			.replace("<", "&lt;")
			.replace(">", "&gt;")
			.replace("\"", "&quot;");
	}

	private static String defaultListenScope(Properties properties, boolean headless) {
		String configured = properties.getProperty("polymr.http.listen-scope");
		if ("local".equalsIgnoreCase(configured) || "network".equalsIgnoreCase(configured)) {
			return configured.toLowerCase();
		}
		return headless ? "network" : "local";
	}

	private static String requireListenScope(String listenScope) {
		if ("local".equalsIgnoreCase(listenScope)) {
			return "local";
		}
		if ("network".equalsIgnoreCase(listenScope)) {
			return "network";
		}
		throw new IllegalArgumentException("Invalid listen scope: " + listenScope);
	}

	private static final class AppConfigHolder {
		private PolymrBootstrapConfig.AppConfig config;

		private void set(PolymrBootstrapConfig.AppConfig config) {
			this.config = config;
		}

		private PolymrBootstrapConfig.AppConfig config() {
			if (config == null) {
				throw new IllegalStateException("Polymr setup finished without app config");
			}
			return config;
		}
	}
}
