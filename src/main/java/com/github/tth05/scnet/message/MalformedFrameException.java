package com.github.tth05.scnet.message;

import java.io.IOException;

/**
 * Indicates that a peer sent a frame which cannot be decoded safely.
 */
public final class MalformedFrameException extends IOException {

    public MalformedFrameException(String message) {
        super(message);
    }

    public MalformedFrameException(String message, Throwable cause) {
        super(message, cause);
    }
}
