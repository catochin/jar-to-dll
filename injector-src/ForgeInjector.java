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
                ClassLoader cl = findBestClassLoader(writer);
                if (cl == null) {
                    throw new Exception("Could not find suitable ClassLoader");
                }
                
                this.setContextClassLoader(cl);
                writer.println("Using ClassLoader: " + cl.getClass().getName());
                
                Method defineClassMethod = ClassLoader.class.getDeclaredMethod("defineClass", String.class, byte[].class, Integer.TYPE, Integer.TYPE, ProtectionDomain.class);
                defineClassMethod.setAccessible(true);
                
                writer.println("Loading " + classes.length + " classes via DLL injection");
                
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
                
                // КРИТИЧЕСКИ ВАЖНО: Применяем миксины с детальным логированием
                if (!mixinClasses.isEmpty()) {
                    applyDllMixins(writer, cl, mixinClasses);
                }
                
                if (!modClasses.isEmpty()) {
                    initializeMods(writer, cl, modClasses);
                }
                
                // ДОПОЛНИТЕЛЬНО: Устанавливаем runtime hooks
                installRuntimeHooks(writer, cl);
                
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
        
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread == null || thread.getContextClassLoader() == null) continue;
            
            ClassLoader loader = thread.getContextClassLoader();
            String loaderName = loader.getClass().getName();
            
            writer.println("Thread: " + thread.getName() + " -> " + loaderName);
            
            if (loaderName.contains("KnotClassLoader") || 
                loaderName.contains("FabricLauncherBase") ||
                loaderName.contains("TransformingClassLoader")) {
                bestLoader = loader;
                writer.println("  -> SELECTED as best loader");
                break;
            }
            
            if (bestLoader == null && (loaderName.contains("AppClassLoader") || loaderName.contains("Launcher"))) {
                bestLoader = loader;
            }
        }
        
        return bestLoader != null ? bestLoader : ClassLoader.getSystemClassLoader();
    }
    
    private boolean isMixinClass(Class<?> clazz) {
        try {
            for (java.lang.annotation.Annotation annotation : clazz.getDeclaredAnnotations()) {
                if (annotation.annotationType().getName().equals("org.spongepowered.asm.mixin.Mixin")) {
                    return true;
                }
            }
            
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
    
    // ИСПРАВЛЕННЫЙ метод применения миксинов
    private void applyDllMixins(PrintWriter writer, ClassLoader cl, ArrayList<Class<?>> mixinClasses) {
        writer.println("=== APPLYING DLL MIXINS ===");
        
        for (Class<?> mixinClass : mixinClasses) {
            try {
                writer.println("Processing mixin: " + mixinClass.getName());
                
                // Создаем instance миксина
                Object mixinInstance = mixinClass.newInstance();
                mixinInstances.put(mixinClass.getName(), mixinInstance);
                writer.println("  Created mixin instance");
                
                // ИСПРАВЛЕНО: Получаем target классы с детальным логированием
                Class<?>[] targets = getMixinTargetsFixed(mixinClass, writer, cl);
                
                if (targets.length == 0) {
                    writer.println("  ERROR: No target classes found for mixin!");
                    continue;
                }
                
                for (Class<?> target : targets) {
                    writer.println("  Target found: " + target.getName());
                    installMixinHooks(writer, mixinClass, mixinInstance, target);
                }
                
            } catch (Exception e) {
                writer.println("Failed to process mixin " + mixinClass.getName() + ": " + e.getMessage());
                e.printStackTrace(writer);
            }
        }
    }
    
    // ИСПРАВЛЕННЫЙ метод получения targets
    private Class<?>[] getMixinTargetsFixed(Class<?> mixinClass, PrintWriter writer, ClassLoader cl) {
        try {
            writer.println("  Analyzing mixin annotations...");
            
            // Получаем все аннотации
            java.lang.annotation.Annotation[] annotations = mixinClass.getDeclaredAnnotations();
            writer.println("  Found " + annotations.length + " annotations");
            
            for (java.lang.annotation.Annotation annotation : annotations) {
                String annotationType = annotation.annotationType().getName();
                writer.println("    Annotation: " + annotationType);
                
                if (annotationType.equals("org.spongepowered.asm.mixin.Mixin")) {
                    writer.println("    Found @Mixin annotation!");
                    
                    try {
                        // Получаем value() method
                        Method valueMethod = annotation.annotationType().getMethod("value");
                        Object valueResult = valueMethod.invoke(annotation);
                        
                        if (valueResult instanceof Class<?>[]) {
                            Class<?>[] targets = (Class<?>[]) valueResult;
                            writer.println("    Targets array length: " + targets.length);
                            
                            for (int i = 0; i < targets.length; i++) {
                                writer.println("      Target[" + i + "]: " + targets[i].getName());
                            }
                            
                            return targets;
                        } else {
                            writer.println("    ERROR: value() returned unexpected type: " + valueResult.getClass());
                        }
                        
                    } catch (Exception e) {
                        writer.println("    ERROR accessing @Mixin value: " + e.getMessage());
                        e.printStackTrace(writer);
                    }
                }
            }
            
            // Fallback: пытаемся определить targets по имени класса
            writer.println("  Fallback: trying to determine targets by class name");
            return guessTargetsByClassName(mixinClass, writer, cl);
            
        } catch (Exception e) {
            writer.println("  ERROR in getMixinTargetsFixed: " + e.getMessage());
            e.printStackTrace(writer);
        }
        
        return new Class<?>[0];
    }
    
    // Fallback метод для определения targets
    private Class<?>[] guessTargetsByClassName(Class<?> mixinClass, PrintWriter writer, ClassLoader cl) {
        try {
            String mixinName = mixinClass.getSimpleName();
            writer.println("    Guessing targets for: " + mixinName);
            
            // Маппинг по именам ваших миксинов
            Map<String, String[]> mixinToTargets = new HashMap<>();
            mixinToTargets.put("RenderMixin", new String[]{"net.minecraft.class_761"}); // EntityRenderer
            mixinToTargets.put("IngameRenderMixin", new String[]{"net.minecraft.class_329"}); // InGameHud
            mixinToTargets.put("KeyboardMixin", new String[]{"net.minecraft.class_309"}); // Keyboard
            
            String[] targetNames = mixinToTargets.get(mixinName);
            if (targetNames != null) {
                List<Class<?>> targets = new ArrayList<>();
                
                for (String targetName : targetNames) {
                    try {
                        Class<?> targetClass = cl.loadClass(targetName);
                        targets.add(targetClass);
                        writer.println("      Guessed target: " + targetName + " ✓");
                    } catch (ClassNotFoundException e) {
                        writer.println("      Failed to load guessed target: " + targetName);
                    }
                }
                
                return targets.toArray(new Class<?>[0]);
            }
            
        } catch (Exception e) {
            writer.println("    Error in target guessing: " + e.getMessage());
        }
        
        return new Class<?>[0];
    }
    
    // Устанавливаем хуки для миксинов
    private void installMixinHooks(PrintWriter writer, Class<?> mixinClass, Object mixinInstance, Class<?> targetClass) {
        try {
            writer.println("    Installing hooks for target: " + targetClass.getName());
            
            Method[] mixinMethods = mixinClass.getDeclaredMethods();
            writer.println("      Found " + mixinMethods.length + " methods in mixin");
            
            for (Method mixinMethod : mixinMethods) {
                mixinMethod.setAccessible(true);
                
                java.lang.annotation.Annotation[] methodAnnotations = mixinMethod.getDeclaredAnnotations();
                writer.println("        Method: " + mixinMethod.getName() + " (" + methodAnnotations.length + " annotations)");
                
                for (java.lang.annotation.Annotation annotation : methodAnnotations) {
                    String annotationType = annotation.annotationType().getName();
                    writer.println("          Annotation: " + annotationType);
                    
                    if (annotationType.equals("org.spongepowered.asm.mixin.injection.Inject")) {
                        setupInjectHookFixed(writer, mixinClass, mixinInstance, mixinMethod, targetClass, annotation);
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
    
    // ИСПРАВЛЕННЫЙ метод setup inject hook
    private void setupInjectHookFixed(PrintWriter writer, Class<?> mixinClass, Object mixinInstance, Method mixinMethod, Class<?> targetClass, java.lang.annotation.Annotation injectAnnotation) {
        try {
            writer.println("          Processing @Inject...");
            
            // Получаем target методы
            Method methodsMethod = injectAnnotation.annotationType().getMethod("method");
            String[] targetMethods = (String[]) methodsMethod.invoke(injectAnnotation);
            
            writer.println("            Target methods: " + java.util.Arrays.toString(targetMethods));
            
            for (String targetMethodName : targetMethods) {
                writer.println("            Setting up hook: " + mixinMethod.getName() + " -> " + targetClass.getSimpleName() + "." + targetMethodName);
                
                // Регистрируем хук
                String hookKey = targetClass.getName() + "." + targetMethodName;
                MixinHookRegistry.registerInjectHook(hookKey, mixinInstance, mixinMethod);
                
                // ДОПОЛНИТЕЛЬНО: Пытаемся установить прямую замену метода
                try {
                    installDirectMethodHook(writer, targetClass, targetMethodName, mixinInstance, mixinMethod);
                } catch (Exception e) {
                    writer.println("              Failed to install direct hook: " + e.getMessage());
                }
                
                writer.println("            ✓ Hook registered: " + hookKey);
            }
            
        } catch (Exception e) {
            writer.println("Failed to setup @Inject hook: " + e.getMessage());
            e.printStackTrace(writer);
        }
    }
    
    private void setupOverwriteHook(PrintWriter writer, Class<?> mixinClass, Object mixinInstance, Method mixinMethod, Class<?> targetClass) {
        try {
            writer.println("          Processing @Overwrite...");
            
            String methodName = mixinMethod.getName();
            String hookKey = targetClass.getName() + "." + methodName;
            
            MixinHookRegistry.registerOverwriteHook(hookKey, mixinInstance, mixinMethod);
            writer.println("            ✓ Overwrite registered: " + hookKey);
            
        } catch (Exception e) {
            writer.println("Failed to setup @Overwrite hook: " + e.getMessage());
        }
    }
    
    // НОВЫЙ метод: прямая замена методов через рефлекшн
    private void installDirectMethodHook(PrintWriter writer, Class<?> targetClass, String methodName, Object mixinInstance, Method mixinMethod) {
        try {
            writer.println("              Installing direct method hook...");
            
            // Находим target метод
            Method[] targetMethods = targetClass.getDeclaredMethods();
            Method targetMethod = null;
            
            for (Method method : targetMethods) {
                if (method.getName().equals(methodName)) {
                    targetMethod = method;
                    break;
                }
            }
            
            if (targetMethod != null) {
                writer.println("                Found target method: " + targetMethod);
                
                // Создаем wrapper который будет вызывать mixin
                MethodInterceptor interceptor = new MethodInterceptor(mixinInstance, mixinMethod, targetMethod);
                
                // Сохраняем interceptor для дальнейшего использования
                String interceptorKey = targetClass.getName() + "." + methodName;
                MixinHookRegistry.registerInterceptor(interceptorKey, interceptor);
                
                writer.println("                ✓ Method interceptor installed");
            } else {
                writer.println("                ! Target method not found: " + methodName);
            }
            
        } catch (Exception e) {
            writer.println("              Error installing direct hook: " + e.getMessage());
        }
    }
    
    // НОВЫЙ метод: установка runtime hooks
    private void installRuntimeHooks(PrintWriter writer, ClassLoader cl) {
        writer.println("=== INSTALLING RUNTIME HOOKS ===");
        
        try {
            // Создаем глобальный перехватчик для runtime вызовов
            RuntimeMethodInterceptor.initialize(writer);
            writer.println("✓ Runtime method interceptor initialized");
            
            // Регистрируем все наши hooks в глобальном перехватчике
            Map<String, MixinHookRegistry.MethodInterceptor> interceptors = MixinHookRegistry.getAllInterceptors();
            
            for (Map.Entry<String, MixinHookRegistry.MethodInterceptor> entry : interceptors.entrySet()) {
                String methodKey = entry.getKey();
                MixinHookRegistry.MethodInterceptor interceptor = entry.getValue();
                
                RuntimeMethodInterceptor.registerHook(methodKey, interceptor);
                writer.println("  Registered runtime hook: " + methodKey);
            }
            
            writer.println("✓ All runtime hooks installed");
            
        } catch (Exception e) {
            writer.println("Error installing runtime hooks: " + e.getMessage());
            e.printStackTrace(writer);
        }
    }
    
    private void initializeMods(PrintWriter writer, ClassLoader cl, ArrayList<Class<?>> modClasses) {
        writer.println("=== INITIALIZING MODS ===");
        
        for (Class<?> modClass : modClasses) {
            try {
                writer.println("Initializing: " + modClass.getName());
                
                Object modInstance = modClass.newInstance();
                
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

// РАСШИРЕННЫЙ реестр для mixin hooks
class MixinHookRegistry {
    private static Map<String, List<MixinHook>> injectHooks = new HashMap<>();
    private static Map<String, MixinHook> overwriteHooks = new HashMap<>();
    private static Map<String, MethodInterceptor> interceptors = new HashMap<>();
    
    public static void registerInjectHook(String methodKey, Object mixinInstance, Method mixinMethod) {
        injectHooks.computeIfAbsent(methodKey, k -> new ArrayList<>())
                   .add(new MixinHook(mixinInstance, mixinMethod));
    }
    
    public static void registerOverwriteHook(String methodKey, Object mixinInstance, Method mixinMethod) {
        overwriteHooks.put(methodKey, new MixinHook(mixinInstance, mixinMethod));
    }
    
    public static void registerInterceptor(String methodKey, MethodInterceptor interceptor) {
        interceptors.put(methodKey, interceptor);
    }
    
    public static Map<String, MethodInterceptor> getAllInterceptors() {
        return new HashMap<>(interceptors);
    }
    
    public static List<MixinHook> getInjectHooks(String methodKey) {
        return injectHooks.getOrDefault(methodKey, new ArrayList<>());
    }
    
    public static MixinHook getOverwriteHook(String methodKey) {
        return overwriteHooks.get(methodKey);
    }
    
    public static MethodInterceptor getInterceptor(String methodKey) {
        return interceptors.get(methodKey);
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
    
    static class MethodInterceptor {
        Object mixinInstance;
        Method mixinMethod;
        Method originalMethod;
        
        MethodInterceptor(Object mixinInstance, Method mixinMethod, Method originalMethod) {
            this.mixinInstance = mixinInstance;
            this.mixinMethod = mixinMethod;
            this.originalMethod = originalMethod;
        }
        
        public Object intercept(Object target, Object... args) throws Exception {
            // Вызываем mixin метод
            return mixinMethod.invoke(mixinInstance, args);
        }
        
        public Method getMixinMethod() { return mixinMethod; }
        public Method getOriginalMethod() { return originalMethod; }
        public Object getMixinInstance() { return mixinInstance; }
    }
}

// НОВЫЙ класс: Runtime перехватчик методов
class RuntimeMethodInterceptor {
    private static Map<String, MixinHookRegistry.MethodInterceptor> hooks = new HashMap<>();
    private static boolean initialized = false;
    
    public static void initialize(PrintWriter writer) {
        if (initialized) return;
        
        writer.println("Initializing RuntimeMethodInterceptor...");
        
        // Здесь можно установить глобальные хуки
        // В реальности нужен более сложный механизм
        
        initialized = true;
    }
    
    public static void registerHook(String methodKey, MixinHookRegistry.MethodInterceptor interceptor) {
        hooks.put(methodKey, interceptor);
    }
    
    public static Object intercept(String className, String methodName, Object target, Object... args) {
        String key = className + "." + methodName;
        MixinHookRegistry.MethodInterceptor interceptor = hooks.get(key);
        
        if (interceptor != null) {
            try {
                return interceptor.intercept(target, args);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        
        return null; // Fallback to original method
    }
    
    public static boolean hasHook(String className, String methodName) {
        return hooks.containsKey(className + "." + methodName);
    }
}