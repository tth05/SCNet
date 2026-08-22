package com.github.tth05.scnet;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

public abstract class AbstractSCNetTest {

    protected void withClientAndServer(ThrowingBiConsumer<Server, Client> consumer) throws Exception {
        try (Server server = new Server(); Client client = new Client()) {
            ConnectionProbe serverProbe = new ConnectionProbe(1, 1);
            server.addConnectionListener(serverProbe);
            server.bind(new InetSocketAddress("127.0.0.1", 0));

            assertTrue(client.connect(server.getLocalAddress()));
            await(serverProbe.connected);
            consumer.accept(server, client);
        }
    }

    protected static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(3, TimeUnit.SECONDS), "Timed out waiting for connection event");
    }

    @FunctionalInterface
    protected interface ThrowingBiConsumer<T, U> {
        void accept(T first, U second) throws Exception;
    }

    protected static final class ConnectionProbe implements IConnectionListener {

        final CountDownLatch connected;
        final CountDownLatch disconnected;
        final CountDownLatch error = new CountDownLatch(1);
        final AtomicInteger connectedCalls = new AtomicInteger();
        final AtomicInteger disconnectedCalls = new AtomicInteger();
        final AtomicReference<Throwable> lastError = new AtomicReference<>();

        ConnectionProbe(int expectedConnections, int expectedDisconnections) {
            this.connected = new CountDownLatch(expectedConnections);
            this.disconnected = new CountDownLatch(expectedDisconnections);
        }

        @Override
        public void onConnected() {
            this.connectedCalls.incrementAndGet();
            this.connected.countDown();
        }

        @Override
        public void onDisconnected() {
            this.disconnectedCalls.incrementAndGet();
            this.disconnected.countDown();
        }

        @Override
        public void onConnectionError(Throwable cause) {
            this.lastError.set(cause);
            this.error.countDown();
        }
    }
}
