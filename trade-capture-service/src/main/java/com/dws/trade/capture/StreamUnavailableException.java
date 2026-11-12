package com.dws.trade.capture;

/** The trade could not be handed to the stream; the caller should retry. */
public class StreamUnavailableException extends RuntimeException {

    public StreamUnavailableException(Throwable cause) {
        super("The trade stream is unavailable; the trade was not accepted", cause);
    }
}
