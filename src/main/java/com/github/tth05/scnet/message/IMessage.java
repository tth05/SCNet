package com.github.tth05.scnet.message;

import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

public interface IMessage {

    void read(ByteBufferInputStream messageByteBuffer);

    void write(ByteBufferOutputStream messageByteBuffer);
}
