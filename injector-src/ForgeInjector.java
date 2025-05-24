import java.io.File;
import java.io.PrintWriter;
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

    private static Class tryGetClass(PrintWriter writer, ClassLoader cl, String... names) throws ClassNotFoundException {
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

    @Override
    public void run() {
        try (PrintWriter writer = new PrintWriter(System.getProperty("user.home") + File.separator + "jar-to-dll-log.txt", "UTF-8")) {
            writer.println("Starting Fabric Injection!");
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
                Class modInitializerInterface = null;
                Class clientModInitializerInterface = null;
                Class dedicatedServerModInitializerInterface = null;
                
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
                    writer.println("DedicatedServerModInitializer interface not found: " + e.getMessage());
                }

                Method loadMethod = ClassLoader.class.getDeclaredMethod("defineClass", String.class, byte[].class, Integer.TYPE, Integer.TYPE, ProtectionDomain.class);
                loadMethod.setAccessible(true);
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
                        Class tClass = null;
                        try {
                            tClass = (Class)loadMethod.invoke(cl, null, classData, 0, classData.length, cl.getClass().getProtectionDomain());
                        } catch (Throwable e) {
                            if (!(e instanceof LinkageError)) {
                                throw e;
                            }

                            if (e.getMessage().contains("duplicate class definition for name: ")) {
                                String className = e.getMessage().split("\"")[1];
                                tClass = cl.loadClass(className.replace('/', '.'));
                                writer.println("It is recommended to remove " + className + ".class from your input.jar");
                            }
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
                        e.printStackTrace();
                        throw new Exception("Exception on defineClass", e);
                    }
                }
                writer.println(classes.length + " loaded successfully");
                writer.flush();
                
                for (Object[] mod : mods) {
                    Class modClass = (Class) mod[0];
                    String initializerType = (String) mod[1];
                    Object modInstance = null;

                    try {
                        writer.println("Instancing " + modClass.getName() + " as " + initializerType);
                        writer.flush();
                        modInstance = modClass.newInstance();
                        writer.println("Instanced");
                        writer.flush();
                    }
                    catch (Exception e) {
                        writer.println("Exception on instancing: " + e);
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
                            writer.println("Initializing " + initMethod + " for " + initializerType);
                            writer.flush();
                            initMethod.invoke(modInstance);
                            writer.println("Initialized " + initializerType);
                            writer.flush();
                        }
                    }
                    catch (InvocationTargetException e) {
                        writer.println("InvocationTargetException on initializing: " + e);
                        e.getCause().printStackTrace(writer);
                        writer.flush();
                        throw new Exception("Exception on initializing (InvocationTargetException)", e.getCause());
                    }
                    catch (Exception e) {
                        writer.println("Exception on initializing: " + e);
                        e.printStackTrace(writer);
                        writer.flush();
                        throw new Exception("Exception on initializing", e);
                    }
                }
                writer.println("Successfully injected into Fabric");
                writer.flush();
            }
            catch (Throwable e) {
                e.printStackTrace(writer);
                writer.flush();
            }
            writer.close();
        }
        catch (Throwable e) {
            e.printStackTrace();
        }
    }
}
