# Android Database Explorer — Build Spec (for Claude Code)

A modern, native Android database client with DBeaver-style functionality, designed for touch.
Drop this file in the repo root (or merge the "Conventions" section into `CLAUDE.md`) and work through the phases in order.

---

## 1. Key decisions (read first)

| Decision | Choice | Why |
|---|---|---|
| Platform | **Native Android, Kotlin + Jetpack Compose (Material 3)** | The mature DB drivers are JDBC/JVM libraries. React Native would need a custom native module bridging to JDBC, which defeats the point. |
| Min SDK | **26** (Android 8.0) | pgjdbc built for Java 8+ uses `java.time.Duration`, which needs API 26; a community compatibility table lists 42.2.9 as API 26+ and the jre7 build as API 24+. Enable core library desugaring regardless. |
| DB access | **JDBC** behind a `DatabaseDriver` abstraction | One generic code path using `java.sql.DatabaseMetaData` gives schema browsing for every JDBC database. Dialect-specific code only where needed. |
| First targets | **PostgreSQL** (pgjdbc, `org.postgresql:postgresql`), **SQLite** (built into Android, `android.database.sqlite` or via JDBC-style wrapper), then **MariaDB/MySQL** | Phase 1 should prove the architecture with two very different backends. |
| MySQL driver | **MariaDB Connector/J** (`org.mariadb.jdbc.Driver`, `jdbc:mariadb://`) | Works with MySQL and MariaDB servers. Oracle's Connector/J has a history of Android compatibility problems (Java 8+ features / desugaring). Verify on a device before committing. |
| SSH tunnel | **mwiede/jsch** (maintained JSch fork, drop-in replacement) | Supports local port forwarding and "stream forwarding" (no local TCP port opened). Alternative: ConnectBot's Kotlin `cbssh` library. |
| Local storage | **Room** for connection profiles, query history, saved scripts | Secrets are NOT stored in Room — see Security. |
| DI / async | Hilt (or Koin), Kotlin Coroutines + Flow | All JDBC calls run on `Dispatchers.IO` (Android forbids network on the main thread). |
| Play Services | **Do not depend on Google Play Services / Firebase** | The app should run on de-Googled setups (e.g. GrapheneOS). Use only AndroidX + Android Keystore + BiometricPrompt. |

> Verify current stable versions of every dependency before pinning (AGP, Kotlin, Compose BOM, pgjdbc, mariadb-java-client, mwiede/jsch). Do not guess version numbers.

---

## 2. DBeaver feature map → mobile design

DBeaver's headline features: schema editor, SQL editor, data editor, AI chat, ER diagrams, data export/import/migration, SQL execution plans, admin tools, dashboards, spatial viewer, proxy and SSH tunnelling, and a custom driver editor.

| DBeaver feature | Mobile version | Phase |
|---|---|---|
| Connection manager (profiles, folders, test connection) | List + edit form, color tags, "Test connection" button | 1 |
| Database navigator tree (databases → schemas → tables/views/functions/sequences) | Drill-down list with breadcrumb + search; long-press for actions. Lazy-loaded via `DatabaseMetaData` | 1 |
| Data viewer/editor (grid, sort, filter, paginate, inline edit, row insert/delete) | Virtualized grid + "card/record view" toggle for single-row editing on small screens; edits batched and committed explicitly | 2 |
| SQL editor (multi-tab, run, run selection, cancel, history, saved scripts) | Tabbed editor, extended keyboard row (`( ) , ; ' "` and Tab), run/cancel FAB, results below in a bottom sheet | 2 |
| Autocomplete / formatting | Metadata-cache-driven completion (tables, columns, keywords); simple formatter | 3 |
| Table/schema editor (DDL: create/alter table, columns, indexes, constraints) | Form-based editors that **generate DDL and show it for review** before execution | 3 |
| Properties / DDL viewer | Tabs: Columns, Indexes, Foreign keys, Constraints, DDL, Stats | 2 |
| ER diagrams | Read-only diagram from FK metadata, custom `Canvas` with pan/zoom; tap table → details | 4 |
| Data export / import | Export results/tables to CSV, JSON, SQL INSERTs via Storage Access Framework; CSV import with column mapping | 3 |
| Execution plans | `EXPLAIN` / `EXPLAIN (ANALYZE, FORMAT JSON)` rendered as an expandable tree with cost highlights | 4 |
| Admin tools / dashboards (sessions, locks, sizes) | Read-only "Server" screen: active sessions (`pg_stat_activity`), DB/table sizes, kill-session with confirmation | 4 |
| SSH tunnelling / proxy | Per-connection SSH settings (host, port, user, password or key from Keystore) | 3 |
| Data/schema compare, visual query builder | Out of scope initially | later |
| AI chat | Optional; BYO API key, send **schema only, never row data** by default | later |
| Custom driver editor | Out of scope — bundle drivers at build time (no dynamic JAR loading on Android) | — |

