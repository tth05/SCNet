package com.github.tth05.scnet;

import com.github.tth05.scnet.message.IMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.ByteBuffer;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

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
            AtomicInteger messagePayload = new AtomicInteger(-1);
            s.getClient().getMessageBus().listenAlways(IntMessage.class, (i) -> {
                messagePayload.set(i.i);
            });

            //Send message
            c.getMessageProcessor().enqueueMessage(new IntMessage(number));
            //Wait for message to arrive
            assertDoesNotThrow(() -> Thread.sleep(50));
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
}
