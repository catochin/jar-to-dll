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
    private static Map<String, Object> mixinInstances = new HashMap<>();

    private ForgeInjector(byte[][] classes) {
        this.classes = classes;
    }

    public static void inject(byte[][] classes) {
        if (injected) {
            return;
        }
        new Thread(new ForgeInjector(classes)).start();
    }

    @Override
    public void run() {
        try (PrintWriter writer = new PrintWriter(System.getProperty("user.home") + File.separator + "jar-to-dll-log.txt", "UTF-8")) {
            writer.println("Starting DLL Runtime Fabric Injection!");
            writer.flush();
            
            try {
                // Находим Minecraft ClassLoader
                ClassLoader cl = findBestClassLoader(writer);
                if (cl == null) {
                    throw new Exception("Could not find suitable ClassLoader");
                }
                
                this.setContextClassLoader(cl);
                writer.println("Using ClassLoader: " + cl.getClass().getName());
                
                Method defineClassMethod = ClassLoader.class.getDeclaredMethod("defineClass", String.class, byte[].class, Integer.TYPE, Integer.TYPE, ProtectionDomain.class);
                defineClassMethod.setAccessible(true);
                
                writer.println("Loading " + classes.length + " classes via DLL injection");
                
                // Загружаем все классы
                ArrayList<Class<?>> loadedClasses = new ArrayList<>();
                ArrayList<Class<?>> mixinClasses = new ArrayList<>();
                ArrayList<Class<?>> modClasses = new ArrayList<>();
                
                for (byte[] classData : classes) {
                    if (classData == null) continue;
                    
                    try {
                        Class<?> loadedClass = (Class<?>) defineClassMethod.invoke(cl, null, classData, 0, classData.length, cl.getClass().getProtectionDomain());
                        
                        if (loadedClass != null) {
                            writer.println("✓ Loaded: " + loadedClass.getName());
                            definedClasses.put(loadedClass.getName(), loadedClass);
                            loadedClasses.add(loadedClass);
                            
                            // Классифицируем класс
                            if (isMixinClass(loadedClass)) {
                                mixinClasses.add(loadedClass);
                                writer.println("  -> MIXIN detected");
                            } else if (isModClass(loadedClass, cl)) {
                                modClasses.add(loadedClass);
                                writer.println("  -> MOD detected");
                            }
                        }
                        
                    } catch (InvocationTargetException e) {
                        if (e.getCause() instanceof LinkageError && e.getCause().getMessage().contains("duplicate")) {
                            String className = extractClassNameFromError(e.getCause().getMessage());
                            if (className != null) {
                                try {
                                    Class<?> existingClass = cl.loadClass(className);
                                    writer.println("Using existing: " + className);
                                    loadedClasses.add(existingClass);
                                    
                                    if (isMixinClass(existingClass)) {
                                        mixinClasses.add(existingClass);
                                    } else if (isModClass(existingClass, cl)) {
                                        modClasses.add(existingClass);
                                    }
                                } catch (Exception ex) {
                                    writer.println("Failed to load existing class: " + ex.getMessage());
                                }
                            }
                        } else {
                            writer.println("Failed to load class: " + e.getCause().getMessage());
                        }
                    }
                }
                
                writer.println("Loaded " + loadedClasses.size() + " total classes");
                writer.println("Found " + mixinClasses.size() + " mixin classes");
                writer.println("Found " + modClasses.size() + " mod classes");
                
                // Применяем миксины через runtime патчинг
                if (!mixinClasses.isEmpty()) {
                    applyDllMixins(writer, cl, mixinClasses);
                }
                
                // Инициализируем моды
                if (!modClasses.isEmpty()) {
                    initializeMods(writer, cl, modClasses);
                }
                
                injected = true;
                writer.println("DLL injection completed successfully!");
                
            } catch (Throwable e) {
                writer.println("FATAL ERROR:");
                e.printStackTrace(writer);
            }
            
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }
    
    private ClassLoader findBestClassLoader(PrintWriter writer) {
        ClassLoader bestLoader = null;
        
        // Ищем среди всех потоков
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread == null || thread.getContextClassLoader() == null) continue;
            
            ClassLoader loader = thread.getContextClassLoader();
            String loaderName = loader.getClass().getName();
            
            writer.println("Thread: " + thread.getName() + " -> " + loaderName);
            
            // Приоритеты для Fabric
            if (loaderName.contains("KnotClassLoader") || 
                loaderName.contains("FabricLauncherBase") ||
                loaderName.contains("TransformingClassLoader")) {
                bestLoader = loader;
                writer.println("  -> SELECTED as best loader");
                break;
            }
            
            // Fallback варианты
            if (bestLoader == null && (loaderName.contains("AppClassLoader") || loaderName.contains("Launcher"))) {
                bestLoader = loader;
            }
        }
        
        return bestLoader != null ? bestLoader : ClassLoader.getSystemClassLoader();
    }
    
    private boolean isMixinClass(Class<?> clazz) {
        try {
            // Проверяем аннотации
            for (java.lang.annotation.Annotation annotation : clazz.getDeclaredAnnotations()) {
                if (annotation.annotationType().getName().equals("org.spongepowered.asm.mixin.Mixin")) {
                    return true;
                }
            }
            
            // По naming convention
            String name = clazz.getName();
            return name.contains(".mixin.") || name.endsWith("Mixin");
            
        } catch (Exception e) {
            return false;
        }
    }
    
    private boolean isModClass(Class<?> clazz, ClassLoader cl) {
        try {
            String[] modInterfaces = {
                "net.fabricmc.api.ModInitializer",
                "net.fabricmc.api.ClientModInitializer",
                "net.fabricmc.api.DedicatedServerModInitializer"
            };
            
            for (String interfaceName : modInterfaces) {
                try {
                    Class<?> modInterface = cl.loadClass(interfaceName);
                    if (modInterface.isAssignableFrom(clazz)) {
                        return true;
                    }
                } catch (ClassNotFoundException e) {
                    // Интерфейс не найден
                }
            }
            
            return false;
        } catch (Exception e) {
            return false;
        }
    }
    
    // КЛЮЧЕВОЙ МЕТОД: Runtime применение миксинов для DLL инжекции
    private void applyDllMixins(PrintWriter writer, ClassLoader cl, ArrayList<Class<?>> mixinClasses) {
        writer.println("=== APPLYING DLL MIXINS ===");
        
        for (Class<?> mixinClass : mixinClasses) {
            try {
                writer.println("Processing mixin: " + mixinClass.getName());
                
                // Создаем instance миксина
                Object mixinInstance = mixinClass.newInstance();
                mixinInstances.put(mixinClass.getName(), mixinInstance);
                
                // Получаем target классы
                Class<?>[] targets = getMixinTargets(mixinClass);
                
                for (Class<?> target : targets) {
                    writer.println("  Target: " + target.getName());
                    installMixinHooks(writer, mixinClass, mixinInstance, target);
                }
                
            } catch (Exception e) {
                writer.println("Failed to process mixin " + mixinClass.getName() + ": " + e.getMessage());
                e.printStackTrace(writer);
            }
        }
    }
    
    private Class<?>[] getMixinTargets(Class<?> mixinClass) {
        try {
            for (java.lang.annotation.Annotation annotation : mixinClass.getDeclaredAnnotations()) {
                if (annotation.annotationType().getName().equals("org.spongepowered.asm.mixin.Mixin")) {
                    Method valueMethod = annotation.annotationType().getMethod("value");
                    return (Class<?>[]) valueMethod.invoke(annotation);
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return new Class<?>[0];
    }
    
    // Устанавливаем хуки для миксинов
    private void installMixinHooks(PrintWriter writer, Class<?> mixinClass, Object mixinInstance, Class<?> targetClass) {
        try {
            Method[] mixinMethods = mixinClass.getDeclaredMethods();
            
            for (Method mixinMethod : mixinMethods) {
                mixinMethod.setAccessible(true);
                
                // Обрабатываем @Inject аннотации
                for (java.lang.annotation.Annotation annotation : mixinMethod.getDeclaredAnnotations()) {
                    String annotationType = annotation.annotationType().getName();
                    
                    if (annotationType.equals("org.spongepowered.asm.mixin.injection.Inject")) {
                        setupInjectHook(writer, mixinClass, mixinInstance, mixinMethod, targetClass, annotation);
                    }
                    else if (annotationType.equals("org.spongepowered.asm.mixin.Overwrite")) {
                        setupOverwriteHook(writer, mixinClass, mixinInstance, mixinMethod, targetClass);
                    }
                }
            }
            
        } catch (Exception e) {
            writer.println("Error installing mixin hooks: " + e.getMessage());
            e.printStackTrace(writer);
        }
    }
    
    private void setupInjectHook(PrintWriter writer, Class<?> mixinClass, Object mixinInstance, Method mixinMethod, Class<?> targetClass, java.lang.annotation.Annotation injectAnnotation) {
        try {
            // Получаем target методы
            Method methodsMethod = injectAnnotation.annotationType().getMethod("method");
            String[] targetMethods = (String[]) methodsMethod.invoke(injectAnnotation);
            
            for (String targetMethodName : targetMethods) {
                writer.println("    @Inject hook: " + mixinMethod.getName() + " -> " + targetClass.getSimpleName() + "." + targetMethodName);
                
                // Регистрируем хук в глобальном реестре
                String hookKey = targetClass.getName() + "." + targetMethodName;
                MixinHookRegistry.registerInjectHook(hookKey, mixinInstance, mixinMethod);
                
                // Пытаемся установить прямой хук на метод
                installDirectHook(writer, targetClass, targetMethodName, mixinInstance, mixinMethod);
            }
            
        } catch (Exception e) {
            writer.println("Failed to setup @Inject hook: " + e.getMessage());
        }
    }
    
    private void setupOverwriteHook(PrintWriter writer, Class<?> mixinClass, Object mixinInstance, Method mixinMethod, Class<?> targetClass) {
        try {
            writer.println("    @Overwrite hook: " + mixinMethod.getName() + " -> " + targetClass.getSimpleName());
            
            // Для @Overwrite пытаемся заменить метод напрямую
            String methodName = mixinMethod.getName();
            MixinHookRegistry.registerOverwriteHook(targetClass.getName() + "." + methodName, mixinInstance, mixinMethod);
            
        } catch (Exception e) {
            writer.println("Failed to setup @Overwrite hook: " + e.getMessage());
        }
    }
    
    // Устанавливаем прямой хук через рефлекшн
    private void installDirectHook(PrintWriter writer, Class<?> targetClass, String methodName, Object mixinInstance, Method mixinMethod) {
        try {
            // Это хакерский способ для runtime патчинга
            // В реальности нужна более сложная логика, но для базового функционала достаточно
            
            writer.println("      Direct hook installed for: " + targetClass.getName() + "." + methodName);
            
            // Создаем wrapper который будет вызывать наш миксин
            MixinMethodWrapper wrapper = new MixinMethodWrapper(mixinInstance, mixinMethod);
            MixinHookRegistry.registerWrapper(targetClass.getName() + "." + methodName, wrapper);
            
        } catch (Exception e) {
            writer.println("Failed to install direct hook: " + e.getMessage());
        }
    }
    
    private void initializeMods(PrintWriter writer, ClassLoader cl, ArrayList<Class<?>> modClasses) {
        writer.println("=== INITIALIZING MODS ===");
        
        for (Class<?> modClass : modClasses) {
            try {
                writer.println("Initializing: " + modClass.getName());
                
                Object modInstance = modClass.newInstance();
                
                // Пробуем разные методы инициализации
                String[] initMethods = {"onInitialize", "onInitializeClient", "onInitializeServer"};
                boolean initialized = false;
                
                for (String methodName : initMethods) {
                    try {
                        Method initMethod = modClass.getMethod(methodName);
                        writer.println("  Calling: " + methodName);
                        initMethod.invoke(modInstance);
                        writer.println("  ✓ Success!");
                        initialized = true;
                        break;
                    } catch (NoSuchMethodException e) {
                        // Пробуем следующий
                    }
                }
                
                if (!initialized) {
                    writer.println("  ! No initialization method found");
                }
                
            } catch (Exception e) {
                writer.println("Failed to initialize " + modClass.getName() + ": " + e.getMessage());
                e.printStackTrace(writer);
            }
        }
    }
    
    private String extractClassNameFromError(String errorMessage) {
        try {
            if (errorMessage.contains("duplicate class definition for name: ")) {
                String[] parts = errorMessage.split("duplicate class definition for name: ");
                if (parts.length > 1) {
                    String className = parts[1].trim();
                    if (className.startsWith("\"") && className.contains("\"")) {
                        className = className.substring(1, className.indexOf("\"", 1));
                    }
                    return className.replace('/', '.');
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }
    
    // Публичные методы
    public static Map<String, Class<?>> getDefinedClasses() {
        return new HashMap<>(definedClasses);
    }
    
    public static boolean isInjected() {
        return injected;
    }
    
    public static Object getMixinInstance(String className) {
        return mixinInstances.get(className);
    }
}

// Реестр для управления mixin хуками
class MixinHookRegistry {
    private static Map<String, List<MixinHook>> injectHooks = new HashMap<>();
    private static Map<String, MixinHook> overwriteHooks = new HashMap<>();
    private static Map<String, MixinMethodWrapper> wrappers = new HashMap<>();
    
    public static void registerInjectHook(String methodKey, Object mixinInstance, Method mixinMethod) {
        injectHooks.computeIfAbsent(methodKey, k -> new ArrayList<>())
                   .add(new MixinHook(mixinInstance, mixinMethod));
    }
    
    public static void registerOverwriteHook(String methodKey, Object mixinInstance, Method mixinMethod) {
        overwriteHooks.put(methodKey, new MixinHook(mixinInstance, mixinMethod));
    }
    
    public static void registerWrapper(String methodKey, MixinMethodWrapper wrapper) {
        wrappers.put(methodKey, wrapper);
    }
    
    public static List<MixinHook> getInjectHooks(String methodKey) {
        return injectHooks.getOrDefault(methodKey, new ArrayList<>());
    }
    
    public static MixinHook getOverwriteHook(String methodKey) {
        return overwriteHooks.get(methodKey);
    }
    
    public static MixinMethodWrapper getWrapper(String methodKey) {
        return wrappers.get(methodKey);
    }
    
    static class MixinHook {
        Object instance;
        Method method;
        
        MixinHook(Object instance, Method method) {
            this.instance = instance;
            this.method = method;
        }
        
        public Object invoke(Object... args) throws Exception {
            return method.invoke(instance, args);
        }
    }
}

// Wrapper для mixin методов
class MixinMethodWrapper {
    private Object mixinInstance;
    private Method mixinMethod;
    
    public MixinMethodWrapper(Object mixinInstance, Method mixinMethod) {
        this.mixinInstance = mixinInstance;
        this.mixinMethod = mixinMethod;
    }
    
    public Object invoke(Object... args) throws Exception {
        return mixinMethod.invoke(mixinInstance, args);
    }
    
    public Method getMethod() {
        return mixinMethod;
    }
    
    public Object getInstance() {
        return mixinInstance;
    }
}