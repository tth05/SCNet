package com.github.tth05.scnet;

import com.github.tth05.scnet.message.impl.EmptyMessage;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ReconnectLifecycleTest {
    enum Callback { CONNECTED, MESSAGE }

    @ParameterizedTest
    @EnumSource(Callback.class)
    void callbackCanCloseWhileAnotherThreadWaitsForReconnect(Callback callback) throws Exception {
        exerciseConcurrentReconnect(callback, false);
    }

    @ParameterizedTest
    @EnumSource(Callback.class)
    void callbackReconnectIsRejectedBeforeWaitingForAnotherReconnect(Callback callback) throws Exception {
        exerciseConcurrentReconnect(callback, true);
    }

    private void exerciseConcurrentReconnect(Callback callback, boolean reconnectFromCallback) throws Exception {
        Client client = new Client();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread reconnectThread = null;
        try (ServerSocketChannel server = ServerSocketChannel.open()) {
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            var address = server.getLocalAddress();
            Runnable action = () -> {
                if (!first.compareAndSet(true, false)) return;
                entered.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    if (reconnectFromCallback) {
                        assertThrows(IllegalStateException.class, () -> client.connect(address));
                    } else {
                        client.close();
                    }
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    finished.countDown();
                }
            };
            if (callback == Callback.CONNECTED) {
                client.addConnectionListener((IConnectedListener) action::run);
            } else {
                client.getMessageBus().listenAlways(EmptyMessage.class, message -> action.run());
            }
            assertTrue(client.connect(address));
            try (var accepted = server.accept()) {
                if (callback == Callback.MESSAGE) {
                    ByteBuffer frame = ByteBuffer.allocate(6).putShort((short) 0).putInt(0).flip();
                    while (frame.hasRemaining()) accepted.write(frame);
                }
                assertTrue(entered.await(3, TimeUnit.SECONDS), "Original callback did not start");
                FutureTask<Boolean> reconnect = new FutureTask<>(() -> client.connect(address));
                reconnectThread = new Thread(reconnect, "test-reconnect");
                reconnectThread.setDaemon(true);
                reconnectThread.start();
                awaitTransportWait(reconnectThread);
                release.countDown();
                assertTrue(finished.await(3, TimeUnit.SECONDS), "Callback blocked behind the reconnect wait");
                assertNull(failure.get(), () -> String.valueOf(failure.get()));
                assertTrue(reconnect.get(3, TimeUnit.SECONDS));
                try (var replacement = server.accept()) {
                    assertTrue(replacement.isConnected());
                    assertTrue(client.isConnected(), "Old callback must finish before the replacement is installed");
                }
            }
        } finally {
            release.countDown();
            if (reconnectThread != null) {
                // Also makes a failing regression release the original reconnect monitor.
                reconnectThread.interrupt();
                reconnectThread.join(3000);
            }
            client.close();
        }
    }

    private static void awaitTransportWait(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (Arrays.stream(thread.getStackTrace()).noneMatch(frame ->
                frame.getMethodName().equals("closeAndAwaitEventLoop"))) {
            assertTrue(System.nanoTime() < deadline, "Reconnect did not reach the old transport wait");
            Thread.sleep(1);
        }
    }
}
