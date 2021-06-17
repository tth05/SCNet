package com.github.tth05.scnet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
public class ConnectionTest extends SCNetTest {

    @Test
    public void testConnectClientToServer() {
        withClientAndServer((s, c) -> assertNotNull(s.getClient()));
    }

    @Test
    public void testIsConnected() {
        withClientAndServer((s, c) -> {
            assertTrue(s.getClient().isConnected());
            assertTrue(c.isConnected());
            s.getClient().close();
            assertFalse(s.getClient().isConnected());
            assertFalse(c.isConnected());
        });

        withClientAndServer((s, c) -> {
            assertTrue(s.getClient().isConnected());
            assertTrue(c.isConnected());
            c.close();
            assertFalse(s.getClient().isConnected());
            assertFalse(c.isConnected());
        });
    }

    @Test
    public void testReconnect() {
        withClientAndServer((s, c) -> {
            c.close();

            Client c2 = new Client();
            assertTrue(c2.connect(new InetSocketAddress(6969)));
            assertTrue(c2.isConnected());
            //Wait for server to accept new client
            assertDoesNotThrow(() -> Thread.sleep(50));
            assertNotNull(s.getClient());
            assertTrue(s.getClient().isConnected());
        });

        withClientAndServer((s, c) -> {
            Client c2 = new Client();
            assertTrue(c2.connect(new InetSocketAddress(6969)));
            assertFalse(c2.isConnected());
            s.getClient().close();

            assertTrue(c.connect(new InetSocketAddress(6969)));
            //Wait for server to accept new client
            assertDoesNotThrow(() -> Thread.sleep(50));
            assertNotNull(s.getClient());
        });
    }
}
