package com.forholiday.galaxyreset;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.*;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.*;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/** One identity per installation, encrypted at rest and excluded from backup/reset history. */
final class AdbCredentials {
    private static final String ALIAS = "galaxy_reset_local_adb_v1";
    final PrivateKey key;
    final X509Certificate certificate;
    private AdbCredentials(PrivateKey key, X509Certificate certificate) { this.key=key; this.certificate=certificate; }

    static X509Certificate certificate(KeyPair pair, long now) throws Exception {
        X500Name name=new X500Name("CN=Galaxy Reset");
        var builder=new JcaX509v3CertificateBuilder(name,
                new BigInteger(160,new SecureRandom()).add(BigInteger.ONE),
                new Date(now-86400000L),new Date(now+20L*365*86400000L),name,pair.getPublic());
        var signer=new JcaContentSignerBuilder("SHA256withRSA").setProvider(new BouncyCastleProvider()).build(pair.getPrivate());
        X509Certificate cert=new JcaX509CertificateConverter().getCertificate(builder.build(signer));
        cert.verify(pair.getPublic());
        return cert;
    }

    static synchronized AdbCredentials load(Context context) throws Exception {
        AtomicFile file=new AtomicFile(new File(context.getNoBackupFilesDir(),"local-adb-identity.bin"));
        KeyStore store=KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        SecretKey secret=(SecretKey)store.getKey(ALIAS,null);
        if(file.getBaseFile().exists()) {
            if(secret==null)throw new IOException("자동 연결 인증 키를 읽을 수 없습니다. 앱 재설치 없이 관리자에게 확인해주세요.");
            byte[] blob=file.readFully();
            if(blob.length<29||blob.length>32768)throw new IOException("자동 연결 인증 파일이 올바르지 않습니다.");
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,secret,new GCMParameterSpec(128,blob,0,12));
            cipher.updateAAD(BuildConfig.APPLICATION_ID.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            try(DataInputStream input=new DataInputStream(new ByteArrayInputStream(cipher.doFinal(blob,12,blob.length-12)))) {
                if(input.readInt()!=1)throw new IOException("지원하지 않는 자동 연결 인증 형식입니다.");
                byte[] key=new byte[input.readUnsignedShort()];input.readFully(key);
                byte[] cert=new byte[input.readUnsignedShort()];input.readFully(cert);
                if(input.available()!=0)throw new IOException("자동 연결 인증 파일이 올바르지 않습니다.");
                PrivateKey privateKey=KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(key));
                X509Certificate certificate=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(cert));
                return new AdbCredentials(privateKey,certificate);
            }
        }
        if(secret==null) {
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
            secret=generator.generateKey();
        }
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        KeyPair pair=generator.generateKeyPair(); X509Certificate certificate=certificate(pair,System.currentTimeMillis());
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(DataOutputStream output=new DataOutputStream(bytes)) {
            byte[] key=pair.getPrivate().getEncoded(),cert=certificate.getEncoded();
            output.writeInt(1);output.writeShort(key.length);output.write(key);output.writeShort(cert.length);output.write(cert);
        }
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,secret);
        cipher.updateAAD(BuildConfig.APPLICATION_ID.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        FileOutputStream output=file.startWrite();
        try { output.write(cipher.getIV());output.write(cipher.doFinal(bytes.toByteArray()));file.finishWrite(output); }
        catch(Exception error) { file.failWrite(output);throw error; }
        return new AdbCredentials(pair.getPrivate(),certificate);
    }
}
