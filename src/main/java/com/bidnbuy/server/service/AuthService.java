package com.bidnbuy.server.service;

import com.bidnbuy.server.dto.*;
import com.bidnbuy.server.entity.RefreshTokenEntity;
import com.bidnbuy.server.entity.UserEntity;
import com.bidnbuy.server.enums.AuthStatus;
import com.bidnbuy.server.enums.ErrorCode;
import com.bidnbuy.server.exception.CustomException;
import com.bidnbuy.server.repository.UserRepository;
import com.bidnbuy.server.security.JwtProvider;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {
    private final JwtProvider jwtProvider;
    private final RefreshTokenService refreshTokenService;
    private final UserService userService;
    private final KakaoApiService kakaoApiService;
    private final NaverApiService naverApiService;
    private final EmailService emailService;
    private final EmailVerificationService emailVerificationService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthResponseDto login(String email, String password) {
        //사용자 인증 시도
        UserEntity loginUser = userService.findByEmail(email).orElse(null);
        
        // 강퇴 계정 체크
        if(loginUser == null) {
            log.warn("User not found for email:{}", email);
            
            Optional<UserEntity> deletedUserOptional = userRepository.findByEmailWithDeleted(email);
            if(deletedUserOptional.isPresent() && deletedUserOptional.get().getBanCount() > 0) {
                log.warn("강퇴 계정 로그인 시도: email={}, banCount={}", email, deletedUserOptional.get().getBanCount());
                throw new CustomException(ErrorCode.BANNED_USER);
            }

            throw new CustomException(ErrorCode.LOGIN_FAILED);
        }

        //비번검증
        if(!passwordEncoder.matches(password, loginUser.getPassword())){
            throw new CustomException(ErrorCode.LOGIN_FAILED);
        }

        //아메일 인증상태 검증
        if(loginUser.getAuthStatus() != AuthStatus.Y){
            throw new CustomException(ErrorCode.EMAIL_NOT_VERIFIED);
        }

        // 정지 상태 체크/해제
        if (loginUser.isSuspended() && loginUser.getSuspendedUntil() != null) {
            if (loginUser.getSuspendedUntil().isBefore(java.time.LocalDateTime.now())) {
                // 정지 기간 만료된 경우 자동 해제
                loginUser.setSuspended(false);
                loginUser.setSuspendedUntil(null);
                userRepository.save(loginUser);
                log.info("정지 해제: userId={}, email={}", loginUser.getUserId(), email);
            } else {
                // 정지 기간인 경우
                throw new CustomException(ErrorCode.USER_SUSPENDED);
            }
        }

        // 강퇴 상태 체크
        if (loginUser.getDeletedAt() != null) {
            throw new CustomException(ErrorCode.BANNED_USER);
        }

        // 2. 로그인 성공 시 access/refresh 토큰 생성
        String accessToken = jwtProvider.createAccessToken(loginUser.getUserId());
        String refreshToken = jwtProvider.createRefreshToken(loginUser.getUserId());

        // 3. Refresh Token 만료시간 설정 및 DB 저장/갱신
//        long refreshTokenExpirationTime = jwtProvider.getAccessTokenExpirationTime();
//        Instant expiryDate = Instant.now().plusMillis(refreshTokenExpirationTime);

        Instant expiryDate = jwtProvider.getRefreshTokenExpiryDate();
        refreshTokenService.saveOrUpdate(loginUser, refreshToken, expiryDate);

        // 4. 응답 DTO 생성
        TokenResponseDto tokenInfo = TokenResponseDto.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .grantType(jwtProvider.getGrantType())
                .accessTokenExpiresIn(jwtProvider.getAccessTokenExpirationTime())
                .build();

        return AuthResponseDto.builder()
                .email(loginUser.getEmail())
                .nickname(loginUser.getNickname())
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .grantType(jwtProvider.getGrantType())
                .accessTokenExpiresIn(jwtProvider.getAccessTokenExpirationTime())
                .build();
    }

    @Transactional
    public AuthResponseDto reissue(String oldRefreshToken){
        //리프레시 토큰 유효성 검증
        if(!jwtProvider.validateToken(oldRefreshToken)){
            throw new CustomException(ErrorCode.INVALID_TOKEN);
        }

        //디비에서 토큰값으로 refresh entity 조회
        RefreshTokenEntity storedToken = refreshTokenService.findByTokenValue(oldRefreshToken)
                .orElseThrow(()->new CustomException(ErrorCode.INVALID_TOKEN));

        UserEntity user = storedToken.getUser();

        //새 토큰들 생성
        Long userId = user.getUserId();
        String newAccessToken = jwtProvider.createAccessToken(userId);
        String newRefreshToken = jwtProvider.createRefreshToken(userId);
        Instant newExpiryDate = jwtProvider.getRefreshTokenExpiryDate();
        Long accessTokenExpiresIn = jwtProvider.getAccessTokenExpirationTime();

        //디비에 새 토큰으로 갱신
        refreshTokenService.saveOrUpdate(
                storedToken.getUser(),
                newRefreshToken,
                newExpiryDate
        );

        TokenResponseDto tokenInfo = TokenResponseDto.builder()
                .grantType(jwtProvider.getGrantType())
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken) // 새로 발급된 토큰 사용
                .accessTokenExpiresIn(accessTokenExpiresIn)
                .build();

        return AuthResponseDto.builder()
                .email(user.getEmail())
                .nickname(user.getNickname())
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .grantType(jwtProvider.getGrantType())
                .accessTokenExpiresIn(accessTokenExpiresIn)
                .build();
    }

    //카카오
    @Transactional
    public AuthResponseDto kakaoLogin(String code){
        //인가코드로 카카오엑세스토큰 획득
        KakaoTokenResponseDto kakaoToken = kakaoApiService.getKakaoAccessToken(code);

        //엑세스토큰으로 사용자 정보 가져오기
        KakaoUserInfoResponseDto userInfo = kakaoApiService.getKakaoUserInfo(kakaoToken.getAccessToken());

        //사용자 정보 디비에 저장, 로그인
        String userEmail = userInfo.getKakao_account().getEmail();
        String userNickname = userInfo.getKakao_account().getProfile().getNickname();

        UserEntity loginUser = userService.findOrCreateUser(userEmail, userNickname);
        if(loginUser.getAuthStatus() != AuthStatus.Y){
            loginUser.setAuthStatus(AuthStatus.Y); //카카오 로그인 유저 이메일 인증상태 Y로 변경
        }

        // 정지 상태 체크/해제
        if (loginUser.isSuspended() && loginUser.getSuspendedUntil() != null) {
            if (loginUser.getSuspendedUntil().isBefore(java.time.LocalDateTime.now())) {
                // 정지 기간 만료된 경우 자동 해제
                loginUser.setSuspended(false);
                loginUser.setSuspendedUntil(null);
                userRepository.save(loginUser);
                log.info("정지 해제: userId={}, email={}", loginUser.getUserId(), userEmail);
            } else {
                // 정지 기간인 경우
                throw new CustomException(ErrorCode.USER_SUSPENDED);
            }
        }

        // 강퇴 상태 체크
        if (loginUser.getDeletedAt() != null) {
            throw new CustomException(ErrorCode.BANNED_USER);
        }

        Long userId = loginUser.getUserId();

        //자체엑세스 리프레시 토큰 생성, 저장
        String accessToken = jwtProvider.createAccessToken(userId);
        String refreshToken = jwtProvider.createRefreshToken(userId);

        Instant expiryDate = jwtProvider.getRefreshTokenExpiryDate();
        refreshTokenService.saveOrUpdate(loginUser, refreshToken, expiryDate);

        //응담시간 dto
        TokenResponseDto tokenInfo = TokenResponseDto.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .grantType(jwtProvider.getGrantType())
                .accessTokenExpiresIn(jwtProvider.getAccessTokenExpirationTime())
                .build();
        return AuthResponseDto.builder()
                .email(userEmail)
                .nickname(userNickname)
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .grantType(jwtProvider.getGrantType())
                .accessTokenExpiresIn(jwtProvider.getAccessTokenExpirationTime())
                .build();
    }

    //네이버
    @Transactional
    public AuthResponseDto naverLogin(String code, String state){
        //네이버 엑세스토큰 획득
        NaverTokenResponseDto naverToken = naverApiService.getNaverAccessToken(code, state);

        //엑세스 토큰으로 사용자 정보 가져오기
        NaverUserInfoResponseDto userInfo = naverApiService.getNaverUserInfo(naverToken.getAccessToken());

        //사용자 정보 디비 저장, 로그인
        String userEmail = userInfo.getResponse().getEmail();
        String userNickname = userInfo.getResponse().getNickname();

        UserEntity loginUser = userService.findOrCreateUser(userEmail, userNickname);
        if(loginUser.getAuthStatus() !=AuthStatus.Y){
            loginUser.setAuthStatus((AuthStatus.Y)); //인증된 사용자로 변경
        }

        // 정지 상태 체크/해제
        if (loginUser.isSuspended() && loginUser.getSuspendedUntil() != null) {
            if (loginUser.getSuspendedUntil().isBefore(java.time.LocalDateTime.now())) {
                // 정지 기간 만료된 경우 자동 해제
                loginUser.setSuspended(false);
                loginUser.setSuspendedUntil(null);
                userRepository.save(loginUser);
                log.info("정지 해제: userId={}, email={}", loginUser.getUserId(), userEmail);
            } else {
                // 정지 기간인 경우
                throw new CustomException(ErrorCode.USER_SUSPENDED);
            }
        }

        // 강퇴 상태 체크
        if (loginUser.getDeletedAt() != null) {
            throw new CustomException(ErrorCode.BANNED_USER);
        }

        Long userId = loginUser.getUserId();

        //자체 엑세스, 리프레시 토큰 생성
        String accessToken = jwtProvider.createAccessToken(userId);
        String refreshToken = jwtProvider.createRefreshToken(userId);

        Instant expiryDate = jwtProvider.getRefreshTokenExpiryDate();
        refreshTokenService.saveOrUpdate(loginUser, refreshToken, expiryDate);

        TokenResponseDto tokenInfo = TokenResponseDto.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .grantType(jwtProvider.getGrantType())
                .accessTokenExpiresIn(jwtProvider.getAccessTokenExpirationTime())
                .build();
        return AuthResponseDto.builder()
                .email(userEmail)
                .nickname(userNickname)
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .grantType(jwtProvider.getGrantType())
                .accessTokenExpiresIn(jwtProvider.getAccessTokenExpirationTime())
                .build();
    }

    //이메일 인증
    @Transactional
    public void sendVerificationEmail(String email){
        //인증코드 생성
        String verificationCode = emailService.createVerificationCode();
        //코드, 만료시간 저장/업데이트
        emailVerificationService.saveVerificationCode(email, verificationCode);
        //이메일 발송
        String subject = "[Bid-n-BUY] 이메일 인증 코드";
        String content = "인증 코드는 [" + verificationCode + "]입니다. 5분 안에 입력해주세요.";

        emailService.sendVerificationEmail(email, verificationCode);
    }

    //이메일 인증 코드 최종 검증 auth_status업데이트
    @Transactional
    public void completeEmailVerification(String email, String inputCode){
        //EmailVerificationService 유효성 검증 위임하고 메서드 내부에서 상태 업데이트 진행
        emailVerificationService.verifyCode(email, inputCode);
        //authstatus찐 업데이트
//        UserEntity user = userRepository.findByEmail(email)
//                .orElseThrow(()->new RuntimeException("인증된 이메일을 가진 사용자를 찾을 수 없습니다."));
//        user.setAuthStatus(AuthStatus.Y);
//        userRepository.save(user);
    }
}
