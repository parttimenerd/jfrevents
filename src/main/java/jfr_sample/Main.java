package jfr_sample;

import java.io.*;
import java.lang.instrument.*;
import java.lang.management.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;
import java.util.jar.*;

/**
 * Maximalist JFR event generator.
 * Triggers as many Java-level JFR event types as possible.
 * Requires: -Djdk.attach.allowAttachSelf=true -XX:DiagnoseSyncOnValueBasedClasses=1
 *            -XX:NativeMemoryTracking=summary
 */
public class Main {

    static volatile Object sink;
    static final Object lock = new Object();
    static volatile boolean running = true;
    static Instrumentation instrumentation;

    // Agent premain — invoked when we self-attach
    public static void agentmain(String args, Instrumentation inst) {
        instrumentation = inst;
    }

    public static void premain(String args, Instrumentation inst) {
        instrumentation = inst;
    }

    public static void main(String[] args) throws Exception {
        long end = System.currentTimeMillis() + 25_000;

        // Self-attach to get Instrumentation (enables ClassRedefinition/Retransformation)
        selfAttach();

        // ThreadStart/ThreadEnd, ThreadCPULoad, ThreadAllocationStatistics
        ExecutorService pool = Executors.newFixedThreadPool(4);
        for (int t = 0; t < 4; t++) {
            pool.submit(() -> {
                while (running) {
                    allocateAndGC();
                    try { Thread.sleep(5); } catch (InterruptedException e) { break; }
                }
            });
        }

        // VirtualThreadStart/End, VirtualThreadPinned (synchronized block in vthread)
        startVirtualThreads();

        // TLSHandshake, X509Certificate, X509Validation
        httpsRequest();

        // Deserialization, SerializationMisdeclaration
        deserialization();

        // ProcessStart
        startProcess();

        // SecurityPropertyModification
        Security.setProperty("jdk.disabled.namedCurves", "secp112r1");

        // BooleanFlagChanged, IntFlagChanged, LongFlagChanged, StringFlagChanged,
        // UnsignedIntFlagChanged, UnsignedLongFlagChanged, DoubleFlagChanged
        changeDiagnosticFlags();

        // FinalizerStatistics
        spawnFinalizable();

        // NativeLibraryLoad (already done by JVM — trigger more via System.loadLibrary attempts)
        triggerNativeLibraryEvents();

        // ClassRedefinition, RedefineClasses, RetransformClasses
        if (instrumentation != null) {
            redefineAndRetransform();
        }

        // ReservedStackActivation (deep recursion + lock)
        try { deepRecurse(5_000); } catch (Error e) { /* expected: StackOverflowError */ }

        // ThreadDump — trigger via JFR DiagnosticCommand
        triggerThreadDump();

        // SyncOnValueBasedClass — sync on Integer/Long (value-based with flag enabled)
        triggerSyncOnValueBased();

        // SerializationMisdeclaration — serialize a class without serialVersionUID
        serializationMisdeclaration();

        // HeapDump — trigger via HotSpotDiagnostic dumpHeap
        heapDump();

        // CodeCacheFull — compile many methods with small cache (best-effort)
        triggerCodeCacheFull();

        while (System.currentTimeMillis() < end) {
            allocateAndGC();
            loadClasses();
            monitorContention();
            fileIO();
            fileForce();
            socketIO();
            Thread.sleep(2);
            LockSupport.parkNanos(1_000_000);
            throwAndCatch();
            System.gc();
            // ClassUnload — keep creating class loaders
            classUnload();
        }

        running = false;
        pool.shutdownNow();
        pool.awaitTermination(2, TimeUnit.SECONDS);
        // Shutdown event fires on JVM exit
    }

    // ── Self-attach ──────────────────────────────────────────────────────────

