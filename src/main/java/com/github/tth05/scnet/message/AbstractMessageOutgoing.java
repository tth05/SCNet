package com.github.tth05.scnet.message;

import com.github.tth05.scnet.util.ByteBufferInputStream;
import org.jetbrains.annotations.NotNull;

/**
 * A simple helper class for messages which can only be written and not read.
 */
public abstract class AbstractMessageOutgoing extends AbstractMessage {

    @Override
    public void read(@NotNull ByteBufferInputStream messageByteBuffer) {
        throw new UnsupportedOperationException();
    }
}
