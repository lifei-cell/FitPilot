package com.fitpilot.pr.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitpilot.pr.domain.PersonalRecord;
import com.fitpilot.pr.dto.LeaderboardRow;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface PersonalRecordMapper extends BaseMapper<PersonalRecord> {
    @Select("SELECT DISTINCT ON (exercise_id, record_type) * FROM personal_record " +
            "WHERE user_id = #{userId} ORDER BY exercise_id, record_type, achieved_at DESC, id DESC")
    List<PersonalRecord> selectCurrent(long userId);

    @Select("""
            SELECT pr.user_id, u.username,
                   MAX(CASE record_type
                         WHEN 'ESTIMATED_1RM' THEN estimated_1rm
                         WHEN 'MAX_VOLUME' THEN weight_kg * reps
                         ELSE weight_kg END) AS score
            FROM personal_record pr
            JOIN users u ON u.id = pr.user_id
            WHERE exercise_id = #{exerciseId} AND record_type = #{recordType}
            GROUP BY pr.user_id, u.username
            ORDER BY score DESC, pr.user_id ASC
            LIMIT #{limit}
            """)
    List<LeaderboardRow> selectLeaderboard(long exerciseId, String recordType, int limit);
}
