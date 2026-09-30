import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Standalone permission and API-shape probe for Android's private face service. */
public final class FaceServiceProbe {
    public static void main(String[] args) throws Exception {
        System.out.println("caller uid=" + Process.myUid());

        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        Method getService = serviceManager.getDeclaredMethod("getService", String.class);
        IBinder binder = (IBinder) getService.invoke(null, "face");
        if (binder == null) {
            System.out.println("face service unavailable");
            return;
        }

        Class<?> faceService = Class.forName("android.hardware.face.IFaceService");
        Class<?> stub = Class.forName("android.hardware.face.IFaceService$Stub");
        Object remote = stub.getDeclaredMethod("asInterface", IBinder.class).invoke(null, binder);
        Method properties = faceService.getDeclaredMethod("getSensorPropertiesInternal", String.class);
        try {
            Object result = properties.invoke(remote, "android");
            int count = result instanceof List<?> ? ((List<?>) result).size() : -1;
            System.out.println("private face service accessible; sensors=" + count);
            if (args.length > 0 && "inspect".equals(args[0])) {
                for (Object sensor : (List<?>) result) {
                    Field sensorId = sensor.getClass().getField("sensorId");
                    System.out.println("sensorId=" + sensorId.getInt(sensor));
                }
                for (Method method : faceService.getMethods()) {
                    if (method.getName().equals("authenticate")
                            || method.getName().equals("cancelAuthentication")) {
                        System.out.println(method);
                    }
                }
                Class<?> builder = Class.forName("android.hardware.face.FaceAuthenticateOptions$Builder");
                for (Method method : builder.getMethods()) {
                    if (method.getName().startsWith("set") || method.getName().equals("build")) {
                        System.out.println(method);
                    }
                }
                Class<?> receiver = Class.forName("android.hardware.face.IFaceServiceReceiver");
                for (Method method : receiver.getMethods()) {
                    System.out.println(method);
                }
            } else if (args.length > 0 && "auth".equals(args[0])) {
                if (count != 1) {
                    System.out.println("auth requires exactly one face sensor");
                    return;
                }
                int sensorId = resultSensorId((List<?>) result);
                authenticate(remote, faceService, sensorId);
            }
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            System.out.println("private face service denied: " + cause.getClass().getName()
                    + ": " + cause.getMessage());
        }
    }

    private static int resultSensorId(List<?> sensors) throws Exception {
        return sensors.get(0).getClass().getField("sensorId").getInt(sensors.get(0));
    }

    private static void authenticate(Object remote, Class<?> faceService, int sensorId)
            throws Exception {
        Class<?> builderClass = Class.forName("android.hardware.face.FaceAuthenticateOptions$Builder");
        Constructor<?> constructor = builderClass.getConstructor();
        Object builder = constructor.newInstance();
        builderClass.getMethod("setUserId", int.class).invoke(builder, 0);
        builderClass.getMethod("setSensorId", int.class).invoke(builder, sensorId);
        builderClass.getMethod("setOpPackageName", String.class).invoke(builder, "android");
        Object options = builderClass.getMethod("build").invoke(builder);

        Class<?> receiverClass = Class.forName("android.hardware.face.IFaceServiceReceiver");
        Class<?> receiverStub = Class.forName("android.hardware.face.IFaceServiceReceiver$Stub");
        Method transactionName = receiverStub.getMethod("getDefaultTransactionName", int.class);
        CountDownLatch finished = new CountDownLatch(1);
        Binder callbackBinder = new Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws RemoteException {
                try {
                    String name = (String) transactionName.invoke(null, code);
                    if ("onAuthenticationSucceeded".equals(name)) {
                        System.out.println("face authentication succeeded");
                        finished.countDown();
                    } else if ("onAuthenticationFailed".equals(name)) {
                        System.out.println("face authentication failed; waiting for another attempt");
                    } else if ("onError".equals(name)) {
                        System.out.println("face authentication error");
                        finished.countDown();
                    } else if ("onAuthenticationTimeout".equals(name)) {
                        System.out.println("face authentication timeout callback");
                        finished.countDown();
                    }
                    return true;
                } catch (ReflectiveOperationException e) {
                    throw new RemoteException(e.toString());
                }
            }
        };
        Object receiver = Proxy.newProxyInstance(receiverClass.getClassLoader(),
                new Class<?>[]{receiverClass}, (proxy, method, args) -> {
                    if (method.getName().equals("asBinder")) return callbackBinder;
                    throw new UnsupportedOperationException(method.getName());
                });
        IBinder token = new Binder();
        Method method = faceService.getMethod("authenticate", IBinder.class, long.class,
                receiverClass, options.getClass());
        long requestId;
        try {
            requestId = (Long) method.invoke(remote, token, 0L, receiver, options);
        } catch (InvocationTargetException e) {
            System.out.println("face authentication rejected: " + e.getCause());
            return;
        }
        System.out.println("face authentication requested; requestId=" + requestId);
        if (!finished.await(20, TimeUnit.SECONDS)) {
            System.out.println("face authentication timed out; cancelling");
            faceService.getMethod("cancelAuthentication", IBinder.class, String.class, long.class)
                    .invoke(remote, token, "android", requestId);
        }
    }
}
