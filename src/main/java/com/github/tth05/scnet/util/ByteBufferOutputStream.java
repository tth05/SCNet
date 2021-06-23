package com.github.tth05.scnet.util;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * An output stream which writes bytes to a {@link ByteBuffer}, allocating a new buffer with a bigger size when needed.
 * If a new buffer is allocated, it will replace the old buffer. Users of this class can check if the buffer changed
 * using {@link #getBuffer()} if they've used {@link #ByteBufferOutputStream(ByteBuffer)} to construct this
 * {@link ByteBufferOutputStream}.
 */
public class ByteBufferOutputStream {

    /**
     * The internal {@link ByteBuffer} used to write bytes to.
     */
    @NotNull
    private ByteBuffer buf;

    public ByteBufferOutputStream() {
        this(32);
    }

    public ByteBufferOutputStream(int size) {
        if (size < 0)
            throw new IllegalArgumentException("Negative initial size: " + size);

        this.buf = ByteBuffer.allocate(size);
    }

    public ByteBufferOutputStream(@NotNull ByteBuffer buffer) {
        if (buffer.isDirect())
            throw new IllegalArgumentException("Direct buffer not allowed");

        buffer.clear();
        this.buf = buffer;
    }

    public void writeByte(int b) {
        ensureFits(1);
        this.buf.put((byte) b);
    }

    public void writeByteArray(byte @NotNull [] ar) {
        writeByteArray(ar, 0, ar.length);
    }

    public void writeByteArray(byte @NotNull [] ar, int offset, int length) {
        ensureFits(length);
        this.buf.put(ar, offset, length);
    }

    public void writeBoolean(boolean b) {
        writeByte(b ? 1 : 0);
    }

    public void writeShort(short i) {
        ensureFits(2);
        this.buf.putShort(i);
    }

    public void writeInt(int i) {
        ensureFits(4);
        this.buf.putInt(i);
    }

    /**
     * Writes the length of the given String followed by the bytes of the String to this output stream. Works in
     * conjunction with {@link ByteBufferInputStream#readString()}.
     *
     * @param s the String to write
     */
    public void writeString(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        ensureFits(bytes.length + 4);
        this.buf.putInt(bytes.length);
        this.buf.put(bytes);
    }

    /**
     * Adjusts the internal buffer so that it can hold at least {@code i} more bytes.
     */
    private void ensureFits(int i) {
        int position = this.buf.position();
        if (this.buf.capacity() < position + i) {
            this.buf.flip();
            this.buf = ByteBufferUtils.moveToNewBuffer(this.buf, position + i);
        }
    }

    /**
     * @return the internal {@link ByteBuffer} of this output stream.
     */
    @NotNull
    @Contract(pure = true)
    public ByteBuffer getBuffer() {
        return this.buf;
    }
}
