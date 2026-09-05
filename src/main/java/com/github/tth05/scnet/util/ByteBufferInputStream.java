package com.github.tth05.scnet.util;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * An input stream which reads data from a given {@link ByteBuffer}.
 */
public class ByteBufferInputStream {

    /** Compatibility default matching the range accepted by the original stream implementation. */
    public static final int DEFAULT_MAX_STRING_BYTES = 16 * 1024 * 1024;

    /**
     * The internal buffer which contains the data to read.
     */
    @NotNull
    private final ByteBuffer buf;

    private final int maxStringBytes;

    public ByteBufferInputStream(@NotNull ByteBuffer buffer) {
        this(buffer, DEFAULT_MAX_STRING_BYTES);
    }

    public ByteBufferInputStream(@NotNull ByteBuffer buffer, int maxStringBytes) {
        if (maxStringBytes < 0) {
            throw new IllegalArgumentException("maxStringBytes cannot be negative");
        }
        this.buf = buffer;
        this.maxStringBytes = maxStringBytes;
    }

    public byte readByte() {
        return this.buf.get();
    }

    @Contract("_ -> new")
    public byte @NotNull [] readByteArray(int length) {
        requireAvailable(length, "byte array");
        byte[] ar = new byte[length];
        readByteArray(ar, 0, length);
        return ar;
    }

    public void readByteArray(byte @NotNull [] ar, int offset, int length) {
        requireAvailable(length, "byte array");
        this.buf.get(ar, offset, length);
    }

    public boolean readBoolean() {
        return readByte() != 0;
    }

    public short readShort() {
        return this.buf.getShort();
    }

    public int readInt() {
        return this.buf.getInt();
    }

    public long readLong() {
        return this.buf.getLong();
    }

    /**
     * Reads a String in the format written by {@link ByteBufferOutputStream#writeString(String)}.
     *
     * @return the String
     */
    @NotNull
    @Contract("-> new")
    public String readString() {
        int length = this.buf.getInt();
        if (length < 0) {
            throw new IllegalArgumentException("Negative string length: " + length);
        }
        if (length > this.maxStringBytes) {
            throw new IllegalArgumentException(
                    "String length " + length + " exceeds maximum " + this.maxStringBytes
            );
        }
        requireAvailable(length, "string");
        return new String(readByteArray(length), StandardCharsets.UTF_8);
    }

    private void requireAvailable(int length, String valueType) {
        if (length < 0) {
            throw new IllegalArgumentException("Negative " + valueType + " length: " + length);
        }
        if (length > this.buf.remaining()) {
            throw new IllegalArgumentException(
                    valueType + " length " + length + " exceeds remaining frame bytes " + this.buf.remaining()
            );
        }
    }
}
