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
    static BufferedWriter logWriter = null;

    public static synchronized void debug(String s) {
        if (development) {
            System.err.println(s);
        }

        if (logWriter != null) {
            try {
                logWriter.write(s + "\n");
                logWriter.flush();
            } catch (IOException e) {
                // help!
            }
        }
    }

    public static synchronized void trace(String s) {
    }

    static final String alphabet = "_!@%'&*([{}]).,;";

    static private class Chunk {
        public final byte[] bytes;
        public final boolean dataChunk;
        public final boolean closed;

        private Chunk(byte[] bytes, boolean dataChunk, boolean closed) {
            this.bytes = bytes;
            this.dataChunk = dataChunk;
            this.closed = closed;
        }

        public boolean isRemoteCmd(String cmd) {
            if (dataChunk) {
                return false;
            } else {
                return cmd.equalsIgnoreCase(new String(bytes, StandardCharsets.UTF_8));
            }
        }
    }

    public static Chunk readChunk(BufferedReader in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        boolean dataChunk = true;
        boolean closed = false;

        while (true) {
            String line = in.readLine();
            if (line == null) {
                debug("stdin closed");
                closed = true;
                break;
            }

            line = line.trim();
            if (line.equalsIgnoreCase("$")) {
                break;
            } else if (line.equalsIgnoreCase("$$")) {
                dataChunk = false;
                break;
            } else if (line.equalsIgnoreCase("")) {
            } else {
                for (int i = 0; i < line.length(); i += 2) {
                    int high = alphabet.indexOf(line.charAt(i)) << 4;
                    int low = alphabet.indexOf(line.charAt(i + 1));
                    baos.write(low+high);
                }
            }
        }
        return new Chunk(baos.toByteArray(), dataChunk, closed);
    }

    public static void main(String[] args) {
        long startTime = System.currentTimeMillis();
        AtomicBoolean okClose = new AtomicBoolean(false);

        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8))) {
            final AtomicBoolean running = new AtomicBoolean(true);

            logFile = File.createTempFile("proxy-", ".log");
            logFile.deleteOnExit();

            String chunk = Base64.getMimeEncoder().encodeToString("ready!".getBytes(StandardCharsets.UTF_8)).trim();
            out.write(chunk);
            out.write("^\n");
            out.flush();

            Chunk c = readChunk(in);
            props.load(new StringReader(new String(c.bytes, StandardCharsets.UTF_8)));

            Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
                debug("uncaught exception on thread: " + t.getName());
                debug("uncaught exception message was: " + e.getMessage());
            });

            String host = props.getProperty("host", "localhost");
            String port = props.getProperty("port", "7777");

            String logPort = props.getProperty("logPort", "-1");
            if (!logPort.equalsIgnoreCase("-1")) {
                logSocket = new Socket("localhost", Integer.parseInt(logPort));
                logWriter = new BufferedWriter(new OutputStreamWriter(logSocket.getOutputStream(), StandardCharsets.UTF_8));
            }

            debug("Proxy starting, development = " + development + ". Connecting to " + host + "@" + port + " ...");
            try (Socket sock = new Socket(host, Integer.parseInt(port));
                 OutputStream toSocket = new BufferedOutputStream(sock.getOutputStream());
                 InputStream fromSocket = new BufferedInputStream(sock.getInputStream())) {

                Thread readStdin = new Thread() {
                    public void run() {
                        try {
                            readStdinLoop(running, out, in, toSocket);
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

        try {
            if (logWriter != null) {
                logWriter.close();
                logWriter = null;
            }
            if (logSocket != null) {
                logSocket.close();
                logSocket = null;
            }
        } catch (Throwable t) {

        }
    }

    private static int parseLine(String line) {
        line = line.trim();
        line = line.replace('_', '0')
                .replace('!', '1');
        return Integer.parseInt(line, 2);
    }

    private static void readStdinLoop(AtomicBoolean running, BufferedWriter out, BufferedReader in, OutputStream toSocket) throws IOException {
        while (running.get()) {
            Chunk c = readChunk(in);
            if (c.closed) {
                running.set(false);
            } else if (c.dataChunk) {
                toSocket.write(c.bytes);
                toSocket.flush();
                debug("wrote chunk of length " + c.bytes.length + " to socket");
                writeCmd(out, "chunk-ok");
            } else if (c.isRemoteCmd("close!")) {
                debug("close requested from remote");
                running.set(false);
            }
        }
        debug("stdin loop exiting");
    }

    private static synchronized void writeOut(BufferedWriter out, String message) {
        try {
            out.write(message + "\n");
            out.flush();
        } catch (IOException e) {
            debug("error during writing to out: " + e.getMessage());
        }
    }

    private static synchronized void writeCmd(BufferedWriter out, String cmd) {
        Base64.Encoder encoder = Base64.getMimeEncoder();
        writeOut(out, encoder.encodeToString(cmd.getBytes(StandardCharsets.UTF_8)) + "^");
    }


    private static void readSocketLoop(AtomicBoolean running, BufferedWriter out, InputStream fromSocket) throws IOException {
        byte[] buf = new byte[65535];
        Base64.Encoder encoder = Base64.getMimeEncoder();
        while (running.get()) {
            int read = fromSocket.read(buf);
            if (read != 1) {
                String chunk = encoder.encodeToString(Arrays.copyOf(buf, read)).trim();
                writeOut(out, chunk + "#");
                debug("got chunk of " + read + " bytes from socket");
            } else {
                debug("read socket closed");
                running.set(false);
            }
        }
        debug("socket read loop exiting");
    }
}
