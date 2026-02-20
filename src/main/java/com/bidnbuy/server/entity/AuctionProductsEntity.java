package com.bidnbuy.server.entity;

import com.bidnbuy.server.enums.ImageType;
import com.bidnbuy.server.enums.SellingStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

// 경매상품 테이블
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Data
@Entity
@Table(name = "Auction_Products")
public class AuctionProductsEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "auction_id")
    private Long auctionId;

    // 유저테이블의 외래키 연결
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private CategoryEntity category;

    @OneToMany(mappedBy = "auctionProduct", cascade = CascadeType.ALL)
    private List<ImageEntity> images = new ArrayList<>();

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "description", nullable = false, length = 6000)
    private String description;

    @Column(name = "start_price", nullable = false)
    private Integer startPrice;

    @Column(name = "current_price", nullable = false)
    private Integer currentPrice;

    @Column(name = "min_bid_price", nullable = true)
    private Integer minBidPrice;

    @ColumnDefault("0")
    @Column(name = "bid_count", nullable = false)
    private Integer bidCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "selling_status", nullable = false)
    private SellingStatus sellingStatus;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalDateTime endTime;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "deleted_at", nullable = true) // nullable=true만 남김
    private LocalDateTime deletedAt;

    /*
     * 아래 메서드는 JPA는 객체와 DB와 매핑하지만, 영속성 컨텍스트(Persistence Context)안에서
     * 객체를 다룰 때는 객체 자체를의 상태를 직접 관리해야한다.
     * */
    public void setUser(UserEntity user) {
        this.user = user;
        if (user != null && !user.getAuctionProducts().contains(this)) {
            user.getAuctionProducts().add(this);
        }
    }

    // 아래 메서드는 데이터 무결성 문제를 방지하기 위해 사용
    public void setCategory(CategoryEntity category) {
        this.category = category;
        if(category != null && !category.getAuctionProducts().contains(this)){
            category.getAuctionProducts().add(this);
        }
    }

    public String getMainImageUrl() {
        if (this.images == null || this.images.isEmpty()) {
            return null;
        }
        return this.images.stream()
                .filter(img -> ImageType.MAIN.equals(img.getImageType()))
                .findFirst()
                .map(ImageEntity::getImageUrl)
                .orElse(null);
    }

}