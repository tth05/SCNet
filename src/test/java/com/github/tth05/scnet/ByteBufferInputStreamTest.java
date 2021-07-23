package com.github.tth05.scnet;

import com.github.tth05.scnet.util.ByteBufferInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class ByteBufferInputStreamTest {

    private ByteBuffer buffer;
    private ByteBufferInputStream stream;

    @BeforeEach
    public void setupStream() {
        buffer = ByteBuffer.allocate(100);
        stream = new ByteBufferInputStream(buffer);
    }

    @Test
    public void testReadByte() {
        buffer.put((byte) 25);
        buffer.put((byte) -25);
        buffer.flip();

        assertEquals(25, stream.readByte());
        assertEquals(-25, stream.readByte());
        assertThrows(BufferUnderflowException.class, stream::readByte);
    }

    @Test
    public void testReadByteArray() {
        byte[] ar = new byte[]{1, 2, 3, 4, 5};
        buffer.put(ar);
        buffer.put(ar, 3, 2);
        buffer.flip();

        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 4, 5}, stream.readByteArray(7));
        assertThrows(BufferUnderflowException.class, stream::readByte);
    }

    @Test
    public void testReadBoolean() {
        buffer.put((byte) 1);
        buffer.put((byte) 0);
        buffer.flip();

        assertTrue(stream.readBoolean());
        assertFalse(stream.readBoolean());
        assertThrows(BufferUnderflowException.class, stream::readByte);
    }

    @Test
    public void testReadShort() {
        buffer.putShort(Short.MAX_VALUE);
        buffer.flip();

        assertEquals(Short.MAX_VALUE, stream.readShort());
        assertThrows(BufferUnderflowException.class, stream::readByte);
    }

    @Test
    public void testReadInt() {
        buffer.putInt(Integer.MAX_VALUE);
        buffer.flip();

        assertEquals(Integer.MAX_VALUE, stream.readInt());
        assertThrows(BufferUnderflowException.class, stream::readByte);
    }

    @Test
    public void testReadLong() {
        buffer.putLong(Long.MAX_VALUE);
        buffer.flip();

        assertEquals(Long.MAX_VALUE, stream.readLong());
        assertThrows(BufferUnderflowException.class, stream::readByte);
    }

    @Test
    public void testReadString() {
        buffer.putInt(14);
        buffer.put("testReadString".getBytes(StandardCharsets.UTF_8));
        buffer.flip();

        assertEquals("testReadString", stream.readString());
        assertThrows(BufferUnderflowException.class, buffer::get);
    }
}
