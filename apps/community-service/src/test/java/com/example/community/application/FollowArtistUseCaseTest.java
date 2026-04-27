package com.example.community.application;

import com.example.community.application.exception.AlreadyFollowingException;
import com.example.community.application.exception.NotFollowingException;
import com.example.community.domain.feed.FeedSubscription;
import com.example.community.domain.feed.FeedSubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
class FollowArtistUseCaseTest {

    @Mock FeedSubscriptionRepository subscriptionRepository;

    FollowArtistUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new FollowArtistUseCase(subscriptionRepository);
    }

    @Test
    @DisplayName("팬이 아티스트를 팔로우하면 구독 정보가 저장된다")
    void follow_validPair_savesSubscription() {
        FeedSubscription sub = FeedSubscription.create("fan-1", "artist-1");
        when(subscriptionRepository.exists("fan-1", "artist-1")).thenReturn(false);
        when(subscriptionRepository.save(any(FeedSubscription.class))).thenReturn(sub);

        FollowArtistUseCase.FollowResult result = useCase.follow("fan-1", "artist-1");

        assertThat(result.fanAccountId()).isEqualTo("fan-1");
        assertThat(result.artistAccountId()).isEqualTo("artist-1");
        assertThat(result.followedAt()).isNotNull();
    }

    @Test
    @DisplayName("자기 자신을 팔로우하면 IllegalArgumentException 이 발생한다")
    void follow_selfFollow_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> useCase.follow("fan-1", "fan-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("이미 팔로우 중인 아티스트를 다시 팔로우하면 AlreadyFollowingException 이 발생한다")
    void follow_alreadyFollowing_throwsAlreadyFollowingException() {
        when(subscriptionRepository.exists("fan-1", "artist-1")).thenReturn(true);

        assertThatThrownBy(() -> useCase.follow("fan-1", "artist-1"))
                .isInstanceOf(AlreadyFollowingException.class);
    }

    @Test
    @DisplayName("팔로우 중인 아티스트를 언팔로우하면 구독 정보가 삭제된다")
    void unfollow_existing_deletesSubscription() {
        FeedSubscription sub = FeedSubscription.create("fan-1", "artist-1");
        when(subscriptionRepository.find("fan-1", "artist-1")).thenReturn(Optional.of(sub));

        useCase.unfollow("fan-1", "artist-1");

        verify(subscriptionRepository).delete(sub);
    }

    @Test
    @DisplayName("팔로우하지 않은 아티스트를 언팔로우하면 NotFollowingException 이 발생한다")
    void unfollow_notFollowing_throwsNotFollowingException() {
        when(subscriptionRepository.find("fan-1", "artist-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.unfollow("fan-1", "artist-1"))
                .isInstanceOf(NotFollowingException.class);
    }
}
