package com.fitpilot.user.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserDomainModelTest {
    @Test
    void domainRecordsAreConstructibleByPersistenceLayer() {
        assertThat(new User()).isNotNull();
        assertThat(new UserProfile()).isNotNull();
        assertThat(new BodyMetric()).isNotNull();
    }
}
