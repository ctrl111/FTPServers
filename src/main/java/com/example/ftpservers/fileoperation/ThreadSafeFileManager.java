package com.example.ftpservers.fileoperation;

import java.io.*;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class ThreadSafeFileManager {
    private static final Map<String, Object> fileLocks = new ConcurrentHashMap<>();

    private static Object getLock(File file) {
        return fileLocks.computeIfAbsent(file.getAbsolutePath(), k -> new Object());
    }

    public static void readFileBase64(File file, OutputStream rawOut) throws IOException {
        synchronized (getLock(file)) {
            try (
                    BufferedInputStream fileIn = new BufferedInputStream(new FileInputStream(file));
                    OutputStream base64Out = Base64.getEncoder().wrap(rawOut)
            ) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = fileIn.read(buffer)) > 0) {
                    base64Out.write(buffer, 0, count);
                }
                base64Out.flush();
            }
        }
    }

    public static void writeFileBase64(File file, InputStream rawIn) throws IOException {
        synchronized (getLock(file)) {
            try (
                    InputStream base64In = Base64.getDecoder().wrap(rawIn);
                    BufferedOutputStream fileOut = new BufferedOutputStream(new FileOutputStream(file))
            ) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = base64In.read(buffer)) > 0) {
                    fileOut.write(buffer, 0, count);
                }
                fileOut.flush();
            }
        }
    }
}
