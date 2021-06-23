package com.github.tth05.scnet.message;

import com.github.tth05.scnet.util.ByteBufferOutputStream;
import org.jetbrains.annotations.NotNull;

/**
 * A simple helper class for messages which can only be read and not written.
 */
public abstract class AbstractMessageIncoming extends AbstractMessage {

    @Override
    public void write(@NotNull ByteBufferOutputStream messageByteBuffer) {
        throw new UnsupportedOperationException();
    }
}
