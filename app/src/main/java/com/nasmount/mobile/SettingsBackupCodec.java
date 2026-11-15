package com.nasmount.mobile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** Portable, password-encrypted .fhbackup file. No plaintext secrets on disk. */
final class SettingsBackupCodec {
    static final byte[] MAGIC="FHBKUP01".getBytes(StandardCharsets.US_ASCII);
    static final int MAX_PLAIN_BYTES=4*1024*1024;
    static final int MAX_FILE_BYTES=MAX_PLAIN_BYTES+128;
    private static final int SALT_BYTES=16;
    private static final int NONCE_BYTES=12;
    private static final int ITERATIONS=210_000;
    private static final SecureRandom RANDOM=new SecureRandom();
    private SettingsBackupCodec(){}

    static byte[] encrypt(byte[] plain,char[] password) throws Exception {
        checkPassword(password);
        if(plain==null || plain.length==0 || plain.length>MAX_PLAIN_BYTES)
            throw new IllegalArgumentException("백업할 설정 데이터가 너무 큽니다.");
        byte[] salt=new byte[SALT_BYTES], nonce=new byte[NONCE_BYTES];
        RANDOM.nextBytes(salt);RANDOM.nextBytes(nonce);
        byte[] key=derive(password,salt);
        try {
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),
                    new GCMParameterSpec(128,nonce));
            cipher.updateAAD(MAGIC);
            byte[] body=cipher.doFinal(plain);
            ByteArrayOutputStream output=new ByteArrayOutputStream(
                    MAGIC.length+salt.length+nonce.length+body.length);
            output.write(MAGIC);output.write(salt);output.write(nonce);output.write(body);
            return output.toByteArray();
        }finally{Arrays.fill(key,(byte)0);}
    }

    static byte[] decrypt(byte[] encoded,char[] password) throws Exception {
        if(encoded==null || encoded.length<MAGIC.length+SALT_BYTES+NONCE_BYTES+17 ||
                encoded.length>MAX_FILE_BYTES)
            throw new IllegalArgumentException("파일 크기 또는 형식이 올바르지 않습니다.");
        for(int i=0;i<MAGIC.length;i++)
            if(encoded[i]!=MAGIC[i])
                throw new IllegalArgumentException("FileHub 설정 백업 파일이 아닙니다.");
        checkPassword(password);
        int offset=MAGIC.length;
        byte[] salt=Arrays.copyOfRange(encoded,offset,offset+SALT_BYTES);
        offset+=SALT_BYTES;
        byte[] nonce=Arrays.copyOfRange(encoded,offset,offset+NONCE_BYTES);
        offset+=NONCE_BYTES;
        byte[] key=derive(password,salt);
        try {
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),
                    new GCMParameterSpec(128,nonce));
            cipher.updateAAD(MAGIC);
            return cipher.doFinal(encoded,offset,encoded.length-offset);
        }catch(javax.crypto.AEADBadTagException wrong) {
            throw new IllegalArgumentException("비밀번호가 틀렸거나 파일이 손상됐습니다.");
        }finally {Arrays.fill(key,(byte)0);}
    }

    private static void checkPassword(char[] password) {
        if(password==null || password.length<8 || password.length>128)
            throw new IllegalArgumentException("백업 비밀번호는 8~128자로 입력해 주세요.");
    }

    private static byte[] derive(char[] password,byte[] salt) throws Exception {
        PBEKeySpec spec=new PBEKeySpec(password,salt,ITERATIONS,256);
        try{return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).getEncoded();}
        finally{spec.clearPassword();}
    }
}
