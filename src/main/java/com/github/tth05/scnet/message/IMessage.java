package com.github.tth05.scnet.message;

import io.netty.buffer.ByteBuf;

public interface IMessage {

    void read(ByteBuf messageByteBuffer);

    void write(ByteBuf messageByteBuffer);
}
