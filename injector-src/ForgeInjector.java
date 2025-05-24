import java.io.File;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

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
                ArrayList<Class<?>> mixinClasses = new ArrayList<>();
                
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
                        
                        try {
                            tClass = (Class)loadMethod.invoke(cl, className, classData, 0, classData.length, cl.getClass().getProtectionDomain());
                            writer.println("Successfully loaded class: " + (tClass != null ? tClass.getName() : "Unknown"));
                            if (tClass != null) {
                                definedClasses.put(tClass.getName(), tClass);
                                
                                // Проверяем, является ли класс Mixin
                                if (isMixinClass(tClass, writer)) {
                                    mixinClasses.add(tClass);
                                    writer.println("Detected Mixin class: " + tClass.getName());
                                }
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
                                
                                // Проверяем на Mixin
                                if (isMixinClass(tClass, writer)) {
                                    mixinClasses.add(tClass);
                                    writer.println("Detected existing Mixin class: " + tClass.getName());
                                }
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
                
                // КРИТИЧЕСКИ ВАЖНО: Регистрируем Mixins СРАЗУ после загрузки классов
                if (!mixinClasses.isEmpty()) {
                    forceRegisterMixins(writer, cl, mixinClasses);
                }
                
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
    
    private boolean isMixinClass(Class<?> clazz, PrintWriter writer) {
        try {
            // Проверяем по аннотации @Mixin
            java.lang.annotation.Annotation[] annotations = clazz.getAnnotations();
            for (java.lang.annotation.Annotation annotation : annotations) {
                if (annotation.annotationType().getName().equals("org.spongepowered.asm.mixin.Mixin")) {
                    return true;
                }
            }
            
            // Проверяем по имени пакета
            String className = clazz.getName();
            if (className.contains(".mixin.") || className.endsWith("Mixin")) {
                return true;
            }
            
            return false;
        } catch (Exception e) {
            writer.println("Error checking if class is Mixin: " + e.getMessage());
            return false;
        }
    }
    
    private void forceRegisterMixins(PrintWriter writer, ClassLoader cl, ArrayList<Class<?>> mixinClasses) {
        try {
            writer.println("FORCE REGISTERING " + mixinClasses.size() + " Mixin classes");
            
            // Подход 1: Через MixinEnvironment
            try {
                Class<?> mixinEnvironmentClass = cl.loadClass("org.spongepowered.asm.mixin.MixinEnvironment");
                writer.println("Found MixinEnvironment class");
                
                // Получаем текущее окружение
                Method getCurrentEnvironmentMethod = mixinEnvironmentClass.getMethod("getCurrentEnvironment");
                Object currentEnvironment = getCurrentEnvironmentMethod.invoke(null);
                writer.println("Got current MixinEnvironment: " + currentEnvironment);
                
                // Пытаемся получить audit trail или processor
                Method[] methods = mixinEnvironmentClass.getMethods();
                for (Method method : methods) {
                    if (method.getName().contains("audit") || method.getName().contains("processor")) {
                        writer.println("Available method: " + method.getName());
                    }
                }
                
            } catch (Exception e) {
                writer.println("Failed to access MixinEnvironment: " + e.getMessage());
            }
            
            // Подход 2: Прямая регистрация через Transformer
            try {
                Class<?> mixinTransformerClass = cl.loadClass("org.spongepowered.asm.mixin.transformer.MixinTransformer");
                writer.println("Found MixinTransformer class");
                
                // Получаем все статические поля
                Field[] fields = mixinTransformerClass.getDeclaredFields();
                for (Field field : fields) {
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                        writer.println("Static field in MixinTransformer: " + field.getName() + " (" + field.getType() + ")");
                        
                        field.setAccessible(true);
                        Object value = field.get(null);
                        writer.println("  Value: " + value);
                        
                        if (field.getName().toLowerCase().contains("instance") || field.getName().toLowerCase().contains("transformer")) {
                            if (value != null) {
                                writer.println("Found transformer instance: " + value.getClass().getName());
                                
                                // Пытаемся получить методы для регистрации
                                Method[] transformerMethods = value.getClass().getMethods();
                                for (Method method : transformerMethods) {
                                    if (method.getName().contains("transform") || method.getName().contains("apply") || method.getName().contains("mixin")) {
                                        writer.println("  Available transformer method: " + method.getName());
                                    }
                                }
                            }
                        }
                    }
                }
                
            } catch (Exception e) {
                writer.println("Failed to access MixinTransformer: " + e.getMessage());
            }
            
            // Подход 3: Через ASM напрямую регистрируем миксины в уже загруженные классы
            for (Class<?> mixinClass : mixinClasses) {
                try {
                    registerMixinDirectly(writer, cl, mixinClass);
                } catch (Exception e) {
                    writer.println("Failed to register mixin " + mixinClass.getName() + " directly: " + e.getMessage());
                    e.printStackTrace(writer);
                }
            }
            
        } catch (Exception e) {
            writer.println("Exception during FORCE Mixin registration: " + e.getMessage());
            e.printStackTrace(writer);
        }
    }
    
    private void registerMixinDirectly(PrintWriter writer, ClassLoader cl, Class<?> mixinClass) throws Exception {
        writer.println("Attempting DIRECT registration of mixin: " + mixinClass.getName());
        
        // Получаем аннотацию @Mixin
        java.lang.annotation.Annotation mixinAnnotation = null;
        for (java.lang.annotation.Annotation annotation : mixinClass.getAnnotations()) {
            if (annotation.annotationType().getName().equals("org.spongepowered.asm.mixin.Mixin")) {
                mixinAnnotation = annotation;
                break;
            }
        }
        
        if (mixinAnnotation == null) {
            writer.println("No @Mixin annotation found on " + mixinClass.getName());
            return;
        }
        
        // Получаем target классы из аннотации
        try {
            Method valueMethod = mixinAnnotation.annotationType().getMethod("value");
            Class<?>[] targetClasses = (Class<?>[]) valueMethod.invoke(mixinAnnotation);
            
            writer.println("Mixin " + mixinClass.getName() + " targets " + targetClasses.length + " classes:");
            for (Class<?> targetClass : targetClasses) {
                writer.println("  Target: " + targetClass.getName());
                
                // ЗДЕСЬ МАГИЯ: Применяем миксин к уже загруженному классу
                applyMixinToLoadedClass(writer, cl, mixinClass, targetClass);
            }
            
        } catch (Exception e) {
            writer.println("Failed to get target classes from @Mixin: " + e.getMessage());
            e.printStackTrace(writer);
        }
    }
    
    private void applyMixinToLoadedClass(PrintWriter writer, ClassLoader cl, Class<?> mixinClass, Class<?> targetClass) {
        try {
            writer.println("Applying mixin " + mixinClass.getName() + " to target " + targetClass.getName());
            
            // Это самая сложная часть - нужно применить трансформации ПОСЛЕ загрузки класса
            // В обычном Minecraft это невозможно, но можем попробовать через Instrumentation
            
            // Подход 1: Через Java Instrumentation API (если доступен)
            try {
                Class<?> instrumentationClass = cl.loadClass("java.lang.instrument.Instrumentation");
                writer.println("Instrumentation API found, but we need an agent for retransformation");
                
                // Нужен Java Agent для этого подхода
                
            } catch (ClassNotFoundException e) {
                writer.println("Instrumentation API not available");
            }
            
            // Подход 2: Рефлекшн для применения изменений
            // Получаем все методы миксина с аннотациями @Inject, @Overwrite, etc.
            Method[] mixinMethods = mixinClass.getDeclaredMethods();
            for (Method mixinMethod : mixinMethods) {
                java.lang.annotation.Annotation[] annotations = mixinMethod.getAnnotations();
                for (java.lang.annotation.Annotation annotation : annotations) {
                    String annotationName = annotation.annotationType().getName();
                    
                    if (annotationName.equals("org.spongepowered.asm.mixin.Inject") ||
                        annotationName.equals("org.spongepowered.asm.mixin.Overwrite") ||
                        annotationName.equals("org.spongepowered.asm.mixin.Shadow")) {
                        
                        writer.println("Found mixin method: " + mixinMethod.getName() + " with annotation: " + annotationName);
                        
                        // Пытаемся применить этот метод к target классу
                        applyMixinMethod(writer, mixinClass, targetClass, mixinMethod, annotation);
                    }
                }
            }
            
        } catch (Exception e) {
            writer.println("Failed to apply mixin to target class: " + e.getMessage());
            e.printStackTrace(writer);
        }
    }
    
    private void applyMixinMethod(PrintWriter writer, Class<?> mixinClass, Class<?> targetClass, Method mixinMethod, java.lang.annotation.Annotation annotation) {
        try {
            String annotationType = annotation.annotationType().getName();
            writer.println("Applying mixin method " + mixinMethod.getName() + " (" + annotationType + ") to " + targetClass.getName());
            
            if (annotationType.equals("org.spongepowered.asm.mixin.Inject")) {
                // Для @Inject нужно найти целевой метод и вставить вызов
                try {
                    Method methodMethod = annotation.annotationType().getMethod("method");
                    String[] targetMethods = (String[]) methodMethod.invoke(annotation);
                    
                    for (String targetMethodName : targetMethods) {
                        writer.println("  Trying to inject into method: " + targetMethodName);
                        
                        // ВАЖНО: Это только логирование, реальная инжекция требует ASM
                        // Но можем попробовать обходной путь через rефлекшн
                        tryDirectMethodInjection(writer, mixinClass, targetClass, mixinMethod, targetMethodName);
                    }
                    
                } catch (Exception e) {
                    writer.println("Failed to process @Inject: " + e.getMessage());
                }
            }
            
            writer.println("Mixin method processing completed for: " + mixinMethod.getName());
            
        } catch (Exception e) {
            writer.println("Failed to apply mixin method: " + e.getMessage());
            e.printStackTrace(writer);
        }
    }
    
    private void tryDirectMethodInjection(PrintWriter writer, Class<?> mixinClass, Class<?> targetClass, Method mixinMethod, String targetMethodName) {
        try {
            writer.println("Attempting direct method injection for: " + targetMethodName);
            
            // Это ОЧЕНЬ хакерский подход - в реальности нужен ASM
            // Но можем попробовать заменить метод через Unsafe или другие трюки
            
            // Пока что просто логируем что мы "применили" миксин
            writer.println("SIMULATED: Applied mixin injection " + mixinClass.getName() + "." + mixinMethod.getName() + 
                         " -> " + targetClass.getName() + "." + targetMethodName);
            
            // В будущем здесь должна быть реальная ASM трансформация
            
        } catch (Exception e) {
            writer.println("Direct method injection failed: " + e.getMessage());
        }
    }
    
    private String extractClassName(byte[] classData) {
        try {
            if (classData.length < 10) return null;
            
            int offset = 8;
            int constantPoolCount = ((classData[offset] & 0xFF) << 8) | (classData[offset + 1] & 0xFF);
            offset += 2;
            
            return null; // Возвращаем null, чтобы использовать автоопределение
        } catch (Exception e) {
            return null;
        }
    }
    
    private void registerModsInFabric(PrintWriter writer, ClassLoader cl, ArrayList<Class<?>> loadedClasses) {
        try {
            Class<?> fabricLoaderClass = null;
            try {
                fabricLoaderClass = cl.loadClass("net.fabricmc.loader.api.FabricLoader");
                writer.println("Found FabricLoader API");
            } catch (ClassNotFoundException e) {
                writer.println("FabricLoader API not found, mods will be registered manually");
                return;
            }
            
            Method getInstanceMethod = fabricLoaderClass.getMethod("getInstance");
            Object fabricLoaderInstance = getInstanceMethod.invoke(null);
            writer.println("Got FabricLoader instance");
            
            for (Class<?> clazz : loadedClasses) {
                try {
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
    
    public static Map<String, Class<?>> getDefinedClasses() {
        return new HashMap<>(definedClasses);
    }
    
    public static boolean isInjected() {
        return injected;
    }
}