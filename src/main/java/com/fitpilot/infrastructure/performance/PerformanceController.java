package com.fitpilot.infrastructure.performance;

import com.fitpilot.common.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/performance")
public class PerformanceController {
    private final TwoLevelCache cache;
    public PerformanceController(TwoLevelCache cache) { this.cache = cache; }

    @GetMapping("/cache-stats")
    ApiResponse<TwoLevelCache.CacheStatsView> cacheStats() {
        return ApiResponse.success(cache.stats());
    }
}
