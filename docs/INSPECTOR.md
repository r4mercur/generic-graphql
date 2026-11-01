# Inspector – was im Service gerade passiert

`http://localhost:8080/inspector` zeigt jeden Request an `POST /graphql` als Call Tree:
welches Feld von welchem Resolver kommt, welche DataLoader-Batches und SQL-Statements es
ausgelöst hat, welche fremden Services gefragt wurden und wie lange alles gedauert hat.
Neue Requests erscheinen sofort – egal ob sie von der Seite selbst, aus GraphiQL, von
`curl` oder aus einem Test kommen.

```bash
./gradlew bootRun
# Browser: http://localhost:8080/inspector
```

## Was die Seite zeigt

| Bereich | Inhalt |
|---|---|
| Beispiele senden | Die Queries aus `docs/` (MatchCard, TrustBadge, ProfileStatus, TrustedInHamburg) und die Mutationen UpdateProfile und DeleteProfile. Ein Klick schickt sie an `POST /graphql`. |
| Requests | Die letzten Mitschnitte, neueste zuerst – Dauer, Anzahl SQL und Remote-Aufrufe, Status (`ok`, `n Fehler`, `Fehler`, `abgelehnt`, `result: error`). |
| Query | Der Text des Requests; die Punkte links markieren Zeilen mit Knoten, ein Klick wählt den Knoten. |
| Call Tree | Ein Knoten je Feld mit eigenem Resolver (über alle Elternobjekte zusammengefasst), angeordnet nach Ebenen. Remote-Suchen für Filter stehen rechts neben dem Feld, die Schritte einer Mutation eine Ebene darunter. Breite Bäume scrollen, die Beschriftung der Ebenen bleibt links stehen. |
| Timeline | Spuren für Request-Thread, Datenbank und jeden fremden Service. Wartezeiten (`awaitAndComplete()`, `RemoteFilterSearch`) sind schraffiert, Fehler rot. Mit `−`/`+` oder Strg + Mausrad zoomen, dann horizontal verschieben (ziehen, Shift + Mausrad oder Scrollleiste); beim Abspielen läuft der Ausschnitt mit, bis man selbst scrollt. `Gesamt` zeigt wieder den ganzen Request. |
| Knoten | Fakten, Aufrufkette mit Fundstelle im Code, Schlüssel des Batches, SQL mit eingesetzten Parametern und das Ergebnis des Knotens aus der Antwort. |
| Antwort | Die JSON-Antwort, wie sie beim Client ankommt. |

Mit **Live folgen** springt die Seite zu jedem neuen Request und spielt ihn ab: weiße Punkte
laufen als Anfrage den Baum hinunter, farbige Pakete bringen die Daten zurück. Abgespielt wird
in „visueller Zeit“: an jeder Übergabe steht die gemessene Zeit kurz still, damit man die Pakete
sieht – die Zahlen an Knoten und Timeline sind die echten Messwerte. Tempo 0,5×/1×/2×,
Schieberegler zum Spulen.

Die Schalter **Trust-Service / Presence-Service ausfallen lassen** setzen die simulierten
Services auf „503“. Damit lässt sich zeigen, dass ein ausgefallenes Remote-Feld nur sich selbst
auf `null` setzt (Teilergebnis), eine ausgefallene Filter-Suche aber die ganze Query scheitern
lässt.

## Wie der Mitschnitt entsteht

```
POST /graphql
  └─ GraphQLController.execute                    unverändert
       └─ GraphQL.execute
            ├─ Guardrails (Tiefe, Komplexität)
            └─ TraceInstrumentation               inspector/ – zweite Instrumentation
                 ├─ createState: TraceCollector je Request, an den Request-Thread gebunden
                 ├─ instrumentDataFetcher: misst jeden Resolver mit eigener Logik
                 └─ instrumentExecutionResult: TraceView bauen, an TraceHub geben
Engine ── EngineProbe.current() ──► TraceCollector  Batches, Remote-Aufrufe, Wartezeiten,
                                                     Filter-Suchen, Mutations-Schritte
DataSource ── TracingDataSource ──► TraceCollector  jedes SQL-Statement mit Dauer und Zeilen
TraceHub ── SSE /inspector/stream ──► Browser       "start" und "trace"
```

- **Felder**: `TraceInstrumentation` umhüllt nur Resolver mit eigener Logik (Query-Felder,
  Relationen, Remote-Felder, Mutationen); einfache Property-Zugriffe bleiben unangetastet.
  `FieldClassifier` ordnet jedes Feld anhand derselben Registry zu, aus der `SchemaFactory` das
  Schema baut.
