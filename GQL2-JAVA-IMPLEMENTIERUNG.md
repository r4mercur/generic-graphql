# Beispielimplementierung – generisches GraphQL in Java/Spring Boot

Lauffähige Umsetzung des Konzepts aus [GQL2-JAVA.md](GQL2-JAVA.md) und
[GQL2-PRESENTATION.md](README.md), mit der Python-Implementierung
(`dgd-stammdaten-refactor`) als Referenz für die Semantik.

Beispieldomäne ist ein kleiner User-Service im Dating-Kontext der Präsentation:
`AppUser`, `Profile`, `Interest`, `ProfileInterest`, `Photo`, `Match`, `Message`.

## Starten

```bash
./gradlew bootRun
```

H2 in-memory, Demodaten werden beim Start über dieselbe Event-Mutation angelegt,
die auch ein Client benutzt – dadurch existiert sofort eine echte Historie.

- GraphQL-Endpoint: `POST http://localhost:8080/graphql`
- GraphiQL (Introspection, Autovervollständigung): <http://localhost:8080/graphiql.html>

## Die fachliche Konfiguration ist eine Datei

`config/RegistryConfig.java` – pro Domänenobjekt eine Zeile:

```java
registry.register(eventSourced(Profile.class, "profile").build());
registry.register(eventSourced(Match.class,   "match").build());
```

Daraus entstehen für jedes Objekt automatisch:

| Artefakt | Beispiel für `Profile` |
|---|---|
| Objekttyp | `Profile` |
| Filter-Input | `ProfileFilter` (inkl. `AND`/`OR`/`NOT`/`WILDCARDFILTER`) |
| OrderBy-Input | `ProfileOrderBy` |
| Mutation-Input | `ProfileInput` |
| Listen-Query | `profileLatestVersions(limit, offset, date, active, filter, order)` |
| Einzel-Query | `profileById(objectBezugsId, date, active)` |
| Verlauf | `profileTimestamps(objectBezugsId)`, `profileLog(objectBezugsId)` |
| Beziehungen | `profileRelations(objectBezugsId)` |
| Mutation | `profileEvent(data: ProfileInput!)` |
| DataLoader | einer je Relation, gebatcht pro Request |

Nicht event-gesourcte Entities (`AppUser`) bekommen `appUserList` und
`appUserById`, aber keine Historie und keine Event-Mutation.

## Zuordnung zur Architekturskizze

| Baustein aus GQL2-JAVA.md | Umsetzung |
|---|---|
| 1 – Lookup-Typen | `engine/types/LookupTypes.java` |
| 2 – Typgenerierung aus Metamodel | `engine/meta/MetaProvider.java`, `engine/types/GqlTypeFactory.java` |
| 3 – Rekursiver Predicate-Builder | `engine/filter/FilterEngine.java`, `engine/filter/LookupPredicates.java` |
| 4 – "Latest Version"-Scope | `engine/scope/LatestVersionScope.java` |
| 5 – DataLoader | `engine/dataloader/RelationDataLoaders.java` |
| Phase 1 – Entity-Registry | `engine/registry/GqlRegistry.java`, `GqlEntityRegistration.java` |
| Phase 8 – Generische Event-Mutation | `engine/mutation/EventMutationService.java` |
| Phase 9 – Permissions | `engine/scope/AccessPolicy.java` (Naht, Default erlaubt alles) |
| Guardrails ("Preis der Freiheit") | `config/GraphQLConfig.java` |

Und die Entsprechungen zur Python-Vorlage:

| Python (`schema_creator.py`) | Java |
|---|---|
| `GQLSchemaModelListItem` | `GqlEntityRegistration` |
| `GQLSchemaModelList` | `GqlRegistry` |
| `create_filter` / `create_orderby` / `create_types` / `create_inputs` | `GqlTypeFactory.build*Type` |
| `build_q_filter` | `FilterEngine.build` |
| `lookup_name_conversion_map` + Lookup-Zweig | `LookupPredicates.build` |
| `create_wildcard_filter` / `build_wildcard_or_objects` | `FilterEngine.buildWildcard` |
| `get_model_latest_version` | `LatestVersionScope.scopeToLatest` |
| `create_loader` / `get_dataloader_field` | `RelationDataLoaders` |
| `get_resolver` (latest/byobjectid/timestamps/log) | `GenericDataFetchers` |
| `mutations/stammdaten.py` | `EventMutationService` |
| `relations_finder` | `RelationsFinderService` |
| `globals()[type_name] = new_type` | `GraphQLTypeReference.typeRef(...)` |

