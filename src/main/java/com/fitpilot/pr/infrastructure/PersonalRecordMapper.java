package com.fitpilot.pr.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitpilot.pr.domain.PersonalRecord;
import com.fitpilot.pr.dto.LeaderboardRow;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface PersonalRecordMapper extends BaseMapper<PersonalRecord> {
    String SCORE = """
            CASE pr.record_type
                WHEN 'ESTIMATED_1RM' THEN pr.estimated_1rm
                WHEN 'MAX_VOLUME' THEN pr.weight_kg * pr.reps
                ELSE pr.weight_kg
            END
            """;

    @Select("""
            SELECT DISTINCT ON (pr.exercise_id, pr.record_type) pr.*
            FROM personal_record pr
            WHERE pr.user_id = #{userId}
            ORDER BY pr.exercise_id, pr.record_type,
            """ + SCORE + " DESC NULLS LAST, pr.achieved_at DESC, pr.id DESC")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    List<PersonalRecord> selectCurrent(long userId);

    @Select("""
            SELECT pr.user_id, u.username, MAX(
            """ + SCORE + """
            ) AS score
            FROM personal_record pr
            JOIN users u ON u.id = pr.user_id
            WHERE exercise_id = #{exerciseId} AND record_type = #{recordType}
            GROUP BY pr.user_id, u.username
            ORDER BY score DESC, pr.user_id ASC
            LIMIT #{limit}
            """)
    List<LeaderboardRow> selectLeaderboard(long exerciseId, String recordType, int limit);
}
