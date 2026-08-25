package com.imagine.livelingo.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores the xAI key encrypted with an Android Keystore AES-GCM key. */
public final class SecureXaiKeyStore {
    private static final String ALIAS="livelingo_xai_key_v1";
    private static final String PREF="livelingo_secrets";
    private static final String VALUE="xai_api_key";
    private final Context context;

    public SecureXaiKeyStore(Context context){this.context=context.getApplicationContext();}

    public synchronized void save(String apiKey) throws Exception {
        String clean=apiKey==null?"":apiKey.trim();
        if(clean.isEmpty()){clear();return;}
        SecretKey key=getOrCreateKey();
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE,key);
        byte[] cipher=c.doFinal(clean.getBytes(StandardCharsets.UTF_8));
        String packed=Base64.encodeToString(c.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(cipher,Base64.NO_WRAP);
        context.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().putString(VALUE,packed).apply();
    }

    public synchronized String load(){
        try{
            SharedPreferences p=context.getSharedPreferences(PREF,Context.MODE_PRIVATE);
            String packed=p.getString(VALUE,null); if(packed==null||packed.isBlank())return null;
            String[] parts=packed.split(":",2); if(parts.length!=2)return null;
            byte[] iv=Base64.decode(parts[0],Base64.NO_WRAP),data=Base64.decode(parts[1],Base64.NO_WRAP);
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE,getOrCreateKey(),new GCMParameterSpec(128,iv));
            return new String(c.doFinal(data),StandardCharsets.UTF_8);
        }catch(Exception ignored){return null;}
    }

    public synchronized boolean hasKey(){String k=load();return k!=null&&!k.isBlank();}
    public synchronized void clear(){context.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().remove(VALUE).apply();}

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        java.security.Key existing=ks.getKey(ALIAS,null);if(existing instanceof SecretKey)return (SecretKey)existing;
        KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return gen.generateKey();
    }
}
