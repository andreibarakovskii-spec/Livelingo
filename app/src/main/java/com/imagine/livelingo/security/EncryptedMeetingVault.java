package com.imagine.livelingo.security;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class EncryptedMeetingVault {
    public static final class StoredMeeting {
        public final String id;
        public final long createdAt;
        public final String title;
        public final String payload;
        public StoredMeeting(String id, long createdAt, String title, String payload) {
            this.id=id; this.createdAt=createdAt; this.title=title; this.payload=payload;
        }
    }

    private static final String KEY_ALIAS = "livelingo_meeting_vault_v1";
    private static final int FORMAT_VERSION = 1;
    private final Context context;

    public EncryptedMeetingVault(Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized String save(String title, String payload) throws Exception {
        String id = UUID.randomUUID().toString();
        long createdAt = System.currentTimeMillis();
        byte[] plain = encode(createdAt, title == null ? "Встреча" : title, payload == null ? "" : payload);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(plain);
        File out = new File(dir(), id + ".llv");
        File tmp = new File(dir(), id + ".part");
        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(tmp))) {
            dos.writeInt(FORMAT_VERSION);
            dos.writeInt(iv.length); dos.write(iv);
            dos.writeInt(encrypted.length); dos.write(encrypted);
            dos.flush();
        }
        if (!tmp.renameTo(out)) { tmp.delete(); throw new IllegalStateException("Не удалось сохранить защищённую встречу"); }
        return id;
    }

    public synchronized StoredMeeting load(String id) throws Exception {
        File f = new File(dir(), id + ".llv");
        if (!f.isFile()) return null;
        try (DataInputStream dis = new DataInputStream(new FileInputStream(f))) {
            int version = dis.readInt();
            if (version != FORMAT_VERSION) throw new IllegalStateException("Неподдерживаемая версия защищённого файла");
            int ivLen = dis.readInt();
            if (ivLen < 12 || ivLen > 32) throw new IllegalStateException("Повреждён защищённый файл");
            byte[] iv = new byte[ivLen]; dis.readFully(iv);
            int n = dis.readInt();
            if (n < 16 || n > 100_000_000) throw new IllegalStateException("Повреждён защищённый файл");
            byte[] encrypted = new byte[n]; dis.readFully(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            byte[] plain = cipher.doFinal(encrypted);
            return decode(id, plain);
        }
    }

    public synchronized List<String> ids() {
        File[] files = dir().listFiles((d, name) -> name.endsWith(".llv"));
        if (files == null) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (File f : files) out.add(f.getName().substring(0, f.getName().length()-4));
        return out;
    }

    public synchronized void delete(String id) {
        File f = new File(dir(), id + ".llv");
        if (f.exists()) f.delete();
    }

    public synchronized void wipeAll() {
        File[] files = dir().listFiles();
        if (files != null) for (File f : files) if (f.isFile()) f.delete();
    }

    private File dir() {
        File d = new File(context.getFilesDir(), "secure_meetings");
        if (!d.exists() && !d.mkdirs()) throw new IllegalStateException("Не удалось создать защищённое хранилище");
        return d;
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        java.security.Key key = ks.getKey(KEY_ALIAS, null);
        if (key instanceof SecretKey) return (SecretKey) key;
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build();
        kg.init(spec); return kg.generateKey();
    }

    private static byte[] encode(long createdAt, String title, String payload) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(bos)) {
            byte[] t=title.getBytes(StandardCharsets.UTF_8), p=payload.getBytes(StandardCharsets.UTF_8);
            dos.writeLong(createdAt); dos.writeInt(t.length); dos.write(t); dos.writeInt(p.length); dos.write(p);
        }
        return bos.toByteArray();
    }

    private static StoredMeeting decode(String id, byte[] plain) throws Exception {
        try (DataInputStream dis = new DataInputStream(new java.io.ByteArrayInputStream(plain))) {
            long createdAt=dis.readLong(); int tl=dis.readInt();
            if(tl<0||tl>1_000_000) throw new IllegalStateException("Повреждённые данные");
            byte[] t=new byte[tl];dis.readFully(t); int pl=dis.readInt();
            if(pl<0||pl>100_000_000) throw new IllegalStateException("Повреждённые данные");
            byte[] p=new byte[pl];dis.readFully(p);
            return new StoredMeeting(id,createdAt,new String(t,StandardCharsets.UTF_8),new String(p,StandardCharsets.UTF_8));
        }
    }
}
