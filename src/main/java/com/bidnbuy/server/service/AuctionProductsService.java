package com.bidnbuy.server.service;

import com.bidnbuy.server.dto.*;
import com.bidnbuy.server.entity.*;
import com.bidnbuy.server.enums.AuctionStatus;
import com.bidnbuy.server.enums.ImageType;
import com.bidnbuy.server.enums.NotificationType;
import com.bidnbuy.server.enums.SellingStatus;
import com.bidnbuy.server.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuctionProductsService {

    // 불변성을 보장하기 위해 final로 사용
    private final AuctionProductsRepository auctionProductsRepository;
    private final UserRepository userRepository;
    private final CategoryRepository categoryRepository;
    private final ImageRepository imageRepository;
    private final WishlistRepository wishlistRepository;
    private final AuctionHistoryService auctionHistoryService;
    private final ImageService imageService;
    private final UserNotificationService userNotificationService;

    @Transactional
    public AuctionProductsEntity create(Long userId, CreateAuctionDto dto, List<MultipartFile> imageFiles) {

        // 1. 기본 데이터 유효성 검증
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("해당 사용자 존재하지 않습니다!"));

        CategoryEntity category = categoryRepository.findById(dto.getCategoryId())
                .orElseThrow(() -> new IllegalArgumentException("해당 카테고리가 존재하지 않습니다!"));

        // 2. 물품 등록 생성 및 기본 정보 설정
        AuctionProductsEntity auctionProducts = CreateAuctionDto.toEntity(dto);
        auctionProducts.setUser(user);
        auctionProducts.setCategory(category);
        auctionProducts.setCurrentPrice(dto.getStartPrice());
        auctionProducts.setSellingStatus(SellingStatus.PROGRESS);
        auctionProducts.setBidCount(0);

        // 3. 물품 저장 (ID를 획득하여 이미지 경로에 사용하기 위해 먼저 저장)
        AuctionProductsEntity savedProducts = auctionProductsRepository.save(auctionProducts);

        // 4. 이미지 파일 처리
        if (imageFiles != null && !imageFiles.isEmpty()) {
            List<ImageEntity> imageEntities = new ArrayList<>();

            for (int i = 0; i < imageFiles.size(); i++) {
                MultipartFile file = imageFiles.get(i);

                String permanentUrl = imageService.uploadAuctionImage(savedProducts.getAuctionId(), file);

                //  첫 번째 이미지를 MAIN 타입으로 지정, 나머지는 PRODUCT
                ImageType imageType = (i == 0) ? ImageType.MAIN : ImageType.PRODUCT;

                // ImageEntity 생성 및 영구 URL 저장
                ImageEntity imageEntity = ImageEntity.builder()
                        .auctionProduct(savedProducts)
                        .user(null)
                        .imageUrl(permanentUrl) // 영구 URL 저장
                        .imageType(imageType)
                        .build();

                imageEntities.add(imageEntity);
            }

            // Image Entity들을 DB에 일괄 저장
            imageRepository.saveAll(imageEntities);
            savedProducts.setImages(imageEntities);

            // 경매 상태 기록
            auctionHistoryService.recordStatusChange(
                    savedProducts.getAuctionId(),
                    AuctionStatus.PROGRESS
            );
        }

        // 알림 추가 - kgb
        userNotificationService.createNotification(userId, NotificationType.ALERT, "경매가 등록되었습니다.");

        return savedProducts;
    }

    // 전체 리스트 출력
    @Transactional(readOnly = true)
    public PagingResponseDto<AuctionListResponseDto> getAllAuctions(
            Long userId,
            int page,
            int size,
            Integer minPrice,
            Integer maxPrice,
            String sortBy,
            Boolean includeEnded,
            String searchKeyword,
            Long mainCategoryId,
            Long subCategoryId,
            String userEmail
    ) {

        // 상태 리스트 결정, 정렬 키, 페이지
        List<SellingStatus> statuses = getFilterStatuses(includeEnded);
        String sortKey = (sortBy != null ? sortBy.toLowerCase() : "latest");
        Pageable pageable = PageRequest.of(page, size);

        // enum -> 문자열 이름 리스트로 변환 (native IN 절 호환)
        List<String> statusNames = statuses.stream().map(Enum::name).toList();

        // 네이티브 프로젝션 쿼리 호출
        Page<com.bidnbuy.server.repository.projection.AuctionListProjection> projectionPage;
        if (userEmail != null && !userEmail.trim().isEmpty()) {
            projectionPage = auctionProductsRepository.findAuctionsByUserEmailNative(
                    userEmail,
                    searchKeyword,
                    mainCategoryId,
                    subCategoryId,
                    minPrice,
                    maxPrice,
                    statusNames,
                    sortKey,
                    pageable
            );
        } else {
            projectionPage = auctionProductsRepository.findAuctionsNative(
                    searchKeyword,
                    mainCategoryId,
                    subCategoryId,
                    minPrice,
                    maxPrice,
                    statusNames,
                    sortKey,
                    pageable
            );
        }

        // 최적화 코드
        List<Long> auctionIds = projectionPage.getContent().stream()
                .map(com.bidnbuy.server.repository.projection.AuctionListProjection::getAuctionId)
                .collect(Collectors.toList());

        Set<Long> likedAuctionIds = new HashSet<>();
        if (userId != null && !auctionIds.isEmpty()) {
            // DB를 딱 한 번만 더 호출합니다.
            List<Long> likedIds = wishlistRepository.findLikedAuctionIdsByUserIdAndAuctionIds(userId, auctionIds);
            likedAuctionIds.addAll(likedIds);
        }

        // 프로젝션 -> 응답 dto 매핑
        List<AuctionListResponseDto> dtoList = projectionPage.getContent().stream()
                .map(p -> {
                    boolean liked = likedAuctionIds.contains(p.getAuctionId());

                    return AuctionListResponseDto.builder()
                            .auctionId(p.getAuctionId())
                            .title(p.getTitle())
                            .currentPrice(p.getCurrentPrice())
                            .createdAt(p.getCreatedAt())
                            .endTime(p.getEndTime())
                            .sellingStatus(calculateSellingStatusFromDbValue(p.getSellingStatus(), p.getEndTime(), p.getStartTime()))
                            .sellerId(p.getSellerId())
                            .sellerNickname(p.getSellerNickname())
                            .mainImageUrl(p.getMainImageUrl())
                            .wishCount(p.getWishCount())
                            .liked(liked)
                            .build();
                })
                .collect(java.util.stream.Collectors.toList());

        return PagingResponseDto.<AuctionListResponseDto>builder()
                .data(dtoList)
                .totalPages(projectionPage.getTotalPages())
                .totalElements(projectionPage.getTotalElements())
                .currentPage(projectionPage.getNumber())
                .pageSize(projectionPage.getSize())
                .isFirst(projectionPage.isFirst())
                .isLast(projectionPage.isLast())
                .build();
    }

    // 상세조회
    @Transactional(readOnly = true)
    public AuctionFindDto getAuctionFind(Long auctionId, Long userId) {
        // 상세 프로젝션으로
        var proj = auctionProductsRepository.findAuctionDetailNative(auctionId)
                .orElseThrow(() -> new IllegalArgumentException("Auction Not Found with ID: " + auctionId));

        // 로그인한 유저가 찜했는지 확인
        boolean liked = false;
        if (userId != null) {
            liked = wishlistRepository.existsByUser_UserIdAndAuction_AuctionId(userId, auctionId);
        }

        // 이미지
        List<ImageDto> imageDtos = imageRepository.findAllByAuctionProduct_AuctionId(auctionId)
                .stream()
                .map(imageEntity -> ImageDto.builder()
                        .imageUrl(imageEntity.getImageUrl())
                        .imageType(imageEntity.getImageType())
                        .build())
                .collect(Collectors.toList());

        // 경매 상태 계산
        String sellingStatus = calculateSellingStatusFromDbValue(proj.getSellingStatus(), proj.getEndTime(), proj.getStartTime());

        // 찜 개수 조회
        Integer wishCount = (int) wishlistRepository.countByAuction_AuctionId(auctionId);

        // 카테고리
        CategoryEntity subCategoryEntity = categoryRepository.findById(proj.getCategoryId()).orElse(null);
        CategoryEntity mainCategoryEntity = (subCategoryEntity != null) ? subCategoryEntity.getParent() : null;
        String mainCategory = "";
        String subCategory = (subCategoryEntity != null) ? subCategoryEntity.getCategoryName() : null;
        if (mainCategoryEntity != null) {
            mainCategory = mainCategoryEntity.getCategoryName();
        } else if (subCategoryEntity != null) {
            mainCategory = subCategoryEntity.getCategoryName();
            subCategory = null;
        }

        // 판매자 정보(없을 수 있음!)
        Long sellerId = proj.getSellerId();
        String sellerNickname = (proj.getSellerNickname() != null) ? proj.getSellerNickname() : "탈퇴회원";
        String sellerProfileImageUrl = proj.getSellerProfileImageUrl();
        Double sellerTemperature = proj.getSellerTemperature();

        return AuctionFindDto.builder()
                .auctionId(proj.getAuctionId())
                .title(proj.getTitle())
                .description(proj.getDescription())
                .currentPrice(proj.getCurrentPrice())
                .minBidPrice(proj.getMinBidPrice())
                .bidCount(proj.getBidCount())
                .startTime(proj.getStartTime())
                .createdAt(proj.getCreatedAt())
                .endTime(proj.getEndTime())
                .categoryId(proj.getCategoryId())
                .categoryMain(mainCategory)
                .categorySub(subCategory)
                .sellerId(sellerId)
                .sellerNickname(sellerNickname)
                .sellerProfileImageUrl(sellerProfileImageUrl)
                .images(imageDtos)
                .sellingStatus(sellingStatus)
                .wishCount(wishCount)
                .liked(liked)
                .sellerTemperature(sellerTemperature)
                .build();
    }

    // 삭제
    @Transactional
    public void deleteAuction(Long auctionId, Long userId) {
        AuctionProductsEntity products = auctionProductsRepository.findByAuctionIdAndDeletedAtIsNull(auctionId)
                .orElseThrow(() -> new IllegalArgumentException("Auction Not Found or already deleted with ID: " + auctionId));

        Long productUserId = products.getUser().getUserId();

        if (productUserId == null || productUserId.longValue() != userId.longValue()) {
            throw new SecurityException("상품을 삭제할 권한이 없습니다. (판매자만 삭제 가능)");
        }

        products.setDeletedAt(LocalDateTime.now());

        auctionProductsRepository.save(products);
    }

    // 관리자용 삭제
    @Transactional
    public void deleteAuctionByAdmin(Long auctionId) {
        AuctionProductsEntity products = auctionProductsRepository.findByAuctionIdAndDeletedAtIsNull(auctionId)
                .orElseThrow(() -> new IllegalArgumentException("해당 경매를 찾을 수 없습니다: " + auctionId));

        products.setDeletedAt(LocalDateTime.now());
        auctionProductsRepository.save(products);

        log.info("관리자에 의해 경매 삭제: auctionId={}, title={}", auctionId, products.getTitle());
    }


    // 경매 상태 리스트 결정 메서드
    private List<SellingStatus> getFilterStatuses(Boolean includeEnded) {
        if (Boolean.TRUE.equals(includeEnded)) {
            // 종료, 완료된 상품까지 모두 포함
            return Arrays.asList(SellingStatus.BEFORE, SellingStatus.SALE, SellingStatus.PROGRESS, SellingStatus.FINISH, SellingStatus.COMPLETED);
        } else {
            // 진행 중이거나 예정인 상품만
            return Arrays.asList(SellingStatus.PROGRESS, SellingStatus.SALE, SellingStatus.BEFORE);
        }
    }

    // 정렬 기준 설정 메서드
    private Sort getSortCriteria(String sortBy) {
        return switch (sortBy != null ? sortBy.toLowerCase() : "latest") {
            case "price_desc" -> Sort.by("currentPrice").descending();
            case "price_asc" -> Sort.by("currentPrice").ascending();
            case "end_time" -> Sort.by("endTime").ascending();
            default -> Sort.by("createdAt").descending(); // 기본: 최신순
        };
    }

    // DB selling_status 문자열과 시간 필드를 바탕으로 기존 라벨 계산과 일치시키기 위한 보조
    private String calculateSellingStatusFromDbValue(String sellingStatusDb, java.time.LocalDateTime endTime, java.time.LocalDateTime startTime) {
        if (sellingStatusDb == null) return "진행중";

        switch (sellingStatusDb) {
            case "PROGRESS":
                java.time.LocalDateTime now = java.time.LocalDateTime.now();
                if (startTime != null && now.isBefore(startTime)) return "시작전";
                if (endTime != null && now.isAfter(endTime)) return "종료";
                return "진행중";
            case "SALE":
                return "진행중";
            case "BEFORE":
                return "시작전";
            case "COMPLETED":
                return "완료";
            case "FINISH":
                return "종료";
            case "DELETED":
                return "삭제";
            default:
                return "진행중";
        }
    }

    // DTO 매핑 유틸리티 (목록용) - 이 메서드는 실제 엔티티를 DTO로 변환
    private AuctionListResponseDto mapToAuctionListResponseDto(AuctionProductsEntity product, Long userId) {
        // 이 부분은 기존에 구현했던 AuctionListResponseDto 매핑 로직
        Integer wishCount = wishlistRepository.countByAuction(product);
        String mainImageUrl = imageRepository.findFirstImageUrlByAuctionId(product.getAuctionId())
                .orElse("default_product.png");

        boolean liked = false;
        if (userId != null) {
            liked = wishlistRepository.existsByUser_UserIdAndAuction_AuctionId(userId, product.getAuctionId());
        }

        return AuctionListResponseDto.builder()
                .auctionId(product.getAuctionId())
                .title(product.getTitle())
                .currentPrice(product.getCurrentPrice())
                .createdAt(product.getCreatedAt())
                .endTime(product.getEndTime())
                .sellingStatus(calculateSellingStatus(product)) // calculateSellingStatus도 클래스에 있어야 합니다.
                //  .categoryName(product.getCategory().getCategoryName())
                .sellerId(product.getUser().getUserId())
                .sellerNickname(product.getUser().getNickname())
                .mainImageUrl(mainImageUrl)
                .wishCount(wishCount)
                .liked(liked)
                .build();
    }

    // 페이징 응답 DTO 빌더 유틸리티
    private PagingResponseDto<AuctionListResponseDto> buildPagingResponse(Page<AuctionProductsEntity> auctionPage, Long userId) {
        List<AuctionListResponseDto> dtoList = auctionPage.getContent().stream()
                .map(product -> mapToAuctionListResponseDto(product, userId))
                .toList();

        return PagingResponseDto.<AuctionListResponseDto>builder()
                .data(dtoList)
                .totalPages(auctionPage.getTotalPages())
                .totalElements(auctionPage.getTotalElements())
                .currentPage(auctionPage.getNumber())
                .pageSize(auctionPage.getSize())
                .isFirst(auctionPage.isFirst())
                .isLast(auctionPage.isLast())
                .build();
    }

    //  경매 상태 계산 메서드
    public String calculateSellingStatus(AuctionProductsEntity product) {

        if (product.getDeletedAt() != null) {
            return "삭제"; // SellingStatus.DELETED에 매핑되는 문자열
        }

        return switch (product.getSellingStatus()) {
            case PROGRESS -> {
                LocalDateTime now = LocalDateTime.now();
                if (now.isBefore(product.getStartTime())) {
                    yield "시작전"; // 시작 전
                } else if (now.isAfter(product.getEndTime())) {
                    yield "종료";
                } else {
                    yield "진행중"; // 판매 중
                }
            }
            case SALE -> "진행중";
            case BEFORE -> "시작전";
            case COMPLETED -> "완료"; // 거래완료
            case FINISH -> "종료";
            case DELETED -> "삭제";
        };
    }

    @Transactional(readOnly = true)
    public AuctionProductsEntity findById(Long auctionId) {

        List<SellingStatus> allowedStatuses = List.of(SellingStatus.PROGRESS, SellingStatus.SALE,
                SellingStatus.FINISH);

        return auctionProductsRepository.findByAuctionIdAndSellingStatusIn(auctionId, allowedStatuses)
                .orElseThrow(() -> new RuntimeException("Auction product not found"));
    }

    //채팅연결
    @Transactional(readOnly = true)
    public Optional<AuctionProductsEntity> findByIdAnyway(Long auctionId) {
        return auctionProductsRepository.findById(auctionId);
    }
}