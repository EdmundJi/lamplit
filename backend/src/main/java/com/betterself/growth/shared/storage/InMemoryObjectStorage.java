package com.betterself.growth.shared.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(name = "app.object-storage.provider", havingValue = "memory", matchIfMissing = true)
public class InMemoryObjectStorage implements ObjectStorage {

    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    @Override
    public void put(String key, byte[] content, String contentType) {
        objects.put(key, content.clone());
    }

    @Override
    public byte[] get(String key) {
        byte[] value = objects.get(key);
        return value == null ? null : value.clone();
    }

    @Override
    public String presignDownload(String key, Duration ttl) {
        return "/api/v1/privacy/exports/download?objectKey=" + key;
    }

    @Override
    public String presignUpload(String key, String contentType, Duration ttl) {
        return "/api/v1/attachments/upload?objectKey=" + key;
    }

    @Override
    public void delete(String key) {
        objects.remove(key);
    }
}
