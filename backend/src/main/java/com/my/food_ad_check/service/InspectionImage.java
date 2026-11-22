package com.my.food_ad_check.service;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;

public record InspectionImage(String contentType, byte[] bytes, String digest) {
    private static final long MAX_FILE_BYTES = 50L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 100L * 1024 * 1024;
    private static final long MAX_PIXELS = 200_000_000;

    public static List<InspectionImage> read(List<MultipartFile> uploads) throws IOException {
        if (uploads.isEmpty() || uploads.size() > 10) {
            throw invalid("이미지는 1장부터 10장까지 올릴 수 있습니다.");
        }
        if (uploads.stream().anyMatch(file -> file.getSize() > MAX_FILE_BYTES)
                || uploads.stream().mapToLong(MultipartFile::getSize).sum() > MAX_TOTAL_BYTES) {
            throw new InspectionInputException(HttpStatus.CONTENT_TOO_LARGE, "IMAGE_TOO_LARGE",
                    "이미지는 한 장당 50 MiB, 합계 100 MiB까지 올릴 수 있습니다.", null);
        }
        var images = new ArrayList<InspectionImage>();
        for (MultipartFile upload : uploads) {
            String type;
            try (var stream = upload.getInputStream(); var input = new MemoryCacheImageInputStream(stream)) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw invalid("PNG 또는 JPEG 이미지를 올려 주세요.");
                var reader = readers.next();
                try {
                    type = switch (reader.getFormatName().toLowerCase(java.util.Locale.ROOT)) {
                        case "png" -> "image/png";
                        case "jpeg" -> "image/jpeg";
                        default -> throw invalid("PNG 또는 JPEG 이미지를 올려 주세요.");
                    };
                    reader.setInput(input);
                    if ((long) reader.getWidth(0) * reader.getHeight(0) > MAX_PIXELS) {
                        throw invalid("한 장의 가로×세로는 2억 픽셀까지 지원합니다.");
                    }
                } finally {
                    reader.dispose();
                }
            } catch (IOException e) {
                throw invalid("이미지의 형식과 크기를 읽을 수 없습니다.");
            }
            byte[] bytes = upload.getBytes();
            try {
                var hash = MessageDigest.getInstance("SHA-256").digest(bytes);
                images.add(new InspectionImage(type, bytes, HexFormat.of().formatHex(hash)));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
        return List.copyOf(images);
    }

    private static InspectionInputException invalid(String message) {
        return new InspectionInputException(HttpStatus.BAD_REQUEST, "INVALID_IMAGE", message, null);
    }
}
