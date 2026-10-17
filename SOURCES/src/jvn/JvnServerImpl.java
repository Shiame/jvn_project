package jvn;

import java.io.Serializable;
import java.rmi.Naming;
import java.rmi.server.UnicastRemoteObject;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

public class JvnServerImpl
        extends UnicastRemoteObject
        implements JvnLocalServer, JvnRemoteServer {

    private static final long serialVersionUID = 1L;
    private static final String CACHE_SIZE_PROPERTY = "jvn.cache.max.objects";

    // Singleton
    private static JvnServerImpl js = null;

    // Reference to the coordinator
    private JvnRemoteCoord coord;

    // Objects managed by this server
    private Map<Integer, JvnObject> objects;

    // Objects associated with symbolic names
    private Map<String, JvnObject> namedObjects;
    private final int maxCacheObjects;


    /**
     * Constructor
     */
    private JvnServerImpl() throws Exception {
        super();

        objects = new LinkedHashMap<>(16, 0.75f, true);
        namedObjects = new LinkedHashMap<>();
        maxCacheObjects = readCacheCapacity();

        // Connection to the JVN coordinator
        coord = (JvnRemoteCoord)
                Naming.lookup("rmi://localhost/JvnCoord");
    }


    /**
     * Return the unique JVN server instance
     */
    public static JvnServerImpl jvnGetServer() {

        if (js == null) {
            try {
                js = new JvnServerImpl();
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        }

        return js;
    }


    /**
     * Create a JVN object
     */
    @Override
    public synchronized JvnObject jvnCreateObject(Serializable o)
            throws JvnException {

        try {

            // Ask the coordinator for a new object ID
            int objectId = coord.jvnGetObjectId();

            /*
             * A newly created object has a WRITE lock.
             * W = 4 in JvnObjectImpl.
             */
            JvnObject jo = new JvnObjectImpl(
                    o,
                    objectId,
                    4,
                    this
            );

            // Store the object locally
            objects.put(objectId, jo);
            enforceCacheCapacity(objectId);

            return jo;

        } catch (Exception e) {

            throw new JvnException(
                    "Error creating object: "
                    + e.getMessage()
            );
        }
    }


    /**
     * Register a JVN object with a symbolic name
     */
    @Override
    public synchronized void jvnRegisterObject(
            String jon,
            JvnObject jo)
            throws JvnException {

        try {

            namedObjects.put(jon, jo);

            coord.jvnRegisterObject(
                    jon,
                    jo,
                    this
            );

        } catch (Exception e) {

            throw new JvnException(
                    "Error registering object: "
                    + e.getMessage()
            );
        }
    }


    /**
     * Lookup a JVN object using its symbolic name
     */
    @Override
    public synchronized JvnObject jvnLookupObject(
            String jon)
            throws JvnException {

        try {

            // First search locally
            JvnObject jo = namedObjects.get(jon);

            if (jo != null) {
                objects.get(jo.jvnGetObjectId());
                return jo;
            }

            // Otherwise ask the coordinator
            jo = coord.jvnLookupObject(
                    jon,
                    this
            );

            if (jo != null) {

                namedObjects.put(jon, jo);

                objects.put(
                        jo.jvnGetObjectId(),
                        jo
                );
                enforceCacheCapacity(jo.jvnGetObjectId());
            }
            if (jo instanceof JvnObjectImpl) {
                ((JvnObjectImpl) jo).setServer(this);
            }

            return jo;

        } catch (Exception e) {

            throw new JvnException(
                    "Error looking up object: "
                    + e.getMessage()
            );
        }
    }


    /**
     * Get a READ lock
     */
    @Override
    public Serializable jvnLockRead(int joi)
            throws JvnException {

        try {

            return coord.jvnLockRead(
                    joi,
                    this
            );

        } catch (Exception e) {

            throw new JvnException(
                    "Error acquiring READ lock: "
                    + e.getMessage()
            );
        }
    }


    /**
     * Get a WRITE lock
     */
    @Override
    public Serializable jvnLockWrite(int joi)
            throws JvnException {

        try {

            return coord.jvnLockWrite(
                    joi,
                    this
            );

        } catch (Exception e) {

            throw new JvnException(
                    "Error acquiring WRITE lock: "
                    + e.getMessage()
            );
        }
    }


    /**
     * Terminate the JVN service
     */
    @Override
    public synchronized void jvnTerminate()
            throws JvnException {

        try {

            coord.jvnTerminate(this);

            objects.clear();
            namedObjects.clear();

        } catch (Exception e) {

            throw new JvnException(
                    "Error terminating JVN server: "
                    + e.getMessage()
            );
        }
    }

    /** Flush every cached object which is not currently used by the application. */
    public synchronized int jvnFlushCache() throws JvnException {
        int flushed = 0;
        Iterator<Map.Entry<Integer, JvnObject>> iterator = objects.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, JvnObject> entry = iterator.next();
            if (flushObject(entry.getKey(), entry.getValue(), iterator)) {
                flushed++;
            }
        }
        return flushed;
    }

    public synchronized int jvnGetCacheSize() {
        return objects.size();
    }

    synchronized void jvnTouchObject(int objectId) {
        objects.get(objectId);
    }

    private void enforceCacheCapacity(int protectedObjectId) throws JvnException {
        while (objects.size() > maxCacheObjects) {
            boolean flushed = false;
            Iterator<Map.Entry<Integer, JvnObject>> iterator = objects.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Integer, JvnObject> entry = iterator.next();
                if (entry.getKey() == protectedObjectId) continue;
                if (flushObject(entry.getKey(), entry.getValue(), iterator)) {
                    flushed = true;
                    break;
                }
            }
            if (!flushed) return;
        }
    }

    private boolean flushObject(
            int objectId,
            JvnObject object,
            Iterator<Map.Entry<Integer, JvnObject>> iterator)
            throws JvnException {
        if (!(object instanceof JvnObjectImpl)) return false;

        JvnObjectImpl implementation = (JvnObjectImpl) object;
        Serializable state;
        try {
            state = implementation.jvnBeginFlush();
        } catch (JvnException e) {
            return false;
        }

        try {
            coord.jvnFlushObject(objectId, state, this);
            implementation.jvnCompleteFlush();
            iterator.remove();
            namedObjects.entrySet().removeIf(entry -> entry.getValue() == object);
            return true;
        } catch (Exception e) {
            implementation.jvnAbortFlush();
            throw new JvnException("Error flushing cached object: " + e.getMessage());
        }
    }

    private int readCacheCapacity() {
        String configuredValue = System.getProperty(CACHE_SIZE_PROPERTY);
        if (configuredValue == null) return 32;
        try {
            return Math.max(1, Integer.parseInt(configuredValue));
        } catch (NumberFormatException e) {
            return 32;
        }
    }


    /**
     * Invalidate the READ lock
     *
     * Called remotely by the coordinator.
     */
    @Override
    public void jvnInvalidateReader(int joi)
            throws java.rmi.RemoteException,
                   JvnException {

        JvnObject jo;

        synchronized (this) {
            jo = objects.get(joi);
        }

        if (jo == null) {
            throw new JvnException(
                    "Unknown object: " + joi
            );
        }

        jo.jvnInvalidateReader();
    }


    /**
     * Invalidate the WRITE lock
     *
     * Called remotely by the coordinator.
     */
    @Override
    public Serializable jvnInvalidateWriter(int joi)
            throws java.rmi.RemoteException,
                   JvnException {

        JvnObject jo;

        synchronized (this) {
            jo = objects.get(joi);
        }

        if (jo == null) {
            throw new JvnException(
                    "Unknown object: " + joi
            );
        }

        return jo.jvnInvalidateWriter();
    }


    /**
     * Reduce WRITE lock to READ lock
     *
     * Called remotely by the coordinator.
     */
    @Override
    public Serializable jvnInvalidateWriterForReader(int joi)
            throws java.rmi.RemoteException,
                   JvnException {

        JvnObject jo;

        synchronized (this) {
            jo = objects.get(joi);
        }

        if (jo == null) {
            throw new JvnException(
                    "Unknown object: " + joi
            );
        }

        return jo.jvnInvalidateWriterForReader();
    }
}
