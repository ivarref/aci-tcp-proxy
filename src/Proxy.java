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

    public static String getOpt(String envKey) throws IOException {
        String v = System.getenv(envKey);
        if (v == null) {
            v = "";
        }
        v = v.trim();
        if (v.equalsIgnoreCase("")) {
            v = readLine();
            if (v == null) {
                debug("ERROR: got null for " +envKey);
                throw new RuntimeException("got null for " + envKey);
            }
            v = v.trim();
            debug("using >" + v + "< for " + envKey + " from remote");
        }
        return v;
    }

    private static final BufferedReader bufStdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

    public static String readLine() throws IOException {
        if (System.console() != null) {
            char[] chars = System.console().readPassword();
            if (chars == null) {
                return null;
            } else {
                String v = new String(chars);
                return v;
            }
        } else {
            return bufStdin.readLine();
        }
    }

    public static void main(String[] args) throws IOException {
        logFile = File.createTempFile("proxy-", ".log");
        logFile.deleteOnExit();

        debug("AciTcpProxy starting ...");
        try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8))) {
            final AtomicBoolean running = new AtomicBoolean(true);


            Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
                debug("uncaught exception on thread: " + t.getName());
                debug("uncaught exception message was: " + e.getMessage());
            });

            String host = getOpt("PROXY_REMOTE_HOST");
            String port = getOpt("PROXY_REMOTE_PORT");

            try (Socket sock = new Socket(host, Integer.parseInt(port));
                 OutputStream toSocket = new BufferedOutputStream(sock.getOutputStream());
                 InputStream fromSocket = new BufferedInputStream(sock.getInputStream())) {
                debug("starting AciTcpProxy ...");

                Thread readStdin = new Thread() {
                    public void run() {
                        try {
                            readStdinLoop(running, toSocket);
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

    private static void readStdinLoop(AtomicBoolean running, OutputStream toSocket) throws IOException {
        StringBuilder sb = new StringBuilder();
        final Base64.Decoder decoder = Base64.getMimeDecoder();
        while (running.get()) {
            String line = readLine();
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
