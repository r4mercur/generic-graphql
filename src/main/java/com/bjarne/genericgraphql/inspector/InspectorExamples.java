package com.bjarne.genericgraphql.inspector;

import java.util.List;

/**
 * Die Beispiele, die die Inspector-Seite mit "Senden" ausloest - woertlich aus
 * docs/GQL2-JAVA-IMPLEMENTIERUNG.md und docs/GQL2-MICROSERVICES.md.
 */
public final class InspectorExamples {

    public static final List<Example> ALL = List.of(
            new Example("MatchCard", "query", """
                    query MatchCard {
                      matchLatestVersions(limit: 5, order: { matchedAt: DESC }) {
                        objectRefId
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
                    """),
            new Example("TrustBadge", "query", """
                    query TrustBadge {
                      matchLatestVersions(order: { matchedAt: DESC }) {
                        objectRefId
                        profileA { displayName trust { level } }
                        profileB { displayName trust { level openReports } }
                      }
                    }
                    """),
            new Example("ProfileStatus", "query", """
                    query ProfileStatus {
                      profileLatestVersions(order: { displayName: ASC }) {
                        displayName
                        trust    { level }
                        presence { status typing }
                      }
                    }
                    """),
            new Example("TrustedInHamburg", "query", """
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
                    """),
            new Example("UpdateProfile", "mutation", """
                    mutation UpdateProfile { profileEvent(data: {
                        event: update, objectRefId: "PROF000003",
                        validFrom: "2026-09-01", city: "Bremen"
                      }) { result message } }
                    """),
            new Example("DeleteProfile", "mutation", """
                    mutation DeleteProfile { profileEvent(data: {
                        event: update, objectRefId: "PROF000001",
                        validFrom: "2026-09-12", deleted: true
                      }) { result message relations { reverseRelations { relation id } } } }
                    """));

    private InspectorExamples() {
    }

    public static List<Example> queries() {
        return ALL.stream().filter(example -> example.kind().equals("query")).toList();
    }

    public static List<Example> mutations() {
        return ALL.stream().filter(example -> example.kind().equals("mutation")).toList();
    }
}
