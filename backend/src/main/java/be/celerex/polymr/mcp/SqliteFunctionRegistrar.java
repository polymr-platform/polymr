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

package be.celerex.polymr.mcp;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jboss.logging.Logger;
import org.sqlite.Function;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class SqliteFunctionRegistrar {
	private static final Logger LOGGER = Logger.getLogger(SqliteFunctionRegistrar.class);
	@Inject
	@ConfigProperty(name = "quarkus.datasource.db-kind")
	String dbKind;

	@Inject
	@ConfigProperty(name = "quarkus.datasource.jdbc.driver")
	Optional<String> jdbcDriver;

	@Inject
	@ConfigProperty(name = "quarkus.datasource.jdbc.url")
	String jdbcUrl;

	void onStartup(@Observes StartupEvent event) {
		if (!isSqlite()) {
			return;
		}
		try (Connection connection = DriverManager.getConnection(jdbcUrl)) {
			Function.create(
				connection,
				"regexp_like",
				new Function() {
					@Override
					protected void xFunc() {
						try {
							String input = value_text(0);
							String pattern = value_text(1);
							String flags = args() > 2 ? value_text(2) : null;
							result(matches(input, pattern, flags) ? 1 : 0);
						}
						catch (Exception e) {
							throw new RuntimeException();
						}
					}
				}
			);
			Function.create(
				connection,
				"regexp_count",
				new Function() {
					@Override
					protected void xFunc() {
						try {
							String input = value_text(0);
							String pattern = value_text(1);
							String flags = args() > 2 ? value_text(2) : null;
							result(count(input, pattern, flags));
						}
						catch (Exception e) {
							throw new RuntimeException();
						}
					}
				}
			);
		}
		catch (Exception e) {
			LOGGER.error("Failed to register SQLite regex functions", e);
			throw new IllegalStateException("Could not register SQLite regex functions", e);
		}
	}

	private boolean isSqlite() {
		String normalizedKind = dbKind == null ? "" : dbKind.toLowerCase(Locale.ROOT);
		String normalizedDriver = jdbcDriver.orElse("").toLowerCase(Locale.ROOT);
		return normalizedKind.contains("sqlite")
			|| "other".equals(normalizedKind) && normalizedDriver.contains("sqlite");
	}

	private static boolean matches(String input, String pattern, String flags) {
		if (input == null || pattern == null) {
			return false;
		}
		return compile(pattern, flags).matcher(input).find();
	}

	private static int count(String input, String pattern, String flags) {
		if (input == null || pattern == null) {
			return 0;
		}
		Matcher matcher = compile(pattern, flags).matcher(input);
		int count = 0;
		while (matcher.find()) {
			count++;
			if (matcher.start() == matcher.end()) {
				if (matcher.end() >= input.length()) {
					break;
				}
				matcher.region(matcher.end() + 1, input.length());
			}
		}
		return count;
	}

	private static Pattern compile(String pattern, String flags) {
		int options = 0;
		if (flags != null && flags.toLowerCase(Locale.ROOT).contains("i")) {
			options |= Pattern.CASE_INSENSITIVE;
		}
		return Pattern.compile(pattern, options);
	}
}
