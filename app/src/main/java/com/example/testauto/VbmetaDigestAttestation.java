package com.example.testauto;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;

import org.bouncycastle.asn1.*;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Enumeration;

public class VbmetaDigestAttestation {

    private static final String TAG = "VBMETA";
    private static final String KEY_ALIAS = "vbmeta_attest_key";

    // Android Key Attestation OID
    private static final String ATTESTATION_OID = "1.3.6.1.4.1.11129.2.1.17";

    public static String getVbmetaDigestHex() {
        try {
            generateAttestedKey();
            byte[] vbmetaDigest = extractVbmetaDigest();

            if (vbmetaDigest == null) {
                Log.e(TAG, "vbmetaDigest not found");
                return null;
            }

            Log.i(TAG, "vbmetaDigest (hex): " + toHex(vbmetaDigest));
            Log.i(TAG, "vbmetaDigest (len): " + vbmetaDigest.length);
            
            return toHex(vbmetaDigest);

        } catch (Exception e) {
            Log.e(TAG, "Error", e);
            return null;
        }
    }

    private static void generateAttestedKey() throws Exception {
        // 先尝试删除旧密钥
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (ks.containsAlias(KEY_ALIAS)) {
                ks.deleteEntry(KEY_ALIAS);
            }
        } catch (Exception e) {
            Log.d(TAG, "Failed to delete old key: " + e.getMessage());
        }
        
        KeyPairGenerator kpg = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_RSA,
                "AndroidKeyStore");

        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setAttestationChallenge("vbmeta_test".getBytes())
                .build();

        kpg.initialize(spec);
        KeyPair kp = kpg.generateKeyPair();
    }

    private static byte[] extractVbmetaDigest() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);

        Certificate[] chain = ks.getCertificateChain(KEY_ALIAS);
        if (chain == null || chain.length == 0) {
            return null;
        }

        X509Certificate leaf = (X509Certificate) chain[0];
        byte[] extBytes = leaf.getExtensionValue(ATTESTATION_OID);
        if (extBytes == null) {
            return null;
        }

        // unwrap OCTET STRING
        ASN1InputStream ais1 = new ASN1InputStream(extBytes);
        ASN1OctetString oct1 = (ASN1OctetString) ais1.readObject();
        ais1.close();

        ASN1InputStream ais2 = new ASN1InputStream(oct1.getOctets());
        ASN1Sequence attestationSeq = (ASN1Sequence) ais2.readObject();
        ais2.close();

        // KeyDescription ::= SEQUENCE {
        //   ...
        //   teeEnforced AuthorizationList (last element)
        // }
        ASN1Sequence teeEnforced =
                (ASN1Sequence) attestationSeq.getObjectAt(attestationSeq.size() - 1);

        Enumeration<?> e = teeEnforced.getObjects();
        while (e.hasMoreElements()) {
            ASN1Encodable obj = (ASN1Encodable) e.nextElement();
            if (!(obj instanceof ASN1TaggedObject)) continue;

            ASN1TaggedObject tagged = (ASN1TaggedObject) obj;

            // vbmetaDigest tag = 704 (Keymaster)
            if (tagged.getTagNo() == 704) {
                ASN1OctetString digest =
                        ASN1OctetString.getInstance(tagged, false);
                return digest.getOctets();
            }
        }

        return null;
    }

    private static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}

