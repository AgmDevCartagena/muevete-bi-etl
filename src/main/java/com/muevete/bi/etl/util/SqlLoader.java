package com.muevete.bi.etl.util;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Carga las consultas .sql desde el directorio sql/ o del classpath. */
public class SqlLoader {

    public static String load(String name) {
        try {
            File f = new File("sql/" + name);
            if (f.exists()) return Files.readString(f.toPath(), StandardCharsets.UTF_8);
            // Fallback a classpath
            var in = SqlLoader.class.getResourceAsStream("/sql/" + name);
            if (in != null) return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            throw new IllegalStateException("No se encontro el archivo SQL: " + name);
        } catch (Exception e) {
            throw new RuntimeException("Error leyendo SQL '" + name + "': " + e.getMessage(), e);
        }
    }
}
