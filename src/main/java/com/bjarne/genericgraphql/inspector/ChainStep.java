package com.bjarne.genericgraphql.inspector;

/** Ein Schritt der Aufrufkette im Detail-Panel: Aufruf, Fundstelle im Code, Erlaeuterung. */
public record ChainStep(String sig, String ref, String note) {

    public static ChainStep of(String sig, String ref) {
        return new ChainStep(sig, ref, null);
    }

    public static ChainStep of(String sig, String ref, String note) {
        return new ChainStep(sig, ref, note);
    }

    public boolean hasRef() {
        return ref != null && !ref.isEmpty();
    }

    public boolean hasNote() {
        return note != null && !note.isEmpty();
    }
}
