package com.betterself.growth.shared.storage;

import java.time.Duration;

public interface ObjectStorage {
    void put(String key, byte[] content, String contentType);

    byte[] get(String key);

    String presignDownload(String key, Duration ttl);

    String presignUpload(String key, String contentType, Duration ttl);

    void delete(String key);
}
