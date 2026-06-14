# Flyway migrations

Flyway now owns schema creation.
It assumes the connected database user resolves unqualified object names in the target schema.

Locations:

- `db/migration/common`
- `db/migration/postgresql`
- `db/migration/duckdb`
- `db/migration/sqlite`
- `db/migration/hsqldb`

Current setup expects:

- PostgreSQL builds to use `db/migration/postgresql/V1__initial_schema.sql`
- DuckDB builds to use `db/migration/duckdb/V1__initial_schema.sql`
- SQLite builds to use `db/migration/sqlite/V1__initial_schema.sql`
- HSQLDB builds to use `db/migration/hsqldb/V1__initial_schema.sql`

The checked-in `V1` files are placeholders and must be replaced with real DDL before first startup on an empty database.
Do not schema-qualify objects in those migrations if the PostgreSQL user is tied to a schema/search_path.

Suggested workflow:

1. Export the current Hibernate schema for PostgreSQL.
2. Export the current Hibernate schema for DuckDB using the duckdb profile.
3. Export the current Hibernate schema for SQLite using the sqlite profile.
4. Export the current Hibernate schema for HSQLDB using the hsqldb profile.
5. Normalize each export into the matching `V1__initial_schema.sql`.
6. Keep later schema changes in incremental `V2+` Flyway migrations.
