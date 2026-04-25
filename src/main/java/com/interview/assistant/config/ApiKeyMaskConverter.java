package com.interview.assistant.config;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.pattern.CompositeConverter;

import java.util.regex.Pattern;

/**
 * Logback 日志脱敏转换器
 *
 * 配置在 logback-spring.xml 中，自动将日志里的 Bearer Token、
 * API Key 等敏感信息替换为 ***，防止泄露。
 *
 * 使用方式：在 <pattern> 中用 %maskKey{...} 包裹要脱敏的内容，
 * 或者全局使用 %maskKey 修饰符。
 */
public class ApiKeyMaskConverter extends CompositeConverter<ILoggingEvent> {

    // Bearer Token: Bearer xxxxx
    private static final Pattern BEARER_TOKEN = Pattern.compile(
            "(?i)(bearer\\s+)([a-zA-Z0-9\\-_.~+/]{10,})");

    // Authorization: Bearer xxx
    private static final Pattern AUTH_HEADER = Pattern.compile(
            "(?i)(authorization\\s*[:=]\\s*)(bearer\\s+)?([a-zA-Z0-9\\-_.~+/]{10,})");

    // api-key: xxx / apiKey: xxx
    private static final Pattern API_KEY = Pattern.compile(
            "(?i)(api[_-]?key\\s*[:=]\\s*)([a-zA-Z0-9\\-_.~+/]{8,})");

    // token=xxxx / password=xxxx（长字符串才脱敏）
    private static final Pattern CREDENTIAL = Pattern.compile(
            "(?i)(token\\s*[:=]\\s*)([a-zA-Z0-9\\-_.~+/]{16,})");

    // sk- 开头或 tm- 开头的 key
    private static final Pattern SK_KEY = Pattern.compile(
            "(?i)(sk[-T][a-zA-Z0-9]{20,})");

    @Override
    protected String transform(ILoggingEvent event, String in) {
        if (in == null || in.isEmpty()) {
            return in;
        }
        return mask(in);
    }

    public static String mask(String text) {
        if (text == null) return null;

        String result = text;
        result = BEARER_TOKEN.matcher(result).replaceAll("$1***");
        result = AUTH_HEADER.matcher(result).replaceAll("$1***");
        result = API_KEY.matcher(result).replaceAll("$1***");
        result = CREDENTIAL.matcher(result).replaceAll("$1***");
        result = SK_KEY.matcher(result).replaceAll("***");

        return result;
    }
}
