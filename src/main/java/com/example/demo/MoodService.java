package com.example.demo;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
class MoodService {

    private final Map<String, String> moods = new ConcurrentHashMap<>();

    String getMood(String user) {
        return moods.getOrDefault(user, "no mood");
    }

    String setMood(String user, String mood) {
        return moods.put(user, mood);
    }
}
