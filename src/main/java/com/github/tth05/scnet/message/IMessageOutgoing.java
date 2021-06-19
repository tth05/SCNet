package com.github.tth05.scnet.message;

import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

public interface IMessageOutgoing extends IMessage {

    default void read(ByteBufferInputStream messageByteBuffer) {
        throw new UnsupportedOperationException();
    }

    void write(ByteBufferOutputStream messageByteBuffer);
}
