import com.google.gson.Gson;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Scanner;

import static java.nio.channels.SelectionKey.OP_READ;
import static java.nio.channels.SelectionKey.OP_WRITE;

public class Client implements Runnable {
    private final static String HOSTNAME = "127.0.0.1";
    private final static int PORT = 8511;
    private boolean connected = false;
    private boolean register = false;
    private String string;
    private boolean threadStarted = false;

    private Selector selector;
    private Scanner scanner = new Scanner(System.in);


    @Override
    public void run() {
        SocketChannel channel;
        try {
            selector = Selector.open();
            channel = SocketChannel.open();
            channel.configureBlocking(false);

            channel.register(selector, SelectionKey.OP_CONNECT);
            channel.connect(new InetSocketAddress(HOSTNAME, PORT));

            while (!Thread.interrupted()) {
                selector.select(100);

                Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                SelectionKey key = null;
                while (keys.hasNext()) {
                    key = keys.next();

                    if (!key.isValid()) continue;

                    if (key.isConnectable() && !connected) {
                        connect(key);
                        connected = true;
                    }
                    if (key.isReadable()) {
                        read(key);
                    }
                    if (key.isWritable()) {
                        write(key);
                    }
                }

            }
        } catch (IOException | CancelledKeyException ignored) {
        } finally {
            close();
        }
    }

    private void close() {
        try {
            selector.close();
        } catch (IOException ignored) {
        }
    }

    private void read(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        ByteBuffer readBuffer = ByteBuffer.allocate(1000);
        readBuffer.clear();
        int length;
        try {
            length = channel.read(readBuffer);
        } catch (IOException e) {
            System.out.println("Reading problem, closing connection");
            key.cancel();
            channel.close();
            return;
        }
        if (length == -1) {
            System.out.println("Nothing was read from server");
            channel.close();
            key.cancel();
            return;
        }
        if (length > 0){
            readBuffer.flip();
            byte[] buff = new byte[100000];
            readBuffer.get(buff, 0, length);
            System.out.println(new String(buff));
        }
    }

    private void write(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        if (!register) {
            register(key, channel);
        }
        else {
            sendMessage(key, channel);
        }
    }

    private void sendMessage(SelectionKey key, SocketChannel channel) throws IOException {
        if (!threadStarted){
            Thread thread = new Thread(()-> {
                string = scanner.nextLine();
            });
            thread.start();
            threadStarted = true;
        }
        if (string != null){
            threadStarted = false;
            Message message = new Message(null, null, string, null);
            String[] strings = string.split("--file");
            if (strings.length > 1){
                message.setFile(strings[1].trim());
                message.setMessage("Файл передан: " + strings[1].trim());
                File file = new File(message.getFile());
                byte[] fileContent = Files.readAllBytes(file.toPath());
                String messageJson = new Gson().toJson(message);
                byte[] firstPartMessage = concat(createHeader(messageJson, false, false, false, true, (int) file.length()), fileContent);
                byte[] messageByte = concat(firstPartMessage, messageJson.getBytes());
                channel.write(ByteBuffer.wrap(messageByte));
                key.interestOps(OP_READ);
            }
            else {
                String messageJson = new Gson().toJson(message);
                byte[] messageByte = concat(createHeader(messageJson, false, false, false, false, 0), messageJson.getBytes());
                channel.write(ByteBuffer.wrap(messageByte));
                key.interestOps(OP_READ);
            }
            if (string.equals("/qqq")){
                channel.close();
                key.cancel();
                Thread.currentThread().interrupt();
            }
            string = null;
        }
    }

    private void register(SelectionKey key, SocketChannel channel) throws IOException {
        String zone = ZoneId.systemDefault().toString();
        if (!threadStarted){
            Thread thread = new Thread(()-> {
                string = scanner.nextLine();
            });
            thread.start();
            threadStarted = true;
        }
        if (string != null){
            threadStarted = false;
            Gson gson = new Gson();
            Message messageZone = new Message(null, null, zone, null);
            String messageZoneJson = gson.toJson(messageZone);
            Message messageName = new Message(null, null, string, null);
            String messageNameJson = gson.toJson(messageName);
            byte[] zoneByte = concat(createHeader(messageZoneJson, true, false, false, false, 0), messageZoneJson.getBytes());
            byte[] nameByte = concat(createHeader(messageNameJson, false, true, false, false, 0), messageNameJson.getBytes());
            channel.write(ByteBuffer.wrap(concat(zoneByte, nameByte)));
            key.interestOps(OP_READ);
            register = true;
            string = null;
        }
    }

    private void connect(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        if (channel.isConnectionPending()) {
            channel.finishConnect();
        }
        channel.configureBlocking(false);
        channel.register(selector, OP_WRITE);
    }

    public static void main(String[] args) {
        Client test1 = new Client();
        Thread thread = new Thread(test1);
        thread.start();
    }

    public byte[] createHeader(String message, boolean startTimeCode, boolean startName, boolean finish, boolean file, int size) throws UnsupportedEncodingException {
        byte[] array = new byte[12];
        array[0] = (byte) (1);
        array[1] = (byte) (1);
        int messageSize = message.getBytes().length;
        array[2] = (byte) (messageSize / (int) Math.pow(2, 8));
        array[3] = (byte) (messageSize % (int) Math.pow(2, 8));
        array[4] = (byte) ((booleanToInt(startTimeCode) << 7) + (booleanToInt(startName) << 6)
                + (booleanToInt(finish) << 5) + (booleanToInt(file) << 4));
        byte[] hashcode = intToByteArray(message.hashCode());
        for (int i = 5; i < 8; i++) {
            array[i] = hashcode[i - 5];
        }
        byte[] sizeBytes = intToByteArrayBig(size);
        for (int i = 8; i < 12; i++) {
            array[i] = sizeBytes[i - 8];
        }
        return array;
    }

    public final byte[] intToByteArrayBig(int value) {
        return new byte[]{
                (byte) (value >>> 24),
                (byte) (value >>> 16),
                (byte) (value >>> 8),
                (byte) value};
    }

    public final byte[] intToByteArray(int value) {
        return new byte[]{
                (byte) (value >>> 16),
                (byte) (value >>> 8),
                (byte) value};
    }

    public int booleanToInt(boolean b) {
        return b ? 1 : 0;
    }

    public byte[] concat(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
