package com.bidnbuy.server.service;

import com.bidnbuy.server.dto.ChatMessageDto;
import com.bidnbuy.server.entity.*;
import com.bidnbuy.server.enums.ImageType;
import com.bidnbuy.server.repository.*;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;


@Service
@RequiredArgsConstructor
public class ImageService {


    private final ImageRepository imageRepository;
    private final AuctionProductsRepository auctionProductsRepository;
    private final UserRepository userRepository;
    private final S3UploadService s3UploadService;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final ChatMessageService chatMessageService;
    private final SimpMessagingTemplate messagingTemplate;


    // 경매 상품 이미지를 S3에 저장하고 S3 URL을 반환
    public String uploadAuctionImage(Long auctionId, MultipartFile imageFile) {

        if (imageFile.isEmpty()) {
            throw new IllegalArgumentException("업로드할 이미지가 없습니다.");
        }

        // 1. 경매 상품 존재 확인
        auctionProductsRepository.findById(auctionId)
                .orElseThrow(() -> new EntityNotFoundException("해당 경매 아이디가 존재하지 않습니다!"));

        // 2. S3 저장 경로 설정
        String s3Directory = "auction-products/" + auctionId + "/";
        String s3ImageUrl;

        try {
            // 3UploadService를 사용하여 파일 업로드
            s3ImageUrl = s3UploadService.uploadFile(imageFile, s3Directory);
        } catch (IOException e) {
            throw new RuntimeException("경매 이미지 S3 업로드에 실패했습니다.", e);
        }

        return s3ImageUrl;
    }


    //사용자 프로필 이미지를 S3에 저장하고 DB를 갱신
    @Transactional
    public String updateProfileImage(Long userId, MultipartFile imageFile) {
        if (imageFile.isEmpty()) {
            throw new IllegalArgumentException("업로드할 이미지가 없습니다.");
        }

        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User Not Found with ID: " + userId));

        // 1. S3 저장 로직 수행
        String s3Directory = "user-profiles/";
        String newS3ImageUrl;

        try {
            // S3UploadService를 사용하여 파일 업로드
            newS3ImageUrl = s3UploadService.uploadFile(imageFile, s3Directory);
        } catch (IOException e) {
            throw new RuntimeException("프로필 이미지 S3 업로드에 실패했습니다.", e);
        }

        // 2. 기존 프로필 이미지 엔티티 조회 및 갱신/생성
        Optional<ImageEntity> existingImage = imageRepository.findByUser_UserId(userId);

        if (existingImage.isPresent()) {
            ImageEntity image = existingImage.get();

            image.setImageUrl(newS3ImageUrl); // URL 갱신
            imageRepository.save(image);
        } else {
            // 새 엔티티 생성
            ImageEntity newImage = ImageEntity.builder()
                    .user(user)
                    .auctionProduct(null)
                    .imageUrl(newS3ImageUrl)
                    .imageType(ImageType.USER)
                    .build();
            imageRepository.save(newImage);
        }

        // 3. UserEntity에도 URL을 갱신
        user.setProfileImageUrl(newS3ImageUrl);
        userRepository.save(user);

        return newS3ImageUrl;
    }

    // 채팅에서 이미지 추가
    public String  uploadChatMessageImage(Long chatRoomId,
                                         Long userId,  MultipartFile imageFile,
                                         String messageText) {
        if (imageFile.isEmpty()) {
            throw new IllegalArgumentException("업로드 이미지 없음");
        }
        ChatRoomEntity chatRoom = chatRoomRepository.findById(chatRoomId)
                .orElseThrow(()->new EntityNotFoundException("채팅방을 찾을 수 없음"));
        UserEntity sender = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("사용자를 찾을 수 없습니다, id:" + userId));


        String s3Directory = "chat-images/" + chatRoomId + "/";
        String s3ImageUrl;

        try {
            s3ImageUrl = s3UploadService.uploadFile(imageFile, s3Directory);
        } catch (IOException e) {
            throw new RuntimeException("채팅 이미지 업로드에 실패", e);
        }
        ChatMessageEntity chatMessage = ChatMessageEntity.builder()
                .chatroomId(chatRoom)
                .senderId(sender)
                .message(messageText)
                .imageUrl(s3ImageUrl)
                .messageType(ChatMessageEntity.MessageType.IMAGE)
                .isRead(false)
                .build();
        ChatMessageEntity saved = chatMessageRepository.save(chatMessage);

        chatRoom.setLastMessagePreview("사진");
        chatRoom.setLastMessageTime(saved.getCreateAt());


        return s3ImageUrl;
    }
}
