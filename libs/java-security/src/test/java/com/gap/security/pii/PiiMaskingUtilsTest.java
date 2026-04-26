package com.gap.security.pii;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class PiiMaskingUtilsTest {

    // ----- maskEmail -----

    @Test
    @DisplayName("maskEmail keeps first char and domain")
    void maskEmail_keepsFirstCharAndDomain() {
        assertThat(PiiMaskingUtils.maskEmail("jane.doe@example.com"))
                .isEqualTo("j***@example.com");
    }

    @Test
    @DisplayName("maskEmail handles single-char local part")
    void maskEmail_singleCharLocalPart() {
        assertThat(PiiMaskingUtils.maskEmail("a@example.com"))
                .isEqualTo("a***@example.com");
    }

    @Test
    @DisplayName("maskEmail returns input for non-email")
    void maskEmail_nonEmailUnchanged() {
        assertThat(PiiMaskingUtils.maskEmail("no-at-sign")).isEqualTo("no-at-sign");
    }

    @Test
    @DisplayName("maskEmail returns null for null input")
    void maskEmail_nullInput() {
        assertThat(PiiMaskingUtils.maskEmail(null)).isNull();
    }

    // ----- maskAccountId -----

    @Test
    @DisplayName("maskAccountId passes UUID unchanged")
    void maskAccountId_uuidUnchanged() {
        String uuid = "00000000-0000-7000-8000-000000000001";
        assertThat(PiiMaskingUtils.maskAccountId(uuid)).isEqualTo(uuid);
    }

    @Test
    @DisplayName("maskAccountId masks embedded email")
    void maskAccountId_embeddedEmailMasked() {
        assertThat(PiiMaskingUtils.maskAccountId("jane.doe@example.com"))
                .isEqualTo("j***@example.com");
    }

    @Test
    @DisplayName("maskAccountId returns null for null input")
    void maskAccountId_nullInput() {
        assertThat(PiiMaskingUtils.maskAccountId(null)).isNull();
    }

    // ----- maskPhone -----

    @Test
    @DisplayName("maskPhone keeps prefix and last four digits")
    void maskPhone_keepsPrefixAndLastFour() {
        assertThat(PiiMaskingUtils.maskPhone("01012345678"))
                .isEqualTo("010-****-5678");
    }

    @Test
    @DisplayName("maskPhone handles formatted input")
    void maskPhone_handlesFormattedInput() {
        assertThat(PiiMaskingUtils.maskPhone("+82-10-1234-5678"))
                .isEqualTo("821-****-5678");
    }

    @Test
    @DisplayName("maskPhone returns input for short numbers")
    void maskPhone_shortNumberUnchanged() {
        assertThat(PiiMaskingUtils.maskPhone("1234")).isEqualTo("1234");
    }

    @Test
    @DisplayName("maskPhone returns input for seven-digit numbers")
    void maskPhone_sevenDigitsUnchanged() {
        assertThat(PiiMaskingUtils.maskPhone("1234567")).isEqualTo("1234567");
    }

    @Test
    @DisplayName("maskPhone returns null for null input")
    void maskPhone_nullInput() {
        assertThat(PiiMaskingUtils.maskPhone(null)).isNull();
    }

    // ----- maskIp -----

    @ParameterizedTest
    @CsvSource({
            "192.168.1.100, 192.168.1.***",
            "10.0.0.1, 10.0.0.***",
            "192.168.1.***, 192.168.1.***",
            "'', ''"
    })
    @DisplayName("maskIp replaces last octet with ***")
    void maskIp_replacesLastOctet(String input, String expected) {
        assertThat(PiiMaskingUtils.maskIp(input)).isEqualTo(expected);
    }

    @Test
    @DisplayName("maskIp returns null for null input")
    void maskIp_nullInput() {
        assertThat(PiiMaskingUtils.maskIp(null)).isNull();
    }

    @Test
    @DisplayName("maskIp returns input unchanged when no dot present")
    void maskIp_noDotUnchanged() {
        assertThat(PiiMaskingUtils.maskIp("notanip")).isEqualTo("notanip");
    }

    // ----- truncateFingerprint -----

    @Test
    @DisplayName("truncateFingerprint truncates to 12 chars")
    void truncateFingerprint_long() {
        assertThat(PiiMaskingUtils.truncateFingerprint("abcdef123456789012345678"))
                .isEqualTo("abcdef123456");
    }

    @Test
    @DisplayName("truncateFingerprint returns short strings unchanged")
    void truncateFingerprint_short() {
        assertThat(PiiMaskingUtils.truncateFingerprint("abc")).isEqualTo("abc");
    }

    @Test
    @DisplayName("truncateFingerprint returns null for null input")
    void truncateFingerprint_null() {
        assertThat(PiiMaskingUtils.truncateFingerprint(null)).isNull();
    }

    @Test
    @DisplayName("truncateFingerprint returns exactly 12 chars unchanged")
    void truncateFingerprint_exact12() {
        assertThat(PiiMaskingUtils.truncateFingerprint("abcdef123456"))
                .isEqualTo("abcdef123456");
    }
}
