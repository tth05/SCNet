package com.github.tth05.scnet.message;

import java.nio.ByteBuffer;

public interface IMessageOutgoing extends IMessage {

    default void read(ByteBuffer messageByteBuffer) {
        throw new UnsupportedOperationException();
    }

    void write(ByteBuffer messageByteBuffer);
}
