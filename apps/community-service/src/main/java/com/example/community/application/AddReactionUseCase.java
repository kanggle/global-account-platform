package com.example.community.application;

import com.example.community.application.event.CommunityEventPublisher;
import com.example.community.application.exception.MembershipRequiredException;
import com.example.community.application.exception.PostNotFoundException;
import com.example.community.domain.access.ContentAccessChecker;
import com.example.community.domain.post.Post;
import com.example.community.domain.post.PostRepository;
import com.example.community.domain.post.PostVisibility;
import com.example.community.domain.post.status.PostStatus;
import com.example.community.domain.reaction.Reaction;
import com.example.community.domain.reaction.ReactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AddReactionUseCase {

    private static final Set<String> ALLOWED_EMOJIS = Set.of("HEART", "FIRE", "CLAP", "WOW", "SAD");

    private final PostRepository postRepository;
    private final ReactionRepository reactionRepository;
    private final ContentAccessChecker contentAccessChecker;
    private final CommunityEventPublisher eventPublisher;

    public record ReactionResult(String postId, String emojiCode, long totalReactions) {}

    @Transactional
    public ReactionResult execute(String postId, String emojiCode, ActorContext actor) {
        if (emojiCode == null || !ALLOWED_EMOJIS.contains(emojiCode)) {
            throw new IllegalArgumentException("Unsupported emojiCode: " + emojiCode);
        }

        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new PostNotFoundException(postId));
        if (post.getStatus() != PostStatus.PUBLISHED) {
            throw new PostNotFoundException(postId);
        }

        if (post.getVisibility() == PostVisibility.MEMBERS_ONLY
                && !post.getAuthorAccountId().equals(actor.accountId())) {
            boolean allowed = contentAccessChecker.check(actor.accountId(), GetPostUseCase.REQUIRED_PLAN_LEVEL);
            if (!allowed) {
                throw new MembershipRequiredException();
            }
        }

        Optional<Reaction> existing = reactionRepository.find(postId, actor.accountId());
        boolean isNew;
        Reaction reaction;
        if (existing.isPresent()) {
            reaction = existing.get();
            isNew = false;
            if (!reaction.getEmojiCode().equals(emojiCode)) {
                reaction.changeEmoji(emojiCode);
                reactionRepository.save(reaction);
            }
        } else {
            reaction = Reaction.create(postId, actor.accountId(), emojiCode);
            reactionRepository.save(reaction);
            isNew = true;
        }

        long total = reactionRepository.countByPostId(postId);
        eventPublisher.publishReactionAdded(postId, actor.accountId(), emojiCode, isNew, reaction.getUpdatedAt());

        return new ReactionResult(postId, emojiCode, total);
    }
}
