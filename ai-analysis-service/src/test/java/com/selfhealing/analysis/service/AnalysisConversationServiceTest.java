package com.selfhealing.analysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfhealing.analysis.dto.AppendConversationMessagesRequest;
import com.selfhealing.analysis.model.AnalysisResult;
import com.selfhealing.analysis.repository.AnalysisResultRepository;
import com.selfhealing.analysis.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalysisConversationServiceTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private AnalysisResultRepository analysisRepository;
    @Mock private AnalysisConversationService self;

    private AnalysisConversationService service;

    private final UUID analysisId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AnalysisConversationService(jdbcTemplate, analysisRepository, new ObjectMapper(), self);
        TenantContext.setTenantId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void appendMessages_rejectsWhenAnalysisApproved() {
        when(analysisRepository.findById(analysisId)).thenReturn(Optional.of(
                AnalysisResult.builder().id(analysisId).status(AnalysisResult.AnalysisStatus.APPROVED).build()));

        assertThatThrownBy(() -> service.appendMessages(analysisId, new AppendConversationMessagesRequest()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("read-only");
    }

    @Test
    void appendMessages_rejectsWhenAnalysisRejected() {
        when(analysisRepository.findById(analysisId)).thenReturn(Optional.of(
                AnalysisResult.builder().id(analysisId).status(AnalysisResult.AnalysisStatus.REJECTED).build()));

        assertThatThrownBy(() -> service.appendMessages(analysisId, new AppendConversationMessagesRequest()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("read-only");
    }

    @Test
    void appendMessages_invokesTrimDeleteWithMaxTwenty() {
        stubPendingAnalysis();
        stubExistingConversation();
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(1);

        AppendConversationMessagesRequest.MessageInput user = new AppendConversationMessagesRequest.MessageInput();
        user.setRole("user");
        user.setContent("hello");
        AppendConversationMessagesRequest.MessageInput assistant = new AppendConversationMessagesRequest.MessageInput();
        assistant.setRole("assistant");
        assistant.setContent("hi");

        service.appendMessages(analysisId, AppendConversationMessagesRequest.builder()
                .messages(List.of(user, assistant))
                .build());

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, atLeastOnce()).update(sqlCaptor.capture(), any(), any(), any());
        assertThat(sqlCaptor.getAllValues().stream().anyMatch(sql ->
                sql.contains("DELETE FROM analysis_conversation_messages")
                        && sql.contains("MAX(seq) - ?"))).isTrue();
    }

    @Test
    void trimOldMessages_usesConfiguredMax() {
        UUID cid = UUID.randomUUID();
        service.trimOldMessages(cid, AnalysisConversationService.MAX_STORED_MESSAGES);

        ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);
        verify(jdbcTemplate).update(anyString(), args.capture(), args.capture(), args.capture());
        assertThat(args.getAllValues()).contains(AnalysisConversationService.MAX_STORED_MESSAGES);
    }

    @Test
    void getOrCreateConversation_createsRowWhenMissing() {
        AnalysisResult analysis = AnalysisResult.builder()
                .id(analysisId)
                .status(AnalysisResult.AnalysisStatus.PENDING_APPROVAL)
                .build();
        when(analysisRepository.findById(analysisId)).thenReturn(Optional.of(analysis));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(analysisId.toString())))
                .thenReturn(List.of());
        when(self.createConversationRow(analysis)).thenReturn(conversationRow());
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(conversationId.toString()), eq(20)))
                .thenReturn(List.of());

        service.getOrCreateConversation(analysisId, 20);

        verify(self).createConversationRow(analysis);
    }

    private void stubPendingAnalysis() {
        when(analysisRepository.findById(analysisId)).thenReturn(Optional.of(
                AnalysisResult.builder().id(analysisId).status(AnalysisResult.AnalysisStatus.PENDING_APPROVAL).build()));
    }

    private void stubExistingConversation() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(analysisId.toString())))
                .thenReturn(List.of(conversationRow()));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(conversationId.toString())))
                .thenReturn(List.of(conversationRow()));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(conversationId.toString()), eq(20)))
                .thenReturn(List.of());
    }

    private AnalysisConversationService.ConversationRow conversationRow() {
        return new AnalysisConversationService.ConversationRow(
                conversationId,
                analysisId,
                "session-1",
                null,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }
}
