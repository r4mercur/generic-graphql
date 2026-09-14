# GQL2 Prinzip in Java/Spring Boot – Skizze der generischen Filter-Engine

Ziel dieses Dokuments: zeigen, wie die generische Filter-Engine aus
`schema_creator.py` (`build_q_filter`, `create_filter`, Wildcard-Filter,
Relations-Subqueries) mit **graphql-java** + **JPA Criteria API** nachgebaut
werden könnte, ohne für jede Entity eigenen Filter-Code zu schreiben.

Kein fertiger Code zum Copy-Paste, sondern eine Architekturskizze mit
Implementierungsstrategie in Phasen.

## Grundidee (bleibt identisch zu Python)

- Eine Entity wird an einer zentralen Registry angemeldet (Äquivalent zu
  `GQLSchemaModelList.add(...)`).
- Aus dem **JPA-Metamodel** der Entity (`EntityType<T>.getAttributes()`)
  werden zur Laufzeit generiert:
  - GraphQL-Objekttyp
  - GraphQL-Filter-Inputtyp (inkl. `AND`/`OR`/`NOT`/Wildcard)
  - GraphQL-OrderBy-Inputtyp
  - GraphQL-Inputtyp für Mutationen
- Ein **einziger** rekursiver Predicate-Builder übersetzt den Filter-Input
  (den graphql-java als `Map<String,Object>` an den Resolver liefert) in ein
  JPA `Predicate` – analog zu `build_q_filter`, das eine Django-`Q`
  zurückgibt.
- FK-/Collection-Felder werden per DataLoader (`java-dataloader`, dieselbe
  Idee wie `graphql_sync_dataloaders`) aufgelöst.

Der wichtigste Unterschied zu Python: graphql-java liefert Input-Objekte
**nicht** als typisierte POJOs, sondern als `Map<String,Object>`
(verschachtelt für Sub-Objekte, `List<Object>` für Listen). Das kommt der
Python-Lösung sogar entgegen – man iteriert über die Map genauso generisch,
wie Python über `filters.__strawberry_definition__.fields` iteriert.

## Baustein 1: Lookup-Typen (Äquivalent zu `StrFilterLookup` etc.)

Statt `@strawberry.input`-Klassen werden `GraphQLInputObjectType`-Objekte
einmalig (nicht pro Entity!) registriert:

```java
GraphQLInputObjectType STRING_FILTER_LOOKUP = newInputObject("StringFilterLookup")
    .field(field("exact", GraphQLString))
    .field(field("iExact", GraphQLString))
    .field(field("contains", GraphQLString))
    .field(field("iContains", GraphQLString))
    .field(field("startsWith", GraphQLString))
    .field(field("endsWith", GraphQLString))
    .field(field("inList", list(GraphQLString)))
    .field(field("isNull", GraphQLBoolean))
    .field(field("regex", GraphQLString))
    .build();
```

Analog: `BOOLEAN_FILTER_LOOKUP`, `INT_FILTER_LOOKUP`, `LONG_FILTER_LOOKUP`
(für `BigInteger`/`Long`, Äquivalent zu `BigIntFilterLookup`),
`BIG_DECIMAL_FILTER_LOOKUP` (Äquivalent `FloatFilterLookup`/Money),
`DATE_FILTER_LOOKUP`, `DATETIME_FILTER_LOOKUP`, `WILDCARD_FILTER_LOOKUP`.

Eine `Map<Class<?>, GraphQLInputType> lookupTypeRegistry` bildet
Java-Typen auf diese Lookup-Typen ab (Äquivalent zu Pythons
`lookup_name_conversion_map` + dem `if type(field) in [...]`-Block in
`create_filter`).

## Baustein 2: Filter-Typ pro Entity generieren

```java
GraphQLInputObjectType buildFilterType(EntityType<?> entityType) {
    String name = entityType.getJavaType().getSimpleName() + "Filter";

    var builder = newInputObject().name(name);

    for (Attribute<?, ?> attribute : entityType.getAttributes()) {
        if (isExcluded(entityType, attribute)) continue;

        String gqlName = toCamelCase(attribute.getName());

        if (attribute.getPersistentAttributeType() == BASIC) {
            builder.field(field(gqlName, lookupTypeFor(attribute.getJavaType())));
        } else if (registry.isRegistered(attribute.getJavaType())) {
            // Relation zu einer anderen registrierten Entity:
            // Feldtyp per GraphQLTypeReference, da der Zieltyp evtl. noch
            // nicht existiert (loest Pythons globals()-Trick ab)
            String relatedFilterName = attribute.getJavaType().getSimpleName() + "Filter";
            builder.field(field(gqlName, GraphQLTypeReference.typeRef(relatedFilterName)));
        }
    }

    builder.field(field("AND", GraphQLTypeReference.typeRef(name)));
    builder.field(field("OR", GraphQLTypeReference.typeRef(name)));
    builder.field(field("NOT", GraphQLTypeReference.typeRef(name)));
    builder.field(field("WILDCARD_FILTER", WILDCARD_FILTER_LOOKUP));

    return builder.build();
}
```

