package com.github.tth05.scnet;

import com.github.tth05.scnet.message.impl.DefaultMessageProcessor;
import com.github.tth05.scnet.message.impl.EmptyMessage;
import com.github.tth05.scnet.message.AbstractMessageOutgoing;
import com.github.tth05.scnet.message.impl.DefaultMessageBus;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class OutboundQueueTest {
    @Test
    void stalledWriterCannotAcceptAnUnlimitedNumberOfMessages() {
        DefaultMessageProcessor processor = new DefaultMessageProcessor();
        for (int index = 0; index < 1024; index++) {
            processor.enqueueMessage(new EmptyMessage());
        }
        assertThrows(RejectedExecutionException.class,
                () -> processor.enqueueMessage(new EmptyMessage()));
    }

    @Test
    void concurrentProducersRespectOneSharedLimitAndResetRestoresAdmission() throws Exception {
        DefaultMessageProcessor processor = new DefaultMessageProcessor();
        processor.setMaxPendingMessages(32);
        AtomicInteger accepted = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<Future<?>>();
            for (int worker = 0; worker < 8; worker++) {
                tasks.add(executor.submit(() -> {
                    for (int attempt = 0; attempt < 1000; attempt++) {
                        try {
                            processor.enqueueMessage(new EmptyMessage());
                            accepted.incrementAndGet();
                        } catch (RejectedExecutionException expected) {
                            // Saturation fails this connection; later producers are rejected too.
                        }
                    }
                }));
            }
            for (Future<?> task : tasks) task.get();
        }
        assertEquals(32, accepted.get());
        processor.reset();
        assertEquals(32, processor.getMaxPendingMessages());
        assertDoesNotThrow(() -> processor.enqueueMessage(new EmptyMessage()));
    }

    @Test
    void completedFramesReleaseCapacity() throws Exception {
        try (Wire wire = new Wire()) {
            DefaultMessageProcessor processor = new DefaultMessageProcessor();
            processor.setMaxPendingMessages(1);
            for (int attempt = 0; attempt < 100; attempt++) {
                processor.enqueueMessage(new EmptyMessage());
                assertTrue(processor.process(wire.selector, wire.sender, new DefaultMessageBus()));
                ByteBuffer header = ByteBuffer.allocate(6);
                while (header.hasRemaining()) assertTrue(wire.receiver.read(header) > 0);
                assertArrayEquals(new byte[6], header.array());
            }
        }
    }

    @Test
    void slowReaderKeepsPartialFrameInBudgetAndOverflowFailsDrain() throws Exception {
        try (Wire wire = new Wire()) {
            DefaultMessageProcessor processor = new DefaultMessageProcessor();
            processor.setMaxPendingMessages(1);
            processor.registerMessage((short) 1, LargeMessage.class);
            processor.enqueueMessage(new LargeMessage());
            assertTrue(processor.process(wire.selector, wire.sender, new DefaultMessageBus()));
            RejectedExecutionException rejection = assertThrows(RejectedExecutionException.class,
                    () -> processor.enqueueMessage(new EmptyMessage()));
            var drain = processor.beginOutboundDrain().toCompletableFuture();
            assertFalse(processor.process(wire.selector, wire.sender, new DefaultMessageBus()));
            assertSame(rejection, processor.getLastError());
            assertTrue(drain.isCompletedExceptionally());
            assertTrue(rejection.getMessage().contains("receiver is not keeping up"));
        }
    }

    @Test
    void limitMustBePositiveAndCannotDiscardAcceptedMessages() {
        DefaultMessageProcessor processor = new DefaultMessageProcessor();
        assertThrows(IllegalArgumentException.class, () -> processor.setMaxPendingMessages(0));
        processor.enqueueMessage(new EmptyMessage());
        processor.enqueueMessage(new EmptyMessage());
        assertThrows(IllegalArgumentException.class, () -> processor.setMaxPendingMessages(1));
    }

    private static final class LargeMessage extends AbstractMessageOutgoing {
        @Override public void write(ByteBufferOutputStream output) {
            output.writeByteArray(new byte[8 * 1024 * 1024]);
        }
    }

    private static final class Wire implements AutoCloseable {
        final Selector selector = Selector.open();
        final SocketChannel sender;
        final SocketChannel receiver;

        Wire() throws Exception {
            try (ServerSocketChannel listener = ServerSocketChannel.open()) {
                listener.bind(new InetSocketAddress("127.0.0.1", 0));
                sender = SocketChannel.open(listener.getLocalAddress());
                receiver = listener.accept();
            }
            sender.setOption(StandardSocketOptions.SO_SNDBUF, 1024);
            receiver.setOption(StandardSocketOptions.SO_RCVBUF, 1024);
            sender.configureBlocking(false);
        }

        @Override public void close() throws Exception {
            sender.close();
            receiver.close();
            selector.close();
        }
    }
}
