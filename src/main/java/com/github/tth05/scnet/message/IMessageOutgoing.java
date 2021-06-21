package com.github.tth05.scnet.message;

import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import org.jetbrains.annotations.NotNull;

/**
 * A simple helper class for messages which can only be written and not read.
 */
public interface IMessageOutgoing extends IMessage {

    default void read(@NotNull ByteBufferInputStream messageByteBuffer) {
        throw new UnsupportedOperationException();
    }

    void write(@NotNull ByteBufferOutputStream messageByteBuffer);
}
