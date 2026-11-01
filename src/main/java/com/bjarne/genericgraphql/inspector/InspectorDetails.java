package com.bjarne.genericgraphql.inspector;

import com.bjarne.genericgraphql.engine.remote.RemoteField;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Baut aus einem mitgeschnittenen Knoten das Detail-Panel: Fakten, Aufrufkette mit Fundstellen
 * im Code, Hinweise zu Batch und Fehler. Die Fundstellen nennen Datei und Methode, keine
 * Zeilennummern - die verschieben sich mit jeder Aenderung.
 */
@Component
@ConditionalOnProperty(name = "inspector.enabled", havingValue = "true", matchIfMissing = true)
public class InspectorDetails {

    private static final String GQ = "graphql-java 26.1";
    private static final String DL = "java-dataloader 5.0.0";
    private static final String CTRL = "web/GraphQLController.java";
    private static final String FETCHERS = "engine/resolver/GenericDataFetchers.java";
    private static final String QUERY = "engine/query/EntityQueryService.java";
    private static final String FILTER = "engine/filter/FilterEngine.java";
    private static final String LATEST = "engine/scope/LatestVersionScope.java";
    private static final String ORDER = "engine/order/OrderByEngine.java";
    private static final String LOADERS = "engine/dataloader/RelationDataLoaders.java";
    private static final String REGISTRY = "engine/remote/RemoteAwareDataLoaderRegistry.java";
    private static final String REMOTE_LOADERS = "engine/remote/RemoteFieldLoaders.java";
    private static final String CALLS = "engine/remote/RemoteCalls.java";
    private static final String SEARCH = "engine/remote/RemoteFilterSearch.java";
    private static final String CONFIG = "config/RegistryConfig.java";
    private static final String MUTATION = "engine/mutation/EventMutationService.java";
    private static final String RELATIONS = "engine/relations/RelationsFinderService.java";
    private static final Pattern TABLE = Pattern.compile("^(?:SELECT|INSERT|UPDATE|DELETE)(?: count\\(\\*\\))? ([A-Za-z0-9_.]+)");

    private final FieldClassifier classifier;
    private final String guardrails;

    public InspectorDetails(FieldClassifier classifier, Environment environment) {
        this.classifier = classifier;
        this.guardrails = "max-depth " + environment.getProperty("gql.max-depth", "12")
                + " \u00b7 max-complexity " + environment.getProperty("gql.max-complexity", "2000");
    }

    public NodeDetails details(TraceView trace, NodeView node) {
        return switch (node.kind()) {
            case "root" -> root(trace, node);
            case "forward", "reverse" -> relation(trace, node);
            case "remote" -> remote(trace, node);
            case "search" -> search(trace, node);
            case "mutation" -> mutation(trace, node);
            case "step" -> step(trace, node);
            default -> queryField(trace, node);
        };
    }

    // ------------------------------------------------------------------ Request

