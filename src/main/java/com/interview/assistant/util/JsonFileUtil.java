package com.interview.assistant.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
@Component
public class JsonFileUtil {

    private final ObjectMapper objectMapper;
    private String dataDir;

    public JsonFileUtil() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @PostConstruct
    public void init() {
        this.dataDir = System.getProperty("app.data-dir", "data");
        try {
            Files.createDirectories(Paths.get(dataDir));
        } catch (IOException e) {
            log.error("创建数据目录失败", e);
        }
    }

    public <T> T readJson(String filename, Class<T> clazz) {
        return readJson(filename, clazz, null);
    }

    public <T> T readJson(String filename, Class<T> clazz, T defaultValue) {
        Path path = Paths.get(dataDir, filename);
        if (!Files.exists(path)) {
            return defaultValue;
        }
        try {
            return objectMapper.readValue(path.toFile(), clazz);
        } catch (IOException e) {
            log.error("读取JSON文件失败: {}", path, e);
            return defaultValue;
        }
    }

    public <T> void writeJson(String filename, T data) {
        Path path = Paths.get(dataDir, filename);
        try {
            Files.createDirectories(path.getParent());
            objectMapper.writeValue(path.toFile(), data);
        } catch (IOException e) {
            log.error("写入JSON文件失败: {}", path, e);
        }
    }

    public String getDataDir() {
        return dataDir;
    }
}
