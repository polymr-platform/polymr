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
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Properties;
import javax.crypto.spec.SecretKeySpec;
import be.celerex.polymr.security.SecretCipher;
import be.celerex.polymr.security.SecretCrypto;

final class PolymrBootstrapConfig {
	private static final String CONFIG_FILE = "application.properties";
	private static final String CONFIG_DIRECTORY = ".config/polymr";
	private static final String DATABASE_VARIANT_KEY = "polymr.database.variant";
	private static final String DB_KIND_KEY = "quarkus.datasource.db-kind";
	private static final String DB_URL_KEY = "quarkus.datasource.jdbc.url";
	private static final String DB_DRIVER_KEY = "quarkus.datasource.jdbc.driver";
	private static final String DB_USER_KEY = "quarkus.datasource.username";
	private static final String DB_PASSWORD_KEY = "quarkus.datasource.password";
	private static final String HIBERNATE_DIALECT_KEY = "quarkus.hibernate-orm.dialect";
	private static final String BOOTSTRAP_DB_URL_KEY = "polymr.bootstrap.datasource.url";
	private static final String BOOTSTRAP_DB_USER_KEY = "polymr.bootstrap.datasource.username";
	private static final String EMBEDDED_DATABASE_KEY = "polymr.bootstrap.database.embedded";
	private static final String DB_PASSWORD_ENCRYPTED_KEY = "polymr.bootstrap.datasource.password.ciphertext";
	private static final String DB_PASSWORD_NONCE_KEY = "polymr.bootstrap.datasource.password.nonce";
	private static final String SSH_ENABLED_KEY = "polymr.bootstrap.ssh.enabled";
	private static final String SSH_SERVER_KEY = "polymr.bootstrap.ssh.server";
	private static final String SSH_PORT_KEY = "polymr.bootstrap.ssh.port";
	private static final String SSH_USERNAME_KEY = "polymr.bootstrap.ssh.username";
	private static final String SSH_PASSWORD_CIPHERTEXT_KEY = "polymr.bootstrap.ssh.password.ciphertext";
	private static final String SSH_PASSWORD_NONCE_KEY = "polymr.bootstrap.ssh.password.nonce";
	private static final String SSH_KEY_PATH_KEY = "polymr.bootstrap.ssh.key-path";
	private static final String SSH_KEY_CIPHERTEXT_KEY = "polymr.bootstrap.ssh.key.ciphertext";
	private static final String SSH_KEY_NONCE_KEY = "polymr.bootstrap.ssh.key.nonce";
	private static final String HTTP_PORT_KEY = "quarkus.http.port";
	private static final String HTTP_LISTEN_SCOPE_KEY = "polymr.http.listen-scope";
	private static final String QUARKUS_HTTP_HOST_KEY = "quarkus.http.host";
	private static final String SERVER_ID_KEY = "polymr.server-id";
	private static final String SECRETS_KEY = "polymr.secrets.key";
	private static final String LOG_DIRECTORY = "logs";
	private static final String DUCKDB_FILE_NAME = "polymr.db";
	private static final String SQLITE_FILE_NAME = "polymr.sqlite";
	private static final String HSQLDB_FILE_NAME = "polymr.hsqldb";
	private static final String DUCKDB_DRIVER = "org.duckdb.DuckDBDriver";
	private static final String SQLITE_DRIVER = "org.sqlite.JDBC";
	private static final String HSQLDB_DRIVER = "org.hsqldb.jdbc.JDBCDriver";
	private static final String POSTGRESQL_DRIVER = "org.postgresql.Driver";
	private static final String SQLITE_DIALECT = "org.hibernate.community.dialect.SQLiteDialect";
	private static final String HSQLDB_DIALECT = "org.hibernate.dialect.HSQLDialect";
	private static final String POSTGRESQL_DIALECT = "org.hibernate.dialect.PostgreSQLDialect";
	private static final String VARIANT_DUCKDB = "duckdb";
	private static final String VARIANT_HSQLDB = "hsqldb";
	private static final String VARIANT_POSTGRESQL = "postgresql";
	private static final String VARIANT_SQLITE = "sqlite";

