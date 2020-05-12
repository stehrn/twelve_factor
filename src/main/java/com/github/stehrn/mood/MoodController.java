package com.github.stehrn.mood;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
public class MoodController {

    @Autowired
    private MoodService moodService;

    @GetMapping("/user/{name}/mood")
    public Mood getMood(@PathVariable(value = "name") String name) {
        return moodService.getMood(name);
    }

    @PutMapping("/user/{name}/mood")
    void setMood(@RequestBody String mood, @PathVariable String name) {
        moodService.setMood(name, mood);
    }
}
