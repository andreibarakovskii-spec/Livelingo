package com.imagine.livelingo.stt;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;

public final class ModelManager {
    public interface Listener {
        void onProgress(int percent, String message);
        void onReady();
        void onError(String message);
    }

    public static final class ModelFile {
        public final String name;
        public final String url;
        public final String sha256;
        public ModelFile(String name, String url, String sha256) {
            this.name = name; this.url = url; this.sha256 = sha256 == null ? "" : sha256;
        }
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());

    public ModelManager(Context context) { this.context = context.getApplicationContext(); }

    public File modelDir() {
        File dir = new File(context.getFilesDir(), "models/whisper");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public boolean isInstalled(List<ModelFile> files) {
        for (ModelFile f : files) if (!new File(modelDir(), f.name).isFile()) return false;
        return !files.isEmpty();
    }

    public long installedBytes() {
        long total = 0;
        File[] fs = modelDir().listFiles();
        if (fs != null) for (File f : fs) total += f.length();
        return total;
    }

    public void deleteInstalled() {
        File[] fs = modelDir().listFiles();
        if (fs != null) for (File f : fs) f.delete();
    }

    public void install(List<ModelFile> files, Listener listener) {
        new Thread(() -> {
            try {
                if (files == null || files.isEmpty()) throw new IllegalStateException("Пакет модели не настроен");
                int index = 0;
                for (ModelFile mf : files) {
                    index++;
                    File out = new File(modelDir(), mf.name);
                    File tmp = new File(modelDir(), mf.name + ".part");
                    download(mf.url, tmp, index, files.size(), listener);
                    if (!mf.sha256.isBlank() && !mf.sha256.equalsIgnoreCase(sha256(tmp))) {
                        tmp.delete(); throw new IllegalStateException("Ошибка проверки файла " + mf.name);
                    }
                    if (out.exists()) out.delete();
                    if (!tmp.renameTo(out)) throw new IllegalStateException("Не удалось установить " + mf.name);
                }
                main.post(listener::onReady);
            } catch (Exception e) {
                main.post(() -> listener.onError(e.getMessage() == null ? "Ошибка установки модели" : e.getMessage()));
            }
        }, "model-installer").start();
    }

    private void download(String address, File out, int index, int totalFiles, Listener listener) throws Exception {
        if (address == null || address.isBlank()) throw new IllegalStateException("Не указан адрес модели");
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(30000); c.setInstanceFollowRedirects(true); c.connect();
        if (c.getResponseCode() / 100 != 2) throw new IllegalStateException("Сервер модели: HTTP " + c.getResponseCode());
        long length = c.getContentLengthLong();
        try (InputStream in = c.getInputStream(); FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[64 * 1024]; long read = 0; int n;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n); read += n;
                int filePct = length > 0 ? (int)Math.min(100, read * 100 / length) : 0;
                int overall = (int)(((index - 1) * 100L + filePct) / totalFiles);
                main.post(() -> listener.onProgress(overall, "AI-модель: файл " + index + " из " + totalFiles));
            }
        } finally { c.disconnect(); }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new java.io.FileInputStream(file)) {
            byte[] b = new byte[64 * 1024]; int n; while ((n = in.read(b)) > 0) md.update(b, 0, n);
        }
        StringBuilder s = new StringBuilder(); for (byte v : md.digest()) s.append(String.format("%02x", v)); return s.toString();
    }

    public static List<ModelFile> defaultBundle() {
        return Arrays.asList(
            new ModelFile("Whisper_initializer.onnx", "", ""),
            new ModelFile("Whisper_encoder.onnx", "", ""),
            new ModelFile("Whisper_decoder.onnx", "", ""),
            new ModelFile("Whisper_cache_initializer.onnx", "", ""),
            new ModelFile("Whisper_cache_initializer_batch.onnx", "", ""),
            new ModelFile("Whisper_detokenizer.onnx", "", "")
        );
    }
}
