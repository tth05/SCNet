package com.github.tth05.scnet;

import com.github.tth05.scnet.message.impl.EmptyMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
public class ConnectionTest extends AbstractSCNetTest {

    @Test
    public void connectsClientToServerAndReportsState() throws Exception {
        try (Server server = new Server(); Client client = new Client()) {
            ConnectionProbe serverProbe = new ConnectionProbe(1, 1);
            ConnectionProbe clientProbe = new ConnectionProbe(1, 1);
            server.addConnectionListener(serverProbe);
            client.addConnectionListener(clientProbe);
            server.bind(new InetSocketAddress("127.0.0.1", 0));

            assertTrue(client.connect(server.getLocalAddress()));
            await(serverProbe.connected);
            await(clientProbe.connected);

            assertTrue(server.isClientConnected());
            assertTrue(client.isConnected());
            assertEquals(ConnectionState.CONNECTED, client.getConnectionState());
            assertEquals(1, serverProbe.connectedCalls.get());
            assertEquals(1, clientProbe.connectedCalls.get());
        }
    }

    @Test
    public void serverCloseDisconnectsBothEndpoints() throws Exception {
        withClientAndServer((server, client) -> {
            ConnectionProbe serverProbe = new ConnectionProbe(0, 1);
            ConnectionProbe clientProbe = new ConnectionProbe(0, 1);
            server.addConnectionListener(serverProbe);
            client.addConnectionListener(clientProbe);

            server.closeClient();

            await(serverProbe.disconnected);
            await(clientProbe.disconnected);
            assertFalse(server.isClientConnected());
            assertFalse(client.isConnected());
            assertEquals(ConnectionState.DISCONNECTED, client.getConnectionState());
            assertEquals(1, serverProbe.disconnectedCalls.get());
            assertEquals(1, clientProbe.disconnectedCalls.get());
        });
    }

    @Test
    public void clientCloseDisconnectsBothEndpointsAndIsIdempotent() throws Exception {
        withClientAndServer((server, client) -> {
            ConnectionProbe serverProbe = new ConnectionProbe(0, 1);
            ConnectionProbe clientProbe = new ConnectionProbe(0, 1);
            server.addConnectionListener(serverProbe);
            client.addConnectionListener(clientProbe);

            client.close();
            client.close();

            await(serverProbe.disconnected);
            await(clientProbe.disconnected);
            assertFalse(server.isClientConnected());
            assertFalse(client.isConnected());
            assertEquals(1, clientProbe.disconnectedCalls.get());
        });
    }

    @Test
    public void reconnectsTheSameClientAfterClose() throws Exception {
        try (Server server = new Server(); Client client = new Client()) {
            CountDownLatch firstServerConnected = new CountDownLatch(1);
            CountDownLatch firstServerDisconnected = new CountDownLatch(1);
            CountDownLatch firstClientConnected = new CountDownLatch(1);
            CountDownLatch firstClientDisconnected = new CountDownLatch(1);
            ConnectionProbe serverProbe = new ConnectionProbe(2, 2);
            ConnectionProbe clientProbe = new ConnectionProbe(2, 2);
            server.addConnectionListener(new IConnectionListener() {
                @Override
                public void onConnected() {
                    firstServerConnected.countDown();
                    serverProbe.onConnected();
                }

                @Override
                public void onDisconnected() {
                    firstServerDisconnected.countDown();
                    serverProbe.onDisconnected();
                }
            });
            client.addConnectionListener(new IConnectionListener() {
                @Override
                public void onConnected() {
                    firstClientConnected.countDown();
                    clientProbe.onConnected();
                }

                @Override
                public void onDisconnected() {
                    firstClientDisconnected.countDown();
                    clientProbe.onDisconnected();
                }
            });
            server.bind(new InetSocketAddress("127.0.0.1", 0));

            assertTrue(client.connect(server.getLocalAddress()));
            await(firstServerConnected);
            await(firstClientConnected);
            client.close();
            await(firstServerDisconnected);
            await(firstClientDisconnected);

            assertTrue(client.connect(server.getLocalAddress()));
            await(serverProbe.connected);
            await(clientProbe.connected);
            assertTrue(server.isClientConnected());
            assertTrue(client.isConnected());
            assertEquals(2, serverProbe.connectedCalls.get());
            assertEquals(2, clientProbe.connectedCalls.get());
        }
    }