    private NodeDetails root(TraceView trace, NodeView node) {
        List<Fact> facts = new ArrayList<>();
        facts.add(mono("Endpoint", "POST /graphql"));
        facts.add(mono("Controller", "GraphQLController.execute \u00b7 @Transactional"));
        facts.add(text("Guardrails", guardrails));
        if (trace.loaders() != null) {
            facts.add(text("DataLoader", trace.loaders()));
        }
        facts.add(mono("Thread", trace.thread()));
        facts.add(text("Abfragen", plural(trace.sqlCount(), "SQL-Statement", "SQL-Statements") + " \u00b7 "
                + plural(trace.remoteCount(), "Remote-Aufruf", "Remote-Aufrufe")));
        facts.add(text("Antwort", trace.errorCount() == 0 ? "ohne errors" : plural(trace.errorCount(), "Fehler", "Fehler") + " im errors-Array"));
        facts.add(duration(node));
        List<ChainStep> chain = List.of(
                ChainStep.of("GraphQLController.execute(request)", CTRL, "@Transactional: eine Transaktion f\u00fcr den ganzen Request"),
                ChainStep.of("new RequestState()", CTRL, "Stichtag und GraphQL-Kontext f\u00fcr alle Loader"),
                ChainStep.of("RelationDataLoaders.newRegistry(state)", LOADERS, "frische Loader, der Cache gilt nur f\u00fcr diesen Request"),
                ChainStep.of("GraphQL.execute(input)", GQ),
                ChainStep.of("MaxQueryDepth \u00b7 MaxQueryComplexity", "config/Guardrails.java", "vor der Ausf\u00fchrung, Introspection ausgenommen"),
                ChainStep.of("TraceInstrumentation", "inspector/TraceInstrumentation.java", "dieser Mitschnitt, hinter den Guardrails"),
                ChainStep.of("ExecutionResult.toSpecification()", CTRL));
        String errorNote = switch (trace.status()) {
            case "rejected" -> "Vor der Ausf\u00fchrung abgelehnt (Syntax, Validierung oder Guardrails) \u2013 kein Resolver ist gelaufen.";
            case "failed" -> "Die Antwort hat Fehler und keine Daten.";
            case "partial" -> "Teilergebnis: Fehler stehen im errors-Array, der Rest der Antwort bleibt erhalten.";
            default -> null;
        };
        return new NodeDetails(node.field(), "POST /graphql", node.sourceName(), "root", facts, chain, null, null,
                errorNote, sqlTitle(node));
    }

    // ------------------------------------------------------------------ Query-Felder

    private NodeDetails queryField(TraceView trace, NodeView node) {
        List<Fact> facts = new ArrayList<>();
        facts.add(text("Quelle", source(node)));
        if (!node.resolver().isEmpty()) {
            facts.add(mono("Resolver", node.resolver()));
        }
        if (node.args() != null) {
            facts.add(mono("Argumente", node.args()));
        }
        NodeView search = child(trace, node, "search");
        if (search != null) {
            facts.add(mono("Filter fremd", search.args() + " \u2192 " + search.sourceName()));
            facts.add(text("Reihenfolge", "erst den Owner fragen, dann SQL mit IN"));
        }
        facts.add(sql(node));
        facts.add(text("Ergebnis", node.count()));
        common(facts, node, null);

        List<ChainStep> chain = new ArrayList<>();
        String strategy = "mutation".equals(trace.kind()) ? "AsyncSerialExecutionStrategy" : "AsyncExecutionStrategy";
        chain.add(ChainStep.of(node.owner() + "." + node.field(), GQ, "Ebene " + node.level() + " \u00b7 " + strategy));
        switch (node.kind()) {
            case "byId" -> {
                chain.add(ChainStep.of("GenericDataFetchers.byObjectId(meta)", FETCHERS));
                chain.add(ChainStep.of("EntityQueryService.find(spec \u00b7 objectRefId = \u2026)", QUERY, "h\u00f6chstens 2 Zeilen: mehr als eine w\u00e4re ein Fehler"));
            }
            case "timestamps" -> {
                chain.add(ChainStep.of("GenericDataFetchers.timestamps(meta)", FETCHERS));
                chain.add(ChainStep.of("EntityQueryService.timestamps(meta, objectRefId)", QUERY));
            }
            case "log" -> {
                chain.add(ChainStep.of("GenericDataFetchers.log(meta)", FETCHERS));
                chain.add(ChainStep.of("EntityQueryService.eventLog(meta, objectRefId)", QUERY, "rohe Events, ohne Snapshots"));
            }
            case "relations" -> {
                chain.add(ChainStep.of("GenericDataFetchers.relationsFinder(meta)", FETCHERS));
                chain.add(ChainStep.of("byObjectId(meta) \u00b7 RelationsFinderService.find(\u2026)", RELATIONS));
            }
            case "list" -> {
                chain.add(ChainStep.of("GenericDataFetchers.list(meta)", FETCHERS));
                chain.add(ChainStep.of("EntityQueryService.find(spec)", QUERY));
            }
            default -> chain.add(ChainStep.of(node.resolver().isEmpty() ? "DataFetcher" : node.resolver(), FETCHERS));
        }
        if (search != null) {
            chain.add(ChainStep.of("resolveRemoteFilters(\u2026) \u00b7 Planungslauf", QUERY, "Filter trocken bauen, fremde Teilb\u00e4ume melden sich im Plan an"));
            chain.add(ChainStep.of("RemoteFilterSearch.resolve(plan)", SEARCH, "alle Owner parallel, der Request-Thread wartet"));
            chain.add(ChainStep.of("FilterEngine.buildRoot(\u2026) \u00b7 Ausf\u00fchrungslauf", FILTER, "Treffer werden an derselben Stelle als IN eingesetzt"));
        }
        if (!node.sql().isEmpty() || "list".equals(node.kind()) || "byId".equals(node.kind())) {
            chain.add(ChainStep.of("FilterEngine.scope() \u00b7 AccessPolicy", FILTER));
            chain.add(ChainStep.of("LatestVersionScope.scopeToLatest(\u2026)", LATEST, "j\u00fcngster Snapshot je objectRefId"));
            if (node.args() != null && node.args().contains("order:")) {
                chain.add(ChainStep.of("OrderByEngine.build(\u2026)", ORDER));
            }
            chain.add(ChainStep.of("TypedQuery.getResultList()", "Hibernate \u2192 " + classifier.dbName()));
        }
        String errorNote = null;
        if (node.failed()) {
            errorNote = search != null && search.failed()
                    ? "Kein Teilergebnis: ohne die Treffer des Owners w\u00e4re die Trefferliste falsch. Das Feld wird null."
                    : "Das Feld wird null, der Fehler steht im errors-Array.";
        }
        return new NodeDetails(node.owner() + "." + node.field(), "\u2192 " + node.type(), node.sourceName(), "db", facts,
                chain, null, null, errorNote, sqlTitle(node));
    }

