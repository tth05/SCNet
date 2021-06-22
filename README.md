
# SCNet

Java SocketChannel wrapper library for one-to-one connections with custom message packets.
This is mostly meant for two local applications that want to communicate with each other.


## Installation

### Gradle

```groovy
repositories {
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.tth05:SCNet:master-SNAPSHOT'
}
```

## Usage

### Connect
```java
try (Server s = new Server(); Client c = new Client()) {
    s.bind(new InetSocketAddress(3456));
    c.connect(new InetSocketAddress(3456));
}
```

### Send messages

To send messages, you first need to create a message class for each message you want to send or receive.
```java
class StringMessage implements IMessage /* IMessageIncoming, IMessageOutgoing */ {

    private String s;

    //Default constructor is required for messages
    public RandomDataMessage() {}

    public RandomDataMessage(String s) {
        this.s = s;
    }

    @Override
    public void read(@NotNull ByteBufferInputStream messageByteBuffer) {
        this.s = messageByteBuffer.readString();        
    }

    @Override
    public void write(@NotNull ByteBufferOutputStream messageByteBuffer) {
        messageByteBuffer.writeString(this.s);
    }

    public String getString() {
        return this.s;
    }
}
```
Then, the message needs to be registered on both sides.
```java
client.getMessageProcessor().registerMessage((short) 1, RandomDataMessage.class);
server.getMessageProcessor().registerMessage((short) 1, RandomDataMessage.class);
```
Now you can send the message and receive it.
```java
client.getMessageBus().listenAlways(RandomDataMessage.class, (message) -> System.out.println(message.getString())
server.getMessageProcessor().enqueueMessage(new RandomDataMessage("Cool!"));
```

