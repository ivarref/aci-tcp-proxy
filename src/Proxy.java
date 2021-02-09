import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

public class Proxy {

    public static void main(String[] args) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
             Socket sock = new Socket("127.0.0.1", 7777);
             OutputStream toSocket = new BufferedOutputStream(sock.getOutputStream());
             InputStream fromSocket = new BufferedInputStream(sock.getInputStream())) {
            final AtomicBoolean running = new AtomicBoolean(true);

            System.err.println("starting AciTcpProxy ...");

            Thread readStdin = new Thread() {
                public void run() {
                    try {
                        readStdinLoop(running, in, toSocket);
                    } catch (Throwable t) {
                        System.err.println("error in stdin read loop: " + t.getMessage());
                    }
                }
            };

            Thread readSocket = new Thread() {
                public void run() {
                    try {
                        readSocketLoop(running, out, fromSocket);
                    } catch (Throwable t) {
                        System.err.println("error in socket read loop:" + t.getMessage());
                    }
                }
            };

            readStdin.start();
            readSocket.start();

            readStdin.join();
            readSocket.interrupt();
        } catch (Throwable t) {
            System.err.println("Unexpected exception in AciTcpProxy. Message: " + t.getMessage());
        } finally {
            System.err.println("AciTcpProxy exiting...");
        }
    }

    private static void readStdinLoop(AtomicBoolean running, BufferedReader in, OutputStream toSocket) throws IOException {
        StringBuilder sb = new StringBuilder();
        final Base64.Decoder decoder = Base64.getMimeDecoder();
        while (running.get()) {
            String line = in.readLine();
            if (line != null) {
                sb.append(line);
                if (line.trim().equals("")) {
                    byte[] byteChunk = decoder.decode(sb.toString());
                    toSocket.write(byteChunk);
                    toSocket.flush();
                    sb = new StringBuilder();
                } else {
                    sb.append("\n");
                }
            } else {
                System.err.println("stdin closed");
                running.set(false);
            }
        }
        System.err.println("stdin loop exiting");
    }

    private static void readSocketLoop(AtomicBoolean running, BufferedWriter out, InputStream fromSocket) throws IOException {
        byte[] buf = new byte[1024];
        final Base64.Encoder encoder = Base64.getMimeEncoder();
        while (running.get()) {
            int read = fromSocket.read(buf);
            if (read != 1) {
                String encoded = encoder.encodeToString(Arrays.copyOf(buf, read));
                out.write(encoded);
                out.write("\n");
                out.flush();
            } else {
                System.err.println("read socket closed");
                running.set(false);
            }
        }
        System.err.println("socket read loop exiting");
    }
}
