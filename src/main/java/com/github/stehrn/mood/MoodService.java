package com.github.stehrn.mood;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
class MoodService {

    private final Map<String, String> moods = new ConcurrentHashMap<>();

    @Value("${mood_not_found_message}")
    private String moodNotFoundMessage;

    String getMood(String user) {
        String mood = moods.get(user);
        if (mood == null) {
            throw new MoodNotSetException(moodNotFoundMessage);
        }
        return mood;
    }

    String setMood(String user, String mood) {
        return moods.put(user, mood);
    }
}
