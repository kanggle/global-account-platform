package com.example.auth.infrastructure.oauth;

import com.example.auth.domain.oauth.OAuthProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.Base64;

/**
 * Microsoft Identity Platform (Azure AD v2.0) OAuth 2.0 / OpenID Connect client.
 * Exchanges authorization code via POST to the Microsoft token endpoint,
 * then parses the id_token (JWT) to extract user information.
 *
 * <p>Email fallback: Microsoft returns {@code email} only when the user has
 * verified email on their account. When absent, {@code preferred_username} is
 * used as a fallback. If both are missing, the resulting {@link OAuthUserInfo}
 * carries {@code null} email and the use-case layer maps it to EMAIL_REQUIRED.
 */
@Slf4j
@Component
public class MicrosoftOAuthClient implements OAuthClient {

    private final RestClient restClient;
    private final OAuthProperties.ProviderProperties props;
    private final ObjectMapper objectMapper;

    public MicrosoftOAuthClient(OAuthProperties oAuthProperties, ObjectMapper objectMapper) {
        this.props = oAuthProperties.getMicrosoft();
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder().build();
    }

    @Override
    public OAuthUserInfo exchangeCodeForUserInfo(String code, String redirectUri) {
        try {
            MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
            formData.add("grant_type", "authorization_code");
            formData.add("code", code);
            formData.add("redirect_uri", redirectUri);
            formData.add("client_id", props.getClientId());
            formData.add("client_secret", props.getClientSecret());

            String responseBody = restClient.post()
                    .uri(props.getTokenUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(formData)
                    .retrieve()
                    .body(String.class);

            JsonNode tokenResponse = objectMapper.readTree(responseBody);

            String idToken = tokenResponse.path("id_token").asText(null);
            if (idToken == null || idToken.isBlank()) {
                throw new OAuthProviderException("Microsoft token response missing id_token");
            }

            String[] parts = idToken.split("\\.");
            if (parts.length < 2) {
                throw new OAuthProviderException("Malformed Microsoft id_token");
            }

            String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]));
            JsonNode payload = objectMapper.readTree(payloadJson);

            String sub = payload.path("sub").asText(null);
            if (sub == null || sub.isBlank()) {
                throw new OAuthProviderException("Microsoft id_token missing 'sub' claim");
            }

            String email = payload.path("email").asText(null);
            if (email == null || email.isBlank()) {
                String preferredUsername = payload.path("preferred_username").asText(null);
                if (preferredUsername != null && !preferredUsername.isBlank() && preferredUsername.contains("@")) {
                    email = preferredUsername;
                } else {
                    email = null;
                }
            }

            String name = payload.path("name").asText(null);

            return new OAuthUserInfo(sub, email, name, OAuthProvider.MICROSOFT);

        } catch (OAuthProviderException e) {
            throw e;
        } catch (Exception e) {
            log.error("Microsoft OAuth code exchange failed: {}", e.getMessage());
            throw new OAuthProviderException("Microsoft OAuth provider error", e);
        }
    }
}
