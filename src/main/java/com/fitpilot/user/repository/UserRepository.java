package com.fitpilot.user.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fitpilot.user.domain.User;
import com.fitpilot.user.domain.UserProfile;
import com.fitpilot.user.infrastructure.UserMapper;
import com.fitpilot.user.infrastructure.UserProfileMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class UserRepository {
    private final UserMapper users;
    private final UserProfileMapper profiles;

    public UserRepository(UserMapper users, UserProfileMapper profiles) {
        this.users = users;
        this.profiles = profiles;
    }

    public Optional<User> findById(long id) { return Optional.ofNullable(users.selectById(id)); }
    public Optional<User> findByUsername(String username) {
        return Optional.ofNullable(users.selectOne(new QueryWrapper<User>().eq("username", username)));
    }
    public boolean usernameExists(String username) {
        return users.selectCount(new QueryWrapper<User>().eq("username", username)) > 0;
    }
    public boolean emailExists(String email) {
        return email != null && users.selectCount(new QueryWrapper<User>().eq("email", email)) > 0;
    }
    public void insert(User user) { users.insert(user); }
    public Optional<UserProfile> findProfile(long userId) {
        return Optional.ofNullable(profiles.selectOne(new QueryWrapper<UserProfile>().eq("user_id", userId)));
    }
    public void insertProfile(UserProfile profile) { profiles.insert(profile); }
    public void updateProfile(UserProfile profile) { profiles.updateById(profile); }
}
