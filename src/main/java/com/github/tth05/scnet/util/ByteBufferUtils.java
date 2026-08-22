package com.github.tth05.scnet.util;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

public class ByteBufferUtils {

    private ByteBufferUtils() {
    }

    /**
     * Reads while the channel makes progress. This method returns instead of spinning when a nonblocking channel would
     * block. New transport code should retain partial state and wait for the next read-ready selector event.
     *
     * @param socketChannel the {@link SocketChannel} to read from
     * @param buffer        the buffer to read the data into
     * @param toRead        the position the buffer should reach before returning
     * @return {@code true} if the requested position was reached; {@code false} on end-of-stream, I/O error, or when a
     * nonblocking channel would block
     * @throws IllegalArgumentException if the given {@code buffer}'s {@link ByteBuffer#limit()} or
     *                                  {@link ByteBuffer#capacity()} is smaller than {@code toRead}
     */
    @Deprecated
    public static boolean readAtLeastBlocking(@NotNull SocketChannel socketChannel, @NotNull ByteBuffer buffer, int toRead) {
        if (buffer.limit() < toRead || buffer.capacity() < toRead)
            throw new IllegalArgumentException("Impossible to read the requested amount of bytes");

        try {
            while (buffer.position() < toRead) {
                int bytesRead = socketChannel.read(buffer);
                if (bytesRead <= 0) {
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Allocates a new direct buffer with the given {@code newSize}. Then the given {@code oldBuffer} is put into the
     * new buffer using {@link ByteBuffer#put(ByteBuffer)}.
     * <br><br>
     * NOTE: {@link ByteBuffer#put(ByteBuffer)} will only copy bytes from the {@code oldBuffer} starting at the old buffer's
     * current position until it's current limit.
     *
     * @param oldBuffer the old buffer
     * @param newSize   the size for the new buffer
     * @return the new buffer
     */
    @NotNull
    @Contract("_, _ -> new")
    public static ByteBuffer moveToNewDirectBuffer(@NotNull ByteBuffer oldBuffer, int newSize) {
        ByteBuffer newBuffer = ByteBuffer.allocateDirect(newSize);
        newBuffer.put(oldBuffer);
        return newBuffer;
    }

    /**
     * Works like {@link #moveToNewDirectBuffer(ByteBuffer, int)} but allocates a non-direct buffer instead.
     */
    @NotNull
    @Contract("_, _ -> new")
    public static ByteBuffer moveToNewBuffer(@NotNull ByteBuffer oldBuffer, int newSize) {
        ByteBuffer newBuffer = ByteBuffer.allocate(newSize);
        newBuffer.put(oldBuffer);
        return newBuffer;
    }

    /**
     * Copies all bytes from the given {@code buffer} to the front of the buffer and discards everything else.
     * The copied range will be [offset;{@code buffer.limit()}[
     * <br><br>
     * <strong>Before</strong>:<br>
     * offset = 4; pos = 0; lim, cap = 9<br>
     * data = [1, 2, 3, 4, 5, 6, 7, 8, 9]<br>
     * <strong>After</strong>:<br>
     * pos = 5; lim, cap = 9<br>
     * data = [5, 6, 7, 8, 9, 6, 7, 8, 9]
     *
     * @param buffer the buffer
     * @param offset the offset to start from
     */
    public static void moveToFrontAndClear(@NotNull ByteBuffer buffer, int offset) {
        if (offset > buffer.limit())
            throw new IllegalArgumentException("offset cannot be greater than the buffer's limit");

        byte[] tmp = new byte[buffer.limit() - offset];
        buffer.position(offset);
        buffer.get(tmp);
        buffer.clear();
        buffer.put(tmp);
    }
}
