package com.muevete.bi.etl.util;

import java.io.*;

/**
 * Buffer de filas en disco (serializacion de Object[]) para desacoplar
 * la extraccion de Oracle de la carga en PostgreSQL, dado que el manejo
 * de la VPN implica conectar/desconectar entre ambas operaciones.
 */
public class RowStore implements Closeable {

    private final File file;
    private ObjectOutputStream out;
    private int count;

    public RowStore(File file) throws IOException {
        this.file = file;
        this.out = new ObjectOutputStream(new BufferedOutputStream(new FileOutputStream(file)));
    }

    public void write(Object[] row) throws IOException {
        out.writeObject(row);
        count++;
        if (count % 5000 == 0) {
            out.reset(); // evita memory leak en ObjectOutputStream
        }
    }

    public int count() { return count; }
    public File file() { return file; }

    @Override
    public void close() throws IOException {
        if (out != null) {
            out.flush();
            out.close();
            out = null;
        }
    }

    /** Lector secuencial para reconstruir las filas. */
    public static class Reader implements Closeable {
        private final ObjectInputStream in;

        public Reader(File f) throws IOException {
            this.in = new ObjectInputStream(new BufferedInputStream(new FileInputStream(f)));
        }

        public Object[] next() throws IOException {
            try {
                return (Object[]) in.readObject();
            } catch (EOFException e) {
                return null;
            } catch (ClassNotFoundException e) {
                throw new IOException(e);
            }
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
