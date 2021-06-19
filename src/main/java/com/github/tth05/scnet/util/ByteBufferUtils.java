package com.github.tth05.scnet.util;

import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

public class ByteBufferUtils {

    public static boolean readAtLeastLimitBlocking(SocketChannel socketChannel, ByteBuffer buffer) {
        return readAtLeastBlocking(socketChannel, buffer, buffer.limit());
    }

    public static boolean readAtLeastBlocking(SocketChannel socketChannel, ByteBuffer buffer, int toRead) {
        try {
            int bytesRead = socketChannel.read(buffer);
            while (bytesRead > 0 || buffer.position() < toRead) {
                bytesRead = socketChannel.read(buffer);
                if (bytesRead == -1)
                    return false;
            }

            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static ByteBuffer moveToNewDirectBuffer(ByteBuffer oldBuffer, int newSize) {
        ByteBuffer newBuffer = ByteBuffer.allocateDirect(newSize);
        newBuffer.put(oldBuffer);
        return newBuffer;
    }

    public static ByteBuffer moveToNewBuffer(ByteBuffer oldBuffer, int newSize) {
        ByteBuffer newBuffer = ByteBuffer.allocate(newSize);
        newBuffer.put(oldBuffer);
        return newBuffer;
    }

    public static void moveToFrontAndClear(ByteBuffer buffer, int offset) {
        byte[] tmp = new byte[buffer.limit() - offset];
        buffer.position(offset);
        buffer.get(tmp);
        buffer.clear();
        buffer.put(tmp);
    }
}
