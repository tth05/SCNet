package com.github.tth05.scnet.util;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;

public class ByteBufferUtils {

    private ByteBufferUtils() {
    }

    /** Copies the remaining bytes into a new heap buffer. */
    @NotNull
    @Contract("_, _ -> new")
    public static ByteBuffer moveToNewBuffer(@NotNull ByteBuffer oldBuffer, int newSize) {
        ByteBuffer newBuffer = ByteBuffer.allocate(newSize);
        newBuffer.put(oldBuffer);
        return newBuffer;
    }

}
