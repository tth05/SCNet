package com.github.tth05.scnet;

import com.github.tth05.scnet.message.AbstractMessageIncoming;
import com.github.tth05.scnet.message.AbstractMessageOutgoing;
import com.github.tth05.scnet.message.impl.DefaultMessageProcessor;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

public class MessageRegistrationTest {

    @Test
    public void rejectsAnOutgoingIdAlreadyUsedByAnIncomingMessage() {
        DefaultMessageProcessor processor = new DefaultMessageProcessor();
        processor.registerMessage((short) 1, IncomingMessage.class, IncomingMessage::new);

        assertThrows(
                IllegalArgumentException.class,
                () -> processor.registerMessage((short) 1, OutgoingMessage.class)
        );
    }

    @Test
    public void rejectsRegisteringOneOutgoingClassUnderTwoIds() {
        DefaultMessageProcessor processor = new DefaultMessageProcessor();
        processor.registerMessage((short) 1, OutgoingMessage.class);

        assertThrows(
                IllegalArgumentException.class,
                () -> processor.registerMessage((short) 2, OutgoingMessage.class)
        );
    }

    public static final class IncomingMessage extends AbstractMessageIncoming {

        @Override
        public void read(@NotNull ByteBufferInputStream messageStream) {
        }
    }

    public static final class OutgoingMessage extends AbstractMessageOutgoing {

        @Override
        public void write(@NotNull ByteBufferOutputStream messageStream) {
        }
    }
}
