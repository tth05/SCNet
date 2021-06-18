package com.github.tth05.scnet.message.impl;

import com.github.tth05.scnet.message.IMessage;

import java.nio.ByteBuffer;

public class EmptyMessage implements IMessage {

    @Override
    public void read(ByteBuffer messageByteBuffer) {
        //NOOP
    }

    @Override
    public void write(ByteBuffer messageByteBuffer) {
        //NOOP
    }
}