	private PolymrBootstrapConfig() {}

	static Path configDirectory() {
		return Paths.get(System.getProperty("user.home"), CONFIG_DIRECTORY);
	}

	static Path configFile() {
		return configDirectory().resolve(CONFIG_FILE);
	}

	static Path logDirectory() {
		return configDirectory().resolve(LOG_DIRECTORY);
	}

	static Properties load() {
		Properties properties = new Properties();
		Path file = configFile();
		if (!Files.exists(file)) {
			return properties;
		}
		try (InputStream inputStream = Files.newInputStream(file)) {
			properties.load(inputStream);
			return properties;
		}
		catch (IOException e) {
			throw new IllegalStateException("Could not load Polymr config from " + file, e);
		}
	}

	static boolean hasDatabaseConfig(Properties properties) {
		String databaseVariant = databaseVariant(properties);
		if (VARIANT_DUCKDB.equals(databaseVariant)
				|| VARIANT_SQLITE.equals(databaseVariant)
				|| VARIANT_HSQLDB.equals(databaseVariant)) {
			return hasEmbeddedDatabaseChoice(properties) && hasValue(properties.getProperty(HTTP_LISTEN_SCOPE_KEY));
		}
		return hasValue(properties.getProperty(BOOTSTRAP_DB_URL_KEY))
			&& hasValue(properties.getProperty(BOOTSTRAP_DB_USER_KEY))
			&& hasDatabasePassword(properties)
			&& hasValue(properties.getProperty(HTTP_LISTEN_SCOPE_KEY));
	}

	static boolean hasEmbeddedDatabaseChoice(Properties properties) {
		return properties.containsKey(EMBEDDED_DATABASE_KEY);
	}

	static boolean useEmbeddedDatabase(Properties properties) {
		return Boolean.parseBoolean(properties.getProperty(EMBEDDED_DATABASE_KEY, "false"));
	}

	static boolean hasDatabasePassword(Properties properties) {
		return hasValue(properties.getProperty(DB_PASSWORD_KEY))
			|| (hasValue(properties.getProperty(DB_PASSWORD_ENCRYPTED_KEY))
					&& hasValue(properties.getProperty(DB_PASSWORD_NONCE_KEY)));
	}

	static void ensureSecretsKey(Properties properties) {
		ensureSecretsKey(properties, null);
	}

	static void ensureServerIdentity(Properties properties, String fixedIdentity) {
		if (!hasValue(fixedIdentity)) {
			return;
		}
		properties.setProperty(SERVER_ID_KEY, fixedIdentity.trim());
		persist(properties);
	}

	static void ensureSecretsKey(Properties properties, String fixedSecret) {
		if (hasValue(fixedSecret)) {
			properties.setProperty(SECRETS_KEY, fixedSecret.trim());
			persist(properties);
			return;
		}
		if (hasValue(properties.getProperty(SECRETS_KEY))) {
			return;
		}
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		properties.setProperty(SECRETS_KEY, Base64.getEncoder().encodeToString(key));
		persist(properties);
	}

