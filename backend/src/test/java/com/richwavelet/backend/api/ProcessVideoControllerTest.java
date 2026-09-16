package com.richwavelet.backend.api;

import com.richwavelet.backend.dto.ProcessVideoRequest;
import com.richwavelet.backend.model.AdUpload;
import com.richwavelet.backend.model.ProcessingStatus;
import com.richwavelet.backend.model.ShaderStyle;
import com.richwavelet.backend.model.VideoUpload;
import com.richwavelet.backend.repository.AdUploadRepository;
import com.richwavelet.backend.repository.VideoUploadRepository;
import com.richwavelet.backend.service.JobQueueService;
import com.richwavelet.backend.service.ProcessingStatusService;
import com.richwavelet.backend.support.TestAuthenticationFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class ProcessVideoControllerTest {

    @Mock
    private JobQueueService jobQueueService;

    @Mock
    private ProcessingStatusService statusService;

    @Mock
    private VideoUploadRepository videoUploadRepository;

    @Mock
    private AdUploadRepository adUploadRepository;

    @Mock
    private Authentication authentication;

    @Mock
    private Jwt jwt;

    @InjectMocks
    private ProcessVideoController processVideoController;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    private String userId = "user123";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(processVideoController)
                .addFilter(new TestAuthenticationFilter(authentication), "/*")
                .build();
        objectMapper = new ObjectMapper();
        when(authentication.getPrincipal()).thenReturn(jwt);
        when(jwt.getSubject()).thenReturn(userId);
    }

    @Test
    void testProcessVideo_Success() throws Exception {
        VideoUpload video = new VideoUpload();
        video.setId(1L);
        video.setUserId(userId);

        ProcessVideoRequest request = new ProcessVideoRequest(1L, null, ShaderStyle.CRT);

        when(videoUploadRepository.findById(1L)).thenReturn(Optional.of(video));
        when(jobQueueService.hasExistingTask(userId)).thenReturn(false);

        mockMvc.perform(post("/api/protected/process-video")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.message").value("Video queued for processing"));

        verify(statusService).createStatus(anyString(), eq(userId), anyString());
        verify(jobQueueService).enqueue(any(), eq(userId), anyString());
    }

    @Test
    void testProcessVideo_VideoNotFound() throws Exception {
        ProcessVideoRequest request = new ProcessVideoRequest(999L, null, ShaderStyle.CRT);

        when(videoUploadRepository.findById(999L)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/protected/process-video")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Video not found"));

        verify(jobQueueService, never()).enqueue(any(), any(), any());
    }

    @Test
    void testProcessVideo_AccessDenied() throws Exception {
        VideoUpload video = new VideoUpload();
        video.setId(1L);
        video.setUserId("other-user");

        ProcessVideoRequest request = new ProcessVideoRequest(1L, null, ShaderStyle.CRT);

        when(videoUploadRepository.findById(1L)).thenReturn(Optional.of(video));

        mockMvc.perform(post("/api/protected/process-video")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(content().string("Access denied"));

        verify(jobQueueService, never()).enqueue(any(), any(), any());
    }

    @Test
    void testProcessVideo_WithAds_Success() throws Exception {
        VideoUpload video = new VideoUpload();
        video.setId(1L);
        video.setUserId(userId);

        AdUpload ad1 = new AdUpload();
        ad1.setId("ad-uuid-1");
        ad1.setUserId(userId);

        AdUpload ad2 = new AdUpload();
        ad2.setId("ad-uuid-2");
        ad2.setUserId(userId);

        ProcessVideoRequest request = new ProcessVideoRequest(
                1L,
                Arrays.asList("ad-uuid-1", "ad-uuid-2"),
                ShaderStyle.VHS
        );

        when(videoUploadRepository.findById(1L)).thenReturn(Optional.of(video));
        when(adUploadRepository.findById("ad-uuid-1")).thenReturn(Optional.of(ad1));
        when(adUploadRepository.findById("ad-uuid-2")).thenReturn(Optional.of(ad2));
        when(jobQueueService.hasExistingTask(userId)).thenReturn(false);

        mockMvc.perform(post("/api/protected/process-video")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted());

        verify(adUploadRepository).findById("ad-uuid-1");
        verify(adUploadRepository).findById("ad-uuid-2");
        verify(jobQueueService).enqueue(any(), eq(userId), anyString());
    }

    @Test
    void testProcessVideo_AdNotFound() throws Exception {
        VideoUpload video = new VideoUpload();
        video.setId(1L);
        video.setUserId(userId);

        ProcessVideoRequest request = new ProcessVideoRequest(
                1L,
                Arrays.asList("ad-uuid-999"),
                ShaderStyle.CRT
        );

        when(videoUploadRepository.findById(1L)).thenReturn(Optional.of(video));
        when(adUploadRepository.findById("ad-uuid-999")).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/protected/process-video")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Ad not found or access denied: ad-uuid-999"));

        verify(jobQueueService, never()).enqueue(any(), any(), any());
    }

    @Test
    void testProcessVideo_ExistingTask() throws Exception {
        VideoUpload video = new VideoUpload();
        video.setId(1L);
        video.setUserId(userId);

        ProcessVideoRequest request = new ProcessVideoRequest(1L, null, ShaderStyle.CRT);

        when(videoUploadRepository.findById(1L)).thenReturn(Optional.of(video));
        when(jobQueueService.hasExistingTask(userId)).thenReturn(true);

        mockMvc.perform(post("/api/protected/process-video")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("You already have a video being processed. Please wait."));

        verify(jobQueueService, never()).enqueue(any(), any(), any());
    }

    @Test
    void testGetProcessingStatus_Success() throws Exception {
        ProcessingStatus status = new ProcessingStatus();
        status.setId("job-123");
        status.setUserId(userId);
        status.setInfo("Processing...");

        when(statusService.getLatestStatus(userId)).thenReturn(Optional.of(status));

        mockMvc.perform(get("/api/protected/processing-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("job-123"))
                .andExpect(jsonPath("$.userId").value(userId));

        verify(statusService).getLatestStatus(userId);
    }

    @Test
    void testGetProcessingStatus_NotFound() throws Exception {
        when(statusService.getLatestStatus(userId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/protected/processing-status"))
                .andExpect(status().isNotFound());

        verify(statusService).getLatestStatus(userId);
    }

    @Test
    void testGetProcessingStatusByJobId_Success() throws Exception {
        ProcessingStatus status = new ProcessingStatus();
        status.setId("job-123");
        status.setUserId(userId);
        status.setInfo("Processing...");

        when(statusService.getStatus("job-123")).thenReturn(Optional.of(status));

        mockMvc.perform(get("/api/protected/processing-status/job-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("job-123"));

        verify(statusService).getStatus("job-123");
    }

    @Test
    void testGetProcessingStatusByJobId_AccessDenied() throws Exception {
        ProcessingStatus status = new ProcessingStatus();
        status.setId("job-123");
        status.setUserId("other-user");

        when(statusService.getStatus("job-123")).thenReturn(Optional.of(status));

        mockMvc.perform(get("/api/protected/processing-status/job-123"))
                .andExpect(status().isForbidden());

        verify(statusService).getStatus("job-123");
    }
}

