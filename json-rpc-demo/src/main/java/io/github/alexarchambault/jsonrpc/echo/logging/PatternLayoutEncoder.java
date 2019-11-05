package io.github.alexarchambault.jsonrpc.demo.logging;

import ch.qos.logback.classic.PatternLayout;

// From https://stackoverflow.com/questions/36875541/process-id-in-logback-logging-pattern/37013689#37013689
public class PatternLayoutEncoder extends ch.qos.logback.classic.encoder.PatternLayoutEncoder {
    @Override
    public void start() {
        PatternLayout.defaultConverterMap.put("pid", ProcessIdConverter.class.getName());
        super.start();
    }
}
