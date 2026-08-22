package com.github.tth05.scnet;

/**
 * The lifecycle state of a client connection.
 */
public enum ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    CLOSING
}
