package com.bjarne.genericgraphql.inspector;

import com.bjarne.genericgraphql.engine.mutation.EventMutationService;
import com.bjarne.genericgraphql.engine.probe.EngineProbe;
import graphql.ExecutionResult;
import graphql.execution.DataFetcherResult;
import graphql.execution.instrumentation.InstrumentationState;
import graphql.language.Field;
import graphql.language.OperationDefinition;
import graphql.language.Selection;
import graphql.language.SourceLocation;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.SelectedField;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Schneidet einen Request mit: jedes Feld mit eigenem Resolver als Knoten (ueber alle
 * Elternobjekte zusammengefasst), dazu die Meldungen der Engine ueber {@link EngineProbe} -
 * DataLoader-Batches, SQL, Remote-Aufrufe, Wartezeiten und die Schritte einer Mutation.
 * {@link #finish} macht daraus eine {@link TraceView}.
 *
 * <p>Thread-sicher: Remote-Aufrufe und Filter-Suchen melden sich aus virtuellen Threads. Nach
 * {@link #finish} nimmt der Collector nichts mehr an.
 */
public final class TraceCollector implements EngineProbe, InstrumentationState {

    private static final Pattern OPERATION = Pattern.compile("(?m)^\\s*(query|mutation|subscription)\\s+([A-Za-z_][A-Za-z0-9_]*)");
    private static final int MAX_KEYS = 200;
    private static final int MAX_SQL = 50;
    private static final int MAX_TEXT = 400;

    /** Darstellung der Schritte, die EventMutationService meldet. */
    private record StepInfo(String owner, String type, String sig, String verb, String one, String many) {
    }

    private static final Map<String, StepInfo> STEPS = Map.of(
            "loadLatest", new StepInfo("handleChange", "Profil-Zeile", "aktueller Stand", "lesen", "Zeile", "Zeilen"),
            "persist(event)", new StepInfo("EventMutationService", "Event", "neue Event-Zeile", "schreiben", null, null),
            "rebuildSnapshots", new StepInfo("EventMutationService", "Snapshot", "Events \u2192 Snapshots", "falten", "Snapshot", "Snapshots"),
            "findReverse", new StepInfo("RelationsFinderService", "RelationRef", "L\u00f6schschutz", "pr\u00fcfen", "Treffer", "Treffer"),
            "find", new StepInfo("RelationsFinderService", "Relations", "Relationen f\u00fcr die Antwort", "Antwort", "Relation", "Relationen"));

    private final String query;
    private final String[] lines;
    private final String requestThread;
    private final String loaders;
    private final long startNanos = System.nanoTime();
    private final long startedAt = System.currentTimeMillis();

    private long id;
    private String operation;
    private String kind = "query";
    private boolean introspection;
    private double execStart = -1;
    private boolean finished;

    private final Node root;
    private final Map<String, Node> byKey = new HashMap<>();
    private final List<Node> nodes = new ArrayList<>();
    private final Deque<Node> active = new ArrayDeque<>();
    private final Deque<Rec> openBatches = new ArrayDeque<>();
    private final List<Rec> records = new ArrayList<>();
    private int nodeIds;

    TraceCollector(String query, String operationName, String loaders) {
        this.query = query == null ? "" : query.replace("\r\n", "\n").replace('\r', '\n');
        this.lines = this.query.split("\n", -1);
        this.requestThread = threadName();
        this.loaders = loaders;
        this.operation = operationName;
        this.root = new Node("req", "", null, 0, "root");
        root.source = "root";
        root.sourceName = "graphql-java";
        root.owner = "POST /graphql";
        root.via = "";
        root.resolver = "GraphQLController.execute";
        register(root);
    }

    // ------------------------------------------------------------------ Lebenszyklus

    synchronized long id() {
        return id;
    }

    synchronized void id(long value) {
        if (id == 0) {
            id = value;
        }
    }

    synchronized String operationName() {
        if (operation != null) {
            return operation;
        }
        Matcher matcher = OPERATION.matcher(query);
        return matcher.find() ? matcher.group(2) : "anonymous";
    }

    synchronized String kind() {
        return kind;
    }

    synchronized long startedAt() {
        return startedAt;
    }

    synchronized boolean introspection() {
        return introspection;
    }

    /** Parsen und Validieren sind vorbei, die Ausfuehrung beginnt. */
    synchronized void operation(OperationDefinition definition) {
        execStart = now();
        if (definition == null) {
            return;
        }
        kind = definition.getOperation().name().toLowerCase(Locale.ROOT);
        if (definition.getName() != null) {
            operation = definition.getName();
        }
        root.qline = line(definition.getSourceLocation());
        List<Selection> selections = definition.getSelectionSet().getSelections();
        introspection = !selections.isEmpty() && selections.stream()
                .allMatch(selection -> selection instanceof Field field && field.getName().startsWith("__"));
    }

    // ------------------------------------------------------------------ Felder

    /** Ruft den eigentlichen Resolver auf und misst ihn - bei Futures bis zu ihrem Abschluss. */
    Object fetch(DataFetcher<?> delegate, DataFetchingEnvironment env, FieldClassifier.Kind kind) throws Exception {
        Node node = fieldStart(env, kind);
        Object value;
        try {
            value = delegate.get(env);
        } catch (Exception | Error ex) {
            fieldReturned(node);
            fieldDone(node, null, ex);
            throw ex;
        }
        fieldReturned(node);
        if (value instanceof CompletableFuture<?> future) {
            // Eigenes Future: so ist der Abschluss gemessen, bevor graphql-java die naechste Ebene anstoesst.
            CompletableFuture<Object> measured = new CompletableFuture<>();
            future.whenComplete((result, error) -> {
                fieldDone(node, result, error);
                if (error != null) {
                    measured.completeExceptionally(error);
                } else {
                    measured.complete(result);
                }
            });
            return measured;
        }
        fieldDone(node, value, null);
        return value;
    }

    private synchronized Node fieldStart(DataFetchingEnvironment env, FieldClassifier.Kind kind) {
        double now = now();
        List<String> names = new ArrayList<>();
        for (Object segment : env.getExecutionStepInfo().getPath().toList()) {
            if (segment instanceof String name) {
                names.add(name);
            }
        }
        String key = String.join("/", names);
        Node node = byKey.get(key);
        if (node == null) {
            Node parent = root;
            for (int i = names.size() - 1; i > 0; i--) {
                Node candidate = byKey.get(String.join("/", names.subList(0, i)));
                if (candidate != null) {
                    parent = candidate;
                    break;
                }
            }
            Field field = env.getField();
            String resultKey = names.isEmpty() ? field.getName() : names.getLast();
            node = new Node(nextNodeId(), key, parent, parent.level + 1, kind.kind());
            node.resultKey = resultKey;
            node.field = resultKey.equals(field.getName()) ? resultKey : resultKey + ": " + field.getName();
            node.owner = GraphQLTypeUtil.simplePrint(env.getParentType());
            node.type = GraphQLTypeUtil.simplePrint(env.getFieldType());
            node.source = kind.source();
            node.sourceName = kind.sourceName();
            node.via = kind.via();
            node.resolver = kind.resolver();
            node.loaderKey = kind.loaderKey();
            node.path = "/" + key;
            node.qline = line(field.getSourceLocation());
            node.selections = selections(env);
            node.args = arguments(env.getArguments());
            register(node);
        }
        node.loads++;
        if (node.t0 < 0) {
            node.t0 = now;
        }
        if (node.thread == null) {
            node.thread = threadName();
        }
        active.push(node);
        return node;
    }

    private synchronized void fieldReturned(Node node) {
        active.removeFirstOccurrence(node);
    }

    private synchronized void fieldDone(Node node, Object value, Throwable error) {
        node.done++;
        node.t1 = Math.max(node.t1, now());
        if (error != null) {
            node.errors++;
            if (node.error == null) {
                node.error = message(error);
            }
            return;
        }
        Object data = value instanceof DataFetcherResult<?> result ? result.getData() : value;
        if (data instanceof Collection<?> list) {
            node.items += list.size();
        } else if (data != null) {
            node.items++;
        }
        if (data instanceof EventMutationService.Result result) {
            node.note = result.result();
        }
    }

    // ------------------------------------------------------------------ EngineProbe

    @Override
    public synchronized Span batch(String loaderKey, Collection<?> keys) {
        if (finished) {
            return Span.NOOP;
        }
        Rec rec = new Rec("db", "batch", loaderKey);
        rec.loaderKey = loaderKey;
        rec.keys = strings(keys);
        rec.onClose = openBatches::removeFirstOccurrence;
        openBatches.push(rec);
        records.add(rec);
        return rec;
    }

    @Override
    public synchronized Span remoteCall(String source, String loaderKey, Collection<?> keys) {
        if (finished) {
            return Span.NOOP;
        }
        Rec rec = new Rec(FieldClassifier.lane(source), "remote",
                "batchGet \u00b7 " + keys.size() + (keys.size() == 1 ? " ID" : " IDs"));
        rec.source = source;
        rec.loaderKey = loaderKey;
        rec.keys = strings(keys);
        rec.thread = threadName();
        records.add(rec);
        return rec;
    }

    @Override
    public synchronized Span remoteSearch(String source, String field, String filter) {
        if (finished) {
            return Span.NOOP;
        }
        // Die Suche laeuft in einem virtuellen Thread; der Request-Thread steckt solange im Feld, das filtert.
        Node parent = active.isEmpty() ? root : active.peek();
        String base = parent.key + "#search:" + field;
        String key = base;
        for (int n = 2; byKey.containsKey(key); n++) {
            key = base + "#" + n;
        }
        Node node = new Node(nextNodeId(), key, parent, Math.max(1, parent.level), "search");
        node.aux = true;
        node.resultKey = field;
        node.field = field;
        node.owner = parent.type.replaceAll("[\\[\\]!]", "") + "Filter";
        node.type = "IDs";
        node.source = FieldClassifier.lane(source);
        node.sourceName = source;
        node.via = "IN (\u2026)";
        node.resolver = "RemoteFilterSearch.resolve";
        node.path = parent.path;
        node.args = shorten(prettyFilter(filter));
        node.chips = List.of(node.args);
        node.qline = lineContaining(field + ":", parent.qline);
        node.t0 = now();
        node.loads = 1;
        node.thread = threadName();
        register(node);

        Rec rec = new Rec(node.source, "search", "search " + node.args);
        rec.source = source;
        rec.node = node;
        rec.thread = node.thread;
        rec.onClose = r -> {
            node.t1 = r.t1;
            node.done = 1;
            if (r.count >= 0) {
                node.items = r.count;
            }
            if (r.note != null && !r.note.isBlank()) {
                node.keys.addAll(List.of(r.note.split(", ")));
            }
            if (r.error != null) {
                node.error = r.error;
            }
        };
        records.add(rec);
        return rec;
    }

    @Override
    public synchronized Span waiting(String label) {
        if (finished) {
            return Span.NOOP;
        }
        Rec rec = new Rec("thread", "wait", label + " \u00b7 wartet");
        rec.thread = threadName();
        rec.node = active.peek();
        records.add(rec);
        return rec;
    }

    @Override
    public synchronized Span step(String name, String verb) {
        if (finished) {
            return Span.NOOP;
        }
        Node parent = active.isEmpty() ? root : active.peek();
        int index = ++parent.stepCount;
        StepInfo info = STEPS.getOrDefault(name, new StepInfo("EventMutationService", "", name, verb, null, null));
        Node node = new Node(nextNodeId(), parent.key + "#" + index + ":" + name, parent, parent.level + 1, "step");
        node.step = true;
        node.stepInfo = info;
        node.stepIndex = index;
        node.resultKey = name;
        node.field = name;
        node.owner = info.owner();
        node.type = info.type();
        node.source = "db";
        node.sourceName = parent.sourceName;
        node.via = index + " \u00b7 " + info.verb();
        node.resolver = info.owner() + "." + name;
        node.path = parent.path;
        node.qline = parent.qline;
        node.t0 = now();
        node.loads = 1;
        node.thread = threadName();
        register(node);
        active.push(node);

        Rec rec = new Rec("thread", "step", index + " \u00b7 " + name);
        rec.node = node;
        rec.thread = node.thread;
        rec.onClose = r -> {
            active.removeFirstOccurrence(node);
            node.t1 = r.t1;
            node.done = 1;
            if (r.count >= 0) {
                node.items = r.count;
            }
            node.note = r.note;
            if (r.error != null) {
                node.error = r.error;
            }
        };
        records.add(rec);
        return rec;
    }

    @Override
    public synchronized Span sql(String sql) {
        if (finished) {
            return Span.NOOP;
        }
        Rec rec = new Rec("db", "sql", JdbcTracing.label(sql));
        rec.detail = sql;
        rec.thread = threadName();
        Rec batch = openBatches.peek();
        if (batch != null) {
            rec.batch = batch;
            batch.children.add(rec);
        } else {
            rec.node = active.isEmpty() ? root : active.peek();
        }
        records.add(rec);
        return rec;
    }

    // ------------------------------------------------------------------ Abschluss

    synchronized TraceView finish(ExecutionResult result) {
        double total = now();
        finished = true;
        Map<String, Object> response = specification(result);

        root.t0 = 0;
        root.t1 = total;
        root.loads = 1;
        root.done = 1;
        root.thread = requestThread;
        root.field = kind + " " + operationName();
        root.type = kind;

        // Was beim Ende noch offen ist (z. B. ein Aufruf nach Timeout), endet mit dem Request.
        for (Rec rec : records) {
            if (rec.t1 < 0) {
                rec.t1 = total;
            }
        }
        for (Node node : nodes) {
            if (node.t0 < 0) {
                node.t0 = 0;
            }
            if (node.t1 < 0) {
                node.t1 = total;
            }
        }

        attribute();
        for (Node node : nodes) {
            node.countLabel = countLabel(node);
            node.retLabel = retLabel(node);
        }

        List<Seg> levels = levels(execStart >= 0 ? execStart : total);
        List<SpanView> spans = new ArrayList<>(threadLane(levels, total));
        int sqlCount = 0;
        int remoteCount = 0;
        for (Rec rec : records) {
            switch (rec.kind) {
                case "sql" -> {
                    sqlCount++;
                    String label = rec.label + (rec.count >= 0 ? " \u00b7 " + rec.count + (rec.count == 1 ? " Zeile" : " Zeilen") : "");
                    spans.add(new SpanView("db", "sql", r3(rec.t0), r3(rec.t1), label, nodeId(rec.node), rec.detail,
                            rec.error, rec.count, rec.thread));
                }
                case "remote" -> {
                    remoteCount++;
                    spans.add(new SpanView(rec.lane, "remote", r3(rec.t0), r3(rec.t1), rec.label + " \u00b7 " + rec.thread,
                            nodeId(rec.node), rec.source + ".batchGet(" + String.join(", ", rec.keys) + ")",
                            rec.error, rec.count, rec.thread));
                }
                case "search" -> {
                    remoteCount++;
                    String label = rec.label + (rec.count >= 0 ? " \u2192 " + rec.count + " Treffer" : "");
                    spans.add(new SpanView(rec.lane, "search", r3(rec.t0), r3(rec.t1), label, nodeId(rec.node),
                            rec.source + ".search(" + rec.node.args + ")", rec.error, rec.count, rec.thread));
                }
                default -> {
                }
            }
        }

        List<MarkView> marks = new ArrayList<>();
        if ("mutation".equals(kind)) {
            nodes.stream().filter(n -> n.isField() && n.level == 1).findFirst()
                    .ifPresent(n -> marks.add(new MarkView(r3(n.t0), "E1")));
            for (Node node : nodes) {
                if (node.step) {
                    marks.add(new MarkView(r3(node.t0), String.valueOf(node.stepIndex)));
                }
            }
        } else {
            for (Seg seg : levels) {
                marks.add(new MarkView(r3(seg.t0), "E" + seg.level));
            }
        }

        List<NodeView> views = new ArrayList<>();
        for (Node node : nodes) {
            views.add(view(node));
        }

        int errorCount = result.getErrors() == null ? 0 : result.getErrors().size();
        String status;
        if (errorCount == 0) {
            boolean declined = nodes.stream().anyMatch(n -> "mutation".equals(n.kind) && "error".equals(n.note));
            status = declined ? "declined" : "ok";
        } else if (!result.isDataPresent()) {
            status = "rejected";
        } else {
            status = allNull(response.get("data")) ? "failed" : "partial";
        }

        return new TraceView(id, operationName(), kind, query, startedAt, r3(total), status, errorCount, sqlCount,
                remoteCount, requestThread, loaders, List.copyOf(views), List.copyOf(spans), List.copyOf(marks), response);
    }

    /** Ordnet Batches, SQL und Remote-Aufrufe den Feldern zu, die sie ausgeloest haben. */
    private void attribute() {
        for (Rec rec : records) {
            switch (rec.kind) {
                case "batch" -> {
                    List<Node> targets = nodesFor(rec.loaderKey, rec.t0);
                    for (Node node : targets) {
                        node.batches++;
                        node.keys.addAll(rec.keys);
                        node.sql.addAll(rec.children);
                    }
                    Node first = targets.isEmpty() ? null : targets.getFirst();
                    for (Rec child : rec.children) {
                        child.node = first;
                    }
                }
                case "remote" -> {
                    List<Node> targets = nodesFor(rec.loaderKey, rec.t0);
                    for (Node node : targets) {
                        node.batches++;
                        node.keys.addAll(rec.keys);
                        node.ready = node.ready == null ? rec.t1 : Math.max(node.ready, rec.t1);
                        node.remoteThread = rec.thread;
                        if (rec.error != null && node.error == null) {
                            node.error = rec.error;
                        }
                    }
                    rec.node = targets.isEmpty() ? null : targets.getFirst();
                }
                default -> {
                }
            }
        }
        for (Rec rec : records) {
            if ("sql".equals(rec.kind) && rec.batch == null && rec.node != null) {
                rec.node.sql.add(rec);
            }
        }
        // Wartezeiten ausserhalb eines Felds gehoeren zum langsamsten Remote-Aufruf, auf den gewartet wurde.
        for (Rec wait : records) {
            if (!"wait".equals(wait.kind) || wait.node != null) {
                continue;
            }
            Rec slowest = null;
            for (Rec call : records) {
                if ("remote".equals(call.kind) && call.node != null && call.t0 <= wait.t1 && call.t1 >= wait.t0
                        && (slowest == null || call.t1 > slowest.t1)) {
                    slowest = call;
                }
            }
            wait.node = slowest != null ? slowest.node : root;
        }
    }

    private List<Node> nodesFor(String loaderKey, double t) {
        List<Node> out = new ArrayList<>();
        if (loaderKey == null) {
            return out;
        }
        Node latest = null;
        for (Node node : nodes) {
            if (!loaderKey.equals(node.loaderKey)) {
                continue;
            }
            if (node.t0 <= t + 1e-6 && t <= node.t1 + 1e-6) {
                out.add(node);
            }
            if (node.t0 <= t && (latest == null || node.t0 > latest.t0)) {
                latest = node;
            }
        }
        if (out.isEmpty() && latest != null) {
            out.add(latest);
        }
        return out;
    }

    /** Die Ebenen der Ausfuehrung als Zeitfenster - nacheinander, wie graphql-java sie abarbeitet. */
    private List<Seg> levels(double execAt) {
        List<Seg> levels = new ArrayList<>();
        int maxLevel = nodes.stream().filter(Node::isField).mapToInt(n -> n.level).max().orElse(0);
        double previousEnd = execAt;
        for (int level = 1; level <= maxLevel; level++) {
            final int current = level;
            List<Node> at = nodes.stream().filter(n -> n.isField() && n.level == current).toList();
            if (at.isEmpty()) {
                continue;
            }
            double start = Math.max(previousEnd, at.stream().mapToDouble(n -> n.t0).min().orElse(previousEnd));
            double end = at.stream().mapToDouble(n -> n.t1).max().orElse(start);
            if (end <= start) {
                continue;
            }
            Node first = at.stream().min(Comparator.comparingDouble(n -> n.t0)).orElse(root);
            String label = level == 1 && "mutation".equals(kind) ? "Mutation-Feld" : "Ebene " + level;
            levels.add(new Seg(start, end, label, "level", first, level));
            previousEnd = end;
        }
        return levels;
    }

    private List<SpanView> threadLane(List<Seg> levels, double total) {
        List<Seg> lane = new ArrayList<>();
        double execAt = execStart >= 0 ? execStart : total;
        lane.add(new Seg(0, execAt, execStart >= 0 ? "parse \u00b7 validate \u00b7 Guardrails" : "parse \u00b7 validate \u2192 abgelehnt",
                "work", root, 0));
        List<Seg> overlays = new ArrayList<>();
        for (Rec rec : records) {
            if ("step".equals(rec.kind) || "wait".equals(rec.kind)) {
                overlays.add(new Seg(rec.t0, rec.t1, rec.label, rec.kind, rec.node, 0));
            }
        }
        overlays.sort(Comparator.comparingDouble(s -> s.t0));
        double minPart = Math.max(0.05, total * 0.004);
        for (Seg level : levels) {
            double cursor = level.t0;
            for (Seg overlay : overlays) {
                if (overlay.t1 <= cursor || overlay.t0 >= level.t1) {
                    continue;
                }
                if (overlay.t0 - cursor >= minPart) {
                    lane.add(level.part(cursor, overlay.t0));
                }
                cursor = Math.max(cursor, overlay.t1);
            }
            if (level.t1 - cursor >= minPart) {
                lane.add(level.part(cursor, level.t1));
            }
        }
        lane.addAll(overlays);
        lane.sort(Comparator.comparingDouble(s -> s.t0));
        double last = lane.stream().mapToDouble(s -> s.t1).max().orElse(execAt);
        if (total - last >= 0.01) {
            lane.add(new Seg(last, total, "ExecutionResult", "work", root, 0));
        }
        List<SpanView> out = new ArrayList<>();
        for (Seg seg : lane) {
            out.add(new SpanView("thread", seg.kind, r3(seg.t0), r3(seg.t1), seg.label, nodeId(seg.node), null, null, -1,
                    requestThread));
        }
        return out;
    }

    private NodeView view(Node node) {
        List<String> sql = new ArrayList<>();
        long rows = 0;
        for (Rec rec : node.sql) {
            if (sql.size() < MAX_SQL) {
                sql.add(rec.detail);
            }
            rows += Math.max(0, rec.count);
        }
        List<String> keys = new ArrayList<>();
        for (String key : node.keys) {
            if (keys.size() == MAX_KEYS) {
                keys.add("\u2026 +" + (node.keys.size() - MAX_KEYS));
                break;
            }
            keys.add(key);
        }
        String thread = node.remoteThread != null ? node.remoteThread : node.thread;
        return new NodeView(node.id, node.parent == null ? null : node.parent.id, node.level, node.kind, node.path,
                node.field, sig(node), node.owner, node.type, node.source, node.sourceName, node.via, node.resolver,
                node.loaderKey, node.qline, chips(node), node.aux, node.step, r3(node.t0), r3(node.t1),
                node.ready == null ? null : r3(node.ready), node.loads, node.items, node.countLabel, node.retLabel,
                List.copyOf(keys), node.batches, List.copyOf(sql), rows, thread, node.args, node.error);
    }

    private static String sig(Node node) {
        if (node.parent == null) {
            return "GraphQLController.execute";
        }
        if (node.step) {
            return node.stepInfo.sig();
        }
        return node.owner + " \u2192 " + node.type;
    }

    private static List<String> chips(Node node) {
        if (node.chips != null) {
            return node.chips;
        }
        if (node.step) {
            return node.retLabel.isEmpty() ? List.of() : List.of(node.retLabel);
        }
        Set<String> children = new LinkedHashSet<>();
        for (Node child : node.children) {
            if (child.isField()) {
                children.add(child.resultKey);
            }
        }
        List<String> chips = new ArrayList<>();
        for (String selection : node.selections) {
            if (!children.contains(selection)) {
                chips.add(selection);
            }
        }
        return List.copyOf(chips);
    }

    private static String countLabel(Node node) {
        if (node.parent == null) {
            return "200";
        }
        if (node.error != null) {
            return "Fehler";
        }
        return switch (node.kind) {
            case "step" -> "";
            case "mutation" -> node.note != null ? node.note : "ok";
            case "remote" -> node.items + (node.items == 1 ? " Wert" : " Werte");
            case "search" -> node.items + " Treffer";
            case "timestamps" -> node.items + (node.items == 1 ? " Zeitpunkt" : " Zeitpunkte");
            case "log" -> node.items + (node.items == 1 ? " Event" : " Events");
            case "relations" -> "Relations";
            default -> node.items + (node.items == 1 ? " Objekt" : " Objekte");
        };
    }

    private static String retLabel(Node node) {
        if (node.error != null) {
            return "Fehler";
        }
        if (node.aux) {
            return node.items + (node.items == 1 ? " ID" : " IDs");
        }
        if (node.step) {
            StepInfo info = node.stepInfo;
            if (info.one() == null) {
                return node.note != null ? node.note : "";
            }
            return node.items + " " + (node.items == 1 ? info.one() : info.many());
        }
        return node.countLabel;
    }

    // ------------------------------------------------------------------ Hilfen

    private Node register(Node node) {
        nodes.add(node);
        byKey.put(node.key, node);
        if (node.parent != null) {
            node.parent.children.add(node);
        }
        return node;
    }

    private String nextNodeId() {
        return "n" + (++nodeIds);
    }

    private String nodeId(Node node) {
        return node == null ? root.id : node.id;
    }

    private double now() {
        return (System.nanoTime() - startNanos) / 1_000_000.0;
    }

    private int lineContaining(String needle, int from) {
        for (int i = Math.max(0, from); i < lines.length; i++) {
            if (lines[i].contains(needle)) {
                return i;
            }
        }
        return Math.max(0, from);
    }

    private static int line(SourceLocation location) {
        return location == null ? 0 : Math.max(0, location.getLine() - 1);
    }

    private static List<String> selections(DataFetchingEnvironment env) {
        if (GraphQLTypeUtil.isLeaf(env.getFieldType())) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (SelectedField selected : env.getSelectionSet().getImmediateFields()) {
            String name = selected.getResultKey();
            if (!name.startsWith("__") && !names.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    private static String arguments(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            if (entry.getValue() != null) {
                parts.add(entry.getKey() + ": " + describe(entry.getValue()));
            }
        }
        return parts.isEmpty() ? null : shorten(String.join(" \u00b7 ", parts));
    }

    private static String describe(Object value) {
        if (value instanceof Map<?, ?> map) {
            List<String> parts = new ArrayList<>();
            map.forEach((k, v) -> parts.add(k + ": " + describe(v)));
            return "{ " + String.join(", ", parts) + " }";
        }
        if (value instanceof Collection<?> list) {
            List<String> parts = new ArrayList<>();
            list.forEach(v -> parts.add(describe(v)));
            return "[" + String.join(", ", parts) + "]";
        }
        if (value instanceof CharSequence text) {
            return "\"" + text + "\"";
        }
        return String.valueOf(value);
    }

    private static String prettyFilter(String filter) {
        return filter == null ? "" : filter.replace("=", ": ").replace("{", "{ ").replace("}", " }");
    }

    private static String shorten(String text) {
        return text == null || text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT - 1) + "\u2026";
    }

    private static List<String> strings(Collection<?> values) {
        List<String> out = new ArrayList<>(values.size());
        for (Object value : values) {
            out.add(String.valueOf(value));
        }
        return out;
    }

    private static String message(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }

    private static String threadName() {
        Thread thread = Thread.currentThread();
        return thread.isVirtual() ? "virtual-" + thread.threadId() : thread.getName();
    }

    private static double r3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private static boolean allNull(Object data) {
        if (!(data instanceof Map<?, ?> map)) {
            return true;
        }
        return map.values().stream().allMatch(v -> v == null);
    }

    private static Map<String, Object> specification(ExecutionResult result) {
        try {
            return result.toSpecification();
        } catch (RuntimeException ex) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("errors", List.of(Map.of("message", "Antwort nicht darstellbar: " + ex.getMessage())));
            return fallback;
        }
    }

    // ------------------------------------------------------------------ Datenstrukturen

    private static final class Node {
        final String id;
        final String key;
        final Node parent;
        final int level;
        final String kind;
        String resultKey = "";
        String field = "";
        String owner = "";
        String type = "";
        String source = "db";
        String sourceName = "";
        String via = "";
        String resolver = "";
        String loaderKey;
        String path = "/";
        String args;
        String note;
        String error;
        String thread;
        String remoteThread;
        String countLabel = "";
        String retLabel = "";
        StepInfo stepInfo;
        List<String> selections = List.of();
        List<String> chips;
        int qline;
        int loads;
        int done;
        int errors;
        int batches;
        int stepCount;
        int stepIndex;
        long items;
        double t0 = -1;
        double t1 = -1;
        Double ready;
        boolean aux;
        boolean step;
        final Set<String> keys = new LinkedHashSet<>();
        final List<Rec> sql = new ArrayList<>();
        final List<Node> children = new ArrayList<>();

        Node(String id, String key, Node parent, int level, String kind) {
            this.id = id;
            this.key = key;
            this.parent = parent;
            this.level = level;
            this.kind = kind;
        }

        boolean isField() {
            return parent != null && !aux && !step;
        }
    }

    /** Ein gemeldeter Abschnitt: Batch, SQL, Remote-Aufruf, Suche, Wartezeit oder Schritt. */
    private final class Rec implements Span {
        final String lane;
        final String kind;
        final String label;
        final double t0;
        double t1 = -1;
        long count = -1;
        String note;
        String error;
        String detail;
        String thread;
        String source;
        String loaderKey;
        List<String> keys = List.of();
        Node node;
        Rec batch;
        final List<Rec> children = new ArrayList<>();
        Consumer<Rec> onClose;
        boolean closed;

        Rec(String lane, String kind, String label) {
            this.lane = lane;
            this.kind = kind;
            this.label = label;
            this.t0 = now();
        }

        @Override
        public Span result(long value) {
            synchronized (TraceCollector.this) {
                count = value;
            }
            return this;
        }

        @Override
        public Span note(String text) {
            synchronized (TraceCollector.this) {
                // Beim SQL ist die Notiz der Text mit eingesetzten Parametern.
                if ("sql".equals(kind)) {
                    detail = text;
                } else {
                    note = text;
                }
            }
            return this;
        }

        @Override
        public void fail(Throwable failure) {
            synchronized (TraceCollector.this) {
                error = message(failure);
            }
        }

        @Override
        public void close() {
            synchronized (TraceCollector.this) {
                if (closed) {
                    return;
                }
                closed = true;
                if (!finished) {
                    t1 = now();
                    if (onClose != null) {
                        onClose.accept(this);
                    }
                }
            }
        }
    }

    /** Ein Balken der Thread-Spur. */
    private record Seg(double t0, double t1, String label, String kind, Node node, int level) {

        Seg part(double from, double to) {
            return new Seg(from, to, label, kind, node, level);
        }
    }
}