    static void selfAttach() {
        try {
            // Build a tiny agent JAR in temp directory
            Path agentJar = buildAgentJar();
            // Use VirtualMachine.attach(pid) via reflection (tools.jar / attach API)
            Class<?> vmClass = null;
            try {
                vmClass = Class.forName("com.sun.tools.attach.VirtualMachine");
            } catch (ClassNotFoundException e) {
                // Try loading from tools.jar
                String javaHome = System.getProperty("java.home");
                File toolsJar = new File(javaHome, "lib/tools.jar");
                if (!toolsJar.exists()) toolsJar = new File(javaHome, "../lib/tools.jar");
                if (toolsJar.exists()) {
                    URLClassLoader cl = new URLClassLoader(new URL[]{toolsJar.toURI().toURL()});
                    vmClass = cl.loadClass("com.sun.tools.attach.VirtualMachine");
                }
            }
            if (vmClass == null) { System.err.println("[agent] VirtualMachine not available"); return; }
            String pid = ProcessHandle.current().pid() + "";
            Object vm = vmClass.getMethod("attach", String.class).invoke(null, pid);
            vmClass.getMethod("loadAgent", String.class).invoke(vm, agentJar.toString());
            vmClass.getMethod("detach").invoke(vm);
            System.out.println("[agent] Self-attached successfully, instrumentation=" + (instrumentation != null));
        } catch (Exception e) {
            System.err.println("[agent] Self-attach failed: " + e.getMessage());
        }
    }

