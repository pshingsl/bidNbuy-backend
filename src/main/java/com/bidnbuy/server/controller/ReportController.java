package com.bidnbuy.server.controller;

import com.bidnbuy.server.dto.CreateReportRequestDto;
import com.bidnbuy.server.dto.ReportListResponseDto;
import com.bidnbuy.server.dto.ReportResponseDto;
import com.bidnbuy.server.entity.InquiriesEntity;
import com.bidnbuy.server.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "신고 API", description = "신공 기능 제공")
@RestController
@RequestMapping("/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;


    // 신고 상세 조회
    @Operation(summary = "신고 상세 조회", description = "신고 상세 조회 사용되는 API")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "신고 상세 조회 성공", // 설명 수정
                    // 실제 반환 타입(Map)을 반영
                    content = @Content(schema = @Schema(implementation = Map.class))),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(responseCode = "404", description = "신고 정보를 찾을 수 없음")
    })
    @GetMapping("/{reportId}")
    public ResponseEntity<Map<String, Object>> getReportDetail(@PathVariable Long reportId, @AuthenticationPrincipal Long userId) {
        ReportResponseDto report = reportService.getReportDetail(reportId, userId);

        Map<String, Object> response = new HashMap<>();
        response.put("data", report);

        return ResponseEntity.ok(response);
    }

    // 내 신고 목록 조회
    @Operation(summary = "신고 조회", description = "신고 조회 사용되는 API")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    // 실제 반환 타입인 ReportListResponseDto로 수정
                    content = @Content(schema = @Schema(implementation = ReportListResponseDto.class))),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(responseCode = "404", description = "사용자 신고 목록을 찾을 수 없음") // 설명 수정 권장
    })
    @GetMapping
    public ResponseEntity<ReportListResponseDto> getMyReports(@AuthenticationPrincipal Long userId) {
        List<ReportResponseDto> reports = reportService.getMyReports(userId);

        return ResponseEntity.ok(new ReportListResponseDto(reports));
    }


    @Operation(summary = "신고 생성", description = "신고 생성 사용되는 API")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "신고 성공",
                    content = @Content(schema = @Schema(type = "string", implementation = CreateReportRequestDto.class))),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(responseCode = "404", description = "주문 정보를 찾을 수 없음") // 예외적으로 추가 고려
    })
    @PostMapping
    public ResponseEntity<Map<String, Object>> createReport(@RequestBody CreateReportRequestDto request, @AuthenticationPrincipal Long userId) {
        InquiriesEntity report = reportService.createReport(userId, request);

        Map<String, Object> response = new HashMap<>();
        response.put("message", "신고가 접수되었습니다");
        response.put("data", Map.of(
                "reportId", report.getInquiriesId(),
                "title", report.getTitle(),
                "content", report.getContent(),
                "status", report.getStatus().name(),
                "createdAt", report.getCreatedAt()
        ));

        return ResponseEntity.ok(response);
    }
}
