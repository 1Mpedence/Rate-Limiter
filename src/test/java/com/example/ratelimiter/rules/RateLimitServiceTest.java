package com.example.ratelimiter.rules;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.ratelimiter.algorithm.RateLimitAlgorithm;
import com.example.ratelimiter.algorithm.RateLimitResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimitServiceTest {

    @Mock RuleService ruleService;
    @Mock RateLimitAlgorithm algorithm;

    RateLimitService service;

    @BeforeEach
    void setUp() {
        when(algorithm.name()).thenReturn("bucket");
        service = new RateLimitService(ruleService, List.of(algorithm));
    }

    @Test
    void returnsEmpty_whenNoRuleExistsForApi() {
        when(ruleService.find(Tier.FREE, "search")).thenReturn(Optional.empty());
        assertThat(service.check("user1", Tier.FREE, "search")).isEmpty();
    }

    @Test
    void returnsDecision_whenRuleAndAlgorithmMatch() {
        when(ruleService.find(Tier.FREE, "search"))
                .thenReturn(Optional.of(new Rule("bucket", 10, 60_000)));
        when(algorithm.check(anyString(), anyLong(), anyLong()))
                .thenReturn(new RateLimitResult(true, 9, 0));

        Optional<RateLimitService.Decision> decision = service.check("alice", Tier.FREE, "search");

        assertThat(decision).isPresent();
        assertThat(decision.get().result().allowed()).isTrue();
        assertThat(decision.get().result().remaining()).isEqualTo(9);
        assertThat(decision.get().rule().limit()).isEqualTo(10);
    }

    @Test
    void throws_whenRuleNamesUnknownAlgorithm() {
        when(ruleService.find(Tier.FREE, "search"))
                .thenReturn(Optional.of(new Rule("unknown", 10, 60_000)));

        assertThatThrownBy(() -> service.check("user1", Tier.FREE, "search"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown");
    }

    @Test
    void redisKey_includesAlgorithmAndUserAndApi() {
        when(ruleService.find(Tier.FREE, "search"))
                .thenReturn(Optional.of(new Rule("bucket", 10, 60_000)));
        when(algorithm.check(anyString(), anyLong(), anyLong()))
                .thenReturn(new RateLimitResult(true, 9, 0));

        service.check("alice", Tier.FREE, "search");

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(algorithm).check(keyCaptor.capture(), anyLong(), anyLong());
        assertThat(keyCaptor.getValue())
                .contains("bucket")
                .contains("alice")
                .contains("search");
    }

    @Test
    void isKnownAlgorithm_returnsTrueForRegisteredAlgorithm() {
        assertThat(service.isKnownAlgorithm("bucket")).isTrue();
    }

    @Test
    void isKnownAlgorithm_returnsFalseForUnknown() {
        assertThat(service.isKnownAlgorithm("leaky")).isFalse();
    }

    @Test
    void algorithmNames_containsRegisteredAlgorithm() {
        assertThat(service.algorithmNames()).containsExactly("bucket");
    }
}
