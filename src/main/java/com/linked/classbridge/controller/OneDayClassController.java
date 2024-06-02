package com.linked.classbridge.controller;

import com.linked.classbridge.dto.SuccessResponse;
import com.linked.classbridge.dto.review.GetReviewResponse;
import com.linked.classbridge.service.ReviewService;
import com.linked.classbridge.type.ResponseMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequiredArgsConstructor
@RequestMapping("/api/class")
public class OneDayClassController {

    private final ReviewService reviewService;

    @GetMapping("/{classId}/reviews")
    public ResponseEntity<SuccessResponse<Slice<GetReviewResponse>>> getClassReviews(
            @PathVariable Long classId,
            @PageableDefault Pageable pageable
    ) {
        return ResponseEntity.ok().body(
                SuccessResponse.of(
                        ResponseMessage.REVIEW_GET_SUCCESS,
                        reviewService.getClassReviews(classId, pageable)
                )
        );
    }
}
