package com.example.community.presentation;

import com.example.community.application.GetPostUseCase;
import com.example.community.application.PostView;
import com.example.community.application.PublishPostCommand;
import com.example.community.application.PublishPostUseCase;
import com.example.community.application.exception.PermissionDeniedException;
import com.example.community.domain.post.PostType;
import com.example.community.domain.post.PostVisibility;
import com.example.community.domain.post.status.PostStatus;
import com.example.community.presentation.exception.GlobalExceptionHandler;
import com.example.community.support.AccountJwtTestFixture;
import com.example.community.support.SliceTestSecurityConfig;
import com.gap.security.jwt.JwtVerifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PostController.class)
@Import({SliceTestSecurityConfig.class, GlobalExceptionHandler.class, PostControllerTest.JwtBeans.class})
class PostControllerTest {

    private static AccountJwtTestFixture jwt;

    @BeforeAll
    static void init() {
        jwt = new AccountJwtTestFixture();
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class JwtBeans {
        @Bean
        JwtVerifier communityJwtVerifier() {
            if (jwt == null) jwt = new AccountJwtTestFixture();
            return jwt.verifier();
        }
    }

    @Autowired
    MockMvc mockMvc;

    @MockBean
    PublishPostUseCase publishPostUseCase;

    @MockBean
    GetPostUseCase getPostUseCase;

    private String bearer(String sub, List<String> roles) {
        return "Bearer " + jwt.token(sub, roles);
    }

    @Test
    void publish_post_as_artist_returns_201() throws Exception {
        PostView view = new PostView(
                "post-1", PostType.ARTIST_POST, PostVisibility.PUBLIC, PostStatus.PUBLISHED,
                "artist-1", "Artist", "t", "body",
                0L, 0L, null, Instant.now(), Instant.now());
        when(publishPostUseCase.execute(any(PublishPostCommand.class))).thenReturn(view);

        String body = """
                {"type":"ARTIST_POST","visibility":"PUBLIC","title":"t","body":"hello"}
                """;

        mockMvc.perform(post("/api/community/posts")
                        .header("Authorization", bearer("artist-1", List.of("ARTIST")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.postId").value("post-1"))
                .andExpect(jsonPath("$.type").value("ARTIST_POST"))
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
    }

    @Test
    void publish_artist_post_as_fan_returns_403() throws Exception {
        when(publishPostUseCase.execute(any(PublishPostCommand.class)))
                .thenThrow(new PermissionDeniedException("ARTIST role required"));

        String body = """
                {"type":"ARTIST_POST","visibility":"PUBLIC","body":"hello"}
                """;

        mockMvc.perform(post("/api/community/posts")
                        .header("Authorization", bearer("fan-1", List.of("FAN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }

    @Test
    void missing_authorization_returns_401() throws Exception {
        mockMvc.perform(post("/api/community/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"FAN_POST\",\"visibility\":\"PUBLIC\",\"body\":\"hi\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }

    @Test
    void missing_body_returns_422() throws Exception {
        String body = """
                {"type":"ARTIST_POST","visibility":"PUBLIC"}
                """;
        mockMvc.perform(post("/api/community/posts")
                        .header("Authorization", bearer("artist-1", List.of("ARTIST")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
