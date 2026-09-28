/***
 * JAVANAISE Implementation
 * JvnServerImpl class
 * Contact:  
 *
 * Authors: 
 */

package jvn;

import java.rmi.server.UnicastRemoteObject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.io.Serializable;


public class JvnCoordImpl 	
              extends UnicastRemoteObject 
							implements JvnRemoteCoord{
	

  /**
	 * 
	 */
	private static final long serialVersionUID = 1L;
  private int nextObjectId = 0;

  private Map<String, ObjectInfo> objectsByName = new HashMap<>();
  private Map<Integer, ObjectInfo> objectsById = new HashMap<>();
  private static  class ObjectInfo {
    int objectId;
    JvnObject object;
    Serializable state;
    JvnRemoteServer writer;
    Set<JvnRemoteServer> readers = new HashSet<>();

    ObjectInfo(
      int objectId,
      JvnObject object,
      Serializable state
    ) {
      this.objectId = objectId;
      this.object = object;
      this.state = state;
    } 
  
    
  }
  public static JvnCoordImpl create()
        throws Exception {

    return new JvnCoordImpl();
}

/**
  * Default constructor
  * @throws JvnException
  **/
	private JvnCoordImpl() throws Exception {
		super();
	}

  /**
  *  Allocate a NEW JVN object id (usually allocated to a 
  *  newly created JVN object)
  * @throws java.rmi.RemoteException,JvnException
  **/
  public synchronized int jvnGetObjectId()
  throws java.rmi.RemoteException,jvn.JvnException {
    return nextObjectId++;
  }
  
  /**
  * Associate a symbolic name with a JVN object
  * @param jon : the JVN object name
  * @param jo  : the JVN object 
  * @param joi : the JVN object identification
  * @param js  : the remote reference of the JVNServer
  * @throws java.rmi.RemoteException,JvnException
  **/
  public void jvnRegisterObject(String jon, JvnObject jo, JvnRemoteServer js)
  throws java.rmi.RemoteException,jvn.JvnException{
  
    if(jon == null || jo == null || js == null){
      throw new JvnException( "cannot register a null object, name or server");
    }

    if (objectsByName.containsKey(jon)) {
      throw new JvnException("an object named" + jon + "already exists");
      
    }

    int objectId = jo.jvnGetObjectId();
    Serializable state = jo.jvnGetObjectState();
    ObjectInfo info = new ObjectInfo(objectId, jo, state);
    info.writer = js;
    objectsByName.put(jon, info);
    objectsById.put(objectId, info);
  }
  
  /**
  * Get the reference of a JVN object managed by a given JVN server 
  * @param jon : the JVN object name
  * @param js : the remote reference of the JVNServer
  * @throws java.rmi.RemoteException,JvnException
  **/
  public synchronized JvnObject jvnLookupObject(String jon, JvnRemoteServer js)
  throws java.rmi.RemoteException,jvn.JvnException{
    if(jon == null){
      throw new JvnException("Object name cannot be null");
    };
    ObjectInfo info = objectsByName.get(jon);

    if (info == null) {
        return null;
    }

    return new JvnObjectImpl(
        info.state,
        info.objectId,
        JvnObjectImpl.NL,
        null
      );
  }
  
  /**
  * Get a Read lock on a JVN object managed by a given JVN server 
  * @param joi : the JVN object identification
  * @param js  : the remote reference of the server
  * @return the current JVN object state
  * @throws java.rmi.RemoteException, JvnException
  **/
   public synchronized Serializable jvnLockRead(int joi, JvnRemoteServer js)
   throws java.rmi.RemoteException, JvnException{
    ObjectInfo info = objectsById.get(joi);
    if(info == null) {
      throw new JvnException("Unknown object id :" + joi);
    }
    // Aucun écrivain : plusieurs lecteurs sont autorisés
    if(info.writer == null) {
      info.readers.add(js);
      return info.state;
    }
    // Un écrivain existe : il doit devenir lecteur
    JvnRemoteServer oldWriter = info.writer;

    Serializable newState = oldWriter.jvnInvalidateWriterForReader(joi);
    info.state = newState;
     // L'ancien écrivain devient lecteur
    info.readers.add(oldWriter);
     // Le nouveau client devient aussi lecteur
    info.readers.add(js);
    // Il n'y a plus d'écrivain
    info.writer = null;
    return info.state;
   }

  /**
  * Get a Write lock on a JVN object managed by a given JVN server 
  * @param joi : the JVN object identification
  * @param js  : the remote reference of the server
  * @return the current JVN object state
  * @throws java.rmi.RemoteException, JvnException
  **/
   public synchronized Serializable jvnLockWrite(int joi, JvnRemoteServer js)
   throws java.rmi.RemoteException, JvnException{
    ObjectInfo info = objectsById.get(joi);
    if(info == null) {
      throw new JvnException("Unknown object id :" + joi);
    }

    if (js == null) {
      throw new JvnException("The server cannot be null");
      
    }

    if(info.writer != null && !info.writer.equals(js)) {
      Serializable newState = info.writer.jvnInvalidateWriter(joi);
      info.state = newState;
      info.writer=null;
    }

    Set<JvnRemoteServer> currentReaders = new HashSet<>(info.readers);
    for(JvnRemoteServer reader : currentReaders){
      if(!reader.equals(js)){
        reader.jvnInvalidateReader(joi);
      }
    }
    info.readers.clear();
    info.writer = js;

    return info.state;

   }

	/**
	* A JVN server terminates
	* @param js  : the remote reference of the server
	* @throws java.rmi.RemoteException, JvnException
	**/
    public void jvnTerminate(JvnRemoteServer js)
	 throws java.rmi.RemoteException, JvnException {

    if (js == null) {
      return;
    }
    for (ObjectInfo info : objectsById.values()) {
      if(info.writer != null && info.writer.equals(js)) {
        Serializable newState = js.jvnInvalidateWriter(info.objectId);
        info.state=newState;
        info.writer=null;
      }
      info.readers.remove(js);
    }
    
  }
}

 
