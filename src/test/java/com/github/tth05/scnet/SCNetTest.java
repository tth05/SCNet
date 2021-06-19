package com.github.tth05.scnet;

import java.net.InetSocketAddress;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SCNetTest {

    public void withClientAndServer(BiConsumer<Server, Client> consumer) {
        try (Server s = new Server(); Client c = new Client()) {
            s.bind(new InetSocketAddress(6969));
            assertTrue(c.connect(new InetSocketAddress(6969)));

            //Wait for the server to accept the client
            while (s.getClient() == null)
                assertDoesNotThrow(() -> Thread.sleep(50));
            consumer.accept(s, c);
        }
    }
}
