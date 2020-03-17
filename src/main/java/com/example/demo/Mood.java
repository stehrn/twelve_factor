package com.example.demo;

import java.util.Objects;

public class Mood {

    private final String user;
    private final String mood;

    public Mood(String user, String mood) {
        this.user = user;
        this.mood = mood;
    }

    public String getUser() {
        return user;
    }

    public String getMood() {
        return mood;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Mood mood1 = (Mood) o;
        return Objects.equals(user, mood1.user) &&
                Objects.equals(mood, mood1.mood);
    }

    @Override
    public int hashCode() {
        return Objects.hash(user, mood);
    }

    @Override
    public String toString() {
        return "Mood{" +
                "user='" + user + '\'' +
                ", mood='" + mood + '\'' +
                '}';
    }
}
