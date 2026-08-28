package com.fitpilot.pr.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitpilot.pr.domain.PersonalRecord;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface PersonalRecordMapper extends BaseMapper<PersonalRecord> {
    @Select("SELECT DISTINCT ON (exercise_id, record_type) * FROM personal_record " +
            "WHERE user_id = #{userId} ORDER BY exercise_id, record_type, achieved_at DESC, id DESC")
    List<PersonalRecord> selectCurrent(long userId);
}
