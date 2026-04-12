package com.example.security.query;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class PiiMaskingUtilsTest {

    @ParameterizedTest
    @CsvSource({
            "192.168.1.100, 192.168.1.***",
            "10.0.0.1, 10.0.0.***",
            "192.168.1.***, 192.168.1.***",
            "'', ''"
    })
    @DisplayName("maskIp replaces last octet with ***")
    void maskIpReplacesLastOctet(String input, String expected) {
        assertThat(PiiMaskingUtils.maskIp(input)).isEqualTo(expected);
    }

    @Test
    @DisplayName("maskIp returns null for null input")
    void maskIpNullInput() {
        assertThat(PiiMaskingUtils.maskIp(null)).isNull();
    }

    @Test
    @DisplayName("truncateFingerprint truncates to 12 chars")
    void truncateFingerprintLong() {
        assertThat(PiiMaskingUtils.truncateFingerprint("abcdef123456789012345678"))
                .isEqualTo("abcdef123456");
    }

    @Test
    @DisplayName("truncateFingerprint returns short strings unchanged")
    void truncateFingerprintShort() {
        assertThat(PiiMaskingUtils.truncateFingerprint("abc")).isEqualTo("abc");
    }

    @Test
    @DisplayName("truncateFingerprint returns null for null input")
    void truncateFingerprintNull() {
        assertThat(PiiMaskingUtils.truncateFingerprint(null)).isNull();
    }

    @Test
    @DisplayName("truncateFingerprint returns exactly 12 chars unchanged")
    void truncateFingerprintExact12() {
        assertThat(PiiMaskingUtils.truncateFingerprint("abcdef123456"))
                .isEqualTo("abcdef123456");
    }
}
