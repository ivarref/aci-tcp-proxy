import java.io.*;
import java.nio.charset.StandardCharsets;

public class Hello {

    public static void main(String[] args) throws IOException {
        System.err.println("hello from stderr... \uD83D\uDE0D");

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        System.err.println("starting readLine loop ...");
        while (true) {
            String line = in.readLine();
            if (line == null) break;
//            System.out.println("hello got: " + line);
        }
        System.err.println("readLine loop done");
    }
}