	static void store(AppConfig appConfig, String port) {
		Properties properties = load();
		properties.setProperty(EMBEDDED_DATABASE_KEY, Boolean.toString(appConfig.useEmbeddedDatabase()));
		properties.setProperty(HTTP_LISTEN_SCOPE_KEY, normalizeListenScope(appConfig.listenScope()));
		properties.remove(DB_KIND_KEY);
		properties.remove(DB_DRIVER_KEY);
		properties.remove(HIBERNATE_DIALECT_KEY);
		properties.remove(DB_URL_KEY);
		properties.remove(DB_USER_KEY);
		properties.remove(DB_PASSWORD_KEY);
		String secretsKey = properties.getProperty(SECRETS_KEY);
		SecretKeySpec keySpec = SecretCrypto.initKey(secretsKey);
		if (appConfig.useEmbeddedDatabase()) {
			properties.remove(BOOTSTRAP_DB_URL_KEY);
			properties.remove(BOOTSTRAP_DB_USER_KEY);
			properties.remove(DB_PASSWORD_ENCRYPTED_KEY);
			properties.remove(DB_PASSWORD_NONCE_KEY);
		}
		else {
			properties.setProperty(BOOTSTRAP_DB_URL_KEY, appConfig.url());
			properties.setProperty(BOOTSTRAP_DB_USER_KEY, appConfig.username());
			SecretCipher.EncryptedSecret encrypted = SecretCrypto.encrypt(appConfig.password(), keySpec, new SecureRandom());
			properties.setProperty(DB_PASSWORD_ENCRYPTED_KEY, encrypted.ciphertext());
			properties.setProperty(DB_PASSWORD_NONCE_KEY, encrypted.nonce());
		}
		storeSshConfig(properties, appConfig.sshTunnel(), keySpec);
		if (hasValue(port)) {
			properties.setProperty(HTTP_PORT_KEY, port);
		}
		persist(properties);
	}

	private static void persist(Properties properties) {
		Path directory = configDirectory();
		Path file = configFile();
		try {
			Files.createDirectories(directory);
			try (OutputStream outputStream = Files.newOutputStream(file)) {
				properties.store(outputStream, "Polymr launcher configuration");
			}
		}
		catch (IOException e) {
			throw new IllegalStateException("Could not store Polymr config to " + file, e);
		}
	}

	static String databaseVariant(Properties properties) {
		String variant = System.getProperty(DATABASE_VARIANT_KEY);
		if (!hasValue(variant)) {
			variant = System.getenv("POLYMR_DATABASE_VARIANT");
		}
		if (!hasValue(variant)) {
			variant = loadBundledApplicationProperties().getProperty(DATABASE_VARIANT_KEY);
		}
		if (!hasValue(variant)) {
			return VARIANT_POSTGRESQL;
		}
		return variant.trim().toLowerCase();
	}

	static boolean isEmbeddedVariant(Properties properties) {
		return VARIANT_DUCKDB.equals(databaseVariant(properties));
	}

	static boolean isSqliteVariant(Properties properties) {
		return VARIANT_SQLITE.equals(databaseVariant(properties));
	}

	static boolean isHsqldbVariant(Properties properties) {
		return VARIANT_HSQLDB.equals(databaseVariant(properties));
	}

	static boolean bootstrapDefaultUseEmbeddedDatabase(Properties properties) {
		return isEmbeddedVariant(properties) || isSqliteVariant(properties) || isHsqldbVariant(properties);
	}

	static RuntimeConfig buildRuntimeConfig(Properties properties, String port) {
		String effectivePort = hasValue(port) ? port : properties.getProperty(HTTP_PORT_KEY);
		String listenScope = normalizeListenScope(properties.getProperty(HTTP_LISTEN_SCOPE_KEY));
		boolean embeddedVariant = isEmbeddedVariant(properties);
		boolean sqliteVariant = isSqliteVariant(properties);
		boolean hsqldbVariant = isHsqldbVariant(properties);
		return new RuntimeConfig(
			embeddedVariant || sqliteVariant || hsqldbVariant,
			embeddedVariant
				? embeddedDatabaseUrl()
				: sqliteVariant
					? sqliteDatabaseUrl()
					: hsqldbVariant ? hsqldbDatabaseUrl() : properties.getProperty(BOOTSTRAP_DB_URL_KEY),
			embeddedVariant
					|| sqliteVariant
					|| hsqldbVariant
				? null
				: properties.getProperty(BOOTSTRAP_DB_USER_KEY),
			embeddedVariant || sqliteVariant || hsqldbVariant ? null : decryptPassword(properties),
			effectivePort,
			listenScope,
			embeddedVariant || sqliteVariant || hsqldbVariant ? null : readSshConfig(properties)
		);
	}

