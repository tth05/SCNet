package com.github.tth05.scnet;

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
}
