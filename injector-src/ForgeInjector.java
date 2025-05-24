import java.io.File;
import java.io.PrintWriter;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import java.util.ArrayList;

public class ForgeInjector extends Thread {
    private byte[][] classes;

    private ForgeInjector(byte[][] classes) {
        this.classes = classes;
    }

    public static void inject(byte[][] classes) {
        new Thread(new ForgeInjector(classes)).start();
    }

    private static Class<?> tryGetClass(PrintWriter writer, ClassLoader cl, String... names) throws ClassNotFoundException {
        ClassNotFoundException lastException = null;
        for (String name : names) {
            try {
                return cl.loadClass(name);
            } catch (ClassNotFoundException e) {
                lastException = e;
            }
        }
        throw lastException;
    }

    // Интерфейс для обертки методов defineClass
    private interface DefineClassInvoker {
        Class<?> defineClass(ClassLoader cl, String name, byte[] bytecode, int offset, int length, ProtectionDomain pd) throws Exception;
    }

    @Override
    public void run() {
        try (PrintWriter writer = new PrintWriter(System.getProperty("user.home") + File.separator + "jar-to-dll-log.txt", "UTF-8")) {
            writer.println("Starting Fabric Injection (Java " + System.getProperty("java.version") + ")!");
            writer.flush();
            try {
                ClassLoader cl = null;
                for (Thread thread : Thread.getAllStackTraces().keySet()) {
                    ClassLoader threadLoader;
                    if (thread == null || thread.getContextClassLoader() == null || (threadLoader = thread.getContextClassLoader()).getClass() == null || 
                        threadLoader.getClass().getName() == null) continue;
                    String loaderName = threadLoader.getClass().getName();
                    writer.println("Thread: " + thread.getName() + " [" + loaderName + "]");
                    writer.flush();
                    // Fabric использует FabricLauncherBase и KnotClassLoader
                    if (!loaderName.contains("KnotClassLoader") && !loaderName.contains("FabricLauncherBase") && 
                        !loaderName.contains("TransformingClassLoader") && !loaderName.contains("AppClassLoader")) continue;
                    cl = threadLoader;
                    break;
                }
                if (cl == null) {
                    throw new Exception("ClassLoader is null");
                }
                this.setContextClassLoader(cl);
                
                // Fabric использует ModInitializer интерфейс вместо аннотаций
                Class<?> modInitializerInterface = null;
                Class<?> clientModInitializerInterface = null;
                Class<?> dedicatedServerModInitializerInterface = null;
                
                try {
                    modInitializerInterface = tryGetClass(writer, cl, "net.fabricmc.api.ModInitializer");
                    writer.println("Found ModInitializer interface");
                } catch (Exception e) {
                    writer.println("ModInitializer interface not found: " + e.getMessage());
                }
                
                try {
                    clientModInitializerInterface = tryGetClass(writer, cl, "net.fabricmc.api.ClientModInitializer");
                    writer.println("Found ClientModInitializer interface");
                } catch (Exception e) {
                    writer.println("ClientModInitializer interface not found: " + e.getMessage());
                }
                
                try {
                    dedicatedServerModInitializerInterface = tryGetClass(writer, cl, "net.fabricmc.api.DedicatedServerModInitializer");
                    writer.println("Found DedicatedServerModInitializer interface");
                } catch (Exception e) {
                    writer.println("DedicatedServerModInitializerInterface not found: " + e.getMessage());
                }

                // Java 17 совместимый способ получения defineClass
                DefineClassInvoker defineClassInvoker = null;
                
                try {
                    // Пытаемся использовать стандартный способ для Java 17+
                    Method loadMethod = ClassLoader.class.getDeclaredMethod("defineClass", String.class, byte[].class, Integer.TYPE, Integer.TYPE, ProtectionDomain.class);
                    loadMethod.setAccessible(true);
                    writer.println("Using standard defineClass method");
                    
                    defineClassInvoker = (classLoader, name, bytecode, offset, length, pd) -> {
                        try {
                            return (Class<?>) loadMethod.invoke(classLoader, name, bytecode, offset, length, pd);
                        } catch (Throwable t) {
                            throw new Exception("Failed to invoke defineClass via reflection", t);
                        }
                    };
                    
                } catch (Exception e) {
                    writer.println("Standard defineClass failed: " + e.getMessage());
                    try {
                        // Альтернативный способ через MethodHandles для Java 17+
                        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(ClassLoader.class, MethodHandles.lookup());
                        MethodHandle methodHandle = lookup.findVirtual(ClassLoader.class, "defineClass", 
                            MethodType.methodType(Class.class, String.class, byte[].class, int.class, int.class, ProtectionDomain.class));
                        writer.println("Using MethodHandles for defineClass");
                        
                        defineClassInvoker = (classLoader, name, bytecode, offset, length, pd) -> {
                            try {
                                return (Class<?>) methodHandle.invoke(classLoader, name, bytecode, offset, length, pd);
                            } catch (Throwable t) {
                                throw new Exception("Failed to invoke defineClass via MethodHandle", t);
                            }
                        };
                        
                    } catch (Exception e2) {
                        writer.println("MethodHandles approach also failed: " + e2.getMessage());
                        throw new Exception("Cannot access defineClass method in Java " + System.getProperty("java.version"), e2);
                    }
                }
                
                writer.println("Loading " + classes.length + " classes");
                writer.flush();
                
                ArrayList<Object[]> mods = new ArrayList<>();
                for (byte[] classData : classes) {
                    if (classData == null) {
                        throw new Exception("classData is null");
                    }
                    if (cl.getClass() == null) {
                        throw new Exception("getClass() is null");
                    }
                    try {
                        Class<?> tClass = null;
                        try {
                            tClass = defineClassInvoker.defineClass(cl, null, classData, 0, classData.length, cl.getClass().getProtectionDomain());
                        } catch (Throwable e) {
                            if (!(e instanceof LinkageError)) {
                                throw e;
                            }

                            if (e.getMessage().contains("duplicate class definition for name: ")) {
                                String className = e.getMessage().split("\"")[1];
                                tClass = cl.loadClass(className.replace('/', '.'));
                                writer.println("It is recommended to remove " + className + ".class from your input.jar");
                            } else {
                                writer.println("LinkageError occurred: " + e.getMessage());
                                throw e;
                            }
                        }
                        
                        if (tClass == null) {
                            writer.println("Warning: tClass is null, skipping");
                            continue;
                        }
                        
                        // Проверяем, реализует ли класс один из интерфейсов Fabric
                        boolean isModInitializer = false;
                        String initializerType = "";
                        
                        if (modInitializerInterface != null && modInitializerInterface.isAssignableFrom(tClass)) {
                            isModInitializer = true;
                            initializerType = "ModInitializer";
                        } else if (clientModInitializerInterface != null && clientModInitializerInterface.isAssignableFrom(tClass)) {
                            isModInitializer = true;
                            initializerType = "ClientModInitializer";
                        } else if (dedicatedServerModInitializerInterface != null && dedicatedServerModInitializerInterface.isAssignableFrom(tClass)) {
                            isModInitializer = true;
                            initializerType = "DedicatedServerModInitializer";
                        }
                        
                        if (!isModInitializer) continue;
                        
                        writer.println("Found " + initializerType + ": " + tClass.getName());
                        
                        Object[] mod = new Object[2];
                        mod[0] = tClass;
                        mod[1] = initializerType;
                        mods.add(mod);
                    }
                    catch (Exception e) {
                        writer.println("Exception during class processing: " + e.getMessage());
                        e.printStackTrace(writer);
                        writer.flush();
                        throw new Exception("Exception on defineClass", e);
                    }
                }
                writer.println(classes.length + " classes processed, " + mods.size() + " mod initializers found");
                writer.flush();
                
                for (Object[] mod : mods) {
                    Class<?> modClass = (Class<?>) mod[0];
                    String initializerType = (String) mod[1];
                    Object modInstance = null;

                    try {
                        writer.println("Instancing " + modClass.getName() + " as " + initializerType);
                        writer.flush();
                        // Используем современный подход вместо устаревшего newInstance()
                        Constructor<?> constructor = modClass.getDeclaredConstructor();
                        constructor.setAccessible(true);
                        modInstance = constructor.newInstance();
                        writer.println("Instanced successfully");
                        writer.flush();
                    }
                    catch (Exception e) {
                        writer.println("Exception on instancing: " + e.getMessage());
                        e.printStackTrace(writer);
                        writer.flush();
                        throw new Exception("Exception on instancing", e);
                    }

                    // Вызываем соответствующий метод инициализации
                    try {
                        Method initMethod = null;
                        if ("ModInitializer".equals(initializerType)) {
                            initMethod = modClass.getMethod("onInitialize");
                        } else if ("ClientModInitializer".equals(initializerType)) {
                            initMethod = modClass.getMethod("onInitializeClient");
                        } else if ("DedicatedServerModInitializer".equals(initializerType)) {
                            initMethod = modClass.getMethod("onInitializeServer");
                        }
                        
                        if (initMethod != null) {
                            writer.println("Initializing " + initMethod.getName() + " for " + initializerType);
                            writer.flush();
                            initMethod.invoke(modInstance);
                            writer.println("Initialized " + initializerType + " successfully");
                            writer.flush();
                        } else {
                            writer.println("Warning: No initialization method found for " + initializerType);
                        }
                    }
                    catch (InvocationTargetException e) {
                        writer.println("InvocationTargetException on initializing: " + e.getMessage());
                        if (e.getCause() != null) {
                            e.getCause().printStackTrace(writer);
                        }
                        writer.flush();
                        throw new Exception("Exception on initializing (InvocationTargetException)", e.getCause());
                    }
                    catch (Exception e) {
                        writer.println("Exception on initializing: " + e.getMessage());
                        e.printStackTrace(writer);
                        writer.flush();
                        throw new Exception("Exception on initializing", e);
                    }
                }
                writer.println("Successfully injected into Fabric!");
                writer.flush();
            }
            catch (Throwable e) {
                writer.println("Fatal error during injection: " + e.getMessage());
                e.printStackTrace(writer);
                writer.flush();
            }
        }
        catch (Throwable e) {
            System.err.println("Failed to create log file: " + e.getMessage());
            e.printStackTrace();
        }
    }
}