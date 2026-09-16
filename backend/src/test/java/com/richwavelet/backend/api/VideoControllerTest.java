package com.richwavelet.backend.api;

import com.richwavelet.backend.model.UploadStatus;
import com.richwavelet.backend.model.VideoUpload;
import com.richwavelet.backend.repository.VideoUploadRepository;
import com.richwavelet.backend.service.ObjectStorage;
import com.richwavelet.backend.support.TestAuthenticationFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class VideoControllerTest {

    @Mock
    private VideoUploadRepository videoUploadRepository;

    @Mock
    private ObjectStorage storageService;

    @Mock
    private Authentication authentication;

    @Mock
    private Jwt jwt;

    @InjectMocks
    private VideoController videoController;

    private MockMvc mockMvc;
    private String userId = "user123";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(videoController)
                .addFilter(new TestAuthenticationFilter(authentication), "/*")
                .build();
        when(authentication.getPrincipal()).thenReturn(jwt);
        when(jwt.getSubject()).thenReturn(userId);
    }

    @Test
    void testUploadVideo_Success() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test-video.mp4",
                "video/mp4",
                "test video content".getBytes()
        );

        VideoUpload savedVideo = new VideoUpload();
        savedVideo.setId(1L);
        savedVideo.setUserId(userId);
        savedVideo.setFileName("test-video.mp4");
        savedVideo.setFileUrl("http://example.com/video.mp4");
        savedVideo.setStoragePath("user123/video.mp4");
        savedVideo.setFileSize(1000L);
        savedVideo.setStatus(UploadStatus.READY);

        when(storageService.uploadVideo(anyString(), any(), anyString())).thenReturn("user123/video.mp4");
        when(storageService.getPublicUrl(anyString(), anyString())).thenReturn("http://example.com/video.mp4");
        when(storageService.sanitizeFileName(anyString())).thenReturn("test-video.mp4");
        when(videoUploadRepository.save(any(VideoUpload.class))).thenReturn(savedVideo);

        mockMvc.perform(multipart("/api/protected/videos/upload")
                        .file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.userId").value(userId));

        verify(storageService).ensureUserFolderExists(userId, "videos");
        verify(storageService).uploadVideo(userId, file, "videos");
        verify(videoUploadRepository).save(any(VideoUpload.class));
    }

    @Test
    void testUploadVideo_EmptyFile() throws Exception {
        MockMultipartFile emptyFile = new MockMultipartFile(
                "file",
                "empty.mp4",
                "video/mp4",
                new byte[0]
        );

        mockMvc.perform(multipart("/api/protected/videos/upload")
                        .file(emptyFile))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("File is empty"));

        verify(videoUploadRepository, never()).save(any());
    }

    @Test
    void testUploadVideo_FileTooLarge() throws Exception {
        byte[] largeContent = new byte[501 * 1024 * 1024]; // 501MB
        MockMultipartFile largeFile = new MockMultipartFile(
                "file",
                "large-video.mp4",
                "video/mp4",
                largeContent
        );

        mockMvc.perform(multipart("/api/protected/videos/upload")
                        .file(largeFile))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(content().string("File size exceeds maximum allowed (500MB)"));

        verify(videoUploadRepository, never()).save(any());
    }

    @Test
    void testUploadVideo_InvalidFileType() throws Exception {
        MockMultipartFile invalidFile = new MockMultipartFile(
                "file",
                "document.pdf",
                "application/pdf",
                "test content".getBytes()
        );

        mockMvc.perform(multipart("/api/protected/videos/upload")
                        .file(invalidFile))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Invalid file type. Allowed: MP4, MOV, AVI, WebM"));

        verify(videoUploadRepository, never()).save(any());
    }

    @Test
    void testGetVideos() throws Exception {
        VideoUpload video1 = new VideoUpload();
        video1.setId(1L);
        video1.setUserId(userId);
        video1.setFileName("video1.mp4");

        VideoUpload video2 = new VideoUpload();
        video2.setId(2L);
        video2.setUserId(userId);
        video2.setFileName("video2.mp4");

        List<VideoUpload> videos = Arrays.asList(video1, video2);

        when(videoUploadRepository.findByUserIdOrderByUploadedAtDesc(userId)).thenReturn(videos);

        mockMvc.perform(get("/api/protected/videos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(1L))
                .andExpect(jsonPath("$[1].id").value(2L));

        verify(videoUploadRepository).findByUserIdOrderByUploadedAtDesc(userId);
    }

    @Test
    void testGetVideo_Success() throws Exception {
        VideoUpload video = new VideoUpload();
        video.setId(1L);
        video.setUserId(userId);
        video.setFileName("test-video.mp4");

        when(videoUploadRepository.findById(1L)).thenReturn(Optional.of(video));

        mockMvc.perform(get("/api/protected/videos/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.userId").value(userId));

        verify(videoUploadRepository).findById(1L);
    }

    @Test
    void testGetVideo_NotFound() throws Exception {
        when(videoUploadRepository.findById(999L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/protected/videos/999"))
                .andExpect(status().isNotFound());

        verify(videoUploadRepository).findById(999L);
    }

    @Test
    void testGetVideo_AccessDenied() throws Exception {
        VideoUpload video = new VideoUpload();
        video.setId(1L);
        video.setUserId("other-user");
        video.setFileName("test-video.mp4");

        when(videoUploadRepository.findById(1L)).thenReturn(Optional.of(video));

        mockMvc.perform(get("/api/protected/videos/1"))
                .andExpect(status().isNotFound());

        verify(videoUploadRepository).findById(1L);
    }

    @Test
    void testDeleteVideo_Success() throws Exception {
        VideoUpload video = new VideoUpload();
        video.setId(1L);
        video.setUserId(userId);
        video.setStoragePath("user123/video.mp4");

        when(videoUploadRepository.findById(1L)).thenReturn(Optional.of(video));
        doNothing().when(storageService).deleteFromStorage(anyString(), anyString());

        mockMvc.perform(delete("/api/protected/videos/1"))
                .andExpect(status().isOk())
                .andExpect(content().string("Video deleted"));

        verify(storageService).deleteFromStorage("videos", "user123/video.mp4");
        verify(videoUploadRepository).delete(video);
    }

    @Test
    void testDeleteVideo_NotFound() throws Exception {
        when(videoUploadRepository.findById(999L)).thenReturn(Optional.empty());

        mockMvc.perform(delete("/api/protected/videos/999"))
                .andExpect(status().isNotFound());

        verify(videoUploadRepository, never()).delete(any());
    }
}

