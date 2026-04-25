package com.interview.assistant.config;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.Layout;
import ch.qos.logback.core.encoder.LayoutWrappingEncoder;

import java.nio.charset.StandardCharsets;

/**
 * 自动脱敏的 Logback Encoder
 *
 * 包装标准 Layout，自动将日志消息中的 Bearer Token、
 * API Key 等敏感信息替换为 *** 后再输出。
 *
 * 配置在 logback-spring.xml 中使用：
 * <encoder class="MaskingLogbackEncoder"/>
 */
public class MaskingLogbackEncoder extends LayoutWrappingEncoder<ILoggingEvent> {

    @Override
    public byte[] encode(ILoggingEvent event) {
        String msg = event.getFormattedMessage();
        if (msg == null) {
            msg = "";
        }

        String masked = ApiKeyMaskConverter.mask(msg);
        StringBuilder sb = new StringBuilder();
        sb.append(masked);

        // 添加异常信息
        if (event.getThrowableProxy() != null) {
            sb.append(System.lineSeparator());
            writeThrowable(event.getThrowableProxy(), sb);
        }

        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void writeThrowable(IThrowableProxy tp, StringBuilder sb) {
        sb.append(tp.getClassName()).append(": ").append(tp.getMessage())
                .append(System.lineSeparator());
        for (StackTraceElementProxy step : tp.getStackTraceElementProxyArray()) {
            sb.append("  at ").append(step.getSTEAsString()).append(System.lineSeparator());
        }
        if (tp.getCause() != null) {
            sb.append("Caused by: ");
            writeThrowable(tp.getCause(), sb);
        }
    }
}
