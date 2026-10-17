package burst;

import irc.Sentence;
import irc.SentenceItf;
import jvn.JvnProxy;

/**
 * Writes once, then stays alive so that it can be killed to simulate a crash.
 */
public final class CrashWriterClient {
    private CrashWriterClient() {}

    public static void main(String[] args) throws Exception {
        String value = args.length == 0 ? "value written before crash" : args[0];
        SentenceItf sentence = (SentenceItf) JvnProxy.newInstance("IRC", new Sentence());
        sentence.write(value);
        System.out.println("Writer ready. Kill this JVM with Ctrl+C or kill -9.");
        Thread.sleep(Long.MAX_VALUE);
    }
}
