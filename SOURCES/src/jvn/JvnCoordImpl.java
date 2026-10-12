package jvn;

import java.io.Serializable;
import java.rmi.server.UnicastRemoteObject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class JvnCoordImpl extends UnicastRemoteObject implements JvnRemoteCoord {
    private static final long serialVersionUID = 1L;
    private int nextObjectId = 0;
    private final Map<String, ObjectInfo> objectsByName = new HashMap<>();
    private final Map<Integer, ObjectInfo> objectsById = new HashMap<>();

    private static class ObjectInfo {
        final int objectId;
        final JvnObject object;
        Serializable state;
        JvnRemoteServer writer;
        final Set<JvnRemoteServer> readers = new HashSet<>();
        boolean transitionInProgress;

        ObjectInfo(int objectId, JvnObject object, Serializable state) {
            this.objectId = objectId;
            this.object = object;
            this.state = state;
        }
    }

    public static JvnCoordImpl create() throws Exception { return new JvnCoordImpl(); }
    private JvnCoordImpl() throws Exception { super(); }

    public synchronized int jvnGetObjectId() throws java.rmi.RemoteException, JvnException {
        return nextObjectId++;
    }

    public synchronized void jvnRegisterObject(String name, JvnObject jo, JvnRemoteServer js)
            throws java.rmi.RemoteException, JvnException {
        if (name == null || jo == null || js == null)
            throw new JvnException("cannot register a null object, name or server");
        if (objectsByName.containsKey(name))
            throw new JvnException("an object named " + name + " already exists");
        int id = jo.jvnGetObjectId();
        ObjectInfo info = new ObjectInfo(id, jo, jo.jvnGetObjectState());
        info.writer = js;
        objectsByName.put(name, info);
        objectsById.put(id, info);
    }

    public synchronized JvnObject jvnLookupObject(String name, JvnRemoteServer js)
            throws java.rmi.RemoteException, JvnException {
        if (name == null) throw new JvnException("Object name cannot be null");
        ObjectInfo info = objectsByName.get(name);
        if (info == null) return null;
        return new JvnObjectImpl(info.state, info.objectId, JvnObjectImpl.NL, null);
    }

    public Serializable jvnLockRead(int id, JvnRemoteServer js)
            throws java.rmi.RemoteException, JvnException {
        ObjectInfo info;
        JvnRemoteServer oldWriter;
        synchronized (this) {
            info = getInfo(id);
            waitForTransition(info);
            if (info.writer == null) {
                info.readers.add(js);
                return info.state;
            }
            oldWriter = info.writer;
            info.transitionInProgress = true;
        }
        try {
            Serializable newState = oldWriter.jvnInvalidateWriterForReader(id);
            synchronized (this) {
                info.state = newState;
                info.readers.add(oldWriter);
                info.readers.add(js);
                info.writer = null;
                return info.state;
            }
        } finally {
            synchronized (this) {
                info.transitionInProgress = false;
                notifyAll();
            }
        }
    }

    public Serializable jvnLockWrite(int id, JvnRemoteServer js)
            throws java.rmi.RemoteException, JvnException {
        if (js == null) throw new JvnException("The server cannot be null");
        ObjectInfo info;
        JvnRemoteServer oldWriter;
        Set<JvnRemoteServer> currentReaders;
        synchronized (this) {
            info = getInfo(id);
            waitForTransition(info);
            if (js.equals(info.writer)) return info.state;
            oldWriter = info.writer;
            currentReaders = new HashSet<>(info.readers);
            info.transitionInProgress = true;
        }
        try {
            if (oldWriter != null && !oldWriter.equals(js)) {
                Serializable newState = oldWriter.jvnInvalidateWriter(id);
                synchronized (this) { info.state = newState; }
            }
            for (JvnRemoteServer reader : currentReaders)
                if (!reader.equals(js)) reader.jvnInvalidateReader(id);
            synchronized (this) {
                info.readers.clear();
                info.writer = js;
                return info.state;
            }
        } finally {
            synchronized (this) {
                info.transitionInProgress = false;
                notifyAll();
            }
        }
    }

    private ObjectInfo getInfo(int id) throws JvnException {
        ObjectInfo info = objectsById.get(id);
        if (info == null) throw new JvnException("Unknown object id :" + id);
        return info;
    }

    private void waitForTransition(ObjectInfo info) throws JvnException {
        while (info.transitionInProgress) {
            try { wait(); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new JvnException("Interrupted while waiting for coordinator");
            }
        }
    }

    public synchronized void jvnTerminate(JvnRemoteServer js)
            throws java.rmi.RemoteException, JvnException {
        if (js == null) return;
        for (ObjectInfo info : objectsById.values()) {
            if (info.writer != null && info.writer.equals(js)) {
                info.state = js.jvnInvalidateWriter(info.objectId);
                info.writer = null;
            }
            info.readers.remove(js);
        }
    }
}