## Zwei bewusste Abweichungen von der Skizze

### 1. Kein jOOQ für "latest version"

Die Skizze nennt das Postgres-`DISTINCT ON` als größten Reibungspunkt und
empfiehlt jOOQ als zusätzlichen Layer. Diese Implementierung geht einen
vierten Weg: eine korrelierte `NOT EXISTS`-Subquery nach dem Muster
*"es gibt keine neuere Zeile mit derselben objectBezugsId"*.

```java
// LatestVersionScope
Predicate base = snapshotPredicate(cb, root, date);       // snapshot, nicht gelöscht, <= Stichtag
Subquery<String> newer = query.subquery(String.class);    // ... und keine neuere Version
newer.where(cb.and(sameObject, otherIsSnapshot, isNewer(cb, other, root)));
return cb.and(base, cb.not(cb.exists(newer)));
```

Vorteile: reines JPA Criteria, kein zusätzlicher Layer, datenbankneutral
(läuft auf H2 wie auf Postgres) – und vor allem in korrelierten Subqueries
wiederverwendbar, was für den Relations-Zweig der Filter-Engine nötig ist.
Dort wäre eine Window-Function ohnehin unhandlich, weil sie in einer
korrelierten Subquery stecken müsste.

Die Sortierregel entspricht exakt `order_by(F("gueltig_von").desc(nulls_last=True))
.distinct("object_bezugs_id")`: es gewinnt das größte nicht-leere `gueltigVon`;
`null` heißt "schon immer" und gewinnt nur, wenn es keinen datierten Stand gibt.
Ein Gleichstand wird über die `objectId` aufgelöst, damit immer genau eine Zeile
übrig bleibt.

### 2. Snapshots werden neu aufgebaut statt fortgeschrieben

Die Python-Vorlage pflegt Snapshots inkrementell
(`__create_snapshot`, `__update_snapshot`, `update_snapshots`). Hier werden
nach jedem Event alle Snapshots der betroffenen `objectBezugsId` verworfen und
aus den Events neu gefaltet: chronologisch sortieren, Zustand Schritt für
Schritt anwenden, je Zeitpunkt eine Snapshot-Zeile schreiben.

Gleiche Semantik – ein nachträgliches `correct` wirkt automatisch auf alle
späteren Stände –, aber deutlich weniger Code und keine Sonderfälle. Für große
Objekthistorien wäre der inkrementelle Weg performanter; für eine
Beispielimplementierung zählt die Lesbarkeit mehr.

## Was das Beispiel zeigt (mit Query)

### Ein Request, beliebige Tiefe

```graphql
query MatchKarte {
  matchLatestVersions(limit: 5, order: { matchedAt: DESC }) {
    objectBezugsId
    matchedAt
    profileA { displayName city owner { username email } }
    profileB {
      displayName
      photos { url caption }
      profileInterests { intensity interest { name category } }
    }
    messages { body sender { displayName } }
  }
}
```

Vier Relationsebenen, ein Roundtrip, gebatcht per DataLoader.

### Filter über Relationen hinweg, an die niemand gedacht hat

```graphql
query GemeinsamesInteresse {
  profileLatestVersions(filter: {
    profileInterests: { interest: { name: { iExact: "Klettern" } } }
  }) { displayName city }
}
```

Auch in der Gegenrichtung (Reverse-Collection):

```graphql
query MatchesMitStichwort {
  matchLatestVersions(filter: { messages: { body: { iContains: "hafen" } } }) {
    objectBezugsId
  }
}
```

Und mit Bool-Logik:

```graphql
query Kombiniert {
  profileLatestVersions(
    filter: { OR:  { city: { iExact: "leipzig" }, heightCm: { gte: 180 } }
              NOT: { displayName: { exact: "Sam" } } }
    order: { displayName: ASC }
  ) { displayName city heightCm }
}
```

### Volltextsuche über genau die Felder, die der Client selektiert

```graphql
query Wildcard {
  profileLatestVersions(filter: { WILDCARDFILTER: { iContains: "klettern" } }) {
    displayName
    profileInterests { interest { name } }   # ohne diese Zeile: kein Treffer
  }
}
```

Der Wildcard-Filter liest das Selection-Set der Query und legt den Lookup über
alle angefragten String-Felder – inklusive der mitselektierten Relationen. Wer
`bio` nicht abfragt, durchsucht `bio` auch nicht.

### Historie ohne Sonder-Endpoint

