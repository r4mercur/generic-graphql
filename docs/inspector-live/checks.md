# Checks – Live-Anbindung Inspector

Stand: 2026-11-01. „Sandbox“ = Cloud-Sandbox mit den Bibliotheken aus `build/libs/generic-graphql-0.0.1-SNAPSHOT.jar`
(Spring Boot 4.1.1, graphql-java 26.1, java-dataloader 5.0.0; javac 21 mit `--enable-preview`), App lief dort auf Port 8099.
Thymeleaf und die Test-Bibliotheken fehlen in der Sandbox (Maven Central gesperrt) – was davon abhängt, läuft auf Bjarnes Rechner.

requirement | verdict | evidence | correction
---|---|---|---
C0 Baseline: bestehende Tests grün vor den Änderungen | pass | `build/test-results/test/*.xml` vom 2026-10-29: 51 Tests, 0 Fehler; der Code war bis auf Leerzeichen derselbe | –
C1 `gradlew test` grün nach den Änderungen (alt + neu) | pass | Bjarnes Rechner, 01.11. 09:36: 68 Tests, 0 Fehler (`build/test-results/test/*.xml`) | erster Lauf 67/68: `UpdateProfile` lief vor dem Hamburg-Test und verschob Jonas nach Bremen – Mutationstest mit `@DirtiesContext` isoliert
C2 Tracer ändert keine fachlichen Ergebnisse | pass | zwei frische Instanzen, `inspector.enabled=true` und `=false`: Antworten der 6 Beispiele identisch bis auf Seed-Zeitstempel und die laut Doku nicht festgelegte Reihenfolge der Relationen (Sandbox-Skript, nicht im Repo). Dazu alle 51 bestehenden Tests grün (C1) | –
C3 Trace-Struktur TrustBadge | pass | Request → matchLatestVersions (list, 1 SQL) → profileA/profileB (forward, 1 gemeinsamer Batch, Schlüssel PROF000001/3/5) → trust (remote, 1 Aufruf mit 3 IDs auf virtual-N, ready vor t1); Marken E1–E3; auf Bjarnes Rechner grün: `trustBadgeShowsOneBatchForBothProfilesAndOneRemoteCall` | –
C4 SQL je Statement (Text, Dauer, Zeilen), Knoten zugeordnet | pass | MatchCard: 7 Statements, je Batch eines, Parameter eingesetzt (`profile_id in ('PROF000005','PROF000003')`), Zeilen 2/3/1/3/4/2/3; auf Bjarnes Rechner grün: `sqlIsCapturedWithTextAndRows` | Endlosrekursion im JDBC-Proxy (Methode `invoke` überdeckte Helfer) – Helfer in `call` umbenannt
C5 Remote-Ausfall | pass | `POST /inspector/api/services/trust {available:false}`: TrustBadge `partial` mit 4 Fehlern an beiden trust-Knoten; TrustedInHamburg `failed`, Suche und Liste rot; auf Bjarnes Rechner grün: `outageShowsPartialResultAndFailedFilter` | –
C6 Mutation UpdateProfile: Schritte und SQL | pass | Schritte loadLatest (1 SELECT), persist(event) (count, find, INSERT), rebuildSnapshots (2 SELECT, DELETE, 2 INSERT); DeleteProfile `declined` mit loadLatest, findReverse, find, nur SELECT; auf Bjarnes Rechner grün: `updateProfileShowsTheStepsOfTheEventMutation, blockedDeleteIsDeclinedWithoutWriting` | –
C7 SSE `/inspector/stream` liefert `trace` | pass | Testclient sah `hello`, je Request `start` und `trace` (JSON mit nodes/spans); auf Bjarnes Rechner grün: `finishedRequestsAreStreamedAsServerSentEvents` | –
C8 `GET /inspector` rendert per Thymeleaf | pass | `InspectorIntegrationTest.pageAndFragmentsAreRenderedWithThymeleaf` grün auf Bjarnes Rechner (Seite, Liste, Detail-Fragment) | `th:attr` mit Kommas im Literal durch `th:aria-label` ersetzt
C9 `inspector.enabled=false` schaltet alles ab | pass | `/inspector` → 404, GraphQL-Antworten unverändert (siehe C2); `InspectorDisabledIntegrationTest` (grün auf Bjarnes Rechner) prüft zusätzlich Beans und DataSource | –
C10 UI im Browser | pass | Sandbox (Playwright): Liste, Call Tree, Timeline, Details, Antwort, Paket-Animation, Ausfall-Schalter, Leeren, Timeline-Zoom; Bjarne: „das klappt alles“ (01.11.) | Legendenfarben (CSS-Reihenfolge), Liste beim Leeren sofort leer
C11 Introspection erzeugt keinen Trace | pass | `{ __schema { … } }` → keine neue ID, kein SSE-Event; auf Bjarnes Rechner grün: `introspectionIsNotPublished` | –
