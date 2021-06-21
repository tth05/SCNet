package com.github.tth05.scnet.message;

import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

/**
 * A simple helper class for messages which can only be read and not written.
 */
public interface IMessageIncoming extends IMessage {

    default void write(ByteBufferInputStream messageByteBuffer) {
        throw new UnsupportedOperationException();
    }

    void read(ByteBufferOutputStream messageByteBuffer);
}
