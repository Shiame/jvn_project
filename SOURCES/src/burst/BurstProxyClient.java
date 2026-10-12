package burst;

import java.util.concurrent.ThreadLocalRandom;

import irc.Sentence;
import irc.SentenceItf;
import jvn.JvnProxy;

public class BurstProxyClient {

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println(
                    "Usage: java burst.BurstProxyClient <clientId> <iterations>"
            );
            return;
        }

        int clientId = Integer.parseInt(args[0]);
        int iterations = Integer.parseInt(args[1]);

        int reads = 0;
        int writes = 0;
        int errors = 0;

        long start = System.currentTimeMillis();

        try {
            SentenceItf sentence =
                    (SentenceItf) JvnProxy.newInstance("IRC", new Sentence());

            for (int i = 0; i < iterations; i++) {
                boolean writeOperation = (i % 2 == 1);

                try {
                    if (writeOperation) {
                        sentence.write(
                                "client: " + clientId + "-operation-" + i
                        );
                        writes++;
                    } else {
                        sentence.read();
                        reads++;
                    }
                } catch (Exception e) {
                    errors++;
                    System.err.println(
                            "Client " + clientId
                            + ", operation " + i
                            + " failed: "
                            + e.getMessage()
                    );
                }

                try {
                    Thread.sleep(ThreadLocalRandom.current().nextInt(1, 30));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } catch (Exception e) {
            errors++;
            System.err.println(
                    "Burst proxy client " + clientId
                    + " stopped: "
                    + e.getMessage()
            );
            return;
        }

        long duration = System.currentTimeMillis() - start;

        System.out.println(
                "Proxy client " + clientId
                + " finished: reads=" + reads
                + ", writes=" + writes
                + ", errors=" + errors
                + ", duration=" + duration
        );
    }
}