    // ------------------------------------------------------------------ DataLoader-Relationen

    private NodeDetails relation(TraceView trace, NodeView node) {
        boolean forward = "forward".equals(node.kind());
        List<Fact> facts = new ArrayList<>();
        facts.add(text("Quelle", source(node)));
        facts.add(mono("Resolver", node.resolver()));
        facts.add(mono("DataLoader", node.loaderKey()));
        List<String> partners = partners(trace, node);
        if (!partners.isEmpty()) {
            facts.add(text("Batch", "gemeinsam mit " + String.join(", ", partners)));
        }
        facts.add(sql(node));
        facts.add(text("Ergebnis", node.count()));
        common(facts, node, null);

        String keys = plural(node.keys().size(), "Schl\u00fcssel", "Schl\u00fcssel");
        List<ChainStep> chain = new ArrayList<>();
        chain.add(forward
                ? ChainStep.of("GenericDataFetchers.forward(rel)", FETCHERS, "liest nur die ID des Lazy-Proxys")
                : ChainStep.of("GenericDataFetchers.reverse(rel)", FETCHERS));
        chain.add(ChainStep.of("DataLoader.load(" + (forward ? "id" : "objectRefId") + ") \u00d7 " + node.loads(), DL, "liefert sofort ein Future"));
        chain.add(ChainStep.of("RemoteAwareDataLoaderRegistry.dispatchAll()", REGISTRY, "einmal f\u00fcr Ebene " + node.level()));
        if (node.batches() > 0) {
            chain.add(forward
                    ? ChainStep.of("RelationDataLoaders.loadForward(" + keys + ")", LOADERS)
                    : ChainStep.of("RelationDataLoaders.loadReverse(" + keys + ")", LOADERS, "gruppiert die Zeilen je Elternobjekt"));
            chain.add(ChainStep.of("EntityQueryService.find(\u2026 IN keys)", QUERY));
            chain.add(ChainStep.of("FilterEngine.scope() \u00b7 LatestVersionScope", FILTER));
        } else {
            chain.add(ChainStep.of("Cache des DataLoaders", DL, "alle Schl\u00fcssel waren in diesem Request schon geladen"));
        }

        String keysNote;
        if (node.batches() == 0) {
            keysNote = "Kein SQL: alle Schl\u00fcssel lagen schon im Cache des DataLoaders.";
        } else {
            keysNote = node.loads() + "\u00d7 load() \u2192 " + keys + " \u2192 " + plural(node.sqlCount(), "SQL", "SQL")
                    + (node.loads() > node.sqlCount() ? " statt " + node.loads() : "")
                    + (partners.isEmpty() ? "" : ", gemeinsam mit " + String.join(", ", partners)) + ".";
        }
        return new NodeDetails(node.owner() + "." + node.field(), "\u2192 " + node.type(), node.sourceName(), "db", facts,
                chain, "Schl\u00fcssel im Batch", keysNote, node.failed() ? "Das Feld wird null, der Fehler steht im errors-Array." : null,
                sqlTitle(node));
    }

