package com.github.tth05.scnet;

import com.github.tth05.scnet.message.impl.DefaultMessageBus;
import com.github.tth05.scnet.message.impl.DefaultMessageProcessor;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class ServerLifecycleTest {
    private static final Executor ACCEPT_EXECUTOR = task -> {
        Thread thread = new Thread(task, "test-server-accept");
        thread.setDaemon(true);
        thread.start();
    };

    @Test
    void publishesEndpointBeforeDispatchAndReleasesAnEndpointClosedBeforeTheLoopRuns() throws Exception {
        AtomicReference<Server> owner = new AtomicReference<>();
        AtomicBoolean published = new AtomicBoolean();
        LinkedBlockingQueue<Runnable> queued = new LinkedBlockingQueue<>();
        try (Server server = new Server(ACCEPT_EXECUTOR, () -> task -> {
            published.set(owner.get().isClientConnected());
            queued.add(task);
        })) {
            owner.set(server);
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            for (int attempt = 0; attempt < 2; attempt++) {
                try (SocketChannel peer = SocketChannel.open(server.getLocalAddress())) {
                    Runnable loop = queued.poll(3, TimeUnit.SECONDS);
                    assertNotNull(loop);
                    await(server::isClientConnected);
                    boolean publishedAtDispatch = published.get();
                    server.closeClient();
                    loop.run();
                    assertFalse(server.isClientConnected());
                    assertTrue(publishedAtDispatch, "Endpoint was dispatched before server publication");
                }
            }
        }
    }

    @Test
    void shutdownDuringPreparationCannotPublishALateClient() throws Exception {
        BlockingReset processor = new BlockingReset();
        LinkedBlockingQueue<Runnable> queued = new LinkedBlockingQueue<>();
        try (Server server = new Server(ACCEPT_EXECUTOR, () -> queued::add)) {
            server.setMessageProcessor(processor);
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            try (SocketChannel peer = SocketChannel.open(server.getLocalAddress())) {
                assertTrue(processor.entered.await(3, TimeUnit.SECONDS));
                server.close();
                processor.release.countDown();
                peer.configureBlocking(false);
                await(() -> {
                    try { return peer.read(java.nio.ByteBuffer.allocate(1)) == -1; }
                    catch (java.io.IOException closed) { return true; }
                });
                assertFalse(server.isClientConnected());
                assertTrue(queued.isEmpty(), "Closed server scheduled a late endpoint");
            } finally {
                processor.release.countDown();
                Runnable loop = queued.poll();
                if (loop != null) loop.run();
            }
        }
    }

    @Test
    void configurationCannotChangeDuringEndpointPreparation() throws Exception {
        BlockingReset processor = new BlockingReset();
        LinkedBlockingQueue<Runnable> queued = new LinkedBlockingQueue<>();
        try (Server server = new Server(ACCEPT_EXECUTOR, () -> queued::add)) {
            server.setMessageProcessor(processor);
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            try (SocketChannel peer = SocketChannel.open(server.getLocalAddress())) {
                assertTrue(processor.entered.await(3, TimeUnit.SECONDS));
                try {
                    assertThrows(IllegalStateException.class, () -> server.setMessageBus(new DefaultMessageBus()));
                    assertThrows(IllegalStateException.class, () -> server.setMessageProcessor(new DefaultMessageProcessor()));
                } finally {
                    processor.release.countDown();
                    Runnable loop = queued.poll(3, TimeUnit.SECONDS);
                    if (loop != null) {
                        await(server::isClientConnected);
                        server.closeClient();
                        loop.run();
                    }
                }
            }
        }
    }

    @Test
    void closeAfterPublicationBeforeStartupReleasesTheEndpointWithoutDispatch() throws Exception {
        AtomicReference<Server> owner = new AtomicReference<>();
        AtomicInteger dispatched = new AtomicInteger();
        CountDownLatch closed = new CountDownLatch(1);
        try (Server server = new Server(ACCEPT_EXECUTOR, () -> {
            owner.get().close();
            closed.countDown();
            return task -> dispatched.incrementAndGet();
        })) {
            owner.set(server);
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            try (SocketChannel peer = SocketChannel.open(server.getLocalAddress())) {
                assertTrue(closed.await(3, TimeUnit.SECONDS));
                assertFalse(server.isClientConnected());
                assertEquals(-1, peer.read(java.nio.ByteBuffer.allocate(1)));
                assertEquals(0, dispatched.get());
            }
        }
    }

    @Test
    void executorRejectionReleasesTheSlotForTheNextClient() throws Exception {
        AtomicBoolean reject = new AtomicBoolean(true);
        CountDownLatch failed = new CountDownLatch(1);
        LinkedBlockingQueue<Runnable> queued = new LinkedBlockingQueue<>();
        try (Server server = new Server(ACCEPT_EXECUTOR, () -> task -> {
            if (reject.compareAndSet(true, false)) throw new java.util.concurrent.RejectedExecutionException("fixture");
            queued.add(task);
        })) {
            server.addConnectionListener(new IConnectionListener() {
                @Override public void onConnected() { }
                @Override public void onDisconnected() { }
                @Override public void onConnectionError(Throwable failure) { failed.countDown(); }
            });
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            try (SocketChannel first = SocketChannel.open(server.getLocalAddress())) {
                assertTrue(failed.await(3, TimeUnit.SECONDS));
                assertFalse(server.isClientConnected());
            }
            try (SocketChannel second = SocketChannel.open(server.getLocalAddress())) {
                Runnable loop = queued.poll(3, TimeUnit.SECONDS);
                assertNotNull(loop);
                server.closeClient();
                loop.run();
                assertFalse(server.isClientConnected());
            }
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "Server lifecycle condition timed out");
            Thread.sleep(1);
        }
    }

    private static final class BlockingReset extends DefaultMessageProcessor {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicBoolean first = new AtomicBoolean(true);

        @Override
        public void reset() {
            if (first.compareAndSet(true, false)) {
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            super.reset();
        }
    }
}
