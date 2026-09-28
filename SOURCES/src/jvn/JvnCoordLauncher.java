package jvn;

import java.rmi.Naming;
import java.rmi.registry.LocateRegistry;


public class JvnCoordLauncher {

    public static void main(String[] args)
            throws Exception {

        LocateRegistry.createRegistry(1099);

        JvnCoordImpl coord =
                JvnCoordImpl.create();

        Naming.rebind("JvnCoord", coord);

        System.out.println("JVN Coordinator ready");
    }
}