    // ------------------------------------------------------------------ Remote-Felder

    private NodeDetails remote(TraceView trace, NodeView node) {
        RemoteField<?> field = classifier.remote(node.sourceName(), node.field());
        List<Fact> facts = new ArrayList<>();
        facts.add(text("Quelle", node.sourceName() + " \u00b7 eigene Datenbank"));
        facts.add(mono("Remote-Feld", node.field() + " \u2192 " + node.type() + " (Record)"));
        facts.add(mono("DataLoader", node.loaderKey()));
        if (field != null) {
            facts.add(text("Timeout", field.timeout().toMillis() + " ms"));
        }
        if (node.ready() != null) {
            facts.add(text("Antwort", "nach " + ms(node.ready() - node.t0()) + " \u00b7 abgeschlossen mit der Ebene nach " + ms(node.t1() - node.t0())));
        }
        List<String> parallel = parallel(trace, node);
        if (!parallel.isEmpty()) {
            facts.add(text("Parallel zu", String.join(", ", parallel)));
        }
        facts.add(text("Ergebnis", node.count()));
        common(facts, node, node.thread());

        String ids = plural(node.keys().size(), "ID", "IDs");
        List<ChainStep> chain = List.of(
                ChainStep.of("GenericDataFetchers.remote(field, \"" + node.owner() + "\")", FETCHERS),
                ChainStep.of("DataLoader.load(objectRefId) \u00d7 " + node.loads(), "MappedBatchLoader", "liefert sofort ein Future"),
                ChainStep.of("RemoteAwareDataLoaderRegistry.dispatchAll()", REGISTRY, "startet alle Loader von Ebene " + node.level()),
                ChainStep.of("RemoteFieldLoaders.start(\u2026)", REMOTE_LOADERS, "Aufruf l\u00e4uft auf " + node.thread() + ", kehrt sofort zur\u00fcck"),
                ChainStep.of("RemoteBatchLoader.load(" + ids + ") \u2192 " + node.sourceName(), CONFIG),
                ChainStep.of("RemoteCalls.awaitAndComplete()", CALLS, "Request-Thread wartet auf den langsamsten Service"));
        List<String> partners = partners(trace, node);
        String keysNote = node.loads() + "\u00d7 load() \u2192 " + ids + " \u2192 " + plural(node.batches(), "Aufruf", "Aufrufe")
                + (partners.isEmpty() ? "" : ", gemeinsam mit " + String.join(", ", partners)) + ".";
        String errorNote = node.failed()
                ? "Teilergebnis: " + node.field() + " wird null, der Rest der Antwort bleibt erhalten."
                : null;
        return new NodeDetails(node.owner() + "." + node.field(), "\u2192 " + node.type(), node.sourceName(), "remote",
                facts, chain, "IDs im Aufruf", keysNote, errorNote, null);
    }

