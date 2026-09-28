package jvn;

import java.io.Serializable;
import java.net.ResponseCache;

public class JvnObjectImpl implements JvnObject {

    private static final long serialVersionUID = 1L;


    static final int NL = 0;
    static final int RC = 1;
    static final int WC = 2;
    static final int R = 3;
    static final int W = 4;
    static final int RWC = 5;

    private Serializable objectState;
    private int objectId;
    private int lockState;
    private transient JvnLocalServer server;

    public JvnObjectImpl(
                Serializable objectState,
                int objectId,
                int lockState,
            JvnLocalServer server) {

            this.objectState = objectState;
            this.objectId = objectId;
            this.lockState = lockState;
            this.server = server;
        }

        void setServer(JvnLocalServer server) {
        this.server = server;
    }

    @Override 
    public void jvnLockRead()  throws JvnException {
        synchronized(this) {
            if(lockState == RC ){
                lockState = R;
                return; 
            }
            if (lockState == WC) {
                lockState = RWC;
                return; 
            }
            if (lockState != NL) {
                throw new JvnException(
                    "cannot acquire read lock in state " + lockState
                );
            }

        }
        Serializable newState = server.jvnLockRead(objectId);
        synchronized (this){
            objectState = newState;
            lockState = R;
        }

    }

    @Override 
    public void jvnLockWrite() throws JvnException {
        synchronized(this) {
            if (lockState == WC) {
                lockState = W;
                return;  
            }
            if (lockState == RWC) {
                lockState = W;
                return;  
            }
            if (lockState != NL && lockState != RC) {
                throw new JvnException(
                    "cannot acquire read lock in state " + lockState
                );
            }
        }
        Serializable newState = server.jvnLockWrite(objectId);
        synchronized(this){
            objectState = newState;
            lockState = W;
        }

    }

    @Override 
    public synchronized void jvnUnLock() throws JvnException {
        switch (lockState) {
            case R:
                lockState = RC;
                break;
            case W:
                lockState = WC;
                break;
            case RWC:
                lockState = WC;
                break;        
        
            default:
                throw new JvnException(
                    "cannot unlock object in state : " + lockState
                );
        }
        notifyAll();

    }

    @Override 
    public int jvnGetObjectId() throws JvnException{
        return objectId;
    }

    @Override 
    public Serializable jvnGetObjectState() throws JvnException {
        return objectState;

    }

    @Override 
    public synchronized void jvnInvalidateReader()throws JvnException { 
        try {
            while (lockState == R) {
                wait();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JvnException(
                "Interrupted while invalidating reader"
            );

        }
        if (lockState == RC){
            lockState = NL; 
            return;
        }
        if (lockState == NL){ 
            return;
        }
        throw new JvnException(
            "Cannot invalidate reader in state : " +lockState
        );

    }

    @Override 
    public synchronized Serializable jvnInvalidateWriter() throws JvnException{
        try {
            while (lockState == W || lockState == RWC ) {
                wait();
            }
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JvnException(
                "Interrupted while invalidating reader"
            );
        }

        if (lockState == WC) {
            Serializable currentState=objectState;
            lockState = NL;
            return currentState; 
        }
        if (lockState == NL) {
            throw new JvnException(
                "No write look to invalidate"
            );
        }
        throw new JvnException("Cannot invalidate writer in state " + lockState);

    }

    @Override 
    public synchronized Serializable jvnInvalidateWriterForReader() throws JvnException{
        try {
            while (lockState == W || lockState == RWC) {
                wait();
            }
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JvnException(
                "Interrupted while reducing writer to reader"
            );
        }
        if (lockState == WC) {
            Serializable currentState = objectState;
            lockState = RC;
            return currentState;
            
        }
        if (lockState == NL) {
            throw new JvnException(
                "No write lock to reduce to reader"
            );
        }

        throw new JvnException("Cannot invalidate writer in state " + lockState);

    }





    
}
