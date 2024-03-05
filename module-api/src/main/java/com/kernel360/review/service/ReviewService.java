package com.kernel360.review.service;

import com.kernel360.exception.BusinessException;
import com.kernel360.file.entity.File;
import com.kernel360.file.entity.FileReferType;
import com.kernel360.file.repository.FileRepository;
import com.kernel360.review.code.ReviewErrorCode;
import com.kernel360.review.dto.ReviewDto;
import com.kernel360.review.dto.ReviewSearchDto;
import com.kernel360.review.entity.Review;
import com.kernel360.review.repository.ReviewRepository;
import com.kernel360.utils.file.FileUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final FileRepository fileRepository;
    private final FileUtils fileUtils;

    @Value("${aws.s3.bucket.url}")
    private String bucketUrl;

    private static final double MAX_STAR_RATING = 5.0;
    private static final String REVIEW_DOMAIN = FileReferType.REVIEW.getDomain();
    private static final String REVIEW_CODE = FileReferType.REVIEW.getCode();

    @Transactional(readOnly = true)
    public Page<ReviewDto> getReviewsByProduct(Long productNo, String sortBy, Pageable pageable) {
        log.info("제품 리뷰 목록 조회 -> product_no {}", productNo);

        return reviewRepository.findAllByCondition(ReviewSearchDto.byProductNo(productNo, sortBy), pageable)
                               .map(review -> ReviewDto.from(review, fileRepository.findByReferenceTypeAndReferenceNo(REVIEW_CODE, review.getReviewNo())));
    }

    @Transactional(readOnly = true)
    public ReviewDto getReview(Long reviewNo) {
        Review review = reviewRepository.findByReviewNo(reviewNo);
        List<File> files = fileRepository.findByReferenceTypeAndReferenceNo(REVIEW_CODE, reviewNo);

        if (Objects.isNull(review)) {
            throw new BusinessException(ReviewErrorCode.NOT_FOUND_REVIEW);
        }

        log.info("리뷰 단건 조회 -> review_no {}", reviewNo);
        return ReviewDto.from(review, files);
    }

    @Transactional
    public Review createReview(ReviewDto reviewDto, List<MultipartFile> files) {
        isValidStarRating(reviewDto.starRating());

        Review review;

        try {
            review = reviewRepository.saveAndFlush(reviewDto.toEntity());

            if (Objects.nonNull(files)) {
                uploadFiles(files, reviewDto.productDto().productNo(), review.getReviewNo());
            }
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ReviewErrorCode.INVALID_REVIEW_WRITE_REQUEST);
        }

        log.info("리뷰 등록 -> review_no {}", review.getReviewNo());
        return review;
    }

    private void uploadFiles(List<MultipartFile> files, Long productNo, Long reviewNo) {
        files.stream().forEach(file -> {
            String path = String.join("/", REVIEW_DOMAIN, productNo.toString());
            String fileKey = fileUtils.upload(path, file);
            String fileUrl = String.join("/", bucketUrl, fileKey);

            fileRepository.save(File.of(null, file.getOriginalFilename(), fileKey, fileUrl, REVIEW_CODE, reviewNo));
        });
    }

    @Transactional
    public void updateReview(ReviewDto reviewDto) {
        isValidStarRating(reviewDto.starRating());

        try {
            reviewRepository.saveAndFlush(reviewDto.toEntity());
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ReviewErrorCode.INVALID_REVIEW_WRITE_REQUEST);
        }

        log.info("리뷰 수정 -> review_no {}", reviewDto.reviewNo());
    }

    @Transactional
    public void deleteReview(Long reviewNo) {
        reviewRepository.deleteById(reviewNo);
        log.info("리뷰 삭제 -> review_no {}", reviewNo);
    }

    private static void isValidStarRating(BigDecimal starRating) {
        if (BigDecimal.ZERO.compareTo(starRating) > 0) {
            throw new BusinessException(ReviewErrorCode.INVALID_STAR_RATING_VALUE);
        }

        if (BigDecimal.valueOf(MAX_STAR_RATING).compareTo(starRating) < 0) {
            throw new BusinessException(ReviewErrorCode.INVALID_STAR_RATING_VALUE);
        }
    }
}
