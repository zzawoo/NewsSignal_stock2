package com.newssignal.common;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/** 비밀번호 해싱(PBKDF2-HMAC-SHA256, 순수 JDK8). 저장형식: pbkdf2$iter$salt$hash */
public final class PasswordUtil {

    private static final SecureRandom RNG = new SecureRandom();
    private static final int ITER = 100_000;
    private static final int KEY_BITS = 256;

    public static String hash(String password) {
        byte[] salt = new byte[16];
        RNG.nextBytes(salt);
        byte[] dk = pbkdf2(password, salt, ITER, KEY_BITS);
        return "pbkdf2$" + ITER + "$" + b64(salt) + "$" + b64(dk);
    }

    public static boolean verify(String password, String stored) {
        try {
            String[] p = stored.split("\\$");
            if (p.length != 4) return false;
            int iter = Integer.parseInt(p[1]);
            byte[] salt = unb64(p[2]);
            byte[] expected = unb64(p[3]);
            byte[] dk = pbkdf2(password, salt, iter, expected.length * 8);
            return constantEquals(dk, expected);
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] pbkdf2(String pw, byte[] salt, int iter, int bits) {
        try {
            PBEKeySpec spec = new PBEKeySpec(pw.toCharArray(), salt, iter, bits);
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return f.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String b64(byte[] b) { return Base64.getEncoder().encodeToString(b); }
    private static byte[] unb64(String s) { return Base64.getDecoder().decode(s); }

    private static boolean constantEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int r = 0;
        for (int i = 0; i < a.length; i++) r |= a[i] ^ b[i];
        return r == 0;
    }

    private PasswordUtil() {}
}
