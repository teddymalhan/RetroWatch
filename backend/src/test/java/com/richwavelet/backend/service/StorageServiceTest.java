package com.richwavelet.backend.service;

import com.richwavelet.backend.config.SupabaseConfig;
import okhttp3.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StorageServiceTest {

    @Mock
    private OkHttpClient httpClient;

    @Mock
    private Call call;

    @Mock
    private Response response;

    @Mock
    private ResponseBody responseBody;

    @Mock
    private SupabaseConfig supabaseConfig;

    @Mock
    private SupabaseService supabaseService;

    private StorageService storageService;

    @BeforeEach
    void setUp() {
        // Only the HTTP-backed tests need the URL; keep the stub lenient so the pure
        // helper tests below are not flagged for an unused stubbing.
        lenient().when(supabaseConfig.getSupabaseUrl()).thenReturn("https://test.supabase.co");
        storageService = new StorageService(httpClient, supabaseConfig, supabaseService);
    }

    @Test
    void testSanitizeFileName_Normal() {
        String result = storageService.sanitizeFileName("test-video.mp4");
        assertEquals("test-video.mp4", result);
    }

    @Test
    void testSanitizeFileName_WithSpecialChars() {
        String result = storageService.sanitizeFileName("test video@2024!.mp4");
        assertTrue(result.matches("^test_video_2024_\\.mp4$"));
    }

    @Test
    void testSanitizeFileName_Null() {
        String result = storageService.sanitizeFileName(null);
        assertEquals("unnamed", result);
    }

    @Test
    void testSanitizeFileName_Empty() {
        String result = storageService.sanitizeFileName("");
        assertEquals("unnamed", result);
    }

    @Test
    void testSanitizeFileName_WithAccents() {
        String result = storageService.sanitizeFileName("café-vidéo.mp4");
        assertFalse(result.contains("é"));
        assertFalse(result.contains("é"));
    }

    @Test
    void testGetFileExtension_WithExtension() {
        String result = storageService.getFileExtension("test.mp4");
        assertEquals(".mp4", result);
    }

    @Test
    void testGetFileExtension_NoExtension() {
        String result = storageService.getFileExtension("test");
        assertEquals("", result);
    }

    @Test
    void testGetFileExtension_Null() {
        String result = storageService.getFileExtension(null);
        assertEquals("", result);
    }

    @Test
    void testGetPublicUrl() {
        String result = storageService.getPublicUrl("videos", "user123/video.mp4");
        assertEquals("https://test.supabase.co/storage/v1/object/public/videos/user123/video.mp4", result);
    }

    @Test
    void testUploadVideo_Success() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.mp4",
                "video/mp4",
                "test content".getBytes()
        );

        when(httpClient.newCall(any(Request.class))).thenReturn(call);
        when(call.execute()).thenReturn(response);
        when(response.isSuccessful()).thenReturn(true);

        String result = storageService.uploadVideo("user123", file, "videos");

        assertNotNull(result);
        assertTrue(result.startsWith("user123/"));
        assertTrue(result.contains("test.mp4"));
        verify(httpClient, atLeastOnce()).newCall(any(Request.class));
    }

    @Test
    void testDeleteFromStorage_Success() throws IOException {
        when(httpClient.newCall(any(Request.class))).thenReturn(call);
        when(call.execute()).thenReturn(response);
        when(response.isSuccessful()).thenReturn(true);

        assertDoesNotThrow(() -> {
            storageService.deleteFromStorage("videos", "user123/video.mp4");
        });

        verify(httpClient).newCall(any(Request.class));
    }

    @Test
    void testDeleteFromStorage_NotFound() throws IOException {
        when(httpClient.newCall(any(Request.class))).thenReturn(call);
        when(call.execute()).thenReturn(response);
        when(response.isSuccessful()).thenReturn(false);
        when(response.code()).thenReturn(404);

        // Should not throw for 404
        assertDoesNotThrow(() -> {
            storageService.deleteFromStorage("videos", "user123/video.mp4");
        });
    }

    @Test
    void testEnsureUserFolderExists_CreatesFolder() throws IOException {
        when(httpClient.newCall(any(Request.class))).thenReturn(call);
        when(call.execute()).thenReturn(response);
        when(response.code()).thenReturn(404); // First call returns 404
        when(response.isSuccessful()).thenReturn(true); // Second call succeeds

        assertDoesNotThrow(() -> {
            storageService.ensureUserFolderExists("user123", "videos");
        });

        verify(httpClient, atLeastOnce()).newCall(any(Request.class));
    }
}

