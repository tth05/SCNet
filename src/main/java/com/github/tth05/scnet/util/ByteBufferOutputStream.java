package com.github.tth05.scnet.util;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class ByteBufferOutputStream {

    private ByteBuffer buf;


    public ByteBufferOutputStream() {
        this(32);
    }

    public ByteBufferOutputStream(int size) {
        if (size < 0)
            throw new IllegalArgumentException("Negative initial size: " + size);

        this.buf = ByteBuffer.allocate(size);
    }

    public ByteBufferOutputStream(ByteBuffer buffer) {
        if (buffer.isDirect())
            throw new IllegalArgumentException("Direct buffer not allowed");

        buffer.clear();
        this.buf = buffer;
    }

    public void writeByte(int b) {
        ensureFits(1);
        this.buf.put((byte) b);
    }

    public void writeByteArray(byte[] ar) {
        writeByteArray(ar, 0, ar.length);
    }

    public void writeByteArray(byte[] ar, int offset, int length) {
        ensureFits(length);
        this.buf.put(ar, offset, length);
    }

    public void writeShort(short i) {
        ensureFits(2);
        this.buf.putShort(i);
    }

    public void writeInt(int i) {
        ensureFits(4);
        this.buf.putInt(i);
    }

    public void writeString(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        ensureFits(bytes.length + 4);
        this.buf.putInt(bytes.length);
        this.buf.put(bytes);
    }

    private void ensureFits(int i) {
        int position = this.buf.position();
        if (this.buf.capacity() < position + i) {
            this.buf.flip();
            this.buf = ByteBufferUtils.moveToNewBuffer(this.buf, position + i);
        }
    }

    public ByteBuffer getBuffer() {
        return this.buf;
    }
}
