package com.github.tth05.scnet.message;

import io.netty.buffer.ByteBuf;

public interface IMessageIncoming extends IMessage {

    default void write(ByteBuf messageByteBuffer) {
        throw new UnsupportedOperationException();
    }

    void read(ByteBuf messageByteBuffer);
}
