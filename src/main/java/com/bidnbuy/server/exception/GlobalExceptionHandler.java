package com.bidnbuy.server.exception;

import com.bidnbuy.server.enums.ErrorResponse;
import com.bidnbuy.server.enums.ErrorCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

// 글로버 커스텀
@RestControllerAdvice
public class GlobalExceptionHandler  extends ResponseEntityExceptionHandler {

    @ExceptionHandler(RestApiException.class)
    protected  ResponseEntity<ErrorResponse> handleCustomException(RestApiException ex) {
        ErrorCode errorCode = ex.getErrorCode();
        return handleExceptionInternal(errorCode);
    }
    @ExceptionHandler(IllegalArgumentException.class)
    protected ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        // 로그에 찍힌 메시지에 따라 분기 처리하거나, 기본 에러코드를 내려줌
        if (ex.getMessage().contains("Auction Not Found")) {
            return handleExceptionInternal(ErrorCode.AUCTION_NOT_FOUND);
        }
        return handleExceptionInternal(ErrorCode.INVALID_REQUEST);
    }

    @ExceptionHandler(CustomException.class)
    protected ResponseEntity<ErrorResponse> handleCustomException(CustomException ex) {
        return handleExceptionInternal(ex.getExceptionCode());
    }

    private ResponseEntity<ErrorResponse> handleExceptionInternal(ErrorCode errorCode) {
        return ResponseEntity
                .status(errorCode.getHttpStatus().value())
                .body(new ErrorResponse(errorCode));
    }
}