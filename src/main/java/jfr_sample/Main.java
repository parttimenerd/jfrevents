package jfr_sample;

import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

/**
 * Maximalist JFR event generator for GraalVM recordings.
 * Triggers as many Java-level JFR event types as possible.
 */
public class Main {

    static volatile Object sink;
    static final Object lock = new Object();

    public static void main(String[] args) throws Exception {
        long end = System.currentTimeMillis() + 20_000;

        // ThreadStart/ThreadEnd, ThreadCPULoad, ThreadAllocationStatistics
        ExecutorService pool = Executors.newFixedThreadPool(4);
        for (int t = 0; t < 4; t++) {
            pool.submit(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    allocateAndGC();
                    try { Thread.sleep(5); } catch (InterruptedException e) { break; }
                }
            });
        }

        // VirtualThreadStart, VirtualThreadEnd
        try {
            ExecutorService vPool = (ExecutorService)
                Executors.class.getMethod("newVirtualThreadPerTaskExecutor").invoke(null);
            for (int i = 0; i < 20; i++) {
                final int n = i;
                vPool.submit(() -> {
                    try { Thread.sleep(1); } catch (InterruptedException e) {}
                    allocateSmall(n);
                });
            }
            vPool.shutdown();
            vPool.awaitTermination(5, TimeUnit.SECONDS);
        } catch (NoSuchMethodException e) { /* JDK < 21 */ }

        // ThreadDump (trigger once at start)
        triggerThreadDump();

        // TLSHandshake, X509Certificate, X509Validation (HTTPS)
        httpsRequest();

        // NativeMemoryUsage (requires NMT — best-effort, may be 0 without flag)
        // Deserialization
        deserialization();

        while (System.currentTimeMillis() < end) {
            // GarbageCollection, GCHeapSummary, G1*, ObjectAllocation*, Promote*
            allocateAndGC();

            // ClassLoad, ClassDefine
            loadClasses();

            // JavaMonitorEnter, JavaMonitorWait, JavaMonitorInflate, JavaMonitorNotify
            monitorContention();

            // FileRead, FileWrite
            fileIO();

            // FileForce (FileChannel.force)
            fileForce();

            // SocketRead, SocketWrite
            socketIO();

            // ThreadSleep
            Thread.sleep(2);

            // ThreadPark
            LockSupport.parkNanos(1_000_000);

            // JavaExceptionThrow
            throwAndCatch();

            // SystemGC → GarbageCollection, GCHeapSummary
            System.gc();
        }

        pool.shutdownNow();
        pool.awaitTermination(2, TimeUnit.SECONDS);
    }

    static void allocateAndGC() {
        List<byte[]> list = new ArrayList<>(500);
        for (int i = 0; i < 500; i++) list.add(new byte[4096]);
        sink = list;
        // Large single alloc → ObjectAllocationOutsideTLAB
        sink = new byte[512 * 1024];
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
        try (FileOutputStream fos = new FileOutputStream(tmpFile, true)) {
            fos.write(new byte[1024]);
        }
        try (FileInputStream fis = new FileInputStream(tmpFile)) {
            //noinspection ResultOfMethodCallIgnored
            fis.read(new byte[1024]);
        }
    }

    static void fileForce() throws Exception {
        if (tmpFile == null) return;
        try (FileChannel ch = FileChannel.open(tmpFile.toPath(),
                StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ch.write(java.nio.ByteBuffer.wrap(new byte[64]));
            ch.force(true);  // → jdk.FileForce
        }
    }

    static void socketIO() {
        try (ServerSocket ss = new ServerSocket(0)) {
            ss.setSoTimeout(200);
            int port = ss.getLocalPort();
            Thread client = new Thread(() -> {
                try (Socket s = new Socket("127.0.0.1", port)) {
                    s.getOutputStream().write(new byte[64]);
                    //noinspection ResultOfMethodCallIgnored
                    s.getInputStream().read(new byte[64]);
                } catch (IOException e) { /* ignore */ }
            });
            client.setDaemon(true);
            client.start();
            try (Socket s = ss.accept()) {
                //noinspection ResultOfMethodCallIgnored
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
            con.setConnectTimeout(3000);
            con.setReadTimeout(3000);
            con.setRequestMethod("GET");
            con.getResponseCode();
            con.disconnect();
        } catch (Exception e) { /* ignore if no network */ }
    }

    static void deserialization() {
        try {
            // Serialize then deserialize a simple object → jdk.Deserialization
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
                oos.writeObject(new ArrayList<>(Arrays.asList("a", "b", "c")));
            }
            try (ObjectInputStream ois = new ObjectInputStream(
                    new ByteArrayInputStream(bos.toByteArray()))) {
                sink = ois.readObject();
            }
        } catch (Exception e) { /* ignore */ }
    }

    static void triggerThreadDump() {
        // jdk.ThreadDump is periodic — force a chunk boundary by doing a lot of work
        for (int i = 0; i < 10; i++) {
            allocateAndGC();
            System.gc();
        }
    }

    static void throwAndCatch() {
        for (int i = 0; i < 5; i++) {
            try {
                if (i % 2 == 0) throw new RuntimeException("jfr_sample exception " + i);
            } catch (RuntimeException e) { /* expected */ }
        }
    }
}
