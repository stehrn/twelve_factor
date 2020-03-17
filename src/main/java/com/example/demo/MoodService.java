package com.example.demo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
class MoodService {

    @Value("${default_mood}")
    private String defaultMood;

    private final Map<String, String> moods = new ConcurrentHashMap<>();

    String getMood(String user) {
        return moods.getOrDefault(user, defaultMood);
    }

    String setMood(String user, String mood) {
        return moods.put(user, mood);
    }
}
