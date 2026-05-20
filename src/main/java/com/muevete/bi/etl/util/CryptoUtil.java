package com.muevete.bi.etl.util;

import org.jasypt.util.text.BasicTextEncryptor;

/**
 * Utilidad para cifrar/descifrar contrasenas.
 *
 *  Uso por linea de comandos (para generar el valor a poner en application.yml):
 *      java -cp muevete-bi-etl.jar com.muevete.bi.etl.util.CryptoUtil encrypt "miPassword"
 *      java -cp muevete-bi-etl.jar com.muevete.bi.etl.util.CryptoUtil decrypt "xxxxxxxx"
 *
 *  La master key debe estar en la variable de entorno MUEVETE_BI_MASTER_KEY.
 *  Si no esta definida, se usa una por defecto (solo para pruebas, NO produccion).
 */
public class CryptoUtil {

    private static final String ENV_KEY = "MUEVETE_BI_MASTER_KEY";
    private static final String DEFAULT_KEY = "muevete-bi-default-do-not-use-in-prod";

    private static BasicTextEncryptor encryptor() {
        String master = System.getenv(ENV_KEY);
        if (master == null || master.isBlank()) master = DEFAULT_KEY;
        BasicTextEncryptor e = new BasicTextEncryptor();
        e.setPassword(master);
        return e;
    }

    public static String encrypt(String plain) {
        return "ENC(" + encryptor().encrypt(plain) + ")";
    }

    public static String decrypt(String enc) {
        String payload = enc;
        if (payload.startsWith("ENC(") && payload.endsWith(")")) {
            payload = payload.substring(4, payload.length() - 1);
        }
        return encryptor().decrypt(payload);
    }

    /** Si el valor viene como ENC(...) lo descifra, de lo contrario lo retorna como esta. */
    public static String decryptIfNeeded(String value) {
        if (value == null) return null;
        if (value.startsWith("ENC(") && value.endsWith(")")) return decrypt(value);
        return value;
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Uso: CryptoUtil <encrypt|decrypt> <texto>");
            System.exit(1);
        }
        String op = args[0];
        String txt = args[1];
        switch (op.toLowerCase()) {
            case "encrypt" -> System.out.println(encrypt(txt));
            case "decrypt" -> System.out.println(decrypt(txt));
            default -> System.out.println("Operacion desconocida: " + op);
        }
    }
}
