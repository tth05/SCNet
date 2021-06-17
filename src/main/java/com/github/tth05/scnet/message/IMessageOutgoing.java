package com.github.tth05.scnet.message;

import io.netty.buffer.ByteBuf;

public interface IMessageOutgoing extends IMessage {

    default void read(ByteBuf messageByteBuffer) {
        throw new UnsupportedOperationException();
    }

    void write(ByteBuf messageByteBuffer);
}
