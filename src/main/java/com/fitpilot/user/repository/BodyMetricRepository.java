package com.fitpilot.user.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fitpilot.user.domain.BodyMetric;
import com.fitpilot.user.infrastructure.BodyMetricMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class BodyMetricRepository {
    private final BodyMetricMapper mapper;
    public BodyMetricRepository(BodyMetricMapper mapper) { this.mapper = mapper; }

    public void insert(BodyMetric metric) { mapper.insert(metric); }

    public Page<BodyMetric> findPage(long userId, LocalDateTime start, LocalDateTime end, long page, long size) {
        QueryWrapper<BodyMetric> query = new QueryWrapper<BodyMetric>().eq("user_id", userId)
                .ge(start != null, "recorded_at", start).le(end != null, "recorded_at", end)
                .orderByDesc("recorded_at");
        return mapper.selectPage(Page.of(page, size), query);
    }

    public Optional<BodyMetric> latest(long userId) {
        return Optional.ofNullable(mapper.selectOne(new QueryWrapper<BodyMetric>().eq("user_id", userId)
                .orderByDesc("recorded_at").last("LIMIT 1")));
    }

    public List<BodyMetric> findRange(long userId, LocalDateTime start, LocalDateTime end) {
        return mapper.selectList(new QueryWrapper<BodyMetric>().eq("user_id", userId)
                .ge("recorded_at", start).le("recorded_at", end).orderByAsc("recorded_at"));
    }
}
