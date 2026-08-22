package com.github.tth05.scnet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
