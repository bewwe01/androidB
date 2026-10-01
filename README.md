# DB Explorer for Android

A DBeaver-style database client for Android phones and tablets, built with Kotlin and Jetpack
Compose. No Google Play Services: it runs on de-Googled devices.

**Status: Phase 1 (connect & browse).** See `db-explorer-android-SPEC.md` for the roadmap.

- Connection manager: PostgreSQL and SQLite profiles, folders, color tags, test connection
- Passwords encrypted with a hardware-backed Android Keystore key; nothing goes into backups
- PostgreSQL: TLS off / require / verify-full, server-enforced read-only mode, error SQLSTATE and position
- SQLite: open a file (copied into app storage) or create a new database
- Navigator: schemas → tables, views, materialized views, sequences, functions, with search and filters
- Table details: columns, primary/foreign keys (tap through to the referenced table), indexes, generated DDL
- Safety: red production banner, read-only badge, optional screenshot blocking per connection

## Build

Requires JDK 17+ and the Android SDK (compileSdk 37).

```sh
./gradlew :app:assembleDebug
./gradlew lint ktlintCheck test
```

### Testing against a local PostgreSQL

JVM driver tests (`core/`) run the PostgreSQL suite when `DBX_PG_HOST` is set:

```sh
DBX_PG_HOST=localhost DBX_PG_PORT=5432 DBX_PG_USER=postgres DBX_PG_PASSWORD=secret ./gradlew :core:test
```

Instrumented tests include `PostgresEmulatorTest`, which proves pgjdbc works on ART. On the emulator the
host machine is `10.0.2.2`; make Postgres listen on the host (`listen_addresses = '*'` plus a
`pg_hba.conf` entry) and pass credentials as runner arguments:

```sh
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.pgUser=postgres \
  -Pandroid.testInstrumentationRunnerArguments.pgPassword=secret
```

The test is skipped, not failed, when nothing listens on `10.0.2.2:5432`.
