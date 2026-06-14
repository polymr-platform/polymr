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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;

@QuarkusMain
public final class PolymrLauncher {
	private PolymrLauncher() {}

	public static void main(String[] args) {
		LaunchArguments launchArguments = parseArguments(args);
		if (launchArguments.showHelp()) {
			printHelp();
			return;
		}
		boolean forceBootstrap = launchArguments.forceBootstrap();
		String port = launchArguments.port();
		if (port == null || port.isBlank()) {
			port = System.getenv("POLYMR_HTTP_PORT");
		}
		String listenScope = launchArguments.listenScope();
		if (listenScope == null || listenScope.isBlank()) {
			listenScope = System.getenv("POLYMR_HTTP_LISTEN_SCOPE");
		}
		String fixedSecret = resolveFixedSecret(launchArguments);
		String fixedIdentity = resolveFixedIdentity(launchArguments);
		boolean devProfile = isDevProfile();
		String effectivePort = port != null && !port.isBlank() ? port : devProfile ? "5050" : "6655";
		boolean bootstrappedThisRun = false;
		if (!devProfile) {
			Properties properties = PolymrBootstrapConfig.load();
			PolymrBootstrapConfig.ensureServerIdentity(properties, fixedIdentity);
			properties = PolymrBootstrapConfig.load();
			PolymrBootstrapConfig.ensureSecretsKey(properties, fixedSecret);
			if (forceBootstrap || !PolymrBootstrapConfig.hasDatabaseConfig(properties)) {
				PolymrBootstrapConfig.AppConfig appConfig = PolymrBootstrapServer.collect(effectivePort, listenScope, launchArguments.headless());
				PolymrBootstrapConfig.store(appConfig, effectivePort);
				properties = PolymrBootstrapConfig.load();
				bootstrappedThisRun = true;
			}
			if (listenScope != null && !listenScope.isBlank()) {
				System.setProperty("polymr.http.listen-scope", listenScope);
			}
			PolymrBootstrapConfig.RuntimeConfig runtimeConfig = PolymrBootstrapConfig.buildRuntimeConfig(properties, effectivePort);
			PolymrBootstrapConfig.applySystemProperties(runtimeConfig);
			PolymrLauncherRuntime.startSshTunnelIfNeeded(runtimeConfig);
		}
		System.setProperty(
			"polymr.launcher.desktop-enabled",
			Boolean.toString(!launchArguments.headless() && !bootstrappedThisRun)
		);
		if (devProfile && listenScope != null && !listenScope.isBlank()) {
			System.setProperty("polymr.http.listen-scope", listenScope);
		}
		Quarkus.run(launchArguments.forwardedArgs().toArray(String[]::new));
	}

	private static boolean isDevProfile() {
		// Properties properties = System.getProperties();
		// Enumeration<Object> keys = properties.keys();
		// while (keys.hasMoreElements()) {
		// Object nextElement = keys.nextElement();
		// System.out.println(nextElement + " = " + properties.getProperty(nextElement.toString()));
		// }
		// System.out.println("--------------");
		String launchMode = System.getProperty("quarkus.launch.mode");
		if (launchMode != null && !launchMode.isBlank()) {
			return "dev".equalsIgnoreCase(launchMode) || "test".equalsIgnoreCase(launchMode);
		}
		String vertx = System.getProperty("vertxweb.environment");
		if (vertx != null && !vertx.isBlank()) {
			return "dev".equalsIgnoreCase(vertx) || "test".equalsIgnoreCase(vertx);
		}
		String profile = System.getProperty("quarkus.profile");
		if (profile == null || profile.isBlank()) {
			profile = System.getenv("QUARKUS_PROFILE");
		}
		if (profile == null || profile.isBlank()) {
			return false;
		}
		return "dev".equalsIgnoreCase(profile) || "test".equalsIgnoreCase(profile);
	}

