package com.jeqvid.QuestManager;

import android.content.Context;
import android.os.Build;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;

/**
 * Wireless ADB connection to the headset itself (127.0.0.1), using libadb-android.
 * The app's ADB key + certificate are created once and kept in the app's private files,
 * so pairing only has to be done once.
 */
public class AdbManager extends AbsAdbConnectionManager {

    private static AdbManager instance;

    public static synchronized AdbManager get(Context context) throws Exception {
        if (instance == null) instance = new AdbManager(context.getApplicationContext());
        return instance;
    }

    private final PrivateKey privateKey;
    private final Certificate certificate;

    private AdbManager(Context context) throws Exception {
        setApi(Build.VERSION.SDK_INT);
        File keyFile = new File(context.getFilesDir(), "adb_key.pk8");
        File certFile = new File(context.getFilesDir(), "adb_cert.der");
        if (keyFile.exists() && certFile.exists()) {
            privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(read(keyFile)));
            InputStream in = new FileInputStream(certFile);
            try {
                certificate = CertificateFactory.getInstance("X.509").generateCertificate(in);
            } finally {
                in.close();
            }
        } else {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair pair = gen.generateKeyPair();
            privateKey = pair.getPrivate();
            certificate = selfSigned(pair);
            write(keyFile, privateKey.getEncoded());
            write(certFile, certificate.getEncoded());
        }
    }

    private static Certificate selfSigned(KeyPair pair) throws Exception {
        X500Name name = new X500Name("CN=Quest Manager");
        long now = System.currentTimeMillis();
        Date from = new Date(now - 24L * 3600 * 1000);
        Date to = new Date(now + 30L * 365 * 24 * 3600 * 1000);
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(now), from, to, name, pair.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(pair.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    @Override
    protected PrivateKey getPrivateKey() {
        return privateKey;
    }

    @Override
    protected Certificate getCertificate() {
        return certificate;
    }

    @Override
    protected String getDeviceName() {
        return "QuestManager";
    }

    private static byte[] read(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            byte[] data = new byte[(int) f.length()];
            int off = 0;
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n < 0) break;
                off += n;
            }
            return data;
        } finally {
            in.close();
        }
    }

    private static void write(File f, byte[] data) throws IOException {
        OutputStream out = new FileOutputStream(f);
        try {
            out.write(data);
        } finally {
            out.close();
        }
    }
}