    @Test
    public void rejectsReconnectFromConnectedCallbackWithoutDeadlocking() throws Exception {
        try (Server server = new Server(); Client client = new Client()) {
            CountDownLatch callbackFinished = new CountDownLatch(1);
            AtomicReference<Throwable> reconnectFailure = new AtomicReference<>();
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            client.addConnectionListener(new IConnectedListener() {
                @Override
                public void onConnected() {
                    try {
                        client.connect(server.getLocalAddress());
                    } catch (Throwable t) {
                        reconnectFailure.set(t);
                    } finally {
                        callbackFinished.countDown();
                    }
                }
            });

            assertTrue(client.connect(server.getLocalAddress()));
            await(callbackFinished);
            assertInstanceOf(IllegalStateException.class, reconnectFailure.get());
            assertTrue(client.isConnected());
        }
    }

    @Test
    public void rejectsReconnectFromMessageCallbackWithoutDeadlocking() throws Exception {
        withClientAndServer((server, client) -> {
            CountDownLatch callbackFinished = new CountDownLatch(1);
            AtomicReference<Throwable> reconnectFailure = new AtomicReference<>();
            client.getMessageBus().listenAlways(EmptyMessage.class, message -> {
                try {
                    client.connect(server.getLocalAddress());
                } catch (Throwable t) {
                    reconnectFailure.set(t);
                } finally {
                    callbackFinished.countDown();
                }
            });

            server.getMessageProcessor().enqueueMessage(new EmptyMessage());
            await(callbackFinished);
            assertInstanceOf(IllegalStateException.class, reconnectFailure.get());
            assertTrue(client.isConnected());
        });
    }

