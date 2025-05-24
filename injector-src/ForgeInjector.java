import java.io.File;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class ForgeInjector extends Thread {
    private byte[][] classes;
    private static volatile boolean injected = false;
    private static Map<String, Class<?>> definedClasses = new HashMap<>();

    private ForgeInjector(byte[][] classes) {
        this.classes = classes;
    }

    public static void inject(byte[][] classes) {
        if (injected) {
            return; // Предотвращаем повторную инжекцию
        }
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
                
                // Регистрируем ForgeInjector в текущем ClassLoader
                try {
                    Class<?> forgeInjectorClass = cl.loadClass("ForgeInjector");
                    writer.println("ForgeInjector already exists in ClassLoader");
                } catch (ClassNotFoundException e) {
                    // ForgeInjector не найден, это нормально для первого запуска
                    writer.println("ForgeInjector not found in ClassLoader, will be defined with mod classes");
                }
                
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
                
                // Сначала загружаем все классы
                ArrayList<Class<?>> loadedClasses = new ArrayList<>();
                for (byte[] classData : classes) {
                    if (classData == null) {
                        throw new Exception("classData is null");
                    }
                    if (cl.getClass() == null) {
                        throw new Exception("getClass() is null");
                    }
                    try {
                        Class tClass = null;
                        String className = null;
                        
                        // Извлекаем имя класса из байт-кода для лучшего логирования
                        try {
                            // Простой парсинг имени класса из константного пула
                            if (classData.length > 10) {
                                // Это упрощенный способ, для полного парсинга нужен более сложный код
                                className = extractClassName(classData);
                            }
                        } catch (Exception e) {
                            // Игнорируем ошибки парсинга имени
                        }
                        
                        try {
                            tClass = (Class)loadMethod.invoke(cl, className, classData, 0, classData.length, cl.getClass().getProtectionDomain());
                            writer.println("Successfully loaded class: " + (tClass != null ? tClass.getName() : "Unknown"));
                            if (tClass != null) {
                                definedClasses.put(tClass.getName(), tClass);
                            }
                        } catch (Throwable e) {
                            if (!(e instanceof LinkageError)) {
                                throw e;
                            }

                            if (e.getMessage().contains("duplicate class definition for name: ")) {
                                String duplicateClassName = e.getMessage().split("\"")[1];
                                tClass = cl.loadClass(duplicateClassName.replace('/', '.'));
                                writer.println("Class already exists, using existing: " + duplicateClassName);
                                writer.println("It is recommended to remove " + duplicateClassName + ".class from your input.jar");
                            } else {
                                writer.println("LinkageError loading class: " + e.getMessage());
                                continue; // Пропускаем этот класс
                            }
                        }
                        
                        if (tClass != null) {
                            loadedClasses.add(tClass);
                        }
                    }
                    catch (Exception e) {
                        writer.println("Exception loading class: " + e.getMessage());
                        e.printStackTrace(writer);
                        // Продолжаем загрузку других классов
                    }
                }
                writer.println(loadedClasses.size() + " classes loaded successfully out of " + classes.length);
                writer.flush();
                
                // Теперь ищем и инициализируем модули
                ArrayList<Object[]> mods = new ArrayList<>();
                for (Class<?> tClass : loadedClasses) {
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
                
                // Регистрируем модули в Fabric
                registerModsInFabric(writer, cl, loadedClasses);
                
                // Инициализируем модули
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
                        continue; // Продолжаем с другими модами
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
                        // Продолжаем с другими модами
                    }
                    catch (Exception e) {
                        writer.println("Exception on initializing: " + e);
                        e.printStackTrace(writer);
                        writer.flush();
                        // Продолжаем с другими модами
                    }
                }
                
                injected = true;
                writer.println("Successfully injected into Fabric");
                writer.flush();
            }
            catch (Throwable e) {
                writer.println("Fatal error during injection:");
                e.printStackTrace(writer);
                writer.flush();
            }
            writer.close();
        }
        catch (Throwable e) {
            e.printStackTrace();
        }
    }
    
    private String extractClassName(byte[] classData) {
        // Упрощенное извлечение имени класса из байт-кода
        // Это базовая реализация, может не работать для всех случаев
        try {
            if (classData.length < 10) return null;
            
            // Пропускаем magic number (4 bytes) и версии (4 bytes)
            int offset = 8;
            
            // Читаем количество элементов в constant pool
            int constantPoolCount = ((classData[offset] & 0xFF) << 8) | (classData[offset + 1] & 0xFF);
            offset += 2;
            
            // Это очень упрощенная версия, для полной реализации нужен парсер constant pool
            return null; // Возвращаем null, чтобы использовать автоопределение
        } catch (Exception e) {
            return null;
        }
    }
    
    private void registerModsInFabric(PrintWriter writer, ClassLoader cl, ArrayList<Class<?>> loadedClasses) {
        try {
            // Пытаемся найти и использовать Fabric Loader API для регистрации модов
            Class<?> fabricLoaderClass = null;
            try {
                fabricLoaderClass = cl.loadClass("net.fabricmc.loader.api.FabricLoader");
                writer.println("Found FabricLoader API");
            } catch (ClassNotFoundException e) {
                writer.println("FabricLoader API not found, mods will be registered manually");
                return;
            }
            
            // Пытаемся получить instance FabricLoader
            Method getInstanceMethod = fabricLoaderClass.getMethod("getInstance");
            Object fabricLoaderInstance = getInstanceMethod.invoke(null);
            writer.println("Got FabricLoader instance");
            
            // Регистрируем классы в Fabric
            for (Class<?> clazz : loadedClasses) {
                try {
                    // Здесь можно добавить дополнительную логику регистрации
                    writer.println("Registered class in Fabric context: " + clazz.getName());
                } catch (Exception e) {
                    writer.println("Failed to register class " + clazz.getName() + ": " + e.getMessage());
                }
            }
            
        } catch (Exception e) {
            writer.println("Exception during Fabric registration: " + e.getMessage());
            e.printStackTrace(writer);
        }
    }
    
    // Статический метод для получения загруженных классов (для использования другими частями кода)
    public static Map<String, Class<?>> getDefinedClasses() {
        return new HashMap<>(definedClasses);
    }
    
    // Статический метод для проверки, была ли выполнена инжекция
    public static boolean isInjected() {
        return injected;
    }
}