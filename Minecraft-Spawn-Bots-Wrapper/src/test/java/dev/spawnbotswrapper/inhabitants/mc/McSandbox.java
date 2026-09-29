package dev.spawnbotswrapper.inhabitants.mc;

import org.junit.jupiter.api.DynamicTest;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Lets unit tests run code that needs the real Minecraft classes and registries.
 * <p>
 * The deobfuscated (Mojang-named) Minecraft jar has package-private members that are used across packages; that is
 * invalid on a plain classpath, and Fabric Loader normally repairs it while loading classes in the game.
 * A JUnit JVM has no Loader, so {@code Bootstrap.bootStrap()} dies with an {@code IllegalAccessError}. This
 * class does the same repair itself: it loads {@code net.minecraft.*} and the addon's own classes through a
 * child class loader that widens every non-private member to public, exactly like Loader's own transformer.
 * <p>
 * Test code that touches Minecraft therefore lives in a "cases" class (never referenced directly by a JUnit
 * class, or it would be loaded by the plain loader), and a JUnit method just says
 * {@code McSandbox.run("SomethingMcCases", "someCase")}. The game is bootstrapped once per JVM, on first use.
 */
final class McSandbox {
    private static final Object LOCK = new Object();
    private static Loader loader;

    private McSandbox() {
    }

    /**
     * Runs the public static no-argument method {@code method} of the class {@code casesClass} (a simple name
     * in this package), inside the repaired loader, after making sure the game is bootstrapped. Assertion
     * failures and other exceptions are rethrown as thrown.
     */
    static void run(String casesClass, String method) throws Exception {
        synchronized (LOCK) {
            if (loader == null) {
                loader = new Loader(McSandbox.class.getClassLoader());
            }
            invoke(loader.loadClass(McSandbox.class.getPackageName() + ".McBootstrap"), "ensure");
            invoke(loader.loadClass(McSandbox.class.getPackageName() + "." + casesClass), method);
        }
    }

    /**
     * One JUnit dynamic test per public static no-argument void method of {@code casesClass}, named after the
     * method, so adding a case to the class adds a test without touching the JUnit side.
     */
    static Stream<DynamicTest> cases(String casesClass) throws Exception {
        List<String> names = new ArrayList<>();
        synchronized (LOCK) {
            if (loader == null) {
                loader = new Loader(McSandbox.class.getClassLoader());
            }
            Class<?> type = loader.loadClass(McSandbox.class.getPackageName() + "." + casesClass);
            for (Method m : type.getDeclaredMethods()) {
                int mods = m.getModifiers();
                if (Modifier.isPublic(mods) && Modifier.isStatic(mods) && m.getParameterCount() == 0 && m.getReturnType() == void.class) {
                    names.add(m.getName());
                }
            }
        }
        names.sort(String::compareTo);
        if (names.isEmpty()) {
            throw new IllegalStateException("no cases found in " + casesClass);
        }
        return names.stream().map(name -> DynamicTest.dynamicTest(name, () -> run(casesClass, name)));
    }

    private static void invoke(Class<?> type, String method) throws Exception {
        Method m = type.getMethod(method);
        Thread current = Thread.currentThread();
        ClassLoader previous = current.getContextClassLoader();
        current.setContextClassLoader(type.getClassLoader());
        try {
            m.invoke(null);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw e;
        } finally {
            current.setContextClassLoader(previous);
        }
    }

    /** Child-first loader for Minecraft and the addon; everything else (JDK, libraries, JUnit) is shared. */
    private static final class Loader extends ClassLoader {
        private static final String SELF = McSandbox.class.getName();

        Loader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            boolean minecraft = name.startsWith("net.minecraft.") || name.startsWith("com.mojang.math.")
                    || name.startsWith("com.mojang.blaze3d.") || name.startsWith("com.mojang.realmsclient.");
            boolean addon = name.startsWith("dev.spawnbotswrapper.inhabitants.") && !name.startsWith(SELF);
            if (!minecraft && !addon) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> type = findLoadedClass(name);
                if (type == null) {
                    type = define(name, minecraft);
                }
                if (resolve) {
                    resolveClass(type);
                }
                return type;
            }
        }

        private Class<?> define(String name, boolean widenAccess) throws ClassNotFoundException {
            try (InputStream in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                if (in == null) {
                    throw new ClassNotFoundException(name);
                }
                byte[] bytes = in.readAllBytes();
                if (widenAccess) {
                    ClassWriter writer = new ClassWriter(0);
                    new ClassReader(bytes).accept(new AccessWidener(writer), 0);
                    bytes = writer.toByteArray();
                }
                return defineClass(name, bytes, 0, bytes.length);
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }
    }

    /** Every non-private access flag becomes public (the same rule as Fabric Loader's package access fixer). */
    private static final class AccessWidener extends ClassVisitor {
        AccessWidener(ClassVisitor next) {
            super(Opcodes.ASM9, next);
        }

        private static int widen(int access) {
            return (access & 0x7) != Opcodes.ACC_PRIVATE ? (access & ~0x7) | Opcodes.ACC_PUBLIC : access;
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            super.visit(version, widen(access), name, signature, superName, interfaces);
        }

        @Override
        public void visitInnerClass(String name, String outerName, String innerName, int access) {
            super.visitInnerClass(name, outerName, innerName, widen(access));
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            return super.visitField(widen(access), name, descriptor, signature, value);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            return super.visitMethod(widen(access), name, descriptor, signature, exceptions);
        }
    }
}