	private static LaunchArguments parseArguments(String[] args) {
		String port = null;
		boolean showHelp = false;
		boolean headless = false;
		boolean forceBootstrap = false;
		String listenScope = null;
		String secret = null;
		String secretFile = null;
		String identity = null;
		List<String> forwardedArgs = new ArrayList<>();
		for (int i = 0; i < args.length; i++) {
			String arg = args[i];
			if ("--help".equals(arg) || "-h".equals(arg)) {
				showHelp = true;
				continue;
			}
			if ("--headless".equals(arg)) {
				headless = true;
				continue;
			}
			if ("--config".equals(arg)) {
				forceBootstrap = true;
				continue;
			}
			if (arg.startsWith("--port=")) {
				port = arg.substring("--port=".length());
				continue;
			}
			if (arg.startsWith("--listen-scope=")) {
				listenScope = arg.substring("--listen-scope=".length());
				continue;
			}
			if (arg.startsWith("--secret=")) {
				secret = arg.substring("--secret=".length());
				continue;
			}
			if (arg.startsWith("--secret-file=")) {
				secretFile = arg.substring("--secret-file=".length());
				continue;
			}
			if (arg.startsWith("--identity=")) {
				identity = arg.substring("--identity=".length());
				continue;
			}
			if ("--port".equals(arg)) {
				if (i + 1 >= args.length) {
					throw new IllegalArgumentException("Missing value for --port");
				}
				port = args[++i];
				continue;
			}
			if ("--listen-scope".equals(arg)) {
				if (i + 1 >= args.length) {
					throw new IllegalArgumentException("Missing value for --listen-scope");
				}
				listenScope = args[++i];
				continue;
			}
			if ("--secret".equals(arg)) {
				if (i + 1 >= args.length) {
					throw new IllegalArgumentException("Missing value for --secret");
				}
				secret = args[++i];
				continue;
			}
			if ("--secret-file".equals(arg)) {
				if (i + 1 >= args.length) {
					throw new IllegalArgumentException("Missing value for --secret-file");
				}
				secretFile = args[++i];
				continue;
			}
			if ("--identity".equals(arg)) {
				if (i + 1 >= args.length) {
					throw new IllegalArgumentException("Missing value for --identity");
				}
				identity = args[++i];
				continue;
			}
			forwardedArgs.add(arg);
		}
		return new LaunchArguments(port, listenScope, showHelp, headless, forceBootstrap, secret, secretFile, identity, forwardedArgs);
	}

	private static String resolveFixedSecret(LaunchArguments launchArguments) {
		if (launchArguments.secret() != null) {
			if (launchArguments.secret().isBlank()) {
				throw new IllegalArgumentException("Missing value for --secret");
			}
			return launchArguments.secret().trim();
		}
		if (launchArguments.secretFile() != null) {
			if (launchArguments.secretFile().isBlank()) {
				throw new IllegalArgumentException("Missing value for --secret-file");
			}
			return PolymrBootstrapConfig.readSecretFile(Path.of(launchArguments.secretFile().trim()));
		}
		return null;
	}

	private static String resolveFixedIdentity(LaunchArguments launchArguments) {
		if (launchArguments.identity() == null) {
			return null;
		}
		if (launchArguments.identity().isBlank()) {
			throw new IllegalArgumentException("Missing value for --identity");
		}
		return launchArguments.identity().trim();
	}

	private static void printHelp() {
		System.out.println("Polymr launcher options:");
		System.out.println("  --port <value>           Override the Polymr HTTP port (default: 6655)");
		System.out.println("  --listen-scope <value>   Set HTTP binding: local or network (default: network)");
		System.out.println("  --headless               Do not load desktop integration or open a browser");
		System.out.println("  --config                 Force the bootstrap setup server before startup");
		System.out.println("  --secret <value>         Use a fixed Polymr secret instead of autogenerating one");
		System.out.println("  --secret-file <path>     Read the fixed Polymr secret from a file");
		System.out.println("  --identity <value>       Use a fixed Polymr server identity instead of deriving one");
		System.out.println("  --help                   Show this help message");
	}

	private record LaunchArguments(
			String port,
			String listenScope,
			boolean showHelp,
			boolean headless,
			boolean forceBootstrap,
			String secret,
			String secretFile,
			String identity,
			List<String> forwardedArgs) {}
}
