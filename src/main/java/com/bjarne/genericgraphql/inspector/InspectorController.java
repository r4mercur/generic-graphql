package com.bjarne.genericgraphql.inspector;

import com.bjarne.genericgraphql.remote.SimulatedRemoteService;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.DigestUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Die Inspector-Seite: Thymeleaf rendert Seite, Request-Liste und Detail-Panel; Call Tree,
 * Timeline und Animation zeichnet {@code static/inspector/app.js} aus dem JSON des Mitschnitts.
 * Neue Mitschnitte kommen per Server-Sent Events.
 */
@Controller
@RequestMapping("/inspector")
@ConditionalOnProperty(name = "inspector.enabled", havingValue = "true", matchIfMissing = true)
public class InspectorController {

    private final TraceHub hub;
    private final InspectorDetails details;
    private final List<SimulatedRemoteService<?>> services;
    private final Environment environment;
    // Haengt an den URLs von app.js und app.css: geaenderte Dateien laedt der Browser neu,
    // statt eine zwischengespeicherte alte Fassung zu benutzen.
    private final String assetVersion = assetVersion();

    public InspectorController(TraceHub hub,
                               InspectorDetails details,
                               List<SimulatedRemoteService<?>> services,
                               Environment environment) {
        this.hub = hub;
        this.details = details;
        this.services = services;
        this.environment = environment;
    }

    // ------------------------------------------------------------------ Seite und Fragmente

    @GetMapping
    public String page(Model model) {
        model.addAttribute("traces", summaries());
        model.addAttribute("queries", InspectorExamples.queries());
        model.addAttribute("mutations", InspectorExamples.mutations());
        model.addAttribute("services", serviceViews());
        model.addAttribute("versions", versions());
        model.addAttribute("settings", settings());
        model.addAttribute("assetVersion", assetVersion);
        return "inspector";
    }

    @GetMapping("/fragments/list")
    public String list(Model model) {
        model.addAttribute("traces", summaries());
        return "inspector/fragments :: list";
    }

    @GetMapping("/fragments/node")
    public String node(@RequestParam("trace") long traceId, @RequestParam("node") String nodeId, Model model) {
        TraceView trace = trace(traceId);
        NodeView node = trace.node(nodeId);
        if (node == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Knoten " + nodeId + " nicht im Mitschnitt " + traceId);
        }
        model.addAttribute("trace", trace);
        model.addAttribute("node", node);
        model.addAttribute("d", details.details(trace, node));
        return "inspector/fragments :: node";
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter stream() {
        return hub.subscribe();
    }

    // ------------------------------------------------------------------ JSON

    @GetMapping("/api/traces")
    @ResponseBody
    public List<TraceSummary> traces() {
        return summaries();
    }

    @GetMapping("/api/traces/{id}")
    @ResponseBody
    public TraceView trace(@PathVariable("id") long id) {
        return hub.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kein Mitschnitt " + id));
    }

    /** Was das Detail-Panel zu einem Knoten zeigt - dieselben Daten wie das Fragment, als JSON. */
    @GetMapping("/api/traces/{id}/nodes/{nodeId}")
    @ResponseBody
    public NodeDetails nodeDetails(@PathVariable("id") long id, @PathVariable("nodeId") String nodeId) {
        TraceView trace = trace(id);
        NodeView node = trace.node(nodeId);
        if (node == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Knoten " + nodeId + " nicht im Mitschnitt " + id);
        }
        return details.details(trace, node);
    }

    @DeleteMapping("/api/traces")
    @ResponseBody
    public Map<String, Object> clear() {
        hub.clear();
        return Map.of("cleared", true);
    }

    @GetMapping("/api/services")
    @ResponseBody
    public List<ServiceView> serviceViews() {
        return services.stream().map(InspectorController::view).toList();
    }

    @PostMapping("/api/services/{id}")
    @ResponseBody
    public ServiceView toggle(@PathVariable("id") String id, @RequestBody Map<String, Object> body) {
        SimulatedRemoteService<?> service = services.stream()
                .filter(candidate -> FieldClassifier.lane(candidate.name()).equals(id))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kein Service " + id));
        Object available = body.get("available");
        if (!(available instanceof Boolean value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Erwartet: {\"available\": true|false}");
        }
        service.available(value);
        return view(service);
    }

    // ------------------------------------------------------------------ Hilfen

    private List<TraceSummary> summaries() {
        return hub.recent().stream().map(TraceView::summary).toList();
    }

    private static ServiceView view(SimulatedRemoteService<?> service) {
        return new ServiceView(FieldClassifier.lane(service.name()), service.name(), service.isAvailable());
    }

    private List<String> versions() {
        List<String> versions = new ArrayList<>();
        versions.add("Spring Boot " + SpringBootVersion.getVersion());
        versions.add(version("graphql-java", graphql.GraphQL.class, "26.1"));
        versions.add(version("java-dataloader", org.dataloader.DataLoader.class, "5.0.0"));
        String url = environment.getProperty("spring.datasource.url", "");
        versions.add(url.startsWith("jdbc:h2:mem:") ? "H2 in-memory" : url.replaceAll("\\?.*$", ""));
        return versions;
    }

    private List<String> settings() {
        List<String> settings = new ArrayList<>();
        for (String key : List.of("gql.max-depth", "gql.max-complexity", "remote.trust.latency-ms",
                "remote.presence.latency-ms", "inspector.max-traces")) {
            String value = environment.getProperty(key);
            if (value != null) {
                settings.add(key + "=" + value);
            }
        }
        return settings;
    }

    private static String assetVersion() {
        try (InputStream js = new ClassPathResource("static/inspector/app.js").getInputStream();
             InputStream css = new ClassPathResource("static/inspector/app.css").getInputStream()) {
            return DigestUtils.md5DigestAsHex(new SequenceInputStream(js, css)).substring(0, 10);
        } catch (IOException ex) {
            return Long.toString(System.currentTimeMillis(), 36);
        }
    }

    private static String version(String name, Class<?> type, String fallback) {
        String version = type.getPackage() == null ? null : type.getPackage().getImplementationVersion();
        return name + " " + (version != null ? version : fallback);
    }
}
