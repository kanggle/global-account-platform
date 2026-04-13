package com.example.community.application;

import com.example.community.application.exception.AlreadyFollowingException;
import com.example.community.application.exception.NotFollowingException;
import com.example.community.domain.feed.FeedSubscription;
import com.example.community.domain.feed.FeedSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FollowArtistUseCase {

    private final FeedSubscriptionRepository subscriptionRepository;

    public record FollowResult(String fanAccountId, String artistAccountId,
                               java.time.Instant followedAt) {}

    @Transactional
    public FollowResult follow(String fanAccountId, String artistAccountId) {
        // TODO: community-api.md declares ARTIST_NOT_FOUND (404) when the artist account
        // does not exist. community-service currently has no account-service integration,
        // so artist existence is not verified here. Add account-service lookup when the
        // client is introduced; until then, non-existent artistAccountId will still
        // succeed at follow time but produce empty feed results.
        if (fanAccountId.equals(artistAccountId)) {
            throw new IllegalArgumentException("Cannot follow self");
        }
        if (subscriptionRepository.exists(fanAccountId, artistAccountId)) {
            throw new AlreadyFollowingException();
        }
        FeedSubscription saved = subscriptionRepository.save(FeedSubscription.create(fanAccountId, artistAccountId));
        return new FollowResult(saved.getFanAccountId(), saved.getArtistAccountId(), saved.getFollowedAt());
    }

    @Transactional
    public void unfollow(String fanAccountId, String artistAccountId) {
        FeedSubscription fs = subscriptionRepository.find(fanAccountId, artistAccountId)
                .orElseThrow(NotFollowingException::new);
        subscriptionRepository.delete(fs);
    }
}
