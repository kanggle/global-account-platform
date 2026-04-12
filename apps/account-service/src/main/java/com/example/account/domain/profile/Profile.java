package com.example.account.domain.profile;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "profiles")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Profile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false, unique = true, length = 36)
    private String accountId;

    @Column(name = "display_name", length = 100)
    private String displayName;

    @Column(name = "phone_number", length = 20)
    private String phoneNumber;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Column(length = 10, nullable = false)
    private String locale;

    @Column(length = 50, nullable = false)
    private String timezone;

    @Column(columnDefinition = "JSON")
    private String preferences;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static Profile create(String accountId, String displayName, String locale, String timezone) {
        Profile profile = new Profile();
        profile.accountId = accountId;
        profile.displayName = displayName;
        profile.locale = locale != null ? locale : "ko-KR";
        profile.timezone = timezone != null ? timezone : "Asia/Seoul";
        profile.updatedAt = Instant.now();
        return profile;
    }

    public void update(String displayName, String phoneNumber, LocalDate birthDate,
                       String locale, String timezone, String preferences) {
        if (displayName != null) {
            this.displayName = displayName;
        }
        if (phoneNumber != null) {
            this.phoneNumber = phoneNumber;
        }
        if (birthDate != null) {
            this.birthDate = birthDate;
        }
        if (locale != null) {
            this.locale = locale;
        }
        if (timezone != null) {
            this.timezone = timezone;
        }
        if (preferences != null) {
            this.preferences = preferences;
        }
        this.updatedAt = Instant.now();
    }
}
