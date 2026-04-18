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
 * Google OAuth 2.0 client.
 * Exchanges authorization code via POST to Google's token endpoint,
 * then parses the id_token (JWT) to extract user information.
 */
@Slf4j
@Component
public class GoogleOAuthClient implements OAuthClient {

    private final RestClient restClient;
    private final OAuthProperties.ProviderProperties props;
    private final ObjectMapper objectMapper;

    public GoogleOAuthClient(OAuthProperties oAuthProperties, ObjectMapper objectMapper) {
        this.props = oAuthProperties.getGoogle();
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
                throw new OAuthProviderException("Google token response missing id_token");
            }

            // Parse id_token payload (Base64 decode of the second segment)
            String[] parts = idToken.split("\\.");
            if (parts.length < 2) {
                throw new OAuthProviderException("Malformed Google id_token");
            }

            String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]));
            JsonNode payload = objectMapper.readTree(payloadJson);

            String sub = payload.path("sub").asText(null);
            String email = payload.path("email").asText(null);
            String name = payload.path("name").asText(null);

            if (sub == null || sub.isBlank()) {
                throw new OAuthProviderException("Google id_token missing 'sub' claim");
            }

            return new OAuthUserInfo(sub, email, name, OAuthProvider.GOOGLE);

        } catch (OAuthProviderException e) {
            throw e;
        } catch (Exception e) {
            log.error("Google OAuth code exchange failed: {}", e.getMessage());
            throw new OAuthProviderException("Google OAuth provider error", e);
        }
    }
}
