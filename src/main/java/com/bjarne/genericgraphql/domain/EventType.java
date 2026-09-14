package com.bjarne.genericgraphql.domain;

public enum EventType {
    CREATE("create"),
    UPDATE("update"),
    CORRECT("correct"),
    CLEAR("clear"),
    SNAPSHOT("snapshot");

    public static final String SNAPSHOT_VALUE = "snapshot";

    private final String value;

    EventType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static EventType fromValue(String value) {
        for (EventType type : values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unbekannter Event-Typ: " + value);
    }
}
