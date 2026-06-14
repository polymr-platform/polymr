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

import org.eclipse.microprofile.config.inject.ConfigProperty;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

@ApplicationScoped
public class PolymrDesktopStartup {
	@ConfigProperty(name = "polymr.launcher.desktop-enabled", defaultValue = "false")
	boolean desktopEnabled;

	@ConfigProperty(name = "quarkus.http.port", defaultValue = "6655")
	String port;

	void onStart(@Observes StartupEvent event) {
		if (!desktopEnabled || LaunchMode.current().isDevOrTest()) {
			return;
		}
		PolymrDesktopSupport.start(port);
	}
}
