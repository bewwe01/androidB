# DB Explorer (Android)

Native Android database client (PostgreSQL, SQLite; MariaDB later). The product spec and phase
checklist live in `db-explorer-android-SPEC.md` — read it before starting a feature and tick its
checklist when you finish one.

## Layout

- `core/` — pure Kotlin/JVM, no Android. Domain model (`dev.dbexplorer.domain.model`),
  `DatabaseDriver`/`DbSession`, generic `JdbcDbSession`, `PostgresDriver`, `SqliteDriver`.
  Everything here is unit-testable on a plain JVM.
- `app/` — Android: Compose UI (`ui/`), Room (`data/local`), Keystore secrets (`data/secrets`),
  Android SQLite adapter + driver registry (`data/drivers`), `SessionManager` (`data/session`), Hilt (`di/`).
- SQLite logic lives once in `core` (`SqliteSession`) behind the small `SqliteConnection` interface;
  the app implements it with `android.database.sqlite`, core tests with xerial sqlite-jdbc.

## Conventions

- Kotlin only, Compose only (no XML layouts), single-activity, unidirectional data flow (ViewModel + `StateFlow`).
- All blocking/JDBC work off the main thread; each `DbSession` serialises access on its own thread
  (`SessionExecutor`). ViewModels expose immutable UI state.
- No Google Play Services / Firebase / analytics SDKs. No network calls except to user-configured
  databases/SSH hosts (and the AI feature if the user enables it).
- Never hardcode credentials; never log secrets, SQL parameters or connection URLs with credentials.
  `Secrets.toString()` is redacted — keep it that way.
- Instantiate JDBC drivers directly (`org.postgresql.Driver().connect(...)`), not via `DriverManager`.
- Look up current stable versions before adding or bumping a dependency (`gradle/libs.versions.toml`).
- Add a unit test with every new driver capability; run `./gradlew lint ktlintCheck test` before committing.
- Small commits per feature.

## Commands

```sh
./gradlew :core:test                      # driver unit tests (SQLite always; Postgres when DBX_PG_HOST is set)
DBX_PG_HOST=localhost DBX_PG_PORT=5432 DBX_PG_USER=postgres DBX_PG_PASSWORD=... ./gradlew :core:test
./gradlew lint ktlintCheck test           # full pre-commit check
./gradlew connectedDebugAndroidTest       # Keystore, Room, platform SQLite, pgjdbc-on-ART (emulator → 10.0.2.2)
./gradlew ktlintFormat                    # auto-fix style
```
