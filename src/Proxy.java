import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class Proxy {

    final static Properties props = new Properties();

    static File logFile = null;

    static boolean development = new File(".").getAbsolutePath().contains("/aci-tcp-proxy/");

    static Socket logSocket = null;
    static BufferedWriter writer = null;

    public static synchronized void debug(String s) {
        System.err.println(s);
    }

    public static synchronized void trace(String s) {
    }

    public static void loadConfig(BufferedReader in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        while (true) {
            String line = in.readLine();
            if (line == null) {
                debug("stdin closed while reading config!");
                break;
                //throw new IllegalStateException("stdin closed while reading config");
            }

            line = line.trim();
            if (line.equalsIgnoreCase("$")) {
                break;
            } else if (line.length() == 0) {
            } else if (line.length() == 8) {
                baos.write(parseLine(line));
            } else {
                debug("unhandled line: >" + line + "<");
                break;
                //throw new IllegalStateException("unhandled line: " + line);
            }
        }
        String config = new String(baos.toByteArray(), StandardCharsets.UTF_8);
        props.load(new StringReader(config));
    }

    public static void main(String[] args) {
        long startTime = System.currentTimeMillis();
        AtomicBoolean okClose = new AtomicBoolean(false);

        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8))) {
            final AtomicBoolean running = new AtomicBoolean(true);

            logFile = File.createTempFile("proxy-", ".log");
            logFile.deleteOnExit();

            loadConfig(in);
            for (Object key : props.keySet()) {
                debug("config '" + key + "' => '" + props.getProperty((String)key) + "'");
            }

            Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
                debug("uncaught exception on thread: " + t.getName());
                debug("uncaught exception message was: " + e.getMessage());
            });

            String host = "127.0.0.1"; //getOpt("PROXY_REMOTE_HOST", bufIn);
            String port = "2222"; //getOpt("PROXY_REMOTE_PORT", bufIn);

            trace("Proxy starting, development = " + development + ". Connecting to " + host + "@" + port + " ...");

            try (Socket sock = new Socket(host, Integer.parseInt(port));
                 OutputStream toSocket = new BufferedOutputStream(sock.getOutputStream());
                 InputStream fromSocket = new BufferedInputStream(sock.getInputStream())) {

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
            trace("AciTcpProxy exiting... Spent " + spentTime + " ms");
        }
    }

    private static int parseLine(String line) {
        line = line.trim();
        line = line.replace('_', '0')
                .replace('!', '1');
        return Integer.parseInt(line, 2);
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
                    int b = parseLine(line);
                    try {
                        toSocket.write(b);
                        counter += 1;
                    } catch (Exception e) {
                        debug("writing to socket failed!: " + e.getMessage());
                        throw e;
                    }
                } else if (line.equalsIgnoreCase("$")) {
                    trace("flushing socket...");
                    toSocket.flush();
                    debug("wrote chunk of length " + counter + " to socket");
                    counter = 0;
                }
            }
        }
        trace("stdin loop exiting");
    }

    private static void readSocketLoop(AtomicBoolean running, BufferedWriter out, InputStream fromSocket) throws IOException {
        byte[] buf = new byte[65535];
        Base64.Encoder encoder = Base64.getMimeEncoder();
        while (running.get()) {
            int read = fromSocket.read(buf);
            if (read != 1) {
                String chunk = encoder.encodeToString(Arrays.copyOf(buf, read)).trim();
                out.write(chunk);
                out.write("#\n");
                out.flush();
                debug("got chunk of " + read + " bytes from socket");
            } else {
                debug("read socket closed");
                running.set(false);
            }
        }
        debug("socket read loop exiting");
    }
}