`GraphQLTypeReference` löst genau das Problem, das Python mit der
`globals()[type_name] = new_type`-Manipulation löst: zirkuläre/vorwärts-
gerichtete Typreferenzen zwischen generierten Filtertypen. graphql-java
löst Referenzen erst beim finalen `GraphQLSchema.newSchema()`-Build auf.

`OrderBy`- und `Input`-Typen entstehen nach demselben Muster
(`create_orderby`, `create_inputs` in Python) – hier weggelassen, da
strukturell identisch.

## Baustein 3: Der rekursive Predicate-Builder (Kern-Äquivalent zu `build_q_filter`)

```java
class FilterEngine {

    Predicate build(CriteriaBuilder cb, AbstractQuery<?> query, Path<?> path,
                     EntityType<?> entityType, Map<String, Object> filterMap,
                     boolean linkOr, DataFetchingEnvironment env) {

        Predicate result = linkOr ? cb.disjunction() : cb.conjunction();

        for (var entry : filterMap.entrySet()) {
            String fieldName = entry.getKey();
            Object rawValue = entry.getValue();
            if (rawValue == null) continue; // GraphQL "nicht gesetzt"

            switch (fieldName) {
                case "AND" -> result = combine(cb, result,
                    build(cb, query, path, entityType, asMap(rawValue), false, env), linkOr);
                case "OR" -> result = combine(cb, result,
                    build(cb, query, path, entityType, asMap(rawValue), true, env), linkOr);
                case "NOT" -> result = combine(cb, result,
                    cb.not(build(cb, query, path, entityType, asMap(rawValue), false, env)), linkOr);
                case "WILDCARD_FILTER" -> result = combine(cb, result,
                    buildWildcard(cb, path, entityType, asMap(rawValue), env), linkOr);
                default -> {
                    Attribute<?, ?> attribute = entityType.getAttribute(toSnakeCase(fieldName));
                    Predicate p = (attribute.getPersistentAttributeType() == BASIC)
                        ? buildLookup(cb, path.get(attribute.getName()), asMap(rawValue))
                        : buildRelationSubquery(cb, query, path, attribute, asMap(rawValue), env);
                    result = combine(cb, result, p, linkOr);
                }
            }
        }
        return result;
    }

    private Predicate combine(CriteriaBuilder cb, Predicate acc, Predicate p, boolean linkOr) {
        return linkOr ? cb.or(acc, p) : cb.and(acc, p);
    }
}
```

Das ist strukturell dieselbe Schleife wie in `build_q_filter`
(`schema_creator.py:1008-1237`): Sonderfelder (`AND`/`OR`/`NOT`/Wildcard)
abfangen, sonst je nach Feldtyp (Basic-Feld vs. Relation) verzweigen, mit
`link_or`, das nach unten durchgereicht wird.

### `buildLookup` (Äquivalent zum `lookup_field != ""`-Zweig)

```java
Predicate buildLookup(CriteriaBuilder cb, Path<?> field, Map<String, Object> lookup) {
    Predicate p = cb.conjunction();
    for (var entry : lookup.entrySet()) {
        switch (entry.getKey()) {
            case "exact"      -> p = cb.and(p, cb.equal(field, entry.getValue()));
            case "iExact"     -> p = cb.and(p, cb.equal(cb.lower(field.as(String.class)),
                                       ((String) entry.getValue()).toLowerCase()));
            case "contains"   -> p = cb.and(p, cb.like(field.as(String.class), "%" + entry.getValue() + "%"));
            case "gt"         -> p = cb.and(p, cb.greaterThan(field, (Comparable) entry.getValue()));
            case "gtOrNull"   -> p = cb.and(p, cb.or(cb.greaterThan(field, (Comparable) entry.getValue()),
                                       cb.isNull(field)));
            case "isNull"     -> p = cb.and(p, (Boolean) entry.getValue() ? cb.isNull(field) : cb.isNotNull(field));
            case "inList"     -> p = cb.and(p, field.in((List<?>) entry.getValue()));
            // ... restliche Lookups analog lookup_name_conversion_map
        }
    }
    return p;
}
```

### `buildWildcard` (Äquivalent zu `build_wildcard_or_objects`/`create_wildcard_filter`)

