package com.github.tth05.scnet;

import com.github.tth05.scnet.message.AbstractMessageOutgoing;
import com.github.tth05.scnet.message.MalformedFrameException;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Timeout(30)
public class DrainCloseTest extends AbstractSCNetTest {

    @Test
    public void drainsLargeFrameBeforeEofAndClosesExactlyOnce() throws Exception {
        byte[] payload = patternedBytes(8 * 1024 * 1024);
        try (Server server = new Server(); SocketChannel rawClient = SocketChannel.open()) {
            server.getMessageProcessor().registerMessage((short) 31, BytesMessage.class);
            ConnectionProbe probe = new ConnectionProbe(1, 1);
            server.addConnectionListener(probe);
            server.bind(new InetSocketAddress("127.0.0.1", 0));

            rawClient.setOption(StandardSocketOptions.SO_RCVBUF, 1024);
            rawClient.connect(server.getLocalAddress());
            await(probe.connected);

            server.getMessageProcessor().enqueueMessage(new BytesMessage(payload));
            CompletionStage<Void> firstClose = server.closeClientAfterPendingWrites();
            CompletionStage<Void> secondClose = server.closeClientAfterPendingWrites();
            assertSame(firstClose, secondClose);
            assertFalse(firstClose.toCompletableFuture().isDone(), "Drain finished before the blocked peer read any data");
            assertThrows(
                    RejectedExecutionException.class,
                    () -> server.getMessageProcessor().enqueueMessage(new BytesMessage(new byte[]{1}))
            );

            ByteBuffer header = ByteBuffer.allocate(6);
            readFully(rawClient, header);
            header.flip();
            assertEquals(31, header.getShort());
            assertEquals(payload.length, header.getInt());
            assertPayload(rawClient, payload);
            assertEquals(-1, rawClient.read(ByteBuffer.allocate(1)));

            firstClose.toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(1, probe.disconnectedCalls.get());
            assertEquals(1, probe.error.getCount());

            server.closeClient();
            assertEquals(1, probe.disconnectedCalls.get());
        }
    }

    @Test
    public void closesImmediatelyWhenNoMessagesArePending() throws Exception {
        try (Server server = new Server(); SocketChannel rawClient = SocketChannel.open()) {
            ConnectionProbe probe = new ConnectionProbe(1, 1);
            server.addConnectionListener(probe);
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            rawClient.connect(server.getLocalAddress());
            await(probe.connected);

            server.closeClientAfterPendingWrites().toCompletableFuture().get(3, TimeUnit.SECONDS);

            assertEquals(-1, rawClient.read(ByteBuffer.allocate(1)));
            assertEquals(1, probe.disconnectedCalls.get());
        }
    }

    @Test
    public void acceptsMessagesAgainAfterDrainCloseAndReconnect() throws Exception {
        try (Server server = new Server(); SocketChannel firstClient = SocketChannel.open()) {
            server.getMessageProcessor().registerMessage((short) 34, BytesMessage.class);
            ConnectionProbe firstProbe = new ConnectionProbe(1, 1);
            server.addConnectionListener(firstProbe);
            server.bind(new InetSocketAddress("127.0.0.1", 0));

            firstClient.connect(server.getLocalAddress());
            await(firstProbe.connected);
            server.closeClientAfterPendingWrites().toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(-1, firstClient.read(ByteBuffer.allocate(1)));
            assertEquals(1, firstProbe.disconnectedCalls.get());
            server.removeConnectionListener(firstProbe);

            try (SocketChannel secondClient = SocketChannel.open()) {
                ConnectionProbe secondProbe = new ConnectionProbe(1, 1);
                server.addConnectionListener(secondProbe);
                secondClient.connect(server.getLocalAddress());
                await(secondProbe.connected);

                byte[] payload = patternedBytes(1024);
                server.getMessageProcessor().enqueueMessage(new BytesMessage(payload));
                server.closeClientAfterPendingWrites().toCompletableFuture().get(3, TimeUnit.SECONDS);

                ByteBuffer header = ByteBuffer.allocate(6);
                readFully(secondClient, header);
                header.flip();
                assertEquals(34, header.getShort());
                assertEquals(payload.length, header.getInt());
                assertPayload(secondClient, payload);
                assertEquals(-1, secondClient.read(ByteBuffer.allocate(1)));
                assertEquals(1, secondProbe.disconnectedCalls.get());
            }
        }
    }

