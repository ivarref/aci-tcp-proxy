import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

public class Proxy {

    static File logFile = null;

    public static synchronized void debug(String s) {
        try {
//            System.err.println(s);
            s = s + "\n";
            Files.write(Paths.get(logFile.getAbsolutePath()), s.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        }catch (IOException e) {
            //exception handling left as an exercise for the reader
        }
    }

    public static String getOpt(String envKey, BufferedReader bufIn) throws IOException {
        String v = System.getenv(envKey);
        if (v == null) {
            v = "";
        }
        v = v.trim();
        if (v.equalsIgnoreCase("")) {
            v = bufIn.readLine().trim();
            debug("using >" + v + "< for " + envKey + " from remote");
        }
        return v;
    }

    public static void main(String[] args) {
        try (BufferedReader bufIn = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8))) {
            final AtomicBoolean running = new AtomicBoolean(true);

            logFile = File.createTempFile("proxy-", ".log");
            logFile.deleteOnExit();

            Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
                debug("uncaught exception on thread: " + t.getName());
                debug("uncaught exception message was: " + e.getMessage());
            });

            String host = getOpt("PROXY_REMOTE_HOST", bufIn);
            String port = getOpt("PROXY_REMOTE_PORT", bufIn);

            try (Socket sock = new Socket(host, Integer.parseInt(port));
                 OutputStream toSocket = new BufferedOutputStream(sock.getOutputStream());
                 InputStream fromSocket = new BufferedInputStream(sock.getInputStream())) {
                debug("starting AciTcpProxy ...");

                Thread readStdin = new Thread() {
                    public void run() {
                        try {
                            readStdinLoop(running, bufIn, toSocket);
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
        while (running.get()) {
            int read = fromSocket.read(buf);
            if (read != 1) {
                for (int i=0; i<read; i++) {
                    byte b = buf[i];
                    out.write("\n");
                    String s = String.format("%8s", Integer.toBinaryString(b & 0xFF))
                            .replace(' ', '$')
                            .replace('0', '$')
                            .replace('1', '!');
                    out.write(s);
                    out.write("\n");
                }
                out.flush();
            } else {
                debug("read socket closed");
                running.set(false);
            }
        }
        debug("socket read loop exiting");
    }
}
