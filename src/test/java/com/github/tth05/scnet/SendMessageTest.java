package com.github.tth05.scnet;

import com.github.tth05.scnet.message.IMessage;
import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
        public void read(ByteBuf messageByteBuffer) {
            for (int j = 0; j < 10; j++) {
                this.i = messageByteBuffer.readInt();
            }
            this.i = messageByteBuffer.readInt();
        }

        @Override
        public void write(ByteBuf messageByteBuffer) {
            for (int j = 0; j < 10; j++) {
                messageByteBuffer.writeInt(69);
            }
            messageByteBuffer.writeInt(i);
        }
    }
}
