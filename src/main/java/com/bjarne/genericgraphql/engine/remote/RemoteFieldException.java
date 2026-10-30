package com.bjarne.genericgraphql.engine.remote;

/** Ein fremder Service hat nicht, zu spaet oder mit zu vielen Treffern geantwortet. */
public class RemoteFieldException extends RuntimeException {

    public RemoteFieldException(String message) {
        super(message);
    }

    public RemoteFieldException(String message, Throwable cause) {
        super(message, cause);
    }
}