---

## 3. Architecture

```
app/
  ui/            Compose screens, navigation, theming (Material 3, dynamic color, dark mode)
  domain/        Use cases, models (Connection, DbObject, QueryResult, Column, Row)
  data/
    drivers/     DatabaseDriver interface + PostgresDriver, SqliteDriver, MariaDbDriver
    metadata/    MetadataRepository (cached tree, lazy loading)
    ssh/         SshTunnelManager
    local/       Room (profiles, history, scripts)
    secrets/     KeystoreSecretStore
  export/        CSV / JSON / SQL writers
```

**`DatabaseDriver` interface (sketch)**

```kotlin
interface DatabaseDriver {
    suspend fun connect(profile: ConnectionProfile, secrets: Secrets): DbSession
}
interface DbSession : AutoCloseable {
    suspend fun listCatalogs(): List<Catalog>
    suspend fun listSchemas(catalog: String?): List<Schema>
    suspend fun listTables(schema: SchemaRef, types: Set<TableType>): List<TableRef>
    suspend fun describeTable(t: TableRef): TableDetails   // columns, PK, FKs, indexes
    suspend fun ddl(t: TableRef): String                   // dialect-specific
    fun execute(sql: String, pageSize: Int): Flow<ResultChunk>   // streamed, cancellable
    suspend fun cancel()                                   // Statement.cancel()
    suspend fun beginTx(); suspend fun commit(); suspend fun rollback()
}
```

Rules:
- Generic metadata comes from `DatabaseMetaData` (`getSchemas`, `getTables`, `getColumns`, `getPrimaryKeys`, `getImportedKeys`, `getIndexInfo`). Dialect-specific SQL (DDL generation, sizes, sessions, EXPLAIN) lives in the driver subclass.
- **Never load a full table into memory.** Page with `LIMIT/OFFSET` (or keyset pagination when a PK exists) and set `fetchSize`. On PostgreSQL, a cursor-based fetch size only works with auto-commit off — handle that.
- Every query is cancellable and has a timeout; show elapsed time and row count.
- Cap cell rendering (e.g. truncate long text/blobs, "tap to view full value"). Render JSON/XML/images in a detail view.
- Inline editing needs a primary key; if the table has none, make the grid read-only and say why.
- Connection pool is unnecessary; one session per open connection, closed when the app backgrounds (configurable) and on tunnel loss.

---

## 4. Networking, SSH & security

- Declare `INTERNET` permission. Targets will often be on a private network or VPN (e.g. a Postgres on a home server reached via WireGuard/Tailscale) — the app just connects to the host given; no special VPN code is needed. Make sure plain-IP and `.local`/MagicDNS hostnames work.
- **TLS**: support `sslmode`-style options per profile (disable / require / verify-full), custom CA import. Default new profiles to TLS on.
- **SSH tunnel**: open a JSch session, forward to `remoteHost:remotePort`, then point the JDBC URL at `127.0.0.1:<localPort>` (use auto-assigned port). Detect session drops and surface a reconnect action.
- **Secrets**: encrypt passwords/keys with an Android Keystore AES-GCM key (hardware-backed where available); store only ciphertext. Optional biometric gate (`BiometricPrompt`) to unlock saved credentials. Never log SQL parameters, passwords, or connection URLs with credentials. `android:allowBackup="false"` or exclude the secrets store.
- **Safety rails** (important on a phone): per-connection **read-only mode**, a confirmation dialog for `UPDATE/DELETE` without `WHERE`, `DROP`, `TRUNCATE`; auto-commit toggle with visible transaction state; color-code production connections (e.g. red banner).
- Use `FLAG_SECURE` option to block screenshots on sensitive connections.

---

## 5. UI/UX guidelines ("nice modern UI")

- Material 3, dynamic color, proper dark theme, edge-to-edge.
- Phone: bottom navigation (Connections · Explorer · Query · History). Tablet/foldable: two-pane (tree + content) via window size classes.
- **Data grid**: Compose has no built-in table. Build with `LazyColumn` (rows) + shared horizontal scroll state + sticky header; fixed-width columns sized from type/sample; selection mode with long-press; column header tap = sort; filter chips above the grid. Offer a record (card) view as the default for narrow screens.
- **SQL editor**: monospaced text with syntax highlighting (custom `VisualTransformation` is enough for v1; evaluate a dedicated Android code-editor library only if highlighting/perf becomes a problem — verify options before adding a dependency). Include an accessory key row above the keyboard.
- Results/errors: show server error message, position, and SQLSTATE. Keep the last result when a new query fails.
- Support external keyboards (Ctrl+Enter to run) — many users will attach one.

---

## 6. Build phases (each ends in a working, testable app)

