package com.platform.subscription_manager.shared;

import com.platform.subscription_manager.shared.domain.exceptions.ConflictException;
import com.platform.subscription_manager.shared.config.GlobalExceptionHandler;
import com.platform.subscription_manager.shared.domain.exceptions.ResourceNotFoundException;
import com.platform.subscription_manager.shared.domain.exceptions.UnprocessableEntityException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("GlobalExceptionHandler")
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @RestController
    static class StubController {
        record Input(@NotBlank String value) {}

        @GetMapping("/stub/not-found")
        void notFound() { throw new ResourceNotFoundException("resource not found"); }

        @GetMapping("/stub/conflict")
        void conflict() { throw new ConflictException("already exists"); }

        @GetMapping("/stub/unprocessable")
        void unprocessable() { throw new UnprocessableEntityException("invariant violated"); }

        @GetMapping("/stub/error")
        void error() { throw new RuntimeException("boom"); }

        @PostMapping("/stub/validate")
        void validate(@Valid @RequestBody Input ignored) {}
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
            .standaloneSetup(new StubController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Nested
    @DisplayName("404 Not Found")
    class NotFound {

        @Test
        @DisplayName("Returns 404 with the exception message as detail")
        void returns404() throws Exception {
            mockMvc.perform(get("/stub/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.detail").value("resource not found"));
        }
    }

    @Nested
    @DisplayName("409 Conflict")
    class Conflict {

        @Test
        @DisplayName("Returns 409 with the exception message as detail")
        void returns409() throws Exception {
            mockMvc.perform(get("/stub/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.detail").value("already exists"));
        }
    }

    @Nested
    @DisplayName("422 Unprocessable Entity")
    class Unprocessable {

        @Test
        @DisplayName("Returns 422 with the exception message as detail")
        void returns422() throws Exception {
            mockMvc.perform(get("/stub/unprocessable"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.title").value("Unprocessable Entity"))
                .andExpect(jsonPath("$.detail").value("invariant violated"));
        }
    }

    @Nested
    @DisplayName("500 Internal Server Error")
    class InternalServerError {

        @Test
        @DisplayName("Returns 500 with a safe generic message — no stacktrace leaked")
        void returns500WithSafeMessage() throws Exception {
            mockMvc.perform(get("/stub/error"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.title").value("Internal Server Error"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred. Please contact support."));
        }
    }

    @Nested
    @DisplayName("400 Bad Request — validation")
    class Validation {

        @Test
        @DisplayName("Returns 400 when a required field is blank")
        void returns400OnBlankField() throws Exception {
            mockMvc.perform(post("/stub/validate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"value\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.errors.value").exists());
        }

        @Test
        @DisplayName("Returns 400 when the body is missing entirely")
        void returns400OnMissingBody() throws Exception {
            mockMvc.perform(post("/stub/validate")
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Bad Request"));
        }

        @Test
        @DisplayName("Returns 400 when the body is malformed JSON")
        void returns400OnMalformedJson() throws Exception {
            mockMvc.perform(post("/stub/validate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Bad Request"));
        }

        @Test
        @DisplayName("errors map contains the field name as key")
        void errorsMapKeyIsFieldName() throws Exception {
            mockMvc.perform(post("/stub/validate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"value\":\"\"}"))
                .andExpect(jsonPath("$.errors.value").isString());
        }
    }
}

