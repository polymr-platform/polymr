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

import java.io.Console;
import java.util.Scanner;

final class PolymrBootstrapCli {
	private PolymrBootstrapCli() {}

	static PolymrBootstrapConfig.AppConfig collect() {
		java.util.Properties properties = PolymrBootstrapConfig.load();
		boolean embeddedVariant = PolymrBootstrapConfig.isEmbeddedVariant(properties)
			|| PolymrBootstrapConfig.isSqliteVariant(properties)
			|| PolymrBootstrapConfig.isHsqldbVariant(properties);
		Console console = System.console();
		if (console != null) {
			boolean useEmbeddedDatabase = embeddedVariant;
			if (!embeddedVariant) {
				useEmbeddedDatabase = readYesNo(console.readLine("Use embedded database? [Y/n]: "), true);
			}
			String url = null;
			String username = null;
			String password = null;
			if (!useEmbeddedDatabase) {
				url = console.readLine("Database URL [%s]: ", "jdbc:postgresql://localhost:5432/postgres?sslmode=disable");
				if (url == null || url.isBlank()) {
					url = "jdbc:postgresql://localhost:5432/postgres?sslmode=disable";
				}
				username = console.readLine("Database user [%s]: ", "polymr");
				if (username == null || username.isBlank()) {
					username = "polymr";
				}
				char[] passwordChars = console.readPassword("Database password: ");
				if (passwordChars == null || passwordChars.length == 0) {
					throw new IllegalArgumentException("Database password is required");
				}
				password = new String(passwordChars);
			}
			String listenScope = readListenScope(console.readLine("Listen scope [network/local] [network]: "));
			boolean sshEnabled = !useEmbeddedDatabase && "y".equalsIgnoreCase(console.readLine("Use SSH tunnel? [y/N]: "));
			PolymrBootstrapConfig.SshTunnelConfig sshTunnel = sshEnabled ? readSsh(console) : null;
			return new PolymrBootstrapConfig.AppConfig(useEmbeddedDatabase, url, username, password, listenScope, sshTunnel);
		}
		Scanner scanner = new Scanner(System.in);
		boolean useEmbeddedDatabase = embeddedVariant;
		if (!embeddedVariant) {
			System.out.print("Use embedded database? [Y/n]: ");
			useEmbeddedDatabase = readYesNo(scanner.nextLine(), true);
		}
		String url = null;
		String username = null;
		String password = null;
		if (!useEmbeddedDatabase) {
			System.out.print("Database URL [jdbc:postgresql://localhost:5432/postgres?sslmode=disable]: ");
			url = scanner.nextLine();
			if (url == null || url.isBlank()) {
				url = "jdbc:postgresql://localhost:5432/postgres?sslmode=disable";
			}
			System.out.print("Database user [polymr]: ");
			username = scanner.nextLine();
			if (username == null || username.isBlank()) {
				username = "polymr";
			}
			System.out.print("Database password: ");
			password = scanner.nextLine();
			if (password.isBlank()) {
				throw new IllegalArgumentException("Database password is required");
			}
		}
		System.out.print("Listen scope [network/local] [network]: ");
		String listenScope = readListenScope(scanner.nextLine());
		boolean sshEnabled = false;
		if (!useEmbeddedDatabase) {
			System.out.print("Use SSH tunnel? [y/N]: ");
			sshEnabled = "y".equalsIgnoreCase(scanner.nextLine());
		}
		PolymrBootstrapConfig.SshTunnelConfig sshTunnel = sshEnabled ? readSsh(scanner) : null;
		return new PolymrBootstrapConfig.AppConfig(useEmbeddedDatabase, url, username, password, listenScope, sshTunnel);
	}

	private static boolean readYesNo(String value, boolean defaultValue) {
		if (value == null || value.isBlank()) {
			return defaultValue;
		}
		if ("y".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value)) {
			return true;
		}
		if ("n".equalsIgnoreCase(value) || "no".equalsIgnoreCase(value)) {
			return false;
		}
		throw new IllegalArgumentException("Invalid yes/no value: " + value);
	}

	private static String readListenScope(String value) {
		if (value == null || value.isBlank() || "network".equalsIgnoreCase(value)) {
			return "network";
		}
		if ("local".equalsIgnoreCase(value)) {
			return "local";
		}
		throw new IllegalArgumentException("Invalid listen scope: " + value);
	}

	private static PolymrBootstrapConfig.SshTunnelConfig readSsh(Console console) {
		String server = console.readLine("SSH server: ");
		String username = console.readLine("SSH username: ");
		String portText = console.readLine("SSH port [22]: ");
		int port = portText == null || portText.isBlank() ? 22 : Integer.parseInt(portText);
		char[] password = console.readPassword("SSH password (leave blank to use key): ");
		String keyPath = console.readLine("SSH key path (optional): ");
		String key = null;
		return new PolymrBootstrapConfig.SshTunnelConfig(true, server, port, username, password == null ? null : new String(password), keyPath, key);
	}

	private static PolymrBootstrapConfig.SshTunnelConfig readSsh(Scanner scanner) {
		System.out.print("SSH server: ");
		String server = scanner.nextLine();
		System.out.print("SSH username: ");
		String username = scanner.nextLine();
		System.out.print("SSH port [22]: ");
		String portText = scanner.nextLine();
		int port = portText == null || portText.isBlank() ? 22 : Integer.parseInt(portText);
		System.out.print("SSH password (leave blank to use key): ");
		String password = scanner.nextLine();
		System.out.print("SSH key path (optional): ");
		String keyPath = scanner.nextLine();
		return new PolymrBootstrapConfig.SshTunnelConfig(true, server, port, username, password, keyPath, null);
	}
}
