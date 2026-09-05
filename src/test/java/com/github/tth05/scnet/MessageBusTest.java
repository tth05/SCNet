package com.github.tth05.scnet;

import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.message.IMessageBus;
import com.github.tth05.scnet.message.impl.DefaultMessageBus;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void listenerRegistrationTakesEffectOnTheNextDispatch() {
        List<String> calls = new ArrayList<>();
        AtomicBoolean added = new AtomicBoolean();
        bus.listenAlways(DummyMessage.class, message -> {
            calls.add("first");
            if (added.compareAndSet(false, true)) {
                bus.listenAlways(DummyMessage.class, next -> calls.add("added"));
            }
        });
        bus.post(new DummyMessage());
        assertEquals(List.of("first"), calls);
        bus.post(new DummyMessage());
        assertEquals(List.of("first", "first", "added"), calls);
    }

    @Test
    void unregisterDoesNotChangeCallbacksAlreadyClaimedForThisDispatch() {
        List<String> calls = new ArrayList<>();
        Object owner = new Object();
        bus.listenAlways(DummyMessage.class, message -> {
            calls.add("first");
            bus.unregister(DummyMessage.class, owner);
        });
        bus.listenAlways(DummyMessage.class, owner, message -> calls.add("second"));
        bus.post(new DummyMessage());
        bus.post(new DummyMessage());
        assertEquals(List.of("first", "second", "first"), calls);
    }

    @Test
    void oneShotListenerIsClaimedBeforeNestedPosting() {
        AtomicInteger calls = new AtomicInteger();
        bus.listenOnce(DummyMessage.class, message -> {
            if (calls.incrementAndGet() == 1) bus.post(new DummyMessage());
        });
        bus.post(new DummyMessage());
        assertEquals(1, calls.get());
    }

    @Test
    void callbackDoesNotHoldTheRegistryLockWhileAnotherThreadRegisters() throws Exception {
        CountDownLatch registered = new CountDownLatch(1);
        AtomicBoolean completedInsideCallback = new AtomicBoolean();
        Thread registration = new Thread(() -> {
            bus.listenAlways(DummyMessage.class, message -> { });
            registered.countDown();
        });
        registration.setDaemon(true);
        bus.listenOnce(DummyMessage.class, message -> {
            registration.start();
            try {
                completedInsideCallback.set(registered.await(2, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        bus.post(new DummyMessage());
        registration.join(3000);
        assertTrue(completedInsideCallback.get(), "Callback held the registry lock");
    }

    @Test
    void failureDoesNotRearmOneShotOrPreventLaterCallbacks() {
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger delivered = new AtomicInteger();
        bus.listenOnce(DummyMessage.class, message -> {
            failures.incrementAndGet();
            throw new IllegalStateException("test callback failure");
        });
        bus.listenAlways(DummyMessage.class, message -> delivered.incrementAndGet());
        bus.post(new DummyMessage());
        bus.post(new DummyMessage());
        assertEquals(1, failures.get());
        assertEquals(2, delivered.get());
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
