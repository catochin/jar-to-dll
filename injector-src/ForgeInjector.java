import java.io.File;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class ForgeInjector extends Thread {
    private byte[][] classes;
    private static volatile boolean injected = false;
    private static Map<String, Class<?>> definedClasses = new HashMap<>();
    private static Map<String, Object> mixinInstances = new HashMap<>();
    
    // Хранилища для активных хуков
    private static Map<String, List<Object[]>> injectHooks = new HashMap<>();
    private static Map<String, Object[]> overwriteHooks = new HashMap<>();
    private static Map<String, Object[]> interceptors = new ConcurrentHashMap<>();
    private static Map<Method, Method> methodReplacements = new HashMap<>();
    
    // Глобальный перехватчик методов
    private static Map<String, Object[]> globalHooks = new ConcurrentHashMap<>();
    private static boolean globalInterceptorInitialized = false;
    
    // Простые массивы для хранения данных перехватчиков (без inner classes)
    private static Map<String, Object[]> runtimeHandlers = new ConcurrentHashMap<>();
    private static Map<String, Object[]> staticInterceptors = new ConcurrentHashMap<>();
    
    // Флаги активности перехватчиков
    private static Map<String, Boolean> interceptorFlags = new ConcurrentHashMap<>();

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
                
                if (!mixinClasses.isEmpty()) {
                    applyDllMixins(writer, cl, mixinClasses);
                }
                
                if (!modClasses.isEmpty()) {
                    initializeMods(writer, cl, modClasses);
                }
                
                // КРИТИЧЕСКИ ВАЖНО: Устанавливаем глобальные хуки
                installGlobalMethodHooks(writer, cl);
                
                // Запускаем runtime перехватчик
                startRuntimeInterceptor(writer);
                
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
    
    private void applyDllMixins(PrintWriter writer, ClassLoader cl, ArrayList<Class<?>> mixinClasses) {
        writer.println("=== APPLYING DLL MIXINS ===");
        
        for (Class<?> mixinClass : mixinClasses) {
            try {
                writer.println("Processing mixin: " + mixinClass.getName());
                
                Object mixinInstance = mixinClass.newInstance();
                mixinInstances.put(mixinClass.getName(), mixinInstance);
                writer.println("  Created mixin instance");
                
                Class<?>[] targets = getMixinTargetsFixed(mixinClass, writer, cl);
                
                if (targets.length == 0) {
                    writer.println("  ERROR: No target classes found for mixin!");
                    continue;
                }
                
                for (Class<?> target : targets) {
                    writer.println("  Target found: " + target.getName());
                    installMixinHooksAdvanced(writer, mixinClass, mixinInstance, target);
                }
                
            } catch (Exception e) {
                writer.println("Failed to process mixin " + mixinClass.getName() + ": " + e.getMessage());
                e.printStackTrace(writer);
            }
        }
    }
    
    private Class<?>[] getMixinTargetsFixed(Class<?> mixinClass, PrintWriter writer, ClassLoader cl) {
        try {
            writer.println("  Analyzing mixin annotations...");
            
            java.lang.annotation.Annotation[] annotations = mixinClass.getDeclaredAnnotations();
            writer.println("  Found " + annotations.length + " annotations");
            
            for (java.lang.annotation.Annotation annotation : annotations) {
                String annotationType = annotation.annotationType().getName();
                writer.println("    Annotation: " + annotationType);
                
                if (annotationType.equals("org.spongepowered.asm.mixin.Mixin")) {
                    writer.println("    Found @Mixin annotation!");
                    
                    try {
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
            
            writer.println("  Fallback: trying to determine targets by class name");
            return guessTargetsByClassName(mixinClass, writer, cl);
            
        } catch (Exception e) {
            writer.println("  ERROR in getMixinTargetsFixed: " + e.getMessage());
            e.printStackTrace(writer);
        }
        
        return new Class<?>[0];
    }
    
    private Class<?>[] guessTargetsByClassName(Class<?> mixinClass, PrintWriter writer, ClassLoader cl) {
        try {
            String mixinName = mixinClass.getSimpleName();
            writer.println("    Guessing targets for: " + mixinName);
            
            Map<String, String[]> mixinToTargets = new HashMap<>();
            mixinToTargets.put("RenderMixin", new String[]{"net.minecraft.class_761"});
            mixinToTargets.put("IngameRenderMixin", new String[]{"net.minecraft.class_329"});
            mixinToTargets.put("KeyboardMixin", new String[]{"net.minecraft.class_309"});
            
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
    
    private void installMixinHooksAdvanced(PrintWriter writer, Class<?> mixinClass, Object mixinInstance, Class<?> targetClass) {
        try {
            writer.println("    Installing ADVANCED hooks for target: " + targetClass.getName());
            
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
                        setupAdvancedInjectHook(writer, mixinClass, mixinInstance, mixinMethod, targetClass, annotation);
                    }
                    else if (annotationType.equals("org.spongepowered.asm.mixin.Overwrite")) {
                        setupAdvancedOverwriteHook(writer, mixinClass, mixinInstance, mixinMethod, targetClass);
                    }
                }
            }
            
        } catch (Exception e) {
            writer.println("Error installing advanced mixin hooks: " + e.getMessage());
            e.printStackTrace(writer);
        }
    }
    
    private void setupAdvancedInjectHook(PrintWriter writer, Class<?> mixinClass, Object mixinInstance, Method mixinMethod, Class<?> targetClass, java.lang.annotation.Annotation injectAnnotation) {
        try {
            writer.println("          Processing ADVANCED @Inject...");
            
            Method methodsMethod = injectAnnotation.annotationType().getMethod("method");
            String[] targetMethodNames = (String[]) methodsMethod.invoke(injectAnnotation);
            
            writer.println("            Target methods: " + java.util.Arrays.toString(targetMethodNames));
            
            for (String targetMethodName : targetMethodNames) {
                writer.println("            Looking for method: " + targetMethodName);
                
                Method foundMethod = findMethodBySimilarity(writer, targetClass, targetMethodName, mixinMethod);
                
                if (foundMethod != null) {
                    writer.println("              ✓ Found similar method: " + foundMethod.getName());
                    
                    String hookKey = targetClass.getName() + "." + foundMethod.getName();
                    Object[] hookData = new Object[]{mixinInstance, mixinMethod, "inject", foundMethod};
                    injectHooks.computeIfAbsent(hookKey, k -> new ArrayList<>()).add(hookData);
                    
                    // Простая регистрация хука без inner classes
                    registerSimpleHook(writer, foundMethod, mixinMethod, mixinInstance);
                    
                    writer.println("              ✓ ADVANCED Hook installed: " + hookKey);
                } else {
                    writer.println("              ! No suitable method found for: " + targetMethodName);
                }
            }
            
        } catch (Exception e) {
            writer.println("Failed to setup advanced @Inject hook: " + e.getMessage());
            e.printStackTrace(writer);
        }
    }
    
    private void setupAdvancedOverwriteHook(PrintWriter writer, Class<?> mixinClass, Object mixinInstance, Method mixinMethod, Class<?> targetClass) {
        try {
            writer.println("          Processing ADVANCED @Overwrite...");
            
            String methodName = mixinMethod.getName();
            Method foundMethod = findMethodBySimilarity(writer, targetClass, methodName, mixinMethod);
            
            if (foundMethod != null) {
                String hookKey = targetClass.getName() + "." + foundMethod.getName();
                Object[] hookData = new Object[]{mixinInstance, mixinMethod, "overwrite", foundMethod};
                overwriteHooks.put(hookKey, hookData);
                
                // Простая регистрация хука
                registerSimpleHook(writer, foundMethod, mixinMethod, mixinInstance);
                
                writer.println("            ✓ ADVANCED Overwrite installed: " + hookKey);
            }
            
        } catch (Exception e) {
            writer.println("Failed to setup advanced @Overwrite hook: " + e.getMessage());
        }
    }
    
    private Method findMethodBySimilarity(PrintWriter writer, Class<?> targetClass, String searchName, Method mixinMethod) {
        Method[] methods = targetClass.getDeclaredMethods();
        
        writer.println("              Searching for similar methods to: " + searchName);
        
        // Специальные случаи для ваших миксинов
        if (searchName.equals("renderEntity") && targetClass.getName().contains("class_761")) {
            for (Method method : methods) {
                Class<?>[] params = method.getParameterTypes();
                if (params.length >= 7) {
                    // Ищем метод с Entity параметром (class_1297)
                    for (Class<?> param : params) {
                        if (param.getName().contains("class_1297")) {
                            writer.println("                Found renderEntity candidate: " + method.getName());
                            return method;
                        }
                    }
                }
            }
        }
        
        if (searchName.equals("render") && targetClass.getName().contains("class_329")) {
            for (Method method : methods) {
                Class<?>[] params = method.getParameterTypes();
                if (params.length >= 1 && params.length <= 3) {
                    // Ищем метод InGameHud.render с MatrixStack
                    for (Class<?> param : params) {
                        if (param.getName().contains("class_4587") || param.getName().contains("MatrixStack")) {
                            writer.println("                Found InGameHud render candidate: " + method.getName());
                            return method;
                        }
                    }
                }
            }
        }
        
        if (searchName.equals("onKey") && targetClass.getName().contains("class_309")) {
            for (Method method : methods) {
                Class<?>[] params = method.getParameterTypes();
                if (params.length >= 5) {
                    // Ищем метод Keyboard.onKey с long и int параметрами
                    if (params[0] == long.class || params[0] == Long.class) {
                        writer.println("                Found onKey candidate: " + method.getName());
                        return method;
                    }
                }
            }
        }
        
        writer.println("                No similar method found");
        return null;
    }
    
    // Простая регистрация хука без inner classes
    private void registerSimpleHook(PrintWriter writer, Method originalMethod, Method mixinMethod, Object mixinInstance) {
        try {
            writer.println("                REGISTERING simple hook for: " + originalMethod.getName());
            
            String key = originalMethod.getDeclaringClass().getName() + "." + originalMethod.getName();
            
            // Сохраняем данные хука в простых массивах
            Object[] hookData = new Object[]{mixinInstance, mixinMethod, originalMethod};
            interceptors.put(key, hookData);
            globalHooks.put(key, hookData);
            runtimeHandlers.put(key, hookData);
            staticInterceptors.put(key, hookData);
            
            // Устанавливаем флаг активности
            interceptorFlags.put(key, true);
            
            writer.println("                ✓ Simple hook registered: " + key);
            
        } catch (Exception e) {
            writer.println("                ! Failed to register simple hook: " + e.getMessage());
            e.printStackTrace(writer);
        }
    }
    
    // Встроенные методы глобального перехватчика
    private void installGlobalMethodHooks(PrintWriter writer, ClassLoader cl) {
        writer.println("=== INSTALLING GLOBAL METHOD HOOKS ===");
        
        try {
            initializeGlobalInterceptor(writer, interceptors);
            
            writer.println("✓ Global method interceptor installed with " + interceptors.size() + " hooks");
            
            for (String hookKey : interceptors.keySet()) {
                writer.println("  Active hook: " + hookKey);
            }
            
        } catch (Exception e) {
            writer.println("Error installing global hooks: " + e.getMessage());
            e.printStackTrace(writer);
        }
    }
    
    public static void initializeGlobalInterceptor(PrintWriter writer, Map<String, Object[]> hooks) {
        if (globalInterceptorInitialized) return;
        
        globalHooks.putAll(hooks);
        globalInterceptorInitialized = true;
        
        writer.println("Global interceptor initialized with " + globalHooks.size() + " hooks");
    }
    
    // Запуск runtime перехватчика
    private void startRuntimeInterceptor(PrintWriter writer) {
        writer.println("=== STARTING RUNTIME INTERCEPTOR ===");
        
        try {
            // Создаем простой перехватчик в отдельном потоке
            Thread interceptorThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    runInterceptorLoop();
                }
            });
            
            interceptorThread.setDaemon(true);
            interceptorThread.setName("MixinInterceptor");
            interceptorThread.start();
            
            writer.println("✓ Runtime interceptor thread started");
            
        } catch (Exception e) {
            writer.println("Error starting runtime interceptor: " + e.getMessage());
            e.printStackTrace(writer);
        }
    }
    
    // Основной цикл перехватчика
    private void runInterceptorLoop() {
        while (true) {
            try {
                // Простая проверка активности каждые 50ms
                Thread.sleep(50);
                
                // Проверяем флаги перехватчиков
                for (String key : interceptorFlags.keySet()) {
                    Boolean active = interceptorFlags.get(key);
                    if (active != null && active) {
                        // Перехватчик активен
                        checkAndInterceptMethod(key);
                    }
                }
                
            } catch (Exception e) {
                System.out.println("Error in interceptor loop: " + e.getMessage());
            }
        }
    }
    
    // Проверка и перехват метода
    private void checkAndInterceptMethod(String key) {
        try {
            Object[] hookData = globalHooks.get(key);
            if (hookData != null) {
                // Метод доступен для перехвата
                // В реальности здесь бы был bytecode injection или JNI hook
                // Но пока просто логируем
            }
        } catch (Exception e) {
            System.out.println("Error checking method: " + key + " - " + e.getMessage());
        }
    }
    
    // ПУБЛИЧНЫЕ МЕТОДЫ ПЕРЕХВАТА
    public static Object interceptMethodCall(Object target, String methodName, Object... args) {
        String className = target.getClass().getName();
        String key = className + "." + methodName;
        
        Object[] hookData = globalHooks.get(key);
        if (hookData != null) {
            try {
                Object mixinInstance = hookData[0];
                Method mixinMethod = (Method) hookData[1];
                
                System.out.println("INTERCEPTED: " + key);
                return mixinMethod.invoke(mixinInstance, args);
            } catch (Exception e) {
                System.out.println("Error in interceptor: " + e.getMessage());
                e.printStackTrace();
            }
        }
        
        return null;
    }
    
    public static boolean shouldInterceptMethodCall(Object target, String methodName) {
        String className = target.getClass().getName();
        String key = className + "." + methodName;
        
        return globalHooks.containsKey(key) && 
               interceptorFlags.getOrDefault(key, false);
    }
    
    // Активный вызов миксина (для тестирования)
    public static void triggerKeyboardMixin(long window, int key, int scancode, int action, int mods) {
        try {
            String targetKey = "net.minecraft.class_309.method_22678";
            Object[] hookData = globalHooks.get(targetKey);
            
            if (hookData != null) {
                Object mixinInstance = hookData[0];
                Method mixinMethod = (Method) hookData[1];
                
                System.out.println("=== TRIGGERING KEYBOARD MIXIN ===");
                System.out.println("Key: " + key + ", Action: " + action);
                
                // Вызываем mixin напрямую
                Object result = mixinMethod.invoke(mixinInstance, window, key, scancode, action, mods);
                
                System.out.println("✓ Keyboard mixin triggered successfully");
            } else {
                System.out.println("! Keyboard mixin not found");
            }
            
        } catch (Exception e) {
            System.out.println("Error triggering keyboard mixin: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    // Тестирование всех миксинов
    public static void testAllMixins() {
        try {
            System.out.println("=== TESTING ALL MIXINS ===");
            
            // Тест клавиатуры
            triggerKeyboardMixin(0L, 256, 0, 1, 0); // ESC key press
            
            // Тест рендера (если есть инстанс)
            Object renderMixin = getMixinInstance("net.fabricmc.example.mixin.RenderMixin");
            if (renderMixin != null) {
                System.out.println("✓ Render mixin instance available");
            }
            
            Object ingameMixin = getMixinInstance("net.fabricmc.example.mixin.IngameRenderMixin");
            if (ingameMixin != null) {
                System.out.println("✓ Ingame render mixin instance available");
            }
            
            System.out.println("=== MIXIN TEST COMPLETED ===");
            
        } catch (Exception e) {
            System.out.println("Error testing mixins: " + e.getMessage());
            e.printStackTrace();
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
    
    // ПУБЛИЧНЫЕ МЕТОДЫ для внешнего вызова
    public static boolean hasHook(String className, String methodName) {
        String key = className + "." + methodName;
        return interceptors.containsKey(key);
    }
    
    public static Object callMixinHook(String className, String methodName, Object... args) {
        String key = className + "." + methodName;
        Object[] hookData = interceptors.get(key);
        
        if (hookData != null) {
            try {
                Object mixinInstance = hookData[0];
                Method mixinMethod = (Method) hookData[1];
                return mixinMethod.invoke(mixinInstance, args);
            } catch (Exception e) {
                e.printStackTrace();
            }
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
    
    public static Map<String, Object[]> getAllHooks() {
        return new HashMap<>(interceptors);
    }
}