    private NodeDetails search(TraceView trace, NodeView node) {
        RemoteField<?> field = classifier.remote(node.sourceName(), node.field());
        List<Fact> facts = new ArrayList<>();
        facts.add(text("Quelle", node.sourceName() + " \u00b7 Suche"));
        facts.add(mono("Teilbaum", node.args()));
        if (field != null) {
            facts.add(text("Freigegeben", String.join(" \u00b7 ", field.filterFields())));
            facts.add(text("Limit", "maxIds + 1 = " + (field.maxIds() + 1)));
        }
        facts.add(text("Treffer", node.count()));
        common(facts, node, node.thread());
        List<ChainStep> chain = List.of(
                ChainStep.of("EntityQueryService.resolveRemoteFilters(\u2026)", QUERY),
                ChainStep.of("FilterEngine.remoteIn(\u2026) \u00b7 Planungslauf", FILTER, "meldet " + node.args() + " im RemoteFilterPlan an"),
                ChainStep.of("RemoteFilterSearch.resolve(plan)", SEARCH, "ein virtueller Thread je Owner"),
                ChainStep.of("RemoteSearch.search(filter, limit) \u2192 " + node.sourceName(), CONFIG),
                ChainStep.of("FilterEngine.remoteIn(\u2026) \u00b7 Ausf\u00fchrungslauf", FILTER,
                        "object_ref_id IN (" + String.join(", ", node.keys()) + ")"));
        String errorNote = node.failed()
                ? "Die Suche ist Teil des Filters \u2013 f\u00e4llt sie aus, scheitert die ganze Query."
                : null;
        return new NodeDetails(node.owner() + "." + node.field(), "\u2192 Set<objectRefId>", node.sourceName(), "filter",
                facts, chain, "Treffer", "Der Owner bekommt nur seinen Teilbaum; die lokalen Bedingungen pr\u00fcft danach SQL.",
                errorNote, null);
    }

    // ------------------------------------------------------------------ Mutation

    private NodeDetails mutation(TraceView trace, NodeView node) {
        List<NodeView> steps = trace.nodes().stream().filter(n -> node.id().equals(n.parent()) && n.step()).toList();
        boolean writes = steps.stream().flatMap(s -> s.sql().stream())
                .anyMatch(sql -> sql.regionMatches(true, 0, "insert", 0, 6) || sql.regionMatches(true, 0, "delete", 0, 6)
                        || sql.regionMatches(true, 0, "update", 0, 6));
        List<Fact> facts = new ArrayList<>();
        facts.add(text("Quelle", node.sourceName() + (writes ? " \u00b7 schreibend" : " \u00b7 nur lesend")));
        facts.add(mono("Resolver", node.resolver()));
        if (node.args() != null) {
            facts.add(mono("Argumente", node.args()));
        }
        Object message = responseValue(trace, node, "message");
        facts.add(text("Ergebnis", "Result." + node.count() + (message != null ? " \u00b7 \u201e" + message + "\u201c" : "")));
        int sql = steps.stream().mapToInt(NodeView::sqlCount).sum();
        facts.add(text("Schritte", plural(steps.size(), "Schritt", "Schritte") + " \u00b7 " + plural(sql, "SQL-Statement", "SQL-Statements")));
        if ("error".equals(node.count())) {
            facts.add(text("HTTP", "200 ohne errors: die Ablehnung ist ein Ergebnis, kein Fehler"));
        }
        common(facts, node, null);

        List<ChainStep> chain = new ArrayList<>();
        chain.add(ChainStep.of("AsyncSerialExecutionStrategy \u00b7 Mutation." + node.field(), GQ, "Mutation-Felder laufen nacheinander"));
        chain.add(ChainStep.of("GenericDataFetchers.eventMutation(meta)", FETCHERS, "reicht data als Map weiter"));
        chain.add(ChainStep.of("EventMutationService.handle(meta, data, ctx)", MUTATION, "@Transactional \u2013 l\u00e4uft in der Transaktion des Controllers"));
        for (NodeView step : steps) {
            chain.add(ChainStep.of(step.via() + " \u00b7 " + step.field(), MUTATION, step.ret()));
        }
        chain.add(ChainStep.of("Result \u2192 MutationResult", GQ, "PropertyDataFetcher liest die Record-Felder"));
        return new NodeDetails(node.owner() + "." + node.field(), "\u2192 " + node.type(), node.sourceName(), "db", facts,
                chain, null, null, node.failed() ? "Die Mutation ist mit einer Exception abgebrochen." : null, sqlTitle(node));
    }

