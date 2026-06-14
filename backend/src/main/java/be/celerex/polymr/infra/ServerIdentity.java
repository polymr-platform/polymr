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

package be.celerex.polymr.infra;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ServerIdentity {
	private static final Logger LOGGER = Logger.getLogger(ServerIdentity.class);
	private final String serverId;

	@Inject
	public ServerIdentity(@ConfigProperty(name = "polymr.server-id") Optional<String> configuredServerId) {
		String configured = configuredServerId.orElse(null);
		if (configured == null || configured.isBlank()) {
			String derived = deriveServerId();
			serverId = derived == null ? "polymr" : derived;
			LOGGER.warnf("polymr.server-id not set; using derived id %s", serverId);
		}
		else {
			serverId = configured.trim();
		}
	}

	public String id() {
		return serverId;
	}

	private String deriveServerId() {
		String ip = findNonLoopbackIp();
		if (ip != null && !ip.isBlank()) {
			return "polymr-" + ip;
		}
		return null;
	}

	private String findNonLoopbackIp() {
		try {
			Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
			while (interfaces != null && interfaces.hasMoreElements()) {
				NetworkInterface iface = interfaces.nextElement();
				if (!iface.isUp() || iface.isLoopback() || iface.isVirtual()) {
					continue;
				}
				Enumeration<InetAddress> addresses = iface.getInetAddresses();
				while (addresses.hasMoreElements()) {
					InetAddress address = addresses.nextElement();
					if (address.isLoopbackAddress() || address.isLinkLocalAddress()) {
						continue;
					}
					String host = address.getHostAddress();
					if (host != null && !host.contains(":")) {
						return host;
					}
				}
			}
		}
		catch (SocketException ignored) {}
		try {
			InetAddress local = InetAddress.getLocalHost();
			if (local != null && !local.isLoopbackAddress() && !local.isLinkLocalAddress()) {
				String host = local.getHostAddress();
				if (host != null && !host.contains(":")) {
					return host;
				}
			}
		}
		catch (Exception ignored) {}
		return null;
	}
}
