package com.linked.classbridge.repository;

import com.linked.classbridge.domain.Lesson;
import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LessonRepository extends JpaRepository<Lesson, Long> {
    void deleteAllByOneDayClassClassIdAndLessonDateIsAfter(Long classId, LocalDate now);
}