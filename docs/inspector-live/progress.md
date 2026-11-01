# Progress – Live-Anbindung Inspector

**Task:** siehe `task.md`. Thymeleaf für Seite, Liste und Detail-Panel; Vanilla-JS für SSE, Call Tree, Timeline und Animation.

**Outputs:**
- SPI: `src/main/java/com/bjarne/genericgraphql/engine/probe/EngineProbe.java`, `EngineProbes.java`
- Haken (ohne fachliche Änderung): `RelationDataLoaders`, `RemoteFieldLoaders`, `RemoteCalls`, `RemoteFilterSearch`, `EventMutationService`, `GraphQLConfig` (weitere Instrumentations als Beans), `SimulatedRemoteService` (`isAvailable`, `name`)
- Paket `src/main/java/com/bjarne/genericgraphql/inspector/`: `TraceInstrumentation`, `TraceCollector`, `FieldClassifier`, `TraceHub` (SSE), `JdbcTracing` + `TracingDataSource` + `TracingDataSourcePostProcessor`, `InspectorController`, `InspectorDetails`, `InspectorExamples`, Records (`TraceView`, `NodeView`, `SpanView`, …)
- Seite: `src/main/resources/templates/inspector.html`, `templates/inspector/fragments.html`, `static/inspector/app.css`, `static/inspector/app.js`
- Tests: `InspectorIntegrationTest`, `InspectorDisabledIntegrationTest`, `inspector/JdbcTracingTest`
- Doku: `docs/INSPECTOR.md`; `build.gradle` (+ `spring-boot-starter-thymeleaf`), `application.properties` (`inspector.enabled`, `inspector.max-traces`)

**Completed:**
- Baseline C0 aus den Testergebnissen vom 29.10.
- Backend und UI gebaut und in der Cloud-Sandbox geprüft: Kompilieren mit den Bibliotheken aus dem bootJar, App gestartet, 6 Beispiele + Ausfall + Fehlerfälle gesendet, REST/SSE geprüft, Seite per Playwright durchgeklickt (Templates dort mit Thymeleaf-Subset gerendert). Ergebnisse in `checks.md`.
- Dateien in der Sandbox gebaut und per Archiv (`build/tmp/inspector-sync/`) ins Repo übertragen; Prüfsummen Gerät = Sandbox.

- Auf Bjarnes Rechner: `gradlew test` 68/68 grün (01.11., nach Fix der Testreihenfolge per `@DirtiesContext`); Seite unter `/inspector` von Bjarne bestätigt.
- Nachtrag auf Bjarnes Hinweis: Timeline zoom- und horizontal scrollbar (−/+, Strg + Mausrad, Ziehen, Shift + Mausrad), Spurköpfe und Ebenen-Beschriftung bleiben beim Scrollen links stehen, Ausschnitt folgt beim Abspielen dem Playhead.
- GraphQL-Logo: offizielle weiße Bildmarke aus den Brand Assets von graphql.org/brand (von Bjarne geladen, `build/tmp/graphql-brand/`), unverändert als `static/inspector/graphql-logo-white.svg` im Kopf der Seite und im Header des Canvas-Artefakts; Markenhinweis in der Fußzeile; Test prüft Einbindung und Auslieferung.

**Decisions:**
- Mitschnitt über eine zweite graphql-java-Instrumentation, keine Änderung am Controller. Der `TraceCollector` ist zugleich `InstrumentationState` und `EngineProbe` und hängt per ThreadLocal am Request-Thread.
- Felder werden je Pfad ohne Listenindex zu einem Knoten zusammengefasst; Batches, SQL und Remote-Aufrufe werden über Loader-Schlüssel und Zeitfenster den Feldern zugeordnet.
- SQL über einen JDBC-Proxy um die DataSource (Text mit eingesetzten Parametern, Dauer, Zeilen) – nur aktiv, solange auf dem Thread mitgeschnitten wird.
- Animation spielt die gemessenen Zeiten in „visueller Zeit“ nach (wie im Canvas-Entwurf); nahe Übergaben teilen sich ein Hop-Fenster.
- Beispiele wörtlich aus `docs/`; ein Test stellt sicher, dass sie dort stehen.

**Open issues:**
- keine bekannten; Zoom der Timeline nur in der Sandbox (Playwright) geprüft – nach Neustart von `bootRun` im eigenen Browser ansehen.

**Next action:** `bootRun` neu starten (Logo, Timeline-Zoom) und einmal `gradlew test` laufen lassen.
