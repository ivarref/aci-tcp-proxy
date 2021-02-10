import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

public class Proxy {

    static File logFile = null;

    public static synchronized void debug(String s) {
        try {
            s = s + "\n";
            Files.write(Paths.get(logFile.getAbsolutePath()), s.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        }catch (IOException e) {
            //exception handling left as an exercise for the reader
        }
    }

    public static void main(String[] args) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8))) {
            final AtomicBoolean running = new AtomicBoolean(true);

            logFile = File.createTempFile("proxy-", ".log");
            logFile.deleteOnExit();

            Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
                debug("uncaught exception on thread: " + t.getName());
                debug("uncaught exception message was: " + e.getMessage());
            });

            String host = System.getenv("PROXY_REMOTE_HOST") != null ? System.getenv("PROXY_REMOTE_HOST") : in.readLine();
            String port = System.getenv("PROXY_REMOTE_PORT") != null ? System.getenv("PROXY_REMOTE_PORT") : in.readLine();

            try (Socket sock = new Socket(host, Integer.parseInt(port));
                 OutputStream toSocket = new BufferedOutputStream(sock.getOutputStream());
                 InputStream fromSocket = new BufferedInputStream(sock.getInputStream())) {
                debug("starting AciTcpProxy ...");

                Thread readStdin = new Thread() {
                    public void run() {
                        try {
                            readStdinLoop(running, in, toSocket);
                        } catch (Throwable t) {
                            debug("error in stdin read loop: " + t.getMessage());
                        }
                    }
                };

                Thread readSocket = new Thread() {
                    public void run() {
                        try {
                            readSocketLoop(running, out, fromSocket);
                        } catch (Throwable t) {
                            debug("error in socket read loop: " + t.getMessage());
                        }
                    }
                };

                readStdin.start();
                readSocket.start();

                readStdin.join();
                Thread.sleep(3000);
                readSocket.interrupt();
            }
        } catch (Throwable t) {
            debug("Unexpected exception in AciTcpProxy. Message: " + t.getMessage());
        } finally {
            debug("AciTcpProxy exiting...");
        }
    }

    private static void readStdinLoop(AtomicBoolean running, BufferedReader in, OutputStream toSocket) throws IOException {
        StringBuilder sb = new StringBuilder();
        final Base64.Decoder decoder = Base64.getMimeDecoder();
        while (running.get()) {
            String line = in.readLine();
            if (line == null || line.trim().equals("")) {
                byte[] byteChunk = decoder.decode(sb.toString());
                toSocket.write(byteChunk);
                toSocket.flush();
                sb = new StringBuilder();
            }

            if (line == null) {
                debug("stdin closed");
                running.set(false);
            } else {
                sb.append(line);
                sb.append("\n");
            }
        }
        debug("stdin loop exiting");
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
                debug("read socket closed");
                running.set(false);
            }
        }
        debug("socket read loop exiting");
    }
}