    private NodeDetails step(TraceView trace, NodeView node) {
        List<Fact> facts = new ArrayList<>();
        facts.add(text("Quelle", source(node)));
        facts.add(mono("Methode", node.resolver()));
        if (!node.ret().isEmpty()) {
            facts.add(text("Ergebnis", node.ret()));
        }
        facts.add(text("SQL", node.sql().isEmpty() ? "kein Statement" : verbs(node)));
        common(facts, node, null);

        List<ChainStep> chain = switch (node.field()) {
            case "loadLatest" -> List.of(
                    ChainStep.of("EventMutationService.loadLatest(meta, id, ctx)", MUTATION, "gibt es das Objekt heute?"),
                    ChainStep.of("EntityQueryService.find(spec)", QUERY),
                    ChainStep.of("FilterEngine.scope() \u00b7 LatestVersionScope", FILTER));
            case "persist(event)" -> List.of(
                    ChainStep.of("EventMutationService.nextObjectId(meta, prefix)", MUTATION, "count(*) + 1, dann find() bis die ID frei ist"),
                    ChainStep.of("applyData(meta, row, data)", MUTATION, "schreibt nur Felder, die im Input stehen"),
                    ChainStep.of("EntityManager.persist(row) \u00b7 flush()", MUTATION));
            case "rebuildSnapshots" -> List.of(
                    ChainStep.of("EventMutationService.rebuildSnapshots(meta, id)", MUTATION),
                    ChainStep.of("Events laden, alte Snapshots l\u00f6schen", MUTATION),
                    ChainStep.of("nach validFrom sortieren und falten", MUTATION, "ein Snapshot je G\u00fcltigkeitszeitpunkt"),
                    ChainStep.of("persist(snapshot) \u00b7 flush()", MUTATION));
            case "findReverse" -> List.of(
                    ChainStep.of("handleChange: deleted = true", MUTATION, "L\u00f6schschutz pr\u00fcfen"),
                    ChainStep.of("RelationsFinderService.findReverse(meta, entity, ctx)", RELATIONS),
                    ChainStep.of("je ReverseRel: EntityQueryService.find(\u2026)", QUERY, "mit LatestVersionScope \u2013 nur g\u00fcltige Objekte z\u00e4hlen"));
            case "find" -> List.of(
                    ChainStep.of("Result.blocked(\u2026, relationsFinder.find(\u2026))", MUTATION),
                    ChainStep.of("RelationsFinderService.find(meta, entity, ctx)", RELATIONS, "forwardRelations: nur die ID vom Proxy"),
                    ChainStep.of("findReverse(\u2026) \u00b7 zweiter Durchlauf", RELATIONS, "dieselben Abfragen wie bei der Pr\u00fcfung"));
            default -> List.of(ChainStep.of(node.resolver(), MUTATION));
        };
        return new NodeDetails(node.owner() + "." + node.field(), node.sig(), node.sourceName(), "db", facts, chain, null,
                null, null, sqlTitle(node));
    }

    // ------------------------------------------------------------------ Hilfen

    private void common(List<Fact> facts, NodeView node, String thread) {
        facts.add(duration(node));
        String level = String.valueOf(node.level());
        if (node.step()) {
            level += " \u00b7 Schritt in EventMutationService";
        } else if (node.aux()) {
            level += " \u00b7 l\u00e4uft vor dem SQL";
        }
        facts.add(text("Ebene", level));
        facts.add(mono("Thread", thread != null ? thread : node.thread()));
    }

    private static Fact duration(NodeView node) {
        return mono("Dauer", ms(node.t1() - node.t0()) + "  \u00b7  " + num(node.t0()) + " \u2192 " + num(node.t1()) + " ms");
    }

