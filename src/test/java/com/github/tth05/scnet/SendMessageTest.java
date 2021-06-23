package com.github.tth05.scnet;

import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
