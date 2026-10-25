package com.bjarne.genericgraphql.remote.presence;

/** Antwort des Presence-Service zu einem Profil - Live-Daten ohne Historie. */
public record PresenceInfo(PresenceStatus status,
                           Boolean typing,
                           String device) {
}
