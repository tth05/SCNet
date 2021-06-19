package com.github.tth05.scnet;

import com.github.tth05.scnet.message.IMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
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
            s.getClient().getMessageProcessor().registerMessage((short) 1, IntMessage.class);

            //Listen for message
            int c1 = 200000;
            CountDownLatch l = new CountDownLatch(c1);
            AtomicInteger messagePayload = new AtomicInteger(-1);
            long t = System.nanoTime();
            s.getClient().getMessageBus().listenAlways(IntMessage.class, (i) -> {
                l.countDown();
                messagePayload.set(i.i);
            });

            //Send message
            for (int i = 0; i < c1; i++) {
                c.getMessageProcessor().enqueueMessage(new IntMessage(number));
            }
            try {
                l.await();
            } catch (InterruptedException e) {
            }
            System.out.println((System.nanoTime() - t) / 1_000_000);
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
            s.getClient().getMessageProcessor().registerMessage((short) 1, IntMessage.class);
            c.getMessageProcessor().registerMessage((short) 2, RandomDataMessage.class);
            s.getClient().getMessageProcessor().registerMessage((short) 2, RandomDataMessage.class);

            //Listen for message
            AtomicInteger messagePayload = new AtomicInteger(-1);
            CountDownLatch l = new CountDownLatch(20000);
            s.getClient().getMessageBus().listenAlways(RandomDataMessage.class, (r) -> {
                l.countDown();
            });
            s.getClient().getMessageBus().listenAlways(IntMessage.class, (i) -> {
                messagePayload.set(i.i);
                l.countDown();
            });

            c.getMessageProcessor().enqueueMessage(new IntMessage(number));

            //Send message
            for (int i = 0; i < 20000 - 1; i++) {
//                    c.getMessageProcessor().enqueueMessage(new IntMessage(number));
                c.getMessageProcessor().enqueueMessage(new RandomDataMessage());
            }

            long t = System.nanoTime();
            assertDoesNotThrow((Executable) l::await);
            System.out.println((System.nanoTime() - t) / 1_000_000d);

            //Wait for message to arrive
            assertEquals(number, messagePayload.get());
        });
    }

    public static final class IntMessage implements IMessage {

        private int i;

        public IntMessage() {
        }

        public IntMessage(int i) {
            this.i = i;
        }

        @Override
        public void read(ByteBuffer messageByteBuffer) {
            for (int j = 0; j < 10; j++) {
                this.i = messageByteBuffer.getInt();
            }
            this.i = messageByteBuffer.getInt();
        }

        @Override
        public void write(ByteBuffer messageByteBuffer) {
            for (int j = 0; j < 10; j++) {
                messageByteBuffer.putInt(69);
            }
            messageByteBuffer.putInt(i);
        }
    }

    public static final class RandomDataMessage implements IMessage {

        @Override
        public void read(ByteBuffer messageByteBuffer) {
            int l = messageByteBuffer.getInt();
            byte[] b = new byte[l];
            messageByteBuffer.get(b);
        }

        @Override
        public void write(ByteBuffer messageByteBuffer) {
            int l = ThreadLocalRandom.current().nextInt(75);
            messageByteBuffer.putInt(l);
            messageByteBuffer.put(IntStream.range(0, l).mapToObj(i -> "1").collect(Collectors.joining()).getBytes(StandardCharsets.UTF_8));
        }
    }
}
