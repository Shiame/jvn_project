package irc;

import jvn.JvnReadMethod;
import jvn.JvnWriteMethod;

public interface SentenceItf {

    @JvnWriteMethod
    void write(String text);

    @JvnReadMethod
    String read();
}
