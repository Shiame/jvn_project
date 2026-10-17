package burst;

import irc.Sentence;
import irc.SentenceItf;
import jvn.JvnProxy;

/** Reads the shared value after another client has been stopped. */
public final class ReadAfterFailureClient {
    private ReadAfterFailureClient() {}

    public static void main(String[] args) throws Exception {
        SentenceItf sentence = (SentenceItf) JvnProxy.newInstance("IRC", new Sentence());
        System.out.println("Read value: " + sentence.read());
    }
}
