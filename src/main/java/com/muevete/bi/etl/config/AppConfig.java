package com.muevete.bi.etl.config;

import com.muevete.bi.etl.util.CryptoUtil;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Map;

/**
 * Configuracion global. Carga application.yml desde (en orden):
 *  1. System property -Dconfig.file=...
 *  2. ./config/application.yml
 *  3. Classpath /application.yml
 * Descifra automaticamente cualquier valor con formato ENC(...).
 */
public class AppConfig {

    public Scheduler scheduler = new Scheduler();
    public Postgres postgres = new Postgres();
    public Oracle oracle = new Oracle();
    public Etl etl = new Etl();

    public static class Scheduler {
        public String cron = "0 0 18 * * ?";
        public String timezone = "America/Bogota";
        public boolean runOnStartup = false;
    }

    public static class Postgres {
        public String host;
        public int port = 5432;
        public String database;
        public String user;
        public String passwordEnc;
        public String schema = "public";
        public String controlTable;
        public String maestraTable;
        public String recaudoTable;
        public String carteraTable;

        public String jdbcUrl() {
            return "jdbc:postgresql://" + host + ":" + port + "/" + database;
        }
    }

    public static class Oracle {
        public String host;
        public int port = 1521;
        public String sid;
        public String user;
        public String passwordEnc;
        public String stagingTable;
        public int fetchSize = 5000;
        public int batchSize = 5000;

        public String jdbcUrl() {
            return "jdbc:oracle:thin:@" + host + ":" + port + ":" + sid;
        }
    }

    public static class Etl {
        public int insertBatchSize = 5000;
        public boolean truncateBeforeInsert = true;
        public String procesoNombre = "ETL_MUEVETE_BI_TURBACO";
    }

    @SuppressWarnings("unchecked")
    public static AppConfig load() throws Exception {
        InputStream in = null;
        String override = System.getProperty("config.file");
        if (override != null) {
            in = new FileInputStream(override);
        } else {
            File f = new File("config/application.yml");
            if (f.exists()) in = new FileInputStream(f);
            else in = AppConfig.class.getResourceAsStream("/application.yml");
        }
        if (in == null) throw new IllegalStateException("No se encontro application.yml");

        Yaml yaml = new Yaml();
        Map<String, Object> root = yaml.load(in);
        in.close();

        AppConfig cfg = new AppConfig();
        applyScheduler(cfg.scheduler, (Map<String, Object>) root.get("scheduler"));
        applyPostgres(cfg.postgres, (Map<String, Object>) root.get("postgres"));
        applyOracle(cfg.oracle, (Map<String, Object>) root.get("oracle"));
        applyEtl(cfg.etl, (Map<String, Object>) root.get("etl"));
        return cfg;
    }

    private static void applyScheduler(Scheduler s, Map<String, Object> m) {
        if (m == null) return;
        if (m.get("cron") != null) s.cron = (String) m.get("cron");
        if (m.get("timezone") != null) s.timezone = (String) m.get("timezone");
        if (m.get("runOnStartup") != null) s.runOnStartup = (Boolean) m.get("runOnStartup");
    }

    private static void applyPostgres(Postgres p, Map<String, Object> m) {
        if (m == null) return;
        p.host = (String) m.get("host");
        p.port = (int) m.getOrDefault("port", 5432);
        p.database = (String) m.get("database");
        p.user = (String) m.get("user");
        p.passwordEnc = (String) m.get("passwordEnc");
        p.schema = (String) m.getOrDefault("schema", "public");
        p.controlTable = (String) m.get("controlTable");
        p.maestraTable = (String) m.get("maestraTable");
        p.recaudoTable = (String) m.get("recaudoTable");
        p.carteraTable = (String) m.get("carteraTable");
    }

    private static void applyOracle(Oracle o, Map<String, Object> m) {
        if (m == null) return;
        o.host = (String) m.get("host");
        o.port = (int) m.getOrDefault("port", 1521);
        o.sid = (String) m.get("sid");
        o.user = (String) m.get("user");
        o.passwordEnc = (String) m.get("passwordEnc");
        o.stagingTable = (String) m.get("stagingTable");
        o.fetchSize = (int) m.getOrDefault("fetchSize", 5000);
        o.batchSize = (int) m.getOrDefault("batchSize", 5000);
    }

    private static void applyEtl(Etl e, Map<String, Object> m) {
        if (m == null) return;
        e.insertBatchSize = (int) m.getOrDefault("insertBatchSize", 5000);
        e.truncateBeforeInsert = (boolean) m.getOrDefault("truncateBeforeInsert", true);
        e.procesoNombre = (String) m.getOrDefault("procesoNombre", "ETL_MUEVETE_BI_TURBACO");
    }

    public String postgresPassword(){ return CryptoUtil.decryptIfNeeded(postgres.passwordEnc); }
    public String oraclePassword()  { return CryptoUtil.decryptIfNeeded(oracle.passwordEnc); }
}
