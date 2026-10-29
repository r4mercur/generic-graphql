# Szenario: Generisches GraphQL als Orchestrierungsschicht über Microservices

Bisher liegt im Beispiel alles in einer Datenbank. In einer echten Landschaft ist
diese Anwendung oft nur die **Orchestrierungssicht**: Ein Teil dessen, was das
Schema anbietet, gehört anderen Services mit eigener Datenbank.

Im Beispiel gehört die Vertrauensbewertung eines Profils dem **Trust-Service**:
Vertrauensstufe, offene Meldungen, letzte Prüfung. Das Profil selbst liegt weiter
hier. Die Frage ist, was von den Fähigkeiten der generischen Engine dann noch trägt.

Weiterführend: [Beispielimplementierung](GQL2-JAVA-IMPLEMENTIERUNG.md) ·
[Architekturskizze](GQL2-JAVA.md) · [Client-Sicht](../README.de.md)

Leitplanke für dieses Kapitel ist die **Datenhoheit**: Der Service, dem die Daten
gehören, ist dafür verantwortlich, was davon ausgespielt wird – an wen, in welchem
Stand und auf welche Fragen hin. Die Orchestrierung darf diese Verantwortung nicht
übernehmen, auch nicht stillschweigend über eine Kopie.

## Kurzantwort