    @Test
    public void reconnectsFromDisconnectedCallbackAfterReleasingTheOldTransport() throws Exception {
        try (Server server = new Server(); Client client = new Client()) {
            CountDownLatch firstConnected = new CountDownLatch(1);
            CountDownLatch secondConnected = new CountDownLatch(1);
            CountDownLatch serverConnected = new CountDownLatch(1);
            CountDownLatch serverDisconnected = new CountDownLatch(1);
            CountDownLatch reconnectFinished = new CountDownLatch(1);
            AtomicInteger connectionCount = new AtomicInteger();
            AtomicBoolean reconnectOnce = new AtomicBoolean();
            AtomicReference<Boolean> reconnectResult = new AtomicReference<>();
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            server.addConnectionListener(new IConnectionListener() {
                @Override
                public void onConnected() {
                    serverConnected.countDown();
                }

                @Override
                public void onDisconnected() {
                    serverDisconnected.countDown();
                }
            });
            client.addConnectionListener(new IConnectionListener() {
                @Override
                public void onConnected() {
                    if (connectionCount.incrementAndGet() == 1) {
                        firstConnected.countDown();
                    } else {
                        secondConnected.countDown();
                    }
                }

                @Override
                public void onDisconnected() {
                    if (reconnectOnce.compareAndSet(false, true)) {
                        try {
                            if (serverDisconnected.await(3, TimeUnit.SECONDS)) {
                                reconnectResult.set(client.connect(server.getLocalAddress()));
                            } else {
                                reconnectResult.set(false);
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            reconnectResult.set(false);
                        }
                        reconnectFinished.countDown();
                    }
                }
            });

            assertTrue(client.connect(server.getLocalAddress()));
            await(firstConnected);
            await(serverConnected);
            server.closeClient();

            await(reconnectFinished);
            await(secondConnected);
            assertEquals(Boolean.TRUE, reconnectResult.get());
            assertTrue(client.isConnected());
        }
    }

    @Test
    public void concurrentReconnectAndDisconnectedCallbackCloseDoNotDeadlock() throws Exception {
        ExecutorService executor = Executors.newCachedThreadPool();
        List<SocketChannel> acceptedChannels = new CopyOnWriteArrayList<>();
        try (ServerSocketChannel rawServer = ServerSocketChannel.open(); Client client = new Client()) {
            rawServer.bind(new InetSocketAddress("127.0.0.1", 0));
            CountDownLatch twoAccepted = new CountDownLatch(2);
            Future<?> acceptFuture = executor.submit(() -> {
                try {
                    while (twoAccepted.getCount() != 0) {
                        acceptedChannels.add(rawServer.accept());
                        twoAccepted.countDown();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            CountDownLatch firstConnected = new CountDownLatch(1);
            CountDownLatch callbackCloseFinished = new CountDownLatch(1);
            AtomicBoolean closeOnce = new AtomicBoolean();
            client.addConnectionListener(new IConnectionListener() {
                @Override
                public void onConnected() {
                    firstConnected.countDown();
                }

                @Override
                public void onDisconnected() {
                    if (closeOnce.compareAndSet(false, true)) {
                        client.close();
                        callbackCloseFinished.countDown();
                    }
                }
            });

            assertTrue(client.connect(rawServer.getLocalAddress()));
            await(firstConnected);
            Future<Boolean> reconnect = executor.submit(() -> client.connect(rawServer.getLocalAddress()));

            assertTrue(reconnect.get(3, TimeUnit.SECONDS));
            await(twoAccepted);
            await(callbackCloseFinished);
            acceptFuture.get(3, TimeUnit.SECONDS);
            // Callback close can precede or follow replacement now that reconnect does not hold its monitor.
            // Once reconnect has returned, an explicit close must close whichever connection is current.
            client.close();
            assertFalse(client.isConnected());
        } finally {
            for (SocketChannel channel : acceptedChannels) {
                channel.close();
            }
            executor.shutdownNow();
        }
    }

    @Test
    public void rejectsASecondClientUntilTheFirstDisconnects() throws Exception {
        try (Server server = new Server(); Client first = new Client(); Client second = new Client()) {
            ConnectionProbe serverProbe = new ConnectionProbe(1, 1);
            ConnectionProbe secondProbe = new ConnectionProbe(1, 1);
            server.addConnectionListener(serverProbe);
            second.addConnectionListener(secondProbe);
            server.bind(new InetSocketAddress("127.0.0.1", 0));

            assertTrue(first.connect(server.getLocalAddress()));
            await(serverProbe.connected);
            assertTrue(second.connect(server.getLocalAddress()));
            await(secondProbe.connected);
            await(secondProbe.disconnected);

            assertTrue(first.isConnected());
            assertFalse(second.isConnected());
            assertEquals(1, secondProbe.disconnectedCalls.get());
        }
    }

    @Test
    public void interruptedRetryStopsAndRestoresInterruptFlag() {
        try (Client client = new Client()) {
            Thread.currentThread().interrupt();
            assertFalse(client.connect(new InetSocketAddress("127.0.0.1", 1), 1000, 2));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void closesEvenWhenAnExternalExecutorHasNotStartedTheEventLoop() throws Exception {
        AtomicReference<Runnable> queuedEventLoop = new AtomicReference<>();
        try (ServerSocketChannel rawServer = ServerSocketChannel.open();
             Client client = new Client(queuedEventLoop::set)) {
            rawServer.bind(new InetSocketAddress("127.0.0.1", 0));
            ConnectionProbe probe = new ConnectionProbe(0, 0);
            client.addConnectionListener(probe);

            assertTrue(client.connect(rawServer.getLocalAddress()));
            try (SocketChannel ignored = rawServer.accept()) {
                client.close();
                assertEquals(ConnectionState.DISCONNECTED, client.getConnectionState());
                assertEquals(0, probe.connectedCalls.get());
                assertEquals(0, probe.disconnectedCalls.get());

                queuedEventLoop.get().run();
                assertEquals(0, probe.connectedCalls.get());
                assertEquals(0, probe.disconnectedCalls.get());
            }
        }
    }

}
