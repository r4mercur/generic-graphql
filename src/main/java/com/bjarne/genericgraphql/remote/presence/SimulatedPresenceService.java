package com.bjarne.genericgraphql.remote.presence;

import com.bjarne.genericgraphql.remote.SimulatedRemoteService;
import java.util.Collection;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Zweiter fremder Microservice - zeigt, dass Remote-Felder einer Ebene parallel laufen. */
@Component
public class SimulatedPresenceService extends SimulatedRemoteService<PresenceInfo> implements PresenceServiceClient {

    private static final Map<String, PresenceInfo> DATA = Map.of(
            "PROF000001", new PresenceInfo(PresenceStatus.ONLINE, true, "ios"),
            "PROF000003", new PresenceInfo(PresenceStatus.AWAY, false, "android"),
            "PROF000005", new PresenceInfo(PresenceStatus.OFFLINE, false, "web"));

    public SimulatedPresenceService(@Value("${remote.presence.latency-ms:80}") long latencyMs) {
        super("Presence-Service", latencyMs);
    }

    @Override
    protected Map<String, PresenceInfo> data() {
        return DATA;
    }

    @Override
    public Map<String, PresenceInfo> batchGet(Collection<String> profileIds) {
        return respond(profileIds);
    }
}
