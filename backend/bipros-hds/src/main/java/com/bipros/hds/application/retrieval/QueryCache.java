package com.bipros.hds.application.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
@Slf4j
@RequiredArgsConstructor
public class QueryCache {

    private final StringRedisTemplate redis;
    private final ObjectMapper om = new ObjectMapper();

    public RetrievalAnswer get(String query, List<UUID> versionIds) {
        String key = key(query, versionIds);
        String val = redis.opsForValue().get(key);
        if (val == null) return null;
        try { return om.readValue(val, RetrievalAnswer.class); }
        catch (Exception e) { log.warn("Cache deserialization failed for {}", key); return null; }
    }

    public void put(String query, List<UUID> versionIds, RetrievalAnswer answer, Duration ttl) {
        String key = key(query, versionIds);
        try {
            String val = om.writeValueAsString(answer);
            redis.opsForValue().set(key, val, ttl);
            for (UUID v : versionIds) {
                redis.opsForSet().add("hds:cache:byversion:" + v, key);
            }
        } catch (Exception e) {
            log.warn("Cache write failed", e);
        }
    }

    public void invalidateForVersion(UUID versionId) {
        Set<String> keys = redis.opsForSet().members("hds:cache:byversion:" + versionId);
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
            redis.delete("hds:cache:byversion:" + versionId);
        }
    }

    private String key(String query, List<UUID> versionIds) {
        var sortedIds = versionIds.stream().map(UUID::toString).sorted().toList();
        String input = query + "||" + String.join(",", sortedIds);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8));
            return "hds:qa:" + HexFormat.of().formatHex(digest);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
