package io.github.alexarchambault.jsonrpc.demo.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.lang.management.ManagementFactory;

// From https://stackoverflow.com/questions/36875541/process-id-in-logback-logging-pattern/37013689#37013689
public class ProcessIdConverter extends ClassicConverter {
    private static String PROCESS_ID = null;

    @Override
    public String convert(final ILoggingEvent event) {
        if (PROCESS_ID == null) {
          PROCESS_ID = ManagementFactory.getRuntimeMXBean().getName();
          int idx = PROCESS_ID.indexOf('@');
          if (idx >= 0)
            PROCESS_ID = PROCESS_ID.substring(0, idx);
        }

        return PROCESS_ID;
    }
}
