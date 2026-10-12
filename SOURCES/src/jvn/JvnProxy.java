package jvn;

import java.io.Serializable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public class JvnProxy implements InvocationHandler {

    private final JvnObject object;

    private JvnProxy(JvnObject object) {
        this.object = object;
    }

    public static Object newInstance(String name, Serializable initialState)
            throws JvnException {

        if (name == null || initialState == null) {
            throw new JvnException("Object name and initial state are required");
        }

        Class<?>[] interfaces = initialState.getClass().getInterfaces();
        if (interfaces.length == 0) {
            throw new JvnException(
                    "Shared object must implement at least one interface"
            );
        }

        JvnServerImpl server = JvnServerImpl.jvnGetServer();
        if (server == null) {
            throw new JvnException("Cannot initialize JVN server");
        }

        JvnObject jo = server.jvnLookupObject(name);
        if (jo == null) {
            jo = server.jvnCreateObject(initialState);
            jo.jvnUnLock();
            server.jvnRegisterObject(name, jo);
        }

        return Proxy.newProxyInstance(
                initialState.getClass().getClassLoader(),
                interfaces,
                new JvnProxy(jo)
        );
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args)
            throws Throwable {

        if (method.getDeclaringClass() == Object.class) {
            return invokeObjectMethod(proxy, method, args);
        }

        boolean write = method.isAnnotationPresent(JvnWriteMethod.class);
        boolean read = method.isAnnotationPresent(JvnReadMethod.class);

        if (!read && !write) {
            throw new JvnException(
                    "Method " + method.getName()
                    + " must be annotated with @JvnReadMethod or @JvnWriteMethod"
            );
        }

        if (read && write) {
            throw new JvnException(
                    "Method " + method.getName()
                    + " cannot be both read and write"
            );
        }

        if (write) {
            object.jvnLockWrite();
        } else {
            object.jvnLockRead();
        }

        try {
            Object state = object.jvnGetObjectState();
            return method.invoke(state, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        } finally {
            object.jvnUnLock();
        }
    }

    private Object invokeObjectMethod(Object proxy, Method method, Object[] args) {
        String name = method.getName();

        if ("toString".equals(name)) {
            return "JvnProxy(" + object + ")";
        }
        if ("hashCode".equals(name)) {
            return System.identityHashCode(proxy);
        }
        if ("equals".equals(name)) {
            return proxy == args[0];
        }

        throw new UnsupportedOperationException(name);
    }
}
