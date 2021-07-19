package com.github.tth05.scnet;

import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;

import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
public class SendMessageTest extends SCNetTest {

    @Test
    public void testSendBasicMessage() {
        int number = ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE);
        withClientAndServer((s, c) -> {
            //Register message
            c.getMessageProcessor().registerMessage((short) 1, IntMessage.class);
            s.getMessageProcessor().registerMessage((short) 1, IntMessage.class);

            //Listen for message
            AtomicInteger messagePayload = new AtomicInteger(-1);
            s.getMessageBus().listenAlways(IntMessage.class, (i) -> {
                messagePayload.set(i.i);
            });

            //Send message
            c.getMessageProcessor().enqueueMessage(new IntMessage(number));
            //Wait for message to arrive
            assertDoesNotThrow(() -> Thread.sleep(50));
            assertEquals(number, messagePayload.get());
        });
    }

    @Test
    public void testSendMixedBatchMessages() {
        int number = ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE);
        withClientAndServer((s, c) -> {
            //Register message
            c.getMessageProcessor().registerMessage((short) 1, IntMessage.class);
            s.getMessageProcessor().registerMessage((short) 1, IntMessage.class);
            c.getMessageProcessor().registerMessage((short) 2, RandomDataMessage.class);
            s.getMessageProcessor().registerMessage((short) 2, RandomDataMessage.class);

            //Listen for message
            AtomicInteger messagePayload = new AtomicInteger(-1);
            CountDownLatch l = new CountDownLatch(100);
            s.getMessageBus().listenAlways(RandomDataMessage.class, (r) -> {
                l.countDown();
            });
            s.getMessageBus().listenAlways(IntMessage.class, (i) -> {
                messagePayload.set(i.i);
                l.countDown();
            });

            c.getMessageProcessor().enqueueMessage(new IntMessage(number));

            //Send message
            for (int i = 0; i < 100 - 1; i++) {
                if (Math.random() < 0.4) {
                    c.getMessageProcessor().enqueueMessage(new IntMessage(number));
                } else {
                    c.getMessageProcessor().enqueueMessage(new RandomDataMessage());
                }
            }

            assertDoesNotThrow((Executable) l::await);
            assertEquals(number, messagePayload.get());
        });
    }

    @Test
    public void testSendLargeMessage() {
        withClientAndServer((s, c) -> {
            s.getMessageProcessor().registerMessage((short) 1, LargeMessage.class);
            c.getMessageProcessor().registerMessage((short) 1, LargeMessage.class);

            int count = 250;
            CountDownLatch latch = new CountDownLatch(count);
            c.getMessageBus().listenAlways(LargeMessage.class, m -> {
                latch.countDown();
            });

            Random random = new Random();
            for (int i = 0; i < count; i++) {
                s.getMessageProcessor().enqueueMessage(new LargeMessage(random.nextInt(100000) + 50000));
            }

            assertDoesNotThrow((Executable) latch::await);
            assertTrue(s.isClientConnected());
            assertTrue(c.isConnected());
        });
    }

    public static final class LargeMessage extends AbstractMessage {

        private int size;

        public LargeMessage() {
        }

        public LargeMessage(int size) {
            this.size = size;
        }

        @Override
        public void read(@NotNull ByteBufferInputStream messageStream) {
            this.size = messageStream.readInt();

            for (int i = 0; i < this.size; i++) {
                int finalI = i;
                assertEquals(Integer.MAX_VALUE, messageStream.readInt(), () -> "i: " + finalI + ", size: " + this.size);
            }
        }

        @Override
        public void write(@NotNull ByteBufferOutputStream messageStream) {
            messageStream.writeInt(this.size);
            for (int i = 0; i < this.size; i++) {
                messageStream.writeInt(Integer.MAX_VALUE);
            }
        }
    }

    public static final class IntMessage extends AbstractMessage {

        private int i;

        public IntMessage() {
        }

        public IntMessage(int i) {
            this.i = i;
        }

        @Override
        public void read(@NotNull ByteBufferInputStream messageStream) {
            for (int j = 0; j < 10; j++) {
                this.i = messageStream.readInt();
            }
            this.i = messageStream.readInt();
        }

        @Override
        public void write(@NotNull ByteBufferOutputStream messageStream) {
            for (int j = 0; j < 10; j++) {
                messageStream.writeInt(69);
            }
            messageStream.writeInt(i);
        }
    }

    public static final class RandomDataMessage extends AbstractMessage {

        @Override
        public void read(@NotNull ByteBufferInputStream messageStream) {
            int l = messageStream.readInt();
            String s = messageStream.readString();
            assertEquals(l, s.length());
        }

        @Override
        public void write(@NotNull ByteBufferOutputStream messageStream) {
            int l = ThreadLocalRandom.current().nextInt(5);
            messageStream.writeInt(l);
            String str = IntStream.range(0, l).mapToObj(i -> "1").collect(Collectors.joining());
            messageStream.writeString(str);
        }
    }
}
