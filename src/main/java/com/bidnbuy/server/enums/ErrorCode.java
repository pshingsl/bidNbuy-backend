package com.bidnbuy.server.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;
import static org.springframework.http.HttpStatus.*;

// 예외 메세지
@Getter
@AllArgsConstructor
public enum ErrorCode {
    // 200 OK : 성공
    SUCCESS(OK, "성공", 200),

    // 400 BAD_REQUEST : 잘못된 요청 - 비즈니스 로직 에러코드를 여기에 작성해주세요!
    INVALID_REQUEST(BAD_REQUEST, "잘못된 요청입니다.", 400),
    AUCTION_NOT_IN_PROGRESS(BAD_REQUEST, "현재 입찰이 불가능합니다. 경매가 진행 중이 아닙니다.", 400),
    EXPIRED_AUCTION_TIME(BAD_REQUEST, "경매 시간이 종료되었습니다", 400),
    INVALID_MIN_BID(BAD_REQUEST, "입찰 금액이 최소 입찰 단위(%d원)를 충족하지 못합니다. 최소 입찰 금액은 %d원 이상입니다.", 400),
    LOWER_THAN_CURRENT_PRICE(BAD_REQUEST, "이미 더 높은 입찰가가 존재합니다", 400),

    // 401 UNAUTHORIZED : 인증되지 않은 사용자
    UNAUTHORIZED_REQUEST(UNAUTHORIZED, "Unauthorized.", 401),
    INVALID_TOKEN(UNAUTHORIZED, "유효하지 않은 토큰입니다.", 401),
    INVALID_REFRESH_TOKEN(UNAUTHORIZED, "리프레시 토큰이 유효하지 않습니다. 다시 로그인해주세요.", 401),
    INVALID_EXPIRED_TOKEN(UNAUTHORIZED, "만료된 토큰입니다", 401),
    INVALID_DELETED_MEMBER(UNAUTHORIZED, "탈퇴한 회원입니다", 401),
    LOGIN_FAILED(UNAUTHORIZED, "이메일 또는 비밀번호가 일치하지 않습니다! 확인해주세요!", 401),
    EMAIL_NOT_VERIFIED(UNAUTHORIZED, "이메일 인증이 필요합니다.", 401),

    // 403 Forbidden : 자원에 대한 권한 없음
    INVALID_AUTH(FORBIDDEN, "권한이 없습니다", 403),
    SELF_BIDDING_FORBIDDEN(FORBIDDEN, "자신의 경매 물품에는 입찰할 수 없습니다", 403),
    USER_SUSPENDED(FORBIDDEN, "정지된 계정입니다. 해제일: %s", 403),
    BANNED_USER(FORBIDDEN, "강제 탈퇴된 계정입니다.", 403),

    // 404 Not Found : 요청한 URI에 대한 리소스 없음
    INVALID_RESOURCE(NOT_FOUND, "요청한 리소스가 없습니다", 404),
    AUCTION_NOT_FOUND(NOT_FOUND, "존재하지 않는 경매 물품입니다", 404),
    INVALID_USER_ID(NOT_FOUND, "존재하지 않는 사용자 입니다", 404),

    // 405 Method Not Allowed : 사용 불가능한 Method 이용
    INVALID_METHOD(METHOD_NOT_ALLOWED, "지원하지 않는 Method 입니다.", 405),

    // 500 INTERNAL_SERVER_ERROR : 서버 에러
    SERVER_ERROR(INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.", 500);

    private final HttpStatus httpStatus;
    private final String message;
    private final int code;
}
