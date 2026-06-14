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

import be.celerex.polymr.net.SshTunnelSupport;
import be.celerex.polymr.net.SshTunnelSupport.ManagedSshTunnel;
import be.celerex.polymr.net.SshTunnelSupport.SshTunnelConfig;

final class PolymrLauncherRuntime {
	private PolymrLauncherRuntime() {}

	static ManagedSshTunnel startSshTunnelIfNeeded(PolymrBootstrapConfig.RuntimeConfig config) {
		PolymrBootstrapConfig.RuntimeSshConfig ssh = config.sshTunnel();
		if (ssh == null || !ssh.enabled()) {
			return null;
		}
		if (config.useEmbeddedDatabase()) {
			return null;
		}
		try {
			ManagedSshTunnel tunnel = SshTunnelSupport.startTunnel(
				new SshTunnelConfig(true, ssh.server(), ssh.port(), ssh.username(), ssh.password(), ssh.keyPath(), ssh.key()),
				config.databaseUrl(),
				"polymr-db-ssh-tunnel"
			);
			System.setProperty("quarkus.datasource.jdbc.url", tunnel.forwardedUrl());
			return tunnel;
		}
		catch (Exception e) {
			throw new IllegalStateException("Could not start SSH tunnel for database connection", e);
		}
	}
}