**Phase 1 — Connect & browse**
Project scaffold, theming, nav, Room, Keystore secret store. Connection profile CRUD + test. `PostgresDriver` + `SqliteDriver`. Navigator tree and table detail (columns, keys, indexes).

**Phase 2 — Query & data**
Query editor with tabs, run/cancel, streamed paged results, history. Data grid for a table with sort/filter/pagination. Inline edit/insert/delete with PK, explicit commit. DDL viewer.

**Phase 3 — Power features**
MariaDB/MySQL driver, SSH tunnel, TLS options, autocomplete, DDL editors (generate-and-review), export (CSV/JSON/SQL) and CSV import.

**Phase 4 — Insight**
ER diagram, EXPLAIN viewer, server/sessions screen, read-only safety rails polish, tablet layouts.

### Progress checklist

Phase 1
- [x] Project scaffold: Gradle version catalog, `:core` (pure JVM) + `:app` (Android), minSdk 26, desugaring, Hilt, Room, ktlint
- [x] Theming (Material 3, dynamic color, dark mode, edge-to-edge) and bottom navigation
- [x] Room store for connection profiles (no secrets)
- [x] Keystore AES-256-GCM secret store (StrongBox when available), backups disabled
- [x] Connection CRUD with folders, color tags, test connection, password prompt when not saved
- [x] `DatabaseDriver` / `DbSession` + generic `DatabaseMetaData` session
- [x] `PostgresDriver` (TLS modes, read-only enforced, SQLSTATE + error position, cursor streaming)
- [x] `SqliteDriver` on `android.database.sqlite` (open/import via SAF, or create new)
- [x] Navigator: open connections → schemas → objects (tables/views/mat. views/sequences/functions) with search and type filters
- [x] Table detail: columns, keys (FK tap-through), indexes, DDL
- [x] Production banner, read-only badge, per-connection `FLAG_SECURE`
- [ ] Verified on an emulator/device (`PostgresEmulatorTest` exists; not yet run — see README)

Pulled forward from later phases: DDL viewer (Phase 2), TLS options for PostgreSQL (Phase 3), `execute()` streaming/cancel in the driver layer (Phase 2 UI still to do).

---

## 7. Testing

- Unit-test drivers against **Testcontainers** (Postgres/MariaDB) on the JVM side where possible; instrumented tests for Keystore, Room, SQLite.
- Manual matrix: emulator + a real device, API 26 and latest, Wi-Fi, VPN, mobile data, airplane-mode mid-query, large tables (1M+ rows), wide tables (100+ columns), huge text/bytea cells, non-UTF8 data, slow network.
- Note: emulator `localhost` is not the host machine — use `10.0.2.2` for the host loopback.

---

## 8. Conventions for CLAUDE.md

- Kotlin only, Compose only (no XML layouts), single-activity, unidirectional data flow (ViewModel + `StateFlow`).
- All blocking/JDBC work off the main thread; ViewModels expose immutable UI state.
- No Google Play Services / Firebase / analytics SDKs. No network calls except to user-configured databases/SSH hosts (and the AI feature if the user enables it).
- Never hardcode credentials; never log secrets.
- Add a unit test with every new driver capability; run `./gradlew lint ktlintCheck test` before committing.
- Small PRs/commits per feature; update this spec's phase checklist as items complete.

---

## 9. Starter prompt for Claude Code

> Read `db-explorer-android-SPEC.md`. Start **Phase 1**. Create a new Android project (Kotlin, Jetpack Compose Material 3, minSdk 26, Hilt, Room, coroutines) with the package layout from section 3. Implement the `DatabaseDriver`/`DbSession` interfaces, a `PostgresDriver` (pgjdbc) and `SqliteDriver`, the Keystore-backed secret store, the connection manager screens, and the navigator tree with table details. Look up current stable dependency versions before adding them, enable core library desugaring, and verify pgjdbc connects from an emulator to a local Postgres (host `10.0.2.2`) in an instrumented test. Stop after Phase 1 and summarize what works and what's stubbed.

---

## Sources consulted
- DBeaver README (feature list): https://github.com/dbeaver/dbeaver
- DBeaver feature overview: https://dbeaver.com/features/
- pgjdbc on Android compatibility table: https://github.com/retlat/Connect-PostgreSQL-from-Android
- MariaDB Connector/J (driver class, `jdbc:mariadb:` scheme): https://mariadb.com/mariadb-java-client
- Java 8+/desugaring issue with MariaDB/MySQL drivers on Android: https://www.b4x.com/android/forum/threads/jdbcsql-only-working-with-earlier-mariadb-connector-j.115005/
- mwiede/jsch (port forwarding, drop-in): https://github.com/mwiede/jsch
- ConnectBot cbssh: https://github.com/connectbot/cbssh
