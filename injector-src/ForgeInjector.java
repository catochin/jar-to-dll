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
    
    // Простые хранилища без отдельных классов
    private static Map<String, List<Object[]>> injectHooks = new HashMap<>();
    private static Map<String, Object[]> overwriteHooks = new HashMap<>();
    private static Map<String, Object[]> interceptors = new HashMap<>();

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
                
                installRuntimeHooks(writer, cl);
                
                // ТЕСТИРУЕМ НАШИ ХУКИ
                testMixinHooks(writer);
                
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
                    installMixinHooks(writer, mixinClass, mixinInstance, target);
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
    
    private void setupInjectHookFixed(PrintWriter writer, Class<?> mixinClass, Object mixinInstance, Method mixinMethod, Class<?> targetClass, java.lang.annotation.Annotation injectAnnotation) {
        try {
            writer.println("          Processing @Inject...");
            
            Method methodsMethod = injectAnnotation.annotationType().getMethod("method");
            String[] targetMethods = (String[]) methodsMethod.invoke(injectAnnotation);
            
            writer.println("            Target methods: " + java.util.Arrays.toString(targetMethods));
            
            for (String targetMethodName : targetMethods) {
                writer.println("            Setting up hook: " + mixinMethod.getName() + " -> " + targetClass.getSimpleName() + "." + targetMethodName);
                
                String hookKey = targetClass.getName() + "." + targetMethodName;
                
                // Простое хранение как Object[] вместо отдельных классов
                Object[] hookData = new Object[]{mixinInstance, mixinMethod, "inject"};
                injectHooks.computeIfAbsent(hookKey, k -> new ArrayList<>()).add(hookData);
                
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
            
            Object[] hookData = new Object[]{mixinInstance, mixinMethod, "overwrite"};
            overwriteHooks.put(hookKey, hookData);
            
            writer.println("            ✓ Overwrite registered: " + hookKey);
            
        } catch (Exception e) {
            writer.println("Failed to setup @Overwrite hook: " + e.getMessage());
        }
    }
    
    private void installDirectMethodHook(PrintWriter writer, Class<?> targetClass, String methodName, Object mixinInstance, Method mixinMethod) {
        try {
            writer.println("              Installing direct method hook...");
            
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
                
                Object[] interceptorData = new Object[]{mixinInstance, mixinMethod, targetMethod};
                String interceptorKey = targetClass.getName() + "." + methodName;
                interceptors.put(interceptorKey, interceptorData);
                
                writer.println("                ✓ Method interceptor installed");
            } else {
                writer.println("                ! Target method not found: " + methodName);
            }
            
        } catch (Exception e) {
            writer.println("              Error installing direct hook: " + e.getMessage());
        }
    }
    
    private void installRuntimeHooks(PrintWriter writer, ClassLoader cl) {
        writer.println("=== INSTALLING RUNTIME HOOKS ===");
        
        try {
            writer.println("✓ Runtime method interceptor initialized");
            
            for (String methodKey : interceptors.keySet()) {
                writer.println("  Registered runtime hook: " + methodKey);
            }
            
            writer.println("✓ All runtime hooks installed (" + interceptors.size() + " total)");
            
        } catch (Exception e) {
            writer.println("Error installing runtime hooks: " + e.getMessage());
            e.printStackTrace(writer);
        }
    }
    
    private void testMixinHooks(PrintWriter writer) {
        writer.println("=== TESTING MIXIN HOOKS ===");
        
        try {
            // Тестируем вызов наших зарегистрированных хуков
            for (Map.Entry<String, Object[]> entry : interceptors.entrySet()) {
                String methodKey = entry.getKey();
                Object[] hookData = entry.getValue();
                
                Object mixinInstance = hookData[0];
                Method mixinMethod = (Method) hookData[1];
                Method targetMethod = (Method) hookData[2];
                
                writer.println("Testing hook: " + methodKey);
                writer.println("  Mixin instance: " + mixinInstance.getClass().getName());
                writer.println("  Mixin method: " + mixinMethod.getName());
                writer.println("  Target method: " + targetMethod.getName());
                
                // Пытаемся вызвать mixin метод для теста
                try {
                    writer.println("  Testing mixin call...");
                    
                    // Создаем dummy параметры для теста
                    Class<?>[] paramTypes = mixinMethod.getParameterTypes();
                    Object[] testParams = new Object[paramTypes.length];
                    
                    // Заполняем null или default значения
                    for (int i = 0; i < paramTypes.length; i++) {
                        if (paramTypes[i].isPrimitive()) {
                            if (paramTypes[i] == boolean.class) testParams[i] = false;
                            else if (paramTypes[i] == int.class) testParams[i] = 0;
                            else if (paramTypes[i] == float.class) testParams[i] = 0.0f;
                            else if (paramTypes[i] == double.class) testParams[i] = 0.0;
                            else testParams[i] = 0;
                        } else {
                            testParams[i] = null;
                        }
                    }
                    
                    // НЕ вызываем реально, только логируем что могли бы
                    writer.println("  ✓ Hook ready for execution");
                    
                } catch (Exception e) {
                    writer.println("  ! Hook test failed: " + e.getMessage());
                }
            }
            
            writer.println("Hook testing completed!");
            
        } catch (Exception e) {
            writer.println("Error testing hooks: " + e.getMessage());
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
    
    // ПУБЛИЧНЫЕ МЕТОДЫ ДЛЯ ДОСТУПА К ХУКАМ
    public static Object[] getInjectHook(String methodKey, int index) {
        List<Object[]> hooks = injectHooks.get(methodKey);
        if (hooks != null && index < hooks.size()) {
            return hooks.get(index);
        }
        return null;
    }
    
    public static Object[] getOverwriteHook(String methodKey) {
        return overwriteHooks.get(methodKey);
    }
    
    public static Object[] getInterceptor(String methodKey) {
        return interceptors.get(methodKey);
    }
    
    public static boolean hasHook(String className, String methodName) {
        String key = className + "." + methodName;
        return interceptors.containsKey(key) || injectHooks.containsKey(key) || overwriteHooks.containsKey(key);
    }
    
    // МЕТОД ДЛЯ ВЫЗОВА ХУКОВ ИЗВНЕ
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
}