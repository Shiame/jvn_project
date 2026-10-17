package jvn;

import java.io.Serializable;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class JvnCoordImpl extends UnicastRemoteObject implements JvnRemoteCoord {
    private static final long serialVersionUID = 1L;
    private static final String STATE_FILE_PROPERTY = "jvn.coord.state.file";

    private int nextObjectId = 0;
    private final Map<String, ObjectInfo> objectsByName = new HashMap<>();
    private final Map<Integer, ObjectInfo> objectsById = new HashMap<>();
    private final Path stateFile;

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

    private static class PersistedObject implements Serializable {
        private static final long serialVersionUID = 1L;

        final String name;
        final int objectId;
        final Serializable state;

        PersistedObject(String name, int objectId, Serializable state) {
            this.name = name;
            this.objectId = objectId;
            this.state = state;
        }
    }

    private static class CoordinatorSnapshot implements Serializable {
        private static final long serialVersionUID = 1L;

        final int nextObjectId;
        final Map<String, PersistedObject> objects;

        CoordinatorSnapshot(int nextObjectId, Map<String, PersistedObject> objects) {
            this.nextObjectId = nextObjectId;
            this.objects = objects;
        }
    }

    public static JvnCoordImpl create() throws Exception {
        Path stateFile = resolveStateFile();
        return new JvnCoordImpl(stateFile, loadSnapshot(stateFile));
    }

    private JvnCoordImpl(Path stateFile, CoordinatorSnapshot snapshot) throws Exception {
        super();
        this.stateFile = stateFile;
        restoreSnapshot(snapshot);
    }

    public synchronized int jvnGetObjectId() throws java.rmi.RemoteException, JvnException {
        int objectId = nextObjectId++;
        persistState();
        return objectId;
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
        persistState();
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
            Serializable newState;
            try {
                newState = oldWriter.jvnInvalidateWriterForReader(id);
            } catch (RemoteException e) {
                synchronized (this) {
                    removeFailedClient(info, oldWriter);
                    info.readers.add(js);
                    return info.state;
                }
            }
            synchronized (this) {
                info.state = newState;
                info.readers.add(oldWriter);
                info.readers.add(js);
                info.writer = null;
                persistState();
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
                try {
                    Serializable newState = oldWriter.jvnInvalidateWriter(id);
                    synchronized (this) {
                        info.state = newState;
                        persistState();
                    }
                } catch (RemoteException e) {
                    synchronized (this) {
                        removeFailedClient(info, oldWriter);
                    }
                }
            }
            for (JvnRemoteServer reader : currentReaders) {
                if (reader.equals(js)) continue;
                try {
                    reader.jvnInvalidateReader(id);
                } catch (RemoteException e) {
                    synchronized (this) {
                        removeFailedClient(info, reader);
                    }
                }
            }
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

    /**
     * A failed RMI callback means that the client is no longer usable for this
     * object. The coordinator keeps its latest known state and forgets it.
     */
    private void removeFailedClient(ObjectInfo info, JvnRemoteServer client) {
        info.readers.remove(client);
        if (client.equals(info.writer)) info.writer = null;
        System.err.println("JVN: client unreachable for object " + info.objectId);
    }

    private static Path resolveStateFile() {
        String configuredPath = System.getProperty(STATE_FILE_PROPERTY);
        if (configuredPath != null && !configuredPath.isEmpty()) {
            return Paths.get(configuredPath).toAbsolutePath();
        }
        return Paths.get(
                System.getProperty("java.io.tmpdir"),
                "jvn-coordinator-state.bin"
        ).toAbsolutePath();
    }

    private static CoordinatorSnapshot loadSnapshot(Path stateFile) throws IOException {
        if (!Files.exists(stateFile)) {
            return new CoordinatorSnapshot(0, new HashMap<String, PersistedObject>());
        }

        try (ObjectInputStream input = new ObjectInputStream(Files.newInputStream(stateFile))) {
            Object snapshot = input.readObject();
            if (!(snapshot instanceof CoordinatorSnapshot)) {
                throw new IOException("Invalid coordinator state file: " + stateFile);
            }
            return (CoordinatorSnapshot) snapshot;
        } catch (ClassNotFoundException e) {
            throw new IOException("Cannot read coordinator state file: " + stateFile, e);
        }
    }

    private void restoreSnapshot(CoordinatorSnapshot snapshot) {
        nextObjectId = snapshot.nextObjectId;
        for (PersistedObject persisted : snapshot.objects.values()) {
            JvnObject restoredObject = new JvnObjectImpl(
                    persisted.state,
                    persisted.objectId,
                    JvnObjectImpl.NL,
                    null
            );
            ObjectInfo info = new ObjectInfo(
                    persisted.objectId,
                    restoredObject,
                    persisted.state
            );
            // Client RMI references are deliberately not restored after a crash.
            objectsByName.put(persisted.name, info);
            objectsById.put(persisted.objectId, info);
        }
    }

    private void persistState() throws JvnException {
        Map<String, PersistedObject> persistedObjects = new HashMap<>();
        for (Map.Entry<String, ObjectInfo> entry : objectsByName.entrySet()) {
            ObjectInfo info = entry.getValue();
            persistedObjects.put(
                    entry.getKey(),
                    new PersistedObject(entry.getKey(), info.objectId, info.state)
            );
        }

        CoordinatorSnapshot snapshot = new CoordinatorSnapshot(nextObjectId, persistedObjects);
        Path parent = stateFile.getParent();
        Path temporaryFile = null;
        try {
            if (parent != null) Files.createDirectories(parent);
            temporaryFile = Files.createTempFile(parent, "jvn-coordinator-", ".tmp");
            try (ObjectOutputStream output = new ObjectOutputStream(
                    Files.newOutputStream(temporaryFile))) {
                output.writeObject(snapshot);
                output.flush();
            }
            try {
                Files.move(
                        temporaryFile,
                        stateFile,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporaryFile, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new JvnException(
                    "Cannot persist coordinator state: " + e.getMessage()
            );
        } finally {
            if (temporaryFile != null) {
                try {
                    Files.deleteIfExists(temporaryFile);
                } catch (IOException ignored) {
                    // The next successful save will replace the state file.
                }
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
        persistState();
    }
}
