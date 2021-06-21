package com.github.tth05.scnet.message.impl;

import com.github.tth05.scnet.message.IMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import org.jetbrains.annotations.NotNull;

/**
 * An empty message containing no data. This is used to test for an open connection and reserves message id {@code 0}.
 * Over the network, the message will look this: {@code [0, 0, 0, 0, 0, 0]}
 */
public class EmptyMessage implements IMessage {

    @Override
    public void read(@NotNull ByteBufferInputStream messageByteBuffer) {
        //NOOP
    }

    @Override
    public void write(@NotNull ByteBufferOutputStream messageByteBuffer) {
        //NOOP
    }
}
