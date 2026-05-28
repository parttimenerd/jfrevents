package jfr_sample;

import java.util.ArrayList;

/**
 * Minimal JFR event generator for GraalVM Native Image recording.
 * Runs ~20 seconds, allocating objects to trigger GC/thread/memory JFR events.
 */
public class Main {
    public static void main(String[] args) throws Exception {
        long end = System.currentTimeMillis() + 20_000;
        ArrayList<byte[]> list = new ArrayList<>();
        while (System.currentTimeMillis() < end) {
            list.add(new byte[1024]);
            if (list.size() > 1000) {
                list.clear();
            }
            Thread.sleep(1);
        }
    }
}