Hier braucht man `DataFetchingEnvironment.getSelectionSet()`, um – genau
wie Python über `info.selected_fields` – herauszufinden, welche
String-Felder der Client überhaupt abgefragt hat:

```java
Predicate buildWildcard(CriteriaBuilder cb, Path<?> path, EntityType<?> entityType,
                         Map<String, Object> wildcardLookup, DataFetchingEnvironment env) {
    List<String> requestedStringFields = env.getSelectionSet().get().keySet().stream()
        .filter(f -> isBasicStringAttribute(entityType, f))
        .toList();

    Predicate wildcard = cb.disjunction();
    for (String field : requestedStringFields) {
        wildcard = cb.or(wildcard, buildLookup(cb, path.get(toSnakeCase(field)), wildcardLookup));
    }
    return wildcard;
}
```

### `buildRelationSubquery` (Äquivalent zum `subquery=True`-Zweig)

Der komplexeste Teil in Python: Filter über eine Relation hinweg muss auf
der "latest version"-Query der Zielentity laufen und wird als
`Exists(Subquery(...))` bzw. `__in`-Forward-Filter umgesetzt. In JPA
Criteria lässt sich das einheitlich als korrelierte `EXISTS`-Subquery
lösen (unabhängig davon ob Forward-FK oder Reverse-Collection):

```java
Predicate buildRelationSubquery(CriteriaBuilder cb, AbstractQuery<?> outerQuery, Path<?> path,
                                 Attribute<?, ?> attribute, Map<String, Object> subFilter,
                                 DataFetchingEnvironment env) {

    Class<?> relatedClass = attribute.getJavaType();
    EntityType<?> relatedType = metamodel.entity(relatedClass);

    Subquery<?> sub = outerQuery.subquery(relatedClass);
    Root<?> subRoot = sub.from(relatedClass);

    Predicate latestVersionScope = latestVersionResolver.scopeToLatest(cb, sub, subRoot, relatedType, env);
    Predicate filterMatch = build(cb, sub, subRoot, relatedType, subFilter, false, env);
    Predicate correlation = correlate(cb, path, attribute, subRoot);

    sub.select(subRoot).where(cb.and(latestVersionScope, filterMatch, correlation));

    return cb.exists(sub);
}
```

`correlate(...)` unterscheidet Forward-FK (`subRoot.get(pk) =
path.get(fkColumn)`) von Reverse-Collection
(`subRoot.get(inverseFkColumn) = path.get(pk)`) – das ist die Java-Version
der Fallunterscheidung `ReverseManyToOneDescriptor`/`ReverseOneToOneDescriptor`
vs. Forward-Relation in `build_q_filter` (Zeile 1205-1232).

## Baustein 4: "Latest Version" / Event-Sourcing-Scope

Python löst das mit `distinct("object_bezugs_id")` +
`order_by(..., F("gueltig_von").desc(nulls_last=True))` – eine reine
Postgres-`DISTINCT ON`-Query. **JPA Criteria kann kein `DISTINCT ON`.**
Das ist der größte Reibungspunkt beim Portieren. Drei Optionen:

1. **Native/JPQL-Subquery mit Window-Function**: `ROW_NUMBER() OVER
   (PARTITION BY object_bezugs_id ORDER BY gueltig_von DESC) = 1` als
   Subquery, in Criteria über `cb.function(...)` oder als natives SQL-
   Fragment eingebettet. Funktioniert, ist aber nicht mehr "reines" JPA.
2. **jOOQ statt/neben JPA** nur für diese eine Query-Schicht (jOOQ kennt
   `DISTINCT ON` und Window-Functions nativ) – Entities bleiben JPA,
   nur der Read-Pfad für "latest version" nutzt jOOQ.
3. **Materialized/denormalisierte "latest"-Tabelle oder View** pro
   Event-Sourcing-Entity, gepflegt per DB-Trigger oder Application-Event,
   dann ganz normale JPA-Query ohne Spezialfall.

Empfehlung: Start mit Option 2 (jOOQ nur für den Latest-Version-Layer),
weil sie am nächsten an der Python-Semantik bleibt und keine
Schema-Änderungen braucht.

## Baustein 5: DataLoader für FK/Collection-Felder

`java-dataloader` (dieselbe Library-Familie wie
`graphql_sync_dataloaders`) batcht die `objectBezugsId`-Keys pro Request
und lädt sie in einer Query – strukturell identisch zu
`create_loader`/`get_dataloader_field` (Zeile 1484-1663 in
`schema_creator.py`). Ein `DataLoaderRegistry` wird pro Entity einmal
registriert, `BatchLoader<String, T>` ruft dieselbe
"Latest-Version-Scope"-Logik aus Baustein 4 auf.

