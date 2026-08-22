package com.github.tth05.scnet;

import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.message.MalformedFrameException;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
public class TransportProtocolTest extends AbstractSCNetTest {

    @Test
    public void decodesFramesFragmentedAtEveryByte() throws Exception {
        try (Server server = new Server()) {
            server.getMessageProcessor().setReadBufferSize(1);
            server.getMessageProcessor().registerMessage((short) 11, ValueMessage.class);
            CountDownLatch messageReceived = new CountDownLatch(1);
            AtomicInteger receivedValue = new AtomicInteger();
            server.getMessageBus().listenAlways(ValueMessage.class, message -> {
                receivedValue.set(message.value);
                messageReceived.countDown();
            });

            ConnectionProbe probe = new ConnectionProbe(1, 1);
            server.addConnectionListener(probe);
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            try (SocketChannel rawClient = SocketChannel.open(server.getLocalAddress())) {
                await(probe.connected);
                ByteBuffer frame = ByteBuffer.allocate(10);
                frame.putShort((short) 11);
                frame.putInt(4);
                frame.putInt(0x12345678);
                frame.flip();
                writeFully(rawClient, frame);

                await(messageReceived);
                assertEquals(0x12345678, receivedValue.get());
                assertTrue(server.isClientConnected());
            }
        }
    }

    @Test
    public void rejectsNegativeFrameLengthAndReportsCause() throws Exception {
        assertMalformedFrame(ByteBuffer.allocate(6).putShort((short) 1).putInt(-1), 64, 64);
    }

    @Test
    public void rejectsOversizedFrameBeforeAllocatingPayload() throws Exception {
        assertMalformedFrame(ByteBuffer.allocate(6).putShort((short) 1).putInt(65), 64, 64);
    }

    @Test
    public void rejectsOversizedStringInsideBoundedFrame() throws Exception {
        try (Server server = new Server()) {
            server.getMessageProcessor().setMaxFrameSize(64);
            server.getMessageProcessor().setMaxStringLength(4);
            server.getMessageProcessor().registerMessage((short) 3, StringMessage.class);
            ConnectionProbe probe = new ConnectionProbe(1, 1);
            server.addConnectionListener(probe);
            server.bind(new InetSocketAddress("127.0.0.1", 0));

            try (SocketChannel rawClient = SocketChannel.open(server.getLocalAddress())) {
                await(probe.connected);
                ByteBuffer frame = ByteBuffer.allocate(15);
                frame.putShort((short) 3);
                frame.putInt(9);
                frame.putInt(5);
                frame.put("abcde".getBytes(StandardCharsets.UTF_8));
                frame.flip();
                writeFully(rawClient, frame);

                await(probe.error);
                await(probe.disconnected);
                assertTrue(probe.lastError.get() instanceof MalformedFrameException);
                assertFalse(server.isClientConnected());
            }
        }
    }

    @Test
    public void preservesOriginalHeaderAndPayloadEncoding() throws Exception {
        try (ServerSocketChannel rawServer = ServerSocketChannel.open(); Client client = new Client()) {
            rawServer.bind(new InetSocketAddress("127.0.0.1", 0));
            client.getMessageProcessor().registerMessage((short) 7, StringMessage.class);
            assertTrue(client.connect(rawServer.getLocalAddress()));

            try (SocketChannel accepted = rawServer.accept()) {
                assertTrue(client.isConnected());
                assertTrue(client.isConnected());
                client.getMessageProcessor().enqueueMessage(new StringMessage("wire"));

                ByteBuffer frame = ByteBuffer.allocate(14);
                readFully(accepted, frame);
                frame.flip();
                assertEquals(7, frame.getShort());
                assertEquals(8, frame.getInt());
                assertEquals(4, frame.getInt());
                byte[] text = new byte[4];
                frame.get(text);
                assertArrayEquals("wire".getBytes(StandardCharsets.UTF_8), text);
            }
        }
    }

    @Test
    public void reportsOutboundSerializationLimitFailures() throws Exception {
        try (ServerSocketChannel rawServer = ServerSocketChannel.open(); Client client = new Client()) {
            rawServer.bind(new InetSocketAddress("127.0.0.1", 0));
            client.getMessageProcessor().setMaxFrameSize(4);
            client.getMessageProcessor().registerMessage((short) 1, OversizedMessage.class);
            ConnectionProbe probe = new ConnectionProbe(1, 1);
            client.addConnectionListener(probe);
            assertTrue(client.connect(rawServer.getLocalAddress()));

            try (SocketChannel ignored = rawServer.accept()) {
                await(probe.connected);
                client.getMessageProcessor().enqueueMessage(new OversizedMessage());

                await(probe.error);
                await(probe.disconnected);
                assertTrue(probe.lastError.get() instanceof IllegalArgumentException
                        || probe.lastError.get() instanceof MalformedFrameException);
            }
        }
    }

    private void assertMalformedFrame(ByteBuffer frame, int maxFrameSize, int maxStringLength) throws Exception {
        frame.flip();
        try (Server server = new Server()) {
            server.getMessageProcessor().setMaxFrameSize(maxFrameSize);
            server.getMessageProcessor().setMaxStringLength(maxStringLength);
            ConnectionProbe probe = new ConnectionProbe(1, 1);
            server.addConnectionListener(probe);
            server.bind(new InetSocketAddress("127.0.0.1", 0));

            try (SocketChannel rawClient = SocketChannel.open(server.getLocalAddress())) {
                await(probe.connected);
                writeFully(rawClient, frame);
                await(probe.error);
                await(probe.disconnected);
                assertTrue(probe.lastError.get() instanceof MalformedFrameException);
                assertFalse(server.isClientConnected());
            }
        }
    }

    private static void writeFully(SocketChannel channel, ByteBuffer buffer) throws Exception {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    private static void readFully(SocketChannel channel, ByteBuffer buffer) throws Exception {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                throw new AssertionError("Connection closed before the full frame was read");
            }
        }
    }

    public static class ValueMessage extends AbstractMessage {

        private int value;

        public ValueMessage() {
        }

        public ValueMessage(int value) {
            this.value = value;
        }

        @Override
        public void read(@NotNull ByteBufferInputStream messageStream) {
            this.value = messageStream.readInt();
        }

        @Override
        public void write(@NotNull ByteBufferOutputStream messageStream) {
            messageStream.writeInt(this.value);
        }
    }

    public static final class StringMessage extends AbstractMessage {

        private String value;

        public StringMessage() {
        }

        public StringMessage(String value) {
            this.value = value;
        }

        @Override
        public void read(@NotNull ByteBufferInputStream messageStream) {
            this.value = messageStream.readString();
        }

        @Override
        public void write(@NotNull ByteBufferOutputStream messageStream) {
            messageStream.writeString(this.value);
        }
    }

    public static final class OversizedMessage extends AbstractMessage {

        @Override
        public void read(@NotNull ByteBufferInputStream messageStream) {
            messageStream.readLong();
        }

        @Override
        public void write(@NotNull ByteBufferOutputStream messageStream) {
            messageStream.writeLong(1L);
        }
    }
}