	static void applySystemProperties(RuntimeConfig runtimeConfig) {
		ensureRuntimeDirectories();
		setSystemProperty(DB_URL_KEY, runtimeConfig.databaseUrl());
		if (!runtimeConfig.useEmbeddedDatabase()) {
			setSystemProperty(DB_USER_KEY, runtimeConfig.databaseUser());
			setSystemProperty(DB_PASSWORD_KEY, runtimeConfig.databasePassword());
		}
		setSystemProperty(HTTP_PORT_KEY, runtimeConfig.port());
		setSystemProperty(HTTP_LISTEN_SCOPE_KEY, runtimeConfig.listenScope());
		setSystemProperty(QUARKUS_HTTP_HOST_KEY, hostForListenScope(runtimeConfig.listenScope()));
		setSystemProperty(SERVER_ID_KEY, load().getProperty(SERVER_ID_KEY));
	}

	private static void storeSshConfig(Properties properties, SshTunnelConfig sshTunnel, SecretKeySpec keySpec) {
		properties.setProperty(SSH_ENABLED_KEY, Boolean.toString(sshTunnel != null && sshTunnel.enabled()));
		properties.remove(SSH_SERVER_KEY);
		properties.remove(SSH_PORT_KEY);
		properties.remove(SSH_USERNAME_KEY);
		properties.remove(SSH_PASSWORD_CIPHERTEXT_KEY);
		properties.remove(SSH_PASSWORD_NONCE_KEY);
		properties.remove(SSH_KEY_PATH_KEY);
		properties.remove(SSH_KEY_CIPHERTEXT_KEY);
		properties.remove(SSH_KEY_NONCE_KEY);
		if (sshTunnel == null || !sshTunnel.enabled()) {
			return;
		}
		properties.setProperty(SSH_SERVER_KEY, sshTunnel.server());
		properties.setProperty(SSH_PORT_KEY, Integer.toString(sshTunnel.port()));
		properties.setProperty(SSH_USERNAME_KEY, sshTunnel.username());
		if (hasValue(sshTunnel.password())) {
			SecretCipher.EncryptedSecret encrypted = SecretCrypto.encrypt(sshTunnel.password(), keySpec, new SecureRandom());
			properties.setProperty(SSH_PASSWORD_CIPHERTEXT_KEY, encrypted.ciphertext());
			properties.setProperty(SSH_PASSWORD_NONCE_KEY, encrypted.nonce());
		}
		if (hasValue(sshTunnel.keyPath())) {
			properties.setProperty(SSH_KEY_PATH_KEY, sshTunnel.keyPath());
		}
		if (hasValue(sshTunnel.key())) {
			SecretCipher.EncryptedSecret encrypted = SecretCrypto.encrypt(sshTunnel.key(), keySpec, new SecureRandom());
			properties.setProperty(SSH_KEY_CIPHERTEXT_KEY, encrypted.ciphertext());
			properties.setProperty(SSH_KEY_NONCE_KEY, encrypted.nonce());
		}
	}

	private static RuntimeSshConfig readSshConfig(Properties properties) {
		if (!Boolean.parseBoolean(properties.getProperty(SSH_ENABLED_KEY, "false"))) {
			return null;
		}
		String secretsKey = properties.getProperty(SECRETS_KEY);
		SecretKeySpec keySpec = SecretCrypto.initKey(secretsKey);
		return new RuntimeSshConfig(
			true,
			properties.getProperty(SSH_SERVER_KEY),
			Integer.parseInt(properties.getProperty(SSH_PORT_KEY, "22")),
			properties.getProperty(SSH_USERNAME_KEY),
			decryptEncrypted(
				properties.getProperty(SSH_PASSWORD_CIPHERTEXT_KEY),
				properties.getProperty(SSH_PASSWORD_NONCE_KEY),
				keySpec
			),
			properties.getProperty(SSH_KEY_PATH_KEY),
			decryptEncrypted(properties.getProperty(SSH_KEY_CIPHERTEXT_KEY), properties.getProperty(SSH_KEY_NONCE_KEY), keySpec)
		);
	}

