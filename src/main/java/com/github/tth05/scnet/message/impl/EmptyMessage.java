package com.github.tth05.scnet.message.impl;

import com.github.tth05.scnet.message.IMessage;
import io.netty.buffer.ByteBuf;

public class EmptyMessage implements IMessage {

    @Override
    public void read(ByteBuf messageByteBuffer) {
        //NOOP
    }

    @Override
    public void write(ByteBuf messageByteBuffer) {
        //NOOP
    }
}
