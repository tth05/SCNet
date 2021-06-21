package com.github.tth05.scnet.message;

import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import org.jetbrains.annotations.NotNull;

/**
 * The base class for any message which can be sent or received over the network.
 */
public interface IMessage {

    /**
     * Called when a message of this type arrives. Read all data here. This should match the data that is written in
     * {@link #write(ByteBufferOutputStream)}.
     * <br><br>
     * <strong>write:</strong><br>
     * writeInt, writeInt, writeShort, writeByte<br>
     * <strong>read:</strong><br>
     * readInt, readInt, readShort, readByte
     *
     * @param messageByteBuffer the input stream to read from
     */
    void read(@NotNull ByteBufferInputStream messageByteBuffer);

    /**
     * Called when a message of this type is being sent. Write all data here. This should match the data that is read in
     * {@link #read(ByteBufferInputStream)}.
     * <br><br>
     * <strong>write:</strong><br>
     * writeInt, writeInt, writeShort, writeByte<br>
     * <strong>read:</strong><br>
     * readInt, readInt, readShort, readByte
     *
     * @param messageByteBuffer the output stream to write to
     */
    void write(@NotNull ByteBufferOutputStream messageByteBuffer);
}
