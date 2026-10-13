package dev.payledger.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Per-user API limits, counted in Redis: one user hitting the cap doesn't affect another. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"payledger.rate-limit.per-minute=5", "payledger.rate-limit.writes-per-minute=2"})
@AutoConfigureMockMvc
class RateLimitTest {

    @Autowired MockMvc mvc;

    @BeforeEach
    void notAtTheEndOfAMinute() throws InterruptedException {
        // The window is the calendar minute; don't start a test that could straddle two.
        long second = Instant.now().getEpochSecond() % 60;
        if (second > 50) {
            Thread.sleep((61 - second) * 1000);
        }
    }

    @Test
    void eachUserGetsTheirOwnAllowanceThen429WithRetryAfter() throws Exception {
        var asha = oidcLogin().idToken(t -> t.subject("asha-" + System.nanoTime()));
        var ravi = oidcLogin().idToken(t -> t.subject("ravi-" + System.nanoTime()));
        for (int i = 0; i < 5; i++) {
            mvc.perform(get("/api/session").with(asha)).andExpect(status().isOk());
        }
        mvc.perform(get("/api/session").with(asha))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("rate-limited"));
        mvc.perform(get("/api/session").with(ravi)).andExpect(status().isOk());
    }

    @Test
    void writesHaveALowerCap() throws Exception {
        var meera = oidcLogin().idToken(t -> t.subject("meera-" + System.nanoTime()));
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/session").with(meera).with(csrf())).andExpect(status().is4xxClientError());
        }
        mvc.perform(post("/api/session").with(meera).with(csrf())).andExpect(status().isTooManyRequests());
        // Reads still have room.
        mvc.perform(get("/api/session").with(meera)).andExpect(status().isOk());
    }
}
