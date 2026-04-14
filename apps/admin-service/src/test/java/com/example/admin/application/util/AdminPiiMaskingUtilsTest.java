package com.example.admin.application.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdminPiiMaskingUtilsTest {

    @Test
    void maskEmail_keeps_first_char_and_domain() {
        assertThat(AdminPiiMaskingUtils.maskEmail("jane.doe@example.com"))
                .isEqualTo("j***@example.com");
    }

    @Test
    void maskEmail_handles_single_char_local_part() {
        assertThat(AdminPiiMaskingUtils.maskEmail("a@example.com"))
                .isEqualTo("a***@example.com");
    }

    @Test
    void maskEmail_returns_input_for_non_email() {
        assertThat(AdminPiiMaskingUtils.maskEmail("no-at-sign")).isEqualTo("no-at-sign");
    }

    @Test
    void maskEmail_returns_null_for_null_input() {
        assertThat(AdminPiiMaskingUtils.maskEmail(null)).isNull();
    }

    @Test
    void maskAccountId_passes_uuid_unchanged() {
        String uuid = "00000000-0000-7000-8000-000000000001";
        assertThat(AdminPiiMaskingUtils.maskAccountId(uuid)).isEqualTo(uuid);
    }

    @Test
    void maskAccountId_masks_embedded_email() {
        assertThat(AdminPiiMaskingUtils.maskAccountId("jane.doe@example.com"))
                .isEqualTo("j***@example.com");
    }

    @Test
    void maskPhone_keeps_prefix_and_last_two_digits() {
        assertThat(AdminPiiMaskingUtils.maskPhone("01012345678"))
                .isEqualTo("010***78");
    }

    @Test
    void maskPhone_handles_formatted_input() {
        assertThat(AdminPiiMaskingUtils.maskPhone("+82-10-1234-5678"))
                .isEqualTo("821***78");
    }

    @Test
    void maskPhone_returns_input_for_short_numbers() {
        assertThat(AdminPiiMaskingUtils.maskPhone("1234")).isEqualTo("1234");
    }
}
