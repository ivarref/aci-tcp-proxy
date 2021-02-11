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

    static boolean development = new File(".").getAbsolutePath().contains("/aci-tcp-proxy/");

    static Socket logSocket = null;
    static BufferedWriter writer = null;

    public static synchronized void debug(String s) {
        System.err.println(s);
        try {
            if (development) {
                if (logSocket == null) {
                    logSocket = new Socket("127.0.0.1", 6666);
                    writer = new BufferedWriter(new OutputStreamWriter(logSocket.getOutputStream(), StandardCharsets.UTF_8));
                }
//                writer.write(s + "\n");
//                writer.flush();

                ;
            }
//            s = s + "\n";
//            Files.write(Paths.get(logFile.getAbsolutePath()), s.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        } catch (IOException e) {
            //exception handling left as an exercise for the reader
        }
    }

    public static synchronized void trace(String s) {
    }

    public static String getOpt(String envKey, BufferedReader bufIn) throws IOException {
        String v = System.getenv(envKey);
        if (v == null) {
            v = "";
        }
        v = v.trim();
        if (v.equalsIgnoreCase("")) {
            v = bufIn.readLine();
            if (v == null) {
                debug("could not get any value for " + envKey + " from remote!");
                return null;
            } else {
                v = v.trim();
                debug("using >" + v + "< for " + envKey + " from remote");
            }
        }
        return v;
    }

    public static void main(String[] args) {
        long startTime = System.currentTimeMillis();
        AtomicBoolean okClose = new AtomicBoolean(false);

        try (BufferedReader bufIn = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8))) {
            final AtomicBoolean running = new AtomicBoolean(true);

            logFile = File.createTempFile("proxy-", ".log");
            logFile.deleteOnExit();


            Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
                debug("uncaught exception on thread: " + t.getName());
                debug("uncaught exception message was: " + e.getMessage());
            });

            String host = "127.0.0.1"; //getOpt("PROXY_REMOTE_HOST", bufIn);
            String port = "2222"; //getOpt("PROXY_REMOTE_PORT", bufIn);

            debug("Proxy starting, development = " + development + ". Connecting to " + host + "@" + port + " ...");

            try (Socket sock = new Socket(host, Integer.parseInt(port));
                 OutputStream toSocket = new BufferedOutputStream(sock.getOutputStream());
                 InputStream fromSocket = new BufferedInputStream(sock.getInputStream())) {
                System.out.println("READY :-)");

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
                            if (t.getMessage().equalsIgnoreCase("Socked closed")) {
                                debug("error class was: " + t.getClass());
                                debug("error in socket read loop: " + t.getMessage());
                            }
                        }
                    }
                };

                readStdin.start();
                readSocket.start();

                readStdin.join();
                try {
                    sock.close();
                } catch (Exception e) {
                }
                readSocket.join(3000);
                if (readSocket.isAlive()) {
                    debug("failed to close read socket loop!");
                } else {
                    okClose.set(true);
                }
            }
        } catch (Throwable t) {
            if (!(t.getMessage().equalsIgnoreCase("Socket closed") && okClose.get())) {
                debug("class of unexpected exception: " + t.getClass());
                debug("Unexpected exception in AciTcpProxy. Message: " + t.getMessage());
            }
        } finally {
            long spentTime = System.currentTimeMillis() - startTime;
            debug("AciTcpProxy exiting... Spent " + spentTime + " ms");
        }
    }

    private static void readStdinLoop(AtomicBoolean running, BufferedReader in, OutputStream toSocket) throws IOException {
        int counter = 0;
        while (running.get()) {
            String line = in.readLine();
            if (line == null) {
                running.set(false);
            } else {
                line = line.trim();
                if (line.length() == 8) {
                    line = line.replace('_', '0')
                            .replace('!', '1');
                    int b = Integer.parseInt(line, 2);
                    try {
                        toSocket.write(b);
                        counter += 1;
                    } catch (Exception e) {
                        debug("writing to socket failed!: " + e.getMessage());
                        throw e;
                    }
                } else if (line.equalsIgnoreCase("$")) {
                    debug("wrote chunk of length " + counter + " to socket");
                    counter = 0;
                }
            }
        }
        debug("stdin loop exiting");
    }

    private static void readSocketLoop(AtomicBoolean running, BufferedWriter out, InputStream fromSocket) throws IOException {
        byte[] buf = new byte[1024];
        while (running.get()) {
            int read = fromSocket.read(buf);
            if (read != 1) {
                for (int i = 0; i < read; i++) {
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
