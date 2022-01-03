package com.github.tth05.scnet;

import org.junit.platform.commons.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

public abstract class AbstractSCNetTest {

    public void withClientAndServer(BiConsumer<Server, Client> consumer) {
        try (Server s = new Server(); Client c = new Client()) {
            s.bind(new InetSocketAddress(6969));
            assertTrue(c.connect(new InetSocketAddress(6969)));

            //Wait for the server to accept the client
            while (getClientFromServer(s) == null)
                assertDoesNotThrow(() -> Thread.sleep(50));
            consumer.accept(s, c);
        }
    }

    public ServerClient getClientFromServer(Server s) {
        return assertDoesNotThrow(() -> {
            Field field = ReflectionUtils.findFields(Server.class, f -> f.getName().equals("client"), ReflectionUtils.HierarchyTraversalMode.TOP_DOWN).get(0);
            field.setAccessible(true);
            return (ServerClient) field.get(s);
        });
    }
}