- **Engine-Messpunkte** (`engine/probe/EngineProbe`): ein schmales SPI mit `batch`, `remoteCall`,
  `remoteSearch`, `waiting`, `step` und `sql`. Ohne Inspector ist es eine leere Probe
  (`EngineProbe.NOOP`). Aufgerufen wird es in `RelationDataLoaders`, `RemoteFieldLoaders`,
  `RemoteCalls`, `RemoteFilterSearch` und `EventMutationService` – ohne fachliche Änderung.
- **SQL**: `TracingDataSource` legt um die DataSource einen JDBC-Proxy, der Statements nur dann
  misst, wenn auf dem Thread gerade ein Request mitgeschnitten wird. Ein Statement in einem
  DataLoader-Batch gehört zu den Feldern dieses Loaders; sonst zum Feld oder Schritt, der gerade
  läuft.
- **Seite**: Thymeleaf rendert Seite, Request-Liste (`inspector/fragments :: list`) und
  Detail-Panel (`inspector/fragments :: node`); `static/inspector/app.js` zeichnet Call Tree,
  Timeline und Animation aus dem JSON des Mitschnitts.

## Endpunkte

| Methode | Pfad | Antwort |
|---|---|---|
| GET | `/inspector` | Seite (Thymeleaf) |
| GET | `/inspector/fragments/list` | Request-Liste als HTML-Fragment |
| GET | `/inspector/fragments/node?trace={id}&node={nodeId}` | Detail-Panel als HTML-Fragment |
| GET | `/inspector/stream` | Server-Sent Events: `hello`, `start`, `trace`, `cleared` |
| GET | `/inspector/api/traces` | Kurzfassung der letzten Mitschnitte |
| GET | `/inspector/api/traces/{id}` | ein Mitschnitt (`TraceView`) |
| GET | `/inspector/api/traces/{id}/nodes/{nodeId}` | Details eines Knotens (`NodeDetails`) |
| DELETE | `/inspector/api/traces` | Liste leeren |
| GET | `/inspector/api/services` | simulierte Services und ob sie antworten |
| POST | `/inspector/api/services/{id}` | `{"available": false}` lässt einen Service ausfallen |

## Konfiguration

```properties
inspector.enabled=true      # false: kein Mitschnitt, keine Seite, kein JDBC-Proxy
inspector.max-traces=50     # so viele Requests hält der Speicher vor
```

## Grenzen

- **Entwicklungswerkzeug.** Die Seite zeigt Query-Texte, SQL mit eingesetzten Parametern und
  komplette Antworten – ohne Anmeldung. Außerhalb der lokalen Entwicklung
  `inspector.enabled=false` setzen.
- Gezeigt wird der Request nach seinem Ende; die Animation spielt die gemessenen Zeiten nach.
  Requests dauern hier 10 bis 300 ms – in Echtzeit wäre nichts zu erkennen.
- Gemessen wird die Ausführung in graphql-java. Serialisierung der Antwort und das `COMMIT` am
  Ende von `@Transactional` liegen danach und fehlen in der Timeline. SQL, das Hibernate erst
  beim Commit schreibt, fehlt ebenso (`EventMutationService` ruft `flush()` selbst auf und ist
  deshalb vollständig).
- Mehrere Felder mit demselben DataLoader im selben Zeitfenster teilen sich einen Batch; das
  SQL steht dann an beiden Knoten („gemeinsam mit …“).
- Die Mitschnitte liegen nur im Speicher.

## Logo

Das Logo im Kopf der Seite ist die offizielle weiße Bildmarke aus den Brand Assets von
https://graphql.org/brand/ (`static/inspector/graphql-logo-white.svg`, unverändert). „GraphQL“ ist
eine Marke der GraphQL Foundation; die Seite nutzt das Logo als sachlichen Verweis auf GraphQL.
Laut Richtlinien: Datei nicht verändern oder nachzeichnen, nur Rhodamine (`#E10098`), Weiß oder
Schwarz – auf dunklem Grund Weiß –, rundum mindestens eine halbe Logohöhe Freiraum.

## Tests

- `InspectorIntegrationTest` – Struktur der Mitschnitte (Felder, Batches, SQL, Remote-Aufrufe,
  Filter-Suche, Mutations-Schritte), Ausfall eines Service, SSE, REST, Thymeleaf-Seite und
  -Fragmente, Introspection ohne Mitschnitt; die Beispiele der Seite müssen wörtlich in `docs/`
  stehen.
- `InspectorDisabledIntegrationTest` – mit `inspector.enabled=false` keine Beans, keine Seite,
  GraphQL unverändert.
- `inspector/JdbcTracingTest` – Kurzform und Parameter-Einsetzung der SQL-Anzeige.
