package burst;

import irc.Sentence;
import irc.SentenceItf;
import jvn.JvnProxy;
import jvn.JvnServerImpl;

/** Demonstrates automatic and manual eviction from one client's local cache. */
public final class CacheSaturationClient {
    private CacheSaturationClient() {}

    public static void main(String[] args) throws Exception {
        int objectCount = args.length == 0 ? 3 : Integer.parseInt(args[0]);
        JvnServerImpl server = JvnServerImpl.jvnGetServer();
        if (server == null) throw new IllegalStateException("Cannot initialize JVN server");

        for (int index = 0; index < objectCount; index++) {
            SentenceItf sentence = (SentenceItf) JvnProxy.newInstance(
                    "CACHE-" + index,
                    new Sentence()
            );
            sentence.write("value-" + index);
        }

        System.out.println("Cache size after creation: " + server.jvnGetCacheSize());
        System.out.println("Flushed objects: " + server.jvnFlushCache());
        System.out.println("Cache size after manual flush: " + server.jvnGetCacheSize());

        SentenceItf restored = (SentenceItf) JvnProxy.newInstance("CACHE-0", new Sentence());
        System.out.println("Restored value: " + restored.read());
    }
}