	private static Properties loadBundledApplicationProperties() {
		Properties properties = new Properties();
		try (InputStream inputStream = PolymrBootstrapConfig.class.getClassLoader().getResourceAsStream("application.properties")) {
			if (inputStream != null) {
				properties.load(inputStream);
			}
		}
		catch (IOException e) {
			throw new IllegalStateException("Could not load bundled application.properties", e);
		}
		return properties;
	}

	private static String decryptPassword(Properties properties) {
		String plaintext = properties.getProperty(DB_PASSWORD_KEY);
		if (hasValue(plaintext)) {
			return plaintext;
		}
		String ciphertext = properties.getProperty(DB_PASSWORD_ENCRYPTED_KEY);
		String nonce = properties.getProperty(DB_PASSWORD_NONCE_KEY);
		if (!hasValue(ciphertext) || !hasValue(nonce)) {
			return null;
		}
		String secretsKey = properties.getProperty(SECRETS_KEY);
		SecretKeySpec keySpec = SecretCrypto.initKey(secretsKey);
		return SecretCrypto.decrypt(new SecretCipher.EncryptedSecret(ciphertext, nonce), keySpec);
	}

	private static String decryptEncrypted(String ciphertext, String nonce, SecretKeySpec keySpec) {
		if (!hasValue(ciphertext) || !hasValue(nonce)) {
			return null;
		}
		return SecretCrypto.decrypt(new SecretCipher.EncryptedSecret(ciphertext, nonce), keySpec);
	}

	static String normalizeListenScope(String listenScope) {
		if (!hasValue(listenScope)) {
			return "network";
		}
		if ("local".equalsIgnoreCase(listenScope)) {
			return "local";
		}
		return "network";
	}

	static String hostForListenScope(String listenScope) {
		return "local".equalsIgnoreCase(normalizeListenScope(listenScope)) ? "127.0.0.1" : "0.0.0.0";
	}

	private static void setSystemProperty(String key, String value) {
		if (hasValue(value)) {
			System.setProperty(key, value);
		}
	}

	private static void ensureRuntimeDirectories() {
		try {
			Files.createDirectories(configDirectory());
			Files.createDirectories(logDirectory());
		}
		catch (IOException e) {
			throw new IllegalStateException("Could not create Polymr runtime directories", e);
		}
	}

	private static String embeddedDatabaseUrl() {
		return "jdbc:duckdb:" + configDirectory().resolve(DUCKDB_FILE_NAME).toAbsolutePath();
	}

	private static String sqliteDatabaseUrl() {
		return "jdbc:sqlite:" + configDirectory().resolve(SQLITE_FILE_NAME).toAbsolutePath()
			+ "?journal_mode=WAL&busy_timeout=5000";
	}

	private static String hsqldbDatabaseUrl() {
		return "jdbc:hsqldb:file:" + configDirectory().resolve(HSQLDB_FILE_NAME).toAbsolutePath()
			+ ";sql.syntax_pgs=true;hsqldb.tx=mvcc;hsqldb.default_table_type=cached;shutdown=true";
	}

	static String readSecretFile(Path path) {
		try {
			String secret = Files.readString(path, StandardCharsets.UTF_8).trim();
			if (!hasValue(secret)) {
				throw new IllegalArgumentException("Secret file is empty: " + path);
			}
			return secret;
		}
		catch (IOException e) {
			throw new IllegalStateException("Could not read Polymr secret file " + path, e);
		}
	}

	private static boolean hasValue(String value) {
		return value != null && !value.isBlank();
	}

	record AppConfig(
			boolean useEmbeddedDatabase,
			String url,
			String username,
			String password,
			String listenScope,
			SshTunnelConfig sshTunnel) {}

	record SshTunnelConfig(
			boolean enabled,
			String server,
			int port,
			String username,
			String password,
			String keyPath,
			String key) {}

	record RuntimeConfig(
			boolean useEmbeddedDatabase,
			String databaseUrl,
			String databaseUser,
			String databasePassword,
			String port,
			String listenScope,
			RuntimeSshConfig sshTunnel) {}

	record RuntimeSshConfig(
			boolean enabled,
			String server,
			int port,
			String username,
			String password,
			String keyPath,
			String key) {}
}
