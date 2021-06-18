package com.github.tth05.scnet.message;

import java.nio.ByteBuffer;

public interface IMessage {

    void read(ByteBuffer messageByteBuffer);

    void write(ByteBuffer messageByteBuffer);
}
