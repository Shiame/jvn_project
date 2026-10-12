package burst;

import java.util.concurrent.ThreadLocalRandom;

import irc.Sentence;
import jvn.JvnException;
import jvn.JvnObject;
import jvn.JvnServerImpl;

public class BurstClient {
    public static void main(String[] args) {
        if(args.length < 2 ){
            System.out.println(
                "Usage: java burst.BurstClient <clientId> <iterations>"
            );
            return;
        }
        int clientId = Integer.parseInt(args[0]);
        int iterations = Integer.parseInt(args[1]); //le nombre d'operations pour le client n idclient

        int reads = 0;
        int writes = 0;
        int errors = 0;

        long start = System.currentTimeMillis(); //chrono pour calculer combien de temps le tesy a duré

       try {
            JvnServerImpl server = 
                JvnServerImpl.jvnGetServer();

            if ( server == null ) {
                throw new Exception(
                    "Unable to initialize JVN server"
                );
            }

            JvnObject object = server.jvnLookupObject("IRC");
            if ( object == null ) {
                throw new Exception(
                    "Obkect IRC does not exist"
                );
            }

            for(int i=0; i < iterations; i++){

                boolean lockAcquired = false;
                boolean writeOperation = (i%2==1);
                try {
                    if(writeOperation){
                        object.jvnLockWrite();
                        lockAcquired = true;

                        Sentence sentence = (Sentence) object.jvnGetObjectState();
                        sentence.write("client: " + clientId + "-opreation-" +i);

                        writes++;
                    }
                    else{
                        object.jvnLockRead();
                        lockAcquired = true;

                        Sentence sentence = (Sentence) object.jvnGetObjectState();

                        reads++;
                    }
                } catch (JvnException e) {
                    errors++;
                    System.err.println(
                         "Client " + clientId
                            + ", operation " + i
                            + " failed: "
                            + e.getMessage()
                    );               
                } finally {
                    if(lockAcquired){
                        try {
                            object.jvnUnLock();
                        } catch (JvnException e) {
                            errors++;
                            System.err.println(
                                "Unlock failed for client "
                                + clientId
                                + ": "
                                + e.getMessage()
                            );
                        }
                    }
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
               "Burst client " + clientId
                + " stopped: "
                + e.getMessage()
            );
            return; 
       }

       long duration = System.currentTimeMillis() - start;
       
       System.out.println(
            "Client " + clientId
            + " finished: "
            +" reads= " +reads
            +", writes= " +writes
            +", errors= " +errors
            +", duration= " + duration
       );

    }
    
}
