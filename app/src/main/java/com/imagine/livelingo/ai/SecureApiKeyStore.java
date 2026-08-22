package com.imagine.livelingo.ai;

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

/** Stores the xAI key encrypted with an app-private Android Keystore AES key. */
public final class SecureApiKeyStore {
    private static final String ALIAS="livelingo_xai_key_v1";
    private static final String PREFS="livelingo_ai_secure";
    private static final String CIPHER="xai_cipher";
    private static final String IV="xai_iv";
    private final SharedPreferences prefs;

    public SecureApiKeyStore(Context context){prefs=context.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);}

    public synchronized boolean hasKey(){return !load().isBlank();}

    public synchronized void save(String apiKey){
        String clean=apiKey==null?"":apiKey.trim();
        if(clean.isBlank()){clear();return;}
        try{
            SecretKey key=getOrCreateKey();
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,key);
            byte[] encrypted=cipher.doFinal(clean.getBytes(StandardCharsets.UTF_8));
            prefs.edit().putString(CIPHER,Base64.encodeToString(encrypted,Base64.NO_WRAP)).putString(IV,Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)).apply();
        }catch(Exception e){throw new IllegalStateException("Не удалось защитить API-ключ",e);}
    }

    public synchronized String load(){
        String c=prefs.getString(CIPHER,null),iv=prefs.getString(IV,null);
        if(c==null||iv==null)return "";
        try{
            KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
            SecretKey key=(SecretKey)ks.getKey(ALIAS,null);if(key==null)return "";
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Base64.decode(iv,Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(c,Base64.NO_WRAP)),StandardCharsets.UTF_8);
        }catch(Exception e){return "";}
    }

    public synchronized void clear(){prefs.edit().remove(CIPHER).remove(IV).apply();}

    private SecretKey getOrCreateKey() throws Exception{
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        SecretKey existing=(SecretKey)ks.getKey(ALIAS,null);if(existing!=null)return existing;
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
        return generator.generateKey();
    }
}