## Was 1:1 übertragbar ist vs. was Reibung erzeugt

| Bereich | Übertragbarkeit |
|---|---|
| Filter-/OrderBy-/Input-Typgenerierung aus Metamodel | Gut – JPA-Metamodel ist genauso reflektierbar wie `model._meta` |
| AND/OR/NOT/Wildcard-Filter-Logik | Gut – Map-basierte Inputs bei graphql-java passen gut zur rekursiven Verarbeitung |
| Relations-Subqueries | Gut, etwas mehr Boilerplate (explizite `EXISTS`-Subqueries statt Djangos `Subquery`-Sugar) |
| Vorwärtsreferenzen zwischen generierten Typen | Gut – `GraphQLTypeReference` statt `globals()`-Hack |
| DataLoader/N+1 | Gut – `java-dataloader` ist funktional äquivalent |
| "Latest version" per `DISTINCT ON` | **Reibung** – kein natives JPA-Äquivalent, braucht jOOQ/native SQL/View |
| Objektbasierte Permissions (`django-guardian`) | **Reibung** – kein direktes Spring-Äquivalent, müsste selbst gebaut werden (z. B. eigene ACL-Tabelle + Criteria-Join) |

## Implementierungsstrategie (Phasen)

**Phase 0 – Grundgerüst**
Spring Boot + Spring Data JPA + `graphql-java` (nicht Spring-GraphQL/DGS,
da diese eher schema-first/annotation-first ticken und der dynamischen
Typgenerierung im Weg stehen). `java-dataloader` einbinden.

**Phase 1 – Entity-Registry**
Äquivalent zu `GQLSchemaModelList`/`GQLSchemaModelListItem`: eine
Registrierungs-API (`registry.register(TechnischeEinheitWind.class,
"teWind", ...)`), die Include/Exclude-Listen und Permission-Namen pro
Entity hält.

**Phase 2 – Typgenerierung aus dem Metamodel**
`buildObjectType`, `buildFilterType`, `buildOrderByType`, `buildInputType`
pro registrierter Entity, inkl. Lookup-Typ-Mapping (Baustein 1+2). Ergebnis
zunächst nur gegen ein leeres `GraphQLSchema` testen (Introspection-Query
muss funktionieren).

**Phase 3 – Predicate-Builder**
`FilterEngine.build()` (Baustein 3) inkl. Unit-Tests pro Lookup-Typ,
zunächst nur für Basic-Felder ohne Relationen.

**Phase 4 – Relationen & Subqueries**
`buildRelationSubquery` + Korrelation Forward/Reverse (Baustein 3), erst
ohne Latest-Version-Scope (auf normalen Entities testen).

**Phase 5 – Wildcard-Filter**
`buildWildcard` inkl. Selection-Set-Introspection.

**Phase 6 – Latest-Version-Scope**
Baustein 4 (jOOQ-Layer), danach Phase 4 erweitern, damit
Relations-Subqueries auch auf "latest version" der Zielentity filtern
(Äquivalent zu Pythons `get_model_latest_version` im Subquery-Zweig,
Zeile 1163-1200).

**Phase 7 – DataLoader-Integration**
Baustein 5, inkl. Batch-Resolver, die denselben Latest-Version-Scope
nutzen wie die Query-Resolver.

**Phase 8 – Generische Event-Mutation**
Äquivalent zu `mutations/stammdaten.py`: ein generischer
`EventMutationResolver`, der über die Entity-Registry für jede Entity
eine `<entity>Event`-Mutation erzeugt (`create`/`update`/`correct`/
`clear`), inkl. FK-Auflösung über `objectBezugsId` und
Relations-Check vor `deleted=true` (Löschschutz, Zeile 195-211 in
`mutations/stammdaten.py`).

**Phase 9 – Permissions**
Row-Level-Security nachbauen (kein 1:1-Äquivalent zu `django-guardian`
vorhanden): eigene ACL-Tabelle + zusätzlicher Predicate in jeder Query,
oder Spring Security ACL-Modul evaluieren.

## Fazit

Das Prinzip trägt vollständig in Java/Spring, solange man bei
**graphql-java direkt** bleibt statt bei einem schema-first-Framework.
Der Predicate-Builder selbst ist praktisch eine wörtliche Übersetzung von
`build_q_filter`. Die einzige strukturelle Lücke ist die
Postgres-`DISTINCT ON`-Query für "latest version", die in reinem JPA
nicht ausdrückbar ist und einen zusätzlichen Layer (jOOQ oder native SQL)
braucht.
