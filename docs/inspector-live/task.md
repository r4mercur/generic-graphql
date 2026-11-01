# Aufgabe: Live-Anbindung des GraphQL Inspectors

Stand: 2026-10-31 · Arbeitsweise nach dem Long-Task-Harness (Baseline, feste Checks, Übergabe in progress.md)

## Inputs

| Feld | Inhalt |
|---|---|
| Task | Jeder Request an `POST /graphql` wird mitgeschnitten (Felder je Ebene, DataLoader-Batches, SQL, Remote-Aufrufe, Mutations-Schritte) und live an eine Thymeleaf-Seite `/inspector` gestreamt. Die Seite zeigt ihn wie das Canvas-Design: Call Tree, Timeline, Details, Antwort, Paket-Animation. |
| Output | Paket `com.bjarne.genericgraphql.inspector`, SPI `engine/probe/EngineProbe`, Template `templates/inspector.html`, Assets `static/inspector/`, Tests, `docs/INSPECTOR.md` |
| Checks | siehe `checks.md` (Tabelle: Anforderung, Urteil, Beleg, Korrektur) |
| Quellen | Code unter `src/main/java/com/bjarne/genericgraphql/`, `docs/GQL2-*.md`, Canvas „generic-graphql · GraphQL Inspector“ (Artboard Main.dc.html = UI-Vorlage, Pipeline.dc.html = Messpunkte) |
| Scope | Nur dieses Repo. Neue Dateien im Paket `inspector`; kleine Haken in Engine-Klassen (keine fachliche Änderung); `build.gradle` (+ Thymeleaf); `application.properties` (`inspector.*`). Keine Commits, keine Löschungen. |
| Limit | Diese Session; reicht der Kontext nicht, übernimmt eine neue Session ab `progress.md`. |

## Umgebung

- Maven Central und services.gradle.org sind aus den Sandboxen gesperrt (HTTP 403). Gebaut und getestet wird auf Bjarnes Rechner: IntelliJ, Gradle-Tool-Fenster per Klick (`test`, `bootRun`).
- Testergebnisse werden aus `build/test-results/test/*.xml` gelesen.
