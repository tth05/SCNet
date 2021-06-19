package com.github.tth05.scnet.message.impl;

import com.github.tth05.scnet.message.IMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

public class EmptyMessage implements IMessage {

    @Override
    public void read(ByteBufferInputStream messageByteBuffer) {
        //NOOP
    }

    @Override
    public void write(ByteBufferOutputStream messageByteBuffer) {
        //NOOP
    }
}
