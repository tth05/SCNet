package com.github.tth05.scnet.message;

import java.nio.ByteBuffer;

public interface IMessageIncoming extends IMessage {

    default void write(ByteBuffer messageByteBuffer) {
        throw new UnsupportedOperationException();
    }

    void read(ByteBuffer messageByteBuffer);
}
