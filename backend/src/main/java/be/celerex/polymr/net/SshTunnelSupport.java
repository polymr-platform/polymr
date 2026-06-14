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

package be.celerex.polymr.net;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder;
import net.schmizz.sshj.connection.channel.direct.Parameters;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;

public final class SshTunnelSupport {
	private SshTunnelSupport() {}

	public static ManagedSshTunnel startTunnel(SshTunnelConfig sshConfig, String targetUrl, String threadName) throws Exception {
		if (sshConfig == null || !sshConfig.enabled()) {
			return null;
		}
		if (sshConfig.server() == null || sshConfig.server().isBlank()) {
			throw new IllegalStateException("SSH server is required");
		}
		if (sshConfig.username() == null || sshConfig.username().isBlank()) {
			throw new IllegalStateException("SSH username is required");
		}
		URI uri = URI.create(targetUrl);
		String targetHost = uri.getHost();
		int targetPort = uri.getPort();
		if (targetHost == null || targetHost.isBlank()) {
			throw new IllegalStateException("Target host is required for SSH tunneling");
		}
		if (targetPort <= 0) {
			targetPort = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
		}
		SSHClient client = new SSHClient();
		client.addHostKeyVerifier(new PromiscuousVerifier());
		client.connect(sshConfig.server(), sshConfig.port());
		boolean authenticated = false;
		try {
			if (sshConfig.key() != null && !sshConfig.key().isBlank()) {
				File tempKey = File.createTempFile("polymr-ssh-", ".key");
				Files.writeString(tempKey.toPath(), sshConfig.key(), StandardCharsets.UTF_8);
				tempKey.deleteOnExit();
				KeyProvider provider = sshConfig.password() != null && !sshConfig.password().isBlank()
					? client.loadKeys(tempKey.getAbsolutePath(), sshConfig.password())
					: client.loadKeys(tempKey.getAbsolutePath());
				client.authPublickey(sshConfig.username(), provider);
				authenticated = true;
			}
			else if (sshConfig.keyPath() != null && !sshConfig.keyPath().isBlank()) {
				KeyProvider provider = sshConfig.password() != null && !sshConfig.password().isBlank()
					? client.loadKeys(sshConfig.keyPath(), sshConfig.password())
					: client.loadKeys(sshConfig.keyPath());
				client.authPublickey(sshConfig.username(), provider);
				authenticated = true;
			}
			else if (sshConfig.password() != null && !sshConfig.password().isBlank()) {
				client.authPassword(sshConfig.username(), sshConfig.password());
				authenticated = true;
			}
			if (!authenticated) {
				throw new IllegalStateException("SSH password, key path, or key is required");
			}
			ServerSocket serverSocket = new ServerSocket();
			serverSocket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
			int localPort = serverSocket.getLocalPort();
			InetSocketAddress localAddress = new InetSocketAddress(InetAddress.getLoopbackAddress(), localPort);
			Parameters parameters = new Parameters(localAddress.getHostString(), localPort, targetHost, targetPort);
			LocalPortForwarder forwarder = client.newLocalPortForwarder(parameters, serverSocket);
			Thread thread = new Thread(
				() -> {
					try {
						forwarder.listen();
					}
					catch (IOException e) {
						System.err.println("SSH tunnel listener stopped: " + e.getMessage());
					}
				},
				threadName
			);
			thread.setDaemon(true);
			thread.start();
			String forwardedUrl = applyHostAndPort(targetUrl, localAddress.getHostString(), localPort);
			return new ManagedSshTunnel(client, forwarder, serverSocket, forwardedUrl);
		}
		catch (Exception ex) {
			client.close();
			throw ex;
		}
	}

	public static String applyHostAndPort(String targetUrl, String host, int port) {
		try {
			URI uri = URI.create(targetUrl);
			return new URI(uri.getScheme(), uri.getUserInfo(), host, port, uri.getPath(), uri.getQuery(), uri.getFragment())
				.toString();
		}
		catch (Exception ex) {
			return targetUrl;
		}
	}

	public record SshTunnelConfig(
			boolean enabled,
			String server,
			int port,
			String username,
			String password,
			String keyPath,
			String key) {}

	public record ManagedSshTunnel(SSHClient client, LocalPortForwarder forwarder, ServerSocket serverSocket, String forwardedUrl) {
		public void close() {
			try {
				if (forwarder != null) {
					forwarder.close();
				}
			}
			catch (IOException e) {
				System.err.println("Failed to close SSH forwarder: " + e.getMessage());
			}
			try {
				if (serverSocket != null) {
					serverSocket.close();
				}
			}
			catch (IOException e) {
				System.err.println("Failed to close SSH server socket: " + e.getMessage());
			}
			try {
				if (client != null) {
					client.close();
				}
			}
			catch (IOException e) {
				System.err.println("Failed to close SSH client: " + e.getMessage());
			}
		}
	}
}
