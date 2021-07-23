package com.github.tth05.scnet.util;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * An input stream which reads data from a given {@link ByteBuffer}.
 */
public class ByteBufferInputStream {

    /**
     * The internal buffer which contains the data to read.
     */
    @NotNull
    private final ByteBuffer buf;

    public ByteBufferInputStream(@NotNull ByteBuffer buffer) {
        this.buf = buffer;
    }

    public byte readByte() {
        return this.buf.get();
    }

    @Contract("_ -> new")
    public byte @NotNull [] readByteArray(int length) {
        byte[] ar = new byte[length];
        readByteArray(ar, 0, length);
        return ar;
    }

    public void readByteArray(byte @NotNull [] ar, int offset, int length) {
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
        return new String(readByteArray(length), StandardCharsets.UTF_8);
    }
}
