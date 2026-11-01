package com.bjarne.genericgraphql;

import static org.assertj.core.api.Assertions.assertThat;

import com.bjarne.genericgraphql.engine.probe.EngineProbe;
import com.bjarne.genericgraphql.inspector.TraceHub;
import com.bjarne.genericgraphql.inspector.TraceInstrumentation;
import com.bjarne.genericgraphql.inspector.TracingDataSource;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/** Mit inspector.enabled=false gibt es weder Mitschnitt noch Seite - die Engine laeuft unveraendert. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:inspector-off;DB_CLOSE_DELAY=-1",
                "inspector.enabled=false"})
class InspectorDisabledIntegrationTest {

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private DataSource dataSource;

    @Test
    void noInspectorBeansAndNoPage() {
        assertThat(context.getBeanNamesForType(TraceInstrumentation.class)).isEmpty();
        assertThat(context.getBeanNamesForType(TraceHub.class)).isEmpty();
        assertThat(dataSource).isNotInstanceOf(TracingDataSource.class);
        assertThat(EngineProbe.current()).isSameAs(EngineProbe.NOOP);

        RestClient client = RestClient.create("http://localhost:" + port);
        int status = client.get().uri("/inspector").exchange((request, response) -> response.getStatusCode().value());
        assertThat(status).isEqualTo(404);

        Map<String, Object> result = client.post().uri("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("query", "{ profileLatestVersions(order: { displayName: ASC }) { displayName } }"))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        assertThat(result).doesNotContainKey("errors");
        assertThat(result.get("data").toString()).contains("Jonas", "Mara", "Sam");
    }
}
