package com.fitpilot.user.application;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fitpilot.common.exception.BusinessException;
import com.fitpilot.common.exception.ErrorCode;
import com.fitpilot.common.response.PageResult;
import com.fitpilot.infrastructure.performance.TwoLevelCache;
import com.fitpilot.user.domain.BodyMetric;
import com.fitpilot.user.domain.User;
import com.fitpilot.user.domain.UserProfile;
import com.fitpilot.user.dto.UserDtos;
import com.fitpilot.user.repository.BodyMetricRepository;
import com.fitpilot.user.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class UserService {
    private final UserRepository users;
    private final BodyMetricRepository metrics;
    private final TwoLevelCache cache;

    public UserService(UserRepository users, BodyMetricRepository metrics, TwoLevelCache cache) {
        this.users = users;
        this.metrics = metrics;
        this.cache = cache;
    }

    public UserDtos.UserProfileView getProfile(long userId) {
        return cache.get("user-profile", String.valueOf(userId), UserDtos.UserProfileView.class, () -> {
            User user = users.findById(userId).orElse(null);
            UserProfile profile = users.findProfile(userId).orElse(null);
            return user == null || profile == null ? java.util.Optional.empty()
                    : java.util.Optional.of(view(user, profile));
        }).orElseThrow(this::notFound);
    }

    @Transactional
    public UserDtos.UserProfileView updateProfile(long userId, UserDtos.ProfileUpdateRequest request) {
        User user = users.findById(userId).orElseThrow(() -> notFound());
        UserProfile profile = users.findProfile(userId).orElseThrow(() -> notFound());
        profile.gender = request.gender();
        profile.birthday = request.birthday();
        profile.heightCm = request.heightCm();
        profile.trainingExperienceMonths = request.trainingExperienceMonths();
        profile.trainingGoal = request.trainingGoal();
        profile.weeklyFrequency = request.weeklyFrequency();
        profile.preferredDurationMinutes = request.preferredDurationMinutes();
        profile.updatedAt = LocalDateTime.now();
        users.updateProfile(profile);
        cache.evictAfterCommit("user-profile", String.valueOf(userId));
        return view(user, profile);
    }

    @Transactional
    public UserDtos.BodyMetricView addMetric(long userId, UserDtos.BodyMetricRequest request) {
        users.findById(userId).orElseThrow(() -> notFound());
        BodyMetric metric = new BodyMetric();
        metric.userId = userId;
        metric.weightKg = request.weightKg();
        metric.bodyFatPercentage = request.bodyFatPercentage();
        metric.muscleMassKg = request.muscleMassKg();
        metric.recordedAt = request.recordedAt() == null ? LocalDateTime.now() : request.recordedAt();
        metric.createdAt = LocalDateTime.now();
        metrics.insert(metric);
        return metricView(metric);
    }

    public PageResult<UserDtos.BodyMetricView> listMetrics(long userId, LocalDateTime start, LocalDateTime end,
                                                            long page, long size) {
        if (start != null && end != null && start.isAfter(end)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "startDate must not be after endDate");
        }
        Page<BodyMetric> result = metrics.findPage(userId, start, end, page, size);
        return PageResult.of(result.getRecords().stream().map(this::metricView).toList(), result.getTotal(), page, size);
    }

    public UserDtos.BodyMetricView latestMetric(long userId) {
        return metrics.latest(userId).map(this::metricView)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND, "body metric not found", HttpStatus.NOT_FOUND));
    }

    private UserDtos.UserProfileView view(User user, UserProfile p) {
        return new UserDtos.UserProfileView(user.id, user.username, user.email, p.gender, p.birthday, p.heightCm,
                p.trainingExperienceMonths, p.trainingGoal, p.weeklyFrequency, p.preferredDurationMinutes);
    }

    private UserDtos.BodyMetricView metricView(BodyMetric m) {
        return new UserDtos.BodyMetricView(m.id, m.weightKg, m.bodyFatPercentage, m.muscleMassKg, m.recordedAt);
    }

    private BusinessException notFound() {
        return new BusinessException(ErrorCode.USER_NOT_FOUND, "user not found", HttpStatus.NOT_FOUND);
    }
}
