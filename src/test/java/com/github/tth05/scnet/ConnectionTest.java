package com.github.tth05.scnet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
public class ConnectionTest extends SCNetTest {

    @Test
    public void testConnectClientToServer() {
        withClientAndServer((s, c) -> assertNotNull(getClientFromServer(s)));
    }

    @Test
    public void testIsConnected() {
        //Disconnect from server side
        withClientAndServer((s, c) -> {
            assertTrue(s.isClientConnected());
            assertTrue(c.isConnected());
            s.closeClient();
            assertDoesNotThrow(() -> Thread.sleep(50));
            assertNull(getClientFromServer(s));
            assertFalse(c.isConnected());
        });

        //Disconnect from client side
        withClientAndServer((s, c) -> {
            assertTrue(s.isClientConnected());
            assertTrue(c.isConnected());
            c.close();
            assertDoesNotThrow(() -> Thread.sleep(50));
            assertNull(getClientFromServer(s));
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
            assertNotNull(getClientFromServer(s));
            assertTrue(s.isClientConnected());
        });

        withClientAndServer((s, c) -> {
            Client c2 = new Client();
            assertTrue(c2.connect(new InetSocketAddress(6969)));
            assertDoesNotThrow(() -> Thread.sleep(50));
            //Second client can't connect
            assertFalse(c2.isConnected());
            //Disconnect first client
            s.closeClient();

            assertTrue(c.connect(new InetSocketAddress(6969)));
            //Wait for server to accept new client
            assertDoesNotThrow(() -> Thread.sleep(50));
            assertNotNull(getClientFromServer(s));
        });
    }
}