    @Test
    public void completesExceptionallyWhenSerializationFails() throws Exception {
        try (Server server = new Server(); SocketChannel rawClient = SocketChannel.open()) {
            server.getMessageProcessor().registerMessage((short) 32, FailingMessage.class);
            ConnectionProbe probe = new ConnectionProbe(1, 1);
            server.addConnectionListener(probe);
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            rawClient.connect(server.getLocalAddress());
            await(probe.connected);

            server.getMessageProcessor().enqueueMessage(new FailingMessage());
            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> server.closeClientAfterPendingWrites().toCompletableFuture().get(3, TimeUnit.SECONDS)
            );

            assertInstanceOf(MalformedFrameException.class, failure.getCause());
            assertEquals(-1, rawClient.read(ByteBuffer.allocate(1)));
            assertEquals(1, probe.disconnectedCalls.get());
            assertInstanceOf(MalformedFrameException.class, probe.lastError.get());
        }
    }

    @Test
    public void completesExceptionallyWhenPeerClosesDuringDrain() throws Exception {
        byte[] payload = patternedBytes(8 * 1024 * 1024);
        try (Server server = new Server(); SocketChannel rawClient = SocketChannel.open()) {
            server.getMessageProcessor().registerMessage((short) 33, BytesMessage.class);
            ConnectionProbe probe = new ConnectionProbe(1, 1);
            server.addConnectionListener(probe);
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            rawClient.setOption(StandardSocketOptions.SO_RCVBUF, 1024);
            rawClient.connect(server.getLocalAddress());
            await(probe.connected);

            server.getMessageProcessor().enqueueMessage(new BytesMessage(payload));
            CompletionStage<Void> close = server.closeClientAfterPendingWrites();
            rawClient.close();
            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> close.toCompletableFuture().get(3, TimeUnit.SECONDS)
            );

            assertInstanceOf(IOException.class, failure.getCause());
            assertEquals(1, probe.disconnectedCalls.get());
            assertInstanceOf(IOException.class, probe.lastError.get());
        }
    }

    @Test
    public void failsWhenNoClientIsConnected() {
        try (Server server = new Server()) {
            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> server.closeClientAfterPendingWrites().toCompletableFuture().get()
            );
            assertInstanceOf(IllegalStateException.class, failure.getCause());
        }
    }

    private static byte[] patternedBytes(int size) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i * 31);
        }
        return bytes;
    }

    private static void assertPayload(SocketChannel channel, byte[] expected) throws IOException {
        ByteBuffer chunk = ByteBuffer.allocate(64 * 1024);
        int offset = 0;
        while (offset < expected.length) {
            chunk.clear();
            chunk.limit(Math.min(chunk.capacity(), expected.length - offset));
            readFully(channel, chunk);
            chunk.flip();
            while (chunk.hasRemaining()) {
                byte actual = chunk.get();
                if (actual != expected[offset]) {
                    throw new AssertionError("Payload differed at offset " + offset);
                }
                offset++;
            }
        }
    }

    private static void readFully(SocketChannel channel, ByteBuffer target) throws IOException {
        while (target.hasRemaining()) {
            if (channel.read(target) < 0) {
                throw new IOException("Connection closed before all expected bytes arrived");
            }
        }
    }

    public static final class BytesMessage extends AbstractMessageOutgoing {

        private final byte[] payload;

        public BytesMessage(byte[] payload) {
            this.payload = payload;
        }

        @Override
        public void write(@NotNull ByteBufferOutputStream messageStream) {
            messageStream.writeByteArray(this.payload);
        }
    }

    public static final class FailingMessage extends AbstractMessageOutgoing {

        @Override
        public void write(@NotNull ByteBufferOutputStream messageStream) {
            throw new IllegalStateException("serialization failed");
        }
    }
}
