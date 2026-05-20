package com.muevete.bi.etl;

import com.muevete.bi.etl.config.AppConfig;
import com.muevete.bi.etl.job.EtlJob;
import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.TimeZone;

/**
 * Punto de entrada. Arranca el scheduler Quartz con el cron configurado
 * y deja el proceso residente. Para ejecutar una vez inmediatamente usar
 * el flag --run-once.
 */
public class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) throws Exception {
        boolean runOnce = false;
        for (String arg : args) {
            if ("--run-once".equalsIgnoreCase(arg)) runOnce = true;
        }

        log.info("=========================================================");
        log.info("  Muevete BI ETL - iniciando");
        log.info("=========================================================");

        AppConfig cfg = AppConfig.load();
        log.info("Configuracion cargada. Cron='{}' TZ='{}' runOnce={}",
                cfg.scheduler.cron, cfg.scheduler.timezone, runOnce);

        if (runOnce) {
            log.info("Modo --run-once: ejecutando ETL una sola vez y saliendo.");
            try {
                new EtlJob(cfg).run();
                log.info("Ejecucion manual completada con exito.");
                System.exit(0);
            } catch (Exception e) {
                log.error("Ejecucion manual fallo: {}", e.getMessage(), e);
                System.exit(1);
            }
        }

        // Modo servicio: Quartz
        Scheduler scheduler = new StdSchedulerFactory().getScheduler();

        JobDetail jobDetail = JobBuilder.newJob(QuartzEtlJob.class)
                .withIdentity("etlJob", "muevete")
                .build();
        jobDetail.getJobDataMap().put("config", cfg);

        CronTrigger trigger = TriggerBuilder.newTrigger()
                .withIdentity("etlTrigger", "muevete")
                .withSchedule(CronScheduleBuilder.cronSchedule(cfg.scheduler.cron)
                        .inTimeZone(TimeZone.getTimeZone(cfg.scheduler.timezone)))
                .build();

        scheduler.scheduleJob(jobDetail, trigger);
        scheduler.start();

        log.info("Scheduler iniciado. Proximo disparo: {}", trigger.getNextFireTime());

        if (cfg.scheduler.runOnStartup) {
            log.info("runOnStartup=true -> disparando ETL ahora");
            scheduler.triggerJob(jobDetail.getKey());
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Apagando scheduler...");
            try { scheduler.shutdown(true); } catch (SchedulerException ignored) {}
        }));

        // Mantener vivo el proceso
        Thread.currentThread().join();
    }

    public static class QuartzEtlJob implements Job {
        @Override
        public void execute(JobExecutionContext context) {
            AppConfig cfg = (AppConfig) context.getJobDetail().getJobDataMap().get("config");
            try {
                new EtlJob(cfg).run();
            } catch (Exception e) {
                LoggerFactory.getLogger(QuartzEtlJob.class)
                        .error("ETL fallo: {}", e.getMessage(), e);
            }
        }
    }
}
