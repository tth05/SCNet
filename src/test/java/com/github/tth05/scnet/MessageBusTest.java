package com.github.tth05.scnet;

import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.message.IMessageBus;
import com.github.tth05.scnet.message.impl.DefaultMessageBus;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class MessageBusTest {

    private IMessageBus bus;

    @BeforeEach
    public void setup() {
        bus = new DefaultMessageBus();
    }

    @Test
    public void testListenAlways() {
        AtomicInteger count1 = new AtomicInteger();
        AtomicInteger count2 = new AtomicInteger();

        bus.listenAlways(DummyMessage.class, (m) -> count1.incrementAndGet());
        bus.listenAlways(DummyMessage.class, this, (m) -> count2.incrementAndGet());

        for (int i = 0; i < 20; i++) {
            bus.post(new DummyMessage());
        }

        assertEquals(20, count1.get());
        assertEquals(20, count2.get());
    }

    @Test
    public void testListenOnce() {
        AtomicInteger count1 = new AtomicInteger();
        AtomicInteger count2 = new AtomicInteger();

        bus.listenOnce(DummyMessage.class, (m) -> count1.incrementAndGet());
        bus.listenOnce(DummyMessage.class, this, (m) -> count2.incrementAndGet());

        for (int i = 0; i < 20; i++) {
            bus.post(new DummyMessage());
        }

        assertEquals(1, count1.get());
        assertEquals(1, count2.get());
    }

    @Test
    public void testUnregisterListenAlways() {
        AtomicInteger count1 = new AtomicInteger();
        AtomicInteger count2 = new AtomicInteger();

        Consumer<DummyMessage> listener1 = (m) -> count1.incrementAndGet();
        bus.listenAlways(DummyMessage.class, listener1);
        bus.listenAlways(DummyMessage.class, this, (m) -> count2.incrementAndGet());

        for (int i = 0; i < 20; i++) {
            bus.post(new DummyMessage());
        }

        assertEquals(20, count1.get());
        assertEquals(20, count2.get());

        bus.unregister(DummyMessage.class, this);

        for (int i = 0; i < 20; i++) {
            bus.post(new DummyMessage());
        }

        assertEquals(40, count1.get());
        assertEquals(20, count2.get());

        bus.unregister(DummyMessage.class, listener1);

        for (int i = 0; i < 20; i++) {
            bus.post(new DummyMessage());
        }

        assertEquals(40, count1.get());
        assertEquals(20, count2.get());
    }

    @Test
    public void testUnregisterListenOnce() {
        AtomicInteger count1 = new AtomicInteger();
        AtomicInteger count2 = new AtomicInteger();

        Consumer<DummyMessage> listener1 = (m) -> count1.incrementAndGet();
        bus.listenOnce(DummyMessage.class, listener1);
        bus.listenOnce(DummyMessage.class, this, (m) -> count2.incrementAndGet());
        bus.unregister(DummyMessage.class, listener1);
        bus.unregister(DummyMessage.class, this);

        for (int i = 0; i < 20; i++) {
            bus.post(new DummyMessage());
        }

        assertEquals(0, count1.get());
        assertEquals(0, count2.get());
    }

    public static class DummyMessage extends AbstractMessage {

        @Override
        public void read(@NotNull ByteBufferInputStream messageStream) {

        }

        @Override
        public void write(@NotNull ByteBufferOutputStream messageStream) {

        }
    }
}
