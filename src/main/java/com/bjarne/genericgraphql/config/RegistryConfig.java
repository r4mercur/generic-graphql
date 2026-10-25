package com.bjarne.genericgraphql.config;

import com.bjarne.genericgraphql.domain.AppUser;
import com.bjarne.genericgraphql.domain.Interest;
import com.bjarne.genericgraphql.domain.Match;
import com.bjarne.genericgraphql.domain.Message;
import com.bjarne.genericgraphql.domain.Photo;
import com.bjarne.genericgraphql.domain.Profile;
import com.bjarne.genericgraphql.domain.ProfileInterest;
import com.bjarne.genericgraphql.engine.registry.GqlEntityRegistration;
import com.bjarne.genericgraphql.engine.registry.GqlRegistry;
import com.bjarne.genericgraphql.engine.registry.ResolverType;
import com.bjarne.genericgraphql.engine.remote.RemoteField;
import com.bjarne.genericgraphql.engine.scope.AccessPolicy;
import com.bjarne.genericgraphql.remote.presence.PresenceInfo;
import com.bjarne.genericgraphql.remote.presence.PresenceServiceClient;
import com.bjarne.genericgraphql.remote.trust.TrustInfo;
import com.bjarne.genericgraphql.remote.trust.TrustServiceClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RegistryConfig {

    private static final String[] TECHNICAL_FILTER_FIELDS = {"event", "deleted", "clearedField"};

    private static final String[] TECHNICAL_INPUT_FIELDS = {"objectId", "createdAt", "changedBy"};

    @Bean
    public GqlRegistry gqlRegistry(TrustServiceClient trustService, PresenceServiceClient presenceService) {
        GqlRegistry registry = new GqlRegistry();

        // trust und presence liegen nicht in dieser Datenbank, sondern in zwei fremden
        // Services. Auf derselben Ebene abgefragt, laufen beide Aufrufe parallel.
        // Der Stichtag wird nicht weitergegeben - keiner der Services kennt eine Historie.
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
        registry.register(eventSourced(Interest.class, "interest").build());
        registry.register(eventSourced(ProfileInterest.class, "profileInterest").build());
        registry.register(eventSourced(Photo.class, "photo").build());
        registry.register(eventSourced(Match.class, "match").build());
        registry.register(eventSourced(Message.class, "message").build());

        registry.register(GqlEntityRegistration.of(AppUser.class)
                .endpointName("appUser")
                .resolverType(ResolverType.DEFAULT)
                .idAttribute("id")
                .build());

        return registry;
    }

    private static GqlEntityRegistration.Builder eventSourced(Class<?> entityClass, String endpoint) {
        return GqlEntityRegistration.of(entityClass)
                .endpointName(endpoint)
                .resolverType(ResolverType.LATEST)
                .idAttribute("objectRefId")
                .filterExclude(TECHNICAL_FILTER_FIELDS)
                .orderByExclude(TECHNICAL_FILTER_FIELDS)
                .inputExclude(TECHNICAL_INPUT_FIELDS);
    }

    @Bean
    public AccessPolicy accessPolicy() {
        return AccessPolicy.permitAll();
    }
}