    private static Fact sql(NodeView node) {
        if (node.sql().isEmpty()) {
            return text("SQL", node.batches() == 0 && node.loaderKey() != null ? "keins \u2013 aus dem Cache" : "kein Statement");
        }
        return text("SQL", plural(node.sqlCount(), "Statement", "Statements") + " \u00b7 " + plural(node.rows(), "Zeile", "Zeilen"));
    }

    private static String sqlTitle(NodeView node) {
        if (node.sql().isEmpty()) {
            return null;
        }
        return node.sqlCount() == 1 ? "SQL" : "SQL \u00b7 " + node.sqlCount() + " Statements";
    }

    private static String source(NodeView node) {
        Set<String> tables = new LinkedHashSet<>();
        for (String statement : node.sql()) {
            Matcher matcher = TABLE.matcher(JdbcTracing.label(statement));
            if (matcher.find()) {
                tables.add(matcher.group(1));
            }
        }
        return node.sourceName() + (tables.isEmpty() ? "" : " \u00b7 " + String.join(", ", tables));
    }

    private static String verbs(NodeView node) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String statement : node.sql()) {
            String label = JdbcTracing.label(statement);
            String verb = label.startsWith("SELECT count(*)") ? "SELECT count(*)" : label.split(" ")[0];
            counts.merge(verb, 1, Integer::sum);
        }
        List<String> parts = new ArrayList<>();
        counts.forEach((verb, n) -> parts.add(n + " " + verb));
        return String.join(" \u00b7 ", parts);
    }

    private static NodeView child(TraceView trace, NodeView node, String kind) {
        for (NodeView candidate : trace.nodes()) {
            if (node.id().equals(candidate.parent()) && kind.equals(candidate.kind())) {
                return candidate;
            }
        }
        return null;
    }

    /** Andere Felder mit demselben Loader, die im selben Zeitfenster geladen wurden. */
    private static List<String> partners(TraceView trace, NodeView node) {
        List<String> partners = new ArrayList<>();
        if (node.loaderKey() == null || node.batches() == 0) {
            return partners;
        }
        for (NodeView other : trace.nodes()) {
            if (other != node && node.loaderKey().equals(other.loaderKey()) && other.batches() > 0
                    && other.t0() <= node.t1() && node.t0() <= other.t1()) {
                partners.add(other.owner() + "." + other.field());
            }
        }
        return partners;
    }

    /** Remote-Felder anderer Services, die gleichzeitig unterwegs waren. */
    private static List<String> parallel(TraceView trace, NodeView node) {
        List<String> parallel = new ArrayList<>();
        double end = node.ready() != null ? node.ready() : node.t1();
        for (NodeView other : trace.nodes()) {
            if (other == node || !"remote".equals(other.kind()) || other.source().equals(node.source())) {
                continue;
            }
            double otherEnd = other.ready() != null ? other.ready() : other.t1();
            if (other.t0() < end && node.t0() < otherEnd) {
                String name = other.sourceName() + " (" + ms(otherEnd - other.t0()) + ")";
                if (!parallel.contains(name)) {
                    parallel.add(name);
                }
            }
        }
        return parallel;
    }

    /** Ein Wert aus der Antwort unter dem Pfad des Knotens, z. B. message einer Mutation. */
    private static Object responseValue(TraceView trace, NodeView node, String key) {
        Object value = trace.response() == null ? null : trace.response().get("data");
        for (String segment : node.path().split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            value = value instanceof Map<?, ?> map ? map.get(segment) : null;
        }
        return value instanceof Map<?, ?> map ? map.get(key) : null;
    }

    private static Fact text(String label, String value) {
        return new Fact(label, value == null ? "\u2013" : value, false);
    }

    private static Fact mono(String label, String value) {
        return new Fact(label, value == null ? "\u2013" : value, true);
    }

    private static String plural(long n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    private static String ms(double value) {
        return num(value) + " ms";
    }

    private static String num(double value) {
        return String.format(Locale.GERMANY, "%.1f", value);
    }
}
