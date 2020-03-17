package com.example.demo;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class HttpRequestTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    public void defaultMood()  {
        Mood expected = new Mood("stehrn", "no mood");
        assertThat(this.restTemplate.getForObject("http://localhost:" + port + "/user/stehrn/mood",
                Mood.class)).isEqualTo(expected);
    }

    @Test
    public void setMood()  {
        String mood  = "happy";
        String user  = "stehrn";

        this.restTemplate.put("http://localhost:" + port + "/user/" + user + "/mood", mood,
                String.class);

        Mood expected = new Mood(user, mood);
        assertThat(this.restTemplate.getForObject("http://localhost:" + port + "/user/" + user + "/mood",
                Mood.class)).isEqualTo(expected);
    }
}
