package com.bidnbuy.server.service;

import com.bidnbuy.server.dto.AuctionBidDto;
import com.bidnbuy.server.dto.BidUpdateDto;
import com.bidnbuy.server.entity.AuctionBidsEntity;
import com.bidnbuy.server.entity.AuctionProductsEntity;
import com.bidnbuy.server.entity.UserEntity;
import com.bidnbuy.server.enums.ErrorCode;
import com.bidnbuy.server.enums.SellingStatus;
import com.bidnbuy.server.exception.CustomException;
import com.bidnbuy.server.repository.AuctionBidRepository;
import com.bidnbuy.server.repository.AuctionProductsRepository;
import com.bidnbuy.server.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AuctionBidService {

    private final UserRepository userRepository;
    private final AuctionProductsRepository auctionProductsRepository;
    private final AuctionBidRepository auctionBidRepository;
    private final SimpMessagingTemplate messagingTemplate; // 웹소켓 메세지 전송을 위해 필요

    @Transactional
    public AuctionBidDto bid(Long userId, Long auctionId, Integer bidPrice) {

        // 1. 사용자 및 경매 물품 유효성 검증(커스텀 예외 적용)
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.INVALID_USER_ID));

        // 2. 경매 상태, 입찰금액 유효성 검증(커스텀 예외 적용)
        AuctionProductsEntity auctionProduct = auctionProductsRepository.findByIdWithLock(auctionId)
                .orElseThrow(() -> new CustomException(ErrorCode.AUCTION_NOT_FOUND));

        // 자신이 판매한 경매물품 입찰 금지(커스텀 예외 적용)
        Long sellerId = auctionProduct.getUser().getUserId();
        if (userId == sellerId) {
            throw new CustomException(ErrorCode.SELF_BIDDING_FORBIDDEN);
        }

        // 경매가 진행 중인지 확인
        if (auctionProduct.getSellingStatus() != SellingStatus.PROGRESS) {
            throw new CustomException(ErrorCode.AUCTION_NOT_IN_PROGRESS);
        }

        // 경매 끝났을때 입찰 막기
        LocalDateTime now = LocalDateTime.now();
        if (now.isAfter(auctionProduct.getEndTime())) {
            throw new CustomException(ErrorCode.EXPIRED_AUCTION_TIME);
        }

        // 사용자의 최소 입찰 금애이 현재 입찰 금액 비교
        Integer minBid = auctionProduct.getCurrentPrice() + auctionProduct.getMinBidPrice();
        if (bidPrice < minBid) {
            throw new CustomException(ErrorCode.INVALID_MIN_BID);
        }

        // 동시성 안전 최고가 체크
        if (bidPrice <= auctionProduct.getCurrentPrice()) {
            throw new CustomException(ErrorCode.LOWER_THAN_CURRENT_PRICE);
        }

        // 3 DB에 저장
        AuctionBidsEntity newBid = AuctionBidsEntity.builder()
                .user(user)
                .auction(auctionProduct)
                .bidPrice(bidPrice)
                .build();

        auctionBidRepository.save(newBid);

        // Lock걸리 경매 물품의 최고가와 입찰 횟수 갱신
        auctionProduct.setCurrentPrice(bidPrice);
        auctionProduct.setBidCount(auctionProduct.getBidCount() + 1);

        auctionProductsRepository.save(auctionProduct);

        // 웹소켓을 이용한 알림전송 디티오
        BidUpdateDto updateDto = BidUpdateDto.builder()
                .auctionId(auctionId)
                .currentPrice(auctionProduct.getCurrentPrice())
                .bidCount(auctionProduct.getBidCount())
                .lastBidderId(userId)
                .build();

        messagingTemplate.convertAndSend("/topic/auctions/" + auctionId, updateDto);

        return AuctionBidDto.builder()
                .bidId(newBid.getBidId())
                .userId(userId)
                .auctionId(auctionId)
                .bidPrice(bidPrice)
                .bidTime(newBid.getBidTime())
                .build();
    }

    @Transactional(readOnly = true) // ⭐️ 조회 전용 트랜잭션으로 설정
    public List<AuctionBidDto> getBidsByAuction(Long auctionId) {

        // 입찰기록 최고가 순으로 조화
        List<AuctionBidsEntity> bids = auctionBidRepository.findByAuction_AuctionIdOrderByBidPriceDesc(auctionId);

        // 조회된 Entity 리스트를 DTO 리스트로 변환
        if (bids.isEmpty()) {
            // 입찰 기록이 없는 경우 빈 리스트 반환
            return Collections.emptyList();
        }

        //  Stream을 사용하여 Entity를 DTO로 매핑
        return bids.stream()
                .map(bid -> {
                    Long userIdSafe = null;

                    if(bid.getUser() != null){
                        userIdSafe = bid.getUser().getUserId();
                    }
                    return AuctionBidDto.builder()
                            .bidId(bid.getBidId())
                            // 안전하게 가져온 userIdSafe 사용
                            .userId(userIdSafe)
                            .auctionId(auctionId)
                            .bidPrice(bid.getBidPrice())
                            .bidTime(bid.getBidTime())
                            .build();
                })
                .collect(Collectors.toList());
    }
}