```graphql
query Verlauf {
  profileTimestamps(objectBezugsId: "PROF000001")
  frueher: profileById(objectBezugsId: "PROF000001", date: "2025-12-01") { city bio verifiedAt }
  heute:   profileById(objectBezugsId: "PROF000001")                     { city bio verifiedAt }
  log:     profileLog(objectBezugsId: "PROF000001")
}
```

Der Stichtag eines Feldes gilt auch für alle Relationen, die darunter
nachgeladen werden.

### Ein Schreib-Pattern für alles

```graphql
mutation { interestEvent(data: {
    event: create, name: "Bouldern", category: OUTDOOR
  }) { result message objectBezugsId } }

mutation { profileEvent(data: {
    event: update, objectBezugsId: "PROF000003",
    gueltigVon: "2026-09-01", city: "Bremen"
  }) { result message } }

mutation { profileEvent(data: {
    event: clear, objectBezugsId: "PROF000005",
    gueltigVon: "2026-09-10", clearedFieldName: "bio"
  }) { result message } }
```

Inklusive Löschschutz – `deleted: true` wird abgelehnt, solange noch etwas auf
das Objekt zeigt, und die Antwort sagt, was:

```json
{ "result": "error",
  "message": "Objekt PROF000001 kann nicht geloescht werden, es haengen noch Objekte daran: PHOT000001, ...",
  "relations": { "reverseRelations": [ { "relation": "Photo", "id": "PHOT000001" } ] } }
```

### Guardrails

`gql.max-depth` (Default 12) und `gql.max-complexity` (Default 2000) in
`application.properties`. Zu tiefe Queries werden abgelehnt:

```json
{"errors":[{"message":"maximum query depth exceeded 14 > 12"}]}
```

**Introspection ist davon ausgenommen** (`config/Guardrails.java`). Die
Standard-Introspection-Query von GraphiQL hat Tiefe 13 und läuft sonst in
dasselbe Limit – mit dem Ergebnis, dass GraphiQL nur noch
*"Error fetching schema"* anzeigt und genau der Vorteil wegfällt, den die
Präsentation als Argument führt (immer aktuelles Schema, Codegen, Tooling).

Die Ausnahme greift nur, wenn **alle** Top-Level-Felder der Operation
Meta-Felder sind. Ein `__schema` neben einer teuren Datenabfrage hebelt die
Limits nicht aus – dafür gibt es einen eigenen Test. Introspection selbst ist
durch die Schemagröße begrenzt und berührt die Datenbank nicht.

Wer Introspection in Produktion gar nicht anbieten will, schaltet sie separat ab
(z. B. über Feldsichtbarkeit) – das ist eine andere Entscheidung als die
Query-Größe zu begrenzen.

## Bekannte Grenzen dieser Beispielimplementierung

- **Ein Stichtag pro Request für Relationen.** Top-Level-Felder können
  unterschiedliche `date`-Werte haben; die per DataLoader nachgeladenen
  Relationen folgen aber dem ersten Stichtag der Query, weil die Loader
  einmal pro Request gebatcht werden. Die Python-Vorlage verhält sich mit
  `info.context.request_params["date"]` genauso.
- **Kein Filter/OrderBy auf Relationsfeldern.** In Python nimmt das
  DataLoader-Feld selbst `filter`/`order`/`limit` entgegen. Hier sind
  Relationsfelder argumentlos; die Loader-Keys müssten sonst die
  Filterargumente mitführen.
- **`AccessPolicy` erlaubt alles.** Die Naht existiert und wird von jedem
  Zugriffspfad durchlaufen (Query, Subquery, DataLoader) – gefüllt ist sie
  nicht. Für einen echten Einsatz ist das der erste Schritt.
- **Sortierung über event-gesourcte Relationen** joint auf die create-Zeile,
  nicht auf die zum Stichtag gültige Version. Für stabile Felder (Namen, IDs)
  ist das richtig, für veränderliche nicht. Gilt in der Python-Vorlage ebenso.
- **Keine Authentifizierung**, kein Batching mehrerer Operations pro Request,
  keine Persisted Queries.

## Tests

`src/test/java/.../GenericSchemaTest.java` – 18 Tests gegen das laufende Schema:
Typgenerierung, Relationsfilter in beide Richtungen, AND/OR/NOT,
Wildcard-Verhalten, Stichtags-Semantik, create/update/clear, Löschschutz,
Tiefenlimit und die Introspection-Ausnahme.

```bash
./gradlew test
```
