package com.ecommerce.userservice.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The health check of Docker and Kubernetes: open to everyone, shows only UP or DOWN, and nothing else is exposed. */
@SpringBootTest
@AutoConfigureMockMvc
class HealthTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    @DisplayName("/actuator/health needs no login and shows only the status")
    void healthIsOpenAndShowsOnlyTheStatus() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist())   // no details about the database
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    @DisplayName("the liveness and readiness probes answer UP")
    void probes() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("no other actuator page is reachable (env, beans, heapdump ...)")
    void nothingElseIsExposed() throws Exception {
        for (String page : new String[]{"env", "beans", "heapdump", "configprops", "mappings", "loggers", "info"}) {
            // 401: not logged in (the page is not open). A logged-in admin would get 404, because it is not exposed at all.
            mockMvc.perform(get("/actuator/" + page)).andExpect(status().is4xxClientError());
        }
    }
}