**In weiten Teilen ja.** Felder aus einem fremden Service lassen sich abfragen,
verschachtelt, gebündelt und parallel ([Weg 1](#weg-1-remote-felder-lauffähig-im-beispiel)).
Soweit der Owner Felder freigibt, lassen sie sich auch filtern und durchsuchen – mit
`AND`, `OR`, `NOT`, in Relationen und im Wildcard
([Weg 2](#weg-2-filter-an-den-owner-delegieren-lauffähig-im-beispiel)). Die
Orchestrierung zerlegt dafür den Filter und fragt die Owner parallel. Sortierung
nach fremden Feldern und Stichtag über Servicegrenzen gibt es nur, soweit der Owner
sie selbst anbietet.

| Fähigkeit | Daten in der eigenen DB | Weg 1: Remote-Feld anzeigen | Weg 2: Filter an den Owner |
|---|---|---|---|
| Abfragen, beliebig verschachtelt | ✅ | ✅ ein Aufruf pro Ebene, Services parallel | ✅ wie Weg 1 |
| Filter auf fremde Felder | ✅ | ❌ | ✅ nur vom Owner freigegebene Felder |
| `AND` / `OR` / `NOT` über beide Quellen | ✅ | ❌ | ✅ |
| Filter innerhalb von Relationen | ✅ | ❌ | ✅ |
| `WILDCARDFILTER` | ✅ | ❌ | ✅ freigegebene Textfelder |
| `order` mit `limit`/`offset` | ✅ | ❌ | ❌ nur nach lokalen Feldern ([Ausbaustufe](#implementierungsplan-filterbaum-zerlegen)) |
| Stichtag `date` | ✅ | ❌ nur, wenn der Owner Stände kennt | ❌ dito |
| Löschschutz, `*Relations` | ✅ | ❌ | ❌ |
| Wer entscheidet, was sichtbar und filterbar ist | Orchestrierung | Owner | Owner |
| Größe der Treffermenge | – | – | höchstens `maxIds` pro Teilbaum |
| Bei Ausfall des Owners | – | Teilergebnis | Query schlägt fehl |

## Das Dilemma: Datenhoheit, freie Abfragen, keine Kopien

Drei Dinge lassen sich nicht gleichzeitig haben:

1. **Datenhoheit beim Owner** – er entscheidet, was ausgespielt wird.
2. **Beliebig über Domänen hinweg filtern, sortieren und blättern**, in einem Request.
3. **Keine Datenkopien** außerhalb des Owners.

| Man wählt … | … und bekommt |
|---|---|
| 2 + 3 | eine gemeinsame Datenbank – der Stand des Beispiels vor diesem Kapitel |
| 1 + 2 | Kopien beim Konsumenten (siehe [Randnotiz](#randnotiz-kopie-über-events-nur-mit-vertrag-des-owners)) |
| 1 + 3 | eingeschränkte Abfragen über Servicegrenzen – **Weg 1 und Weg 2** |

Wenn Datenhoheit ein festes Prinzip ist, bleibt die letzte Zeile. Die Frage ist dann
nicht, *ob* man Fähigkeiten aufgibt, sondern *welche* – und wie viele man über den
Owner zurückholen kann, ohne seine Hoheit zu verletzen.

Ein Punkt wird dabei leicht übersehen: **Filtern ist auch Lesen.** Mit
`filter: { openReports: { gt: 0 } }` erfährt ein Client, wer gemeldet wurde, auch
wenn er `openReports` nie angezeigt bekommt. Ob über ein Feld gefiltert werden darf,
muss deshalb der Owner entscheiden – nicht die Orchestrierung.

## Warum die Grenze genau dort verläuft

Die Engine übersetzt eine Query in **ein** SQL-Statement. Filter über Relationen
werden zu korrelierten `EXISTS`-Subqueries, `order` zu Joins, der Stichtag zu einer
`NOT EXISTS`-Bedingung (siehe [GQL2-JAVA-IMPLEMENTIERUNG.md](GQL2-JAVA-IMPLEMENTIERUNG.md)).
Daten in einer fremden Datenbank kommen in diesem Statement nicht vor.

Das ist keine Lücke dieser Implementierung, sondern liegt in der Sache. Wer
`limit: 20` nach einem fremden Feld sortiert haben will, muss entweder alle
Kandidaten laden und im Speicher sortieren oder die Daten dort haben, wo
sortiert wird.

---

## Weg 1: Remote-Felder (lauffähig im Beispiel)

Ein Remote-Feld hängt einen Wert aus einem fremden Service an einen lokalen Typ.
Die Engine lädt ihn per DataLoader nach: ein Aufruf pro Query-Ebene für alle
Schlüssel dieser Ebene.

### Konfiguration

`config/RegistryConfig.java`:

```java
registry.register(eventSourced(Profile.class, "profile")
        .remoteField(RemoteField.of("trust", TrustInfo.class)
                .source("Trust-Service")
                .keyAttribute("objectRefId")
                .timeout(Duration.ofSeconds(2))
                .loader((profileIds, context) -> trustService.batchGet(profileIds))
                .build())
        .remoteField(RemoteField.of("presence", PresenceInfo.class)
                .source("Presence-Service")
                .keyAttribute("objectRefId")
                .timeout(Duration.ofMillis(500))
                .loader((profileIds, context) -> presenceService.batchGet(profileIds))
                .build())
        .build());
```

`TrustInfo` ist ein Record, also das, was über die Leitung kommt. Der
`TrustServiceClient` ist ein Interface. Im Beispiel steckt dahinter der
`SimulatedTrustService` mit eigener „Datenbank“ (einer Map), künstlicher Latenz
(`remote.trust.latency-ms`) und Aufrufprotokoll. In einer echten Landschaft wäre
das ein HTTP-Client für `POST /trust/profiles:batchGet`.

Der **Presence-Service** (online, schreibt gerade, Gerät) ist nach demselben Muster
gebaut. Es gibt ihn vor allem, um zu zeigen, dass
[mehrere Services auf einer Ebene parallel](#mehrere-services-auf-einer-ebene-parallel)
gefragt werden.

| Baustein | Datei |
|---|---|
| Registrierung | `engine/remote/RemoteField.java` |
| Remote-Loader: Aufruf starten | `engine/remote/RemoteFieldLoaders.java` |
| Offene Aufrufe, gemeinsames Warten, Timeout, Fehler | `engine/remote/RemoteCalls.java` |
| Warten nach jeder Ebene | `engine/remote/RemoteAwareDataLoaderRegistry.java` |
| Objekttyp aus dem Record | `GqlTypeFactory.buildRemoteType` |
| Resolver | `GenericDataFetchers.remote` |
| Simulierte Services | `remote/SimulatedRemoteService.java`, `remote/trust/…`, `remote/presence/…` |

### Was im Schema entsteht

```graphql
type Profile {
  # ... alle lokalen Felder wie bisher
  "Aus Trust-Service nachgeladen (ein Aufruf pro Ebene). Filter auf level, score, note werden an Trust-Service delegiert. Nicht sortierbar."
  trust: TrustInfo
  "Aus Presence-Service nachgeladen (ein Aufruf pro Ebene). Filter auf status werden an Presence-Service delegiert. Nicht sortierbar."
  presence: PresenceInfo
}

"Gehoert Trust-Service - wird nicht aus der eigenen Datenbank gelesen."
type TrustInfo {
  level: TrustLevel
  score: BigDecimal
  openReports: Int
  lastReviewedOn: Date
  note: String
}
```

`PresenceInfo` (`status`, `typing`, `device`) entsteht genauso. `ProfileOrderBy`
bekommt **weder** `trust` **noch** `presence`. Welche fremden Felder filterbar sind,
legt der Owner fest – das steht in [Weg 2](#weg-2-filter-an-den-owner-delegieren-lauffähig-im-beispiel).
Die Grenzen sind im Schema sichtbar und damit auch in GraphiQL und im Codegen der
Clients.

### Ein Request, zwei Datenquellen

```graphql
query TrustBadge {
  matchLatestVersions(order: { matchedAt: DESC }) {
    objectRefId
    profileA { displayName trust { level } }
    profileB { displayName trust { level openReports } }
  }
}
```

Im Log steht genau ein Aufruf:

```
Trust-Service: 1 Aufruf fuer 3 Profile [PROF000005, PROF000001, PROF000003]
```

(Die Reihenfolge der IDs ist nicht festgelegt – der DataLoader übergibt eine Menge.)

Mara ist in beiden Matches `profileA`, der DataLoader fragt sie trotzdem nur einmal
an. Profile aus der eigenen DB und Vertrauensstufen aus dem Trust-Service kommen in
einer Antwort zurück. Für den Client ist nicht erkennbar, woher welches Feld stammt.

### Mehrere Services auf einer Ebene: parallel

```graphql
query ProfileStatus {
  profileLatestVersions(order: { displayName: ASC }) {
    displayName
    trust    { level }
    presence { status typing }
  }
}
```

`trust` und `presence` liegen auf derselben Ebene und hängen nicht voneinander ab.
Beide Aufrufe sind **gleichzeitig** unterwegs, die Ebene dauert so lange wie der
langsamere Service – nicht so lange wie beide zusammen:

Gemessen mit `./gradlew bootRun` (simulierte Latenz Trust 120 ms, Presence 80 ms;
Median aus fünf Läufen, Profile-Liste ohne Remote-Felder ≈ 10 ms):

| Query auf `profileLatestVersions` | Antwortzeit |
|---|---|
| nur `trust` | ≈ 132 ms |
| nur `presence` | ≈ 92 ms |
| `trust` und `presence` – parallel (jetzt) | ≈ 132 ms |
| `trust` und `presence` – nacheinander (erste Version, rechnerisch) | ≈ 210 ms |

Im Log starten beide Aufrufe in derselben Millisekunde, auf zwei verschiedenen
virtuellen Threads:

```
12:38:56.772 [virtual-97] Trust-Service: 1 Aufruf fuer 3 Profile [...]
12:38:56.772 [virtual-98] Presence-Service: 1 Aufruf fuer 3 Profile [...]
```

Bei drei, vier Services auf einer Ebene wächst der Unterschied entsprechend: Die
Wartezeit einer Ebene ist das **Maximum** der Latenzen, nicht die Summe.

So funktioniert es:

```mermaid
sequenceDiagram
    participant G as graphql-java (Request-Thread)
    participant R as RemoteAwareDataLoaderRegistry
    participant T as Trust-Service
    participant P as Presence-Service
    G->>R: dispatchAll() für Ebene 2
    R->>T: Aufruf starten (virtueller Thread)
    R->>P: Aufruf starten (virtueller Thread)
    Note over R: alle Loader der Ebene ausgelöst
    T-->>R: Antwort
    P-->>R: Antwort
    R->>G: Futures abschließen – auf dem Request-Thread
    G->>G: Query fortsetzen, nächste Ebene
```

1. Ein Remote-Loader **startet** seinen Aufruf nur und gibt ein offenes Future
   zurück (`RemoteFieldLoaders`).
2. Der gestartete Aufruf wird pro Request vermerkt (`RemoteCalls`).
3. graphql-java löst die Loader einer Ebene über `dispatchAll()` aus. Die eigene
   `RemoteAwareDataLoaderRegistry` ruft erst `super.dispatchAll()` auf – jetzt sind
   alle Aufrufe der Ebene unterwegs – und wartet dann gemeinsam auf sie.
4. Erst wenn alle Antworten (oder Timeouts) da sind, werden die Futures
   abgeschlossen, und zwar **auf dem Request-Thread**. Die Query läuft dort weiter,
   wo Transaktion und EntityManager der lokalen DataLoader liegen.

**Was sich nicht parallelisieren lässt:** Remote-Felder auf **verschiedenen** Ebenen.
`profile { photos { moderation { … } } }` kann einen Moderations-Service erst fragen,
wenn die Fotos geladen sind – die IDs entstehen erst aus diesem Ergebnis. Das liegt
an den Daten, nicht an der Implementierung. Bei einem komplexen Datenmodell bestimmt
deshalb die **Zahl der Ebenen mit Remote-Feldern** die Latenz, nicht die Zahl der
Remote-Felder.

### Stichtag: lokale und fremde Daten laufen auseinander

```graphql
query TrustHistory {
  earlier: profileById(objectRefId: "PROF000001", date: "2025-12-01") { city verifiedAt trust { level note } }
  today:   profileById(objectRefId: "PROF000001")                     { city verifiedAt trust { level note } }
}
```

`earlier` zeigt Hamburg und `verifiedAt: null`, das ist der lokale Stand vom
Dezember. Zugleich zeigt es `trust.level: HIGH` und „Ausweis geprueft“, also den
**heutigen** Stand des Trust-Service. Der Service kennt keine Historie.

Die Engine reicht den Stichtag über `RemoteCallContext.date()` an den Loader weiter.
Was der fremde Service damit macht, liegt nicht in ihrer Hand. Für Trust-&-Safety-
Reviews („wie sah das Profil vor der Meldung aus?“) ist genau dieser Fall heikel.
Lösen kann ihn nur der Owner selbst, indem er Stände zu einem Stichtag anbietet.
Das ist eine Anforderung an seine API, keine an die Orchestrierung.

### Ausfall: Teilergebnis statt Totalausfall

```graphql
query ProfileCard {
  profileById(objectRefId: "PROF000001") {
    displayName
    city
    photos { url }
    trust { level openReports }
  }
}
```

Ist der Trust-Service nicht erreichbar oder antwortet er nicht innerhalb des
Timeouts, bleibt der Rest der Antwort erhalten:

```json
{ "errors": [ { "message": "Exception while fetching data (/profileById/trust) : Trust-Service nicht erreichbar: 503 Service Unavailable",
                "path": ["profileById", "trust"] } ],
  "data": { "profileById": { "displayName": "Mara", "city": "Leipzig",
                             "photos": [ { "url": "https://cdn.example.test/mara-1.jpg" }, { "url": "https://cdn.example.test/mara-2.jpg" } ],
                             "trust": null } } }
```

Das ist ein echter Vorteil von GraphQL gegenüber einem REST-Aggregat, das bei einem
fehlenden Teil oft komplett mit 502 antwortet. Der Client muss aber damit rechnen,
dass `trust` `null` sein kann, obwohl das Profil existiert.

Ausfälle sind dabei **pro Service** getrennt: Ist nur der Trust-Service weg, liefert
`ProfileStatus` weiterhin `presence` für alle Profile, nur `trust` ist `null`.

### Designentscheidungen

- **Remote-Aufrufe einer Ebene laufen parallel, abgeschlossen wird auf dem
  Request-Thread.** Siehe [oben](#mehrere-services-auf-einer-ebene-parallel) und
  [Entscheidung E2](#e2-remote-aufrufe-parallel-mit-gemeinsamem-warten-pro-ebene).
- **Der Timeout zählt ab dem Start des Aufrufs**, nicht ab dem Beginn des Wartens.
  Fällt ein Service aus, kostet das höchstens seinen eigenen Timeout – parallel zu
  den anderen, nicht zusätzlich.
- **Sicherheitsnetz gegen hängende Requests.** Jedes Future hat zusätzlich ein
  `orTimeout` (Timeout + 1 s). Das greift nur, wenn das gemeinsame Warten nie
  aufgerufen wird – etwa wenn jemand in graphql-java das DataLoader-Chaining
  aktiviert, das `dispatchAll()` umgeht. Dann gibt es einen Fehler im Feld statt eines
  Requests, der nie endet.
- **Die DB-Transaktion bleibt während des Aufrufs offen.** Der Controller ist
  `@Transactional`, also hält jeder Remote-Aufruf eine DB-Verbindung fest. Für ein
  Beispiel ist das in Ordnung. In Produktion würde man die Request-Transaktion
  durch kurze `readOnly`-Transaktionen pro Loader ersetzen.
- **`AccessPolicy` reicht nicht über die Grenze.** Sie erzeugt ein SQL-Predicate.
  Der fremde Service muss selbst prüfen. Den Kontext dafür (z. B. das Token des
  Aufrufers) bekommt der Loader über `RemoteCallContext.graphQLContext()`.
- **Guardrails zählen ein Remote-Feld wie jedes andere.** Ein Remote-Aufruf ist
  aber teurer als ein Join. Eine Gewichtung in `Guardrails.maxComplexity` wäre der
  nächste Schritt.

---

## Weg 2: Filter an den Owner delegieren (lauffähig im Beispiel)

Ein Filter auf ein fremdes Feld wird nicht in SQL übersetzt, sondern als **Frage an
den Owner** gestellt: „Welche Profile erfüllen `level = HIGH`?“ Der Owner wertet
sie mit seinen eigenen Regeln und Berechtigungen aus und liefert IDs zurück. Die
Engine setzt sie als `objectRefId IN (…)` in den lokalen Filter ein und rechnet den
Rest wie gewohnt in SQL, einschließlich `order` nach lokalen Feldern und
`limit`/`offset`.

```mermaid
sequenceDiagram
    participant C as Client
    participant O as Orchestrierung (Engine)
    participant T as Trust-Service (Owner)
    participant P as Presence-Service (Owner)
    C->>O: profileLatestVersions(filter: { OR: { trust: {...}, presence: {...} } })
    Note over O: Planungslauf: Teilbäume je Owner sammeln
    par parallel
        O->>T: search(Teilbaum Trust)
        O->>P: search(Teilbaum Presence)
    end
    T-->>O: [PROF000001]
    P-->>O: [PROF000003]
    Note over O: Ausführungslauf: SQL mit objectRefId IN (...) OR objectRefId IN (...)
    O->>T: batchGet(Treffer) für die Anzeige, wie Weg 1
    O-->>C: eine Antwort
```

### Warum das die Datenhoheit wahrt

- **Der Owner entscheidet, welche Felder filterbar sind.** Nur die erscheinen im
  Filter-Typ. `openReports` ist anzeigbar und trotzdem nicht filterbar.
- **Der Owner prüft das selbst noch einmal.** Auch wenn die Orchestrierung ein Feld
  durchließe, lehnt seine Suche es ab.
- **Der Owner wendet seine Berechtigungen auf die Suche an**, nicht erst auf die
  Anzeige. Den Kontext dafür (z. B. das Token des Aufrufers) bekommt die Suche über
  `RemoteCallContext`.
- **Die Daten liegen nirgends sonst.** Es gibt keine Kopie, die veraltet, die
  gelöscht werden muss oder die jemand „korrigiert“.

### Konfiguration

Zu Weg 1 kommen pro Remote-Feld zwei Zeilen dazu – die Such-API des Owners mit den
Feldern, die er freigibt, und die Obergrenze für Treffer:

```java
.remoteField(RemoteField.of("trust", TrustInfo.class)
        .source("Trust-Service")
        .loader((profileIds, context) -> trustService.batchGet(profileIds))
        .filterable((filter, limit, context) -> trustService.search(filter, limit),
                "level", "score", "note")
        .maxIds(1_000)
        .build())
.remoteField(RemoteField.of("presence", PresenceInfo.class)
        .source("Presence-Service")
        .loader((profileIds, context) -> presenceService.batchGet(profileIds))
        .filterable((filter, limit, context) -> presenceService.search(filter, limit),
                "status")
        .maxIds(1_000)
        .build())
```

Daraus entsteht im Schema:

```graphql
input ProfileFilter {
  # ... alle lokalen Felder und Relationen wie bisher
  trust: TrustInfoFilter
  presence: PresenceInfoFilter
}

"Wird komplett an Trust-Service delegiert. Enthaelt nur die Felder, die der Service zum Filtern freigibt."
input TrustInfoFilter {
  level: TrustLevelFilterLookup
  score: DecimalFilterLookup
  note: StringFilterLookup
  AND: TrustInfoFilter
  OR: TrustInfoFilter
  NOT: TrustInfoFilter
}
```

| Baustein | Datei |
|---|---|
| Such-API des Owners | `engine/remote/RemoteSearch.java` |
| Freigabe, `maxIds` | `RemoteField.filterable(…)`, `RemoteField.maxIds(…)` |
| Filter-Typ aus den freigegebenen Feldern | `GqlTypeFactory.buildRemoteFilterType` |
| Teilbäume sammeln, Treffer bereitstellen | `engine/remote/RemoteFilterPlan.java` |
| Planungs- und Ausführungslauf | `EntityQueryService.resolveRemoteFilters` |
| Owner parallel fragen, Obergrenze, Fehler | `engine/remote/RemoteFilterSearch.java` |
| Teilbaum → `IN`, Wildcard → Owner | `FilterEngine.remoteIn`, `FilterEngine.collectWildcard` |
| Suche im simulierten Owner | `remote/InMemoryFilter.java`, `SimulatedRemoteService.searchIds` |

### Lokal und fremd zusammen

```graphql
query TrustedInHamburg {
  profileLatestVersions(filter: {
    city: { iExact: "hamburg" }
    trust: { score: { gte: 0.7 } }
  }) {
    displayName
    city
    trust { score }
  }
}
```

Liefert Jonas. Der Trust-Service bekommt nur seinen Teilbaum (`score ≥ 0.7`) und
antwortet mit Mara (0.92) und Jonas (0.71); erst die lokale Bedingung `city` in SQL
lässt Jonas übrig. Der Client merkt von der Aufteilung nichts.

### `OR` über zwei Owner – parallel gefragt

```graphql
query TrustedOrAway {
  profileLatestVersions(
    filter: { OR: { trust: { level: { exact: HIGH } }, presence: { status: { exact: AWAY } } } }
    order: { displayName: ASC }
  ) {
    displayName
    trust { level }
    presence { status }
  }
}
```

Liefert Jonas (abwesend) und Mara (Vertrauensstufe HIGH). Die beiden Teilbäume
gehen gleichzeitig an Trust- und Presence-Service; im Log:

```
Presence-Service: Suche {status={exact=AWAY}} -> 1 Treffer [PROF000003]
Trust-Service: Suche {level={exact=HIGH}} -> 1 Treffer [PROF000001]
```

### `NOT`

```graphql
query NotLowTrust {
  profileLatestVersions(filter: { NOT: { trust: { level: { exact: LOW } } } }, order: { displayName: ASC }) {
    displayName
  }
}
```

Liefert Jonas und Mara – alle außer Sam. Das funktioniert, weil der Owner seine
**vollständige** Treffermenge liefert (siehe [Verknüpfungen](#verknüpfungen-und-ihre-grenzen)).

### Filter in einer Relation

```graphql
query MatchesWithLowTrust {
  matchLatestVersions(filter: { profileB: { trust: { level: { exact: LOW } } } }) {
    profileA { displayName }
    profileB { displayName trust { level openReports } }
  }
}
```

Liefert das Match von Mara mit Sam. Der Teilbaum steckt hier in der Relation
`profileB`; das `IN` landet in deren `EXISTS`-Subquery – ohne eigenen Code dafür.

### Volltextsuche über freigegebene Felder

```graphql
query TrustWildcard {
  profileLatestVersions(filter: { WILDCARDFILTER: { iContains: "ausweis" } }) {
    displayName
    trust { note }
  }
}
```

Liefert Mara – ihre Prüfnotiz lautet „Ausweis geprueft, …“. Der Wildcard-Filter
durchsucht die selektierten Felder: lokale in SQL, `trust { note }` beim Owner, als
`{ OR: { note: { iContains: "ausweis" } } }`. Beide Treffermengen werden per `OR`
verknüpft.

Das gilt nur für Textfelder, die der Owner **freigibt**:

```graphql
query DeviceWildcard {
  profileLatestVersions(filter: { WILDCARDFILTER: { iContains: "ios" } }) {
    displayName
    presence { device }
  }
}
```

Liefert `[]`, obwohl Mara per iOS online ist. `device` ist nicht freigegeben, also
wird es nicht durchsucht – sonst ließe sich über die Suche herausfinden, was der
Owner nicht filterbar machen wollte.

### Nicht freigegebene Felder sind nicht filterbar

```graphql
query ReportFilter {
  profileLatestVersions(filter: { trust: { openReports: { gt: 0 } } }) {
    displayName
  }
}
```

Wird schon bei der Validierung abgelehnt, bevor irgendein Service gefragt wird:

```
Validierungsfehler (WrongType@[profileLatestVersions]) : Argument 'filter.trust' mit Wert '...'
enthält ein Feld nicht in 'TrustInfoFilter': 'openReports'
```

(graphql-java lokalisiert Validierungsfehler nach der Locale der JVM – auf einem
englischen System lautet die Meldung „contains a field not in …“.)

### Ausfall und zu viele Treffer: die Query scheitert

Ist der Trust-Service bei `TrustedInHamburg` nicht erreichbar, gibt es **kein**
Teilergebnis:

```json
{ "errors": [ { "message": "Exception while fetching data (/profileLatestVersions) : Suche im Trust-Service fehlgeschlagen: 503 Service Unavailable",
                "path": ["profileLatestVersions"] } ],
  "data": { "profileLatestVersions": null } }
```

Anders als beim Anzeigen (Weg 1) wäre ein Teilergebnis hier falsch: Ohne die
Treffer des Owners stimmt die Trefferliste nicht. Dasselbe gilt, wenn der Owner
mehr als `maxIds` Treffer meldet – die Engine fragt dafür einen Treffer mehr an, als
sie erlaubt, und bricht dann mit „Filter auf trust trifft mehr als 1000 Objekte im
Trust-Service – bitte weiter einschränken“ ab, statt ein abgeschnittenes Ergebnis
zu liefern.

### Die eigentliche Schwierigkeit: die Größe der ID-Mengen

„Alle Profile mit `level = LOW`“ können Millionen sein. Eine `IN`-Liste dieser Größe
überträgt niemand. Zwei Richtungen sind möglich:

| Richtung | Wann | Ablauf | Stand |
|---|---|---|---|
| **Owner zuerst** | fremder Filter ist selektiv (`level = HIGH`, wenige Treffer) | Owner liefert IDs → lokales SQL mit `IN` | umgesetzt, mit `maxIds` |
| **Lokal zuerst** | lokaler Filter ist selektiv (`city = hamburg`) | lokale Kandidaten-IDs → Owner: „welche davon erfüllen `level = HIGH`?“ | Ausbaustufe |

Bei „lokal zuerst“ gilt zusätzlich: `limit`/`offset` darf erst **nach** der Antwort
des Owners angewendet werden. Sonst fehlen Treffer, die außerhalb der ersten
Seite lagen.

### Verknüpfungen und ihre Grenzen

Weil „Owner zuerst“ die vollständige Treffermenge liefert, ist jeder Teilbaum ein
gewöhnliches Predicate – die Filter-Engine verknüpft es wie jedes andere:

- **`AND`** mit lokalen Filtern: ✅ (`TrustedInHamburg`).
- **`OR`**, auch über zwei Owner: ✅ (`TrustedOrAway`).
- **`NOT`**: ✅ als `NOT IN` (`NotLowTrust`) – aber nur, solange der Owner seine
  Treffermenge nicht wegen Berechtigungen kürzt. Sonst wird aus „nicht LOW“
  unbemerkt „nicht LOW oder nicht sichtbar“. Das ist Teil des Vertrags mit dem
  Owner.
- **Relationen**: ✅ (`MatchesWithLowTrust`).
- **`WILDCARDFILTER`**: ✅ über freigegebene Textfelder (`TrustWildcard`).
- **Sortieren nach einem fremden Feld**: ❌ – Ausbaustufe, nur bei begrenzten
  Treffermengen sinnvoll lösbar.
- **Stichtag**: ❌ – die Suche bekommt ihn über `RemoteCallContext`, aber keiner der
  simulierten Owner kennt eine Historie.
- **Konsistenz**: ❌ Lokale DB und Owner werden zu zwei leicht verschiedenen
  Zeitpunkten gelesen. Es gibt keinen gemeinsamen Snapshot.

### Was der Owner dafür anbieten muss

Die Delegation verschiebt Arbeit zum Owner. Das ist gewollt, denn er hat die Hoheit,
aber es ist ein Vertrag, den sein Team bauen und betreiben muss:

1. **Eine Such-API** in der gemeinsamen Filtersprache, die IDs liefert – im besten
   Fall die generische Engine selbst. Im Beispiel übernimmt das `InMemoryFilter`:
   dieselben Lookups und dieselbe Bool-Logik, ausgewertet auf den Daten im Speicher.
2. **Die Liste filterbarer Felder** als Teil des Vertrags, versioniert – im Beispiel
   steht sie auf beiden Seiten (`RegistryConfig` und `SimulatedTrustService.FILTERABLE`).
3. **Berechtigungsprüfung auf der Suche**, mit dem weitergereichten Token des
   Clients (`RemoteCallContext.graphQLContext()`).
4. **Optional:** Stichtag, geordnete Treffer, Zählen. Jede dieser Fähigkeiten
   schaltet in der Orchestrierung eine Ausbaustufe frei.

### Der Preis

- **Ein Ausfall des Owners lässt Queries mit seinem Filter scheitern** (siehe oben).
- **Zwei Aufrufe pro Query** mit fremdem Filter und fremder Anzeige: einer für die
  Suche, einer für die Anzeige.
- **Der Filter wird zweimal aufgebaut** – einmal trocken zum Planen, einmal echt.
  Das kostet Mikrosekunden (es wird dabei kein SQL ausgeführt) und erspart eine
  zweite Implementierung der Bool-Logik.
- **Last beim Owner.** Jeder Client-Filter wird zu einer Suche in seinem Service.
  Die Guardrails der Orchestrierung zählen das bisher nicht gesondert.

### Implementierungsplan: Filterbaum zerlegen

Grundidee: Alle Services sprechen **dieselbe Filtersprache** – im Zielbild betreibt
jeder Owner selbst diese generische Engine. Die Orchestrierung muss dann nichts pro
Service übersetzen, sie zerlegt nur den Filterbaum nach Ownern:

```graphql
profileLatestVersions(filter: {
  city: { iExact: "hamburg" }                                      # lokal → SQL
  trust: { level: { exact: HIGH }, note: { iContains: "ausweis" } } # Teilbaum Trust
  OR: { presence: { status: { exact: ONLINE } } }                  # Teilbaum Presence
})
```

Jeder Teilbaum, der einem Owner gehört, geht **komplett** an dessen Suche. Die
Teilbäume verschiedener Owner laufen parallel. Die zurückgelieferten ID-Mengen
werden zu `objectRefId IN (…)` und an genau der Stelle in den SQL-Filter eingesetzt,
an der der Teilbaum stand. Dadurch gelten `AND`, `OR`, `NOT` und Relationen ohne
eigene Logik – die Filter-Engine verknüpft das `IN` wie jedes andere Predicate.

Voraussetzung dafür ist die Richtung **„Owner zuerst“** mit vollständiger
Treffermenge. Sie ist im ersten Schritt die einzige; eine Obergrenze (`maxIds`)
schützt vor zu großen Mengen.

| Phase | Inhalt | Status |
|---|---|---|
| **A – Freigabe & Schema** | `RemoteField` bekommt `filterable(search, felder…)` und `maxIds`. Aus den freigegebenen Record-Komponenten entsteht `TrustInfoFilter` (mit `AND`/`OR`/`NOT`), angehängt als `ProfileFilter.trust`. Nicht freigegebene Felder (z. B. `openReports`) bleiben unfilterbar. | ✅ umgesetzt |
| **B – Planen** | Die Filter-Engine läuft zweimal: Ein **Planungslauf** baut den Filter trocken auf und sammelt dabei jeden fremden Teilbaum (`RemoteFilterPlan`). Der **Ausführungslauf** baut das echte SQL mit den aufgelösten ID-Mengen. Beide Läufe nutzen denselben Code – Relationen, Bool-Logik und Wildcard werden nicht doppelt implementiert. | ✅ umgesetzt |
| **C – Ausführen** | `RemoteFilterSearch` fragt alle Owner eines Requests **parallel** (virtuelle Threads), Timeout ab Start, gleiche Teilbäume nur einmal. Mehr als `maxIds` Treffer oder ein Ausfall des Owners **brechen die Query ab** – ein Teilergebnis wäre hier falsch. | ✅ umgesetzt |
| **D – Wildcard** | Selektierte, **freigegebene** String-Felder eines Remote-Felds (`trust { note }`) gehen als `{ OR: { note: { iContains: … } } }` an den Owner; seine Treffer werden mit dem lokalen Wildcard per `OR` verknüpft. | ✅ umgesetzt |
| **E – Owner simulieren** | Die simulierten Services werten dieselbe Filtersprache in-memory aus – als Stellvertreter für die generische Engine im Owner – und prüfen die Freigabe selbst noch einmal. | ✅ umgesetzt |
| **F – Tests & Doku** | Beispiele in diesem Dokument, wörtlich getestet: Filter, `OR` über zwei Owner (parallel), `NOT`, Filter in einer Relation, Wildcard, abgelehntes Feld, Ausfall, Obergrenze. | ✅ umgesetzt |

**Ausbaustufen danach** (nicht Teil dieses Schritts):

1. **Richtung nach Treffermenge** (Idee 2): Zähl-Endpunkte auf beiden Seiten, die
   kleinere Menge zuerst; „lokal zuerst“ schickt Kandidaten-IDs an den Owner.
2. **Einstieg beim Owner** (Idee 3): Listen-Endpunkte für fremde Typen
   (`trustInfoList(filter: …) { profile { … } }`), damit der Client selbst bei der
   wählerischsten Seite beginnt.
3. **Sortierung nach fremdem Feld** (Idee 4): nur bei begrenzter Treffermenge über
   die vom Owner gelieferte Reihenfolge.
4. **Echter Owner-Endpunkt**: ein HTTP-Client, der den Teilbaum als GraphQL-Query an
   die generische Engine des Owners schickt (`…LatestVersions(filter: …) { objectRefId }`),
   und Übernahme von `TrustInfoFilter` aus dessen Introspection.
5. **Stichtag** weiterreichen, sobald ein Owner Stände zu einem Stichtag liefert.

---

## Randnotiz: Kopie über Events (nur mit Vertrag des Owners)

Technisch naheliegend wäre es, die Daten des Trust-Service per Events in eine lokale,
event-gesourcte Entity zu replizieren. Dann wäre `trust` eine normale Relation, und
alle Fähigkeiten aus der Tabelle oben – Filter, Sortierung, Wildcard, Stichtag –
funktionierten ohne Einschränkung. Die Engine passt dazu sogar gut: Eingehende
Events wären `update`-Events mit `validFrom`, und weil Snapshots aus allen Events
neu aufgebaut werden, landen auch verspätete Events an der richtigen Stelle.

**Mit dem Leitprinzip dieses Kapitels ist das nicht vereinbar.** Mit der Kopie
entscheidet die Orchestrierung, wer was sieht, wonach gefiltert werden darf und wie
aktuell der Stand ist. Die Verantwortung für das, was ausgespielt wird, wandert vom
Owner weg.

Zulässig wäre es nur, wenn der **Owner selbst** einen Lesestand als Vertrag
veröffentlicht und verantwortet – mit festgelegten Feldern, Aktualitätszusage
(SLA), Löschweitergabe (DSGVO) und Regeln, wer filtern darf. Ob eine Organisation
das erlaubt, ist eine Governance-Frage, keine technische. Für das Dating-Beispiel
mit sensiblen Trust-&-Safety-Daten ist es **nicht** die Empfehlung.

---

## Weg 3: Federation (Ausblick)

Jeder Service betreibt sein eigenes GraphQL-Schema, gern auch mit dieser Engine,
und ein Gateway setzt die Schemas zu einem zusammen. Das passt zur Datenhoheit:
Jeder Teil des Schemas wird von seinem Owner ausgeliefert.

Die Frage nach servicesübergreifenden Filtern löst es aber nicht von selbst. Ein
Gateway, das `trust: { level: HIGH }` an den Trust-Service weiterreicht und das
Ergebnis mit lokalen Filtern verschneidet, macht genau das, was Weg 2 beschreibt –
nur an anderer Stelle. Federation ist damit eine Entscheidung darüber, **wo** die
Delegation stattfindet, nicht ein Ersatz dafür. Die Engine bräuchte dafür außerdem
einen `_entities`-Resolver; der ließe sich auf den vorhandenen `byId`-DataLoader
abbilden.

---

## Entscheidungshilfe

Für jedes Feld, das einem anderen Service gehört:

| Frage | Ja → | Nein → |
|---|---|---|
| Wird es nur angezeigt? | Weg 1 | weiter |
| Muss danach gefiltert werden, und gibt der Owner das Feld zum Filtern frei? | Weg 2 | weiter |
| Muss in großen Mengen danach sortiert oder blätternd gesucht werden? | Anforderung an den Owner: eigene Such-Query, die der Client direkt nutzt | weiter |
| Wird ein Stand zu einem Stichtag gebraucht? | Anforderung an den Owner: Historie in seiner API | – |
| Gehört eine ganze Domäne einem anderen Team, mit eigenen Clients? | zusätzlich Weg 3 | – |

Für das Dating-Beispiel heißt das:

- **Vertrauensstufe auf der Profilkarte** – Weg 1.
- **„Zeig nur verifizierte Profile“** – Weg 2: Der Trust-Service gibt `level` zum
  Filtern frei, die Treffermenge (`HIGH`) ist klein genug für „Owner zuerst“.
- **„Wer hat offene Meldungen?“** – nicht über die Orchestrierung: `openReports` ist
  bewusst nicht freigegeben.
- **„Alle gemeldeten Profile, sortiert nach Anzahl der Meldungen“** – gehört gar
  nicht in die Orchestrierung. Das ist ein Trust-&-Safety-Werkzeug, und der
  Trust-Service bietet es selbst an.
- **„Schreibt gerade“ im Chat** – Weg 1 über den Presence-Service. Live-Daten, die nie
  gefiltert werden.

---

## Entscheidungen

Getroffene Entscheidungen zu diesem Szenario, mit Begründung – damit später
nachvollziehbar ist, warum es so gebaut ist und was dafür aufgegeben wurde.

### E1: Datenhoheit bleibt beim Owner – keine Kopien fremder Daten

*Stand: 2026-10-25*

- **Kontext:** Für Filter, Sortierung und Historie über fremde Felder läge eine
  lokale Kopie per Events nahe; die Engine passt technisch gut dazu.
- **Entscheidung:** Fremde Daten werden nicht in die Orchestrierung repliziert. Der
  Service, dem die Daten gehören, ist dafür verantwortlich, was davon ausgespielt
  wird – einschließlich der Frage, wonach gefiltert werden darf (Filtern ist auch
  Lesen). Fremde Felder werden angezeigt (Weg 1); Filter werden, wo nötig, an den
  Owner delegiert (Weg 2).
- **Konsequenzen:** Sortierung, Volltextsuche und Stichtag über Servicegrenzen gibt es
  nur, soweit der Owner sie anbietet. Ein Ausfall des Owners lässt delegierte Filter
  scheitern. Die Kopie über Events bleibt eine [Randnotiz](#randnotiz-kopie-über-events-nur-mit-vertrag-des-owners)
  für den Fall, dass ein Owner selbst einen Lesestand als Vertrag verantwortet.

### E2: Remote-Aufrufe parallel, mit gemeinsamem Warten pro Ebene

*Stand: 2026-10-25*

- **Kontext:** In der ersten Version wartete jeder Remote-Loader blockierend auf seine
  Antwort. Weil graphql-java die Loader einer Ebene nacheinander auslöst, addierten
  sich die Latenzen aller Remote-Felder einer Ebene. Bei einem Datenmodell mit
  mehreren fremden Services ist das nicht tragbar.
- **Betrachtete Optionen:**
  1. *Nacheinander, blockierend* (erste Version): einfach, aber Summe der Latenzen.
  2. *Starten, dann gemeinsam warten:* Remote-Loader starten nur; eine eigene
     `DataLoaderRegistry` wartet nach `dispatchAll()` auf alle Aufrufe der Ebene und
     schließt die Futures auf dem Request-Thread ab.
  3. *Vollständig asynchron:* `executeAsync`, keine Transaktion über den ganzen
     Request, kurze `readOnly`-Transaktionen pro Loader. Kein Thread wartet, auch
     über Ebenen hinweg nicht.
- **Entscheidung:** Option 2. Sie bringt den Großteil des Gewinns – die Wartezeit
  einer Ebene ist das Maximum statt der Summe – und lässt alles andere unverändert:
  Transaktion, EntityManager, Timeout pro Feld, Teilergebnis bei Ausfall. Die
  Änderung bleibt in `engine/remote`, dazu eine Zeile in `RelationDataLoaders`.
- **Konsequenzen:**
  - Ebenen bleiben nacheinander. Das liegt an den Daten und ändert auch Option 3 nicht.
  - Der Request-Thread wartet pro Ebene einmal. Für hohe Last bleibt Option 3 der
    nächste Schritt – dann aber als Umbau der ganzen Engine, nicht nur der
    Remote-Felder.
  - Die Lösung hängt an der Standard-Dispatch-Strategie von graphql-java
    (`dispatchAll()` pro Ebene, geprüft in graphql-java 26.1). Wer DataLoader-Chaining
    aktiviert, muss das gemeinsame Warten anders auslösen; bis dahin verhindert das
    Sicherheitsnetz (`orTimeout`) hängende Requests.

### E3: Filter über fremde Felder – Filterbaum zerlegen, Owner zuerst

*Stand: 2026-10-29*

- **Kontext:** Nach E1 liegen fremde Daten nur beim Owner. Ohne Filter über fremde
  Felder verliert das generische Schema auf Orchestrierungsebene einen Kern seines
  Versprechens („Filterkombinationen, an die niemand gedacht hat“).
- **Betrachtete Optionen:** pro Service handgeschriebene Such-Adapter; Zerlegen des
  Filterbaums bei gleicher Filtersprache (Idee 1); Richtung nach Treffermenge
  (Idee 2); Einstieg beim Owner (Idee 3); vom Owner betriebener Suchindex.
- **Entscheidung:** Idee 1 in der Richtung „Owner zuerst“: Teilbäume fremder Felder
  gehen komplett an den Owner, parallel; die ID-Mengen werden als `IN` in den lokalen
  Filter eingesetzt. Der Owner bestimmt, welche Felder filterbar sind, und wertet mit
  seinen eigenen Regeln aus. Ideen 2–4 sind Ausbaustufen.
- **Konsequenzen:**
  - `AND`, `OR`, `NOT`, Relationen und Wildcard funktionieren über Servicegrenzen –
    solange die Treffermenge des Owners vollständig und unter `maxIds` ist.
  - Ein Ausfall des Owners lässt Queries mit seinem Filter scheitern.
  - Kürzt ein Owner seine Treffermenge aus Berechtigungsgründen, wird `NOT`
    ungenau („nicht HIGH“ wird zu „nicht HIGH oder nicht sichtbar“). Das ist Teil
    des Vertrags mit dem Owner.
  - Sortierung nach fremden Feldern und Stichtag bleiben außen vor.
- **Umsetzung (2026-10-29):** Phasen A–F des
  [Implementierungsplans](#implementierungsplan-filterbaum-zerlegen) sind umgesetzt.
  Die Filter-Engine läuft dafür zweimal über denselben Code (planen, ausführen);
  der Owner wird in der Simulation durch `InMemoryFilter` vertreten.

---

## Grenzen der Beispielimplementierung

**Weg 1 – Anzeigen:**

- Ein Remote-Feld liefert genau ein Objekt pro Schlüssel, keine Liste. Der Werttyp
  ist ein flacher Record aus Skalaren und Enums.
- Kein Rückweg: Ein Remote-Typ kann nicht auf lokale Entities verweisen (z. B.
  `TrustInfo.reviewedBy → Profile`).
- Pro Feld gibt es einen Timeout, aber kein Retry und keinen Circuit-Breaker. Ein
  Ausfall kostet bei jedem Request die Wartezeit bis zum Timeout – parallel zu den
  anderen Services, aber eben jedes Mal.
- Remote-Felder auf verschiedenen Ebenen laufen nacheinander (siehe E2).
- Der DataLoader-Cache gilt pro Request. Er ist unabhängig vom Stichtag, wie bei
  den lokalen Relationen auch.

**Weg 2 – Filtern:**

- Nur die Richtung „Owner zuerst“. Ist der fremde Teilbaum wenig wählerisch, greift
  `maxIds` – auch wenn der lokale Teil die Menge stark einschränken würde.
- Kein Sortieren nach fremden Feldern, kein Stichtag beim Owner.
- Der Owner ist simuliert (`InMemoryFilter`). Ein echter Owner bräuchte einen
  HTTP-Client, der den Teilbaum als Query an seine generische Engine schickt.
- Die Liste filterbarer Felder steht doppelt – in der Registrierung und beim Owner.
  Im Zielbild käme sie aus der Introspection des Owner-Schemas.
- Die Treffer eines Owners werden pro Query gefragt, nicht pro Request
  zwischengespeichert. Zwei Top-Level-Felder mit gleichem fremden Filter fragen
  zweimal.

## Tests

`src/test/java/.../RemoteFieldIntegrationTest.java` liest die Queries dieses
Dokuments wörtlich und prüft sie über HTTP:

- **Weg 1:** ein Aufruf pro Ebene, Stichtags-Drift, Teilergebnis bei einem Ausfall –
  und dass Trust- und Presence-Aufruf sich zeitlich **überlappen**, also wirklich
  parallel laufen. Nacheinander ausgeführt wäre der eine beendet, bevor der andere
  beginnt; der Test hängt damit nicht an der Geschwindigkeit der Maschine.
- **Weg 2:** Filter-Typen nur mit freigegebenen Feldern, lokaler und fremder Filter
  zusammen, `OR` über zwei Owner (Suchen überlappen sich ebenfalls), `NOT`, Filter in
  einer Relation, Wildcard über freigegebene und nicht freigegebene Felder,
  abgelehntes Feld (im Schema **und** beim Owner selbst), Ausfall ohne Teilergebnis.

`src/test/java/.../engine/remote/RemoteFilterSearchTest.java` prüft ohne Spring die
Obergrenze (einer mehr angefragt, genau `maxIds` erlaubt, darüber Abbruch), dass
gleiche Teilbäume nur einmal gefragt werden, und den Timeout.
