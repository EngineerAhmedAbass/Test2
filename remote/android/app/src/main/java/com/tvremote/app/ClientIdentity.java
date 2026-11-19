package com.tvremote.app;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.math.BigInteger;
import java.net.Socket;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.security.spec.RSAKeyGenParameterSpec;
import java.util.Calendar;

import javax.net.ssl.KeyManager;
import javax.net.ssl.X509KeyManager;
import javax.security.auth.x500.X500Principal;

/**
 * هوية الموبايل قدام التليفزيون: مفتاح RSA وشهادة متخزنين في AndroidKeyStore.
 * التليفزيون بيفتكر الشهادة دي بعد الاقتران، فمش محتاج تعمل اقتران تاني.
 */
final class ClientIdentity {
    private static final String ALIAS = "atvremote";

    final X509Certificate cert;
    final KeyManager[] keyManagers;

    private ClientIdentity(X509Certificate cert, KeyManager[] km) {
        this.cert = cert;
        this.keyManagers = km;
    }

    static ClientIdentity load() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) generate();
        final PrivateKey key = (PrivateKey) ks.getKey(ALIAS, null);
        final X509Certificate cert = (X509Certificate) ks.getCertificate(ALIAS);
        final X509Certificate[] chain = {cert};
        X509KeyManager km = new X509KeyManager() {
            public String[] getClientAliases(String keyType, Principal[] issuers) { return new String[]{ALIAS}; }
            public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) { return ALIAS; }
            public String[] getServerAliases(String keyType, Principal[] issuers) { return null; }
            public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) { return null; }
            public X509Certificate[] getCertificateChain(String alias) { return chain; }
            public PrivateKey getPrivateKey(String alias) { return key; }
        };
        return new ClientIdentity(cert, new KeyManager[]{km});
    }

    private static void generate() throws Exception {
        Calendar start = Calendar.getInstance();
        start.add(Calendar.DAY_OF_YEAR, -1);
        Calendar end = Calendar.getInstance();
        end.add(Calendar.YEAR, 20);
        KeyPairGenerator g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
        g.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_DECRYPT)
                .setAlgorithmParameterSpec(new RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA1, KeyProperties.DIGEST_SHA256,
                        KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE, KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                .setCertificateSubject(new X500Principal("CN=atvremote, O=TV Remote"))
                .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
                .setCertificateNotBefore(start.getTime())
                .setCertificateNotAfter(end.getTime())
                .build());
        g.generateKeyPair();
    }

    /** بيمسح الهوية (لو حصلت مشكلة غريبة) — بعدها لازم اقتران من جديد. */
    static void reset() {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            ks.deleteEntry(ALIAS);
        } catch (Exception ignored) { }
    }
}
