package com.github.tth05.scnet;

import com.github.tth05.scnet.util.ByteBufferOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class ByteBufferOutputStreamTest {

    private ByteBufferOutputStream stream;

    @BeforeEach
    public void setupStream() {
        stream = new ByteBufferOutputStream(10);
    }

    @Test
    public void testWriteByte() {
        stream.writeByte(25);
        stream.writeByte(-25);

        ByteBuffer buffer = stream.getBuffer();
        buffer.flip();
        assertEquals(25, buffer.get());
        assertEquals(-25, buffer.get());
        assertThrows(BufferUnderflowException.class, buffer::get);
    }

    @Test
    public void testAutoIncreaseBufferSize() {
        ByteBuffer prevBuffer = stream.getBuffer();

        assertEquals(10, stream.getBuffer().capacity());
        for (int i = 0; i < 15; i++) {
            stream.writeByte(12);
        }

        ByteBuffer buffer = stream.getBuffer();
        assertNotSame(prevBuffer, buffer);
        assertTrue(stream.getBuffer().capacity() >= 15);
        buffer.flip();
        for (int i = 0; i < 15; i++) {
            assertEquals(12, buffer.get());
        }
        assertThrows(BufferUnderflowException.class, buffer::get);
    }

    @Test
    public void testWriteByteArray() {
        byte[] ar = new byte[]{1, 2, 3, 4, 5};
        stream.writeByteArray(ar);
        stream.writeByteArray(ar, 3, 2);

        ByteBuffer buffer = stream.getBuffer();
        buffer.flip();
        byte[] dst = new byte[7];
        buffer.get(dst);
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 4, 5}, dst);
        assertThrows(BufferUnderflowException.class, buffer::get);
    }

    @Test
    public void testWriteBoolean() {
        stream.writeBoolean(true);
        stream.writeBoolean(false);

        ByteBuffer buffer = stream.getBuffer();
        buffer.flip();
        assertEquals(1, buffer.get());
        assertEquals(0, buffer.get());
        assertThrows(BufferUnderflowException.class, buffer::get);
    }

    @Test
    public void testWriteShort() {
        stream.writeShort(Short.MAX_VALUE);

        ByteBuffer buffer = stream.getBuffer();
        buffer.flip();
        assertEquals(Short.MAX_VALUE, buffer.getShort());
        assertThrows(BufferUnderflowException.class, buffer::get);
    }

    @Test
    public void testWriteInt() {
        stream.writeInt(Integer.MAX_VALUE);

        ByteBuffer buffer = stream.getBuffer();
        buffer.flip();
        assertEquals(Integer.MAX_VALUE, buffer.getInt());
        assertThrows(BufferUnderflowException.class, buffer::get);
    }

    @Test
    public void testWriteLong() {
        stream.writeLong(Long.MAX_VALUE);

        ByteBuffer buffer = stream.getBuffer();
        buffer.flip();
        assertEquals(Long.MAX_VALUE, buffer.getLong());
        assertThrows(BufferUnderflowException.class, buffer::get);
    }

    @Test
    public void testWriteString() {
        stream.writeString("testWriteString");

        ByteBuffer buffer = stream.getBuffer();
        buffer.flip();
        assertEquals("testWriteString".getBytes(StandardCharsets.UTF_8).length, buffer.getInt());
        byte[] dst = new byte["testWriteString".getBytes(StandardCharsets.UTF_8).length];
        buffer.get(dst);
        assertEquals("testWriteString", new String(dst, StandardCharsets.UTF_8));
        assertThrows(BufferUnderflowException.class, buffer::get);
    }
}