    static Path buildAgentJar() throws Exception {
        // Write a tiny MANIFEST and the Main class bytecode into a JAR
        Path jar = Files.createTempFile("jfr_agent", ".jar");
        // The agent class is Main itself — just package it as an agent JAR
        // pointing at jfr_sample.Main as Agent-Class
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Agent-Class", "jfr_sample.Main");
        manifest.getMainAttributes().putValue("Premain-Class", "jfr_sample.Main");
        manifest.getMainAttributes().putValue("Can-Redefine-Classes", "true");
        manifest.getMainAttributes().putValue("Can-Retransform-Classes", "true");

        // Find our own class file
        String className = "jfr_sample/Main.class";
        URL classUrl = Main.class.getClassLoader().getResource(className);

        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            jos.putNextEntry(new JarEntry(className));
            if (classUrl != null) {
                try (InputStream is = classUrl.openStream()) {
                    is.transferTo(jos);
                }
            }
            jos.closeEntry();
        }
        return jar;
    }

    // ── Flag changes (triggers *FlagChanged events) ──────────────────────────

    static void changeDiagnosticFlags() {
        try {
            javax.management.MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            javax.management.ObjectName on = new javax.management.ObjectName(
                    "com.sun.management:type=HotSpotDiagnostic");
            // BooleanFlagChanged: toggle a manageable bool flag
            mbs.invoke(on, "setVMOption", new Object[]{"PrintConcurrentLocks", "true"},
                    new String[]{"java.lang.String", "java.lang.String"});
            mbs.invoke(on, "setVMOption", new Object[]{"PrintConcurrentLocks", "false"},
                    new String[]{"java.lang.String", "java.lang.String"});
            // UnsignedLongFlagChanged (uintx on 64-bit): tweak heap ratios
            mbs.invoke(on, "setVMOption", new Object[]{"MinHeapFreeRatio", "45"},
                    new String[]{"java.lang.String", "java.lang.String"});
            mbs.invoke(on, "setVMOption", new Object[]{"MinHeapFreeRatio", "40"},
                    new String[]{"java.lang.String", "java.lang.String"});
            mbs.invoke(on, "setVMOption", new Object[]{"MaxHeapFreeRatio", "75"},
                    new String[]{"java.lang.String", "java.lang.String"});
            mbs.invoke(on, "setVMOption", new Object[]{"MaxHeapFreeRatio", "70"},
                    new String[]{"java.lang.String", "java.lang.String"});
            // StringFlagChanged: HeapDumpPath
            mbs.invoke(on, "setVMOption", new Object[]{"HeapDumpPath", "/tmp/jfr_heap"},
                    new String[]{"java.lang.String", "java.lang.String"});
            // DoubleFlagChanged: G1PeriodicGCSystemLoadThreshold
            mbs.invoke(on, "setVMOption", new Object[]{"G1PeriodicGCSystemLoadThreshold", "0.5"},
                    new String[]{"java.lang.String", "java.lang.String"});
            mbs.invoke(on, "setVMOption", new Object[]{"G1PeriodicGCSystemLoadThreshold", "0.0"},
                    new String[]{"java.lang.String", "java.lang.String"});
            // IntFlagChanged: HeapDumpGzipLevel (int)
            mbs.invoke(on, "setVMOption", new Object[]{"HeapDumpGzipLevel", "1"},
                    new String[]{"java.lang.String", "java.lang.String"});
            mbs.invoke(on, "setVMOption", new Object[]{"HeapDumpGzipLevel", "0"},
                    new String[]{"java.lang.String", "java.lang.String"});
            // UnsignedIntFlagChanged: FullGCHeapDumpLimit (uint)
            mbs.invoke(on, "setVMOption", new Object[]{"FullGCHeapDumpLimit", "1"},
                    new String[]{"java.lang.String", "java.lang.String"});
            mbs.invoke(on, "setVMOption", new Object[]{"FullGCHeapDumpLimit", "0"},
                    new String[]{"java.lang.String", "java.lang.String"});
            // LongFlagChanged: SoftMaxHeapSize (size_t)
            long currentMax = Runtime.getRuntime().maxMemory();
            mbs.invoke(on, "setVMOption", new Object[]{"SoftMaxHeapSize", String.valueOf(currentMax / 2)},
                    new String[]{"java.lang.String", "java.lang.String"});
            mbs.invoke(on, "setVMOption", new Object[]{"SoftMaxHeapSize", String.valueOf(currentMax)},
                    new String[]{"java.lang.String", "java.lang.String"});
            System.out.println("[flags] changeDiagnosticFlags done");
        } catch (Exception e) {
            System.err.println("[flags] changeDiagnosticFlags failed: " + e.getMessage());
        }
    }

    // ── ProcessStart ─────────────────────────────────────────────────────────

    static void startProcess() {
        try {
            Process p = new ProcessBuilder("java", "-version")
                    .redirectErrorStream(true).start();
            p.waitFor(3, TimeUnit.SECONDS);
        } catch (Exception e) { /* ignore */ }
    }

    // ── RedefineClasses / RetransformClasses ─────────────────────────────────

    static void redefineAndRetransform() {
        try {
            // Retransform: no-op transformer, fires RetransformClasses
            ClassFileTransformer noop = new ClassFileTransformer() {
                @Override public byte[] transform(ClassLoader l, String n, Class<?> c,
                        ProtectionDomain pd, byte[] buf) { return null; }
            };
            instrumentation.addTransformer(noop, true);
            instrumentation.retransformClasses(java.util.ArrayList.class);
            instrumentation.removeTransformer(noop);

            // Redefine: replace ArrayList with identical bytes, fires RedefineClasses + ClassRedefinition
            String res = "java/util/ArrayList.class";
            try (InputStream is = ClassLoader.getSystemResourceAsStream(res)) {
                if (is != null) {
                    byte[] bytes = is.readAllBytes();
                    ClassDefinition def = new ClassDefinition(java.util.ArrayList.class, bytes);
                    instrumentation.redefineClasses(def);
                    System.out.println("[redef] RedefineClasses done");
                }
            }
        } catch (Exception e) {
            System.err.println("[redef] failed: " + e.getMessage());
        }
    }

    // ── ThreadDump via JFR DiagnosticCommand ─────────────────────────────────

    static void triggerThreadDump() {
        // jdk.ThreadDump fires periodically — also trigger via JFR diagnostic command
        try {
            javax.management.MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            javax.management.ObjectName on = new javax.management.ObjectName(
                    "com.sun.management:type=DiagnosticCommand");
            mbs.invoke(on, "threadPrint", new Object[]{new String[0]}, new String[]{"[Ljava.lang.String;"});
            System.out.println("[tdump] ThreadDump triggered via DiagnosticCommand");
        } catch (Exception e) {
            // Fallback: use ThreadMXBean
            ThreadMXBean tmx = ManagementFactory.getThreadMXBean();
            sink = tmx.dumpAllThreads(true, true);
            System.out.println("[tdump] ThreadDump via ThreadMXBean (fallback)");
        }
    }

    // ── SyncOnValueBasedClass ─────────────────────────────────────────────────

    @SuppressWarnings("synchronization")
    static void triggerSyncOnValueBased() {
        // With -XX:DiagnoseSyncOnValueBasedClasses=1, syncing on Integer fires the event
        Integer boxed = Integer.valueOf(42);
        for (int i = 0; i < 10; i++) {
            synchronized (boxed) { sink = boxed; }
        }
        Long boxedL = Long.valueOf(123L);
        for (int i = 0; i < 10; i++) {
            synchronized (boxedL) { sink = boxedL; }
        }
        System.out.println("[sync] triggerSyncOnValueBased done");
    }

    // ── ClassUnload ───────────────────────────────────────────────────────────

    static void classUnload() throws Exception {
        // Load a trivial class in a child classloader and let it GC
        ClassLoader cl = new ClassLoader(null) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = makeTrivialClass(name);
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        try {
            String name = "jfr_sample.Unloadable" + System.nanoTime();
            cl.loadClass(name);
        } catch (ClassNotFoundException e) { /* ok */ }
        sink = null;
        System.gc();
    }

    /** Generate minimal valid class file bytes for a class with given binary name, JDK 8 (52.0). */
    static byte[] makeTrivialClass(String binaryName) {
        // Minimal class: version 52 (Java 8), one constant pool entry for class name,
        // public, extends java/lang/Object, no fields, no methods, no attributes
        String internalName = binaryName.replace('.', '/');
        // Constant pool:
        //  #1 = Utf8  <internalName>
        //  #2 = Class #1
        //  #3 = Utf8  "java/lang/Object"
        //  #4 = Class #3
        //  #5 = Utf8  "Code"   -- not strictly needed for empty class
        byte[] nameBytes = internalName.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] objBytes = "java/lang/Object".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream dos = new java.io.DataOutputStream(bos);
        try {
            dos.writeInt(0xCAFEBABE);      // magic
            dos.writeShort(0);             // minor version
            dos.writeShort(52);            // major version (Java 8)
            dos.writeShort(5);             // constant pool count (entries #1..#4)
            // #1 Utf8 internalName
            dos.writeByte(1); dos.writeShort(nameBytes.length); dos.write(nameBytes);
            // #2 Class -> #1
            dos.writeByte(7); dos.writeShort(1);
            // #3 Utf8 "java/lang/Object"
            dos.writeByte(1); dos.writeShort(objBytes.length); dos.write(objBytes);
            // #4 Class -> #3
            dos.writeByte(7); dos.writeShort(3);
            dos.writeShort(0x0021);        // access flags: ACC_PUBLIC | ACC_SUPER
            dos.writeShort(2);             // this class (#2)
            dos.writeShort(4);             // super class (#4)
            dos.writeShort(0);             // interfaces count
            dos.writeShort(0);             // fields count
            dos.writeShort(0);             // methods count
            dos.writeShort(0);             // attributes count
            dos.flush();
        } catch (IOException e) { return null; }
        return bos.toByteArray();
    }

    // ── FinalizerStatistics ───────────────────────────────────────────────────

    static void spawnFinalizable() {
        for (int i = 0; i < 200; i++) {
            sink = new Object() {
                @Override @SuppressWarnings("deprecation")
                protected void finalize() { /* trigger finalizer queue */ }
            };
        }
        sink = null;
        System.gc();
        System.runFinalization();
    }

    // ── NativeLibraryLoad/Unload ──────────────────────────────────────────────

    static void triggerNativeLibraryEvents() {
        // Loading a library that's already loaded is a no-op but the JFR events
        // fire on first load — try some that may not be loaded yet
        String[] libs = { "java", "net", "nio", "zip" };
        for (String lib : libs) {
            try { System.loadLibrary(lib); } catch (UnsatisfiedLinkError e) { /* already loaded or not found */ }
        }
    }

    // ── VirtualThreadPinned ───────────────────────────────────────────────────

    static void startVirtualThreads() throws Exception {
        try {
            ExecutorService vPool = (ExecutorService)
                Executors.class.getMethod("newVirtualThreadPerTaskExecutor").invoke(null);

            // VirtualThreadPinned: synchronized block + blocking op from a virtual thread
            for (int i = 0; i < 10; i++) {
                final int n = i;
                vPool.submit(() -> {
                    try {
                        // Pinned: synchronized + sleep triggers jdk.VirtualThreadPinned
                        synchronized (lock) { Thread.sleep(5); }
                        allocateSmall(n);
                    } catch (InterruptedException e) { /* ok */ }
                });
            }
            vPool.shutdown();
            vPool.awaitTermination(5, TimeUnit.SECONDS);
            System.out.println("[vthread] Virtual thread tasks done");
        } catch (NoSuchMethodException e) { /* JDK < 21 */ }
    }

    // ── ReservedStackActivation ───────────────────────────────────────────────

    static final ReentrantLock deepLock = new ReentrantLock();

    // ReentrantLock methods use @ReservedStackAccess internally.
    // By calling lock() deep in a recursion, we trigger ReservedStackActivation.
    static void deepRecurse(int n) {
        if (n <= 0) {
            deepLock.lock();
            try { sink = Thread.currentThread().getName(); } finally { deepLock.unlock(); }
            return;
        }
        deepRecurse(n - 1);
    }

    // ── CodeCacheFull ─────────────────────────────────────────────────────────

    static void triggerCodeCacheFull() {
        // With -XX:ReservedCodeCacheSize=32m, aggressively JIT-compiling many hot methods
        // may fill the code cache and trigger jdk.CodeCacheFull
        // We generate many large methods to stress the JIT compiler
        try {
            for (int pass = 0; pass < 3; pass++) {
                for (int outer = 0; outer < 300; outer++) {
                    sink = hotMethod1(outer) + hotMethod2(outer) + hotMethod3(outer)
                         + hotMethod4(outer) + hotMethod5(outer) + hotMethod6(outer)
                         + hotMethod7(outer) + hotMethod8(outer);
                }
            }
        } catch (Exception e) { /* ignore */ }
    }

    static int hotMethod1(int n) { int r = 0; for (int i = 0; i < 500; i++) r += i * n + Math.abs(i - n); return r; }
    static int hotMethod2(int n) { int r = 0; for (int i = 0; i < 500; i++) r += (i ^ n) + Integer.bitCount(i); return r; }
    static int hotMethod3(int n) { int r = 0; for (int i = 0; i < 500; i++) r += Integer.reverse(i) + n; return r; }
    static int hotMethod4(int n) { double r = 0; for (int i = 1; i < 300; i++) r += Math.log(i) * n; return (int) r; }
    static int hotMethod5(int n) { double r = 0; for (int i = 1; i < 300; i++) r += Math.sqrt(i) + n; return (int) r; }
    static int hotMethod6(int n) { long r = 0; for (int i = 0; i < 500; i++) r += Long.reverseBytes(i * (long)n); return (int) r; }
    static int hotMethod7(int n) { int r = 0; for (int i = 0; i < 500; i++) r += Integer.numberOfLeadingZeros(i + n) * i; return r; }
    static int hotMethod8(int n) { double r = 1.0; for (int i = 1; i < 300; i++) r = Math.fma(r, Math.cos(i), n); return (int) r; }



    static void serializationMisdeclaration() {
        // Serializing a class that doesn't declare serialVersionUID triggers the event
        // (requires jdk.SerializationMisdeclaration enabled in JFC)
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
                // Use an anonymous class with no serialVersionUID
                oos.writeObject(new java.io.Serializable() {
                    String data = "misdeclaration_test";
                });
            }
        } catch (Exception e) { /* ignore */ }
    }

    // ── HeapDump ──────────────────────────────────────────────────────────────

    static void heapDump() {
        try {
            javax.management.MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            javax.management.ObjectName on = new javax.management.ObjectName(
                    "com.sun.management:type=HotSpotDiagnostic");
            String path = "/tmp/jfr_sample_heap_" + System.nanoTime() + ".hprof";
            mbs.invoke(on, "dumpHeap", new Object[]{path, Boolean.TRUE},
                    new String[]{"java.lang.String", "boolean"});
            new File(path).delete();
            System.out.println("[heap] HeapDump done");
        } catch (Exception e) {
            System.err.println("[heap] HeapDump failed: " + e.getMessage());
        }
    }

    // ── ThreadDump (old stub, removed) ───────────────────────────────────────


    // ── Core helpers ──────────────────────────────────────────────────────────

    static void allocateAndGC() {
        List<byte[]> list = new ArrayList<>(500);
        for (int i = 0; i < 500; i++) list.add(new byte[4096]);
        sink = list;
        sink = new byte[512 * 1024];  // OutsideTLAB
        sink = null;
    }

    static void allocateSmall(int n) {
        Object[] arr = new Object[n + 1];
        for (int i = 0; i <= n; i++) arr[i] = new Object();
        sink = arr;
    }

    static void loadClasses() throws Exception {
        Class.forName("java.util.logging.Logger");
        Class.forName("java.beans.Introspector");
        Class.forName("javax.naming.InitialContext");
    }

    static void monitorContention() throws Exception {
        synchronized (lock) { lock.wait(1); }
        ReentrantLock rl = new ReentrantLock();
        rl.lock();
        try { /* hold */ } finally { rl.unlock(); }
    }

    static File tmpFile;
    static {
        try { tmpFile = File.createTempFile("jfr_sample", ".tmp"); tmpFile.deleteOnExit(); }
        catch (IOException e) { tmpFile = null; }
    }

    static void fileIO() throws Exception {
        if (tmpFile == null) return;
        try (FileOutputStream fos = new FileOutputStream(tmpFile, true)) { fos.write(new byte[1024]); }
        try (FileInputStream fis = new FileInputStream(tmpFile)) { fis.read(new byte[1024]); }
    }

    static void fileForce() throws Exception {
        if (tmpFile == null) return;
        try (FileChannel ch = FileChannel.open(tmpFile.toPath(),
                StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ch.write(java.nio.ByteBuffer.wrap(new byte[64]));
            ch.force(true);
        }
    }

    static void socketIO() {
        try (ServerSocket ss = new ServerSocket(0)) {
            ss.setSoTimeout(200);
            int port = ss.getLocalPort();
            Thread client = new Thread(() -> {
                try (Socket s = new Socket("127.0.0.1", port)) {
                    s.getOutputStream().write(new byte[64]);
                    s.getInputStream().read(new byte[64]);
                } catch (IOException e) { /* ignore */ }
            });
            client.setDaemon(true); client.start();
            try (Socket s = ss.accept()) {
                s.getInputStream().read(new byte[64]);
                s.getOutputStream().write(new byte[64]);
            }
            client.join(500);
        } catch (Exception e) { /* ignore */ }
    }

    static void httpsRequest() {
        try {
            URL url = new URL("https://example.com");
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setConnectTimeout(3000); con.setReadTimeout(3000);
            con.setRequestMethod("GET");
            con.getResponseCode();
            con.disconnect();
        } catch (Exception e) { /* ignore if no network */ }
    }

    static void deserialization() {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
                oos.writeObject(new ArrayList<>(Arrays.asList("a", "b", "c")));
            }
            try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bos.toByteArray()))) {
                sink = ois.readObject();
            }
        } catch (Exception e) { /* ignore */ }
    }

    static void throwAndCatch() {
        for (int i = 0; i < 5; i++) {
            try {
                if (i % 2 == 0) throw new RuntimeException("jfr_sample " + i);
            } catch (RuntimeException e) { /* expected */ }
        }
    }
}
