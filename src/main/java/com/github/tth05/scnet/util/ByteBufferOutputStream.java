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

    public static final int DEFAULT_MAX_STRING_BYTES = 16 * 1024 * 1024;

    /**
     * The internal {@link ByteBuffer} used to write bytes to.
     */
    @NotNull
    private ByteBuffer buf;

    private final int maxCapacity;

    private final int maxStringBytes;

    public ByteBufferOutputStream() {
        this(32);
    }

    public ByteBufferOutputStream(int size) {
        this(size, Integer.MAX_VALUE - 8, DEFAULT_MAX_STRING_BYTES);
    }

    public ByteBufferOutputStream(int size, int maxCapacity, int maxStringBytes) {
        validateLimits(size, maxCapacity, maxStringBytes);
        this.buf = ByteBuffer.allocate(size);
        this.maxCapacity = maxCapacity;
        this.maxStringBytes = maxStringBytes;
    }

    public ByteBufferOutputStream(@NotNull ByteBuffer buffer) {
        this(buffer, Integer.MAX_VALUE - 8, DEFAULT_MAX_STRING_BYTES);
    }

    public ByteBufferOutputStream(@NotNull ByteBuffer buffer, int maxCapacity, int maxStringBytes) {
        if (buffer.isDirect())
            throw new IllegalArgumentException("Direct buffer not allowed");

        validateLimits(buffer.capacity(), maxCapacity, maxStringBytes);

        buffer.clear();
        this.buf = buffer;
        this.maxCapacity = maxCapacity;
        this.maxStringBytes = maxStringBytes;
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

    public void writeLong(long l) {
        ensureFits(8);
        this.buf.putLong(l);
    }

    /**
     * Writes the length of the given String followed by the bytes of the String to this output stream. Works in
     * conjunction with {@link ByteBufferInputStream#readString()}.
     *
     * @param s the String to write
     */
    public void writeString(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > this.maxStringBytes) {
            throw new IllegalArgumentException(
                    "String length " + bytes.length + " exceeds maximum " + this.maxStringBytes
            );
        }
        ensureFits(bytes.length + 4);
        this.buf.putInt(bytes.length);
        this.buf.put(bytes);
    }

    /**
     * Adjusts the internal buffer so that it can hold at least {@code i} more bytes by doubling its size if needed.
     */
    private void ensureFits(int i) {
        if (i < 0) {
            throw new IllegalArgumentException("Additional size cannot be negative");
        }
        int position = this.buf.position();
        long required = (long) position + i;
        if (required > this.maxCapacity) {
            throw new IllegalArgumentException(
                    "Serialized message size " + required + " exceeds maximum " + this.maxCapacity
            );
        }
        if (this.buf.capacity() < required) {
            this.buf.flip();
            long doubled = Math.max(1L, (long) this.buf.capacity() * 2L);
            int newCapacity = (int) Math.min(this.maxCapacity, Math.max(required, doubled));
            this.buf = ByteBufferUtils.moveToNewBuffer(this.buf, newCapacity);
        }
    }

    private static void validateLimits(int initialSize, int maxCapacity, int maxStringBytes) {
        if (initialSize < 0) {
            throw new IllegalArgumentException("Negative initial size: " + initialSize);
        }
        if (maxCapacity < initialSize) {
            throw new IllegalArgumentException("maxCapacity cannot be smaller than the initial size");
        }
        if (maxStringBytes < 0) {
            throw new IllegalArgumentException("maxStringBytes cannot be negative");
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
