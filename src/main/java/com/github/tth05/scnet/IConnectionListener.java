package com.github.tth05.scnet;

public interface IConnectionListener {

    void onConnected();

    void onDisconnected();

    /**
     * Called before {@link #onDisconnected()} when a connection ends because of an error.
     *
     * @param cause the error which ended the connection
     */
    default void onConnectionError(Throwable cause) {
    }
}
