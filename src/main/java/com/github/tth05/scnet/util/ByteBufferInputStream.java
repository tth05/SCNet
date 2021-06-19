package com.github.tth05.scnet.util;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class ByteBufferInputStream {

    private final ByteBuffer buf;

    public ByteBufferInputStream(ByteBuffer buffer) {
        this.buf = buffer;
    }

    public int readByte() {
        return this.buf.get();
    }

    public byte[] readByteArray(int length) {
        byte[] ar = new byte[length];
        readByteArray(ar, 0, length);
        return ar;
    }

    public void readByteArray(byte[] ar, int offset, int length) {
        this.buf.get(ar, offset, length);
    }

    public short readShort() {
        return this.buf.getShort();
    }

    public int readInt() {
        return this.buf.getInt();
    }

    public String readString() {
        int length = this.buf.getInt();
        return new String(readByteArray(length), StandardCharsets.UTF_8);
    }
}
